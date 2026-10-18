/**
 * The RFC 0025 capture decision surface. The engine reports every media-access
 * request it decided, and this policy turns that report into the published typed
 * contract.
 *
 * It publishes decisions, not a source-selection API: the pinned runtime carries
 * no channel that could carry a host-selected display or window into a media
 * request, so desktop capture is refused with its own stable reason instead of
 * being answered by a browser default the application cannot observe.
 */
package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebCaptureCapabilities
import io.github.kingsword09.kwebshell.core.KWebCaptureDecision
import io.github.kingsword09.kwebshell.core.KWebCaptureDecisionReason
import io.github.kingsword09.kwebshell.core.KWebCaptureErrorCode
import io.github.kingsword09.kwebshell.core.KWebCaptureKind
import io.github.kingsword09.kwebshell.core.KWebCapturePermissionEvent
import io.github.kingsword09.kwebshell.core.KWebCapturePolicy
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The published capture surface of one engine. */
public class KWebDesktopCapturePolicy internal constructor() : KWebCapturePolicy {

    override val capabilities: KWebCaptureCapabilities = KWebCaptureCapabilities.DECLARED

    private val mutableDecisions = MutableSharedFlow<KWebCapturePermissionEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    override val decisions: Flow<KWebCapturePermissionEvent> = mutableDecisions.asSharedFlow()

    /** Publishes one decision the engine already made; never invents a grant. */
    internal fun publish(event: NativeBrowserEvent) {
        if (mutableDecisions.subscriptionCount.value == 0) return
        mutableDecisions.tryEmit(
            KWebCapturePermissionEvent(
                sequence = event.sequence,
                decision = KWebDesktopCaptureJson.parse(event),
            ),
        )
    }
}

internal object KWebDesktopCaptureJson {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    private const val DESKTOP_VIDEO = 8
    private const val DESKTOP_AUDIO = 4
    private const val DEVICE_VIDEO = 2
    private const val DEVICE_AUDIO = 1

    /** Parses one engine report into the published decision contract. */
    fun parse(event: NativeBrowserEvent): KWebCaptureDecision {
        val root = parseObject(event.details)
        if (requiredInt(root, "version") != 1) invalid("version")
        val requestedBits = requiredInt(root, "requested")
        val grantedBits = requiredInt(root, "granted")
        // This runtime publishes no grant at all. A report that claims one is a
        // contract violation rather than a decision to forward.
        if (grantedBits != 0) invalid("granted")
        val reason = when (requiredInt(root, "reason")) {
            0 -> KWebCaptureDecisionReason.NONE
            1 -> KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED
            2 -> KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED
            3 -> KWebCaptureDecisionReason.FRAME_REFUSED
            4 -> KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED
            else -> invalid("reason")
        }
        val requested = buildSet {
            if ((requestedBits and DESKTOP_VIDEO) != 0) add(KWebCaptureKind.DESKTOP_VIDEO)
            if ((requestedBits and DESKTOP_AUDIO) != 0) add(KWebCaptureKind.DESKTOP_AUDIO)
            if ((requestedBits and DEVICE_VIDEO) != 0) add(KWebCaptureKind.CAMERA)
            if ((requestedBits and DEVICE_AUDIO) != 0) add(KWebCaptureKind.MICROPHONE)
        }
        if ((requestedBits and 15.inv()) != 0) invalid("requested")
        val isMainFrame = requiredBoolean(root, "mainFrame")
        return KWebCaptureDecision.Denied(
            origin = event.origin,
            frameId = event.frameId,
            isMainFrame = isMainFrame,
            requested = requested,
            reason = reason,
        )
    }

    private fun parseObject(payload: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (error: IllegalArgumentException) {
        throw invalid("payload", error)
    }

    private fun requiredInt(root: JsonObject, name: String): Int =
        root[name]?.jsonPrimitive?.intOrNull ?: throw invalid(name)

    private fun requiredBoolean(root: JsonObject, name: String): Boolean =
        root[name]?.jsonPrimitive?.booleanOrNull ?: throw invalid(name)

    private fun invalid(name: String, cause: Throwable? = null): Nothing =
        throw KWebConfigurationException(
            code = KWebCaptureErrorCode.CONTRACT_INVALID,
            details = mapOf("field" to name),
            message = "The engine capture report field '$name' is invalid.",
            cause = cause,
        )
}
