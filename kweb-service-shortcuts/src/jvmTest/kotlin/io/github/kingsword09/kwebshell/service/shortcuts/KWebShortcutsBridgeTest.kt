package io.github.kingsword09.kwebshell.service.shortcuts

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
 * The renderer surface of the shortcut service: a granted exact-origin main frame
 * reads the bounded summary and may dispatch one application-declared command with
 * a user gesture, and a page can never register, read, or replace an accelerator.
 */
class KWebShortcutsBridgeTest {
    private inner class RecordingShortcuts : KWebShortcuts {
        override val descriptor: KWebServiceDescriptor = KWebShortcuts.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()
        override val events: Flow<KWebShortcutEvent> = MutableSharedFlow()

        val dispatched = mutableListOf<KWebShortcutCommandId>()

        override suspend fun capabilities(): KWebShortcutCapabilities = KWebShortcutCapabilities(
            providerId = "shortcuts.test",
            flags = setOf(KWebShortcutCapability.GLOBAL_REGISTRATION, KWebShortcutCapability.INVOCATION_REPORTING),
            modifiers = setOf(KWebShortcutModifier.CONTROL, KWebShortcutModifier.ALT),
            mediaKeys = emptySet(),
            maxRegistrations = KWEB_SHORTCUTS_MAX_REGISTRATIONS,
        )

        override suspend fun register(registration: KWebShortcutRegistration): KWebShortcutRegistrationResult =
            KWebShortcutRegistrationResult(registration.id, 1u)

        override suspend fun replaceAll(registrations: List<KWebShortcutRegistration>): KWebShortcutReplacementResult =
            KWebShortcutReplacementResult(registrations.map { it.id }, emptyList(), 2u)

        override suspend fun unregister(id: KWebShortcutId): KWebShortcutUnregistration =
            KWebShortcutUnregistration(id, wasRegistered = true)

        override suspend fun isRegistered(id: KWebShortcutId): Boolean = true

        override suspend fun registrations(): List<KWebShortcutRegistration> = listOf(
            KWebShortcutRegistration(
                KWebShortcutId("copy"),
                KWebShortcutAccelerator(
                    setOf(KWebShortcutModifier.CONTROL, KWebShortcutModifier.ALT),
                    KWebShortcutKey.Named(KWebShortcutNamedKey.A),
                ),
                KWebShortcutCommandId("text.copy"),
            ),
        )

        override suspend fun dispatch(commandId: KWebShortcutCommandId): KWebShortcutId {
            dispatched += commandId
            if (commandId != KWebShortcutCommandId("text.copy")) {
                throw KWebServiceException(
                    KWebShortcutErrorCode.COMMAND_UNDECLARED,
                    mapOf("command" to commandId.value),
                    "The renderer named an undeclared command.",
                )
            }
            return KWebShortcutId("copy")
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
        consentStore = KWebInMemoryConsentStore("shortcuts-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )

    private fun grant(operation: String) = KWebServiceGrant(KWebShortcuts.DESCRIPTOR.id, operation)

    private fun minted(gestures: KWebUserGestureRegistry) = gestures.mint(
        KWebGestureBinding("engine-1", "profile-1", "page-1", "https://app.example"),
    )

    @Test
    fun aGrantedRendererReadsOnlyTheSummaryItAskedFor() = runBlocking {
        val shortcuts = RecordingShortcuts()
        val dispatcher = shortcuts.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val response = dispatcher.dispatch(
            """{"version":1,"method":"read","payload":{"facts":["supported","registration-count"]}}""",
        )
        assertTrue(response.contains("\"supported\":true"), response)
        assertTrue(response.contains("\"registrationCount\":1"), response)
        // No accelerator, registration identifier, or command identifier is exposed.
        assertTrue(!response.contains("copy"), response)
        assertTrue(!response.contains("CONTROL"), response)

        val partial = dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        assertTrue(partial.contains("\"supported\":true"), partial)
        assertTrue(partial.contains("\"registrationCount\":0"), partial)
    }

    @Test
    fun unknownAndNonVisibleFactsAreRejectedWithStableCodes() = runBlocking {
        val shortcuts = RecordingShortcuts()
        val dispatcher = shortcuts.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val notVisible = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["accelerator"]}}""")
        }
        assertEquals(KWebShortcutErrorCode.FACT_NOT_RENDERER_VISIBLE, notVisible.code)
        val unknown = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["EVERY_COMMAND"]}}""")
        }
        assertEquals(KWebShortcutErrorCode.FACT_UNKNOWN, unknown.code)
    }

    @Test
    fun aMissingGrantOrAChildFrameIsDeniedBeforeAnythingHappens() = runBlocking {
        val shortcuts = RecordingShortcuts()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            shortcuts.bridgeDispatcher(engine(emptySet()), subject())
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            shortcuts.bridgeDispatcher(engine(setOf(grant("read"))), subject(mainFrame = false))
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["supported"]}}""")
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
    }

    @Test
    fun dispatchRequiresAGestureAndOnlyDispatchesADeclaredCommand() = runBlocking {
        val shortcuts = RecordingShortcuts()
        val gestures = KWebUserGestureRegistry()
        val engine = engine(setOf(grant("dispatch")), gestures)
        val dispatcher = shortcuts.bridgeDispatcher(engine, subject())

        // Without a minted gesture the dispatch is denied and nothing happens.
        val noGesture = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"dispatch","payload":{"commandId":"text.copy"}}""")
        }
        assertEquals("service.user-gesture-required", noGesture.code)
        assertTrue(shortcuts.dispatched.isEmpty(), "a denied dispatch reached the service")

        // A minted gesture authorizes exactly one dispatch of a declared command.
        minted(gestures)
        val accepted = dispatcher.dispatch("""{"version":1,"method":"dispatch","payload":{"commandId":"text.copy"}}""")
        assertTrue(accepted.contains("\"accepted\":true"), accepted)
        assertEquals(listOf(KWebShortcutCommandId("text.copy")), shortcuts.dispatched)

        // An undeclared command surfaces its typed code after the grant and gesture.
        minted(gestures)
        val undeclared = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"dispatch","payload":{"commandId":"text.unknown"}}""")
        }
        assertEquals(KWebShortcutErrorCode.COMMAND_UNDECLARED, undeclared.code)

        // Without the grant the dispatch never reaches the service.
        val denied = assertFailsWith<KWebBridgeException> {
            shortcuts.bridgeDispatcher(engine(emptySet(), gestures), subject())
                .dispatch("""{"version":1,"method":"dispatch","payload":{"commandId":"text.copy"}}""")
        }
        assertEquals("service.permission-denied", denied.code)
    }
}
