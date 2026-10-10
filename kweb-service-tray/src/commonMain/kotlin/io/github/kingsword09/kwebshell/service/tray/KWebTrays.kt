/**
 * The RFC 0018 typed tray contract shared by Windows notification-area icons,
 * macOS status items, and Linux status notifier items. Values are immutable and
 * bounded before a provider sees them; Kotlin owns item identity, menu versions,
 * capability reporting, and event ordering.
 */
package io.github.kingsword09.kwebshell.service.tray

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.service.menus.KWebMenuId
import io.github.kingsword09.kwebshell.service.menus.KWebMenuTree
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One stable tray item identifier. */
public class KWebTrayItemId(public val value: String) {
    init {
        if (!TRAY_ID_PATTERN.matches(value)) {
            invalid(KWebTrayErrorCode.ITEM_ID_INVALID, "A tray item identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebTrayItemId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One scale variant of an RFC 0027 package icon resource. */
public class KWebTrayIconVariant(
    public val scale: Int,
    public val resourceId: String,
    public val sha256: String,
    public val template: Boolean = false,
) {
    init {
        if (scale !in 1..KWEB_TRAY_MAX_ICON_SCALE) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon scale must be between 1 and 8.")
        }
        validateResourceId(resourceId)
        if (!SHA256_PATTERN.matches(sha256)) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon requires a SHA-256 resource digest.")
        }
    }
}

/** One bounded icon value built from verified package resources. */
public class KWebTrayIcon(variants: Collection<KWebTrayIconVariant>) {
    private val variantSnapshot = variants.toList()

    public val variants: List<KWebTrayIconVariant> get() = variantSnapshot.toList()

    init {
        if (variantSnapshot.isEmpty() || variantSnapshot.size > KWEB_TRAY_MAX_ICON_VARIANTS) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon accepts one through eight variants.")
        }
        if (variantSnapshot.map { it.scale }.toSet().size != variantSnapshot.size) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon cannot repeat a scale.")
        }
    }

    /** The deterministic variant a single-scale provider publishes. */
    public fun selectForPlatformScale(platformScale: Int): KWebTrayIconVariant {
        val bounded = platformScale.coerceAtLeast(1)
        return variantSnapshot.filter { it.scale <= bounded }.maxByOrNull { it.scale } ?: variantSnapshot.minBy { it.scale }
    }
}

public enum class KWebTrayActivation { PRIMARY, SECONDARY, DOUBLE }

/** One bounded item declaration. */
public data class KWebTrayItemSpec(
    public val id: KWebTrayItemId,
    public val icon: KWebTrayIcon,
    public val tooltip: String? = null,
    public val activations: Set<KWebTrayActivation> = setOf(KWebTrayActivation.PRIMARY),
    public val menuId: KWebMenuId? = null,
) {
    init {
        tooltip?.let { validateTooltip(it) }
        if (activations.isEmpty() || activations.any { it !in KWebTrayActivation.entries }) {
            invalid(KWebTrayErrorCode.ACTIVATION_INVALID, "A tray item must declare at least one supported activation.")
        }
    }
}

/** One item location the platform actually publishes. */
public class KWebTrayBounds(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        if (x !in KWEB_TRAY_MIN_POSITION..KWEB_TRAY_MAX_POSITION ||
            y !in KWEB_TRAY_MIN_POSITION..KWEB_TRAY_MAX_POSITION ||
            width <= 0 || height <= 0
        ) {
            invalid(KWebTrayErrorCode.BOUNDS_INVALID, "Tray bounds are outside the supported range.")
        }
    }
}

public enum class KWebTrayRemovalReason { CLOSED, HOST_LOST, PLATFORM_REMOVED, OWNER_CLOSED }

/** One ordered service event. Sequences start at 1 and never repeat. */
public sealed interface KWebTrayEvent {
    public val sequence: ULong

    public data class Activated(
        public val itemId: KWebTrayItemId,
        public val activation: KWebTrayActivation,
        override val sequence: ULong,
    ) : KWebTrayEvent

    public data class MenuCommand(
        public val itemId: KWebTrayItemId,
        public val menuId: KWebMenuId,
        public val commandId: KWebMenuId,
        public val treeVersion: Long,
        override val sequence: ULong,
    ) : KWebTrayEvent

    public data class BalloonAction(
        public val itemId: KWebTrayItemId,
        override val sequence: ULong,
    ) : KWebTrayEvent

    public data class Removed(
        public val itemId: KWebTrayItemId,
        public val reason: KWebTrayRemovalReason,
        override val sequence: ULong,
    ) : KWebTrayEvent

    public data class Failed(
        public val itemId: KWebTrayItemId?,
        public val code: String,
        override val sequence: ULong,
    ) : KWebTrayEvent
}

public data class KWebTrayItemResult(
    public val itemId: KWebTrayItemId,
    public val additional: Boolean,
    public val appliedAtSequence: ULong,
)

public data class KWebTrayMenuResult(
    public val itemId: KWebTrayItemId,
    public val menuId: KWebMenuId,
    public val version: Long,
    public val itemCount: Int,
)

/** Declared provider capabilities for the current target. */
public enum class KWebTrayCapability {
    MENU,
    TOOLTIP,
    TEMPLATE_ICON,
    ICON_VARIANTS,
    BALLOON,
    BOUNDS,
    EXPLORER_RESTART_RECOVERY,
    WATCHER_RECONNECT,
}

