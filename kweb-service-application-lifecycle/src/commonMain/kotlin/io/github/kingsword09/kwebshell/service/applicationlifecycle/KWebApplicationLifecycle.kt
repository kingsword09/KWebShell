package io.github.kingsword09.kwebshell.service.applicationlifecycle

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

public enum class KWebApplicationLifecycleState {
    NEW,
    STARTING,
    PRIMARY_READY,
    SECONDARY_FORWARDED,
    QUIESCING,
    CLOSED,
    FAILED,
}

@Serializable
public enum class KWebActivationSource {
    INITIAL_ARGUMENTS,
    PROTOCOL,
    FILE_OPEN,
    SECOND_INSTANCE,
    TEST,
}

public enum class KWebQuitReason {
    USER_REQUEST,
    OS_REQUEST,
    RELAUNCH,
    SHUTDOWN,
    TEST,
}

public enum class KWebShutdownVote {
    ALLOW,
    DENY,
}

public enum class KWebApplicationStartResult {
    PRIMARY,
    SECONDARY_FORWARDED,
}

public enum class KWebQuitResult {
    GRACEFUL,
    VETOED,
    FAILED,
    ALREADY_CLOSED,
}

public enum class KWebRelaunchResult {
    ACCEPTED,
    UNAVAILABLE,
    FAILED,
}

public enum class KWebApplicationRegistrationOperation {
    INSTALL,
    REMOVE,
}

public data class KWebApplicationRegistrationReport(
    public val operation: KWebApplicationRegistrationOperation,
    public val target: KWebTarget,
    public val applicationId: String,
    public val provider: String,
    public val registered: Boolean,
    public val observedDigest: String,
)

@Serializable
public data class KWebOpenedFile(
    public val absolutePath: String,
    public val exists: Boolean,
)

@Serializable
public data class KWebActivationBatch(
    public val source: KWebActivationSource,
    public val uris: List<String> = emptyList(),
    public val files: List<KWebOpenedFile> = emptyList(),
) {
    init {
        if (uris.isEmpty() && files.isEmpty()) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID,
                details = emptyMap(),
                message = "An application activation must contain a URI or file.",
            )
        }
    }
}

public sealed interface KWebApplicationEvent {
    public data object Ready : KWebApplicationEvent

    public data class Activation(
        public val sequence: ULong,
        public val batch: KWebActivationBatch,
    ) : KWebApplicationEvent

    public data class QuitStarted(
        public val reason: KWebQuitReason,
    ) : KWebApplicationEvent

    public data class Closed(
        public val result: KWebQuitResult,
    ) : KWebApplicationEvent

    public data class ForcedTermination(
        public val reason: String,
    ) : KWebApplicationEvent
}

public interface KWebApplicationShutdownParticipant {
    public val id: String
    public val order: Int
    public suspend fun requestClose(reason: KWebQuitReason): KWebShutdownVote
    public suspend fun close(reason: KWebQuitReason)
}

public interface KWebApplicationShutdownRegistration {
    public fun unregister()
}

