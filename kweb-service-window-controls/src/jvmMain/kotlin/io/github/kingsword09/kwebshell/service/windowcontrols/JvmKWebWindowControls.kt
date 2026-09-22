package io.github.kingsword09.kwebshell.service.windowcontrols

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.GraphicsConfiguration
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong
import javax.swing.JFrame
import javax.swing.WindowConstants

public object JvmKWebWindowControls {
    public fun open(window: ComposeWindow, registration: KWebWindowRegistration): KWebWindowControls =
        ComposeKWebWindowControls.open(window, registration)
}

private object WindowHierarchyRegistry {
    private const val MAX_HIERARCHY_DEPTH: Int = 64
    private const val MAX_APPLICATION_WINDOWS: Int = 256
    private val lock = Any()
    private val services = linkedMapOf<KWebWindowId, ComposeKWebWindowControls>()
    private val modalDisableCounts = linkedMapOf<ComposeKWebWindowControls, Int>()
    private val modalOwnersByChild = linkedMapOf<ComposeKWebWindowControls, Set<ComposeKWebWindowControls>>()

    fun register(service: ComposeKWebWindowControls) {
        val shouldActivateModal = synchronized(lock) {
            val registration = service.registration
            if (services.size >= MAX_APPLICATION_WINDOWS) {
                throw KWebConfigurationException(
                    code = "window.registration-invalid",
                    details = mapOf("limit" to MAX_APPLICATION_WINDOWS.toString()),
                    message = "The application window registration limit has been reached.",
                )
            }
            if (services.containsKey(registration.id)) {
                throw KWebConfigurationException(
                    code = "window.registration-invalid",
                    details = mapOf("id" to registration.id),
                    message = "A window with this id is already registered.",
                )
            }
            val parent = registration.parentId?.let { services[it] }
                ?: registration.parentId?.let {
                    throw KWebConfigurationException(
                        code = "window.parent-missing",
                        details = mapOf("id" to registration.id, "parentId" to it),
                        message = "A window cannot attach to an unregistered parent.",
                    )
                }
            if (parent != null && parent.registration.id == registration.id) {
                throw KWebConfigurationException(
                    code = "window.parent-cycle",
                    details = mapOf("id" to registration.id),
                    message = "A window cannot be its own parent.",
                )
            }
            val depth = parent?.let { depthOf(it.registration.id) + 1 } ?: 1
            if (depth > MAX_HIERARCHY_DEPTH) {
                throw KWebConfigurationException(
                    code = "window.registration-invalid",
                    details = mapOf("depth" to depth.toString(), "limit" to MAX_HIERARCHY_DEPTH.toString()),
                    message = "The window hierarchy exceeds the published depth limit.",
                )
            }
            services[registration.id] = service
            registration.modality != KWebWindowModality.NONE && service.isVisibleForHierarchy()
        }
        if (shouldActivateModal) activateModal(service)
    }

    fun unregister(service: ComposeKWebWindowControls) {
        val descendants = synchronized(lock) {
            if (services[service.registration.id] !== service) return
            descendantsLocked(service)
        }
        descendants.forEach { it.forceCloseFromHierarchy() }
        synchronized(lock) {
            if (services[service.registration.id] !== service) return
            deactivateModal(service)
            services.remove(service.registration.id)
        }
    }

    fun visibilityChanged(service: ComposeKWebWindowControls, visible: Boolean) {
        if (visible) activateModal(service) else deactivateModal(service)
    }

    fun descendants(service: ComposeKWebWindowControls): List<ComposeKWebWindowControls> = synchronized(lock) {
        descendantsLocked(service)
    }

    private fun descendantsLocked(service: ComposeKWebWindowControls): List<ComposeKWebWindowControls> {
        val result = mutableListOf<ComposeKWebWindowControls>()
        fun visit(parentId: KWebWindowId) {
            services.values.filter { it.registration.parentId == parentId }.forEach { child ->
                result += child
                visit(child.registration.id)
            }
        }
        visit(service.registration.id)
        return result.asReversed()
    }

    private fun depthOf(id: KWebWindowId): Int {
        var current = services[id]
        var depth = 1
        while (current?.registration?.parentId != null) {
            depth += 1
            current = services[current.registration.parentId]
        }
        return depth
    }

