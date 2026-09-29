package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public enum class KWebFileGrant {
    READ, WRITE, CREATE, ENUMERATE, WATCH, METADATA, COPY, MOVE,
}

public enum class KWebFileNodeKind { FILE, DIRECTORY }

public enum class KWebFileConflictPolicy { FAIL, REPLACE }

public enum class KWebFileOpenMode { READ, WRITE, READ_WRITE }

public data class KWebFileOwnerScope(
    public val engineId: String,
    public val profileId: String,
    public val pageId: String,
    public val origin: String,
    public val navigationId: Long,
) {
    init {
        if (engineId.isBlank() || profileId.isBlank() || pageId.isBlank() || origin.isBlank() || navigationId <= 0) {
            throw KWebConfigurationException(
                code = "service.policy.subject-invalid",
                details = mapOf("service" to "files"),
                message = "A file service owner scope must identify a positive navigation and a non-empty owner.",
            )
        }
    }
}

public data class KWebWorkspaceRequest(
    public val workspaceId: String,
    public val grants: Set<KWebFileGrant>,
) {
    init {
        if (!WORKSPACE_ID.matches(workspaceId) || grants.isEmpty()) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.WORKSPACE_INVALID,
                details = mapOf("workspace" to workspaceId),
                message = "A workspace request requires one safe id and at least one grant.",
            )
        }
    }
}

public data class KWebFileOpenRequest(
    public val parent: KWebFileHandle,
    public val name: String,
    public val mode: KWebFileOpenMode,
    public val createIfMissing: Boolean = false,
) {
    init {
        requireFileName(name, "name")
        if (createIfMissing && mode == KWebFileOpenMode.READ) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.IO_BOUNDS,
                details = mapOf("field" to "createIfMissing"),
                message = "A read-only file cannot request creation.",
            )
        }
    }
}

public data class KWebDirectoryOpenRequest(
    public val parent: KWebFileHandle,
    public val name: String,
    public val createIfMissing: Boolean = false,
) {
    init {
        requireFileName(name, "name")
    }
}

/** A capability token has no public constructor and is not a serializable DTO. */
public class KWebFileHandle internal constructor(public val token: String) {
    init {
        if (!HANDLE_PATTERN.matches(token)) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.HANDLE_INVALID,
                details = emptyMap(),
                message = "The file capability token is invalid.",
            )
        }
    }

    public companion object {
        internal fun fromBridge(token: String): KWebFileHandle = KWebFileHandle(token)
    }
}

public data class KWebFileCapabilityDescriptor(
    public val handle: String,
    public val kind: KWebFileNodeKind,
    public val name: String,
    public val grants: Set<KWebFileGrant>,
) {
    init {
        KWebFileHandle(handle)
        requireFileName(name, "name")
        if (grants.isEmpty()) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.HANDLE_GRANT,
                details = mapOf("field" to "grants"),
                message = "A capability descriptor must retain at least one grant.",
            )
        }
    }
}

public class KWebFileReadResult(bytes: ByteArray, public val eof: Boolean) {
    private val snapshot: ByteArray = bytes.copyOf()
    public val bytes: ByteArray get() = snapshot.copyOf()

    init {
        if (bytes.size > KWEB_FILES_MAX_TRANSFER_BYTES) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.IO_BOUNDS,
                details = mapOf("bytes" to bytes.size.toString()),
                message = "A file read result exceeds the bounded transfer size.",
            )
        }
    }
}

public data class KWebFileWriteResult(public val written: Int) {
    init {
        if (written !in 0..KWEB_FILES_MAX_TRANSFER_BYTES) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.IO_BOUNDS,
                details = mapOf("written" to written.toString()),
                message = "A file write result exceeds the bounded transfer size.",
            )
        }
    }
}

public data class KWebFileMetadata(
    public val kind: KWebFileNodeKind,
    public val name: String,
    public val sizeBytes: Long?,
    public val lastModifiedEpochMillis: Long,
) {
    init {
        requireFileName(name, "name")
        if (sizeBytes != null && sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.IO_BOUNDS,
                details = mapOf("field" to "metadata"),
                message = "File metadata contains a negative value.",
            )
        }
    }
}

public data class KWebDirectoryEntry(
    public val name: String,
    public val kind: KWebFileNodeKind,
    public val sizeBytes: Long?,
    public val lastModifiedEpochMillis: Long,
) {
    init {
        requireFileName(name, "name")
        if (sizeBytes != null && sizeBytes < 0 || lastModifiedEpochMillis < 0) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.IO_BOUNDS,
                details = mapOf("field" to "entry"),
                message = "Directory metadata contains a negative value.",
            )
        }
    }
}

public data class KWebDirectoryListing(public val entries: List<KWebDirectoryEntry>, public val truncated: Boolean) {
    init {
        if (entries.size > KWEB_FILES_MAX_DIRECTORY_ENTRIES) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.ENUMERATION_LIMIT,
                details = mapOf("entries" to entries.size.toString()),
                message = "A directory listing exceeds the bounded entry limit.",
            )
        }
    }
}

