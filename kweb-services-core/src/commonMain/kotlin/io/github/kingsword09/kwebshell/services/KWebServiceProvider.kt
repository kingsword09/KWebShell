package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One application-declared capability fact. Probes report facts only; they never
 * select a weaker backend and never substitute a missing provider.
 */
public data class KWebCapabilityFact(
    public val id: String,
    public val available: Boolean,
    public val details: Map<String, String> = emptyMap(),
)

/** One typed dependency edge from a provider to another installed service contract. */
public data class KWebServiceDependency(
    public val id: String,
    public val contract: KWebServiceVersionRange,
    public val scope: KWebServiceScope,
)

/**
 * The only input a provider factory receives: its declared owner environment.
 * Dependency access outside the declaration and undeclared capability facts fail
 * with typed errors.
 */
public interface KWebServiceProviderEnvironment {
    public val target: KWebTarget
    public val scope: KWebServiceScope
    public val ownerId: String

    public fun fact(id: String): KWebCapabilityFact

    public fun <T : KWebNativeService> dependency(key: KWebServiceKey<T>): T
}

public fun interface KWebServiceProviderFactory<T : KWebNativeService> {
    public fun create(environment: KWebServiceProviderEnvironment): T
}

/**
 * One explicit provider declaration. Application code installs providers through
 * configuration; discovery, classpath scanning, and default providers do not exist.
 */
public class KWebServiceProviderDeclaration<T : KWebNativeService>(
    public val providerId: String,
    public val key: KWebServiceKey<T>,
    public val contractVersion: KWebServiceVersion,
    public val scope: KWebServiceScope,
    public val supportedTargets: Set<KWebTarget>,
    public val requiredFacts: Set<String>,
    public val dependencies: Set<KWebServiceDependency>,
    public val factory: KWebServiceProviderFactory<T>,
) {
    init {
        if (!SERVICE_IDENTIFIER.matches(providerId)) {
            throw KWebConfigurationException(
                code = "service.provider.id-invalid",
                details = mapOf("provider" to providerId),
                message = "A provider id is invalid.",
            )
        }
        if (supportedTargets.isEmpty()) {
            throw KWebConfigurationException(
                code = "service.provider.targets-empty",
                details = mapOf("provider" to providerId),
                message = "A provider must declare at least one supported target.",
            )
        }
        if (key.contract.let { contract -> !contract.contains(contractVersion) }) {
            throw KWebConfigurationException(
                code = "service.version-incompatible",
                details = mapOf(
                    "provider" to providerId,
                    "contract" to key.contract.describe(),
                    "version" to contractVersion.toString(),
                ),
                message = "A provider contract version must satisfy its own key contract.",
            )
        }
        val dependencyIds = dependencies.map(KWebServiceDependency::id)
        if (dependencyIds.size != dependencyIds.toSet().size) {
            throw KWebConfigurationException(
                code = "service.dependency.duplicate",
                details = mapOf("provider" to providerId),
                message = "A provider cannot declare the same dependency twice.",
            )
        }
        if (providerId in dependencyIds) {
            throw KWebConfigurationException(
                code = "service.dependency.self",
                details = mapOf("provider" to providerId),
                message = "A provider cannot depend on itself.",
            )
        }
    }
}

