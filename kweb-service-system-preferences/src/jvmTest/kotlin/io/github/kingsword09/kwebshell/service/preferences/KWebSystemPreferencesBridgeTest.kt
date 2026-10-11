package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebAppearanceSource
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The renderer surface of the system-preferences service: reads and
 * subscriptions are grant checked, the appearance override needs a gesture, and
 * no assistive-technology fact is ever renderer-visible.
 */
class KWebSystemPreferencesBridgeTest {
    private class RecordingPreferences : KWebSystemPreferences {
        override val descriptor: KWebServiceDescriptor = KWebSystemPreferences.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()

        val publishedEvents = MutableSharedFlow<KWebSystemPreferenceEvent>(replay = 4)
        override val events: Flow<KWebSystemPreferenceEvent> = publishedEvents

        var snapshotValue: KWebSystemPreferenceSnapshot = KWebSystemPreferenceSnapshot(
            colorScheme = KWebColorScheme.DARK,
            contrast = KWebContrastPreference.MORE,
            reducedMotion = true,
            accentColor = KWebAccentColor(0x10, 0x20, 0x30, KWebAccentColorSource.CONTROL_ACCENT),
            textScale = KWebTextScale(120),
            screenReader = true,
            sequence = 4uL,
        )
        val appearances = mutableListOf<KWebAppearanceSource>()

