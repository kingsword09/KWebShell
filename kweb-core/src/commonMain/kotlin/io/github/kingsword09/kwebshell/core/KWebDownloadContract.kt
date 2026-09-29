package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.StateFlow

public enum class KWebDownloadStatus {
    STARTING,
    IN_PROGRESS,
    PAUSED,
    COMPLETE,
    CANCELED,
    INTERRUPTED,
    DENIED,
}

public enum class KWebDownloadCollisionPolicy {
    FAIL,
    RENAME_UNIQUE,
    REPLACE_EXISTING,
}

public enum class KWebDownloadInterruptReason {
    NONE,
    FILE,
    NETWORK,
    SERVER,
    USER_CANCELED,
    OWNER_CLOSED,
    DESTINATION_INVALID,
    FILE_EXISTS,
    INTEGRITY_MISMATCH,
    UNKNOWN,
}

public data class KWebDownloadState(
    public val id: Long,
    public val profileId: String,
    public val pageId: String?,
    public val originalUrl: String,
    public val url: String,
    public val suggestedFileName: String,
    public val fileName: String?,
    public val contentDisposition: String?,
    public val mimeType: String?,
    public val receivedBytes: Long,
    public val totalBytes: Long?,
    public val currentSpeedBytesPerSecond: Long,
    public val status: KWebDownloadStatus,
    public val interruptReason: KWebDownloadInterruptReason,
    public val sha256: String?,
    public val file: KWebDownloadFile?,
) {
    init {
        require(id > 0L) { "A download identifier must be positive." }
        require(profileId.isNotBlank() && profileId.length <= 256) {
            "A download Profile identifier must be non-blank and bounded."
        }
        require(originalUrl.isNotBlank() && url.isNotBlank()) {
            "A download URL must be non-blank."
        }
        require(suggestedFileName.isNotBlank() && suggestedFileName.encodeToByteArray().size <= 255) {
            "A suggested download name must be non-blank and bounded."
        }
        require(receivedBytes >= 0L) { "Received download bytes cannot be negative." }
        require(totalBytes == null || totalBytes >= 0L) {
            "Total download bytes cannot be negative."
        }
        require(currentSpeedBytesPerSecond >= 0L) {
            "Download speed cannot be negative."
        }
        require(totalBytes == null || receivedBytes <= totalBytes || status.isTerminal) {
            "In-progress download bytes cannot exceed the declared total."
        }
        fileName?.let {
            require(it.isNotBlank() && it.encodeToByteArray().size <= 255 &&
                !it.contains('/') && !it.contains('\\')) {
                "A final download name must be one bounded path component."
            }
        }
        sha256?.let {
            require(it.matches(SHA256_PATTERN)) { "A download SHA-256 must be lowercase hex." }
        }
        if (status == KWebDownloadStatus.COMPLETE) {
            require(file != null) { "A complete download must expose its scoped file." }
            require(interruptReason == KWebDownloadInterruptReason.NONE) {
                "A complete download cannot carry an interrupt reason."
            }
        } else {
            require(file == null) { "Only a complete download may expose a file." }
        }
    }

    public companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}

public interface KWebDownloadFile : AutoCloseable {
    public val name: String
    public val sizeBytes: Long
    public val sha256: String?

    public suspend fun read(offset: Long, length: Int): KWebDownloadReadResult

    override fun close()
}

public data class KWebDownloadReadResult(
    public val bytes: ByteArray,
    public val eof: Boolean,
) {
    init {
        require(bytes.size <= KWEB_DOWNLOAD_MAX_READ_BYTES) {
            "A download file read exceeds the bounded transfer size."
        }
    }
}

public interface KWebDownload : AutoCloseable {
    public val id: Long
    public val profileId: String
    public val pageId: String?
    public val state: StateFlow<KWebDownloadState>

    public suspend fun pause(): KWebDownloadControlResult
    public suspend fun resume(): KWebDownloadControlResult
    public suspend fun cancel(): KWebDownloadControlResult

    override fun close()
}

public enum class KWebDownloadControlOutcome {
    ACCEPTED,
    ALREADY_TERMINAL,
    OWNER_CLOSED,
}

public data class KWebDownloadControlResult(
    public val id: Long,
    public val outcome: KWebDownloadControlOutcome,
)

public val KWebDownloadStatus.isTerminal: Boolean
    get() = this == KWebDownloadStatus.COMPLETE ||
        this == KWebDownloadStatus.CANCELED ||
        this == KWebDownloadStatus.INTERRUPTED ||
        this == KWebDownloadStatus.DENIED

public const val KWEB_DOWNLOAD_MAX_READ_BYTES: Int = 1_048_576
