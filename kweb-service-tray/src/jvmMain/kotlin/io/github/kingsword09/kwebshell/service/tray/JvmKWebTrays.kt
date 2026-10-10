package io.github.kingsword09.kwebshell.service.tray

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.menus.KWebMenuItem
import io.github.kingsword09.kwebshell.service.menus.KWebMenuId
import io.github.kingsword09.kwebshell.service.menus.KWebMenuTree
import io.github.kingsword09.kwebshell.service.tray.internal.TrayFfm
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
import java.nio.file.Path

/**
 * Resolves one declared package icon through RFC 0027. The host installs the
 * resolver; the tray service never reads renderer bytes, URLs, or paths.
 */
public fun interface KWebTrayIconResolver {
    public suspend fun resolve(resourceId: String, sha256: String): KWebTrayIconPixels
}

/** Straight RGBA8 pixels of one verified package image resource. */
public class KWebTrayIconPixels(
    public val width: Int,
    public val height: Int,
    rgba: ByteArray,
) {
    public val rgba: ByteArray = rgba.copyOf()

    init {
        if (width !in 1..KWEB_TRAY_MAX_ICON_DIMENSION || height !in 1..KWEB_TRAY_MAX_ICON_DIMENSION) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon is outside the supported dimension range.")
        }
        if (this.rgba.size != width * height * 4) {
            invalid(KWebTrayErrorCode.ICON_INVALID, "A tray icon must contain exactly one RGBA8 buffer per pixel.")
        }
    }
}

/**
 * The JVM tray session. It requires the packaged provider library explicitly
 * and dispatches every provider call to the platform user-interface thread
 * through the native provider, which owns its own bus or status-bar thread.
 */
