package io.github.kingsword09.kwebshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import io.github.kingsword09.kwebshell.service.preferences.KWebColorScheme
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferenceEvent
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferenceSnapshot
import io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * The appearance a Compose host renders with. Every value comes from one
 * system-preferences snapshot, and a fact the platform does not publish keeps a
 * neutral value instead of inventing one; the host decides what to render.
 */
public class KWebAppearance(public val snapshot: KWebSystemPreferenceSnapshot) {
    /** True when the host should render its dark palette. */
    public val dark: Boolean = snapshot.colorScheme == KWebColorScheme.DARK

    /** True when the host should reduce motion; unpublished motion stays full. */
    public val reduceMotion: Boolean = snapshot.reducedMotion ?: false

    /** The published text scale as a factor, or null when the platform publishes none. */
    public val textScale: Float? = snapshot.textScale?.let { it.percent / 100f }

    /** The published accent color as 0xFFRRGGBB, or null when the platform publishes none. */
    public val accentArgb: Long? = snapshot.accentColor?.let { accent ->
        0xFF000000L or (accent.red.toLong() shl 16) or (accent.green.toLong() shl 8) or accent.blue.toLong()
    }

    /** The published screen-reader fact, or null when the host did not declare its key. */
    public val screenReader: Boolean? = snapshot.screenReader

    /** The contrast preference the host may render, or null when unpublished. */
    public val highContrast: Boolean? = snapshot.contrast?.let {
        it != io.github.kingsword09.kwebshell.service.preferences.KWebContrastPreference.NONE
    }
}

/**
 * Follows one system-preferences service: it reads the current snapshot and then
 * every ordered change, so the Compose side never keeps a second source of truth.
 */
public class KWebAppearanceState internal constructor(
    private val preferences: KWebSystemPreferences,
    private val scope: CoroutineScope,
) {
    private val mutableAppearance = MutableStateFlow(KWebAppearance(KWebSystemPreferenceSnapshot()))

    public val appearance: StateFlow<KWebAppearance> = mutableAppearance.asStateFlow()

    /** Reads the current snapshot and starts following the change stream. */
    public fun start(): kotlinx.coroutines.Job = scope.launch {
        mutableAppearance.value = KWebAppearance(preferences.snapshot())
        preferences.events.collect { event ->
            if (event is KWebSystemPreferenceEvent.Changed) {
                mutableAppearance.value = KWebAppearance(event.snapshot)
            }
        }
    }
}

/**
 * Remembers the appearance of the installed system-preferences service for one
 * composition. The service stays the only source of truth: closing it closes the
 * observation with it.
 */
@Composable
public fun rememberKWebAppearance(preferences: KWebSystemPreferences): State<KWebAppearance> {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = remember(preferences) { KWebAppearanceState(preferences, scope) }
    LaunchedEffect(preferences) { state.start() }
    return state.appearance.collectAsState()
}
