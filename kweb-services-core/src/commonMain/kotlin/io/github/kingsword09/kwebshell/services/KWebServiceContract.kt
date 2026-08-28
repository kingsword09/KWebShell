package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private val SERVICE_IDENTIFIER = Regex(
    "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)*",
)

public enum class KWebServiceScope {
    APPLICATION,
    PROFILE,
    PAGE,
}

public data class KWebServiceVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
) {
    init {
        if (major < 0 || minor < 0 || patch < 0) {
            throw KWebConfigurationException(
                code = "service.version-invalid",
                details = mapOf(
                    "major" to major.toString(),
                    "minor" to minor.toString(),
                    "patch" to patch.toString(),
                ),
                message = "Service version components must be non-negative.",
            )
        }
    }

    public override fun toString(): String = "$major.$minor.$patch"
}

public data class KWebServiceOperationDescriptor(
    public val id: String,
    public val schemaVersion: Int,
    public val rendererPermission: String?,
    public val requiresUserGesture: Boolean,
) {
    init {
        if (!SERVICE_IDENTIFIER.matches(id)) {
            throw KWebConfigurationException(
                code = "service.operation-id-invalid",
                details = mapOf("operation" to id),
                message = "A native service operation identifier is invalid.",
            )
        }
        if (schemaVersion <= 0) {
            throw KWebConfigurationException(
                code = "service.schema-version-invalid",
                details = mapOf("operation" to id, "version" to schemaVersion.toString()),
                message = "A native service operation schema version must be positive.",
            )
        }
        if (rendererPermission != null && !SERVICE_IDENTIFIER.matches(rendererPermission)) {
            throw KWebConfigurationException(
                code = "service.permission-id-invalid",
                details = mapOf("operation" to id, "permission" to rendererPermission),
                message = "A native service renderer permission identifier is invalid.",
            )
        }
    }
}

public data class KWebServiceDescriptor(
    public val id: String,
    public val version: KWebServiceVersion,
    public val scope: KWebServiceScope,
    public val operations: Set<KWebServiceOperationDescriptor>,
    public val requiredCapabilities: Set<KWebCapability>,
    public val supportedTargets: Set<KWebTarget>,
) {
    init {
        if (!SERVICE_IDENTIFIER.matches(id)) {
            throw KWebConfigurationException(
                code = "service.id-invalid",
                details = mapOf("service" to id),
                message = "A native service identifier is invalid.",
            )
        }
        if (operations.isEmpty()) {
            throw KWebConfigurationException(
                code = "service.operations-empty",
                details = mapOf("service" to id),
                message = "A native service must publish at least one operation.",
            )
        }
        if (operations.map(KWebServiceOperationDescriptor::id).toSet().size != operations.size) {
            throw KWebConfigurationException(
                code = "service.operations-duplicate",
                details = mapOf("service" to id),
                message = "A native service cannot publish duplicate operation identifiers.",
            )
        }
        if (supportedTargets.isEmpty()) {
            throw KWebConfigurationException(
                code = "service.targets-empty",
                details = mapOf("service" to id),
                message = "A native service must declare at least one supported target.",
            )
        }
    }
}

public interface KWebNativeService {
    public val descriptor: KWebServiceDescriptor

    public val lifecycle: StateFlow<KWebLifecycleState>

    public fun close()
}

public interface KWebServiceKey<T : KWebNativeService> {
    public val id: String
    public val version: KWebServiceVersion
}

public data class KWebServiceGrant(
    public val serviceId: String,
    public val operationId: String,
) {
    init {
        if (!SERVICE_IDENTIFIER.matches(serviceId) || !SERVICE_IDENTIFIER.matches(operationId)) {
            throw KWebConfigurationException(
                code = "service.grant-invalid",
                details = mapOf("service" to serviceId, "operation" to operationId),
                message = "A native service grant must use stable identifiers.",
            )
        }
    }
}

public class KWebServicePermissionPolicy private constructor(
    private val grants: Set<KWebServiceGrant>,
) {
    public fun allows(serviceId: String, operationId: String): Boolean =
        KWebServiceGrant(serviceId, operationId) in grants

    public fun grants(): Set<KWebServiceGrant> = grants.toSet()

    public companion object {
        public fun exact(grants: Set<KWebServiceGrant>): KWebServicePermissionPolicy =
            KWebServicePermissionPolicy(grants.toSet())
    }
}

