package io.github.kingsword09.kwebshell.services.policy

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private const val ORIGIN = "https://app.example"

private fun operation(
    permission: String? = "native.test.op",
    gesture: Boolean = false,
    osConsent: Boolean = false,
) = KWebServiceOperationDescriptor(
    id = "op",
    schemaVersion = 1,
    rendererPermission = permission,
    requiresUserGesture = gesture,
    requiresOsConsent = osConsent,
)

private fun subject(
    pageId: String = "page-1",
    origin: String = ORIGIN,
    mainFrame: Boolean = true,
    hostCall: Boolean = false,
) = KWebPolicySubject(
    engineId = "engine-1",
    profileId = "profile-1",
    pageId = pageId,
    origin = origin,
    scope = KWebServiceScope.APPLICATION,
    isMainFrame = mainFrame,
    hostCall = hostCall,
)

private fun binding(pageId: String = "page-1", origin: String = ORIGIN) = KWebGestureBinding(
    engineId = "engine-1",
    profileId = "profile-1",
    pageId = pageId,
    origin = origin,
)

private class StubOsConsent(
    private var status: KWebConsentStatus,
    override val facility: String = "test.facility",
) : KWebOsConsentProvider {
    val queries = mutableListOf<KWebConsentRequest>()

    override suspend fun status(request: KWebConsentRequest): KWebConsentStatus {
        queries += request
        return status
    }
}

