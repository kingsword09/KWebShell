package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow

/**
 * The kind of one item in the page context menu Chromium observed. The item
 * list is Chromium's own default model, so it carries navigation and editing
 * commands only; extension-provided items require the patched runtime.
 */
public enum class KWebContextMenuItemKind(public val id: String) {
    COMMAND("command"),
    CHECKBOX("checkbox"),
    RADIO("radio"),
    SEPARATOR("separator"),
    SUBMENU("submenu"),
}

/**
 * One Chromium-provided menu item. `command` is a stable virtual identifier
 * (`chromium.copy`, `chromium.id.123`, ...) that the application echoes back in
 * a [KWebContextMenuDecision.CONTINUE] decision.
 */
public sealed interface KWebContextMenuItem {
    public val kind: KWebContextMenuItemKind
    public val command: String?
    public val label: String?
    public val enabled: Boolean
    public val checked: Boolean
    public val items: List<KWebContextMenuItem>

    public data class Item(
        override val kind: KWebContextMenuItemKind,
        override val command: String,
        override val label: String,
        override val enabled: Boolean,
        override val checked: Boolean,
        override val items: List<KWebContextMenuItem> = emptyList(),
    ) : KWebContextMenuItem {
        init {
            if (kind == KWebContextMenuItemKind.SEPARATOR) {
                throw contextMenuError("kind", "a separator is its own item shape")
            }
            requireContextMenuCommand(command)
            requireContextMenuText("label", label, 512, allowEmpty = true)
            if (items.isNotEmpty() && kind != KWebContextMenuItemKind.SUBMENU) {
                throw contextMenuError("items", "only a submenu may declare children")
            }
        }
    }

    public data object Separator : KWebContextMenuItem {
        override val kind: KWebContextMenuItemKind = KWebContextMenuItemKind.SEPARATOR
        override val command: String? = null
        override val label: String? = null
        override val enabled: Boolean = false
        override val checked: Boolean = false
        override val items: List<KWebContextMenuItem> = emptyList()
    }
}

/**
 * One page context-menu request. It is a request, not a presented menu: the
 * application decides which items it shows and how, then answers with a
 * [KWebContextMenuDecision].
 */
public data class KWebContextMenuRequest(
    public val requestId: Long,
    public val profileId: String,
    public val pageId: String?,
    public val origin: String?,
    public val url: String,
    public val frameId: String,
    public val isMainFrame: Boolean,
    public val x: Int,
    public val y: Int,
    public val editable: Boolean,
    public val selectionText: String,
    public val linkUrl: String,
    public val items: List<KWebContextMenuItem>,
) {
    init {
        if (requestId <= 0L) throw contextMenuError("requestId", "must be positive")
        requireContextMenuText("profileId", profileId, 256)
        pageId?.let { requireContextMenuText("pageId", it, 256) }
        origin?.let { requireContextMenuText("origin", it, 2048) }
        requireContextMenuText("url", url, 8192, allowEmpty = true)
        requireContextMenuText("frameId", frameId, 256, allowEmpty = true)
        requireContextMenuText("selectionText", selectionText, 4096, allowEmpty = true)
        requireContextMenuText("linkUrl", linkUrl, 8192, allowEmpty = true)
        if (x !in -32768..32767 || y !in -32768..32767) {
            throw contextMenuError("coordinates", "are outside the supported range")
        }
    }
}

public sealed interface KWebContextMenuDecision {
    /** Lets Chromium execute the item identified by its virtual command. */
    public data class CONTINUE(public val command: String) : KWebContextMenuDecision {
        init {
            requireContextMenuCommand(command)
        }
    }

    /** Closes the menu without executing a Chromium command. */
    public data object DISMISS : KWebContextMenuDecision
}

public enum class KWebContextMenuOutcome(public val id: String) {
    CONTINUED("continued"),
    DISMISSED("dismissed"),
    STALE("stale"),
    OWNER_CLOSED("owner-closed"),
    REJECTED("rejected"),
}

public data class KWebContextMenuResult(
    public val requestId: Long,
    public val outcome: KWebContextMenuOutcome,
)

internal val CONTEXT_MENU_COMMAND_PATTERN = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal fun requireContextMenuCommand(value: String) {
    if (!CONTEXT_MENU_COMMAND_PATTERN.matches(value)) {
        throw contextMenuError("command", "must be a stable dotted identifier")
    }
}

internal fun requireContextMenuText(
    name: String,
    value: String,
    maximumBytes: Int,
    allowEmpty: Boolean = false,
) {
    if ((!allowEmpty && value.isEmpty()) || value.contains('\u0000') ||
        value.encodeToByteArray().size > maximumBytes
    ) {
        throw contextMenuError(name, "is empty, contains NUL, or exceeds $maximumBytes UTF-8 bytes")
    }
}

private fun contextMenuError(field: String, reason: String): KWebConfigurationException =
    KWebConfigurationException(
        code = "page.context-menu.invalid",
        details = mapOf("field" to field, "reason" to reason),
        message = "The page context-menu field '$field' is invalid: $reason.",
    )