public class KWebServiceException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public object KWebServiceErrorCode {
    public const val NOT_INSTALLED: String = "service.not-installed"
    public const val VERSION_INCOMPATIBLE: String = "service.version-incompatible"
    public const val OPERATION_UNAVAILABLE: String = "service.operation-unavailable"
    public const val PERMISSION_DENIED: String = "service.permission-denied"
    public const val USER_GESTURE_REQUIRED: String = "service.user-gesture-required"
    public const val REQUEST_INVALID: String = "service.request-invalid"
    public const val OWNER_CLOSED: String = "service.owner-closed"
    public const val CANCELLED: String = "service.cancelled"
    public const val NATIVE_FAILED: String = "service.native-failed"
}

public class KWebNativeServiceRegistry : AutoCloseable {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val services = linkedMapOf<String, KWebNativeService>()
    private var closeFailure: KWebNativeException? = null

    public val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    public fun <T : KWebNativeService> install(
        key: KWebServiceKey<T>,
        service: T,
    ) {
        synchronized(lock) {
            requireOpen("install")
            if (service.descriptor.id != key.id || service.descriptor.version != key.version) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                    details = mapOf(
                        "requestedService" to key.id,
                        "requestedVersion" to key.version.toString(),
                        "actualService" to service.descriptor.id,
                        "actualVersion" to service.descriptor.version.toString(),
                    ),
                    message = "The installed service descriptor does not satisfy the requested key.",
                )
            }
            if (service.lifecycle.value != KWebLifecycleState.OPEN) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.OWNER_CLOSED,
                    details = mapOf("service" to key.id, "state" to service.lifecycle.value.name),
                    message = "A native service must be open when it is installed.",
                )
            }
            if (services.putIfAbsent(key.id, service) != null) {
                throw KWebServiceException(
                    code = "service.duplicate-installation",
                    details = mapOf("service" to key.id),
                    message = "The native service is already installed in this registry.",
                )
            }
        }
    }

    public fun <T : KWebNativeService> require(key: KWebServiceKey<T>): T {
        synchronized(lock) {
            requireOpen("require")
            val service = services[key.id] ?: throw KWebServiceException(
                code = KWebServiceErrorCode.NOT_INSTALLED,
                details = mapOf("service" to key.id, "version" to key.version.toString()),
                message = "The requested native service is not installed.",
            )
            if (service.descriptor.version != key.version) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                    details = mapOf(
                        "service" to key.id,
                        "requestedVersion" to key.version.toString(),
                        "actualVersion" to service.descriptor.version.toString(),
                    ),
                    message = "The installed native service has an incompatible contract version.",
                )
            }
            if (service.lifecycle.value != KWebLifecycleState.OPEN) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.OWNER_CLOSED,
                    details = mapOf("service" to key.id, "state" to service.lifecycle.value.name),
                    message = "The requested native service is no longer open.",
                )
            }
            @Suppress("UNCHECKED_CAST")
            return service as T
        }
    }

    override fun close() {
        val toClose: List<KWebNativeService>
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            closeFailure?.let { throw it }
            requireOpen("close")
            mutableLifecycle.value = KWebLifecycleState.CLOSING
            toClose = services.values.toList().asReversed()
            services.clear()
        }
        var failure: Throwable? = null
        toClose.forEach { service ->
            try {
                service.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        val terminalFailure = failure?.let {
            KWebNativeException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("operation" to "registry-close"),
                message = "One or more native services failed during registry shutdown.",
                cause = it,
            )
        }
        synchronized(lock) {
            closeFailure = terminalFailure
            mutableLifecycle.value = if (terminalFailure == null) {
                KWebLifecycleState.CLOSED
            } else {
                KWebLifecycleState.FAILED
            }
        }
        terminalFailure?.let { throw it }
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebServiceException(
                code = KWebServiceErrorCode.OWNER_CLOSED,
                details = mapOf("operation" to operation),
                message = "The native service registry is not open.",
            )
        }
    }
}
