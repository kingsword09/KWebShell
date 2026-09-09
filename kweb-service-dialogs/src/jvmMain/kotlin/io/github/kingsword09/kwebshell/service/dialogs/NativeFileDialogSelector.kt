package io.github.kingsword09.kwebshell.service.dialogs

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.dialogs.internal.DialogsFfm
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal interface KWebFileDialogSelector {
    suspend fun select(request: KWebFileDialogRequest): Path?
    fun close()
}

internal class NativeFileDialogSelector(
    private val owner: ComposeWindow,
    library: Path,
    private val testDefaultDirectory: Path? = null,
    private val testDefaultNames: Map<KWebFileDialogMode, String> = emptyMap(),
) : KWebFileDialogSelector {
    private val lock = Any()
    private val native = try {
        DialogsFfm(library)
    } catch (error: Exception) {
        throw bindingFailure(error)
    } catch (error: LinkageError) {
        throw bindingFailure(error)
    }
    private var activeId = 0L
    private var closed = false
    private var closeFailure: Exception? = null

    internal fun isVisible(): Boolean = synchronized(lock) {
        activeId != 0L && native.poll(activeId).state() == DialogsFfm.VISIBLE
    }

    override suspend fun select(request: KWebFileDialogRequest): Path? = withContext(Dispatchers.IO) {
        val nativeRequest = if (request.defaultDirectory == null && testDefaultDirectory != null) {
            KWebFileDialogRequest(
                mode = request.mode,
                title = request.title,
                defaultDirectory = testDefaultDirectory.toString(),
                defaultName = request.defaultName ?: testDefaultNames[request.mode],
                filters = request.filters,
            )
        } else request
        val parent = onDialogsAwtThread {
            if (!owner.isShowing || !owner.isDisplayable) throw unavailable()
            owner.windowHandle
        }
        val id = synchronized(lock) {
            if (closed) throw ownerClosed()
            if (activeId != 0L) throw unavailable()
            native.start(
                parent, nativeRequest.mode.nativeId, nativeRequest.title, nativeRequest.defaultDirectory,
                nativeRequest.defaultName, nativeRequest.filters.flatMap { it.extensions }.distinct().toTypedArray(),
            ).also { activeId = it }
        }
        var failure: Throwable? = null
        try {
            while (true) {
                coroutineContext.ensureActive()
                val result = synchronized(lock) {
                    if (closed) throw ownerClosed()
                    native.poll(id)
                }
                when (result.state()) {
                    DialogsFfm.SELECTED -> return@withContext Path.of(requireNotNull(result.path()))
                    DialogsFfm.CANCELLED -> return@withContext null
                    DialogsFfm.FAILED -> throw KWebNativeException(
                        code = KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
                        details = mapOf("nativeStatus" to result.failure().toString()),
                        message = "The platform file picker could not complete the request.",
                    )
                }
                delay(10)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            synchronized(lock) {
                if (activeId == id) {
                    try {
                        finishActive()
                    } catch (error: Exception) {
                        if (failure == null) throw error else failure.addSuppressed(error)
                    }
                }
            }
        }
    }

    override fun close() = synchronized(lock) {
        closeFailure?.let { throw it }
        if (!closed) {
            closed = true
            try {
                finishActive()
                native.close()
            } catch (error: Exception) {
                closeFailure = error
                throw error
            }
        }
    }

    private fun finishActive() {
        if (activeId == 0L) return
        native.cancel(activeId)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        var interrupted = Thread.interrupted()
        try {
            while (native.poll(activeId).state() < DialogsFfm.SELECTED) {
                if (System.nanoTime() >= deadline) {
                    throw KWebNativeException(
                        code = "dialog.cancel-timeout",
                        details = emptyMap(),
                        message = "The native picker did not acknowledge cancellation.",
                    )
                }
                try { Thread.sleep(10) } catch (_: InterruptedException) { interrupted = true }
            }
            native.release(activeId)
            activeId = 0
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun ownerClosed(): KWebNativeException = KWebNativeException(
        code = KWebServiceErrorCode.OWNER_CLOSED, details = emptyMap(),
        message = "The dialog owner is closed.",
    )

    private fun unavailable(): KWebNativeException = KWebNativeException(
        code = KWebDialogsErrorCode.DIALOG_UNAVAILABLE, details = emptyMap(),
        message = "The file picker requires an available, visible ComposeWindow.",
    )
}

private fun bindingFailure(error: Throwable): KWebNativeException = KWebNativeException(
    code = KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
    details = mapOf("service" to KWebDialogs.DESCRIPTOR.id, "operation" to "open"),
    message = "The platform dialogs library is missing, incompatible, or cannot be loaded.",
    cause = error,
)

private val KWebFileDialogMode.nativeId: Int
    get() = when (this) {
        KWebFileDialogMode.OPEN -> 0
        KWebFileDialogMode.SAVE -> 1
    }
