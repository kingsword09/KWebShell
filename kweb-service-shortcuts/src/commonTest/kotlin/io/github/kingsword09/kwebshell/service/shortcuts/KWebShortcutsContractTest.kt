package io.github.kingsword09.kwebshell.service.shortcuts

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KWebShortcutsContractTest {
    private fun named(vararg modifiers: KWebShortcutModifier) =
        KWebShortcutAccelerator(modifiers.toSet(), KWebShortcutKey.Named(KWebShortcutNamedKey.F13))

    @Test
    fun identifiersAreBoundedAndStable() {
        assertEquals("copy", KWebShortcutId("copy").value)
        KWebShortcutCommandId("text.copy")
        assertFailsWith<KWebConfigurationException> { KWebShortcutId("1copy") }
        assertFailsWith<KWebConfigurationException> { KWebShortcutId("copy now") }
        assertFailsWith<KWebConfigurationException> { KWebShortcutId("a".repeat(129)) }
        assertFailsWith<KWebConfigurationException> { KWebShortcutCommandId("") }
    }

    @Test
    fun namedKeysRequireAModifierAndMediaKeysDoNot() {
        named(KWebShortcutModifier.CONTROL, KWebShortcutModifier.SHIFT)
        assertFailsWith<KWebConfigurationException> { named() }
        KWebShortcutAccelerator(emptySet(), KWebShortcutKey.Media(KWebShortcutMediaKey.PLAY_PAUSE))
        KWebShortcutAccelerator(
            setOf(KWebShortcutModifier.CONTROL),
            KWebShortcutKey.Media(KWebShortcutMediaKey.NEXT_TRACK),
        )
    }

    @Test
    fun acceleratorsCompareByModifiersAndKey() {
        assertEquals(
            named(KWebShortcutModifier.CONTROL, KWebShortcutModifier.ALT),
            named(KWebShortcutModifier.ALT, KWebShortcutModifier.CONTROL),
        )
        assertEquals(
            named(KWebShortcutModifier.ALT, KWebShortcutModifier.CONTROL).hashCode(),
            named(KWebShortcutModifier.CONTROL, KWebShortcutModifier.ALT).hashCode(),
        )
        assertNotEquals<KWebShortcutAccelerator>(
            named(KWebShortcutModifier.CONTROL),
            KWebShortcutAccelerator(emptySet(), KWebShortcutKey.Media(KWebShortcutMediaKey.MUTE)),
        )
        assertEquals("CONTROL+ALT+F13", named(KWebShortcutModifier.ALT, KWebShortcutModifier.CONTROL).toString())
        assertEquals(
            "MEDIA_MUTE",
            KWebShortcutAccelerator(emptySet(), KWebShortcutKey.Media(KWebShortcutMediaKey.MUTE)).toString(),
        )
    }

    @Test
    fun capabilityReportAnswersDeclaredQueries() {
        val capabilities = KWebShortcutCapabilities(
            providerId = "shortcuts.macos.carbon-hotkey",
            flags = setOf(
                KWebShortcutCapability.GLOBAL_REGISTRATION,
                KWebShortcutCapability.ATOMIC_REPLACEMENT,
                KWebShortcutCapability.INVOCATION_REPORTING,
            ),
            modifiers = setOf(KWebShortcutModifier.CONTROL, KWebShortcutModifier.META),
            mediaKeys = emptySet(),
            maxRegistrations = KWEB_SHORTCUTS_MAX_REGISTRATIONS,
        )
        assertTrue(capabilities.supports(KWebShortcutCapability.GLOBAL_REGISTRATION))
        assertTrue(capabilities.supports(KWebShortcutModifier.META))
        assertTrue(!capabilities.supports(KWebShortcutModifier.SHIFT))
        assertTrue(!capabilities.supports(KWebShortcutMediaKey.PLAY_PAUSE))
    }

    @Test
    fun eventsCarryAStrictlyOrderedIdentity() {
        val registrationId = KWebShortcutId("copy")
        val commandId = KWebShortcutCommandId("text.copy")
        val invoked = KWebShortcutEvent.Invoked(registrationId, commandId, KWebShortcutInvocationSource.PLATFORM, 1u)
        assertEquals(1u, invoked.sequence)
        assertEquals(KWebShortcutInvocationSource.PLATFORM, invoked.source)
        KWebShortcutEvent.Lost(registrationId, commandId, KWebShortcutLossReason.SESSION_LOCK, 2u)
        KWebShortcutEvent.Removed(registrationId, KWebShortcutRemovalReason.OWNER_CLOSED, 3u)
        KWebShortcutEvent.Failed(null, KWebShortcutErrorCode.NATIVE_FAILED, 4u)
    }

    @Test
    fun resultsAreImmutableRecords() {
        val registration = KWebShortcutRegistration(
            KWebShortcutId("copy"),
            named(KWebShortcutModifier.CONTROL),
            KWebShortcutCommandId("text.copy"),
        )
        assertEquals(KWebShortcutId("copy"), registration.id)
        assertEquals(KWebShortcutCommandId("text.copy"), registration.commandId)
        KWebShortcutRegistrationResult(registration.id, 5u)
        KWebShortcutReplacementResult(listOf(registration.id), emptyList(), 6u)
        assertEquals(false, KWebShortcutUnregistration(registration.id, false).wasRegistered)
    }

    @Test
    fun descriptorPublishesHostAndPolicyGatedRendererOperations() {
        val descriptor = KWebShortcuts.DESCRIPTOR
        assertEquals("shortcuts", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(3, descriptor.supportedTargets.size)
        val host = descriptor.operations.filter { it.rendererPermission == null }.map { it.id }.toSet()
        assertEquals(
            setOf(
                "capabilities",
                "register",
                "replace-all",
                "unregister",
                "is-registered",
                "registrations",
                "events",
            ),
            host,
        )
        val read = descriptor.operations.single { it.id == "read" }
        assertEquals(KWebShortcuts.RENDERER_READ_PERMISSION, read.rendererPermission)
        assertEquals(false, read.requiresUserGesture)
        val dispatch = descriptor.operations.single { it.id == "dispatch" }
        assertEquals(KWebShortcuts.RENDERER_DISPATCH_PERMISSION, dispatch.rendererPermission)
        assertEquals(true, dispatch.requiresUserGesture)
        assertEquals(KWebLifecycleState.OPEN, KWebLifecycleState.OPEN)
    }
}