public data class KWebTrayCapabilities(
    public val providerId: String,
    public val flags: Set<KWebTrayCapability>,
    public val activations: Set<KWebTrayActivation>,
) {
    public fun supports(capability: KWebTrayCapability): Boolean = capability in flags

    public fun supports(activation: KWebTrayActivation): Boolean = activation in activations
}

public interface KWebTrays : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable service events. */
    public val events: Flow<KWebTrayEvent>

    public suspend fun capabilities(): KWebTrayCapabilities

    public suspend fun create(spec: KWebTrayItemSpec): KWebTrayItemResult

    public suspend fun update(spec: KWebTrayItemSpec): KWebTrayItemResult

    /**
     * Binds one RFC 0017 menu tree version to the live item. Closing the item
     * clears its menu, and a recreated item starts without one, so no command
     * outlives an item replacement.
     */
    public suspend fun setMenu(itemId: KWebTrayItemId, tree: KWebMenuTree?): KWebTrayMenuResult

    /** Returns the published item location, or null when the platform hides it. */
    public suspend fun bounds(itemId: KWebTrayItemId): KWebTrayBounds?

    public suspend fun closeItem(itemId: KWebTrayItemId)

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "tray",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("create"),
                hostOperation("update"),
                hostOperation("set-menu"),
                hostOperation("bounds"),
                hostOperation("close-item"),
                hostOperation("events"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebTrays> = object : KWebServiceKey<KWebTrays> {
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

public object KWebTrayErrorCode {
    public const val ITEM_ID_INVALID: String = "tray.item-id-invalid"
    public const val ITEM_EXISTS: String = "tray.item-exists"
    public const val ITEM_UNKNOWN: String = "tray.item-unknown"
    public const val ITEM_LIMIT_EXCEEDED: String = "tray.item-limit-exceeded"
    public const val ICON_INVALID: String = "tray.icon-invalid"
    public const val ICON_UNSUPPORTED: String = "tray.icon-unsupported"
    public const val TOOLTIP_INVALID: String = "tray.tooltip-invalid"
    public const val TOOLTIP_UNSUPPORTED: String = "tray.tooltip-unsupported"
    public const val ACTIVATION_INVALID: String = "tray.activation-invalid"
    public const val ACTIVATION_UNSUPPORTED: String = "tray.activation-unsupported"
    public const val MENU_INVALID: String = "tray.menu-invalid"
    public const val MENU_NOT_DECLARED: String = "tray.menu-not-declared"
    public const val MENU_VERSION_STALE: String = "tray.menu-version-stale"
    public const val MENU_UNSUPPORTED: String = "tray.menu-unsupported"
    public const val BOUNDS_INVALID: String = "tray.bounds-invalid"
    public const val BOUNDS_UNAVAILABLE: String = "tray.bounds-unavailable"
    public const val BALLOON_UNSUPPORTED: String = "tray.balloon-unsupported"
    public const val PLATFORM_UNAVAILABLE: String = "tray.platform-unavailable"
    public const val HOST_LOST: String = "tray.host-lost"
    public const val NATIVE_FAILED: String = "tray.native-failed"
    public const val OWNER_CLOSED: String = "tray.owner-closed"
}

public const val KWEB_TRAY_MAX_ITEMS: Int = 4
public const val KWEB_TRAY_MAX_ICON_VARIANTS: Int = 8
public const val KWEB_TRAY_MAX_ICON_SCALE: Int = 8
public const val KWEB_TRAY_MAX_ICON_DIMENSION: Int = 256
public const val KWEB_TRAY_MAX_TOOLTIP_BYTES: Int = 64
public const val KWEB_TRAY_EVENT_REPLAY: Int = 64
public const val KWEB_TRAY_MIN_POSITION: Int = -32768
public const val KWEB_TRAY_MAX_POSITION: Int = 32767

internal val TRAY_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")
internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")

/**
 * RFC 0027 resource identifiers are path-safe package names, not tray ids, so
 * they keep their own portable-component rules.
 */
internal fun validateResourceId(value: String) {
    val components = value.split('/')
    if (value.isEmpty() || value.length > 128 || value.startsWith('/') || value.startsWith('\\') ||
        value.contains('\\') ||
        components.any {
            it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') ||
                it.any { character -> character in "<>:\"|?*" }
        } ||
        value.any { it.code < 0x20 || it == '\u007f' }
    ) {
        invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon resource identifier is not portable.")
    }
}

internal fun validateTooltip(tooltip: String) {
    val bytes = tooltip.encodeToByteArray()
    if (bytes.isEmpty() || tooltip.isBlank()) {
        invalid(KWebTrayErrorCode.TOOLTIP_INVALID, "A tray tooltip cannot be empty.")
    }
    if (bytes.size > KWEB_TRAY_MAX_TOOLTIP_BYTES) {
        invalid(KWebTrayErrorCode.TOOLTIP_INVALID, "A tray tooltip accepts at most $KWEB_TRAY_MAX_TOOLTIP_BYTES UTF-8 bytes.")
    }
    if (tooltip.any { it.isISOControl() }) {
        invalid(KWebTrayErrorCode.TOOLTIP_INVALID, "A tray tooltip cannot contain control characters.")
    }
}

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
