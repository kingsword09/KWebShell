package io.github.kingsword09.kwebshell.service.menus

import io.github.kingsword09.kwebshell.service.menus.internal.MenuFfm

/**
 * One explicit mapping between the published Kotlin model and the versioned C
 * ABI codes. Every mapping is total and version-checked by the native struct
 * sizes, so an unmapped value fails instead of silently degrading.
 */
internal fun itemKind(item: KWebMenuItem): Int = itemKindCode(item.kind)

internal fun itemKindCode(kind: KWebMenuItemKind): Int = when (kind) {
    KWebMenuItemKind.COMMAND -> MenuFfm.KIND_COMMAND
    KWebMenuItemKind.CHECKBOX -> MenuFfm.KIND_CHECKBOX
    KWebMenuItemKind.RADIO -> MenuFfm.KIND_RADIO
    KWebMenuItemKind.SEPARATOR -> MenuFfm.KIND_SEPARATOR
    KWebMenuItemKind.SUBMENU -> MenuFfm.KIND_SUBMENU
}

internal fun roleCode(role: KWebMenuRole?): Int = role?.let { it.ordinal + 1 } ?: 0

internal fun modifierCode(accelerator: KWebMenuAccelerator): Int =
    accelerator.modifiers.fold(0) { bits, modifier -> bits or (1 shl modifier.ordinal) }

internal fun keyCode(key: KWebMenuKey): Int = key.ordinal + 1

internal fun decodeFlags(flags: Long): Set<KWebMenuCapability> {
    val decoded = KWebMenuCapability.entries.filter { (flags and (1L shl it.ordinal)) != 0L }.toSet()
    return decoded
}

internal fun decodeRoles(roles: Long): Set<KWebMenuRole> {
    val decoded = KWebMenuRole.entries.filter { (roles and (1L shl it.ordinal)) != 0L }.toSet()
    return decoded
}

internal fun dismissReason(code: Int): KWebMenuDismissReason = when (code) {
    MenuFfm.DISMISS_REPLACED -> KWebMenuDismissReason.REPLACED
    MenuFfm.DISMISS_OWNER_CLOSED -> KWebMenuDismissReason.OWNER_CLOSED
    MenuFfm.DISMISS_CANCELLED -> KWebMenuDismissReason.CANCELLED
    MenuFfm.DISMISS_OWNER_MOVED -> KWebMenuDismissReason.OWNER_MOVED
    MenuFfm.DISMISS_NATIVE -> KWebMenuDismissReason.NATIVE
    else -> KWebMenuDismissReason.USER_DISMISSED
}

internal fun ownerOf(kind: Int, ownerId: String): KWebMenuOwner = when (kind) {
    MenuFfm.OWNER_WINDOW -> KWebMenuOwner.Window(KWebMenuWindowId(ownerId))
    MenuFfm.OWNER_PAGE -> KWebMenuOwner.Page(KWebMenuPageToken(ownerId))
    else -> KWebMenuOwner.Application
}

internal fun decodeEvent(event: MenuFfm.Event): KWebMenuEvent = when (event.kind) {
    MenuFfm.EVENT_INVOKED -> KWebMenuEvent.Invoked(
        KWebMenuInvocation(
            source = when (event.source) {
                MenuFfm.SOURCE_WINDOW_MENU -> KWebMenuInvocationSource.WINDOW_MENU
                MenuFfm.SOURCE_POPUP -> KWebMenuInvocationSource.POPUP
                MenuFfm.SOURCE_PAGE_MENU -> KWebMenuInvocationSource.PAGE_MENU
                else -> KWebMenuInvocationSource.APPLICATION_MENU
            },
            owner = ownerOf(event.ownerKind, event.ownerId),
            commandId = KWebMenuId(event.commandId),
            treeVersion = event.treeVersion,
            popupId = event.popupId.takeIf { it.isNotEmpty() }?.let { KWebMenuPopupId(it) },
        ),
        event.sequence.toULong(),
    )

    MenuFfm.EVENT_DISMISSED -> KWebMenuEvent.Dismissed(
        popupId = KWebMenuPopupId(event.popupId),
        owner = ownerOf(event.ownerKind, event.ownerId),
        menuId = KWebMenuId(event.ownerId.ifEmpty { "menus.popup" }),
        reason = dismissReason(event.dismissReason),
        sequence = event.sequence.toULong(),
    )

    else -> KWebMenuEvent.Failed(
        owner = ownerOf(event.ownerKind, event.ownerId),
        menuId = KWebMenuId(event.ownerId.ifEmpty { "menus.failed" }),
        code = event.code.ifEmpty { KWebMenuErrorCode.NATIVE_FAILED },
        sequence = event.sequence.toULong(),
    )
}
