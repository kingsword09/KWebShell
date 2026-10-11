package io.github.kingsword09.kwebshell.service.screen

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.screen.internal.ScreenFfm
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

/**
 * The JVM screen session. It requires the packaged provider library explicitly,
 * validates every provider value through the shared contract, and owns ordering,
 * capability caching, and the sticky close failure.
 */
public class JvmKWebScreensSession internal constructor(
    private val native: ScreenFfm,
    private val scope: CoroutineScope,
) : KWebScreens {

    override val descriptor: KWebServiceDescriptor = KWebScreens.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebScreenEvent>(
        replay = KWEB_SCREEN_EVENT_REPLAY,
        extraBufferCapacity = KWEB_SCREEN_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebScreenEvent> = mutableEvents.asSharedFlow()

    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }
    private var capabilitiesCache: KWebScreenCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebScreenCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebScreenCapabilities(
            providerId = result.providerId,
            session = sessionOf(result.session),
            capabilities = KWebScreenCapability.entries.filter { (result.capabilityBits and (1 shl it.ordinal)) != 0 }.toSet(),
            spaces = KWebScreenSpace.entries.filter { (result.spaceBits and (1 shl it.ordinal)) != 0 }.toSet(),
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun topology(): KWebScreenTopology {
        requireOpen()
        val result = translate("topology") { native.topology() }
        return KWebScreenTopology(
            providerId = result.providerId,
            session = sessionOf(result.session),
            displays = result.displays.map { displayOf(it) },
            sequence = result.sequence.toULong(),
        )
    }

    override suspend fun cursor(): KWebScreenCursor {
        requireOpen()
        val result = translate("cursor") { native.cursor() }
        return KWebScreenCursor(
            point = KWebScreenPoint(result.x, result.y),
            space = spaceOf(result.space),
            displayId = if (result.displayKnown) KWebScreenId(result.displayId) else null,
        )
    }

    override suspend fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId? {
        requireOpen()
        val result = translate("display-at") { native.displayAt(point.x, point.y, spaceCode(space)) }
        return if (result.found) KWebScreenId(result.id) else null
    }

    override suspend fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId {
        requireOpen()
        val result = translate("nearest") { native.nearest(point.x, point.y, spaceCode(space)) }
        if (!result.found) {
            throw failure(KWebScreenErrorCode.TOPOLOGY_UNAVAILABLE, "nearest", emptyMap())
        }
        return KWebScreenId(result.id)
    }

    override suspend fun convert(request: KWebScreenConversion): KWebScreenPoint {
        requireOpen()
        val result = translate("convert") {
            native.convert(request.displayId.value, spaceCode(request.from), spaceCode(request.to), request.point.x, request.point.y)
        }
        return KWebScreenPoint(result.x, result.y)
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
                message = "The screen provider failed while closing.",
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

    private suspend fun decodeEvent(event: ScreenFfm.Event): KWebScreenEvent {
        val sequence = event.sequence.toULong()
        return when (event.kind) {
            ScreenFfm.EVENT_TOPOLOGY_CHANGED -> {
                // The event names what moved; the topology carries the values.
                val snapshot = try {
                    topology()
                } catch (error: Throwable) {
                    return KWebScreenEvent.Failed(KWebScreenErrorCode.TOPOLOGY_UNAVAILABLE, sequence)
                }
                KWebScreenEvent.TopologyChanged(
                    topology = snapshot.copy(sequence = sequence),
                    added = event.added.map { KWebScreenId(it) }.toSet(),
                    removed = event.removed.map { KWebScreenId(it) }.toSet(),
                    changed = event.changed.map { KWebScreenId(it) }.toSet(),
                    sequence = sequence,
                )
            }
            ScreenFfm.EVENT_FAILED -> KWebScreenEvent.Failed(
                code = event.code.ifEmpty { KWebScreenErrorCode.NATIVE_FAILED },
                sequence = sequence,
            )
            else -> KWebScreenEvent.Failed(KWebScreenErrorCode.NATIVE_FAILED, sequence)
        }
    }

    private fun displayOf(flat: ScreenFfm.FlatDisplay): KWebScreenDisplay {
        val facts = flat.factBits
        fun published(bit: Int): Boolean = (facts and bit) != 0
        return KWebScreenDisplay(
            id = KWebScreenId(flat.id),
            name = flat.name.ifEmpty { null },
            geometry = KWebScreenGeometry(flat.left, flat.top, flat.width, flat.height),
            nativeGeometry = KWebScreenGeometry(flat.nativeLeft, flat.nativeTop, flat.nativeWidth, flat.nativeHeight),
            workArea = if (published(ScreenFfm.FACT_WORK_AREA)) {
                KWebScreenGeometry(flat.workLeft, flat.workTop, flat.workWidth, flat.workHeight)
            } else {
                null
            },
            scale = if (published(ScreenFfm.FACT_SCALE)) KWebScreenScale(flat.scalePermille) else null,
            rotation = if (published(ScreenFfm.FACT_ROTATION)) rotationOf(flat.rotation) else null,
            refreshHertz = if (published(ScreenFfm.FACT_REFRESH_RATE)) flat.refreshHertz else null,
            colorDepthBits = if (published(ScreenFfm.FACT_COLOR_DEPTH)) flat.colorDepthBits else null,
            physicalSize = if (published(ScreenFfm.FACT_PHYSICAL_SIZE)) {
                KWebScreenPhysicalSize(flat.physicalWidthMm, flat.physicalHeightMm)
            } else {
                null
            },
            primary = published(ScreenFfm.FACT_PRIMARY),
        )
    }

    private fun sessionOf(code: Int): KWebScreenSession = when (code) {
        ScreenFfm.SESSION_WINDOWS -> KWebScreenSession.WINDOWS
        ScreenFfm.SESSION_MACOS -> KWebScreenSession.MACOS
        ScreenFfm.SESSION_X11 -> KWebScreenSession.X11
        ScreenFfm.SESSION_WAYLAND -> KWebScreenSession.WAYLAND
        else -> throw failure(KWebScreenErrorCode.SESSION_UNSUPPORTED, "capabilities", mapOf("session" to code.toString()))
    }

    private fun spaceOf(code: Int): KWebScreenSpace = when (code) {
        ScreenFfm.SPACE_LOGICAL -> KWebScreenSpace.LOGICAL
        ScreenFfm.SPACE_NATIVE -> KWebScreenSpace.NATIVE
        ScreenFfm.SPACE_PHYSICAL -> KWebScreenSpace.PHYSICAL
        else -> throw failure(KWebScreenErrorCode.SPACE_UNSUPPORTED, "cursor", mapOf("space" to code.toString()))
    }

    private fun spaceCode(space: KWebScreenSpace): Int = when (space) {
        KWebScreenSpace.LOGICAL -> ScreenFfm.SPACE_LOGICAL
        KWebScreenSpace.NATIVE -> ScreenFfm.SPACE_NATIVE
        KWebScreenSpace.PHYSICAL -> ScreenFfm.SPACE_PHYSICAL
    }

    private fun rotationOf(code: Int): KWebScreenRotation = when (code) {
        ScreenFfm.ROTATION_NONE -> KWebScreenRotation.NONE
        ScreenFfm.ROTATION_QUAD_1 -> KWebScreenRotation.QUAD_1
        ScreenFfm.ROTATION_QUAD_2 -> KWebScreenRotation.QUAD_2
        ScreenFfm.ROTATION_QUAD_3 -> KWebScreenRotation.QUAD_3
        else -> throw failure(KWebScreenErrorCode.TOPOLOGY_INVALID, "topology", mapOf("rotation" to code.toString()))
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebScreenErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The screen operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: ScreenFfm.ScreenNativeException) {
        throw failure(
            when (error.status) {
                ScreenFfm.STATUS_PLATFORM_UNAVAILABLE -> KWebScreenErrorCode.PLATFORM_UNAVAILABLE
                ScreenFfm.STATUS_TOPOLOGY_UNAVAILABLE -> KWebScreenErrorCode.TOPOLOGY_UNAVAILABLE
                ScreenFfm.STATUS_TOPOLOGY_INVALID -> KWebScreenErrorCode.TOPOLOGY_INVALID
                ScreenFfm.STATUS_DISPLAY_UNKNOWN -> KWebScreenErrorCode.DISPLAY_UNKNOWN
                ScreenFfm.STATUS_GEOMETRY_INVALID -> KWebScreenErrorCode.GEOMETRY_INVALID
                ScreenFfm.STATUS_POINT_INVALID -> KWebScreenErrorCode.POINT_INVALID
                ScreenFfm.STATUS_SPACE_UNSUPPORTED -> KWebScreenErrorCode.SPACE_UNSUPPORTED
                ScreenFfm.STATUS_CURSOR_UNAVAILABLE -> KWebScreenErrorCode.CURSOR_UNAVAILABLE
                ScreenFfm.STATUS_OWNER_CLOSED -> KWebScreenErrorCode.OWNER_CLOSED
                else -> KWebScreenErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebScreens {
    /**
     * Opens the packaged screen provider. There is no fallback provider: a missing
     * native library or an absent session facility fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        packageIdentity: String = applicationId,
    ): JvmKWebScreensSession = JvmKWebScreensSession(
        ScreenFfm.open(nativeLibrary, applicationId, packageIdentity),
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
