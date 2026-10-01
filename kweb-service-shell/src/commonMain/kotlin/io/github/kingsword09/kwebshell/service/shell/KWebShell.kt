package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import io.github.kingsword09.kwebshell.services.KWebNativeService
import kotlinx.coroutines.flow.StateFlow

public enum class KWebShellResourceKind { FILE, DIRECTORY }

public enum class KWebShellAction {
    OPEN_EXTERNAL,
    OPEN_RESOURCE,
    REVEAL_RESOURCE,
    TRASH_RESOURCE,
}

public enum class KWebShellActionOutcome {
    HANDLER_ACCEPTED,
    MOVED_TO_TRASH,
}

public data class KWebShellConfiguration(
    public val allowedExternalSchemes: Set<String> = setOf("http", "https", "mailto"),
    public val allowDirectoryTrash: Boolean = false,
) {
    init {
        if (allowedExternalSchemes.isEmpty() ||
            allowedExternalSchemes.any { !SCHEME.matches(it) || it.lowercase() != it || it in DENIED_SCHEMES }
        ) {
            throw KWebConfigurationException(
                code = "service.request-invalid",
                details = emptyMap(),
                message = "Shell external schemes must be lowercase allowlisted schemes.",
            )
        }
    }

    private companion object {
        val SCHEME = Regex("[a-z][a-z0-9+.-]{0,31}")
        val DENIED_SCHEMES = setOf(
            "file", "javascript", "data", "vbscript", "about", "blob", "filesystem",
            "command", "shell", "chrome", "devtools",
        )
    }
}

public data class KWebShellExternalUriRequest(public val uri: String) {
    init {
        if (uri.isEmpty() || uri.toByteArray(Charsets.UTF_8).size > KWEB_SHELL_MAX_URI_BYTES) {
            throw KWebConfigurationException(
                code = KWebShellErrorCode.URI_TOO_LARGE,
                details = emptyMap(),
                message = "The external URI is outside the bounded shell request size.",
            )
        }
    }
}

public class KWebShellResourceHandle internal constructor(
    public val token: String,
) {
    public companion object {
        internal fun fromBridge(token: String): KWebShellResourceHandle = KWebShellResourceHandle(token)
    }
}

public data class KWebShellActionResult(
    public val action: KWebShellAction,
    public val outcome: KWebShellActionOutcome,
    public val resourceKind: KWebShellResourceKind?,
)

public interface KWebShell : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<io.github.kingsword09.kwebshell.core.KWebLifecycleState>

    public suspend fun openExternal(request: KWebShellExternalUriRequest): KWebShellActionResult
    public suspend fun openResource(handle: KWebShellResourceHandle): KWebShellActionResult
    public suspend fun revealResource(handle: KWebShellResourceHandle): KWebShellActionResult
    public suspend fun trashResource(handle: KWebShellResourceHandle): KWebShellActionResult

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "shell",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.PAGE,
            operations = setOf(
                operation("open-external"),
                operation("open-resource"),
                operation("reveal-resource"),
                operation("trash-resource"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebShell> = object : KWebServiceKey<KWebShell> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(id: String): KWebServiceOperationDescriptor =
            KWebServiceOperationDescriptor(
                id = id,
                schemaVersion = 1,
                rendererPermission = "native.shell.$id",
                requiresUserGesture = true,
            )
    }
}

public object KWebShellErrorCode {
    public const val URI_INVALID: String = "shell.uri-invalid"
    public const val URI_TOO_LARGE: String = "shell.uri-too-large"
    public const val SCHEME_DENIED: String = "shell.scheme-denied"
    public const val HANDLE_INVALID: String = "shell.handle-invalid"
    public const val HANDLE_NOT_FOUND: String = "shell.handle-not-found"
    public const val HANDLE_GRANT: String = "shell.handle-grant"
    public const val HANDLE_KIND: String = "shell.handle-kind"
    public const val DIRECTORY_NOT_ALLOWED: String = "shell.directory-not-allowed"
    public const val BUSY: String = "shell.busy"
    public const val HANDLER_REJECTED: String = "shell.handler-rejected"
    public const val REVEAL_UNAVAILABLE: String = "shell.reveal-unavailable"
    public const val TRASH_FAILED: String = "shell.trash-failed"
    public const val TRASH_VERIFICATION_FAILED: String = "shell.trash-verification-failed"
    public const val NATIVE_UNAVAILABLE: String = "shell.native-unavailable"
    public const val PLATFORM_UNAVAILABLE: String = "shell.platform-unavailable"
    public const val OPERATION_CANCELLED: String = "shell.operation-cancelled"
}

public const val KWEB_SHELL_MAX_URI_BYTES: Int = 8 * 1024
public const val KWEB_SHELL_MAX_HANDLE_BYTES: Int = 512
