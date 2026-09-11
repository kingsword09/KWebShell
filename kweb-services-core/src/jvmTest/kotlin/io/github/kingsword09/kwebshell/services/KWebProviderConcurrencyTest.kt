package io.github.kingsword09.kwebshell.services

import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Startup and close are serialized on the registry lock: a close racing a
 * startup waits for the startup attempt to finish, then closes every created
 * service exactly once. Nothing leaks in either direction.
 */
private fun serviceDescriptor(id: String): KWebServiceDescriptor = KWebServiceDescriptor(
    id = id,
    version = KWebServiceVersion(1, 0, 0),
    scope = KWebServiceScope.APPLICATION,
    operations = setOf(KWebServiceOperationDescriptor("op", 1, null, false)),
    requiredCapabilities = emptySet<KWebCapability>(),
    supportedTargets = KWebTarget.supported,
)

class KWebProviderConcurrencyTest {
    private val closeOrder = mutableListOf<String>()

    private class CountingService(
        id: String,
        private val closeOrder: MutableList<String>,
    ) : KWebNativeService {
        private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
        override val descriptor = serviceDescriptor(id)
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
            closeOrder += descriptor.id
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }

    @Test
    fun closeRacingStartupClosesEveryCreatedServiceExactlyOnce() {
        val registry = KWebNativeServiceRegistry()
        val startupEntered = CountDownLatch(1)
        val releaseStartup = CountDownLatch(1)
        val installResult = AtomicReference<KWebServiceProviderInstallReport?>()

        val installer = Thread {
            installResult.set(
                registry.installProviders(
                    KWebServiceProviderConfiguration(
                        KWebTarget.parse("macos-arm64"),
                        "concurrency-owner",
                        emptySet(),
                        listOf(
                            KWebServiceProviderDeclaration(
                                providerId = "slow.provider",
                                key = object : KWebServiceKey<KWebNativeService> {
                                    override val id: String = "slow"
                                    override val contract: KWebServiceVersionRange =
                                        KWebServiceVersionRange.exact(KWebServiceVersion(1, 0, 0))
                                },
                                contractVersion = KWebServiceVersion(1, 0, 0),
                                scope = KWebServiceScope.APPLICATION,
                                supportedTargets = KWebTarget.supported,
                                requiredFacts = emptySet(),
                                dependencies = emptySet(),
                                factory = { _ ->
                                    val service = CountingService("slow", closeOrder)
                                    startupEntered.countDown()
                                    assertTrue(releaseStartup.await(10, TimeUnit.SECONDS))
                                    service
                                },
                            ),
                        ),
                    ),
                ),
            )
        }
        installer.start()
        assertTrue(startupEntered.await(10, TimeUnit.SECONDS))

        // Initiate close while the startup attempt is still blocked inside the factory.
        val closer = Thread { registry.close() }
        closer.start()

        // Release the factory; the install finishes, then the pending close runs.
        releaseStartup.countDown()
        installer.join(10_000)
        closer.join(10_000)
        assertEquals(listOf("slow"), installResult.get()?.startupOrder)
        assertEquals(listOf("slow"), closeOrder)
        assertEquals(KWebLifecycleState.CLOSED, registry.lifecycle.value)
    }
}
