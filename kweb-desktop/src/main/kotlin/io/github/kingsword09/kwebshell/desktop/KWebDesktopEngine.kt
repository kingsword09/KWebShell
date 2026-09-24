package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureIssuer
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventFlag
import io.github.kingsword09.kwebshell.core.KWebPageEventReason
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebPageFrameScope
import io.github.kingsword09.kwebshell.core.KWebPageHost
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadDecision
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadRequest
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadResult
import io.github.kingsword09.kwebshell.core.KWebPopupDecision
import io.github.kingsword09.kwebshell.core.KWebPopupFeatures
import io.github.kingsword09.kwebshell.core.KWebPopupOutcome
import io.github.kingsword09.kwebshell.core.KWebPopupRequest
import io.github.kingsword09.kwebshell.core.KWebPopupResult
import io.github.kingsword09.kwebshell.core.KWebReloadMode
import io.github.kingsword09.kwebshell.core.KWebReloadOutcome
import io.github.kingsword09.kwebshell.core.KWebReloadResult
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.services.KWebNativeServiceRegistry
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationShutdownParticipant
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebQuitReason
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebShutdownVote
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowser
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngine
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngineConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

public data class KWebDesktopShutdownStage(
    public val id: String,
    public val elapsedMillis: Long,
)

public data class KWebDesktopShutdownReport(
    public val outcome: String,
    public val stages: List<KWebDesktopShutdownStage>,
)

public class KWebDesktopEngine private constructor(
    private val native: NativeEngine,
    private val configuration: KWebDesktopEngineConfiguration,
) : KWebEngine {
    internal val engineId: String = configuration.engineId

    internal val userGestureIssuer: KWebUserGestureIssuer? = configuration.userGestureIssuer
    private val lock = Any()
    private val profiles = linkedMapOf<Path, KWebDesktopProfile>()
    private val closed = AtomicBoolean(false)
    private var closeFailure: Throwable? = null
    private var shutdownReport: KWebDesktopShutdownReport? = null

    public val nativeServices: KWebNativeServiceRegistry = KWebNativeServiceRegistry()

    public val lastApplicationShutdownReport: KWebDesktopShutdownReport?
        get() = synchronized(lock) { shutdownReport }

    /**
     * Registers the complete desktop owner as one application-lifecycle
     * shutdown participant. The lifecycle service calls this after all
     * application vetoes have been resolved and before releasing its lease.
     */
    public fun applicationShutdownParticipant(): KWebApplicationShutdownParticipant =
        object : KWebApplicationShutdownParticipant {
            override val id: String = "desktop-engine"
            override val order: Int = 100

            override suspend fun requestClose(reason: KWebQuitReason): KWebShutdownVote =
                KWebShutdownVote.ALLOW

            override suspend fun close(reason: KWebQuitReason) {
                this@KWebDesktopEngine.close()
            }
        }

    override val lifecycle: StateFlow<KWebLifecycleState> = native.lifecycle
    override val capabilities: Set<KWebCapability> = buildSet {
        add(KWebCapability.NATIVE_CHILD)
        add(KWebCapability.PERSISTENT_PROFILE)
        add(KWebCapability.NAVIGATION)
        add(KWebCapability.RESIZE)
        add(KWebCapability.DEVTOOLS)
        if (configuration.remoteDebuggingPort != 0) {
            add(KWebCapability.CDP)
        }
    }

    override suspend fun openProfile(name: String): KWebProfile = withContext(Dispatchers.IO) {
        withEngineLock {
            requireEngineOpen("open-profile")
            val path = KWebProfilePathResolver.resolve(native.rootCachePath(), name)
            if (profiles.values.any { it.isSamePhysicalPath(path) }) {
                throw KWebConfigurationException(
                    code = "profile.duplicate-physical-identity",
                    details = mapOf("profile" to path.toString()),
                    message = "The requested Profile is already open through this engine.",
                )
            }
            val profile = KWebDesktopProfile(this@KWebDesktopEngine, path)
            profiles[path] = profile
            profile
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed.get()) {
                closeFailure?.let { throw it }
                if (nativeServices.lifecycle.value == KWebLifecycleState.FAILED) {
                    try {
                        nativeServices.close()
                    } catch (error: Throwable) {
                        closeFailure = error
                        throw error
                    }
                }
                return
            }
            requireEngineOpen("close-engine")
            val startedAt = System.nanoTime()
            val stages = mutableListOf<KWebDesktopShutdownStage>()
            var nativeFailure: Throwable? = null
            try {
                native.close()
                stages += KWebDesktopShutdownStage("engine-native", elapsedMillis(startedAt))
            } catch (error: Throwable) {
                nativeFailure = error
                if (lifecycle.value == KWebLifecycleState.OPEN) {
                    shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                    throw error
                }
            }
            var serviceFailure: Throwable? = null
            try {
                nativeServices.close()
                stages += KWebDesktopShutdownStage("native-services", elapsedMillis(startedAt))
            } catch (error: Throwable) {
                serviceFailure = error
            } finally {
                closed.set(true)
                profiles.values.toList().forEach { it.markClosedByEngine() }
                profiles.clear()
                stages += KWebDesktopShutdownStage("profiles", elapsedMillis(startedAt))
            }
            if (nativeFailure != null) {
                serviceFailure?.let { nativeFailure.addSuppressed(it) }
                closeFailure = nativeFailure
                shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                throw nativeFailure
            }
            serviceFailure?.let {
                closeFailure = it
                shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                throw it
            }
            shutdownReport = KWebDesktopShutdownReport("GRACEFUL", stages.toList())
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    internal fun <T> withEngineLock(block: () -> T): T = synchronized(lock) { block() }

    internal fun nativeEngine(): NativeEngine = native

    internal fun requireEngineOpen(operation: String) {
        if (closed.get() || lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.engine.closed",
                details = mapOf("operation" to operation),
                message = "The KWebShell engine is not open.",
            )
        }
    }

    internal fun removeProfile(profile: KWebDesktopProfile) {
        synchronized(lock) {
            profiles.remove(profile.path, profile)
        }
    }

    internal companion object {
        internal fun open(configuration: KWebDesktopEngineConfiguration): KWebDesktopEngine {
            val nativeConfiguration = NativeEngineConfiguration(
                cefRuntime = configuration.cefRuntime,
                browserSubprocess = configuration.browserSubprocess,
                resources = configuration.resources,
                locales = configuration.locales,
                rootCache = configuration.rootCache,
                log = configuration.log,
                remoteDebuggingPort = configuration.remoteDebuggingPort,
            )
            return KWebDesktopEngine(
                native = NativeEngine.open(nativeConfiguration),
                configuration = configuration,
            )
        }
    }
}

