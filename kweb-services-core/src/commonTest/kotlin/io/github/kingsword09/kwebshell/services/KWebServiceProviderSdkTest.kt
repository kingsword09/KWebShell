package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KWebServiceProviderSdkTest {
    private val closeOrder = mutableListOf<String>()
    private val createdServices = mutableMapOf<String, FakeService>()

    private fun descriptor(
        id: String,
        version: KWebServiceVersion = KWebServiceVersion(1, 0, 0),
        scope: KWebServiceScope = KWebServiceScope.APPLICATION,
    ): KWebServiceDescriptor = KWebServiceDescriptor(
        id = id,
        version = version,
        scope = scope,
        operations = setOf(
            KWebServiceOperationDescriptor(
                id = "op",
                schemaVersion = 1,
                rendererPermission = null,
                requiresUserGesture = false,
            ),
        ),
        requiredCapabilities = emptySet<KWebCapability>(),
        supportedTargets = KWebTarget.supported,
    )

    private fun <T : KWebNativeService> testKey(
        id: String,
        contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)),
    ): KWebServiceKey<T> = object : KWebServiceKey<T> {
        override val id: String = id
        override val contract: KWebServiceVersionRange = contract
    }

    private fun declaration(
        serviceId: String,
        providerId: String = "$serviceId.default",
        version: KWebServiceVersion = KWebServiceVersion(1, 0, 0),
        scope: KWebServiceScope = KWebServiceScope.APPLICATION,
        targets: Set<KWebTarget> = KWebTarget.supported,
        requiredFacts: Set<String> = emptySet(),
        dependencies: Set<KWebServiceDependency> = emptySet(),
        onCreate: (KWebServiceProviderEnvironment) -> Unit = {},
        factoryError: Throwable? = null,
        contractRange: KWebServiceVersionRange = KWebServiceVersionRange.exact(version),
    ): KWebServiceProviderDeclaration<KWebNativeService> = KWebServiceProviderDeclaration(
        providerId = providerId,
        key = testKey(serviceId, contractRange),
        contractVersion = version,
        scope = scope,
        supportedTargets = targets,
        requiredFacts = requiredFacts,
        dependencies = dependencies,
        factory = { environment ->
            onCreate(environment)
            factoryError?.let { throw it }
            val service = FakeService(descriptor(serviceId, version, scope), closeOrder)
            createdServices[serviceId] = service
            service
        },
    )

    private fun configuration(
        vararg providers: KWebServiceProviderDeclaration<*>,
        target: KWebTarget = KWebTarget.parse("macos-arm64"),
        facts: Set<KWebCapabilityFact> = emptySet(),
    ): KWebServiceProviderConfiguration =
        KWebServiceProviderConfiguration(target, "test-owner", facts, providers.toList())

    @Test
    fun startupIsTopologicallyDeterministicAndCloseIsExactReverse() {
        val registry = KWebNativeServiceRegistry()
        val report = registry.installProviders(
            configuration(
                declaration("a", dependencies = setOf(KWebServiceDependency("b", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION))),
                declaration("b", dependencies = setOf(KWebServiceDependency("c", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION))),
                declaration("c"),
            ),
        )
        assertEquals(listOf("c", "b", "a"), report.startupOrder)
        assertEquals(listOf("c.default", "b.default", "a.default"), report.providerOrder)

        registry.close()
        assertEquals(listOf("a", "b", "c"), closeOrder)
    }

    @Test
    fun duplicateServiceIdIsAmbiguous() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("shared", providerId = "one"),
                    declaration("shared", providerId = "two"),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_AMBIGUOUS, failure.code)
    }

    @Test
    fun duplicateProviderIdentityFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("one", providerId = "same"),
                    declaration("two", providerId = "same"),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_DUPLICATE, failure.code)
    }

    @Test
    fun missingProviderForDeclaredDependencyFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration(
                        "b",
                        dependencies = setOf(
                            KWebServiceDependency("ghost", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION),
                        ),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_MISSING, failure.code)
        assertEquals("ghost", failure.details["dependency"])
    }

    @Test
    fun unsupportedTargetFailsBeforeAnyFactoryRuns() {
        val registry = KWebNativeServiceRegistry()
        val failure = assertFailsWith<KWebServiceException> {
            registry.installProviders(
                configuration(
                    declaration("only", targets = setOf(KWebTarget.parse("linux-x64"))),
                    target = KWebTarget.parse("macos-arm64"),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_TARGET_UNSUPPORTED, failure.code)
        assertTrue(createdServices.isEmpty(), "No factory may run when the target is unsupported.")
    }

    @Test
    fun undeclaredDependencyQueryFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("a"),
                    declaration(
                        "b",
                        // b never declares a, so its factory must not reach it.
                        onCreate = { environment -> environment.dependency(testKey<KWebNativeService>("a")) },
                        dependencies = emptySet(),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.DEPENDENCY_UNDECLARED, failure.code)
        assertEquals(1, createdServices.getValue("a").closeCount, "The typed failure must still roll back created services.")
    }

    @Test
    fun dependencyOnNarrowerScopeFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("page-service", scope = KWebServiceScope.PAGE),
                    declaration(
                        "app-service",
                        scope = KWebServiceScope.APPLICATION,
                        dependencies = setOf(
                            KWebServiceDependency("page-service", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.PAGE),
                        ),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.DEPENDENCY_SCOPE_INVALID, failure.code)
    }

    @Test
    fun unsatisfiedDependencyRangeFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("a", version = KWebServiceVersion(1, 0, 0)),
                    declaration(
                        "b",
                        dependencies = setOf(
                            KWebServiceDependency("a", KWebServiceVersionRange.exact(KWebServiceVersion(2, 0, 0)), KWebServiceScope.APPLICATION),
                        ),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.VERSION_INCOMPATIBLE, failure.code)
    }

    @Test
    fun dependencyGraphCycleFails() {
        val failure = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration(
                        "a",
                        dependencies = setOf(KWebServiceDependency("b", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
                    ),
                    declaration(
                        "b",
                        dependencies = setOf(KWebServiceDependency("a", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_GRAPH_CYCLE, failure.code)
        assertTrue(failure.details["cycle"]!!.contains("a"))
        assertTrue(createdServices.isEmpty(), "No factory may run when the graph is cyclic.")
    }

    @Test
    fun failedStartupRollsBackCreatedServicesAndSticks() {
        val registry = KWebNativeServiceRegistry()
        val factoryError = IllegalStateException("native init exploded")
        val failure = assertFailsWith<KWebServiceException> {
            registry.installProviders(
                configuration(
                    declaration("a"),
                    declaration(
                        "b",
                        dependencies = setOf(KWebServiceDependency("a", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
                        factoryError = factoryError,
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_STARTUP_FAILED, failure.code)
        assertEquals("factory-failed", failure.details["reason"])
        assertEquals(1, createdServices.getValue("a").closeCount)
        assertEquals(KWebLifecycleState.FAILED, registry.lifecycle.value)

        // The failure is sticky: retries and close surface the same typed failure.
        val retry = assertFailsWith<KWebServiceException> {
            registry.installProviders(configuration(declaration("a")))
        }
        assertSame(failure, retry)
        val closeFailure = assertFailsWith<KWebServiceException> { registry.close() }
        assertSame(failure, closeFailure)
        assertEquals(1, createdServices.getValue("a").closeCount, "Rollback closes each created resource exactly once.")
    }

    @Test
    fun rollbackLeavesIndependentServicesInstalled() {
        val registry = KWebNativeServiceRegistry()
        val failure = assertFailsWith<KWebServiceException> {
            registry.installProviders(
                configuration(
                    declaration("independent"),
                    declaration(
                        "broken",
                        dependencies = setOf(KWebServiceDependency("independent", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
                        factoryError = IllegalStateException("broken factory"),
                    ),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.PROVIDER_STARTUP_FAILED, failure.code)
        assertEquals(1, createdServices.getValue("independent").closeCount)
        // The registry has failed, so even the independent service is no longer reachable.
        val requireFailure = assertFailsWith<KWebServiceException> {
            registry.require(testKey<KWebNativeService>("independent"))
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, requireFailure.code)
    }

    @Test
    fun missingOrUnavailableCapabilityFactsFailBeforeFactories() {
        val missing = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("a", requiredFacts = setOf("secure-storage")),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.CAPABILITY_MISSING, missing.code)

        createdServices.clear()
        val unavailable = assertFailsWith<KWebServiceException> {
            KWebNativeServiceRegistry().installProviders(
                configuration(
                    declaration("a", requiredFacts = setOf("secure-storage")),
                    facts = setOf(KWebCapabilityFact("secure-storage", available = false)),
                ),
            )
        }
        assertEquals(KWebServiceErrorCode.CAPABILITY_UNAVAILABLE, unavailable.code)
        assertTrue(createdServices.isEmpty())
    }

    @Test
    fun environmentRestrictsFactsAndResolvesDeclaredDependencies() {
        val registry = KWebNativeServiceRegistry()
        lateinit var capturedEnvironment: KWebServiceProviderEnvironment
        registry.installProviders(
            configuration(
                declaration("a"),
                declaration(
                    "b",
                    requiredFacts = setOf("declared-fact"),
                    dependencies = setOf(KWebServiceDependency("a", KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
                    onCreate = { environment ->
                        capturedEnvironment = environment
                        assertSame(createdServices.getValue("a"), environment.dependency(testKey<KWebNativeService>("a")))
                        val fact = environment.fact("declared-fact")
                        assertEquals(true, fact.available)
                    },
                ),
                facts = setOf(
                    KWebCapabilityFact("declared-fact", available = true, details = mapOf("kind" to "test")),
                ),
            ),
        )
        assertEquals(KWebServiceScope.APPLICATION, capturedEnvironment.scope)
        assertEquals("test-owner", capturedEnvironment.ownerId)

        val undeclaredFact = assertFailsWith<KWebServiceException> { capturedEnvironment.fact("other") }
        assertEquals(KWebServiceErrorCode.CAPABILITY_MISSING, undeclaredFact.code)
        registry.close()
        val afterClose = assertFailsWith<KWebServiceException> {
            capturedEnvironment.dependency(testKey<KWebNativeService>("a"))
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, afterClose.code)
    }

    @Test
    fun startAfterOwnerCloseFailsTyped() {
        val registry = KWebNativeServiceRegistry()
        registry.installProviders(configuration(declaration("a")))
        registry.close()
        val failure = assertFailsWith<KWebServiceException> {
            registry.installProviders(configuration(declaration("b")))
        }
        assertEquals(KWebServiceErrorCode.OWNER_CLOSED, failure.code)
    }

    @Test
    fun rangeKeysMatchInstalledContracts() {
        val registry = KWebNativeServiceRegistry()
        val service = FakeService(descriptor("range", KWebServiceVersion(1, 2, 0)), closeOrder)
        registry.install(testKey<KWebNativeService>("range", KWebServiceVersionRange.atLeast(KWebServiceVersion(1, 0, 0))), service)
        assertSame(
            service,
            registry.require(testKey<KWebNativeService>("range", KWebServiceVersionRange.between(KWebServiceVersion(1, 0, 0), KWebServiceVersion(1, 9, 9)))),
        )
        val incompatible = assertFailsWith<KWebServiceException> {
            registry.require(testKey<KWebNativeService>("range", KWebServiceVersionRange.exact(KWebServiceVersion(2, 0, 0))))
        }
        assertEquals(KWebServiceErrorCode.VERSION_INCOMPATIBLE, incompatible.code)
        registry.close()
    }

    @Test
    fun catalogIsDeterministicAndNamesEveryProvider() {
        val configuration = configuration(
            declaration("a-service", providerId = "a.provider"),
            declaration(
                "b-service",
                providerId = "b.provider",
                dependencies = setOf(KWebServiceDependency("a-service", KWebServiceVersionRange.atLeast(KWebServiceVersion(1, 0, 0)), KWebServiceScope.APPLICATION)),
            ),
        )
        val first = KWebServiceProviderCatalog.of(configuration.catalog())
        val second = KWebServiceProviderCatalog.of(configuration.catalog())
        assertEquals(first, second)
        assertTrue(first.contains("\"providerId\": \"a.provider\""))
        assertTrue(first.contains("\"providerId\": \"b.provider\""))
        assertTrue(first.contains(">=1.0.0"))
        assertTrue(first.contains("\"target\": \"macos-arm64\""))
        // Packaged metadata names contracts, not implementation classes.
        assertTrue(!first.contains("class "))
    }

    @Test
    fun versionRangeSemantics() {
        val exact = KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0))
        val atLeast = KWebServiceVersionRange.atLeast(KWebServiceVersion(1, 0, 0))
        val between = KWebServiceVersionRange.between(KWebServiceVersion(1, 0, 0), KWebServiceVersion(2, 0, 0))
        assertEquals("1.0.0", exact.describe())
        assertEquals(">=1.0.0", atLeast.describe())
        assertEquals(">=1.0.0 <=2.0.0", between.describe())
        assertEquals(true, exact.contains(KWebServiceVersion(1, 0, 0)))
        assertEquals(false, exact.contains(KWebServiceVersion(1, 0, 1)))
        assertEquals(true, atLeast.contains(KWebServiceVersion(9, 9, 9)))
        assertEquals(true, between.contains(KWebServiceVersion(2, 0, 0)))
        assertEquals(false, between.contains(KWebServiceVersion(2, 0, 1)))
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebServiceVersionRange.between(KWebServiceVersion(2, 0, 0), KWebServiceVersion(1, 0, 0))
        }
        assertEquals("service.version-range-invalid", failure.code)
    }

    private class FakeService(
        override val descriptor: KWebServiceDescriptor,
        private val closeOrder: MutableList<String>,
    ) : KWebNativeService {
        private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            mutableLifecycle.value = KWebLifecycleState.CLOSED
            closeOrder += descriptor.id
        }
    }
}
