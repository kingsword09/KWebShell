package io.github.kingsword09.kwebshell.core

/**
 * The application appearance one host declares. The system-preferences service
 * publishes the platform facts, and the engine applies this declared value so a
 * page media query and the application theme cannot disagree.
 */
public enum class KWebAppearanceSource {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * One preference Chromium reads while its browser process starts. A runtime
 * change is restart-required, so it is declared up front instead of ignored.
 */
public enum class KWebEnginePreference {
    REDUCED_MOTION,
    HIGH_CONTRAST,
}

/**
 * What the engine applies to page content. The preferences are read while the
 * browser process starts, so a change is restart-required and declared instead
 * of being dropped silently. The page color scheme is not an engine override:
 * the real-CEF probe of the hosted matrix showed that page content follows the
 * platform appearance, which is the fact the service also publishes, so both
 * sides agree through the platform.
 */
public data class KWebAppearanceSupport(
    public val restartRequiredPreferences: Set<KWebEnginePreference>,
    public val contentColorSchemeFollowsPlatform: Boolean,
)
