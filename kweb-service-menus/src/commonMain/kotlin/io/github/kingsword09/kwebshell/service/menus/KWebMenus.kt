/**
 * The RFC 0017 typed command-tree contract shared by application, window, and
 * context menus. Values are immutable, bounded, and validated before a provider
 * sees them; Kotlin owns command handling and window association.
 */
package io.github.kingsword09.kwebshell.service.menus

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

/**
 * One stable command, menu, or page-menu identifier. Identifiers are ASCII,
 * start with a letter, and never exceed 128 bytes so they stay safe for every
 * native menu API and for generated bindings.
 */
public class KWebMenuId(public val value: String) {
    init {
        if (!MENU_ID_PATTERN.matches(value)) {
            invalid(KWebMenuErrorCode.COMMAND_ID_INVALID, "A menu identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebMenuId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** A host-registered window identifier from the RFC 0007 window registry. */
public class KWebMenuWindowId(public val value: String) {
    init {
        if (!MENU_ID_PATTERN.matches(value)) {
            invalid(KWebMenuErrorCode.WINDOW_ID_INVALID, "A window identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebMenuWindowId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** The opaque identity of one page that declared a renderer-visible menu. */
public class KWebMenuPageToken(public val value: String) {
    init {
        if (!MENU_ID_PATTERN.matches(value)) {
            invalid(KWebMenuErrorCode.PAGE_TOKEN_INVALID, "A page token must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebMenuPageToken && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One open popup, assigned by the service and unique while it is open. */
public class KWebMenuPopupId(public val value: String)

/**
 * The v1 role set. A role names a standard menu behavior. A provider whose
 * platform owns that behavior maps the node to the native facility and reports
 * the role in [KWebMenuCapabilities.nativeRoles]; every other role node is
 * delivered to the application as an ordinary command invocation.
 */
public enum class KWebMenuRole {
    ABOUT,
    SERVICES,
    HIDE,
    HIDE_OTHERS,
    SHOW_ALL,
    QUIT,
    CLOSE,
    MINIMIZE,
    ZOOM,
    TOGGLE_FULL_SCREEN,
    BRING_ALL_TO_FRONT,
    WINDOW,
    HELP,
    UNDO,
    REDO,
    CUT,
    COPY,
    PASTE,
    PASTE_AND_MATCH_STYLE,
    DELETE,
    SELECT_ALL,
    RELOAD,
    FORCE_RELOAD,
    TOGGLE_DEV_TOOLS,
    BACK,
    FORWARD,
}

public enum class KWebMenuItemKind { COMMAND, CHECKBOX, RADIO, SEPARATOR, SUBMENU }

/** The toggle behavior of one command node. */
public enum class KWebMenuToggleKind { CHECKBOX, RADIO }

/** A modifier of one declared accelerator. */
public enum class KWebMenuModifier { PRIMARY, CONTROL, ALT, SHIFT, META }

/** A non-modifier accelerator key. */
public enum class KWebMenuKey {
    A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V, W, X, Y, Z,
    DIGIT_0, DIGIT_1, DIGIT_2, DIGIT_3, DIGIT_4, DIGIT_5, DIGIT_6, DIGIT_7, DIGIT_8, DIGIT_9,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    BACKSPACE, DELETE, INSERT, HOME, END, PAGE_UP, PAGE_DOWN, ARROW_LEFT, ARROW_RIGHT, ARROW_UP, ARROW_DOWN,
    ENTER, ESCAPE, TAB, SPACE, PLUS, MINUS, EQUAL, COMMA, PERIOD, SEMICOLON, SLASH, BACKSLASH, BRACKET_LEFT,
    BRACKET_RIGHT, QUOTE, BACKQUOTE,
}

/**
 * One validated accelerator. `PRIMARY` is Command on macOS and Control on
 * Windows and Linux; `META` is the platform Super/Windows key.
 */
public class KWebMenuAccelerator(
    modifiers: Collection<KWebMenuModifier>,
    public val key: KWebMenuKey,
) {
    private val modifierSnapshot = modifiers.toList()

    public val modifiers: List<KWebMenuModifier> get() = modifierSnapshot.toList()

    init {
        if (modifierSnapshot.isEmpty()) {
            invalid(KWebMenuErrorCode.ACCELERATOR_INVALID, "An accelerator requires at least one modifier.")
        }
        if (modifierSnapshot.size > KWEB_MENU_MAX_ACCELERATOR_MODIFIERS) {
            invalid(KWebMenuErrorCode.ACCELERATOR_INVALID, "An accelerator accepts at most three modifiers.")
        }
        if (modifierSnapshot.toSet().size != modifierSnapshot.size) {
            invalid(KWebMenuErrorCode.ACCELERATOR_INVALID, "An accelerator cannot repeat a modifier.")
        }
    }

    override fun equals(other: Any?): Boolean =
        other is KWebMenuAccelerator && modifierSnapshot == other.modifierSnapshot && key == other.key

    override fun hashCode(): Int = 31 * modifierSnapshot.hashCode() + key.hashCode()
}

/**
 * One icon value. Bytes always come from a verified RFC 0027 package resource;
 * renderer bytes, URLs, filesystem paths, and arbitrary formats are not menu
 * inputs.
 */
public class KWebMenuIcon(
    public val resourceId: String,
    public val sha256: String,
    public val template: Boolean = false,
)

/**
 * One immutable menu node. A separator carries no command and no label; every
 * other node carries a command identifier, so invocations never depend on a
 * platform-assigned numeric id.
 */
public sealed interface KWebMenuItem {
    public val kind: KWebMenuItemKind

    /** The command this node invokes; null only for a separator. */
    public val id: KWebMenuId?

    /** The display label; null only for a separator. */
    public val label: String?
    public val enabled: Boolean
    public val visible: Boolean

    public class Command(
        public val commandId: KWebMenuId,
        label: String,
        public val role: KWebMenuRole? = null,
        public val accelerator: KWebMenuAccelerator? = null,
        public val icon: KWebMenuIcon? = null,
        public val mnemonic: Char? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
        public val toggle: KWebMenuToggleKind? = null,
        public val checked: Boolean = false,
    ) : KWebMenuItem {
        private val labelSnapshot = validateLabel(label)
        private val mnemonicSnapshot = validateMnemonic(labelSnapshot, mnemonic)

        override val id: KWebMenuId get() = commandId
        override val label: String get() = labelSnapshot
        public val mnemonicCharacter: Char? get() = mnemonicSnapshot
        override val kind: KWebMenuItemKind = when (toggle) {
            null -> KWebMenuItemKind.COMMAND
            KWebMenuToggleKind.CHECKBOX -> KWebMenuItemKind.CHECKBOX
            KWebMenuToggleKind.RADIO -> KWebMenuItemKind.RADIO
        }

        init {
            if (toggle == null && checked) {
                invalid(KWebMenuErrorCode.TREE_INVALID, "Only checkbox and radio items may be checked.")
            }
            validateIcon(icon)
        }
    }

    public class Submenu(
        public val commandId: KWebMenuId,
        label: String,
        items: Collection<KWebMenuItem>,
        public val role: KWebMenuRole? = null,
        public val icon: KWebMenuIcon? = null,
        public val mnemonic: Char? = null,
        override val enabled: Boolean = true,
        override val visible: Boolean = true,
    ) : KWebMenuItem {
        private val labelSnapshot = validateLabel(label)
        private val mnemonicSnapshot = validateMnemonic(labelSnapshot, mnemonic)
        private val childSnapshot = items.toList()

        override val id: KWebMenuId get() = commandId
        override val label: String get() = labelSnapshot
        public val mnemonicCharacter: Char? get() = mnemonicSnapshot
        public val items: List<KWebMenuItem> get() = childSnapshot.toList()
        override val kind: KWebMenuItemKind = KWebMenuItemKind.SUBMENU

        init {
            if (childSnapshot.isEmpty()) {
                invalid(KWebMenuErrorCode.TREE_INVALID, "A submenu requires at least one item.")
            }
            validateIcon(icon)
        }
    }

    public class Separator : KWebMenuItem {
        override val kind: KWebMenuItemKind = KWebMenuItemKind.SEPARATOR
        override val id: KWebMenuId? = null
        override val label: String? = null
        override val enabled: Boolean = false
        override val visible: Boolean = true
    }
}

/**
 * One complete immutable menu. The tree carries its own menu identifier, its
 * version is strictly increasing per owner, and a newer tree replaces the
 * previous one atomically.
 */
public class KWebMenuTree(
    public val menuId: KWebMenuId,
    public val version: Long,
    items: Collection<KWebMenuItem>,
) {
    private val itemSnapshot = items.toList()

    public val items: List<KWebMenuItem> get() = itemSnapshot.toList()

    init {
        if (version < 1) {
            invalid(KWebMenuErrorCode.TREE_INVALID, "A menu tree version must be positive.")
        }
        if (itemSnapshot.isEmpty()) {
            invalid(KWebMenuErrorCode.TREE_INVALID, "A menu tree requires at least one item.")
        }
        val stats = MenuTreeStats()
        validateItems(itemSnapshot, 1, stats)
        if (stats.nodes > KWEB_MENU_MAX_NODES) {
            invalid(KWebMenuErrorCode.TREE_TOO_LARGE, "A menu tree accepts at most $KWEB_MENU_MAX_NODES nodes.")
        }
        if (stats.leaves == 0) {
            invalid(KWebMenuErrorCode.TREE_INVALID, "A menu tree requires at least one actionable item.")
        }
    }
}

private class MenuTreeStats {
    var nodes: Int = 0
    var leaves: Int = 0
    val commandIds: MutableSet<String> = mutableSetOf()
    val accelerators: MutableSet<KWebMenuAccelerator> = mutableSetOf()
}

private fun validateItems(items: List<KWebMenuItem>, depth: Int, stats: MenuTreeStats) {
    if (depth > KWEB_MENU_MAX_DEPTH) {
        invalid(KWebMenuErrorCode.TREE_DEPTH_EXCEEDED, "Menu nesting accepts at most $KWEB_MENU_MAX_DEPTH levels.")
    }
    items.forEachIndexed { index, item ->
        stats.nodes += 1
        if (item is KWebMenuItem.Separator) {
            if (index == 0 || index == items.lastIndex || items[index - 1] is KWebMenuItem.Separator) {
                invalid(KWebMenuErrorCode.TREE_INVALID, "A separator must sit between two items exactly once.")
            }
            return@forEachIndexed
        }
        stats.leaves += 1
        val id = requireNotNull(item.id).value
        if (!stats.commandIds.add(id)) {
            invalid(KWebMenuErrorCode.COMMAND_ID_DUPLICATE, "A menu tree cannot declare the command '$id' twice.")
        }
        val accelerator = (item as? KWebMenuItem.Command)?.accelerator
        if (accelerator != null && !stats.accelerators.add(accelerator)) {
            invalid(KWebMenuErrorCode.ACCELERATOR_DUPLICATE, "A menu tree cannot reuse one accelerator for two commands.")
        }
        if (item is KWebMenuItem.Submenu) {
            validateItems(item.items, depth + 1, stats)
        }
    }
}

/** The owner a menu tree or a popup belongs to. */
public sealed interface KWebMenuOwner {
    public val identifier: String

    public data object Application : KWebMenuOwner {
        override val identifier: String get() = "application"
    }

    public data class Window(public val windowId: KWebMenuWindowId) : KWebMenuOwner {
        override val identifier: String get() = "window:${windowId.value}"
    }

    public data class Page(
        public val pageToken: KWebMenuPageToken,
        public val windowId: KWebMenuWindowId? = null,
    ) : KWebMenuOwner {
        override val identifier: String
            get() = "page:${pageToken.value}${windowId?.let { "@${it.value}" } ?: ""}"
    }
}

/** The coordinate space of one popup anchor. */
public enum class KWebMenuCoordinateSpace {
    /** Client-area coordinates of the owner window. */
    WINDOW,

    /** Page viewport coordinates in device-independent pixels of the owner page. */
    PAGE,
}

/** One bounded popup anchor. */
public data class KWebMenuPosition(
    public val space: KWebMenuCoordinateSpace,
    public val x: Int,
    public val y: Int,
) {
    init {
        if (x !in KWEB_MENU_MIN_POSITION..KWEB_MENU_MAX_POSITION || y !in KWEB_MENU_MIN_POSITION..KWEB_MENU_MAX_POSITION) {
            invalid(KWebMenuErrorCode.ANCHOR_INVALID, "A popup anchor is outside the supported range.")
        }
    }
}

/** Where one popup was requested from. */
public enum class KWebMenuPopupSource { HOST, RENDERER, PAGE_CONTEXT }

/**
 * One popup request. `menuId` identifies a tree the caller declared through
 * [KWebMenus.setApplicationMenu], [KWebMenus.setWindowMenu], or
 * [KWebMenus.declarePageMenu]; arbitrary native templates are never accepted.
 */
public data class KWebMenuPopupRequest(
    public val menuId: KWebMenuId,
    public val owner: KWebMenuOwner,
    public val position: KWebMenuPosition,
    public val source: KWebMenuPopupSource = KWebMenuPopupSource.HOST,
)

public sealed interface KWebMenuPopupOutcome {
    public val popupId: KWebMenuPopupId
    public val owner: KWebMenuOwner
    public val menuId: KWebMenuId
    public val treeVersion: Long

    public data class Invoked(
        override val popupId: KWebMenuPopupId,
        override val owner: KWebMenuOwner,
        override val menuId: KWebMenuId,
        override val treeVersion: Long,
        public val commandId: KWebMenuId,
    ) : KWebMenuPopupOutcome

    public data class Dismissed(
        override val popupId: KWebMenuPopupId,
        override val owner: KWebMenuOwner,
        override val menuId: KWebMenuId,
        override val treeVersion: Long,
        public val reason: KWebMenuDismissReason,
    ) : KWebMenuPopupOutcome

    public data class Failed(
        override val popupId: KWebMenuPopupId,
        override val owner: KWebMenuOwner,
        override val menuId: KWebMenuId,
        override val treeVersion: Long,
        public val code: String,
    ) : KWebMenuPopupOutcome
}

public enum class KWebMenuDismissReason { USER_DISMISSED, REPLACED, OWNER_CLOSED, CANCELLED, OWNER_MOVED, NATIVE }

/** One applied menu tree. */
public data class KWebMenuTreeResult(
    public val owner: KWebMenuOwner,
    public val menuId: KWebMenuId,
    public val version: Long,
    public val itemCount: Int,
    public val appliedAtSequence: ULong,
)

/** One ordered service event. Sequences start at 1 and never repeat. */
public sealed interface KWebMenuEvent {
    public val sequence: ULong

    public data class Invoked(
        public val invocation: KWebMenuInvocation,
        override val sequence: ULong,
    ) : KWebMenuEvent

    public data class Dismissed(
        public val popupId: KWebMenuPopupId,
        public val owner: KWebMenuOwner,
        public val menuId: KWebMenuId,
        public val reason: KWebMenuDismissReason,
        override val sequence: ULong,
    ) : KWebMenuEvent

    public data class Failed(
        public val owner: KWebMenuOwner,
        public val menuId: KWebMenuId,
        public val code: String,
        override val sequence: ULong,
    ) : KWebMenuEvent
}

/** How one command was invoked. */
public enum class KWebMenuInvocationSource { APPLICATION_MENU, WINDOW_MENU, POPUP, PAGE_MENU }

public data class KWebMenuInvocation(
    public val source: KWebMenuInvocationSource,
    public val owner: KWebMenuOwner,
    public val commandId: KWebMenuId,
    public val treeVersion: Long,
    public val popupId: KWebMenuPopupId? = null,
)

/** Declared provider capabilities for the current target. */
public enum class KWebMenuCapability {
    APPLICATION_MENU,
    WINDOW_MENU,
    PAGE_MENU,
    GLOBAL_MENU_HOST,
    SUBMENUS,
    CHECKBOX_ITEMS,
    RADIO_ITEMS,
    ITEM_ICONS,
    MNEMONICS,
    ACCELERATOR_DISPLAY,
    ACCELERATOR_ACTIVATION,
    POPUP_POSITIONING,
}

public data class KWebMenuCapabilities(
    public val providerId: String,
    public val flags: Set<KWebMenuCapability>,
    public val nativeRoles: Set<KWebMenuRole>,
) {
    public fun supports(capability: KWebMenuCapability): Boolean = capability in flags

    public fun handlesNatively(role: KWebMenuRole): Boolean = role in nativeRoles
}

public interface KWebMenus : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    /** Ordered, replayable service events. */
    public val events: Flow<KWebMenuEvent>

    public suspend fun capabilities(): KWebMenuCapabilities

    public suspend fun setApplicationMenu(tree: KWebMenuTree?): KWebMenuTreeResult

    public suspend fun setWindowMenu(windowId: KWebMenuWindowId, tree: KWebMenuTree?): KWebMenuTreeResult

    public suspend fun declarePageMenu(
        pageToken: KWebMenuPageToken,
        tree: KWebMenuTree,
    ): KWebMenuTreeResult

    public suspend fun clearPageMenu(pageToken: KWebMenuPageToken, menuId: KWebMenuId)

    /**
     * Presents one popup for a declared menu and returns its terminal outcome.
     * The popup owns an immutable snapshot of the tree version that is current
     * when it opens; a replacement tree applies to the next presentation.
     */
    public suspend fun showPopup(request: KWebMenuPopupRequest): KWebMenuPopupOutcome

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "menus",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                hostOperation("set-application-menu"),
                hostOperation("set-window-menu"),
                hostOperation("declare-page-menu"),
                hostOperation("clear-page-menu"),
                hostOperation("show-popup"),
                hostOperation("capabilities"),
                hostOperation("events"),
                KWebServiceOperationDescriptor(
                    id = "show-declared-popup",
                    schemaVersion = 1,
                    rendererPermission = "native.menus.show-declared-popup",
                    requiresUserGesture = true,
                ),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebMenus> = object : KWebServiceKey<KWebMenus> {
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

public object KWebMenuErrorCode {
    public const val TREE_INVALID: String = "menus.tree-invalid"
    public const val TREE_TOO_LARGE: String = "menus.tree-too-large"
    public const val TREE_DEPTH_EXCEEDED: String = "menus.tree-depth-exceeded"
    public const val COMMAND_ID_INVALID: String = "menus.command-id-invalid"
    public const val COMMAND_ID_DUPLICATE: String = "menus.command-id-duplicate"
    public const val WINDOW_ID_INVALID: String = "menus.window-id-invalid"
    public const val PAGE_TOKEN_INVALID: String = "menus.page-token-invalid"
    public const val LABEL_INVALID: String = "menus.label-invalid"
    public const val LABEL_TOO_LONG: String = "menus.label-too-long"
    public const val MNEMONIC_INVALID: String = "menus.mnemonic-invalid"
    public const val ACCELERATOR_INVALID: String = "menus.accelerator-invalid"
    public const val ACCELERATOR_DUPLICATE: String = "menus.accelerator-duplicate"
    public const val ICON_INVALID: String = "menus.icon-invalid"
    public const val ICON_UNSUPPORTED: String = "menus.icon-unsupported"
    public const val MNEMONIC_UNSUPPORTED: String = "menus.mnemonic-unsupported"
    public const val VERSION_STALE: String = "menus.version-stale"
    public const val WINDOW_UNKNOWN: String = "menus.window-unknown"
    public const val PAGE_UNKNOWN: String = "menus.page-unknown"
    public const val MENU_NOT_DECLARED: String = "menus.menu-not-declared"
    public const val POPUP_LIMIT: String = "menus.popup-limit"
    public const val ANCHOR_INVALID: String = "menus.anchor-invalid"
    public const val TARGET_UNSUPPORTED: String = "menus.target-unsupported"
    public const val PLATFORM_UNAVAILABLE: String = "menus.platform-unavailable"
    public const val NATIVE_FAILED: String = "menus.native-failed"
    public const val REQUEST_INVALID: String = "menus.request-invalid"
    public const val ABI_MISMATCH: String = "menus.abi-mismatch"
    public const val OUTCOME_UNKNOWN: String = "menus.outcome-unknown"
    public const val OWNER_CLOSED: String = "menus.owner-closed"
    public const val PERMISSION_DENIED: String = "menus.permission-denied"
    public const val CONSENT_REQUIRED: String = "menus.consent-required"
    public const val CANCELLED: String = "menus.cancelled"
}

public const val KWEB_MENU_MAX_NODES: Int = 512
public const val KWEB_MENU_MAX_DEPTH: Int = 8
public const val KWEB_MENU_MAX_LABEL_BYTES: Int = 256
public const val KWEB_MENU_MAX_ACCELERATOR_MODIFIERS: Int = 3
public const val KWEB_MENU_MAX_OPEN_POPUPS: Int = 4
public const val KWEB_MENU_MAX_ICON_DIMENSION: Int = 256
public const val KWEB_MENU_EVENT_REPLAY: Int = 64
public const val KWEB_MENU_MIN_POSITION: Int = -32768
public const val KWEB_MENU_MAX_POSITION: Int = 32767

internal val MENU_ID_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun validateLabel(label: String): String {
    val bytes = label.encodeToByteArray()
    if (bytes.isEmpty() || label.isBlank()) {
        invalid(KWebMenuErrorCode.LABEL_INVALID, "A menu label cannot be empty.")
    }
    if (bytes.size > KWEB_MENU_MAX_LABEL_BYTES) {
        invalid(KWebMenuErrorCode.LABEL_TOO_LONG, "A menu label accepts at most $KWEB_MENU_MAX_LABEL_BYTES UTF-8 bytes.")
    }
    if (label.any { it.isISOControl() && it != '\t' } || label.contains('\u0000')) {
        invalid(KWebMenuErrorCode.LABEL_INVALID, "A menu label cannot contain control characters.")
    }
    return label
}

internal fun validateMnemonic(label: String, mnemonic: Char?): Char? {
    if (mnemonic == null) return null
    if (!mnemonic.isLetter()) {
        invalid(KWebMenuErrorCode.MNEMONIC_INVALID, "A mnemonic must be a single letter.")
    }
    if (label.none { it.equals(mnemonic, ignoreCase = true) }) {
        invalid(KWebMenuErrorCode.MNEMONIC_INVALID, "A mnemonic must appear in the label.")
    }
    return mnemonic
}

internal fun validateIcon(icon: KWebMenuIcon?) {
    if (icon == null) return
    if (!MENU_ID_PATTERN.matches(icon.resourceId)) {
        invalid(KWebMenuErrorCode.ICON_INVALID, "A menu icon resource identifier is invalid.")
    }
    if (!SHA256_PATTERN.matches(icon.sha256)) {
        invalid(KWebMenuErrorCode.ICON_INVALID, "A menu icon requires a SHA-256 resource digest.")
    }
}

internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
