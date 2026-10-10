package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebSystemPreferencesContractTest {
    private val accent = KWebAccentColor(0x2A, 0x7D, 0xE0, KWebAccentColorSource.CONTROL_ACCENT)

    private fun expectedError(code: String, block: () -> Unit) {
        val failure = assertFailsWith<KWebConfigurationException> { block() }
        assertEquals(code, failure.code)
    }

    @Test
    fun snapshotPublishesExactlyTheFactsItCarries() {
        val empty = KWebSystemPreferenceSnapshot()
        assertEquals(setOf(KWebPreferenceFact.APPEARANCE_SOURCE), empty.facts)

        val partial = KWebSystemPreferenceSnapshot(
            colorScheme = KWebColorScheme.DARK,
            reducedMotion = true,
        )
        assertEquals(
            setOf(
                KWebPreferenceFact.APPEARANCE_SOURCE,
                KWebPreferenceFact.COLOR_SCHEME,
                KWebPreferenceFact.REDUCED_MOTION,
            ),
            partial.facts,
        )
        // An unpublishing platform cannot leak a defaulted fact.
        assertEquals(null, partial.contrast)
        assertEquals(null, partial.screenReader)
        assertEquals(null, partial.textScale)
        assertEquals(null, partial.accentColor)
        assertTrue(!partial.facts.contains(KWebPreferenceFact.TEXT_SCALE))
    }

    @Test
    fun snapshotsRejectAZeroSequenceAndReportOnlyAdjacentChanges() {
        expectedError(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID) {
            KWebSystemPreferenceSnapshot(sequence = 0uL)
        }

        val before = KWebSystemPreferenceSnapshot(
            colorScheme = KWebColorScheme.LIGHT,
            contrast = KWebContrastPreference.NONE,
            accentColor = accent,
            sequence = 4uL,
        )
        assertEquals(emptySet(), before.changedFacts(before.copy(sequence = 5uL)))
        assertEquals(
            setOf(KWebPreferenceFact.COLOR_SCHEME),
            before.copy(colorScheme = KWebColorScheme.DARK).changedFacts(before),
        )
        assertEquals(
            setOf(KWebPreferenceFact.APPEARANCE_SOURCE),
            before.copy(appearanceSource = KWebAppearanceSource.DARK).changedFacts(before),
        )
        assertEquals(
            setOf(KWebPreferenceFact.ACCENT_COLOR),
            before.copy(accentColor = null).changedFacts(before),
        )
        assertEquals(
            setOf(KWebPreferenceFact.CONTRAST),
            before.copy(contrast = KWebContrastPreference.MORE).changedFacts(before),
        )
    }

    @Test
    fun accentColorsAreBoundedAndFormatAsUppercaseHex() {
        assertEquals("#2A7DE0", accent.hex)
        assertEquals("#000000", KWebAccentColor(0, 0, 0, KWebAccentColorSource.DESKTOP_PORTAL).hex)
        assertEquals("#FFFFFF", KWebAccentColor(255, 255, 255, KWebAccentColorSource.SYSTEM_COLORIZATION).hex)
        expectedError(KWebSystemPreferenceErrorCode.ACCENT_INVALID) {
            KWebAccentColor(-1, 0, 0, KWebAccentColorSource.CONTROL_ACCENT)
        }
        expectedError(KWebSystemPreferenceErrorCode.ACCENT_INVALID) {
            KWebAccentColor(0, 256, 0, KWebAccentColorSource.CONTROL_ACCENT)
        }
        assertEquals(
            accent,
            KWebAccentColor(0x2A, 0x7D, 0xE0, KWebAccentColorSource.CONTROL_ACCENT),
        )
        assertTrue(accent != KWebAccentColor(0x2A, 0x7D, 0xE0, KWebAccentColorSource.DESKTOP_PORTAL))
    }

    @Test
    fun textScaleEnforcesThePublishedBounds() {
        assertEquals(50, KWebTextScale(KWEB_PREFERENCES_MIN_TEXT_SCALE).percent)
        assertEquals(500, KWebTextScale(KWEB_PREFERENCES_MAX_TEXT_SCALE).percent)
        expectedError(KWebSystemPreferenceErrorCode.TEXT_SCALE_INVALID) {
            KWebTextScale(KWEB_PREFERENCES_MIN_TEXT_SCALE - 1)
        }
        expectedError(KWebSystemPreferenceErrorCode.TEXT_SCALE_INVALID) {
            KWebTextScale(KWEB_PREFERENCES_MAX_TEXT_SCALE + 1)
        }
    }

    @Test
    fun capabilitiesRejectUndeclaredLiveFactsAndUnknownSensitiveKeys() {
        val capabilities = KWebSystemPreferenceCapabilities(
            providerId = "preferences.test",
            publishedFacts = setOf(KWebPreferenceFact.COLOR_SCHEME, KWebPreferenceFact.REDUCED_MOTION),
            liveFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
        )
        assertTrue(capabilities.publishes(KWebPreferenceFact.COLOR_SCHEME))
        assertTrue(!capabilities.publishes(KWebPreferenceFact.TEXT_SCALE))
        assertTrue(capabilities.updatesLive(KWebPreferenceFact.COLOR_SCHEME))
        assertTrue(!capabilities.updatesLive(KWebPreferenceFact.REDUCED_MOTION))

        expectedError(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID) {
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
                liveFacts = setOf(KWebPreferenceFact.TEXT_SCALE),
            )
        }
        expectedError(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID) {
            KWebSystemPreferenceCapabilities(
                providerId = " ",
                publishedFacts = emptySet(),
                liveFacts = emptySet(),
            )
        }
        expectedError(KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN) {
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = emptySet(),
                liveFacts = emptySet(),
                sensitiveKeys = setOf("windows.registry-dump"),
            )
        }
        // An assistive-technology fact without its declared key is a contract error.
        expectedError(KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN) {
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = setOf(KWebPreferenceFact.SCREEN_READER),
                liveFacts = emptySet(),
            )
        }
        assertTrue(
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = setOf(KWebPreferenceFact.SCREEN_READER),
                liveFacts = setOf(KWebPreferenceFact.SCREEN_READER),
                sensitiveKeys = setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER),
            ).updatesLive(KWebPreferenceFact.SCREEN_READER),
        )
    }

    @Test
    fun sensitiveKeysAreDeclaredPerOperatingSystem() {
        assertEquals(
            setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER),
            KWebSystemPreferenceSensitiveKeys.forOperatingSystem(KWebOperatingSystem.MACOS),
        )
        assertEquals(
            setOf(KWebSystemPreferenceSensitiveKeys.WINDOWS_SCREEN_READER),
            KWebSystemPreferenceSensitiveKeys.forOperatingSystem(KWebOperatingSystem.WINDOWS),
        )
        assertEquals(
            setOf(KWebSystemPreferenceSensitiveKeys.LINUX_A11Y),
            KWebSystemPreferenceSensitiveKeys.forOperatingSystem(KWebOperatingSystem.LINUX),
        )
        val macos = KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64)
        assertEquals(
            setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER),
            validateSensitiveKeys(setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER), macos),
        )
        expectedError(KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN) {
            validateSensitiveKeys(setOf(KWebSystemPreferenceSensitiveKeys.LINUX_A11Y), macos)
        }
        expectedError(KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN) {
            validateSensitiveKeys(setOf("macos.every-preference"), macos)
        }
    }

    @Test
    fun changeEventsRequireAChangedFactAndKeepTheSequence() {
        val event = KWebSystemPreferenceEvent.Changed(
            snapshot = KWebSystemPreferenceSnapshot(colorScheme = KWebColorScheme.DARK, sequence = 7uL),
            changedFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
            sequence = 7uL,
        )
        assertEquals(7uL, event.sequence)
        assertEquals(KWebColorScheme.DARK, event.snapshot.colorScheme)
        expectedError(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID) {
            KWebSystemPreferenceEvent.Changed(
                snapshot = KWebSystemPreferenceSnapshot(),
                changedFacts = emptySet(),
                sequence = 1uL,
            )
        }
        assertEquals(
            3uL,
            KWebSystemPreferenceEvent.Failed(
                code = KWebSystemPreferenceErrorCode.NATIVE_FAILED,
                sequence = 3uL,
            ).sequence,
        )
    }

    @Test
    fun descriptorPublishesHostOperationsWithoutRendererPermissions() {
        val descriptor = KWebSystemPreferences.DESCRIPTOR
        assertEquals("system-preferences", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(KWebServiceScope.APPLICATION, descriptor.scope)
        assertEquals(
            setOf("capabilities", "changes", "request-appearance", "snapshot"),
            descriptor.operations.map { it.id }.toSet(),
        )
        descriptor.operations.forEach { operation ->
            // The renderer surface is a separate policy-gated bridge, never a service operation.
            assertEquals(null, operation.rendererPermission)
            assertTrue(!operation.requiresUserGesture)
            assertTrue(!operation.requiresOsConsent)
        }
        assertEquals(
            setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
            descriptor.supportedTargets,
        )
        assertEquals("system-preferences", KWebSystemPreferences.Key.id)
    }

    @Test
    fun errorCodesAreStablePreferenceCodes() {
        val codes = listOf(
            KWebSystemPreferenceErrorCode.PLATFORM_UNAVAILABLE,
            KWebSystemPreferenceErrorCode.FACILITY_UNSUPPORTED,
            KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN,
            KWebSystemPreferenceErrorCode.SENSITIVE_KEY_MISSING,
            KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID,
            KWebSystemPreferenceErrorCode.APPEARANCE_INVALID,
            KWebSystemPreferenceErrorCode.APPEARANCE_UNSUPPORTED,
            KWebSystemPreferenceErrorCode.ACCENT_INVALID,
            KWebSystemPreferenceErrorCode.TEXT_SCALE_INVALID,
            KWebSystemPreferenceErrorCode.NATIVE_FAILED,
            KWebSystemPreferenceErrorCode.OWNER_CLOSED,
        )
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { code ->
            assertTrue(code.startsWith("preferences."), code)
            assertTrue(Regex("preferences\\.[a-z]+(?:-[a-z]+)*").matches(code), code)
        }
        assertEquals(64, KWEB_PREFERENCES_EVENT_REPLAY)
    }
}