internal class KWebDesktopProfile(
    private val engine: KWebDesktopEngine,
    internal val path: Path,
) : KWebProfile {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val pages = linkedSetOf<KWebDesktopPage>()
    private var closedByEngine = false

    override val name: String = path.fileName.toString()
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun openPage(
        host: KWebPageHost,
        initialUrl: String,
        bounds: KWebRect,
    ): KWebPage = withContext(Dispatchers.IO) {
        engine.withEngineLock {
            synchronized(lock) {
                requireOpen("open-page")
                val composeHost = host as? KWebComposeWindowHost
                    ?: throw KWebConfigurationException(
                        code = "desktop.page.host-unsupported",
                        details = mapOf("host" to host::class.qualifiedName.orEmpty()),
                        message = "The desktop CEF page requires KWebComposeWindowHost.",
                    )
                val nativeParent = validateComposeWindow(composeHost.window)
                val pageId = java.util.UUID.randomUUID().toString()
                val bridgeDispatcher = composeHost.bridgeDispatcherForPage?.create(pageId)
                val streamDispatcher = composeHost.streamDispatcherForPage?.create(pageId)
                if ((bridgeDispatcher == null) != composeHost.bridgeOrigin.isNullOrEmpty()) {
                    throw KWebConfigurationException(
                        code = "desktop.page.bridge-incomplete",
                        details = mapOf("origin" to composeHost.bridgeOrigin.orEmpty()),
                        message = "An exact-origin page requires both a bridge origin and a dispatcher.",
                    )
                }
                val gestureContext = engine.userGestureIssuer?.let { issuer ->
                    KWebPageGestureContext(
                        issuer = issuer,
                        engineId = engine.engineId,
                        profileId = name,
                        pageId = pageId,
                        origin = composeHost.bridgeOrigin.orEmpty(),
                    )
                }
                val eventStream = KWebPageEventStream(pageId, name, gestureContext)
                val nativePage = NativeBrowser.open(
                    engine = engine.nativeEngine(),
                    nativeParent = nativeParent,
                    profilePath = path,
                    initialUrl = initialUrl,
                    x = bounds.x,
                    y = bounds.y,
                    width = bounds.width,
                    height = bounds.height,
                    bridgeOrigin = composeHost.bridgeOrigin.orEmpty(),
                    bridgeDispatcher = bridgeDispatcher,
                    streamDispatcher = streamDispatcher,
                    listener = eventStream::accept,
                )
                val page = KWebDesktopPage(this@KWebDesktopProfile, nativePage, eventStream, pageId)
                pages += page
                page.trackTerminalOwnership()
                page
            }
        }
    }

    override fun close() {
        engine.withEngineLock {
            synchronized(lock) {
                if (mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                    if (pages.isNotEmpty()) {
                        throw KWebNativeException(
                            code = "desktop.profile.live-pages",
                            details = mapOf("profile" to path.toString(), "count" to pages.size.toString()),
                            message = "The Profile cannot close while pages are still live.",
                        )
                    }
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                    engine.removeProfile(this)
                }
            }
        }
    }

    internal fun removePage(page: KWebDesktopPage) {
        synchronized(lock) {
            pages.remove(page)
        }
    }

    internal fun isSamePhysicalPath(other: Path): Boolean =
        try {
            Files.isSameFile(path, other)
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.identity-unavailable",
                details = mapOf("profile" to path.toString(), "requested" to other.toString()),
                message = "The Profile physical identity could not be verified.",
                cause = error,
            )
        }

    internal fun markClosedByEngine() {
        val orphanedPages: List<KWebDesktopPage>
        synchronized(lock) {
            closedByEngine = true
            mutableLifecycle.value = KWebLifecycleState.CLOSED
            orphanedPages = pages.toList()
        }
        // An Engine shutdown is a page-owner close: every page the Profile
        // still holds must have its outstanding gesture tokens invalidated
        // exactly as an explicit Page close would.
        orphanedPages.forEach { it.onEngineClosed() }
    }

    private fun requireOpen(operation: String) {
        if (closedByEngine || mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.profile.closed",
                details = mapOf("operation" to operation, "profile" to path.toString()),
                message = "The KWebShell Profile is not open.",
            )
        }
        engine.requireEngineOpen(operation)
    }

    private fun validateComposeWindow(window: ComposeWindow): Long =
        NativeEngine.onAwtEventDispatchThread {
            if (!window.isDisplayable || !window.isShowing) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-not-visible",
                    details = emptyMap(),
                    message = "ComposeWindow must be displayable and showing before page creation.",
                )
            }
            val handle = try {
                window.windowHandle
            } catch (error: Throwable) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-handle-unavailable",
                    details = emptyMap(),
                    message = "ComposeWindow.windowHandle could not be obtained.",
                    cause = error,
                )
            }
            if (handle == 0L) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-handle-invalid",
                    details = emptyMap(),
                    message = "ComposeWindow.windowHandle returned zero.",
                )
            }
            handle
        }
}

