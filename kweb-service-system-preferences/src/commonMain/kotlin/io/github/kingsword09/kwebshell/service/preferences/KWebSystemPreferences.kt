/**
 * The RFC 0019 typed system-preferences contract shared by Windows, macOS, and
 * Linux. Every appearance fact is optional: a fact the platform does not publish
 * is absent from the snapshot and from the capability report instead of being
 * defaulted. Kotlin owns snapshot atomicity, ordering, de-duplication, the
 * appearance request, and capability reporting.
 */
package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.core.KWebAppearanceSource
import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One published color-scheme preference. */
public enum class KWebColorScheme { LIGHT, DARK }

/**
 * The platform's contrast preference. Windows high contrast, macOS increase
 * contrast, and the portal contrast key all map onto this one bounded value.
 */
public enum class KWebContrastPreference { NONE, MORE, FORCED_COLORS }

/** The declared origin of a published accent color. */
public enum class KWebAccentColorSource { SYSTEM_COLORIZATION, CONTROL_ACCENT, DESKTOP_PORTAL }

/** One bounded sRGB accent color. */
public class KWebAccentColor(
    public val red: Int,
    public val green: Int,
    public val blue: Int,
    public val source: KWebAccentColorSource,
) {
    init {
        if (red !in 0..255 || green !in 0..255 || blue !in 0..255) {
            invalid(
                KWebSystemPreferenceErrorCode.ACCENT_INVALID,
                "An accent color component must be between 0 and 255.",
            )
        }
    }

    /** The uppercase `#RRGGBB` form the renderer snapshot publishes. */
    public val hex: String = "#" + channel(red) + channel(green) + channel(blue)

    override fun equals(other: Any?): Boolean = other is KWebAccentColor &&
        red == other.red && green == other.green && blue == other.blue && source == other.source

    override fun hashCode(): Int = listOf(red, green, blue, source).hashCode()

    override fun toString(): String = "KWebAccentColor($hex, $source)"
}