/** Deterministic startup selection input: explicit target, facts, and providers. */
public class KWebServiceProviderConfiguration(
    public val target: KWebTarget,
    public val ownerId: String,
    public val facts: Set<KWebCapabilityFact>,
    public val providers: List<KWebServiceProviderDeclaration<*>>,
) {
    init {
        if (providers.isEmpty()) {
            throw KWebConfigurationException(
                code = "service.provider.configuration-empty",
                details = mapOf(),
                message = "A provider configuration must declare at least one provider.",
            )
        }
        val factIds = facts.map(KWebCapabilityFact::id)
        if (factIds.size != factIds.toSet().size) {
            throw KWebConfigurationException(
                code = "service.capability.duplicate",
                details = mapOf(),
                message = "A provider configuration cannot declare the same capability fact twice.",
            )
        }
        if (ownerId.isBlank()) {
            throw KWebConfigurationException(
                code = "service.provider.owner-invalid",
                details = mapOf(),
                message = "A provider configuration requires an owner identity.",
            )
        }
    }

    /** Deterministic packaged metadata naming every provider; no reflective lookup exists. */
    public fun catalog(): KWebServiceProviderCatalog {
        val entries = providers
            .sortedWith(compareBy({ it.key.id }, { it.providerId }))
            .map { declaration ->
                KWebServiceProviderCatalogEntry(
                    providerId = declaration.providerId,
                    serviceId = declaration.key.id,
                    contract = declaration.key.contract.describe(),
                    contractVersion = declaration.contractVersion.toString(),
                    scope = declaration.scope.name,
                    supportedTargets = declaration.supportedTargets.map(KWebTarget::id).sorted(),
                    requiredFacts = declaration.requiredFacts.sorted(),
                    dependencies = declaration.dependencies
                        .sortedBy(KWebServiceDependency::id)
                        .map { dependency ->
                            KWebServiceProviderCatalogDependency(
                                id = dependency.id,
                                contract = dependency.contract.describe(),
                                scope = dependency.scope.name,
                            )
                        },
                )
            }
        return KWebServiceProviderCatalog(
            schemaVersion = KWebServiceProviderCatalog.SCHEMA_VERSION,
            target = target.id,
            ownerId = ownerId,
            entries = entries,
        )
    }
}

@Serializable
public data class KWebServiceProviderCatalogDependency(
    public val id: String,
    public val contract: String,
    public val scope: String,
)

@Serializable
public data class KWebServiceProviderCatalogEntry(
    public val providerId: String,
    public val serviceId: String,
    public val contract: String,
    public val contractVersion: String,
    public val scope: String,
    public val supportedTargets: List<String>,
    public val requiredFacts: List<String>,
    public val dependencies: List<KWebServiceProviderCatalogDependency>,
)

@Serializable
public data class KWebServiceProviderCatalog(
    public val schemaVersion: Int,
    public val target: String,
    public val ownerId: String,
    public val entries: List<KWebServiceProviderCatalogEntry>,
) {
    public companion object {
        public const val SCHEMA_VERSION: Int = 1

        /** Canonical, byte-for-byte deterministic serialization of the packaged metadata. */
        public fun of(catalog: KWebServiceProviderCatalog): String =
            CATALOG_JSON.encodeToString(serializer(), catalog)
    }
}

private val CATALOG_JSON: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** The deterministic result of one provider startup attempt. */
public data class KWebServiceProviderInstallReport(
    public val providerOrder: List<String>,
    public val startupOrder: List<String>,
)

/**
 * Pure startup planner: validates one configuration completely — duplicates,
 * ambiguity, target support, capability facts, dependency existence, scope
 * direction, and contract ranges — and returns the deterministic topological
 * startup order. Every mismatch fails before any factory runs.
 */
