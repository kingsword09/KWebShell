package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.service.preferences.internal.PreferencesFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.Locale

/**
 * The JVM system-preferences session. It requires the packaged provider library
 * explicitly, validates the declared sensitive keys against the current target,
 * and owns ordering, capability caching, and the sticky close failure.
 */
public class JvmKWebSystemPreferencesSession internal constructor(
    private val native: PreferencesFfm,
    private val declaredKeys: Set<String>,
    private val scope: CoroutineScope,
) : KWebSystemPreferences {

    override val descriptor: KWebServiceDescriptor = KWebSystemPreferences.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebSystemPreferenceEvent>(
        replay = KWEB_PREFERENCES_EVENT_REPLAY,
        extraBufferCapacity = KWEB_PREFERENCES_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebSystemPreferenceEvent> = mutableEvents.asSharedFlow()

    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }
    private var capabilitiesCache: KWebSystemPreferenceCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebSystemPreferenceCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebSystemPreferenceCapabilities(
            providerId = result.providerId,
            publishedFacts = factsOf(result.publishedFacts),
            liveFacts = factsOf(result.liveFacts),
            sensitiveKeys = declaredKeys,
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun snapshot(): KWebSystemPreferenceSnapshot {
        requireOpen()
        return snapshotOf(translate("snapshot") { native.snapshot() })
    }

    override suspend fun requestAppearance(source: KWebAppearanceSource): KWebAppearanceResult {
        requireOpen()
        val result = translate("request-appearance") { native.requestAppearance(appearanceCode(source)) }
        return KWebAppearanceResult(
            requested = appearanceOf(result.requested),
            effective = appearanceOf(result.effective),
            sequence = result.sequence.toULong(),
        )
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        poller.cancel()
        var failure: KWebException? = null
        try {
            translate("close") { native.close() }
        } catch (error: KWebException) {
            failure = error
        } catch (error: Throwable) {
            failure = KWebServiceException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The system-preferences provider failed while closing.",
                cause = error,
            )
        }
        scope.cancel()
        synchronized(this) {
            closeFailure = failure
            mutableLifecycle.value = if (failure == null) KWebLifecycleState.CLOSED else KWebLifecycleState.FAILED
        }
        closeFailure?.let { throw it }
    }

    private suspend fun pollEvents() {
        while (scope.isActive) {
            var empty = false
            while (!empty) {
                val event = try {
                    native.pollEvent()
                } catch (error: Throwable) {
                    return
                }
                if (event == null) {
                    empty = true
                } else {
                    mutableEvents.emit(decodeEvent(event))
                }
            }
            delay(5)
        }
    }

    private fun decodeEvent(event: PreferencesFfm.Event): KWebSystemPreferenceEvent {
        val sequence = event.sequence.toULong()
        return when (event.kind) {
            PreferencesFfm.EVENT_CHANGED -> {
                val changed = factsOf(event.changedFacts)
                if (changed.isEmpty()) {
                    KWebSystemPreferenceEvent.Failed(KWebSystemPreferenceErrorCode.NATIVE_FAILED, sequence)
                } else {
                    // The event carries what moved; the snapshot carries the values.
                    val current = snapshotOf(translate("snapshot") { native.snapshot() })
                    KWebSystemPreferenceEvent.Changed(
                        snapshot = current.copy(sequence = sequence),
                        changedFacts = changed,
                        sequence = sequence,
                    )
                }
            }
            PreferencesFfm.EVENT_FAILED -> KWebSystemPreferenceEvent.Failed(
                code = event.code.ifEmpty { KWebSystemPreferenceErrorCode.NATIVE_FAILED },
                sequence = sequence,
            )
            else -> KWebSystemPreferenceEvent.Failed(KWebSystemPreferenceErrorCode.NATIVE_FAILED, sequence)
        }
    }

    private fun snapshotOf(result: PreferencesFfm.Snapshot): KWebSystemPreferenceSnapshot {
        val facts = factsOf(result.factBits)
        return KWebSystemPreferenceSnapshot(
            colorScheme = published(facts, KWebPreferenceFact.COLOR_SCHEME) {
                when (result.colorScheme) {
                    PreferencesFfm.SCHEME_LIGHT -> KWebColorScheme.LIGHT
                    PreferencesFfm.SCHEME_DARK -> KWebColorScheme.DARK
                    else -> null
                }
            },
            contrast = published(facts, KWebPreferenceFact.CONTRAST) {
                when (result.contrast) {
                    PreferencesFfm.CONTRAST_NONE -> KWebContrastPreference.NONE
                    PreferencesFfm.CONTRAST_MORE -> KWebContrastPreference.MORE
                    PreferencesFfm.CONTRAST_FORCED_COLORS -> KWebContrastPreference.FORCED_COLORS
                    else -> null
                }
            },
            reducedMotion = triState(result.reducedMotion, facts, KWebPreferenceFact.REDUCED_MOTION),
            reducedTransparency = triState(
                result.reducedTransparency,
                facts,
                KWebPreferenceFact.REDUCED_TRANSPARENCY,
            ),
            differentiateWithoutColor = triState(
                result.differentiateWithoutColor,
                facts,
                KWebPreferenceFact.DIFFERENTIATE_WITHOUT_COLOR,
            ),
            invertColors = triState(result.invertColors, facts, KWebPreferenceFact.INVERT_COLORS),
            accentColor = published(facts, KWebPreferenceFact.ACCENT_COLOR) {
                KWebAccentColor(
                    red = result.accentRed,
                    green = result.accentGreen,
                    blue = result.accentBlue,
                    source = accentSourceOf(result.accentSource),
                )
            },
            textScale = published(facts, KWebPreferenceFact.TEXT_SCALE) {
                KWebTextScale(result.textScalePercent)
            },
            screenReader = triState(result.screenReader, facts, KWebPreferenceFact.SCREEN_READER),
            appearanceSource = appearanceOf(result.appearanceSource),
            sequence = result.sequence.toULong(),
        )
    }

    private fun <T> published(
        facts: Set<KWebPreferenceFact>,
        fact: KWebPreferenceFact,
        value: () -> T?,
    ): T? = if (fact in facts) value() else null

    private fun triState(
        value: Int,
        facts: Set<KWebPreferenceFact>,
        fact: KWebPreferenceFact,
    ): Boolean? = published(facts, fact) {
        when (value) {
            PreferencesFfm.TRI_TRUE -> true
            PreferencesFfm.TRI_FALSE -> false
            else -> null
        }
    }

    private fun factsOf(bits: Int): Set<KWebPreferenceFact> = KWebPreferenceFact.entries
        .filter { (bits and (1 shl it.ordinal)) != 0 }
        .toSet()

    private fun accentSourceOf(code: Int): KWebAccentColorSource = when (code) {
        PreferencesFfm.ACCENT_SYSTEM_COLORIZATION -> KWebAccentColorSource.SYSTEM_COLORIZATION
        PreferencesFfm.ACCENT_CONTROL_ACCENT -> KWebAccentColorSource.CONTROL_ACCENT
        PreferencesFfm.ACCENT_DESKTOP_PORTAL -> KWebAccentColorSource.DESKTOP_PORTAL
        else -> throw failure(
            KWebSystemPreferenceErrorCode.NATIVE_FAILED,
            "snapshot",
            mapOf("accent-source" to code.toString()),
        )
    }

    private fun appearanceOf(code: Int): KWebAppearanceSource = when (code) {
        PreferencesFfm.APPEARANCE_SYSTEM -> KWebAppearanceSource.SYSTEM
        PreferencesFfm.APPEARANCE_LIGHT -> KWebAppearanceSource.LIGHT
        PreferencesFfm.APPEARANCE_DARK -> KWebAppearanceSource.DARK
        else -> throw failure(
            KWebSystemPreferenceErrorCode.APPEARANCE_INVALID,
            "appearance",
            mapOf("appearance-source" to code.toString()),
        )
    }

    private fun appearanceCode(source: KWebAppearanceSource): Int = when (source) {
        KWebAppearanceSource.SYSTEM -> PreferencesFfm.APPEARANCE_SYSTEM
        KWebAppearanceSource.LIGHT -> PreferencesFfm.APPEARANCE_LIGHT
        KWebAppearanceSource.DARK -> PreferencesFfm.APPEARANCE_DARK
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(
                KWebSystemPreferenceErrorCode.OWNER_CLOSED,
                "require-open",
                mapOf("state" to state.name),
            )
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The system-preferences operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: PreferencesFfm.PreferencesNativeException) {
        throw failure(
            when (error.status) {
                PreferencesFfm.STATUS_PLATFORM_UNAVAILABLE -> KWebSystemPreferenceErrorCode.PLATFORM_UNAVAILABLE
                PreferencesFfm.STATUS_FACILITY_UNSUPPORTED -> KWebSystemPreferenceErrorCode.FACILITY_UNSUPPORTED
                PreferencesFfm.STATUS_SENSITIVE_KEY_UNKNOWN -> KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN
                PreferencesFfm.STATUS_APPEARANCE_INVALID -> KWebSystemPreferenceErrorCode.APPEARANCE_INVALID
                PreferencesFfm.STATUS_APPEARANCE_UNSUPPORTED ->
                    KWebSystemPreferenceErrorCode.APPEARANCE_UNSUPPORTED
                PreferencesFfm.STATUS_OWNER_CLOSED -> KWebSystemPreferenceErrorCode.OWNER_CLOSED
                else -> KWebSystemPreferenceErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebSystemPreferences {
    /**
     * Opens the packaged system-preferences provider. There is no fallback
     * provider: a missing native library, an undeclared key, or an absent
     * platform host fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        packageIdentity: String = applicationId,
        sensitiveKeys: Set<String> = emptySet(),
    ): JvmKWebSystemPreferencesSession {
        val declared = validateSensitiveKeys(sensitiveKeys, currentTarget())
        return JvmKWebSystemPreferencesSession(
            PreferencesFfm.open(nativeLibrary, applicationId, packageIdentity, sensitiveKeyBits(declared)),
            declared,
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }

    private fun sensitiveKeyBits(keys: Set<String>): Int = keys.fold(0) { bits, key ->
        bits or when (key) {
            KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER -> PreferencesFfm.KEY_MACOS_VOICEOVER
            KWebSystemPreferenceSensitiveKeys.WINDOWS_SCREEN_READER -> PreferencesFfm.KEY_WINDOWS_SCREEN_READER
            KWebSystemPreferenceSensitiveKeys.LINUX_A11Y -> PreferencesFfm.KEY_LINUX_A11Y
            else -> invalid(
                KWebSystemPreferenceErrorCode.SENSITIVE_KEY_UNKNOWN,
                "The sensitive key '$key' is not declared by this contract.",
            )
        }
    }

    private fun currentTarget(): KWebTarget {
        val name = System.getProperty("os.name").lowercase(Locale.ROOT)
        val operatingSystem = when {
            name.startsWith("windows") -> KWebOperatingSystem.WINDOWS
            name.startsWith("mac") -> KWebOperatingSystem.MACOS
            name.startsWith("linux") -> KWebOperatingSystem.LINUX
            else -> invalid(
                KWebSystemPreferenceErrorCode.FACILITY_UNSUPPORTED,
                "The system-preferences service is not published for '$name'.",
            )
        }
        return KWebSystemPreferences.DESCRIPTOR.supportedTargets.single { it.operatingSystem == operatingSystem }
    }
}
