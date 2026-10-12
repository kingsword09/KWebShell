/**
 * The RFC 0020 typed screen and display-topology contract shared by Windows,
 * macOS, and Linux. Display identifiers are stable for one service lifetime, the
 * models keep logical, native, and physical coordinates apart, and a fact the
 * session does not publish is absent instead of defaulted.
 */
package io.github.kingsword09.kwebshell.service.screen

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

/** The session a provider implements. A provider advertises exactly one. */
public enum class KWebScreenSession { WINDOWS, MACOS, X11, WAYLAND }

/**
 * The coordinate spaces a display is described in. `LOGICAL` is the global
 * desktop in device-independent units, `NATIVE` is the global desktop in device
 * pixels, and `PHYSICAL` is millimetres from that display's own top-left corner.
 */
public enum class KWebScreenSpace { LOGICAL, NATIVE, PHYSICAL }

/** The quarter-turn rotations a display server publishes. */
public enum class KWebScreenRotation { NONE, QUAD_1, QUAD_2, QUAD_3 }

/** One scale in permille, so a fractional scale such as 1.5 stays exact. */
public class KWebScreenScale(public val permille: Int) {
    init {
        if (permille !in KWEB_SCREEN_MIN_SCALE_PERMILLE..KWEB_SCREEN_MAX_SCALE_PERMILLE) {
            invalid(
                KWebScreenErrorCode.SCALE_INVALID,
                "A display scale must be between $KWEB_SCREEN_MIN_SCALE_PERMILLE and " +
                    "$KWEB_SCREEN_MAX_SCALE_PERMILLE permille.",
            )
        }
    }

    /** The scale as a factor, for callers that render with floating point. */
    public val factor: Double get() = permille / 1000.0

    override fun equals(other: Any?): Boolean = other is KWebScreenScale && permille == other.permille

    override fun hashCode(): Int = permille.hashCode()

    override fun toString(): String = "KWebScreenScale($permille)"
}