internal object KWebProviderStartupPlanner {
    public fun validateAndOrder(configuration: KWebServiceProviderConfiguration): List<KWebServiceProviderDeclaration<*>> {
        val providers = configuration.providers
        val factsById = configuration.facts.associateBy(KWebCapabilityFact::id)

        providers.groupBy { it.key.id }.forEach { (serviceId, declarations) ->
            if (declarations.size > 1) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.PROVIDER_AMBIGUOUS,
                    details = mapOf(
                        "service" to serviceId,
                        "providers" to declarations.map { it.providerId }.sorted().joinToString(),
                    ),
                    message = "The provider configuration is ambiguous for one service contract.",
                )
            }
        }
        providers.groupBy { it.providerId }.forEach { (providerId, declarations) ->
            if (declarations.size > 1) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.PROVIDER_DUPLICATE,
                    details = mapOf("provider" to providerId),
                    message = "The provider configuration declares one provider identity twice.",
                )
            }
        }

        providers.forEach { declaration ->
            if (configuration.target !in declaration.supportedTargets) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.PROVIDER_TARGET_UNSUPPORTED,
                    details = mapOf(
                        "provider" to declaration.providerId,
                        "target" to configuration.target.id,
                        "supported" to declaration.supportedTargets.map(KWebTarget::id).sorted().joinToString(),
                    ),
                    message = "A provider does not support the configuration target.",
                )
            }
            declaration.requiredFacts.forEach { factId ->
                val fact = factsById[factId]
                    ?: throw KWebServiceException(
                        code = KWebServiceErrorCode.CAPABILITY_MISSING,
                        details = mapOf("provider" to declaration.providerId, "fact" to factId),
                        message = "A provider requires a capability fact the configuration does not declare.",
                    )
                if (!fact.available) {
                    throw KWebServiceException(
                        code = KWebServiceErrorCode.CAPABILITY_UNAVAILABLE,
                        details = mapOf("provider" to declaration.providerId, "fact" to factId),
                        message = "A provider requires a capability fact that is unavailable on this target.",
                    )
                }
            }
        }

        val byServiceId = providers.associateBy { it.key.id }
        providers.forEach { declaration ->
            declaration.dependencies.forEach { dependency ->
                val providing = byServiceId[dependency.id]
                    ?: throw KWebServiceException(
                        code = KWebServiceErrorCode.PROVIDER_MISSING,
                        details = mapOf(
                            "provider" to declaration.providerId,
                            "dependency" to dependency.id,
                        ),
                        message = "A declared dependency has no provider in the configuration.",
                    )
                if (dependency.scope.ordinal > declaration.scope.ordinal) {
                    throw KWebServiceException(
                        code = KWebServiceErrorCode.DEPENDENCY_SCOPE_INVALID,
                        details = mapOf(
                            "provider" to declaration.providerId,
                            "dependency" to dependency.id,
                            "dependencyScope" to dependency.scope.name,
                            "providerScope" to declaration.scope.name,
                        ),
                        message = "A provider cannot depend on a narrower owner scope.",
                    )
                }
                if (!dependency.contract.contains(providing.contractVersion)) {
                    throw KWebServiceException(
                        code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                        details = mapOf(
                            "provider" to declaration.providerId,
                            "dependency" to dependency.id,
                            "contract" to dependency.contract.describe(),
                            "provided" to providing.contractVersion.toString(),
                        ),
                        message = "A dependency contract range does not contain the provided version.",
                    )
                }
            }
        }

        return topologicalOrder(providers)
    }

    private fun topologicalOrder(
        providers: List<KWebServiceProviderDeclaration<*>>,
    ): List<KWebServiceProviderDeclaration<*>> {
        val byServiceId = providers.associateBy { it.key.id }
        val remaining = providers
            .associateBy({ it.key.id }) { it.dependencies.map(KWebServiceDependency::id).toMutableSet() }
            .toMutableMap()
        val ordered = mutableListOf<KWebServiceProviderDeclaration<*>>()
        while (ordered.size < providers.size) {
            val ready = remaining.entries
                .filter { it.value.isEmpty() }
                .map { it.key }
                .sorted()
            if (ready.isEmpty()) {
                throw KWebServiceException(
                    code = KWebServiceErrorCode.PROVIDER_GRAPH_CYCLE,
                    details = mapOf("cycle" to remaining.keys.sorted().joinToString()),
                    message = "The provider dependency graph contains a cycle.",
                )
            }
            ready.forEach { serviceId ->
                val declaration = byServiceId.getValue(serviceId)
                ordered += declaration
                remaining.remove(serviceId)
                remaining.values.forEach { pending -> pending.remove(serviceId) }
            }
        }
        return ordered
    }
}