public enum class KWebFileWatchKind { CREATED, MODIFIED, DELETED, OVERFLOW }

public data class KWebFileWatchEvent(
    public val sequence: Long,
    public val kind: KWebFileWatchKind,
    public val name: String?,
) {
    init {
        if (sequence <= 0 || (kind == KWebFileWatchKind.OVERFLOW && name != null)) {
            throw KWebConfigurationException(
                code = KWebFilesErrorCode.WATCH_OVERFLOW,
                details = mapOf("sequence" to sequence.toString()),
                message = "A file watch event is invalid.",
            )
        }
        name?.let { requireFileName(it, "name") }
    }
}

public interface KWebFiles : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun openWorkspace(request: KWebWorkspaceRequest): KWebFileCapabilityDescriptor
    public suspend fun openFile(request: KWebFileOpenRequest): KWebFileCapabilityDescriptor
    public suspend fun openDirectory(request: KWebDirectoryOpenRequest): KWebFileCapabilityDescriptor
    public suspend fun readFile(handle: KWebFileHandle, offset: Long, length: Int): KWebFileReadResult
    public suspend fun writeFile(handle: KWebFileHandle, offset: Long, bytes: ByteArray): KWebFileWriteResult
    public suspend fun truncateFile(handle: KWebFileHandle, sizeBytes: Long): Long
    public suspend fun listDirectory(handle: KWebFileHandle, limit: Int): KWebDirectoryListing
    public suspend fun metadata(handle: KWebFileHandle): KWebFileMetadata
    public suspend fun copyFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor
    public suspend fun moveFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor
    public fun watchDirectory(handle: KWebFileHandle): Flow<KWebFileWatchEvent>
    public suspend fun closeHandle(handle: KWebFileHandle)

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "files",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.PAGE,
            operations = setOf(
                operation("open-workspace", requiresUserGesture = true),
                operation("open-file"),
                operation("open-directory"),
                operation("read-file"),
                operation("write-file"),
                operation("truncate-file"),
                operation("list-directory"),
                operation("metadata"),
                operation("copy-file"),
                operation("move-file"),
                operation("watch-directory"),
                operation("close-handle"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebFiles> = object : KWebServiceKey<KWebFiles> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(id: String, requiresUserGesture: Boolean = false): KWebServiceOperationDescriptor =
            KWebServiceOperationDescriptor(
                id = id,
                schemaVersion = 1,
                rendererPermission = "native.files.$id",
                requiresUserGesture = requiresUserGesture,
            )
    }
}

public object KWebFilesErrorCode {
    public const val WORKSPACE_INVALID: String = "files.workspace-invalid"
    public const val WORKSPACE_NOT_ALLOWED: String = "files.workspace-not-allowed"
    public const val HANDLE_INVALID: String = "files.handle-invalid"
    public const val HANDLE_NOT_FOUND: String = "files.handle-not-found"
    public const val HANDLE_KIND: String = "files.handle-kind"
    public const val HANDLE_GRANT: String = "files.handle-grant"
    public const val NAME_INVALID: String = "files.name-invalid"
    public const val ROOT_ESCAPE: String = "files.root-escape"
    public const val IO_BOUNDS: String = "files.io-bounds"
    public const val ENUMERATION_LIMIT: String = "files.enumeration-limit"
    public const val CONFLICT: String = "files.conflict"
    public const val ATOMIC_MOVE_UNAVAILABLE: String = "files.atomic-move-unavailable"
    public const val WATCH_OVERFLOW: String = "files.watch-overflow"
    public const val PLATFORM_UNAVAILABLE: String = "files.platform-unavailable"
}

public const val KWEB_FILES_MAX_TRANSFER_BYTES: Int = 1_048_576
public const val KWEB_FILES_MAX_DIRECTORY_ENTRIES: Int = 4_096
public const val KWEB_FILES_WATCH_CAPACITY: Int = 64

private val WORKSPACE_ID = Regex("[a-z][a-z0-9-]{0,63}")
private val HANDLE_PATTERN = Regex("[A-Za-z0-9_-]{43}")
private val FILE_NAME_PATTERN = Regex("[^/\\\\\\u0000-\\u001f\\u007f]{1,255}")

internal fun requireFileName(value: String, field: String) {
    if (!FILE_NAME_PATTERN.matches(value) || value == "." || value == ".." ||
        value.endsWith('.') || value.endsWith(' ') || value.any { it in "<>:\"|?*" }
    ) {
        throw KWebConfigurationException(
            code = KWebFilesErrorCode.NAME_INVALID,
            details = mapOf("field" to field),
            message = "The file name must be one portable path component.",
        )
    }
    val device = value.substringBefore('.').uppercase()
    if (device in setOf("CON", "PRN", "AUX", "NUL") || device.matches(Regex("COM[1-9]") ) ||
        device.matches(Regex("LPT[1-9]"))) {
        throw KWebConfigurationException(
            code = KWebFilesErrorCode.NAME_INVALID,
            details = mapOf("field" to field),
            message = "The file name is a reserved device name.",
        )
    }
}