public class JvmKWebTraysSession internal constructor(
    private val native: TrayFfm,
    private val iconResolver: KWebTrayIconResolver?,
    private val scope: CoroutineScope,
) : KWebTrays {

    override val descriptor: KWebServiceDescriptor = KWebTrays.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val mutableEvents = MutableSharedFlow<KWebTrayEvent>(
        replay = KWEB_TRAY_EVENT_REPLAY,
        extraBufferCapacity = KWEB_TRAY_EVENT_REPLAY,
    )
    override val events: SharedFlow<KWebTrayEvent> = mutableEvents.asSharedFlow()

    private val poller: Job = scope.launch(Dispatchers.IO) { pollEvents() }
    private val liveItems = java.util.concurrent.ConcurrentHashMap<String, KWebTrayItemId>()
    private val menuVersions = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var capabilitiesCache: KWebTrayCapabilities? = null
    private var closeFailure: KWebException? = null

    override suspend fun capabilities(): KWebTrayCapabilities {
        requireOpen()
        capabilitiesCache?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        val capabilities = KWebTrayCapabilities(
            providerId = result.providerId,
            flags = KWebTrayCapability.entries.filter { (result.flags and (1L shl it.ordinal)) != 0L }.toSet(),
            activations = KWebTrayActivation.entries
                .filter { (result.activationBits and (1L shl it.ordinal)) != 0L }
                .toSet(),
        )
        capabilitiesCache = capabilities
        return capabilities
    }

    override suspend fun create(spec: KWebTrayItemSpec): KWebTrayItemResult {
        requireOpen()
        if (liveItems.containsKey(spec.id.value)) {
            throw failure(KWebTrayErrorCode.ITEM_EXISTS, "create", mapOf("item" to spec.id.value))
        }
        if (liveItems.size >= KWEB_TRAY_MAX_ITEMS) {
            throw failure(KWebTrayErrorCode.ITEM_LIMIT_EXCEEDED, "create", mapOf("item" to spec.id.value))
        }
        val flat = prepare(spec)
        val created = translate("create") { native.setItem(flat) }
        liveItems[spec.id.value] = spec.id
        return KWebTrayItemResult(spec.id, created, 0u)
    }

    override suspend fun update(spec: KWebTrayItemSpec): KWebTrayItemResult {
        requireOpen()
        if (!liveItems.containsKey(spec.id.value)) {
            throw failure(KWebTrayErrorCode.ITEM_UNKNOWN, "update", mapOf("item" to spec.id.value))
        }
        val flat = prepare(spec)
        val created = translate("update") { native.setItem(flat) }
        return KWebTrayItemResult(spec.id, created, 0u)
    }

    override suspend fun setMenu(itemId: KWebTrayItemId, tree: KWebMenuTree?): KWebTrayMenuResult {
        requireOpen()
        if (!liveItems.containsKey(itemId.value)) {
            throw failure(KWebTrayErrorCode.ITEM_UNKNOWN, "set-menu", mapOf("item" to itemId.value))
        }
        if (tree != null && !capabilities().supports(KWebTrayCapability.MENU)) {
            throw failure(
                KWebTrayErrorCode.MENU_UNSUPPORTED,
                "set-menu",
                mapOf("item" to itemId.value, "provider" to native.providerId()),
            )
        }
        val previous = menuVersions[itemId.value]
        if (tree != null && previous != null && tree.version <= previous) {
            throw failure(
                KWebTrayErrorCode.MENU_VERSION_STALE,
                "set-menu",
                mapOf("item" to itemId.value, "version" to tree.version.toString()),
            )
        }
        val flat = tree?.let { flatten(it) }
        translate("set-menu") { native.setMenu(itemId.value, flat) }
        if (tree == null) {
            menuVersions.remove(itemId.value)
            return KWebTrayMenuResult(itemId, KWebMenuId("tray.cleared"), 0L, 0)
        }
        menuVersions[itemId.value] = tree.version
        return KWebTrayMenuResult(itemId, tree.menuId, tree.version, flat?.items?.count { it.kind() != TrayFfm.MENU_SEPARATOR } ?: 0)
    }

    override suspend fun bounds(itemId: KWebTrayItemId): KWebTrayBounds? {
        requireOpen()
        if (!liveItems.containsKey(itemId.value)) {
            throw failure(KWebTrayErrorCode.ITEM_UNKNOWN, "bounds", mapOf("item" to itemId.value))
        }
        val bounds = translate("bounds") { native.bounds(itemId.value) } ?: return null
        return KWebTrayBounds(bounds.x, bounds.y, bounds.width, bounds.height)
    }

    override suspend fun closeItem(itemId: KWebTrayItemId) {
        requireOpen()
        if (!liveItems.containsKey(itemId.value)) {
            throw failure(KWebTrayErrorCode.ITEM_UNKNOWN, "close-item", mapOf("item" to itemId.value))
        }
        translate("close-item") { native.closeItem(itemId.value) }
        liveItems.remove(itemId.value)
        menuVersions.remove(itemId.value)
    }

    override fun close() {
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        poller.cancel()
        var failure: KWebException? = null
        try {
            runBlocking { translate("close") { native.close() } }
        } catch (error: KWebException) {
            failure = error
        } catch (error: Throwable) {
            failure = KWebServiceException(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The tray provider failed while closing.",
                cause = error,
            )
        }
        scope.cancel()
        liveItems.clear()
        menuVersions.clear()
        synchronized(this) {
            closeFailure = failure
            mutableLifecycle.value = if (failure == null) KWebLifecycleState.CLOSED else KWebLifecycleState.FAILED
        }
        closeFailure?.let { throw it }
    }

    /** Validates one spec against the provider capabilities and flattens it. */
    private suspend fun prepare(spec: KWebTrayItemSpec): TrayFfm.FlatItem {
        val capabilities = capabilities()
        if (spec.tooltip != null && !capabilities.supports(KWebTrayCapability.TOOLTIP)) {
            throw failure(
                KWebTrayErrorCode.TOOLTIP_UNSUPPORTED,
                "create",
                mapOf("item" to spec.id.value, "provider" to capabilities.providerId),
            )
        }
        val unsupported = spec.activations.firstOrNull { !capabilities.supports(it) }
        if (unsupported != null) {
            throw failure(
                KWebTrayErrorCode.ACTIVATION_UNSUPPORTED,
                "create",
                mapOf("item" to spec.id.value, "activation" to unsupported.name),
            )
        }
        if (spec.icon.variants.any { it.template } && !capabilities.supports(KWebTrayCapability.TEMPLATE_ICON)) {
            throw failure(
                KWebTrayErrorCode.ICON_UNSUPPORTED,
                "create",
                mapOf("item" to spec.id.value, "provider" to capabilities.providerId),
            )
        }
        val variants = spec.icon.variants.sortedBy { it.scale }.map { variant ->
            val resolver = iconResolver ?: throw failure(
                KWebTrayErrorCode.ICON_UNSUPPORTED,
                "create",
                mapOf("item" to spec.id.value, "reason" to "resolver-missing"),
            )
            val pixels = resolver.resolve(variant.resourceId, variant.sha256)
            TrayFfm.FlatVariant(
                variant.scale,
                variant.template,
                variant.resourceId,
                variant.sha256,
                pixels.rgba,
                pixels.width,
                pixels.height,
            )
        }
        return TrayFfm.FlatItem(spec.id.value, spec.tooltip.orEmpty(), activationBits(spec.activations), variants)
    }

    /**
     * The tray menu is the RFC 0017 tree shape without roles, accelerators, or
     * icons; a tree that uses them fails instead of dropping the field.
     */
    private fun flatten(tree: KWebMenuTree): TrayFfm.FlatTree {
        val items = mutableListOf<TrayFfm.FlatMenuItem>()
        fun append(item: KWebMenuItem) {
            when (item) {
                is KWebMenuItem.Separator -> items.add(
                    TrayFfm.FlatMenuItem("", "", TrayFfm.MENU_SEPARATOR, 0, 0),
                )
                is KWebMenuItem.Command -> {
                    if (item.role != null || item.accelerator != null || item.icon != null || item.mnemonicCharacter != null) {
                        throw failure(
                            KWebTrayErrorCode.MENU_INVALID,
                            "set-menu",
                            mapOf("command" to item.commandId.value, "reason" to "unsupported-field"),
                        )
                    }
                    val kind = when (item.toggle) {
                        null -> TrayFfm.MENU_COMMAND
                        io.github.kingsword09.kwebshell.service.menus.KWebMenuToggleKind.CHECKBOX -> TrayFfm.MENU_CHECKBOX
                        io.github.kingsword09.kwebshell.service.menus.KWebMenuToggleKind.RADIO -> TrayFfm.MENU_RADIO
                    }
                    val flags = (if (item.enabled) TrayFfm.MENU_FLAG_ENABLED else 0) or
                        (if (item.visible) TrayFfm.MENU_FLAG_VISIBLE else 0) or
                        (if (item.checked) TrayFfm.MENU_FLAG_CHECKED else 0)
                    items.add(TrayFfm.FlatMenuItem(item.commandId.value, item.label, kind, flags, 0))
                }
                is KWebMenuItem.Submenu -> {
                    if (item.role != null || item.icon != null || item.mnemonicCharacter != null) {
                        throw failure(
                            KWebTrayErrorCode.MENU_INVALID,
                            "set-menu",
                            mapOf("command" to item.commandId.value, "reason" to "unsupported-field"),
                        )
                    }
                    val flags = (if (item.enabled) TrayFfm.MENU_FLAG_ENABLED else 0) or
                        (if (item.visible) TrayFfm.MENU_FLAG_VISIBLE else 0)
                    items.add(TrayFfm.FlatMenuItem(item.commandId.value, item.label, TrayFfm.MENU_SUBMENU, flags, item.items.size))
                    item.items.forEach { child -> append(child) }
                }
            }
        }
        tree.items.forEach { append(it) }
        return TrayFfm.FlatTree(tree.menuId.value, tree.version, items)
    }

    private fun activationBits(activations: Set<KWebTrayActivation>): Int = activations.fold(0) { bits, activation ->
        bits or (1 shl activation.ordinal)
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

    private fun decodeEvent(event: TrayFfm.Event): KWebTrayEvent = when (event.kind) {
        TrayFfm.EVENT_ACTIVATED -> KWebTrayEvent.Activated(
            KWebTrayItemId(event.itemId),
            when (event.activation) {
                TrayFfm.ACTIVATION_SECONDARY -> KWebTrayActivation.SECONDARY
                TrayFfm.ACTIVATION_DOUBLE -> KWebTrayActivation.DOUBLE
                else -> KWebTrayActivation.PRIMARY
            },
            event.sequence.toULong(),
        )
        TrayFfm.EVENT_MENU_COMMAND -> KWebTrayEvent.MenuCommand(
            KWebTrayItemId(event.itemId),
            KWebMenuId(event.menuId),
            KWebMenuId(event.commandId),
            event.treeVersion,
            event.sequence.toULong(),
        )
        TrayFfm.EVENT_BALLOON -> KWebTrayEvent.BalloonAction(KWebTrayItemId(event.itemId), event.sequence.toULong())
        TrayFfm.EVENT_REMOVED -> KWebTrayEvent.Removed(
            KWebTrayItemId(event.itemId),
            when (event.removalReason) {
                TrayFfm.REMOVAL_HOST_LOST -> KWebTrayRemovalReason.HOST_LOST
                TrayFfm.REMOVAL_PLATFORM_REMOVED -> KWebTrayRemovalReason.PLATFORM_REMOVED
                TrayFfm.REMOVAL_OWNER_CLOSED -> KWebTrayRemovalReason.OWNER_CLOSED
                else -> KWebTrayRemovalReason.CLOSED
            },
            event.sequence.toULong(),
        )
        else -> KWebTrayEvent.Failed(
            event.itemId.takeIf { it.isNotEmpty() }?.let { KWebTrayItemId(it) },
            event.code.ifEmpty { KWebTrayErrorCode.NATIVE_FAILED },
            event.sequence.toULong(),
        )
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebTrayErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The tray operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: TrayFfm.TrayNativeException) {
        throw failure(
            when (error.status) {
                3 -> KWebTrayErrorCode.PLATFORM_UNAVAILABLE
                4 -> KWebTrayErrorCode.ITEM_EXISTS
                5 -> KWebTrayErrorCode.ITEM_UNKNOWN
                6 -> KWebTrayErrorCode.ITEM_LIMIT_EXCEEDED
                7 -> KWebTrayErrorCode.ICON_INVALID
                8 -> KWebTrayErrorCode.ICON_UNSUPPORTED
                9 -> KWebTrayErrorCode.TOOLTIP_INVALID
                10 -> KWebTrayErrorCode.TOOLTIP_UNSUPPORTED
                11 -> KWebTrayErrorCode.ACTIVATION_UNSUPPORTED
                12 -> KWebTrayErrorCode.MENU_INVALID
                13 -> KWebTrayErrorCode.MENU_UNSUPPORTED
                14 -> KWebTrayErrorCode.BOUNDS_UNAVAILABLE
                17 -> KWebTrayErrorCode.OWNER_CLOSED
                else -> KWebTrayErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

public object JvmKWebTrays {
    /**
     * Opens the packaged tray provider. There is no fallback provider: a missing
     * native library or an absent platform host fails immediately.
     */
    public fun open(
        applicationId: String,
        nativeLibrary: Path,
        packageIdentity: String = applicationId,
        iconResolver: KWebTrayIconResolver? = null,
    ): JvmKWebTraysSession = JvmKWebTraysSession(
        TrayFfm.open(nativeLibrary, applicationId, packageIdentity),
        iconResolver,
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )
}
