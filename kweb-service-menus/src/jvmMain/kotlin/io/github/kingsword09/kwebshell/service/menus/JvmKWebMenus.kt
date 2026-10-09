package io.github.kingsword09.kwebshell.service.menus

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.menus.internal.MenuFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.EventQueue
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** Straight RGBA8 pixels of one verified package image resource. */
public class KWebMenuIconPixels(
    public val width: Int,
    public val height: Int,
    rgba: ByteArray,
) {
    public val rgba: ByteArray = rgba.copyOf()

    init {
        if (width !in 1..KWEB_MENU_MAX_ICON_DIMENSION || height !in 1..KWEB_MENU_MAX_ICON_DIMENSION) {
            invalid(KWebMenuErrorCode.ICON_INVALID, "A menu icon is outside the supported dimension range.")
        }
        if (this.rgba.size != width * height * 4) {
            invalid(KWebMenuErrorCode.ICON_INVALID, "A menu icon must contain exactly one RGBA8 buffer per pixel.")
        }
    }
}

/**
 * Resolves one declared package icon through RFC 0027. The host installs the
 * resolver; the menus service never reads renderer bytes, URLs, or paths.
 */
public fun interface KWebMenuIconResolver {
    public suspend fun resolve(resourceId: String, sha256: String): KWebMenuIconPixels
}

/** One registered Compose window that can own a native menu or popup. */
public class KWebMenuWindowBinding(
    public val windowId: KWebMenuWindowId,
    public val window: ComposeWindow,
)

/**
 * The JVM menus session. It requires the packaged provider library explicitly
 * and dispatches every native menu call to the AWT event thread, which is the
 * AppKit main thread on macOS and the Win32/X11 user-interface thread on the
 * other targets.
 */