internal class KWebDesktopPage(
    private val owner: KWebDesktopProfile,
    private val native: NativeBrowser,
    private val eventStream: KWebPageEventStream,
    override val id: String,
) : KWebPage {
    private val closeLock = Any()

    internal fun trackTerminalOwnership() {
        eventStream.setPageClosedListener { owner.removePage(this) }
    }

    override val lifecycle: StateFlow<KWebLifecycleState> = native.lifecycle
    override val events: Flow<KWebPageEvent> = eventStream.events
    override val profile: KWebProfile = owner

    override suspend fun navigate(url: String) {
        withContext(Dispatchers.IO) {
            requireOpen("navigate")
            native.navigate(url)
        }
    }

    override suspend fun reload(mode: KWebReloadMode): KWebReloadResult = withContext(Dispatchers.IO) {
        if (native.rendererHasTerminated) {
            throw KWebNativeException(
                code = "page.renderer-terminated",
                details = mapOf("pageId" to id),
                message = "The renderer for this page has terminated.",
            )
        }
        if (lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "page.closed",
                details = mapOf("pageId" to id),
                message = "A closed page cannot be reloaded.",
            )
        }
        val status = native.reload(mode == KWebReloadMode.IGNORE_CACHE)
        when (status) {
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value ->
                KWebReloadResult(mode, KWebReloadOutcome.STARTED)
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_OPERATION_PENDING.value ->
                throw KWebNativeException(
                    code = "page.operation-pending",
                    details = mapOf("operation" to "reload", "pageId" to id),
                    message = "A before-unload decision is still pending for this page.",
                )
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.BROWSER_NOT_READY.value,
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.BROWSER_CLOSING.value ->
                throw KWebNativeException(
                    code = "page.closed",
                    details = mapOf("pageId" to id),
                    message = "The page cannot accept a reload while it is closing.",
                )
            else -> throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-reload",
                status,
                mapOf("pageId" to id),
            )
        }
    }

    override suspend fun respondToBeforeUnload(
        requestId: Long,
        decision: KWebBeforeUnloadDecision,
    ): KWebBeforeUnloadResult = withContext(Dispatchers.IO) {
        val deadlineNanos = eventStream.takeBeforeUnload(requestId)
        if (System.nanoTime() >= deadlineNanos) {
            return@withContext KWebBeforeUnloadResult(
                requestId,
                KWebBeforeUnloadDecision.CANCEL,
                timedOut = true,
            )
        }
        val status = native.respondToBeforeUnload(
            requestId,
            decision == KWebBeforeUnloadDecision.PROCEED,
        )
        when (status) {
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value ->
                KWebBeforeUnloadResult(requestId, decision, timedOut = false)
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_REQUEST_NOT_FOUND.value ->
                if (System.nanoTime() >= deadlineNanos) {
                    KWebBeforeUnloadResult(
                        requestId,
                        KWebBeforeUnloadDecision.CANCEL,
                        timedOut = true,
                    )
                } else {
                    throw KWebNativeException(
                        code = "page.before-unload-stale",
                        details = mapOf("requestId" to requestId.toString()),
                        message = "The before-unload request is stale or belongs to another page.",
                    )
                }
            else -> throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-before-unload-respond",
                status,
                mapOf("requestId" to requestId.toString()),
            )
        }
    }

    override suspend fun respondToPopup(
        requestId: Long,
        decision: KWebPopupDecision,
    ): KWebPopupResult = withContext(Dispatchers.IO) {
        // Resolve the page-bound request first. This makes a stale or forged
        // request fail with the contract error even when the supplied decision
        // also contains an invalid owner or bounds.
        val requestCandidate = eventStream.requirePopup(requestId)
        val allowedHost = when (decision) {
            KWebPopupDecision.DENY -> null
            is KWebPopupDecision.ALLOW -> {
                if (decision.bounds.x > 32768 || decision.bounds.y > 32768 ||
                    decision.bounds.width > 32768 || decision.bounds.height > 32768
                ) {
                    throw KWebConfigurationException(
                        code = "page.popup-bounds-invalid",
                        details = mapOf(
                            "x" to decision.bounds.x.toString(),
                            "y" to decision.bounds.y.toString(),
                            "width" to decision.bounds.width.toString(),
                            "height" to decision.bounds.height.toString(),
                        ),
                        message = "Popup child dimensions must not exceed the native viewport limit.",
                    )
                }
                decision.owner as? KWebComposeWindowHost ?: throw KWebNativeException(
                    code = "page.popup-owner-invalid",
                    details = mapOf("requestId" to requestId.toString()),
                    message = "An allowed popup requires a caller-owned Compose window host.",
                )
            }
        }
        val request = eventStream.takePopup(requestId)
        if (System.nanoTime() >= request.second) {
            return@withContext KWebPopupResult(requestId, KWebPopupOutcome.TIMED_OUT)
        }
        val allow = decision is KWebPopupDecision.ALLOW
        val status = native.respondToPopup(requestId, allow)
        if (status == io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_REQUEST_NOT_FOUND.value) {
            return@withContext if (System.nanoTime() >= request.second) {
                KWebPopupResult(requestId, KWebPopupOutcome.TIMED_OUT)
            } else {
                throw KWebNativeException(
                    code = "page.popup-stale",
                    details = mapOf("requestId" to requestId.toString()),
                    message = "The popup request is stale or belongs to another page.",
                )
            }
        }
        if (status != io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value) {
            throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-popup-respond",
                status,
                mapOf("requestId" to requestId.toString()),
            )
        }
        when (decision) {
            KWebPopupDecision.DENY -> KWebPopupResult(requestId, KWebPopupOutcome.DENIED)
            is KWebPopupDecision.ALLOW -> {
                val page = owner.openPage(requireNotNull(allowedHost), requestCandidate.targetUrl, decision.bounds)
                KWebPopupResult(requestId, KWebPopupOutcome.ALLOWED, page)
            }
        }
    }

    override suspend fun setBounds(bounds: KWebRect) {
        withContext(Dispatchers.IO) {
            requireOpen("set-bounds")
            native.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        }
    }

    override suspend fun setSurfaceState(visible: Boolean, focused: Boolean) {
        withContext(Dispatchers.IO) {
            requireOpen("set-surface-state")
            native.setSurfaceState(visible, focused)
        }
    }

    override suspend fun openDevTools() {
        withContext(Dispatchers.IO) {
            requireOpen("open-devtools")
            native.openDevTools()
        }
    }

    override suspend fun closeDevTools() {
        withContext(Dispatchers.IO) {
            requireOpen("close-devtools")
            native.closeDevTools()
        }
    }

    override fun close() {
        synchronized(closeLock) {
            if (lifecycle.value == KWebLifecycleState.CLOSED) {
                eventStream.onPageClosed()
                return
            }
            try {
                native.close()
            } finally {
                if (lifecycle.value == KWebLifecycleState.CLOSED) {
                    eventStream.onPageClosed()
                }
            }
        }
    }

    internal fun onEngineClosed() {
        eventStream.onPageClosed()
    }

    internal fun requireNativeHandle(operation: String): Long = native.requireLiveHandle(operation)

    private fun requireOpen(operation: String) {
        if (native.rendererHasTerminated) {
            throw KWebNativeException(
                code = "page.renderer-terminated",
                details = mapOf("operation" to operation, "pageId" to id),
                message = "The renderer for this page has terminated.",
            )
        }
        if (lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.page.closed",
                details = mapOf("operation" to operation),
                message = "The KWebShell page is not open.",
            )
        }
    }
}