    private fun activateModal(service: ComposeKWebWindowControls) {
        synchronized(lock) {
            if (service.registration.modality == KWebWindowModality.NONE ||
                !service.isVisibleForHierarchy() ||
                services[service.registration.id] !== service ||
                modalOwnersByChild.containsKey(service)
            ) return
            val owners = ownersFor(service).toSet()
            modalOwnersByChild[service] = owners
            owners.forEach { owner ->
                val count = modalDisableCounts.getOrDefault(owner, 0)
                modalDisableCounts[owner] = count + 1
                if (count == 0) owner.setEnabledFromHierarchy(false)
            }
        }
    }

    private fun deactivateModal(service: ComposeKWebWindowControls) {
        val owners = synchronized(lock) { modalOwnersByChild.remove(service).orEmpty() }
        owners.forEach { owner ->
            synchronized(lock) {
                val count = modalDisableCounts.getOrDefault(owner, 0)
                if (count <= 1) {
                    modalDisableCounts.remove(owner)
                    owner.setEnabledFromHierarchy(true)
                } else {
                    modalDisableCounts[owner] = count - 1
                }
            }
        }
    }

    private fun ownersFor(service: ComposeKWebWindowControls): List<ComposeKWebWindowControls> {
        val registration = service.registration
        return when (registration.modality) {
            KWebWindowModality.NONE -> emptyList()
            KWebWindowModality.WINDOW_MODAL -> listOfNotNull(registration.parentId?.let(services::get))
            KWebWindowModality.APPLICATION_MODAL -> services.values.filter { it !== service }
        }
    }
}

