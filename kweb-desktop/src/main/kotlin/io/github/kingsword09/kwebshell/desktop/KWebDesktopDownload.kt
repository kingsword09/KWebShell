package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebDownload
import io.github.kingsword09.kwebshell.core.KWebDownloadCollisionPolicy
import io.github.kingsword09.kwebshell.core.KWebDownloadControlOutcome
import io.github.kingsword09.kwebshell.core.KWebDownloadControlResult
import io.github.kingsword09.kwebshell.core.KWebDownloadFile
import io.github.kingsword09.kwebshell.core.KWebDownloadInterruptReason
import io.github.kingsword09.kwebshell.core.KWebDownloadReadResult
import io.github.kingsword09.kwebshell.core.KWebDownloadState
import io.github.kingsword09.kwebshell.core.KWebDownloadStatus
import io.github.kingsword09.kwebshell.core.KWEB_DOWNLOAD_MAX_READ_BYTES
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.isTerminal
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowser
import io.github.kingsword09.kwebshell.desktop.internal.NativeStatus
import io.github.kingsword09.kwebshell.desktop.internal.downloadStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class KWebDesktopDownload(
    private val owner: KWebDesktopProfile,
    private val native: NativeBrowser,
    override val id: Long,
    override val profileId: String,
    override val pageId: String?,
    initial: KWebDesktopDownloadUpdate,
) : KWebDownload {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val mutableState = MutableStateFlow(owner.initialDownloadState(id, pageId, initial))
    private var file: KWebDownloadFile? = null
    private var lastStagingPath: String? = initial.stagingPath

    override val state: StateFlow<KWebDownloadState> = mutableState.asStateFlow()

    internal fun apply(update: KWebDesktopDownloadUpdate) {
        synchronized(lock) {
            if (closed.get() || mutableState.value.status.isTerminal) return
            if (update.id != id) {
                throw KWebNativeException(
                    code = "download.event-invalid",
                    details = mapOf("downloadId" to id.toString()),
                    message = "A native update targeted a different download object.",
                )
            }
            val previous = mutableState.value
            update.stagingPath?.let { lastStagingPath = it }
            if (update.receivedBytes < previous.receivedBytes) {
                throw KWebNativeException(
                    code = "download.event-invalid",
                    details = mapOf("downloadId" to id.toString()),
                    message = "Native download progress regressed.",
                )
            }
            if (previous.totalBytes != null && update.totalBytes != null &&
                previous.totalBytes != update.totalBytes
            ) {
                throw KWebNativeException(
                    code = "download.event-invalid",
                    details = mapOf("downloadId" to id.toString()),
                    message = "Native download total bytes changed.",
                )
            }
            val next = if (update.status == "complete") {
                owner.finalizeDownload(
                    id,
                    pageId,
                    update.copy(stagingPath = update.stagingPath ?: lastStagingPath),
                )
            } else {
                if (update.status == "canceled" || update.status == "interrupted" || update.status == "denied") {
                    owner.deleteStaging(update.stagingPath)
                }
                owner.stateFromUpdate(id, pageId, update, null, null)
            }
            if (next.status == KWebDownloadStatus.COMPLETE) file = next.file
            mutableState.value = next
        }
    }

    override suspend fun pause(): KWebDownloadControlResult = control(DOWNLOAD_PAUSE)

    override suspend fun resume(): KWebDownloadControlResult = control(DOWNLOAD_RESUME)

    override suspend fun cancel(): KWebDownloadControlResult = control(DOWNLOAD_CANCEL)

    private suspend fun control(operation: Int): KWebDownloadControlResult = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (closed.get()) return@withContext KWebDownloadControlResult(id, KWebDownloadControlOutcome.OWNER_CLOSED)
            if (mutableState.value.status.isTerminal) {
                return@withContext KWebDownloadControlResult(id, KWebDownloadControlOutcome.ALREADY_TERMINAL)
            }
        }
        val status = native.controlDownload(id, operation)
        when (status) {
            NativeStatus.OK.value -> KWebDownloadControlResult(id, KWebDownloadControlOutcome.ACCEPTED)
            NativeStatus.DOWNLOAD_ALREADY_TERMINAL.value ->
                KWebDownloadControlResult(id, KWebDownloadControlOutcome.ALREADY_TERMINAL)
            else -> throw downloadStatusException("download-control", status, mapOf("downloadId" to id.toString()))
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val live = synchronized(lock) { !mutableState.value.status.isTerminal }
        if (live) runCatching { native.controlDownload(id, DOWNLOAD_CANCEL) }
        synchronized(lock) { file?.close() }
    }

    internal fun markOwnerClosed() {
        synchronized(lock) {
            if (mutableState.value.status.isTerminal) return
            owner.deleteStaging(lastStagingPath)
            mutableState.value = mutableState.value.copy(
                status = KWebDownloadStatus.CANCELED,
                interruptReason = KWebDownloadInterruptReason.OWNER_CLOSED,
            )
        }
    }

    private companion object {
        const val DOWNLOAD_CANCEL = 1
        const val DOWNLOAD_PAUSE = 2
        const val DOWNLOAD_RESUME = 3
    }
}