/** One stable display identifier, valid only for one service lifetime. */
public class KWebScreenId(public val value: String) {
    init {
        if (!SCREEN_ID_PATTERN.matches(value)) {
            invalid(KWebScreenErrorCode.DISPLAY_ID_INVALID, "A display identifier must match the published form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebScreenId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One bounded point in a declared coordinate space. */
public data class KWebScreenPoint(public val x: Int, public val y: Int) {
    init {
        if (x !in KWEB_SCREEN_MIN_COORDINATE..KWEB_SCREEN_MAX_COORDINATE ||
            y !in KWEB_SCREEN_MIN_COORDINATE..KWEB_SCREEN_MAX_COORDINATE
        ) {
            invalid(KWebScreenErrorCode.POINT_INVALID, "A screen point is outside the supported range.")
        }
    }
}

/** One bounded rectangle in a declared coordinate space. */
public class KWebScreenGeometry(
    public val left: Int,
    public val top: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        if (left !in KWEB_SCREEN_MIN_COORDINATE..KWEB_SCREEN_MAX_COORDINATE ||
            top !in KWEB_SCREEN_MIN_COORDINATE..KWEB_SCREEN_MAX_COORDINATE ||
            width !in 1..KWEB_SCREEN_MAX_EXTENT || height !in 1..KWEB_SCREEN_MAX_EXTENT
        ) {
            invalid(KWebScreenErrorCode.GEOMETRY_INVALID, "Screen geometry is outside the supported range.")
        }
    }

    public val right: Int get() = left + width
    public val bottom: Int get() = top + height

    /** True when the point lies inside the half-open rectangle. */
    public fun contains(point: KWebScreenPoint): Boolean =
        point.x >= left && point.x < right && point.y >= top && point.y < bottom

    /** The squared distance from the point to the nearest edge, zero inside. */
    public fun distanceSquared(point: KWebScreenPoint): Long {
        val dx = when {
            point.x < left -> (left - point.x).toLong()
            point.x >= right -> (point.x - right + 1).toLong()
            else -> 0L
        }
        val dy = when {
            point.y < top -> (top - point.y).toLong()
            point.y >= bottom -> (point.y - bottom + 1).toLong()
            else -> 0L
        }
        return dx * dx + dy * dy
    }

    override fun equals(other: Any?): Boolean = other is KWebScreenGeometry &&
        left == other.left && top == other.top && width == other.width && height == other.height

    override fun hashCode(): Int = listOf(left, top, width, height).hashCode()

    override fun toString(): String = "KWebScreenGeometry(${width}x$height+$left+$top)"
}

/** One bounded physical display size in millimetres. */
public class KWebScreenPhysicalSize(public val widthMillimetres: Int, public val heightMillimetres: Int) {
    init {
        if (widthMillimetres !in 1..KWEB_SCREEN_MAX_MILLIMETRES ||
            heightMillimetres !in 1..KWEB_SCREEN_MAX_MILLIMETRES
        ) {
            invalid(KWebScreenErrorCode.PHYSICAL_SIZE_INVALID, "A physical display size is outside the supported range.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebScreenPhysicalSize &&
        widthMillimetres == other.widthMillimetres && heightMillimetres == other.heightMillimetres

    override fun hashCode(): Int = listOf(widthMillimetres, heightMillimetres).hashCode()

    override fun toString(): String = "KWebScreenPhysicalSize(${widthMillimetres}x${heightMillimetres}mm)"
}

/**
 * One display. `geometry` is the logical desktop rectangle and `nativeGeometry`
 * the device-pixel rectangle; they are equal on a session that publishes no scale.
 */
public data class KWebScreenDisplay(
    public val id: KWebScreenId,
    public val name: String? = null,
    public val geometry: KWebScreenGeometry,
    public val nativeGeometry: KWebScreenGeometry,
    public val workArea: KWebScreenGeometry? = null,
    public val scale: KWebScreenScale? = null,
    public val rotation: KWebScreenRotation? = null,
    public val refreshHertz: Int? = null,
    public val colorDepthBits: Int? = null,
    public val physicalSize: KWebScreenPhysicalSize? = null,
    public val primary: Boolean = false,
) {
    init {
        name?.let { value ->
            if (value.isEmpty() || value.length > KWEB_SCREEN_MAX_NAME) {
                invalid(KWebScreenErrorCode.DISPLAY_NAME_INVALID, "A display name is outside the supported length.")
            }
        }
        refreshHertz?.let { value ->
            if (value !in 1..KWEB_SCREEN_MAX_REFRESH_HERTZ) {
                invalid(KWebScreenErrorCode.REFRESH_INVALID, "A refresh rate is outside the supported range.")
            }
        }
        colorDepthBits?.let { value ->
            if (value !in 1..KWEB_SCREEN_MAX_COLOR_DEPTH) {
                invalid(KWebScreenErrorCode.COLOR_DEPTH_INVALID, "A color depth is outside the supported range.")
            }
        }
        workArea?.let { area ->
            if (area.left < geometry.left || area.top < geometry.top ||
                area.right > geometry.right || area.bottom > geometry.bottom
            ) {
                invalid(KWebScreenErrorCode.GEOMETRY_INVALID, "A work area must lie inside its display.")
            }
        }
    }

    /** The rectangle that describes this display in [space]. */
    public fun geometryIn(space: KWebScreenSpace): KWebScreenGeometry = when (space) {
        KWebScreenSpace.LOGICAL -> geometry
        KWebScreenSpace.NATIVE -> nativeGeometry
        KWebScreenSpace.PHYSICAL -> geometry
    }
}

/** One atomic topology snapshot. Sequences start at 1 and never repeat. */
public data class KWebScreenTopology(
    public val providerId: String,
    public val session: KWebScreenSession,
    public val displays: List<KWebScreenDisplay>,
    public val sequence: ULong = 1uL,
) {
    init {
        if (providerId.isBlank()) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology requires its provider identity.")
        }
        if (displays.isEmpty()) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology publishes at least one display.")
        }
        if (sequence == 0uL) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology sequence starts at 1.")
        }
        if (displays.map { it.id }.toSet().size != displays.size) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology cannot repeat a display identifier.")
        }
        if (displays.count { it.primary } > 1) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology declares at most one primary display.")
        }
    }

    /** The declared primary display, or null when the session publishes none. */
    public val primary: KWebScreenDisplay? get() = displays.singleOrNull { it.primary }

    /** The display with this identifier, or null when it is not part of this snapshot. */
    public fun display(id: KWebScreenId): KWebScreenDisplay? = displays.singleOrNull { it.id == id }

    /** The display whose rectangle contains the point, or null when none does. */
    public fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenDisplay? =
        displays.singleOrNull { it.geometryIn(space).contains(point) }

    /** The closest display to the point; ties resolve to the primary, then to the first declared. */
    public fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenDisplay {
        val containing = displayAt(point, space)
        if (containing != null) return containing
        return displays.sortedWith(
            compareBy<KWebScreenDisplay> { it.geometryIn(space).distanceSquared(point) }
                .thenByDescending { it.primary }
                .thenBy { displays.indexOf(it) },
        ).first()
    }

    /** The change facts that differ between two topology snapshots. */
    public fun changedFacts(other: KWebScreenTopology): Set<KWebScreenChangeFact> = buildSet {
        if (displays.map { it.id }.toSet() != other.displays.map { it.id }.toSet()) {
            add(KWebScreenChangeFact.DISPLAYS)
        }
        if (primary?.id != other.primary?.id) add(KWebScreenChangeFact.PRIMARY)
        val shared = displays.map { it.id }.toSet() intersect other.displays.map { it.id }.toSet()
        val changedGeometry = shared.any { id ->
            val left = display(id)
            val right = other.display(id)
            left != null && right != null && (left.geometry != right.geometry ||
                left.workArea != right.workArea || left.scale != right.scale)
        }
        if (changedGeometry) add(KWebScreenChangeFact.GEOMETRY)
    }

    /** The same displays stamped with a new sequence. */
    public fun atSequence(sequence: ULong): KWebScreenTopology = copy(sequence = sequence)
}

/** One observable change between two topology snapshots. */
public enum class KWebScreenChangeFact { DISPLAYS, PRIMARY, GEOMETRY }

/** One display event. Sequences start at 1 and never repeat. */
public sealed interface KWebScreenEvent {
    public val sequence: ULong