        override suspend fun capabilities(): KWebSystemPreferenceCapabilities =
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = KWebPreferenceFact.entries.toSet(),
                liveFacts = KWebPreferenceFact.entries.toSet(),
                sensitiveKeys = setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER),
            )

        override suspend fun snapshot(): KWebSystemPreferenceSnapshot = snapshotValue

        override suspend fun requestAppearance(source: KWebAppearanceSource): KWebAppearanceResult {
            appearances += source
            return KWebAppearanceResult(source, source, snapshotValue.sequence + 1uL)
        }

        override fun close() = Unit
    }

    private fun subject(hostCall: Boolean = false, mainFrame: Boolean = true) = KWebPolicySubject(
        engineId = "engine-1",
        profileId = "profile-1",
        pageId = "page-1",
        origin = "https://app.example",
        scope = KWebServiceScope.APPLICATION,
        isMainFrame = mainFrame,
        hostCall = hostCall,
    )

    private fun engine(
        grants: Set<KWebServiceGrant>,
        gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
    ) = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("preferences-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )

    private fun grant(operation: String) = KWebServiceGrant(
        KWebSystemPreferences.DESCRIPTOR.id,
        operation,
    )

    private fun mintGesture(gestures: KWebUserGestureRegistry) = gestures.mint(
        KWebGestureBinding(
            engineId = "engine-1",
            profileId = "profile-1",
            pageId = "page-1",
            origin = "https://app.example",
        ),
    )

    @Test
    fun aGrantedRendererReadsOnlyTheFactsItAskedFor() = runBlocking {
        val preferences = RecordingPreferences()
        val dispatcher = preferences.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val response = dispatcher.dispatch(
            """{"version":1,"method":"read","payload":{"facts":["COLOR_SCHEME","REDUCED_MOTION"]}}""",
        )
        assertTrue(response.contains("\"colorScheme\":\"DARK\""), response)
        assertTrue(response.contains("\"reducedMotion\":\"true\""), response)
        // A fact the renderer did not ask for stays empty, and no sensitive fact is
        // visible even though the service publishes it.
        assertTrue(response.contains("\"textScalePercent\":0"), response)
        assertTrue(response.contains("\"contrast\":\"\""), response)
        assertTrue(!response.contains("MORE"), response)
        assertTrue(!response.contains("VOICEOVER"), response)
    }

    @Test
    fun sensitiveAndUnknownFactsAreRejectedWithStableCodes() = runBlocking {
        val preferences = RecordingPreferences()
        val dispatcher = preferences.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val sensitive = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["SCREEN_READER"]}}""")
        }
        assertEquals(KWebSystemPreferenceErrorCode.FACT_NOT_RENDERER_VISIBLE, sensitive.code)
        val unknown = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["EVERY_SETTING"]}}""")
        }
        assertEquals(KWebSystemPreferenceErrorCode.FACT_UNKNOWN, unknown.code)
    }

    @Test
    fun aMissingGrantOrAChildFrameIsDeniedBeforeAnythingHappens() = runBlocking {
        val preferences = RecordingPreferences()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            preferences.bridgeDispatcher(engine(emptySet()), subject())
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["COLOR_SCHEME"]}}""")
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            preferences.bridgeDispatcher(engine(setOf(grant("read"))), subject(mainFrame = false))
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["COLOR_SCHEME"]}}""")
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
        assertEquals(0, preferences.appearances.size)
    }

    @Test
    fun theAppearanceOverrideRequiresAGestureAndReportsTheEffectiveSource() = runBlocking {
        val preferences = RecordingPreferences()
        val gestures = KWebUserGestureRegistry()
        val dispatcher = preferences.bridgeDispatcher(
            engine(setOf(grant("set-appearance")), gestures),
            subject(),
        )
        val gestureDenied = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"setAppearance","payload":{"source":"DARK"}}""")
        }
        assertEquals("service.user-gesture-required", gestureDenied.code)
        assertEquals(0, preferences.appearances.size)

        mintGesture(gestures)
        val applied = dispatcher.dispatch(
            """{"version":1,"method":"setAppearance","payload":{"source":"DARK"}}""",
        )
        assertEquals(listOf(KWebAppearanceSource.DARK), preferences.appearances)
        assertTrue(applied.contains("\"effective\":\"DARK\""), applied)

        // A gesture is one-shot, so the invalid request needs its own gesture to
        // reach the argument check.
        mintGesture(gestures)
        val unknownSource = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"setAppearance","payload":{"source":"NEON"}}""")
        }
        assertEquals(KWebSystemPreferenceErrorCode.APPEARANCE_INVALID, unknownSource.code)
    }

    @Test
    fun theSubscriptionIsGrantCheckedAndEndsOnAProviderFailure() = runBlocking {
        val preferences = RecordingPreferences()
        val denied = assertFailsWith<KWebBridgeException> {
            preferences.changesFlow(engine(emptySet()), subject(), listOf("COLOR_SCHEME"))
                .first()
        }
        assertEquals("service.permission-denied", denied.code)

        val granted = engine(setOf(grant("subscribe")))
        val flow = preferences.changesFlow(granted, subject(), listOf("COLOR_SCHEME"))
        val chunks = mutableListOf<String>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            .launch(kotlinx.coroutines.Dispatchers.Default) {
            flow.collect { snapshot -> chunks += snapshot.colorScheme }
        }
        try {
            preferences.publishedEvents.emit(
                KWebSystemPreferenceEvent.Changed(
                    snapshot = preferences.snapshotValue.copy(colorScheme = KWebColorScheme.LIGHT, sequence = 5uL),
                    changedFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
                    sequence = 5uL,
                ),
            )
            withTimeout(5_000) { while (chunks.isEmpty()) kotlinx.coroutines.delay(20) }
            assertEquals(listOf("LIGHT"), chunks)
        } finally {
            collector.cancel()
        }

        // A provider failure ends the subscription with its typed code.
        val failing = preferences.changesFlow(granted, subject(), listOf("COLOR_SCHEME"))
        val failure = assertFailsWith<KWebBridgeException> {
            kotlinx.coroutines.coroutineScope {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    failing.collect { }
                }
                kotlinx.coroutines.delay(50)
                preferences.publishedEvents.emit(
                    KWebSystemPreferenceEvent.Failed(
                        code = KWebSystemPreferenceErrorCode.NATIVE_FAILED,
                        sequence = 6uL,
                    ),
                )
                job.join()
            }
        }
        assertEquals(KWebSystemPreferenceErrorCode.NATIVE_FAILED, failure.code)
    }
}
