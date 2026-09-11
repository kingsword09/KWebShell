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

internal val SERVICE_IDENTIFIER = Regex(
    "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)*",
)

public enum class KWebServiceScope {
    APPLICATION,
    PROFILE,
    WINDOW,
    PAGE,
}

public data class KWebServiceVersion(
    public val major: Int,
    public val minor: Int,
    public val patch: Int,
) : Comparable<KWebServiceVersion> {
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

    override fun compareTo(other: KWebServiceVersion): Int {
        val majorOrder = major.compareTo(other.major)
        if (majorOrder != 0) return majorOrder
        val minorOrder = minor.compareTo(other.minor)
        if (minorOrder != 0) return minorOrder
        return patch.compareTo(other.patch)
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
    public val contract: KWebServiceVersionRange
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
    public const val PROVIDER_AMBIGUOUS: String = "service.provider.ambiguous"
    public const val PROVIDER_DUPLICATE: String = "service.provider.duplicate"
    public const val PROVIDER_MISSING: String = "service.provider.missing"
    public const val PROVIDER_TARGET_UNSUPPORTED: String = "service.provider.target-unsupported"
    public const val PROVIDER_GRAPH_CYCLE: String = "service.provider.graph-cycle"
    public const val PROVIDER_STARTUP_FAILED: String = "service.provider.startup-failed"
    public const val PROVIDER_CONTRACT_MISMATCH: String = "service.provider.contract-mismatch"
    public const val DEPENDENCY_UNDECLARED: String = "service.dependency.undeclared"
    public const val DEPENDENCY_SCOPE_INVALID: String = "service.dependency.scope-invalid"
    public const val CAPABILITY_MISSING: String = "service.capability.missing"
    public const val CAPABILITY_UNAVAILABLE: String = "service.capability.unavailable"
}

public class KWebNativeServiceRegistry : AutoCloseable {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val services = linkedMapOf<String, KWebNativeService>()
    private var closeFailure: KWebException? = null

    public val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    public fun <T : KWebNativeService> install(
        key: KWebServiceKey<T>,
        service: T,
    ) {
        synchronized(lock) {
            requireOpen("install")
            if (service.descriptor.id != key.id || !key.contract.contains(service.descriptor.version)) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                    details = mapOf(
                        "requestedService" to key.id,
                        "requestedContract" to key.contract.describe(),
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
                details = mapOf("service" to key.id, "contract" to key.contract.describe()),
                message = "The requested native service is not installed.",
            )
            if (!key.contract.contains(service.descriptor.version)) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                    details = mapOf(
                        "service" to key.id,
                        "requestedContract" to key.contract.describe(),
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

    /**
     * Starts every declared provider in deterministic topological order and
     * registers the created services. The whole configuration is validated
     * before any factory runs; a failed startup rolls back exactly the
     * resources created by that attempt and leaves a sticky typed failure.
     */
    public fun installProviders(
        configuration: KWebServiceProviderConfiguration,
    ): KWebServiceProviderInstallReport {
        synchronized(lock) {
            closeFailure?.let { throw it }
            requireOpen("install-providers")
            val ordered = KWebProviderStartupPlanner.validateAndOrder(configuration)
            val created = mutableListOf<Pair<KWebServiceProviderDeclaration<*>, KWebNativeService>>()
            try {
                ordered.forEach { declaration ->
                    val service = declaration.factory.create(
                        ProviderStartupEnvironment(this, configuration, declaration),
                    )
                    validateCreatedService(declaration, service)
                    services[service.descriptor.id] = service
                    created += declaration to service
                }
            } catch (error: Throwable) {
                rollbackStartup(error, created)
            }
            return KWebServiceProviderInstallReport(
                providerOrder = created.map { it.first.providerId },
                startupOrder = created.map { it.second.descriptor.id },
            )
        }
    }

    private fun validateCreatedService(
        declaration: KWebServiceProviderDeclaration<*>,
        service: KWebNativeService,
    ) {
        val mismatch = KWebServiceException(
            code = KWebServiceErrorCode.PROVIDER_CONTRACT_MISMATCH,
            details = mapOf(
                "provider" to declaration.providerId,
                "declaredService" to declaration.key.id,
                "declaredVersion" to declaration.contractVersion.toString(),
                "declaredScope" to declaration.scope.name,
            ),
            message = "A provider factory returned a service that does not match its declaration.",
        )
        if (service.lifecycle.value != KWebLifecycleState.OPEN) throw mismatch
        val descriptor = service.descriptor
        if (descriptor.id != declaration.key.id ||
            descriptor.version != declaration.contractVersion ||
            descriptor.scope != declaration.scope ||
            !declaration.key.contract.contains(descriptor.version)
        ) {
            throw mismatch
        }
    }

    private fun rollbackStartup(
        cause: Throwable,
        created: List<Pair<KWebServiceProviderDeclaration<*>, KWebNativeService>>,
    ): Nothing {
        var failure: Throwable = cause
        created.asReversed().forEach { (_, service) ->
            services.remove(service.descriptor.id)
            try {
                service.close()
            } catch (closeError: Throwable) {
                failure.addSuppressed(closeError)
            }
        }
        // Typed SDK failures keep their stable codes; only foreign factory failures
        // are wrapped into the typed startup failure. Either way the failure sticks.
        val sticky = (cause as? KWebException) ?: KWebServiceException(
            code = KWebServiceErrorCode.PROVIDER_STARTUP_FAILED,
            details = mapOf("reason" to "factory-failed"),
            message = "The native service provider startup failed and every created service was rolled back.",
            cause = failure,
        )
        closeFailure = sticky
        mutableLifecycle.value = KWebLifecycleState.FAILED
        throw sticky
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

/**
 * The environment one provider factory receives. It exposes only the declared
 * configuration facts and refuses dependency access outside the declaration, so
 * a provider can never query an undeclared service or reach another scope owner.
 */
private class ProviderStartupEnvironment(
    private val registry: KWebNativeServiceRegistry,
    private val configuration: KWebServiceProviderConfiguration,
    private val declaration: KWebServiceProviderDeclaration<*>,
) : KWebServiceProviderEnvironment {
    override val target: KWebTarget = configuration.target
    override val scope: KWebServiceScope = declaration.scope
    override val ownerId: String = configuration.ownerId

    override fun fact(id: String): KWebCapabilityFact {
        return configuration.facts.singleOrNull { it.id == id }
            ?: throw KWebServiceException(
                code = KWebServiceErrorCode.CAPABILITY_MISSING,
                details = mapOf("provider" to declaration.providerId, "fact" to id),
                message = "The provider environment does not declare the requested capability fact.",
            )
    }

    override fun <T : KWebNativeService> dependency(key: KWebServiceKey<T>): T {
        if (declaration.dependencies.none { it.id == key.id }) {
            throw KWebServiceException(
                code = KWebServiceErrorCode.DEPENDENCY_UNDECLARED,
                details = mapOf("provider" to declaration.providerId, "dependency" to key.id),
                message = "A provider cannot query a service it did not declare.",
            )
        }
        return registry.require(key)
    }
}