public class JvmKWebMenusSession internal constructor(
    private val native: MenuFfm,
    private val iconResolver: KWebMenuIconResolver?,
    private val scope: CoroutineScope,
) : KWebMenus {

    override val descriptor: KWebServiceDescriptor = KWebMenus.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebMenuEvent>(
        replay = KWEB_MENU_EVENT_REPLAY,
        extraBufferCapacity = KWEB_MENU_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebMenuEvent> = mutableEvents.asSharedFlow()

    private val windows = CopyOnWriteArrayList<KWebMenuWindowBinding>()
    private val pageAnchors = java.util.concurrent.ConcurrentHashMap<String, PageAnchor>()
    private val treeVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }

    private var capabilitiesCache: KWebMenuCapabilities? = null
    private var closeFailure: KWebException? = null

    private class PageAnchor(val window: KWebMenuWindowBinding, val offsetX: Int, val offsetY: Int)

    /** Registers one Compose window; the menu bar attaches to its native handle. */
    public fun registerWindow(binding: KWebMenuWindowBinding) {
        requireOpen()
        if (windows.none { it.windowId == binding.windowId }) windows.add(binding)
    }

    public fun unregisterWindow(windowId: KWebMenuWindowId) {
        windows.removeIf { it.windowId == windowId }
    }

    /** Declares where the page viewport origin sits inside its owner window. */
    public fun registerPageAnchor(
        pageToken: KWebMenuPageToken,
        windowId: KWebMenuWindowId,
        offsetX: Int,
        offsetY: Int,
    ) {
        requireOpen()
        val binding = windows.singleOrNull { it.windowId == windowId }
            ?: throw failure(KWebMenuErrorCode.WINDOW_UNKNOWN, "register-page-anchor", mapOf("window" to windowId.value))
        pageAnchors[pageToken.value] = PageAnchor(binding, offsetX, offsetY)
    }

    public fun unregisterPageAnchor(pageToken: KWebMenuPageToken) {
        pageAnchors.remove(pageToken.value)
    }

    override suspend fun capabilities(): KWebMenuCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { onEdtSync { native.capabilities() } }
        val capabilities = KWebMenuCapabilities(
            providerId = native.providerId(),
            flags = decodeFlags(result.flags),
            nativeRoles = decodeRoles(result.nativeRoles),
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun setApplicationMenu(tree: KWebMenuTree?): KWebMenuTreeResult =
        applyTree(KWebMenuOwner.Application, tree)

    override suspend fun setWindowMenu(windowId: KWebMenuWindowId, tree: KWebMenuTree?): KWebMenuTreeResult {
        requireOpen()
        val binding = windows.singleOrNull { it.windowId == windowId }
            ?: throw failure(KWebMenuErrorCode.WINDOW_UNKNOWN, "set-window-menu", mapOf("window" to windowId.value))
        val owner = KWebMenuOwner.Window(windowId)
        if (tree != null && !capabilitiesSync().supports(KWebMenuCapability.WINDOW_MENU)) {
            throw failure(
                KWebMenuErrorCode.TARGET_UNSUPPORTED,
                "set-window-menu",
                mapOf("window" to windowId.value, "provider" to native.providerId()),
            )
        }
        val flat = tree?.let { prepare(it, owner) }
        translate("set-window-menu") { onEdtSync { native.setWindowMenu(windowId.value, binding.window.windowHandle, flat) } }
        treeVersions[owner.identifier + ":" + (tree?.menuId?.value ?: "")] = tree?.version ?: 0L
        return KWebMenuTreeResult(owner, tree?.menuId ?: KWebMenuId("menus.cleared"), tree?.version ?: 0L,
            flat?.items?.count { it.kind != MenuFfm.KIND_SEPARATOR } ?: 0, 0u)
    }

    override suspend fun declarePageMenu(pageToken: KWebMenuPageToken, tree: KWebMenuTree): KWebMenuTreeResult {
        requireOpen()
        val anchor = pageAnchors[pageToken.value]
            ?: throw failure(KWebMenuErrorCode.PAGE_UNKNOWN, "declare-page-menu", mapOf("page" to pageToken.value))
        val owner = KWebMenuOwner.Page(pageToken, anchor.window.windowId)
        val flat = prepare(tree, owner)
        translate("declare-page-menu") { onEdtSync { native.declarePageMenu(pageToken.value, flat) } }
        return KWebMenuTreeResult(owner, tree.menuId, tree.version, flat.items.count { it.kind != MenuFfm.KIND_SEPARATOR }, 0u)
    }

    override suspend fun clearPageMenu(pageToken: KWebMenuPageToken, menuId: KWebMenuId) {
        requireOpen()
        translate("clear-page-menu") { onEdtSync { native.clearPageMenu(pageToken.value, menuId.value) } }
    }

    override suspend fun showPopup(request: KWebMenuPopupRequest): KWebMenuPopupOutcome {
        requireOpen()
        val (ownerKind, ownerId, nativeWindow, origin) = when (val owner = request.owner) {
            KWebMenuOwner.Application -> PopupTarget(MenuFfm.OWNER_APPLICATION, "", 0L, 0 to 0)
            is KWebMenuOwner.Window -> {
                val binding = windows.singleOrNull { it.windowId == owner.windowId }
                    ?: throw failure(KWebMenuErrorCode.WINDOW_UNKNOWN, "show-popup", mapOf("window" to owner.windowId.value))
                PopupTarget(MenuFfm.OWNER_WINDOW, owner.windowId.value, binding.window.windowHandle, windowOrigin(binding))
            }
            is KWebMenuOwner.Page -> {
                val anchor = pageAnchors[owner.pageToken.value]
                    ?: throw failure(KWebMenuErrorCode.PAGE_UNKNOWN, "show-popup", mapOf("page" to owner.pageToken.value))
                val windowOrigin = windowOrigin(anchor.window)
                PopupTarget(
                    MenuFfm.OWNER_PAGE,
                    owner.pageToken.value,
                    anchor.window.window.windowHandle,
                    (windowOrigin.first + anchor.offsetX) to (windowOrigin.second + anchor.offsetY),
                )
            }
        }
        val screenX = origin.first + request.position.x
        val screenY = origin.second + request.position.y
        if (screenX !in KWEB_MENU_MIN_POSITION..KWEB_MENU_MAX_POSITION ||
            screenY !in KWEB_MENU_MIN_POSITION..KWEB_MENU_MAX_POSITION
        ) {
            throw failure(KWebMenuErrorCode.ANCHOR_INVALID, "show-popup", mapOf("menu" to request.menuId.value))
        }
        val source = when (request.source) {
            KWebMenuPopupSource.HOST -> MenuFfm.POPUP_SOURCE_HOST
            KWebMenuPopupSource.RENDERER -> MenuFfm.POPUP_SOURCE_RENDERER
            KWebMenuPopupSource.PAGE_CONTEXT -> MenuFfm.POPUP_SOURCE_PAGE_CONTEXT
        }
        val result = translate("show-popup") {
            onEdtSync {
                native.showPopup(ownerKind, ownerId, request.menuId.value, source, screenX, screenY, nativeWindow)
            }
        }
        val popupId = KWebMenuPopupId(result.popupId)
        return when (result.outcome) {
            MenuFfm.OUTCOME_INVOKED -> KWebMenuPopupOutcome.Invoked(
                popupId, request.owner, request.menuId, result.treeVersion, KWebMenuId(result.commandId),
            )
            else -> KWebMenuPopupOutcome.Dismissed(
                popupId, request.owner, request.menuId, result.treeVersion, dismissReason(result.dismissReason),
            )
        }
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        poller.cancel()
        var failure: KWebException? = null
        try {
            runBlocking { onEdtSync { native.close() } }
        } catch (error: KWebException) {
            failure = error
        } catch (error: Throwable) {
            failure = KWebServiceException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The menus provider failed while closing.",
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

    private suspend fun applyTree(owner: KWebMenuOwner, tree: KWebMenuTree?): KWebMenuTreeResult {
        requireOpen()
        val flat = tree?.let { prepare(it, owner) }
        translate("set-application-menu") { onEdtSync { native.setApplicationMenu(flat) } }
        if (tree != null) treeVersions[owner.identifier + ":" + tree.menuId.value] = tree.version
        return KWebMenuTreeResult(
            owner,
            tree?.menuId ?: KWebMenuId("menus.cleared"),
            tree?.version ?: 0L,
            flat?.items?.count { it.kind != MenuFfm.KIND_SEPARATOR } ?: 0,
            0u,
        )
    }

    /** Validates one tree against the provider capabilities and flattens it. */
    private suspend fun prepare(tree: KWebMenuTree, owner: KWebMenuOwner): MenuFfm.FlatTree {
        val capabilities = capabilities()
        val flat = flatten(tree, capabilities)
        val previous = treeVersions[owner.identifier + ":" + tree.menuId.value]
        if (previous != null && tree.version <= previous) {
            throw failure(
                KWebMenuErrorCode.VERSION_STALE,
                "apply-menu",
                mapOf("owner" to owner.identifier, "version" to tree.version.toString()),
            )
        }
        return flat
    }

    private suspend fun flatten(tree: KWebMenuTree, capabilities: KWebMenuCapabilities): MenuFfm.FlatTree {
        val items = mutableListOf<MenuFfm.FlatItem>()
        suspend fun append(item: KWebMenuItem) {
            val id = item.id?.value ?: ""
            val label = item.label ?: ""
            val role = (item as? KWebMenuItem.Command)?.role ?: (item as? KWebMenuItem.Submenu)?.role
            val accelerator = (item as? KWebMenuItem.Command)?.accelerator
            val icon = (item as? KWebMenuItem.Command)?.icon ?: (item as? KWebMenuItem.Submenu)?.icon
            val mnemonic = (item as? KWebMenuItem.Command)?.mnemonicCharacter
                ?: (item as? KWebMenuItem.Submenu)?.mnemonicCharacter
            if (mnemonic != null && !capabilities.supports(KWebMenuCapability.MNEMONICS)) {
                throw failure(
                    KWebMenuErrorCode.MNEMONIC_UNSUPPORTED,
                    "apply-menu",
                    mapOf("command" to id, "provider" to capabilities.providerId),
                )
            }
            val pixels = if (icon != null) {
                if (!capabilities.supports(KWebMenuCapability.ITEM_ICONS)) {
                    throw failure(
                        KWebMenuErrorCode.ICON_UNSUPPORTED,
                        "apply-menu",
                        mapOf("command" to id, "provider" to capabilities.providerId),
                    )
                }
                val resolver = iconResolver ?: throw failure(
                    KWebMenuErrorCode.ICON_UNSUPPORTED,
                    "apply-menu",
                    mapOf("command" to id, "reason" to "resolver-missing"),
                )
                resolver.resolve(icon.resourceId, icon.sha256)
            } else {
                null
            }
            val flags = (if (item.enabled) MenuFfm.FLAG_ENABLED else 0) or
                (if (item.visible) MenuFfm.FLAG_VISIBLE else 0) or
                (if ((item as? KWebMenuItem.Command)?.checked == true) MenuFfm.FLAG_CHECKED else 0) or
                (if (icon?.template == true) MenuFfm.FLAG_TEMPLATE_ICON else 0)
            val children = when (item) {
                is KWebMenuItem.Submenu -> item.items
                else -> emptyList()
            }
            items.add(
                MenuFfm.FlatItem(
                    id,
                    label,
                    itemKind(item),
                    flags,
                    roleCode(role),
                    mnemonic?.code ?: 0,
                    accelerator?.let { modifierCode(it) } ?: 0,
                    accelerator?.let { keyCode(it.key) } ?: 0,
                    icon?.resourceId ?: "",
                    icon?.sha256 ?: "",
                    pixels?.rgba,
                    pixels?.width ?: 0,
                    pixels?.height ?: 0,
                    children.size,
                ),
            )
            children.forEach { child -> append(child) }
        }
        tree.items.forEach { append(it) }
        return MenuFfm.FlatTree(tree.menuId.value, tree.version, items)
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

    private suspend fun capabilitiesSync(): KWebMenuCapabilities = capabilities()

    private fun windowOrigin(binding: KWebMenuWindowBinding): Pair<Int, Int> {
        val location = binding.window.locationOnScreen
        val insets = binding.window.insets
        return (location.x + insets.left) to (location.y + insets.top)
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebMenuErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The menus operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: MenuFfm.MenuNativeException) {
        throw failure(
            when (error.status) {
                3 -> KWebMenuErrorCode.PLATFORM_UNAVAILABLE
                1 -> KWebMenuErrorCode.REQUEST_INVALID
                2 -> KWebMenuErrorCode.ABI_MISMATCH
                4, 5, 6, 7, 8, 9 -> KWebMenuErrorCode.TREE_INVALID
                10 -> KWebMenuErrorCode.VERSION_STALE
                11 -> KWebMenuErrorCode.WINDOW_UNKNOWN
                12 -> KWebMenuErrorCode.PAGE_UNKNOWN
                13 -> KWebMenuErrorCode.MENU_NOT_DECLARED
                14 -> KWebMenuErrorCode.POPUP_LIMIT
                15 -> KWebMenuErrorCode.ANCHOR_INVALID
                16 -> KWebMenuErrorCode.TARGET_UNSUPPORTED
                17 -> KWebMenuErrorCode.ICON_UNSUPPORTED
                20 -> KWebMenuErrorCode.OWNER_CLOSED
                else -> KWebMenuErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }

    private fun <T> onEdtSync(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching { block() } }
        return requireNotNull(result).getOrThrow()
    }

    private data class PopupTarget(
        val ownerKind: Int,
        val ownerId: String,
        val nativeWindow: Long,
        val origin: Pair<Int, Int>,
    )
}

public object JvmKWebMenus {
    /**
     * Opens the packaged menus provider. Every declared window must be a live
     * Compose window; there is no implicit fallback provider and no ambient
     * window discovery.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        windows: List<KWebMenuWindowBinding> = emptyList(),
        packageIdentity: String = applicationId,
        iconResolver: KWebMenuIconResolver? = null,
    ): JvmKWebMenusSession {
        var session: JvmKWebMenusSession? = null
        var failure: Throwable? = null
        val open = {
            try {
                val native = MenuFfm.open(nativeLibrary, applicationId, packageIdentity)
                session = JvmKWebMenusSession(native, iconResolver, CoroutineScope(SupervisorJob() + Dispatchers.Default))
            } catch (error: Throwable) {
                failure = error
            }
        }
        if (EventQueue.isDispatchThread()) {
            open()
        } else {
            EventQueue.invokeAndWait(open)
        }
        failure?.let { throw it }
        val created = requireNotNull(session)
        windows.forEach { created.registerWindow(it) }
        return created
    }
}
