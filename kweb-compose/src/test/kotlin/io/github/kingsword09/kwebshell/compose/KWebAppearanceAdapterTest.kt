package io.github.kingsword09.kwebshell.compose

import io.github.kingsword09.kwebshell.core.KWebAppearanceSource
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.preferences.KWebAccentColor
import io.github.kingsword09.kwebshell.service.preferences.KWebAccentColorSource
import io.github.kingsword09.kwebshell.service.preferences.KWebAppearanceResult
import io.github.kingsword09.kwebshell.service.preferences.KWebColorScheme
import io.github.kingsword09.kwebshell.service.preferences.KWebContrastPreference
import io.github.kingsword09.kwebshell.service.preferences.KWebPreferenceFact
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferenceCapabilities
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferenceEvent
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferenceSnapshot
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferences
import io.github.kingsword09.kwebshell.service.preferences.KWebTextScale
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KWebAppearanceAdapterTest {
    @Test
    fun mapsOnlyPublishedFactsAndKeepsNeutralValuesOtherwise() {
        val empty = KWebAppearance(KWebSystemPreferenceSnapshot())
        assertEquals(false, empty.dark)
        assertEquals(false, empty.reduceMotion)
        assertNull(empty.textScale)
        assertNull(empty.accentArgb)
        assertNull(empty.screenReader)
        assertNull(empty.highContrast)

        val published = KWebAppearance(
            KWebSystemPreferenceSnapshot(
                colorScheme = KWebColorScheme.DARK,
                contrast = KWebContrastPreference.MORE,
                reducedMotion = true,
                accentColor = KWebAccentColor(0x2A, 0x7D, 0xE0, KWebAccentColorSource.CONTROL_ACCENT),
                textScale = KWebTextScale(125),
                screenReader = false,
            ),
        )
        assertEquals(true, published.dark)
        assertEquals(true, published.reduceMotion)
        assertEquals(true, published.highContrast)
        assertEquals(0xFF2A7DE0L, published.accentArgb)
        assertEquals(1.25f, published.textScale)
        assertEquals(false, published.screenReader)

        // A published normal contrast is not high contrast, and it is not absent either.
        val normalContrast = KWebAppearance(
            KWebSystemPreferenceSnapshot(contrast = KWebContrastPreference.NONE),
        )
        assertEquals(false, normalContrast.highContrast)
    }

    @Test
    fun followsTheServiceSnapshotAndItsChangeStream() = runBlocking {
        val preferences = FakePreferences()
        val scope = CoroutineScope(SupervisorJob())
        val state = KWebAppearanceState(preferences, scope)
        try {
            preferences.snapshotValue = KWebSystemPreferenceSnapshot(
                colorScheme = KWebColorScheme.LIGHT,
                reducedMotion = false,
                sequence = 1uL,
            )
            state.start()
            assertEquals(false, state.appearance.first { it.snapshot.sequence == 1uL }.dark)

            preferences.publish(
                KWebSystemPreferenceSnapshot(
                    colorScheme = KWebColorScheme.DARK,
                    reducedMotion = true,
                    sequence = 2uL,
                ),
                setOf(KWebPreferenceFact.COLOR_SCHEME, KWebPreferenceFact.REDUCED_MOTION),
            )
            val changed = withTimeout(5_000) {
                state.appearance.first { it.snapshot.sequence == 2uL }
            }
            assertEquals(true, changed.dark)
            assertEquals(true, changed.reduceMotion)
        } finally {
            scope.cancel()
        }
    }

    /** A service double that publishes what the test decides. */
    private class FakePreferences : KWebSystemPreferences {
        override val descriptor: KWebServiceDescriptor = KWebSystemPreferences.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> = MutableStateFlow(KWebLifecycleState.OPEN)
            .asStateFlow()

        private val mutableEvents = MutableSharedFlow<KWebSystemPreferenceEvent>(replay = 1)
        override val events: SharedFlow<KWebSystemPreferenceEvent> = mutableEvents.asSharedFlow()

        var snapshotValue: KWebSystemPreferenceSnapshot = KWebSystemPreferenceSnapshot()

        fun publish(snapshot: KWebSystemPreferenceSnapshot, changed: Set<KWebPreferenceFact>) {
            snapshotValue = snapshot
            mutableEvents.tryEmit(
                KWebSystemPreferenceEvent.Changed(
                    snapshot = snapshot,
                    changedFacts = changed,
                    sequence = snapshot.sequence,
                ),
            )
        }

        override suspend fun capabilities(): KWebSystemPreferenceCapabilities =
            KWebSystemPreferenceCapabilities(
                providerId = "preferences.test",
                publishedFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
                liveFacts = setOf(KWebPreferenceFact.COLOR_SCHEME),
            )

        override suspend fun snapshot(): KWebSystemPreferenceSnapshot = snapshotValue

        override suspend fun requestAppearance(source: KWebAppearanceSource): KWebAppearanceResult =
            KWebAppearanceResult(source, source, snapshotValue.sequence)

        override fun close() = Unit
    }
}
