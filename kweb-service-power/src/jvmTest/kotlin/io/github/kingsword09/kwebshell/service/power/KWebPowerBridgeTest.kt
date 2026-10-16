package io.github.kingsword09.kwebshell.service.power

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceException
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The renderer surface of the power service: a granted exact-origin main frame
 * reads the bounded summary and may acquire one application-declared profile with a
 * user gesture, and an observation can never create a lease.
 */
class KWebPowerBridgeTest {
    private inner class RecordingPower : KWebPower {
        override val descriptor: KWebServiceDescriptor = KWebPower.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()
        override val events: Flow<KWebPowerEvent> = MutableSharedFlow()

        val acquired = mutableListOf<KWebPowerLeaseId>()
        private var live = 0

        override suspend fun capabilities(): KWebPowerCapabilities = KWebPowerCapabilities(
            providerId = "power.test",
            flags = setOf(
                KWebPowerCapability.SUSPEND_EVENTS,
                KWebPowerCapability.POWER_SOURCE_STATE,
                KWebPowerCapability.SYSTEM_SLEEP_BLOCKER,
                KWebPowerCapability.DISPLAY_SLEEP_BLOCKER,
            ),
            maxLeases = KWEB_POWER_MAX_LEASES,
            maxLeasesPerType = KWEB_POWER_MAX_LEASES_PER_TYPE,
            minLeaseSeconds = KWEB_POWER_MIN_LEASE_SECONDS,
            maxLeaseSeconds = KWEB_POWER_MAX_LEASE_SECONDS,
        )

        override suspend fun state(): KWebPowerState = KWebPowerState(
            onBattery = true,
            batteryPercent = 42,
            thermal = null,
            locked = null,
        )

        override suspend fun acquire(request: KWebPowerLeaseRequest): KWebPowerLease {
            acquired += request.id
            live += 1
            return KWebPowerLease(request.id, request.type, request.reason, request.maxDurationSeconds, request.maxDurationSeconds, 0)
        }

        override suspend fun renew(id: KWebPowerLeaseId): KWebPowerLease =
            KWebPowerLease(id, KWebPowerBlockerType.SYSTEM_SLEEP, "Fixture lease", 600, 600, 1)

        override suspend fun release(id: KWebPowerLeaseId): Boolean {
            if (live == 0) return false
            live -= 1
            return true
        }

        override suspend fun leases(): List<KWebPowerLease> = List(live) { index ->
            KWebPowerLease(
                KWebPowerLeaseId("lease-$index"),
                KWebPowerBlockerType.SYSTEM_SLEEP,
                "Fixture lease reason",
                600,
                600,
                0,
            )
        }

        override suspend fun acquireProfile(id: KWebPowerLeaseProfileId): KWebPowerLease {
            if (id != KWebPowerLeaseProfileId("render")) {
                throw KWebServiceException(
                    KWebPowerErrorCode.PROFILE_UNKNOWN,
                    mapOf("profile" to id.value),
                    "The renderer named an undeclared profile.",
                )
            }
            return acquire(
                KWebPowerLeaseRequest(KWebPowerLeaseId("profile-render"), KWebPowerBlockerType.SYSTEM_SLEEP, "Report", 600),
            )
        }

        override fun close() = Unit
    }

    private fun subject(mainFrame: Boolean = true) = KWebPolicySubject(
        engineId = "engine-1",
        profileId = "profile-1",
        pageId = "page-1",
        origin = "https://app.example",
        scope = KWebServiceScope.APPLICATION,
        isMainFrame = mainFrame,
        hostCall = false,
    )

    private fun engine(
        grants: Set<KWebServiceGrant>,
        gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
    ) = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("power-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )

    private fun grant(operation: String) = KWebServiceGrant(KWebPower.DESCRIPTOR.id, operation)

    private fun minted(gestures: KWebUserGestureRegistry) = gestures.mint(
        KWebGestureBinding("engine-1", "profile-1", "page-1", "https://app.example"),
    )

    @Test
    fun aGrantedRendererReadsOnlyTheSummaryItAskedFor() = runBlocking {
        val power = RecordingPower()
        val dispatcher = power.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val response = dispatcher.dispatch(
            """{"version":1,"method":"read","payload":{"facts":["supported","on-battery","lease-count"]}}""",
        )
        assertTrue(response.contains("\"supported\":true"), response)
        assertTrue(response.contains("\"onBattery\":true"), response)
        assertTrue(response.contains("\"leaseCount\":0"), response)
        // No lease identifier, reason, or asserted type is exposed.
        assertTrue(!response.contains("lease-0"), response)
        assertTrue(!response.contains("Fixture lease reason"), response)
        assertTrue(!response.contains("SYSTEM_SLEEP"), response)
        // A read never creates a lease.
        assertTrue(power.acquired.isEmpty(), "a read created a lease")

        val partial = dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        assertTrue(partial.contains("\"onBattery\":false"), partial)
        assertTrue(partial.contains("\"leaseCount\":0"), partial)
    }

    @Test
    fun unknownAndNonVisibleFactsAreRejectedWithStableCodes() = runBlocking {
        val power = RecordingPower()
        val dispatcher = power.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val notVisible = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["assertion-id"]}}""")
        }
        assertEquals(KWebPowerErrorCode.FACT_NOT_RENDERER_VISIBLE, notVisible.code)
        val unknown = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["EVERY_LEASE"]}}""")
        }
        assertEquals(KWebPowerErrorCode.FACT_UNKNOWN, unknown.code)
    }

    @Test
    fun aMissingGrantOrAChildFrameIsDeniedBeforeAnythingHappens() = runBlocking {
        val power = RecordingPower()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            power.bridgeDispatcher(engine(emptySet()), subject())
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            power.bridgeDispatcher(engine(setOf(grant("read"))), subject(mainFrame = false))
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
    }

    @Test
    fun profileAcquisitionRequiresAGestureAndADeclaredProfile() = runBlocking {
        val power = RecordingPower()
        val gestures = KWebUserGestureRegistry()
        val dispatcher = power.bridgeDispatcher(engine(setOf(grant("acquire-profile")), gestures), subject())

        // Without a minted gesture nothing happens.
        val noGesture = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"acquireProfile","payload":{"profileId":"render"}}""")
        }
        assertEquals("service.user-gesture-required", noGesture.code)
        assertTrue(power.acquired.isEmpty(), "a denied acquisition reached the service")

        // A minted gesture authorizes exactly one declared profile.
        minted(gestures)
        val accepted = dispatcher.dispatch("""{"version":1,"method":"acquireProfile","payload":{"profileId":"render"}}""")
        assertTrue(accepted.contains("\"accepted\":true"), accepted)
        assertEquals(listOf(KWebPowerLeaseId("profile-render")), power.acquired)

        // An undeclared profile surfaces its typed code after the grant and gesture.
        minted(gestures)
        val undeclared = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"acquireProfile","payload":{"profileId":"absent"}}""")
        }
        assertEquals(KWebPowerErrorCode.PROFILE_UNKNOWN, undeclared.code)

        // Without the grant nothing reaches the service.
        val denied = assertFailsWith<KWebBridgeException> {
            power.bridgeDispatcher(engine(emptySet(), gestures), subject())
                .dispatch("""{"version":1,"method":"acquireProfile","payload":{"profileId":"render"}}""")
        }
        assertEquals("service.permission-denied", denied.code)
    }
}