    public data class TopologyChanged(
        public val topology: KWebScreenTopology,
        public val added: Set<KWebScreenId>,
        public val removed: Set<KWebScreenId>,
        public val changed: Set<KWebScreenId>,
        override val sequence: ULong,
    ) : KWebScreenEvent {
        init {
            if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) {
                invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A topology event reports at least one change.")
            }
        }
    }

    public data class Failed(
        public val code: String,
        override val sequence: ULong,
    ) : KWebScreenEvent
}

/** The published cursor position and the display it belongs to. */
public data class KWebScreenCursor(
    public val point: KWebScreenPoint,
    public val space: KWebScreenSpace,
    public val displayId: KWebScreenId?,
)

/** One declared conversion between two coordinate spaces of one display. */
public data class KWebScreenConversion(
    public val displayId: KWebScreenId,
    public val from: KWebScreenSpace,
    public val to: KWebScreenSpace,
    public val point: KWebScreenPoint,
)

/** Declared provider facts for the current target and session. */
public enum class KWebScreenCapability {
    WORK_AREA,
    SCALE,
    ROTATION,
    REFRESH_RATE,
    COLOR_DEPTH,
    PHYSICAL_SIZE,
    CURSOR,
    TOPOLOGY_EVENTS,
    MULTI_DISPLAY,
}

public data class KWebScreenCapabilities(
    public val providerId: String,
    public val session: KWebScreenSession,
    public val capabilities: Set<KWebScreenCapability>,
    public val spaces: Set<KWebScreenSpace>,
) {
    init {
        if (providerId.isBlank()) {
            invalid(KWebScreenErrorCode.TOPOLOGY_INVALID, "A capability report requires its provider identity.")
        }
        if (!spaces.contains(KWebScreenSpace.LOGICAL) || !spaces.contains(KWebScreenSpace.NATIVE)) {
            invalid(
                KWebScreenErrorCode.TOPOLOGY_INVALID,
                "Every session publishes the logical and native spaces for each display.",
            )
        }
    }

    public fun supports(capability: KWebScreenCapability): Boolean = capability in capabilities

    public fun supports(space: KWebScreenSpace): Boolean = space in spaces
}

