package io.github.kingsword09.kwebshell.service.dialogs

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import kotlinx.coroutines.flow.StateFlow

public enum class KWebFileDialogMode(public val id: String) {
    OPEN("open"),
    SAVE("save"),
    ;

    public companion object {
        public fun fromId(id: String): KWebFileDialogMode = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("mode" to id),
                message = "The requested file dialog mode is not published.",
            )
    }
}

public class KWebFileFilter(
    description: String,
    extensions: Collection<String>,
) {
    public val description: String = description
    private val extensionSnapshot = extensions.map { it.removePrefix(".").lowercase() }.toSet()
    public val extensions: Set<String> get() = extensionSnapshot.toSet()

    init {
        requireText(description, "filter.description", 128)
        if (extensionSnapshot.isEmpty() || extensionSnapshot.size > 32) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("field" to "filter.extensions"),
                message = "A file filter must declare between one and thirty-two extensions.",
            )
        }
        this.extensions.forEach { extension ->
            if (extension.length > 32 || !EXTENSION_PATTERN.matches(extension)) {
                throw KWebConfigurationException(
                    code = KWebServiceErrorCode.REQUEST_INVALID,
                    details = mapOf("extension" to extension),
                    message = "A file filter extension is invalid.",
                )
            }
        }
    }
}

public class KWebFileDialogRequest(
    public val mode: KWebFileDialogMode,
    public val title: String,
    public val defaultDirectory: String? = null,
    public val defaultName: String? = null,
    filters: Collection<KWebFileFilter> = emptyList(),
) {
    private val filterSnapshot = filters.toList()
    public val filters: List<KWebFileFilter> get() = filterSnapshot.toList()

    init {
        requireText(title, "title", 256)
        if (filterSnapshot.size > 32) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("field" to "filters"),
                message = "A file dialog cannot contain more than thirty-two filters.",
            )
        }
        defaultDirectory?.let { requireText(it, "defaultDirectory", 4096) }
        defaultName?.let { requireFileName(it, "defaultName") }
    }
}

public data class KWebFileSelection(
    public val handle: String,
    public val name: String,
    public val sizeBytes: Long,
    public val mode: KWebFileDialogMode,
) {
    init {
        requireFileName(name, "selection.name")
        if (!HANDLE_PATTERN.matches(handle) || name.isBlank() || name.any { it == '\u0000' } || sizeBytes < 0) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("field" to "selection"),
                message = "The selected file result is invalid.",
            )
        }
    }
}

public class KWebFileReadResult(
    bytes: Collection<Int>,
    public val eof: Boolean,
) {
    private val byteSnapshot = bytes.toList()
    public val bytes: List<Int> get() = byteSnapshot.toList()

    init {
        if (byteSnapshot.size > MAX_TRANSFER_BYTES || byteSnapshot.any { it !in 0..255 }) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("field" to "bytes"),
                message = "A file read result contains an invalid byte buffer.",
            )
        }
    }
}

public data class KWebFileWriteResult(public val written: Int) {
    init {
        if (written < 0 || written > MAX_TRANSFER_BYTES) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("written" to written.toString()),
                message = "A file write result contains an invalid byte count.",
            )
        }
    }
}

public data class KWebFileTruncateResult(public val sizeBytes: Long) {
    init {
        if (sizeBytes < 0) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("sizeBytes" to sizeBytes.toString()),
                message = "A file size cannot be negative.",
            )
        }
    }
}

public interface KWebDialogs : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun selectFile(request: KWebFileDialogRequest): KWebFileSelection?
    public suspend fun readFile(handle: String, offset: Long, length: Int): KWebFileReadResult
    public suspend fun writeFile(handle: String, offset: Long, bytes: List<Int>): KWebFileWriteResult
    public suspend fun truncateFile(handle: String, sizeBytes: Long): KWebFileTruncateResult
    public suspend fun closeFile(handle: String)

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "dialogs",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("select-file"),
                operation("read-file"),
                operation("write-file"),
                operation("truncate-file"),
                operation("close-file"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebDialogs> = object : KWebServiceKey<KWebDialogs> {
            override val id: String = DESCRIPTOR.id
            override val version: KWebServiceVersion = DESCRIPTOR.version
        }

        private fun operation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = "native.dialogs.$id",
            requiresUserGesture = false,
        )
    }
}

public object KWebDialogsErrorCode {
    public const val DIALOG_UNAVAILABLE: String = "dialog.unavailable"
    public const val HANDLE_INVALID: String = "dialog.handle-invalid"
    public const val HANDLE_NOT_FOUND: String = "dialog.handle-not-found"
    public const val HANDLE_MODE: String = "dialog.handle-mode"
    public const val IO_BOUNDS: String = "dialog.io-bounds"
    public const val PATH_INVALID: String = "dialog.path-invalid"
}

// Decimal byte arrays require up to four JSON bytes per payload byte.
public const val KWEB_DIALOGS_MAX_TRANSFER_BYTES: Int = 131_072

private const val MAX_TRANSFER_BYTES: Int = KWEB_DIALOGS_MAX_TRANSFER_BYTES
private val EXTENSION_PATTERN = Regex("[a-z0-9][a-z0-9_+\\-]*(\\.[a-z0-9][a-z0-9_+\\-]*)*")
private val HANDLE_PATTERN = Regex("[A-Za-z0-9_-]{43}")

internal fun requireFileName(value: String, field: String) {
    requireText(value, field, 255)
    if (value == "." || value == ".." || value.endsWith('.') || value.endsWith(' ') ||
        value.any { it.code < 32 || it.code == 127 || it in "<>:\"/\\|?*" }) {
        throw KWebConfigurationException(
            code = KWebServiceErrorCode.REQUEST_INVALID,
            details = mapOf("field" to field),
            message = "The file name must be one portable path component.",
        )
    }
}

private fun requireText(value: String, field: String, maxLength: Int) {
    if (value.isBlank() || value.length > maxLength || value.any { it == '\u0000' }) {
        throw KWebConfigurationException(
            code = KWebServiceErrorCode.REQUEST_INVALID,
            details = mapOf("field" to field),
            message = "The file dialog field '$field' is empty, too long, or contains a NUL character.",
        )
    }
}