public data class KWebApplicationLifecycleConfiguration(
    public val applicationId: String,
    public val target: KWebTarget,
    public val packageIdentity: String,
    public val registeredSchemes: Set<String>,
    public val registeredExtensions: Set<String>,
    public val packageRoot: String,
    public val transportRoot: String,
    public val relaunchExecutable: String?,
    public val isPackaged: Boolean,
    public val activationCapacity: Int = 64,
    public val maximumUriBytes: Int = 4096,
    public val maximumFileCount: Int = 32,
    public val maximumFilePathBytes: Int = 4096,
    public val maximumTransportFrameBytes: Int = 256 * 1024,
    public val shutdownTimeoutMillis: Long = 30_000,
) {
    init {
        requireApplicationId(applicationId)
        if (packageIdentity.isBlank() || packageRoot.isBlank() || transportRoot.isBlank()) {
            configurationFailure("Application package identity and root are required.")
        }
        if (registeredSchemes.any { !SCHEME.matches(it) || it != it.lowercase() }) {
            configurationFailure("Every registered scheme must be lowercase and portable.")
        }
        if (registeredExtensions.any { !EXTENSION.matches(it) || it != it.lowercase() }) {
            configurationFailure("Every registered extension must be lowercase and portable.")
        }
        if (activationCapacity != 64 || maximumUriBytes != 4096 || maximumFileCount != 32 ||
            maximumFilePathBytes != 4096 || maximumTransportFrameBytes != 256 * 1024 ||
            shutdownTimeoutMillis != 30_000L
        ) {
            configurationFailure("RFC 0006 v1 limits are fixed and cannot be changed by the host.")
        }
        if (isPackaged && relaunchExecutable.isNullOrBlank()) {
            configurationFailure("A packaged application requires a verified relaunch executable.")
        }
        relaunchExecutable?.let {
            if (it.startsWith('/') || it.contains('\\') || it.split('/').any { part -> part == ".." || part == "." }) {
                configurationFailure("The relaunch executable must be a normalized package-relative path.")
            }
        }
    }

    public companion object {
        private val APPLICATION_ID = Regex(
            "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
        )
        private val SCHEME = Regex("[a-z][a-z0-9+.-]{1,31}")
        private val EXTENSION = Regex("\\.[A-Za-z0-9][A-Za-z0-9._-]{0,31}")

        private fun requireApplicationId(value: String) {
            if (!APPLICATION_ID.matches(value)) configurationFailure("The application id is invalid.")
        }

        private fun configurationFailure(message: String): Nothing = throw KWebApplicationLifecycleException(
            code = KWebApplicationLifecycleErrorCode.CONFIGURATION_INVALID,
            details = emptyMap(),
            message = message,
        )
    }
}

public object KWebApplicationLifecycleErrorCode {
    public const val CONFIGURATION_INVALID: String = "application.lifecycle.configuration-invalid"
    public const val ALREADY_STARTED: String = "application.lifecycle.already-started"
    public const val SECONDARY: String = "application.lifecycle.secondary"
    public const val CLOSING: String = "application.lifecycle.closing"
    public const val LEASE_UNAVAILABLE: String = "application.lifecycle.lease-unavailable"
    public const val ACTIVATION_INVALID: String = "application.activation.invalid"
    public const val UNREGISTERED_SCHEME: String = "application.activation.unregistered-scheme"
    public const val AUTH_FAILED: String = "application.activation.auth-failed"
    public const val QUEUE_FULL: String = "application.activation.queue-full"
    public const val TRANSPORT_FAILED: String = "application.activation.transport-failed"
    public const val REGISTRATION_FAILED: String = "application.registration.failed"
    public const val QUIT_VETOED: String = "application.quit.vetoed"
    public const val QUIT_TIMEOUT: String = "application.quit.timeout"
    public const val QUIT_FORCED: String = "application.quit.forced"
    public const val RELAUNCH_UNAVAILABLE: String = "application.relaunch.unavailable"
    public const val NATIVE_ABI_MISMATCH: String = "application.native.abi-mismatch"
    public const val NATIVE_UNAVAILABLE: String = "application.native.unavailable"
}

public class KWebApplicationLifecycleException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public interface KWebApplicationLifecycle : KWebNativeService {
    public val state: StateFlow<KWebApplicationLifecycleState>
    public val events: SharedFlow<KWebApplicationEvent>

    public suspend fun start(initial: KWebActivationBatch): KWebApplicationStartResult
    public suspend fun requestQuit(reason: KWebQuitReason): KWebQuitResult
    public suspend fun requestRelaunch(): KWebRelaunchResult
    public suspend fun installAssociations(): KWebApplicationRegistrationReport
    public suspend fun removeAssociations(): KWebApplicationRegistrationReport
    public fun registerShutdownParticipant(
        participant: KWebApplicationShutdownParticipant,
    ): KWebApplicationShutdownRegistration

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "application-lifecycle",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                KWebServiceOperationDescriptor(
                    id = "activation-events",
                    schemaVersion = 1,
                    rendererPermission = null,
                    requiresUserGesture = false,
                ),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebApplicationLifecycle> =
            object : KWebServiceKey<KWebApplicationLifecycle> {
                override val id: String = DESCRIPTOR.id
                override val contract: KWebServiceVersionRange =
                    KWebServiceVersionRange.exact(DESCRIPTOR.version)
            }
    }
}