/**
 * Gesture minting context for one page. The issuer is application-supplied;
 * minting happens only from the native input event path and binding uses the
 * page's exact configured bridge origin.
 */
internal class KWebPageGestureContext(
    private val issuer: KWebUserGestureIssuer,
    private val engineId: String,
    private val profileId: String,
    private val pageId: String,
    private val origin: String,
) {
    fun onInputGesture() {
        if (origin.isEmpty()) return
        issuer.mint(
            KWebGestureBinding(
                engineId = engineId,
                profileId = profileId,
                pageId = pageId,
                origin = origin,
            ),
        )
    }

    fun onNavigationStarted() {
        if (origin.isNotEmpty()) issuer.invalidateNavigation(pageId)
    }

    fun onPageClosed() {
        if (origin.isNotEmpty()) issuer.invalidatePage(pageId)
    }
}

internal class KWebPageEventStream(
    private val pageId: String,
    private val profileId: String,
    private val gestureContext: KWebPageGestureContext? = null,
) {
    private val mutableEvents = MutableSharedFlow<KWebPageEvent>(
        replay = 128,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val events: Flow<KWebPageEvent> = mutableEvents.asSharedFlow()
    private val requestLock = Any()
    private data class PendingPopup(val request: KWebPopupRequest, val deadlineNanos: Long)
    private val pendingPopups = linkedMapOf<Long, PendingPopup>()
    private val pendingBeforeUnload = linkedMapOf<Long, Long>()
    private var nextPublicSequence = 1L
    private var pageClosed = false
    private var pageClosedListener: (() -> Unit)? = null

    internal fun accept(event: NativeBrowserEvent) {
        if (event.type == NativeBrowserEventType.INPUT_GESTURE) {
            gestureContext?.onInputGesture()
            return
        }
        if (event.type == NativeBrowserEventType.NAVIGATION_STARTED) {
            gestureContext?.onNavigationStarted()
        }
        val publicSequence = synchronized(requestLock) {
            val sequence = nextPublicSequence
            nextPublicSequence += 1
            sequence
        }
        val publicEvent = event.toPublicEvent(pageId, profileId, publicSequence)
        synchronized(requestLock) {
            publicEvent.popupRequest?.let {
                pendingPopups[it.requestId] = PendingPopup(
                    it,
                    System.nanoTime() + 5_000_000_000L,
                )
            }
            publicEvent.beforeUnloadRequest?.let {
                pendingBeforeUnload[it.requestId] = System.nanoTime() + 5_000_000_000L
            }
        }
        val emitted = mutableEvents.tryEmit(publicEvent)
        if (event.type == NativeBrowserEventType.CLOSED) {
            onPageClosed()
        }
        if (!emitted) {
            throw KWebNativeException(
                code = "desktop.page.event-backpressure",
                details = mapOf("sequence" to event.sequence.toString()),
                message = "The page event stream has no capacity for the ordered native event.",
            )
        }
    }

    internal fun setPageClosedListener(listener: () -> Unit) {
        val invokeNow = synchronized(requestLock) {
            pageClosedListener = listener
            pageClosed
        }
        if (invokeNow) listener()
    }

    internal fun onPageClosed() {
        gestureContext?.onPageClosed()
        val listener = synchronized(requestLock) {
            pendingPopups.clear()
            pendingBeforeUnload.clear()
            if (pageClosed) null else {
                pageClosed = true
                pageClosedListener
            }
        }
        listener?.invoke()
    }

    internal fun requirePopup(requestId: Long): KWebPopupRequest = synchronized(requestLock) {
        pendingPopups[requestId]?.request
    } ?: throw KWebNativeException(
        code = "page.popup-stale",
        details = mapOf("requestId" to requestId.toString()),
        message = "The popup request is stale or belongs to another page.",
    )

    internal fun takePopup(requestId: Long): Pair<KWebPopupRequest, Long> {
        val pending = synchronized(requestLock) { pendingPopups.remove(requestId) }
            ?: throw KWebNativeException(
                code = "page.popup-stale",
                details = mapOf("requestId" to requestId.toString()),
                message = "The popup request is stale or belongs to another page.",
            )
        return pending.request to pending.deadlineNanos
    }

    internal fun takeBeforeUnload(requestId: Long): Long {
        return synchronized(requestLock) {
            pendingBeforeUnload.remove(requestId)
        } ?: throw KWebNativeException(
            code = "page.before-unload-stale",
            details = mapOf("requestId" to requestId.toString()),
            message = "The before-unload request is stale or belongs to another page.",
        )
    }
}

private fun NativeBrowserEvent.toPublicEvent(
    pageId: String,
    profileId: String,
    publicSequence: Long,
): KWebPageEvent {
    val type = when (type) {
        NativeBrowserEventType.CREATED -> KWebPageEventType.CREATED
        NativeBrowserEventType.NAVIGATION_STARTED -> KWebPageEventType.NAVIGATION_STARTED
        NativeBrowserEventType.NAVIGATION_COMMITTED -> KWebPageEventType.NAVIGATION_COMMITTED
        NativeBrowserEventType.SAME_DOCUMENT_NAVIGATION -> KWebPageEventType.SAME_DOCUMENT_NAVIGATION
        NativeBrowserEventType.ADDRESS_CHANGED -> KWebPageEventType.ADDRESS_CHANGED
        NativeBrowserEventType.LOADING_STATE_CHANGED -> KWebPageEventType.LOADING_STATE_CHANGED
        NativeBrowserEventType.LOAD_ENDED -> KWebPageEventType.LOAD_ENDED
        NativeBrowserEventType.LOAD_FAILED -> KWebPageEventType.LOAD_FAILED
        NativeBrowserEventType.RESIZED -> KWebPageEventType.RESIZED
        NativeBrowserEventType.FATAL_ERROR -> KWebPageEventType.FATAL_ERROR
        NativeBrowserEventType.TITLE_CHANGED -> KWebPageEventType.TITLE_CHANGED
        NativeBrowserEventType.FAVICON_CHANGED -> KWebPageEventType.FAVICON_CHANGED
        NativeBrowserEventType.BEFORE_UNLOAD_REQUESTED -> KWebPageEventType.BEFORE_UNLOAD_REQUESTED
        NativeBrowserEventType.POPUP_REQUESTED -> KWebPageEventType.POPUP_REQUESTED
        NativeBrowserEventType.RENDERER_UNRESPONSIVE -> KWebPageEventType.RENDERER_UNRESPONSIVE
        NativeBrowserEventType.RENDERER_RESPONSIVE -> KWebPageEventType.RENDERER_RESPONSIVE
        NativeBrowserEventType.RENDERER_TERMINATED -> KWebPageEventType.RENDERER_TERMINATED
        NativeBrowserEventType.CLOSED -> KWebPageEventType.CLOSED
        NativeBrowserEventType.DEVTOOLS_OPENED -> KWebPageEventType.DEVTOOLS_OPENED
        NativeBrowserEventType.DEVTOOLS_CLOSED -> KWebPageEventType.DEVTOOLS_CLOSED
        NativeBrowserEventType.DEVTOOLS_FAILED -> KWebPageEventType.DEVTOOLS_FAILED
        NativeBrowserEventType.INPUT_GESTURE ->
            // Filtered by KWebPageEventStream before the public mapping.
            throw IllegalStateException("The internal input-gesture event reached the public mapping.")
    }
    val flags = buildSet {
        if (this@toPublicEvent.flags and 1 != 0) add(KWebPageEventFlag.LOADING)
        if (this@toPublicEvent.flags and 2 != 0) add(KWebPageEventFlag.CAN_GO_BACK)
        if (this@toPublicEvent.flags and 4 != 0) add(KWebPageEventFlag.CAN_GO_FORWARD)
        if (this@toPublicEvent.flags and 8 != 0) add(KWebPageEventFlag.USER_GESTURE)
        if (this@toPublicEvent.flags and 16 != 0) add(KWebPageEventFlag.REDIRECT)
    }
    val eventBounds = if (width > 0 && height > 0) KWebBounds(width, height) else null
    val publicOrigin = origin.takeIf { it.isNotEmpty() }
    val publicUrl = url.ifEmpty {
        if (type in setOf(
                KWebPageEventType.NAVIGATION_STARTED,
                KWebPageEventType.NAVIGATION_COMMITTED,
                KWebPageEventType.SAME_DOCUMENT_NAVIGATION,
                KWebPageEventType.ADDRESS_CHANGED,
                KWebPageEventType.LOAD_ENDED,
                KWebPageEventType.LOAD_FAILED,
            )
        ) text else ""
    }.takeIf { it.isNotEmpty() }
    val publicTitle = if (type == KWebPageEventType.TITLE_CHANGED) {
        boundedPublicText(title.ifEmpty { text }, 4096)
    } else {
        null
    }
    val publicReason = when (reason) {
        1 -> KWebPageEventReason.NAVIGATION_FAILED
        2 -> KWebPageEventReason.NAVIGATION_ABORTED
        3 -> KWebPageEventReason.BEFORE_UNLOAD_TIMEOUT
        4 -> KWebPageEventReason.POPUP_TIMEOUT
        5 -> KWebPageEventReason.RENDERER_CRASH
        6 -> KWebPageEventReason.RENDERER_KILLED
        7 -> KWebPageEventReason.RENDERER_OOM
        8 -> KWebPageEventReason.RENDERER_UNKNOWN
        9 -> KWebPageEventReason.NATIVE_FAILURE
        else -> KWebPageEventReason.NONE
    }
    val popupFeatures = if (type == KWebPageEventType.POPUP_REQUESTED) {
        val values = details.split(',')
        fun valueAt(index: Int): Int? = values.getOrNull(index)?.toIntOrNull()?.takeIf { it >= 0 }
        KWebPopupFeatures(
            x = valueAt(0),
            y = valueAt(1),
            width = valueAt(2),
            height = valueAt(3),
            isPopup = values.getOrNull(4) == "1",
        )
    } else {
        null
    }
    val popup = popupFeatures?.let {
        KWebPopupRequest(
            requestId = requestId,
            pageId = pageId,
            profileId = profileId,
            frameId = frameId,
            origin = publicOrigin,
            targetUrl = publicUrl.orEmpty(),
            frameName = title,
            userGesture = KWebPageEventFlag.USER_GESTURE in flags,
            features = it,
        )
    }
    val beforeUnload = if (type == KWebPageEventType.BEFORE_UNLOAD_REQUESTED) {
        KWebBeforeUnloadRequest(requestId, pageId, frameId, publicOrigin, text.takeIf { it.isNotEmpty() })
    } else {
        null
    }
    val rendererReason = when (reason) {
        5 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.CRASH
        6 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.KILLED
        7 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.OOM
        8 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.UNKNOWN
        else -> null
    }
    return KWebPageEvent(
        type = type,
        sequence = publicSequence,
        statusCode = statusCode,
        bounds = eventBounds,
        flags = flags,
        pageId = pageId,
        profileId = profileId,
        frameId = frameId.ifEmpty { "0" },
        frameScope = if (frameScope == 2) KWebPageFrameScope.SUBFRAME else KWebPageFrameScope.MAIN,
        origin = publicOrigin,
        url = publicUrl,
        title = publicTitle,
        faviconUrls = if (type == KWebPageEventType.FAVICON_CHANGED && details.isNotEmpty()) details.split('\n') else emptyList(),
        reason = publicReason,
        rendererFailureReason = rendererReason,
        beforeUnloadRequest = beforeUnload,
        popupRequest = popup,
    )
}

private fun boundedPublicText(value: String, maximumBytes: Int): String {
    val sanitized = value.replace('\u0000', '\uFFFD')
    if (sanitized.encodeToByteArray().size <= maximumBytes) return sanitized
    var end = sanitized.length
    while (end > 0 && sanitized.substring(0, end).encodeToByteArray().size > maximumBytes) {
        end -= 1
    }
    return sanitized.substring(0, end)
}

internal object KWebProfilePathResolver {
    private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    internal fun resolve(root: Path, name: String): Path {
        if (!NAME_PATTERN.matches(name) || name.equals("default", ignoreCase = true)) {
            throw KWebConfigurationException(
                code = "profile.name.invalid",
                details = mapOf("name" to name),
                message = "Profile names must be one safe directory component and cannot be Default.",
            )
        }
        val realRoot = try {
            root.toAbsolutePath().normalize().toRealPath()
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.root.invalid",
                details = mapOf("root" to root.toString()),
                message = "The engine Profile root cannot be canonicalized.",
                cause = error,
            )
        }
        val candidate = realRoot.resolve(name).normalize()
        if (candidate.parent != realRoot) {
            throw KWebConfigurationException(
                code = "profile.path.invalid",
                details = mapOf("name" to name),
                message = "The Profile must be a direct child of the engine root cache.",
            )
        }
        try {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(candidate)) {
                    throw KWebConfigurationException(
                        code = "profile.path.invalid",
                        details = mapOf("path" to candidate.toString()),
                        message = "The Profile path is not a directory.",
                    )
                }
            } else {
                Files.createDirectory(candidate)
            }
            val canonical = candidate.toRealPath()
            if (canonical.parent != realRoot || !Files.isDirectory(canonical)) {
                throw KWebConfigurationException(
                    code = "profile.path.invalid",
                    details = mapOf("path" to canonical.toString()),
                    message = "The Profile path resolves outside the engine root cache.",
                )
            }
            return canonical
        } catch (error: KWebConfigurationException) {
            throw error
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.path.invalid",
                details = mapOf("path" to candidate.toString()),
                message = "The Profile directory could not be created or canonicalized.",
                cause = error,
            )
        }
    }
}