public interface KWebScreens : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable topology events. */
    public val events: Flow<KWebScreenEvent>

    public suspend fun capabilities(): KWebScreenCapabilities

    /** One atomic topology snapshot. */
    public suspend fun topology(): KWebScreenTopology

    /**
     * The published cursor position. A session that does not publish one fails
     * typed instead of synthesizing a location.
     */
    public suspend fun cursor(): KWebScreenCursor

    /** The display containing the point, or null when no display does. */
    public suspend fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId?

    /** The closest display to the point; close calls never guess a removed display. */
    public suspend fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId

    /** One exact conversion between the declared spaces of one live display. */
    public suspend fun convert(request: KWebScreenConversion): KWebScreenPoint

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "screen",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("topology"),
                hostOperation("cursor"),
                hostOperation("display-at"),
                hostOperation("nearest"),
                hostOperation("convert"),
                hostOperation("events"),
                rendererOperation("read", "native.screen.read"),
                rendererOperation("subscribe", "native.screen.subscribe"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebScreens> = object : KWebServiceKey<KWebScreens> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun hostOperation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = null,
            requiresUserGesture = false,
        )

        /**
         * The renderer surface stays policy-gated and read-only: a page learns the
         * display count and the primary display's summary, never the identifiers,
         * the other displays, or the cursor.
         */
        private fun rendererOperation(id: String, permission: String): KWebServiceOperationDescriptor =
            KWebServiceOperationDescriptor(
                id = id,
                schemaVersion = 1,
                rendererPermission = permission,
                requiresUserGesture = false,
            )
    }
}

public object KWebScreenErrorCode {
    public const val PLATFORM_UNAVAILABLE: String = "screen.platform-unavailable"
    public const val TOPOLOGY_UNAVAILABLE: String = "screen.topology-unavailable"
    public const val TOPOLOGY_INVALID: String = "screen.topology-invalid"
    public const val DISPLAY_ID_INVALID: String = "screen.display-id-invalid"
    public const val DISPLAY_NAME_INVALID: String = "screen.display-name-invalid"
    public const val DISPLAY_UNKNOWN: String = "screen.display-unknown"
    public const val GEOMETRY_INVALID: String = "screen.geometry-invalid"
    public const val POINT_INVALID: String = "screen.point-invalid"
    public const val SCALE_INVALID: String = "screen.scale-invalid"
    public const val REFRESH_INVALID: String = "screen.refresh-invalid"
    public const val COLOR_DEPTH_INVALID: String = "screen.color-depth-invalid"
    public const val PHYSICAL_SIZE_INVALID: String = "screen.physical-size-invalid"
    public const val SPACE_UNSUPPORTED: String = "screen.space-unsupported"
    public const val CURSOR_UNAVAILABLE: String = "screen.cursor-unavailable"
    public const val SESSION_UNSUPPORTED: String = "screen.session-unsupported"
    public const val NATIVE_FAILED: String = "screen.native-failed"
    public const val OWNER_CLOSED: String = "screen.owner-closed"
    public const val FACT_UNKNOWN: String = "screen.fact-unknown"
    public const val FACT_NOT_RENDERER_VISIBLE: String = "screen.fact-not-renderer-visible"
}

public const val KWEB_SCREEN_EVENT_REPLAY: Int = 64
public const val KWEB_SCREEN_MIN_COORDINATE: Int = -32768
public const val KWEB_SCREEN_MAX_COORDINATE: Int = 32767
public const val KWEB_SCREEN_MAX_EXTENT: Int = 32767
public const val KWEB_SCREEN_MIN_SCALE_PERMILLE: Int = 1000
public const val KWEB_SCREEN_MAX_SCALE_PERMILLE: Int = 8000
public const val KWEB_SCREEN_MAX_REFRESH_HERTZ: Int = 1000
public const val KWEB_SCREEN_MAX_COLOR_DEPTH: Int = 64
public const val KWEB_SCREEN_MAX_MILLIMETRES: Int = 100000
public const val KWEB_SCREEN_MAX_NAME: Int = 128

internal val SCREEN_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
