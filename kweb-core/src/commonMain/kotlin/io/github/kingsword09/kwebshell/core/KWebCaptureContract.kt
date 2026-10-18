/**
 * The RFC 0025 capture permission contract. KWebShell publishes the *decision* a
 * media-access request receives and the typed reason for it; it does not publish
 * a source-selection API, because the pinned runtime carries no channel that
 * could carry a host-selected display or window into a media request.
 *
 * A desktop-capture request is therefore always refused with a stable reason
 * instead of being answered by a browser default the application cannot observe,
 * and camera or microphone requests are refused by their own reason until the
 * RFC 0038 device policy exists.
 */
package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow

/** One kind of media capture a page may request. */
public enum class KWebCaptureKind(public val id: String) {
    /** A screen or window: the desktop capture surface this runtime cannot grant. */
    DESKTOP_VIDEO("desktop-video"),
    DESKTOP_AUDIO("desktop-audio"),
    CAMERA("camera"),
    MICROPHONE("microphone"),
}

/** Why a media-access request was decided the way it was. */
public enum class KWebCaptureDecisionReason(public val id: String) {
    /** The application asked for nothing, which is never a success path. */
    NONE("none"),

    /**
     * The runtime carries no native source selection, so a desktop source cannot
     * be granted. This is a declared capability boundary, not a user denial.
     */
    DESKTOP_CAPTURE_UNSUPPORTED("desktop-capture-unsupported"),

    /** The request came from a child frame, which can never receive a grant. */
    FRAME_REFUSED("frame-refused"),

    /** Camera and microphone belong to the RFC 0038 device policy. */
    DEVICE_CAPTURE_NOT_GRANTED("device-capture-not-granted"),
}

/** The decision one media-access request received. */
public sealed interface KWebCaptureDecision {
    public val origin: String
    public val frameId: String
    public val isMainFrame: Boolean
    public val requested: Set<KWebCaptureKind>

    /** Every request is refused while this runtime cannot select a source. */
    public data class Denied(
        override val origin: String,
        override val frameId: String,
        override val isMainFrame: Boolean,
        override val requested: Set<KWebCaptureKind>,
        public val reason: KWebCaptureDecisionReason,
    ) : KWebCaptureDecision {
        init {
            requireCaptureOrigin(origin)
            requireCaptureText("frameId", frameId, 256)
            if (requested.isEmpty()) {
                throw captureContractError("requested", "must name at least one capture kind")
            }
            if (reason == KWebCaptureDecisionReason.NONE) {
                throw captureContractError("reason", "a denial must carry its own reason")
            }
        }
    }
}

/** One ordered capture decision, exactly as the engine reported it. */
public data class KWebCapturePermissionEvent(
    public val sequence: Long,
    public val decision: KWebCaptureDecision,
) {
    init {
        if (sequence <= 0L) {
            throw captureContractError("sequence", "must be positive")
        }
    }
}

/** The declared capture capabilities of this runtime. */
public data class KWebCaptureCapabilities(
    public val desktopCapture: Boolean,
    public val deviceCapture: Boolean,
    public val refusalReasons: Set<KWebCaptureDecisionReason>,
) {
    init {
        if (desktopCapture) {
            throw captureContractError(
                "desktopCapture",
                "this runtime declares no desktop capture, because it carries no source selection",
            )
        }
        if (deviceCapture) {
            throw captureContractError(
                "deviceCapture",
                "camera and microphone belong to the RFC 0038 device policy",
            )
        }
        if (KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED !in refusalReasons) {
            throw captureContractError(
                "refusalReasons",
                "a runtime without desktop capture must publish that refusal",
            )
        }
    }

    public companion object {
        /** The only capability report this runtime may publish. */
        public val DECLARED: KWebCaptureCapabilities = KWebCaptureCapabilities(
            desktopCapture = false,
            deviceCapture = false,
            refusalReasons = setOf(
                KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED,
                KWebCaptureDecisionReason.FRAME_REFUSED,
                KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED,
            ),
        )
    }
}

/** The published capture surface of one engine. */
public interface KWebCapturePolicy {
    public val capabilities: KWebCaptureCapabilities

    /** Every decision the engine made, in order. */
    public val decisions: Flow<KWebCapturePermissionEvent>
}

/** Stable capture error identifiers. */
public object KWebCaptureErrorCode {
    public const val CONTRACT_INVALID: String = "capture.contract.invalid"
    public const val DESKTOP_CAPTURE_UNSUPPORTED: String = "capture.desktop-unsupported"
    public const val FRAME_REFUSED: String = "capture.frame-refused"
    public const val DEVICE_CAPTURE_NOT_GRANTED: String = "capture.device-not-granted"
}

private fun requireCaptureOrigin(value: String) {
    requireCaptureText("origin", value, 2048)
}

private fun requireCaptureText(name: String, value: String, maximumBytes: Int) {
    if (value.isEmpty() || value.contains('\u0000') || value.encodeToByteArray().size > maximumBytes) {
        throw captureContractError(name, "is empty, contains NUL, or exceeds $maximumBytes UTF-8 bytes")
    }
}

private fun captureContractError(field: String, reason: String): KWebConfigurationException =
    KWebConfigurationException(
        code = KWebCaptureErrorCode.CONTRACT_INVALID,
        details = mapOf("field" to field, "reason" to reason),
        message = "The capture contract field '$field' is invalid: $reason.",
    )