internal class KWebDesktopDownloadFile(
    private val path: Path,
    override val name: String,
    override val sizeBytes: Long,
    override val sha256: String?,
) : KWebDownloadFile {
    private val closed = AtomicBoolean(false)

    override suspend fun read(offset: Long, length: Int): KWebDownloadReadResult = withContext(Dispatchers.IO) {
        if (closed.get()) {
            throw KWebNativeException(
                code = "download.file-closed",
                details = mapOf("name" to name),
                message = "The completed download file capability is closed.",
            )
        }
        if (offset < 0 || length !in 1..KWEB_DOWNLOAD_MAX_READ_BYTES || offset > sizeBytes) {
            throw KWebNativeException(
                code = "download.file-read-bounds",
                details = mapOf("offset" to offset.toString(), "length" to length.toString()),
                message = "The download file read is outside the bounded capability.",
            )
        }
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw KWebNativeException(
                code = "download.file-closed",
                details = mapOf("name" to name),
                message = "The completed download file is no longer a regular file.",
            )
        }
        val buffer = ByteBuffer.allocate(length)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(offset)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) <= 0) break
            }
        }
        val bytes = buffer.array().copyOf(buffer.position())
        KWebDownloadReadResult(bytes, offset + bytes.size >= sizeBytes)
    }

    override fun close() {
        closed.set(true)
    }
}

internal fun mapDownloadStatus(value: String): KWebDownloadStatus = when (value) {
    "starting" -> KWebDownloadStatus.STARTING
    "in-progress" -> KWebDownloadStatus.IN_PROGRESS
    "paused" -> KWebDownloadStatus.PAUSED
    "complete" -> KWebDownloadStatus.COMPLETE
    "canceled" -> KWebDownloadStatus.CANCELED
    "interrupted" -> KWebDownloadStatus.INTERRUPTED
    "denied" -> KWebDownloadStatus.DENIED
    else -> error("Unknown download status '$value'.")
}

internal fun mapDownloadInterruptReason(value: Int): KWebDownloadInterruptReason = when {
    value == 0 -> KWebDownloadInterruptReason.NONE
    value in 1..15 -> KWebDownloadInterruptReason.FILE
    value in 20..24 -> KWebDownloadInterruptReason.NETWORK
    value in 30..39 -> KWebDownloadInterruptReason.SERVER
    value == 40 || value == 41 -> KWebDownloadInterruptReason.USER_CANCELED
    value == 100 || value == 101 || value == 102 -> KWebDownloadInterruptReason.DESTINATION_INVALID
    else -> KWebDownloadInterruptReason.UNKNOWN
}

internal fun sha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path, StandardOpenOption.READ).use { input ->
        val buffer = ByteArray(128 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun deleteQuietly(path: Path?) {
    if (path == null) return
    runCatching { Files.deleteIfExists(path) }
}

internal fun safeDownloadName(value: String): String? {
    if (value.isBlank() || value.encodeToByteArray().size > 255 || value == "." || value == ".." ||
        value.endsWith('.') || value.endsWith(' ') || value.contains('/') || value.contains('\\') ||
        value.any { it.code < 32 || it.code == 127 || it in "<>:\"|?*" }
    ) return null
    val stem = value.substringBefore('.').uppercase()
    if (stem in setOf("CON", "PRN", "AUX", "NUL") ||
        (stem.length == 4 && stem.substring(0, 3) in setOf("COM", "LPT") && stem[3] in '1'..'9')
    ) return null
    return value
}

internal fun uniqueDownloadName(name: String, index: Int): String {
    val extension = name.substringAfterLast('.', "")
    val base = if (extension.isEmpty()) name else name.removeSuffix(".$extension")
    val suffix = " ($index)"
    val resultBase = base.take((255 - suffix.length - if (extension.isEmpty()) 0 else extension.length + 1).coerceAtLeast(1))
    return if (extension.isEmpty()) "$resultBase$suffix" else "$resultBase$suffix.$extension"
}