/** One bounded text-scale percentage. */
public class KWebTextScale(public val percent: Int) {
    init {
        if (percent !in KWEB_PREFERENCES_MIN_TEXT_SCALE..KWEB_PREFERENCES_MAX_TEXT_SCALE) {
            invalid(
                KWebSystemPreferenceErrorCode.TEXT_SCALE_INVALID,
                "A text scale must be between $KWEB_PREFERENCES_MIN_TEXT_SCALE and " +
                    "$KWEB_PREFERENCES_MAX_TEXT_SCALE percent.",
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebTextScale && percent == other.percent

    override fun hashCode(): Int = percent.hashCode()

    override fun toString(): String = "KWebTextScale($percent%)"
}

/** One observable appearance or accessibility fact. */
public enum class KWebPreferenceFact {
    APPEARANCE_SOURCE,
    COLOR_SCHEME,
    CONTRAST,
    REDUCED_MOTION,
    REDUCED_TRANSPARENCY,
    DIFFERENTIATE_WITHOUT_COLOR,
    INVERT_COLORS,
    ACCENT_COLOR,
    TEXT_SCALE,
    SCREEN_READER,
}

/**
 * The platform keys that unlock assistive-technology facts. A service instance
 * accepts only the keys of its own target, and a fact without its declared key
 * is absent from the snapshot, the capability report, and the renderer snapshot.
 */
public object KWebSystemPreferenceSensitiveKeys {
    public const val MACOS_VOICEOVER: String = "macos.voiceover"
    public const val WINDOWS_SCREEN_READER: String = "windows.screen-reader"
    public const val LINUX_A11Y: String = "linux.a11y"

    /** Every key the contract knows. */
    public val all: Set<String> = setOf(MACOS_VOICEOVER, WINDOWS_SCREEN_READER, LINUX_A11Y)

    /** The keys one operating system accepts. */
    public fun forOperatingSystem(operatingSystem: KWebOperatingSystem): Set<String> = when (operatingSystem) {
        KWebOperatingSystem.MACOS -> setOf(MACOS_VOICEOVER)
        KWebOperatingSystem.WINDOWS -> setOf(WINDOWS_SCREEN_READER)
        KWebOperatingSystem.LINUX -> setOf(LINUX_A11Y)
    }
}

/**
 * One atomic set of published facts. Absent facts stay null; the snapshot never
 * mixes two provider reads and never guesses a value.
 */
public data class KWebSystemPreferenceSnapshot(
    public val colorScheme: KWebColorScheme? = null,
    public val contrast: KWebContrastPreference? = null,
    public val reducedMotion: Boolean? = null,
    public val reducedTransparency: Boolean? = null,
    public val differentiateWithoutColor: Boolean? = null,
    public val invertColors: Boolean? = null,
    public val accentColor: KWebAccentColor? = null,
    public val textScale: KWebTextScale? = null,
    public val screenReader: Boolean? = null,
    public val appearanceSource: KWebAppearanceSource = KWebAppearanceSource.SYSTEM,
    public val sequence: ULong = 1uL,
) {
    /** The facts this snapshot publishes. */
    public val facts: Set<KWebPreferenceFact> = buildSet {
        add(KWebPreferenceFact.APPEARANCE_SOURCE)
        if (colorScheme != null) add(KWebPreferenceFact.COLOR_SCHEME)
        if (contrast != null) add(KWebPreferenceFact.CONTRAST)
        if (reducedMotion != null) add(KWebPreferenceFact.REDUCED_MOTION)
        if (reducedTransparency != null) add(KWebPreferenceFact.REDUCED_TRANSPARENCY)
        if (differentiateWithoutColor != null) add(KWebPreferenceFact.DIFFERENTIATE_WITHOUT_COLOR)
        if (invertColors != null) add(KWebPreferenceFact.INVERT_COLORS)
        if (accentColor != null) add(KWebPreferenceFact.ACCENT_COLOR)
        if (textScale != null) add(KWebPreferenceFact.TEXT_SCALE)
        if (screenReader != null) add(KWebPreferenceFact.SCREEN_READER)
    }

    init {
        if (sequence == 0uL) {
            invalid(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID, "A snapshot sequence starts at 1.")
        }
    }

    /** The facts whose published value differs from [other]. */
    public fun changedFacts(other: KWebSystemPreferenceSnapshot): Set<KWebPreferenceFact> = buildSet {
        if (appearanceSource != other.appearanceSource) add(KWebPreferenceFact.APPEARANCE_SOURCE)
        if (colorScheme != other.colorScheme) add(KWebPreferenceFact.COLOR_SCHEME)
        if (contrast != other.contrast) add(KWebPreferenceFact.CONTRAST)
        if (reducedMotion != other.reducedMotion) add(KWebPreferenceFact.REDUCED_MOTION)
        if (reducedTransparency != other.reducedTransparency) add(KWebPreferenceFact.REDUCED_TRANSPARENCY)
        if (differentiateWithoutColor != other.differentiateWithoutColor) {
            add(KWebPreferenceFact.DIFFERENTIATE_WITHOUT_COLOR)
        }
        if (invertColors != other.invertColors) add(KWebPreferenceFact.INVERT_COLORS)
        if (accentColor != other.accentColor) add(KWebPreferenceFact.ACCENT_COLOR)
        if (textScale != other.textScale) add(KWebPreferenceFact.TEXT_SCALE)
        if (screenReader != other.screenReader) add(KWebPreferenceFact.SCREEN_READER)
    }

    /** The same facts stamped with a new sequence. */
    public fun atSequence(sequence: ULong): KWebSystemPreferenceSnapshot = copy(sequence = sequence)
}

/** The facts one provider publishes and keeps current for the current target. */
public data class KWebSystemPreferenceCapabilities(
    public val providerId: String,
    public val publishedFacts: Set<KWebPreferenceFact>,
    public val liveFacts: Set<KWebPreferenceFact>,
    public val sensitiveKeys: Set<String> = emptySet(),
) {
    init {
        if (providerId.isBlank()) {
            invalid(KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID, "A provider identity is required.")
        }
        if (!publishedFacts.containsAll(liveFacts)) {
            invalid(
                KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID,
                "A live fact must also be a published fact.",
            )
        }
        if (!KWebSystemPreferenceSensitiveKeys.all.containsAll(sensitiveKeys)) {
            invalid(
                KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN,
                "A capability report cannot claim an unknown sensitive key.",
            )
        }
        if (KWebPreferenceFact.SCREEN_READER in publishedFacts && sensitiveKeys.isEmpty()) {
            invalid(
                KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN,
                "A screen-reader fact requires its declared platform key.",
            )
        }
    }

    public fun publishes(fact: KWebPreferenceFact): Boolean = fact in publishedFacts

    /** True when the fact is delivered as an ordered change instead of a restart. */
    public fun updatesLive(fact: KWebPreferenceFact): Boolean = fact in liveFacts
}

/** One ordered service event. Sequences start at 1 and never repeat. */
public sealed interface KWebSystemPreferenceEvent {
    public val sequence: ULong

    /** One native change with the facts that actually moved. */
    public data class Changed(
        public val snapshot: KWebSystemPreferenceSnapshot,
        public val changedFacts: Set<KWebPreferenceFact>,
        override val sequence: ULong,
    ) : KWebSystemPreferenceEvent {
        init {
            if (changedFacts.isEmpty()) {
                invalid(
                    KWebSystemPreferenceErrorCode.SNAPSHOT_INVALID,
                    "A change event must report at least one changed fact.",
                )
            }
        }
    }

    public data class Failed(
        public val code: String,
        override val sequence: ULong,
    ) : KWebSystemPreferenceEvent
}

/** The recorded and effective application appearance. */
public data class KWebAppearanceResult(
    public val requested: KWebAppearanceSource,
    public val effective: KWebAppearanceSource,
    public val sequence: ULong,
)

public interface KWebSystemPreferences : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable service events. */
    public val events: Flow<KWebSystemPreferenceEvent>

    public suspend fun capabilities(): KWebSystemPreferenceCapabilities

    /** One consistent set of published facts. */
    public suspend fun snapshot(): KWebSystemPreferenceSnapshot

    /**
     * Records the application appearance and reports the effective source.
     * `SYSTEM` restores the platform preference; an unsupported source fails
     * typed instead of being ignored.
     */
    public suspend fun requestAppearance(source: KWebAppearanceSource): KWebAppearanceResult

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "system-preferences",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("snapshot"),
                hostOperation("request-appearance"),
                hostOperation("changes"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebSystemPreferences> = object : KWebServiceKey<KWebSystemPreferences> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun hostOperation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = null,
            requiresUserGesture = false,
        )
    }
}

public object KWebSystemPreferenceErrorCode {
    public const val PLATFORM_UNAVAILABLE: String = "preferences.platform-unavailable"
    public const val FACILITY_UNSUPPORTED: String = "preferences.facility-unsupported"
    public const val SENSITIVE_KEY_UNKNOWN: String = "preferences.sensitive-key-unknown"
    public const val SENSITIVE_KEY_MISSING: String = "preferences.sensitive-key-missing"
    public const val SNAPSHOT_INVALID: String = "preferences.snapshot-invalid"
    public const val APPEARANCE_INVALID: String = "preferences.appearance-invalid"
    public const val APPEARANCE_UNSUPPORTED: String = "preferences.appearance-unsupported"
    public const val ACCENT_INVALID: String = "preferences.accent-invalid"
    public const val TEXT_SCALE_INVALID: String = "preferences.text-scale-invalid"
    public const val NATIVE_FAILED: String = "preferences.native-failed"
    public const val OWNER_CLOSED: String = "preferences.owner-closed"
}

public const val KWEB_PREFERENCES_EVENT_REPLAY: Int = 64
public const val KWEB_PREFERENCES_MIN_TEXT_SCALE: Int = 50
public const val KWEB_PREFERENCES_MAX_TEXT_SCALE: Int = 500

/** The declared keys one target accepts, rejected as a typed configuration error otherwise. */
internal fun validateSensitiveKeys(keys: Collection<String>, target: KWebTarget): Set<String> {
    val accepted = KWebSystemPreferenceSensitiveKeys.forOperatingSystem(target.operatingSystem)
    val requested = keys.toSet()
    if (!accepted.containsAll(requested)) {
        invalid(
            KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN,
            "The keys ${requested - accepted} are not declared for ${target.id}.",
        )
    }
    return requested
}

internal fun channel(value: Int): String = value.toString(16).padStart(2, '0').uppercase()

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