internal class ComposeKWebWindowControls private constructor(
    private val window: ComposeWindow,
    override val registration: KWebWindowRegistration,
    initialState: KWebWindowState,
) : KWebWindowControls {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val mutableState = MutableStateFlow(initialState)
    private val mutableEvents = MutableSharedFlow<KWebWindowEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = EVENT_REPLAY,
    )
    private val mutableCloseRequests = MutableSharedFlow<KWebWindowCloseRequest>(replay = 1, extraBufferCapacity = 1)
    private val nextSequence = AtomicLong(1L)
    private var listenersInstalled = false
    private var placementBeforeMinimize = initialState.placement
    private var restoredBounds: KWebWindowBounds? = initialState.restoredBounds
    private var restoredConstraints: KWebWindowConstraints = initialState.constraints
    private var restoredPlacement: KWebWindowPlacement = initialState.placement
    private var capabilitiesBeforeKiosk: CapabilitySnapshot? = null
    private var fullscreenMode: KWebWindowFullscreenMode = initialState.fullscreen
    private var pendingClose: PendingClose? = null
    private var closeTimeoutJob: Job? = null
    private var closingFromService = false
    private var internalMove = false
    private var internalStateChange = false
    private var movable = initialState.movable
    private var minimizable = initialState.minimizable
    private var maximizable = initialState.maximizable
    private var closable = initialState.closable
    private var attention = initialState.attention

    override val descriptor = KWebWindowControls.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
    override val state: StateFlow<KWebWindowState> = mutableState.asStateFlow()
    override val events: Flow<KWebWindowEvent> = mutableEvents.asSharedFlow()
    override val closeRequests: Flow<KWebWindowCloseRequest> = mutableCloseRequests.asSharedFlow()

    private val componentListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = publishCurrentState()
        override fun componentMoved(event: ComponentEvent) {
            if (!internalMove && !movable && mutableLifecycle.value == KWebLifecycleState.OPEN) {
                val target = mutableState.value.bounds
                internalMove = true
                try {
                    window.setLocation(target.x, target.y)
                } finally {
                    internalMove = false
                }
            }
            publishCurrentState()
        }

        override fun componentShown(event: ComponentEvent) = publishCurrentState()
        override fun componentHidden(event: ComponentEvent) = publishCurrentState()
    }
    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) = publishCurrentState()
        override fun windowLostFocus(event: WindowEvent) = publishCurrentState()
    }
    private val stateListener = WindowStateListener {
        if (!internalStateChange && mutableLifecycle.value == KWebLifecycleState.OPEN) {
            if (!minimizable && window.isMinimized) {
                internalStateChange = true
                try { window.isMinimized = false } finally { internalStateChange = false }
            }
            if (!maximizable && window.placement == WindowPlacement.Maximized) {
                internalStateChange = true
                try { window.placement = WindowPlacement.Floating } finally { internalStateChange = false }
            }
        }
        publishCurrentState()
    }
    private val closeListener = object : WindowAdapter() {
        override fun windowClosing(event: WindowEvent) {
            if (closingFromService) return
            if (!closable || mutableState.value.fullscreen == KWebWindowFullscreenMode.KIOSK) return
            requestCloseInternal(KWebWindowCloseSource.USER)
        }

        override fun windowClosed(event: WindowEvent) {
            closeFromWindow()
        }
    }

    override suspend fun snapshot(): KWebWindowState = operation("get-state") { readState() }

    override suspend fun setTitle(title: String): KWebWindowState {
        if (title.length > MAXIMUM_TITLE_LENGTH || title.any { it == '\u0000' }) {
            throw invalidRequest("set-title", "The window title is too long or contains a NUL character.")
        }
        return operation("set-title") { window.title = title; readAndPublish() }
    }

    override suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState = operation("set-bounds") {
        if (mutableState.value.placement != KWebWindowPlacement.FLOATING ||
            fullscreenMode != KWebWindowFullscreenMode.WINDOWED
        ) {
            throw unavailable("set-bounds", "Window bounds require a restored windowed state.")
        }
        internalMove = true
        try {
            window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        } finally {
            internalMove = false
        }
        readAndPublish()
    }

    override suspend fun setConstraints(constraints: KWebWindowConstraints): KWebWindowState = operation("set-constraints") {
        window.minimumSize = Dimension(constraints.minimumWidth, constraints.minimumHeight)
        window.maximumSize = Dimension(constraints.maximumWidth ?: Int.MAX_VALUE, constraints.maximumHeight ?: Int.MAX_VALUE)
        readAndPublish()
    }

    override suspend fun setVisible(visible: Boolean): KWebWindowState = operation("set-visible") {
        window.isVisible = visible
        WindowHierarchyRegistry.visibilityChanged(this@ComposeKWebWindowControls, visible)
        readAndPublish()
    }

    override suspend fun focus(): KWebWindowState = operation("focus") {
        if (!mutableState.value.visible || !window.isVisible || !window.isShowing) {
            throw unavailable("focus", "A hidden window cannot receive focus.")
        }
        window.toFront()
        window.requestFocus()
        readAndPublish()
    }

    override suspend fun minimize(): KWebWindowState = mutateAndAwait("minimize", { it.placement == KWebWindowPlacement.MINIMIZED }) {
        if (!minimizable) throw unavailable("minimize", "Minimization is disabled for this window.")
        if (window.placement != WindowPlacement.Fullscreen) placementBeforeMinimize = currentPlacement()
        window.isMinimized = true
    }

    override suspend fun restore(): KWebWindowState {
        val before = snapshot()
        if (before.placement == KWebWindowPlacement.FLOATING && before.fullscreen == KWebWindowFullscreenMode.WINDOWED) return before
        return mutateAndAwait("restore", {
            it.placement == KWebWindowPlacement.FLOATING && it.fullscreen == KWebWindowFullscreenMode.WINDOWED
        }) {
            if (before.fullscreen != KWebWindowFullscreenMode.WINDOWED) exitFullscreenInternal()
            window.isMinimized = false
            window.placement = placementBeforeMinimize.toComposePlacement()
        }
    }

    override suspend fun setMaximized(maximized: Boolean): KWebWindowState = mutateAndAwait(
        "set-maximized",
        { it.placement == if (maximized) KWebWindowPlacement.MAXIMIZED else KWebWindowPlacement.FLOATING },
    ) {
        if (!maximizable && maximized) throw unavailable("set-maximized", "Maximization is disabled for this window.")
        if (fullscreenMode != KWebWindowFullscreenMode.WINDOWED) {
            throw unavailable("set-maximized", "Maximization is unavailable while fullscreen.")
        }
        internalStateChange = true
        try { window.placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating }
        finally { internalStateChange = false }
        placementBeforeMinimize = if (maximized) KWebWindowPlacement.MAXIMIZED else KWebWindowPlacement.FLOATING
    }

    override suspend fun setFullscreen(mode: KWebWindowFullscreenMode): KWebWindowState = mutateAndAwait(
        "set-fullscreen",
        { it.fullscreen == mode },
    ) {
        if (fullscreenMode == KWebWindowFullscreenMode.KIOSK && mode != KWebWindowFullscreenMode.WINDOWED) {
            throw unavailable("set-fullscreen", "Kiosk mode must be exited before another fullscreen mode can be selected.")
        }
        if (mode == KWebWindowFullscreenMode.KIOSK && registration.parentId != null) {
            throw unavailable("set-fullscreen", "A child window cannot enter kiosk mode.")
        }
        if (mode == KWebWindowFullscreenMode.WINDOWED) exitFullscreenInternal()
        else enterFullscreenInternal(mode)
    }

    override suspend fun setMovable(movable: Boolean): KWebWindowState = operation("set-movable") {
        this@ComposeKWebWindowControls.movable = movable
        readAndPublish()
    }

    override suspend fun setMinimizable(minimizable: Boolean): KWebWindowState = operation("set-minimizable") {
        this@ComposeKWebWindowControls.minimizable = minimizable
        if (!minimizable && window.isMinimized) window.isMinimized = false
        readAndPublish()
    }

    override suspend fun setMaximizable(maximizable: Boolean): KWebWindowState = operation("set-maximizable") {
        this@ComposeKWebWindowControls.maximizable = maximizable
        if (!maximizable && window.placement == WindowPlacement.Maximized) window.placement = WindowPlacement.Floating
        readAndPublish()
    }

    override suspend fun setClosable(closable: Boolean): KWebWindowState = operation("set-closable") {
        this@ComposeKWebWindowControls.closable = closable
        readAndPublish()
    }

    override suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState = operation("set-always-on-top") {
        if (alwaysOnTop && !window.isAlwaysOnTopSupported) throw unavailable(
            "set-always-on-top",
            "The current desktop cannot keep this window always on top.",
        )
        window.isAlwaysOnTop = alwaysOnTop
        readAndPublish()
    }

    override suspend fun setResizable(resizable: Boolean): KWebWindowState = operation("set-resizable") {
        window.isResizable = resizable
        readAndPublish()
    }

    override suspend fun requestAttention(): KWebWindowState = operation("request-attention") {
        attention = KWebWindowAttention.REQUESTED
        window.toFront()
        readAndPublish()
    }

    override suspend fun clearAttention(): KWebWindowState = operation("clear-attention") {
        attention = KWebWindowAttention.NONE
        readAndPublish()
    }

    override suspend fun requestClose(): KWebWindowCloseResult = withContext(Dispatchers.IO) {
        onAwtThread { requestCloseInternal(KWebWindowCloseSource.APPLICATION) }
    }

    internal suspend fun requestRendererClose(): KWebWindowCloseResult = withContext(Dispatchers.IO) {
        onAwtThread { requestCloseInternal(KWebWindowCloseSource.RENDERER) }
    }

    override suspend fun respondToClose(
        requestId: Long,
        decision: KWebWindowCloseDecision,
    ): KWebWindowCloseResult = withContext(Dispatchers.IO) {
        onAwtThread { resolveClose(requestId, decision) }
    }

    override suspend fun forceClose(reason: KWebWindowForceCloseReason): KWebWindowCloseResult = withContext(Dispatchers.IO) {
        onAwtThread { forceCloseInternal(reason) }
    }

    override fun close() {
        onAwtThread {
            synchronized(lock) {
                if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return@onAwtThread
                closeTimeoutJob?.cancel()
                removeListeners()
                WindowHierarchyRegistry.unregister(this)
                mutableLifecycle.value = KWebLifecycleState.CLOSED
                scope.cancel()
            }
        }
    }

    internal fun setEnabledFromHierarchy(enabled: Boolean) {
        onAwtThread { if (window.isDisplayable) window.isEnabled = enabled }
    }

    internal fun isVisibleForHierarchy(): Boolean = window.isDisplayable && window.isVisible

    internal fun forceCloseFromHierarchy() {
        check(EventQueue.isDispatchThread())
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            if (!window.isDisplayable) {
                mutableLifecycle.value = KWebLifecycleState.FAILED
                throw nativeFailure(
                    "force-close",
                    "A registered child window was disposed before its parent.",
                    null,
                )
            }
            forceCloseInternal(KWebWindowForceCloseReason.PARENT_CLOSED)
        }
    }

    private suspend fun operation(name: String, action: () -> KWebWindowState): KWebWindowState = withContext(Dispatchers.IO) {
        onAwtThread {
            synchronized(lock) {
                requireOpen(name)
                requireWindow(name)
                try { action() } catch (error: KWebConfigurationException) { throw error }
                catch (error: KWebNativeException) { throw error }
                catch (error: Throwable) { throw nativeFailure(name, "The Compose window control operation failed.", error) }
            }
        }
    }

    private suspend fun mutateAndAwait(
        name: String,
        expected: (KWebWindowState) -> Boolean,
        mutation: () -> Unit,
    ): KWebWindowState = withContext(Dispatchers.IO) {
        onAwtThread { synchronized(lock) { requireOpen(name); requireWindow(name); mutation() } }
        val deadline = System.nanoTime() + TRANSITION_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val current = onAwtThread { synchronized(lock) { requireOpen(name); requireWindow(name); readState() } }
            if (expected(current)) {
                onAwtThread { synchronized(lock) { publish(current) } }
                return@withContext current
            }
            delay(TRANSITION_POLL_MILLIS)
        }
        throw nativeFailure(name, "The Compose window did not reach the requested state in time.", null)
    }

    private fun requestCloseInternal(source: KWebWindowCloseSource): KWebWindowCloseResult {
        synchronized(lock) {
            requireOpen("request-close")
            requireWindow("request-close")
            if (!closable || fullscreenMode == KWebWindowFullscreenMode.KIOSK) {
                return KWebWindowCloseResult(0L, KWebWindowCloseOutcome.DENIED, readState())
            }
            pendingClose?.let { return KWebWindowCloseResult(it.request.requestId, KWebWindowCloseOutcome.PENDING, readState()) }
            val request = KWebWindowCloseRequest(nextCloseId++, source, CLOSE_DEADLINE_MILLIS)
            pendingClose = PendingClose(request)
            mutableCloseRequests.tryEmit(request)
            closeTimeoutJob?.cancel()
            closeTimeoutJob = scope.launch {
                delay(CLOSE_DEADLINE_MILLIS)
                onAwtThread {
                    synchronized(lock) {
                        if (pendingClose?.request?.requestId == request.requestId) finalizeClose(request, KWebWindowCloseOutcome.TIMED_OUT)
                    }
                }
            }
            return KWebWindowCloseResult(request.requestId, KWebWindowCloseOutcome.PENDING, readState())
        }
    }

    private fun resolveClose(requestId: Long, decision: KWebWindowCloseDecision): KWebWindowCloseResult {
        synchronized(lock) {
            val pending = pendingClose
                ?: throw nativeFailure("respond-close", "The close request has already been resolved.", null, "service.close-request-resolved")
            if (pending.request.requestId != requestId) {
                throw nativeFailure("respond-close", "The close request id is not current.", null, "service.close-request-resolved")
            }
            if (decision == KWebWindowCloseDecision.DENY) {
                pendingClose = null
                closeTimeoutJob?.cancel()
                return KWebWindowCloseResult(requestId, KWebWindowCloseOutcome.DENIED, readAndPublish())
            }
            return finalizeClose(pending.request, KWebWindowCloseOutcome.ALLOWED)
        }
    }

    private fun forceCloseInternal(reason: KWebWindowForceCloseReason): KWebWindowCloseResult {
        synchronized(lock) {
            requireOpen("force-close")
            requireWindow("force-close")
            val request = pendingClose?.request ?: KWebWindowCloseRequest(nextCloseId++, KWebWindowCloseSource.APPLICATION, 0L)
            return finalizeClose(request, KWebWindowCloseOutcome.FORCED)
        }
    }

    private fun finalizeClose(request: KWebWindowCloseRequest, outcome: KWebWindowCloseOutcome): KWebWindowCloseResult {
        closeTimeoutJob?.cancel()
        pendingClose = null
        val terminalState = readState().copy(visible = false, focused = false)
        mutableLifecycle.value = KWebLifecycleState.CLOSING
        closingFromService = true
        window.defaultCloseOperation = JFrame.DISPOSE_ON_CLOSE
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_CLOSING))
        if (window.isDisplayable) window.dispose()
        closingFromService = false
        if (mutableLifecycle.value != KWebLifecycleState.CLOSED && !window.isDisplayable) closeFromWindow()
        return KWebWindowCloseResult(request.requestId, outcome, terminalState)
    }

    private fun enterFullscreenInternal(mode: KWebWindowFullscreenMode) {
        if (fullscreenMode == KWebWindowFullscreenMode.WINDOWED) {
            restoredBounds = mutableState.value.bounds
            restoredConstraints = mutableState.value.constraints
            restoredPlacement = mutableState.value.placement
            capabilitiesBeforeKiosk = CapabilitySnapshot(
                movable = movable,
                minimizable = minimizable,
                maximizable = maximizable,
                closable = closable,
                resizable = window.isResizable,
                alwaysOnTop = window.isAlwaysOnTop,
            )
        }
        if (mode == KWebWindowFullscreenMode.KIOSK) {
            movable = false
            minimizable = false
            maximizable = false
            closable = false
            window.isResizable = false
            if (!window.isAlwaysOnTopSupported) {
                throw unavailable("set-fullscreen", "The current desktop cannot enforce kiosk always-on-top state.")
            }
            window.isAlwaysOnTop = true
        }
        internalStateChange = true
        try {
            fullscreenMode = mode
            window.placement = WindowPlacement.Fullscreen
        } finally { internalStateChange = false }
    }

    private fun exitFullscreenInternal() {
        internalStateChange = true
        try {
            fullscreenMode = KWebWindowFullscreenMode.WINDOWED
            window.placement = restoredPlacement.toComposePlacement()
            restoredBounds?.let { bounds ->
                internalMove = true
                try { window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height) }
                finally { internalMove = false }
            }
            window.minimumSize = Dimension(restoredConstraints.minimumWidth, restoredConstraints.minimumHeight)
            window.maximumSize = Dimension(restoredConstraints.maximumWidth ?: Int.MAX_VALUE, restoredConstraints.maximumHeight ?: Int.MAX_VALUE)
            capabilitiesBeforeKiosk?.let { capabilities ->
                movable = capabilities.movable
                minimizable = capabilities.minimizable
                maximizable = capabilities.maximizable
                closable = capabilities.closable
                window.isResizable = capabilities.resizable
                window.isAlwaysOnTop = capabilities.alwaysOnTop
            }
            capabilitiesBeforeKiosk = null
        } finally {
            internalStateChange = false
        }
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) throw nativeFailure(operation, "The window controls service is closed.", null, KWebServiceErrorCode.OWNER_CLOSED)
    }

    private fun requireWindow(operation: String) {
        if (!window.isDisplayable) throw nativeFailure(operation, "The ComposeWindow owner has been disposed.", null, KWebServiceErrorCode.OWNER_CLOSED)
    }

    private fun readAndPublish(): KWebWindowState = readState().also(::publish)

    private fun readState(): KWebWindowState {
        val configuration: GraphicsConfiguration? = window.graphicsConfiguration
        val displayId = configuration?.device?.getIDstring()
        val scale = configuration?.defaultTransform?.scaleX
        return KWebWindowState(
            id = registration.id,
            parentId = registration.parentId,
            modality = registration.modality,
            title = window.title.orEmpty(),
            bounds = KWebWindowBounds(window.x, window.y, window.width.coerceAtLeast(1), window.height.coerceAtLeast(1)),
            restoredBounds = restoredBounds,
            placement = currentPlacement(),
            fullscreen = fullscreenMode,
            visible = window.isVisible,
            focused = window.isFocused,
            movable = movable,
            minimizable = minimizable,
            maximizable = maximizable,
            closable = closable,
            resizable = window.isResizable,
            alwaysOnTop = window.isAlwaysOnTop,
            constraints = KWebWindowConstraints(
                minimumWidth = window.minimumSize.width.coerceIn(1, MAXIMUM_DIMENSION),
                minimumHeight = window.minimumSize.height.coerceIn(1, MAXIMUM_DIMENSION),
                maximumWidth = window.maximumSize.width.takeUnless { it >= Int.MAX_VALUE }?.coerceIn(1, MAXIMUM_DIMENSION),
                maximumHeight = window.maximumSize.height.takeUnless { it >= Int.MAX_VALUE }?.coerceIn(1, MAXIMUM_DIMENSION),
            ),
            attention = attention,
            displayId = displayId,
            displayScale = scale,
        )
    }

    private fun publishCurrentState() {
        if (!EventQueue.isDispatchThread()) return
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.OPEN && window.isDisplayable) publish(readState())
        }
    }

    private fun publish(value: KWebWindowState) {
        if (mutableState.value == value) return
        mutableState.value = value
        if (!mutableEvents.tryEmit(KWebWindowEvent(nextSequence.getAndIncrement(), value))) {
            throw nativeFailure("publish-event", "The window state event buffer is exhausted.", null)
        }
    }

    private fun installListeners() {
        check(EventQueue.isDispatchThread())
        window.addComponentListener(componentListener)
        window.addWindowFocusListener(focusListener)
        window.addWindowStateListener(stateListener)
        window.addWindowListener(closeListener)
        listenersInstalled = true
    }

    private fun removeListeners() {
        check(EventQueue.isDispatchThread())
        if (!listenersInstalled) return
        window.removeComponentListener(componentListener)
        window.removeWindowFocusListener(focusListener)
        window.removeWindowStateListener(stateListener)
        window.removeWindowListener(closeListener)
        listenersInstalled = false
    }

    private fun closeFromWindow() {
        check(EventQueue.isDispatchThread())
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            closeTimeoutJob?.cancel()
            pendingClose = null
            removeListeners()
            WindowHierarchyRegistry.unregister(this)
            mutableLifecycle.value = KWebLifecycleState.CLOSED
            scope.cancel()
        }
    }

    private fun currentPlacement(): KWebWindowPlacement = when {
        window.isMinimized -> KWebWindowPlacement.MINIMIZED
        window.placement == WindowPlacement.Maximized -> KWebWindowPlacement.MAXIMIZED
        else -> KWebWindowPlacement.FLOATING
    }

    private fun unavailable(operation: String, message: String): Nothing =
        throw nativeFailure(operation, message, null, KWebServiceErrorCode.OPERATION_UNAVAILABLE)

    private fun invalidRequest(operation: String, message: String): Nothing =
        throw KWebConfigurationException(
            code = KWebServiceErrorCode.REQUEST_INVALID,
            details = mapOf("service" to descriptor.id, "operation" to operation),
            message = message,
        )

    private fun nativeFailure(
        operation: String,
        message: String,
        cause: Throwable?,
        code: String = KWebServiceErrorCode.NATIVE_FAILED,
    ): KWebNativeException = KWebNativeException(
        code = code,
        details = mapOf("service" to descriptor.id, "operation" to operation, "windowId" to registration.id),
        message = message,
        cause = cause,
    )

    private data class PendingClose(val request: KWebWindowCloseRequest)

    private data class CapabilitySnapshot(
        val movable: Boolean,
        val minimizable: Boolean,
        val maximizable: Boolean,
        val closable: Boolean,
        val resizable: Boolean,
        val alwaysOnTop: Boolean,
    )

    companion object {
        const val EVENT_REPLAY: Int = 64
        const val MAXIMUM_TITLE_LENGTH: Int = 4096
        const val TRANSITION_POLL_MILLIS: Long = 25L
        const val TRANSITION_TIMEOUT_NANOS: Long = 10_000_000_000L
        const val CLOSE_DEADLINE_MILLIS: Long = 5_000L
        var nextCloseId: Long = 1L

        fun open(window: ComposeWindow, registration: KWebWindowRegistration): ComposeKWebWindowControls = onAwtThreadStatic {
            if (!window.isDisplayable || window.width <= 0 || window.height <= 0) {
                throw KWebConfigurationException(
                    code = "window.owner.not-displayable",
                    details = mapOf("id" to registration.id),
                    message = "RFC 0007 requires a displayable caller-owned ComposeWindow with positive bounds.",
                )
            }
            val operatingSystem = System.getProperty("os.name").lowercase()
            if (operatingSystem.contains("linux") &&
                !System.getenv("WAYLAND_DISPLAY").isNullOrBlank()
            ) {
                throw KWebNativeException(
                    code = "service.platform-unavailable",
                    details = mapOf("platform" to "linux-x11", "session" to "wayland"),
                    message = "RFC 0007 requires an X11 session; Wayland is not silently substituted.",
                )
            }
            if (window.windowHandle == 0L) {
                throw KWebNativeException(
                    code = "window.native-state-unobserved",
                    details = mapOf("windowId" to registration.id),
                    message = "The caller-owned ComposeWindow has no observable native top-level handle.",
                )
            }
            if (window.defaultCloseOperation != JFrame.DO_NOTHING_ON_CLOSE) {
                window.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            }
            val initial = KWebWindowState(
                id = registration.id,
                parentId = registration.parentId,
                modality = registration.modality,
                title = window.title.orEmpty(),
                bounds = KWebWindowBounds(window.x, window.y, window.width, window.height),
                restoredBounds = null,
                placement = when {
                    window.isMinimized -> KWebWindowPlacement.MINIMIZED
                    window.placement == WindowPlacement.Maximized -> KWebWindowPlacement.MAXIMIZED
                    else -> KWebWindowPlacement.FLOATING
                },
                fullscreen = KWebWindowFullscreenMode.WINDOWED,
                visible = window.isVisible,
                focused = window.isFocused,
                movable = true,
                minimizable = true,
                maximizable = true,
                closable = true,
                resizable = window.isResizable,
                alwaysOnTop = window.isAlwaysOnTop,
                constraints = registration.initialConstraints,
                attention = KWebWindowAttention.NONE,
                displayId = window.graphicsConfiguration?.device?.getIDstring(),
                displayScale = window.graphicsConfiguration?.defaultTransform?.scaleX,
            )
            window.minimumSize = Dimension(registration.initialConstraints.minimumWidth, registration.initialConstraints.minimumHeight)
            window.maximumSize = Dimension(
                registration.initialConstraints.maximumWidth ?: Int.MAX_VALUE,
                registration.initialConstraints.maximumHeight ?: Int.MAX_VALUE,
            )
            val service = ComposeKWebWindowControls(window, registration, initial)
            WindowHierarchyRegistry.register(service)
            service.installListeners()
            service
        }

        private fun <T> onAwtThreadStatic(action: () -> T): T {
            if (EventQueue.isDispatchThread()) return action()
            val task = FutureTask(action)
            try {
                EventQueue.invokeAndWait(task)
                return task.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KWebNativeException(
                    code = KWebServiceErrorCode.CANCELLED,
                    details = mapOf("service" to KWebWindowControls.DESCRIPTOR.id, "operation" to "open"),
                    message = "Opening KWebWindowControls was interrupted.",
                    cause = error,
                )
            } catch (error: ExecutionException) {
                throw error.cause ?: error
            }
        }
    }

    private fun <T> onAwtThread(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask(action)
        try {
            EventQueue.invokeAndWait(task)
            return task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebNativeException(
                code = KWebServiceErrorCode.CANCELLED,
                details = mapOf("service" to KWebWindowControls.DESCRIPTOR.id),
                message = "The window control operation was interrupted.",
                cause = error,
            )
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }
}

private fun KWebWindowPlacement.toComposePlacement(): WindowPlacement = when (this) {
    KWebWindowPlacement.FLOATING -> WindowPlacement.Floating
    KWebWindowPlacement.MAXIMIZED -> WindowPlacement.Maximized
    KWebWindowPlacement.MINIMIZED -> WindowPlacement.Floating
}