class KWebServicePolicyEngineTest {
    private fun engine(
        grants: Set<KWebServiceGrant> = setOf(KWebServiceGrant("test-service", "op")),
        gestures: KWebUserGestureIssuer = KWebUserGestureRegistry(),
        store: KWebConsentStore = KWebInMemoryConsentStore("app"),
        osConsent: KWebOsConsentProvider? = null,
        audit: KWebPolicyAudit = KWebPolicyAudit(),
    ): KWebServicePolicyEngine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
        consentStore = store,
        osConsent = osConsent,
        audit = audit,
    )

    @Test
    fun missingRendererGrantDeniesWithoutBurningTheGesture() = runBlocking {
        val issuer = KWebUserGestureRegistry()
        issuer.mint(binding())
        val policy = engine(grants = emptySet(), gestures = issuer)
        val verdict = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebServiceErrorCode.PERMISSION_DENIED, verdict.reasonCode)
        // Precedence: the renderer grant is evaluated before the gesture is spent.
        assertEquals(null, verdict.gestureResult)
    }

    @Test
    fun childFrameRendererCallsAreDenied() = runBlocking {
        val policy = engine()
        val verdict = policy.authorize(subject(mainFrame = false), "test-service", operation())
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebServicePolicyEngine.REASON_FRAME, verdict.reasonCode)
    }

    @Test
    fun gestureIsRequiredConsumedOnceAndReplayIsDenied() = runBlocking {
        val issuer = KWebUserGestureRegistry()
        val policy = engine(gestures = issuer)

        val withoutGesture = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.DENY, withoutGesture.decision)
        assertEquals(KWebServiceErrorCode.USER_GESTURE_REQUIRED, withoutGesture.reasonCode)
        assertEquals(KWebGestureConsumeResult.NOT_MINTED, withoutGesture.gestureResult)

        issuer.mint(binding())
        val allowed = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.ALLOW, allowed.decision)
        assertEquals(KWebGestureConsumeResult.CONSUMED, allowed.gestureResult)

        val replay = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.DENY, replay.decision)
        assertEquals(KWebGestureConsumeResult.REPLAYED, replay.gestureResult)
    }

    @Test
    fun gestureIsBoundToOriginAndPage() = runBlocking {
        val issuer = KWebUserGestureRegistry()
        val policy = engine(gestures = issuer)
        issuer.mint(binding(pageId = "page-1"))

        val otherOrigin = policy.authorize(
            subject(pageId = "page-1", origin = "https://other.example"),
            "test-service",
            operation(gesture = true),
        )
        assertEquals(KWebGestureConsumeResult.WRONG_ORIGIN, otherOrigin.gestureResult)

        val otherPage = policy.authorize(subject(pageId = "page-2"), "test-service", operation(gesture = true))
        assertEquals(KWebGestureConsumeResult.NOT_MINTED, otherPage.gestureResult)
    }

    @Test
    fun expiredGesturesAreDenied() = runBlocking {
        // The virtual clock shifts mint marks backward, so the minted token is
        // already past its expiry when the policy engine consumes it.
        var shift = Duration.ZERO
        val virtualClock = object : TimeSource {
            override fun markNow(): TimeSource.Monotonic.ValueTimeMark = TimeSource.Monotonic.markNow() - shift
        }
        val issuer = KWebUserGestureRegistry(expiry = 50.milliseconds, clock = virtualClock)
        val policy = engine(gestures = issuer)

        shift = 200.milliseconds
        issuer.mint(binding())
        val verdict = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebGestureConsumeResult.EXPIRED, verdict.gestureResult)
    }

    @Test
    fun navigationInvalidatesOutstandingGestures() = runBlocking {
        val issuer = KWebUserGestureRegistry()
        val policy = engine(gestures = issuer)
        issuer.mint(binding())
        issuer.invalidateNavigation("page-1")
        val verdict = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebGestureConsumeResult.INVALIDATED_BY_NAVIGATION, verdict.gestureResult)
    }

    @Test
    fun ownerCloseRejectsLaterGestures() = runBlocking {
        val issuer = KWebUserGestureRegistry()
        val policy = engine(gestures = issuer)
        issuer.mint(binding())
        issuer.invalidatePage("page-1")
        val verdict = policy.authorize(subject(), "test-service", operation(gesture = true))
        assertEquals(KWebGestureConsumeResult.OWNER_CLOSED, verdict.gestureResult)
        val mintFailure = assertFailsWith<KWebConfigurationException> { issuer.mint(binding()) }
        assertEquals("service.gesture.owner-closed", mintFailure.code)
    }

    @Test
    fun osConsentDenialDeniesAndIsNeverBypassed() = runBlocking {
        val consent = StubOsConsent(KWebConsentStatus.DENIED)
        val policy = engine(osConsent = consent)
        val denied = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.DENY, denied.decision)
        assertEquals(KWebServicePolicyEngine.REASON_CONSENT, denied.reasonCode)
        assertEquals(KWebConsentStatus.DENIED, denied.consentStatus)
        assertEquals(1, consent.queries.size)
        assertEquals("test.facility", consent.queries.single().facility)

        val restricted = StubOsConsent(KWebConsentStatus.RESTRICTED)
        val restrictedPolicy = engine(osConsent = restricted)
        val verdict = restrictedPolicy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebConsentStatus.RESTRICTED, verdict.consentStatus)
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
    }

    @Test
    fun missingOsConsentProviderIsDeniedNotConfigured() = runBlocking {
        val policy = engine(osConsent = null)
        val verdict = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebConsentStatus.NOT_CONFIGURED, verdict.consentStatus)
    }

    @Test
    fun promptIsRequiredUntilAPersistentUserDecisionExists() = runBlocking {
        val policy = engine(osConsent = StubOsConsent(KWebConsentStatus.GRANTED))
        val prompt = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, prompt.decision)
        assertEquals(KWebServicePolicyEngine.REASON_PROMPT, prompt.reasonCode)
        // No implicit allow: the operation stays blocked without a decision.
        val stillPrompt = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, stillPrompt.decision)
    }

    @Test
    fun persistedGrantAllowsAndRevocationReopensPrompt() = runBlocking {
        val store = KWebInMemoryConsentStore("app")
        val revocations = mutableListOf<KWebConsentRevocation>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { store.revocations.collect { revocations += it } }
        val policy = engine(osConsent = StubOsConsent(KWebConsentStatus.GRANTED), store = store)
        val request = KWebConsentRequest("test-service", "op", ORIGIN, "test.facility")

        assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, policy.authorize(subject(), "test-service", operation(osConsent = true)).decision)

        store.record(request, granted = true, decidedBy = "user")
        val allowed = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.ALLOW, allowed.decision)
        assertEquals(KWebServicePolicyEngine.REASON_ALLOWED, allowed.reasonCode)

        store.revoke(request, decidedBy = "user")
        yield()
        val afterRevoke = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, afterRevoke.decision)
        collector.cancel()
        assertEquals(1, revocations.size)
        assertEquals("user", revocations.single().decidedBy)
    }

    @Test
    fun persistedDenialDeniesEvenWhenTheOsGrants() = runBlocking {
        val store = KWebInMemoryConsentStore("app")
        store.record(KWebConsentRequest("test-service", "op", ORIGIN, "test.facility"), granted = false, decidedBy = "user")
        val policy = engine(osConsent = StubOsConsent(KWebConsentStatus.GRANTED), store = store)
        val verdict = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.DENY, verdict.decision)
        assertEquals(KWebServicePolicyEngine.REASON_REVOKED, verdict.reasonCode)
    }

    @Test
    fun concurrentPromptsEachRequireExplicitUserDecision() = runBlocking {
        val store = KWebInMemoryConsentStore("app")
        val policy = engine(osConsent = StubOsConsent(KWebConsentStatus.GRANTED), store = store)
        coroutineScope {
            val first = async { policy.authorize(subject(pageId = "page-1"), "test-service", operation(osConsent = true)) }
            val second = async { policy.authorize(subject(pageId = "page-2"), "test-service", operation(osConsent = true)) }
            assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, first.await().decision)
            assertEquals(KWebPolicyDecision.PROMPT_REQUIRED, second.await().decision)
        }
        store.record(KWebConsentRequest("test-service", "op", ORIGIN, "test.facility"), granted = true, decidedBy = "user")
        val allowed = policy.authorize(subject(), "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.ALLOW, allowed.decision)
    }

    @Test
    fun hostCallsBypassOnlyRendererGrantsAndFrames() = runBlocking {
        val consent = StubOsConsent(KWebConsentStatus.DENIED)
        val policy = engine(grants = emptySet(), osConsent = consent)
        val hostSubject = subject(mainFrame = false, hostCall = true)

        val granted = policy.authorize(hostSubject, "test-service", operation())
        assertEquals(KWebPolicyDecision.ALLOW, granted.decision)

        // OS consent is never bypassed by host calls.
        val consented = policy.authorize(hostSubject, "test-service", operation(osConsent = true))
        assertEquals(KWebPolicyDecision.DENY, consented.decision)
        assertEquals(KWebConsentStatus.DENIED, consented.consentStatus)
    }

    @Test
    fun auditIsOrderedAndBounded() = runBlocking {
        val audit = KWebPolicyAudit(capacity = 4)
        val policy = engine(osConsent = StubOsConsent(KWebConsentStatus.GRANTED), audit = audit)
        repeat(6) { index ->
            policy.authorize(subject(pageId = "page-$index"), "test-service", operation())
        }
        val snapshot = audit.snapshot()
        assertEquals(4, snapshot.size, "The audit trail is bounded.")
        assertEquals(listOf(3L, 4L, 5L, 6L), snapshot.map { it.sequence })
        assertEquals(
            List(4) { KWebPolicyDecision.ALLOW },
            snapshot.map { it.decision },
        )
        assertEquals(ORIGIN, snapshot.first().origin)
    }

    @Test
    fun invalidAuditCapacityFails() {
        val failure = assertFailsWith<KWebConfigurationException> { KWebPolicyAudit(capacity = 0) }
        assertEquals("service.policy.audit-capacity-invalid", failure.code)
    }
}
