package io.github.kingsword09.kwebshell.service.dialogs

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.OpenOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.coroutineContext

public object JvmKWebDialogs {
    public fun open(owner: ComposeWindow, libraryPath: Path): KWebDialogs = onDialogsAwtThread {
        requireVisibleOwner(owner)
        ComposeKWebDialogs(owner, NativeFileDialogSelector(owner, libraryPath))
    }

    internal fun openForTesting(
        owner: ComposeWindow,
        selector: KWebFileDialogSelector,
        beforeSelectionDelivery: (suspend () -> Unit)? = null,
    ): KWebDialogs =
        onDialogsAwtThread {
            requireVisibleOwner(owner)
            ComposeKWebDialogs(owner, selector, beforeSelectionDelivery)
        }
}

private class ComposeKWebDialogs(
    private val owner: ComposeWindow,
    private val selector: KWebFileDialogSelector,
    private val beforeSelectionDelivery: (suspend () -> Unit)? = null,
) : KWebDialogs {
    // Selection publication, all channel operations, and close share one order.
    private val lock = Any()
    private val closeLock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val handles = linkedMapOf<String, OpenFileHandle>()
    private var selectionActive = false
    private var closeFailure: KWebNativeException? = null
    private val ownerListener = object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) {
            try {
                close()
            } catch (_: KWebNativeException) {
                // FAILED and the original failure remain observable through close().
            }
        }
    }

    init {
        check(EventQueue.isDispatchThread())
        owner.addWindowListener(ownerListener)
    }

    override val descriptor = KWebDialogs.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun selectFile(request: KWebFileDialogRequest): KWebFileSelection? {
        synchronized(lock) {
            requireOpen("select-file")
            if (selectionActive || handles.size >= MAX_OPEN_HANDLES) {
                throw ioFailure(KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
                    "Another selection is active or the open handle limit has been reached.", "select-file")
            }
            selectionActive = true
        }
        var opened: OpenFileHandle? = null
        var delivered = false
        try {
            withContext(Dispatchers.IO) { validateDirectory(request.defaultDirectory) }
            val selected = selector.select(request) ?: return null
            val result = withContext(Dispatchers.IO) {
                synchronized(lock) {
                    coroutineContext.ensureActive()
                    requireOpen("select-file")
                    openSelected(request, selected).also {
                        opened = it
                        handles[it.selection.handle] = it
                    }.selection
                }
            }
            beforeSelectionDelivery?.invoke()
            delivered = true
            return result
        } catch (error: CancellationException) {
            throw error
        } catch (error: KWebException) {
            throw error
        } catch (error: Exception) {
            throw ioFailure(KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
                "The native file dialog did not return a usable selection.", "select-file", error)
        } finally {
            synchronized(lock) {
                selectionActive = false
                // withContext may discard a completed result on cancellation.
                if (!delivered) opened?.let { file ->
                    if (handles.remove(file.selection.handle) != null) closeChannel(file, "select-file")
                }
            }
        }
    }

    override suspend fun readFile(handle: String, offset: Long, length: Int): KWebFileReadResult =
        io("read-file") {
            validateRange(offset, length, "read-file")
            val file = requireHandle(handle, KWebFileDialogMode.OPEN, "read-file")
            val buffer = ByteBuffer.allocate(length)
            var total = 0
            while (buffer.hasRemaining()) {
                val count = file.channel.read(buffer, offset + total)
                if (count < 0) break
                if (count == 0) throw ioFailure(KWebServiceErrorCode.NATIVE_FAILED,
                    "The file read made no progress.", "read-file")
                total += count
            }
            KWebFileReadResult(buffer.array().take(total).map { it.toInt() and 0xff },
                offset + total >= file.channel.size())
        }

    override suspend fun writeFile(handle: String, offset: Long, bytes: List<Int>): KWebFileWriteResult {
        validateRange(offset, bytes.size, "write-file")
        val snapshot = bytes.toList()
        return io("write-file") {
            validateRange(offset, snapshot.size, "write-file")
            if (snapshot.any { it !in 0..255 }) throw ioFailure(KWebDialogsErrorCode.IO_BOUNDS,
                "The file write contains a value outside the byte range.", "write-file")
            val file = requireHandle(handle, KWebFileDialogMode.SAVE, "write-file")
            val buffer = ByteBuffer.wrap(snapshot.map { it.toByte() }.toByteArray())
            var total = 0
            while (buffer.hasRemaining()) {
                val count = file.channel.write(buffer, offset + total)
                if (count <= 0) throw ioFailure(KWebServiceErrorCode.NATIVE_FAILED,
                    "The file write made no progress.", "write-file")
                total += count
            }
            KWebFileWriteResult(total)
        }
    }

    override suspend fun truncateFile(handle: String, sizeBytes: Long): KWebFileTruncateResult =
        io("truncate-file") {
            if (sizeBytes < 0) throw ioFailure(KWebDialogsErrorCode.IO_BOUNDS,
                "The file size cannot be negative.", "truncate-file")
            val file = requireHandle(handle, KWebFileDialogMode.SAVE, "truncate-file")
            file.channel.truncate(sizeBytes)
            KWebFileTruncateResult(file.channel.size())
        }

    override suspend fun closeFile(handle: String): Unit = io("close-file") {
        val file = requireHandle(handle, null, "close-file")
        closeChannel(file, "close-file")
        handles.remove(handle)
    }

    override fun close(): Unit = synchronized(closeLock) {
        synchronized(lock) {
            closeFailure?.let { throw it }
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        var failure: Exception? = null
        try {
            selector.close()
        } catch (error: Exception) {
            failure = error
        }
        synchronized(lock) {
            handles.values.forEach { file ->
                try {
                    closeChannel(file, "close")
                } catch (error: Exception) {
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            handles.clear()
            closeFailure = failure?.let {
                ioFailure(KWebServiceErrorCode.NATIVE_FAILED,
                    "The dialog service could not release all native resources.", "close", it)
            }
            mutableLifecycle.value = if (failure == null) KWebLifecycleState.CLOSED else KWebLifecycleState.FAILED
        }
        // Do not wait for AWT while an owner-close event may itself be calling close.
        if (EventQueue.isDispatchThread()) owner.removeWindowListener(ownerListener)
        else EventQueue.invokeLater { owner.removeWindowListener(ownerListener) }
        closeFailure?.let { throw it }
    }

    private suspend fun <T> io(operation: String, action: () -> T): T = withContext(Dispatchers.IO) {
        synchronized(lock) {
            coroutineContext.ensureActive()
            requireOpen(operation)
            try {
                action()
            } catch (error: KWebException) {
                throw error
            } catch (error: Exception) {
                throw ioFailure(KWebServiceErrorCode.NATIVE_FAILED,
                    "The selected file operation failed.", operation, error)
            }
        }
    }

    private fun openSelected(request: KWebFileDialogRequest, selected: Path): OpenFileHandle {
        if (!selected.isAbsolute || selected.fileName == null) throw ioFailure(
            KWebDialogsErrorCode.PATH_INVALID, "The native selection is not an absolute file path.", "select-file")
        val name = selected.fileName.toString()
        try {
            requireFileName(name, "selection.name")
        } catch (error: KWebException) {
            throw ioFailure(KWebDialogsErrorCode.PATH_INVALID, "The selected file name is invalid.", "select-file", error)
        }
        if (request.filters.isNotEmpty() && request.filters.none { filter ->
                filter.extensions.any { name.lowercase().endsWith(".$it") }
            }) throw ioFailure(KWebDialogsErrorCode.PATH_INVALID,
            "The selected file does not match the declared filters.", "select-file")
        try {
            val parent = requireNotNull(selected.parent).toRealPath()
            val path = parent.resolve(name)
            if (!Files.isDirectory(parent, NOFOLLOW_LINKS) || Files.isSymbolicLink(path) ||
                (Files.exists(path, NOFOLLOW_LINKS) && !Files.isRegularFile(path, NOFOLLOW_LINKS)) ||
                (request.mode == KWebFileDialogMode.OPEN && !Files.isRegularFile(path, NOFOLLOW_LINKS))) {
                throw ioFailure(KWebDialogsErrorCode.PATH_INVALID,
                    "The selection must be a regular file, not a directory or symbolic link.", "select-file")
            }
            val options = when (request.mode) {
                KWebFileDialogMode.OPEN -> arrayOf<OpenOption>(StandardOpenOption.READ, NOFOLLOW_LINKS)
                KWebFileDialogMode.SAVE -> arrayOf<OpenOption>(StandardOpenOption.CREATE, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
            }
            val channel = FileChannel.open(path, *options)
            try {
                if (parent.toRealPath() != parent || !Files.isRegularFile(path, NOFOLLOW_LINKS)) {
                    throw ioFailure(KWebDialogsErrorCode.PATH_INVALID,
                        "The selected path changed while it was being opened.", "select-file")
                }
                return OpenFileHandle(channel,
                    KWebFileSelection(nextToken(), name, channel.size(), request.mode))
            } catch (error: Throwable) {
                try { channel.close() } catch (closeError: Exception) { error.addSuppressed(closeError) }
                throw error
            }
        } catch (error: KWebException) {
            throw error
        } catch (error: Exception) {
            throw ioFailure(KWebDialogsErrorCode.PATH_INVALID,
                "The selected file could not be opened.", "select-file", error)
        }
    }

    private fun requireHandle(token: String, mode: KWebFileDialogMode?, operation: String): OpenFileHandle {
        if (!HANDLE_PATTERN.matches(token)) throw ioFailure(KWebDialogsErrorCode.HANDLE_INVALID,
            "The file handle token is invalid.", operation)
        val file = handles[token] ?: throw ioFailure(KWebDialogsErrorCode.HANDLE_NOT_FOUND,
            "The file handle is not open.", operation)
        if (mode != null && file.selection.mode != mode) throw ioFailure(KWebDialogsErrorCode.HANDLE_MODE,
            "The file handle does not permit this operation.", operation)
        return file
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) throw ioFailure(KWebServiceErrorCode.OWNER_CLOSED,
            "The KWebDialogs service is not open.", operation)
    }

    private fun nextToken(): String {
        var token: String
        do {
            val bytes = ByteArray(32)
            RANDOM.nextBytes(bytes)
            token = ENCODER.encodeToString(bytes)
        } while (handles.containsKey(token))
        return token
    }

    private class OpenFileHandle(val channel: FileChannel, val selection: KWebFileSelection)

    private fun closeChannel(file: OpenFileHandle, operation: String) {
        try {
            file.channel.close()
        } catch (error: Exception) {
            throw ioFailure(KWebServiceErrorCode.NATIVE_FAILED,
                "The file handle could not be closed.", operation, error)
        }
    }

    companion object {
        private const val MAX_OPEN_HANDLES = 64
        private val RANDOM = SecureRandom()
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val HANDLE_PATTERN = Regex("[A-Za-z0-9_-]{43}")
    }
}

private fun validateDirectory(directory: String?) {
    if (directory == null) return
    try {
        val path = Path.of(directory)
        if (path.isAbsolute && Files.isDirectory(path, NOFOLLOW_LINKS)) return
    } catch (error: Exception) {
        throw ioFailure(KWebDialogsErrorCode.PATH_INVALID, "The default directory is invalid.", "select-file", error)
    }
    throw ioFailure(KWebDialogsErrorCode.PATH_INVALID,
        "The default directory must be an existing absolute directory, not a symbolic link.", "select-file")
}

private fun validateRange(offset: Long, length: Int, operation: String) {
    if (offset < 0 || length !in 0..KWEB_DIALOGS_MAX_TRANSFER_BYTES || offset > Long.MAX_VALUE - length) {
        throw ioFailure(KWebDialogsErrorCode.IO_BOUNDS,
            "The file offset or transfer length is outside its supported range.", operation)
    }
}

private fun requireVisibleOwner(owner: ComposeWindow) {
    if (!owner.isDisplayable || !owner.isShowing) throw KWebConfigurationException(
        code = KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
        details = mapOf("service" to KWebDialogs.DESCRIPTOR.id),
        message = "The ComposeWindow owner must be displayable and showing.",
    )
}

private fun ioFailure(code: String, message: String, operation: String, cause: Throwable? = null): KWebNativeException =
    KWebNativeException(code, mapOf("service" to KWebDialogs.DESCRIPTOR.id, "operation" to operation), message, cause)
