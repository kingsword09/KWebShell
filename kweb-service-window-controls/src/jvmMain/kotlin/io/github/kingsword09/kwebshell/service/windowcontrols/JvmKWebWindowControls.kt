package io.github.kingsword09.kwebshell.service.windowcontrols

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicLong

public object JvmKWebWindowControls {
    public fun open(window: ComposeWindow): KWebWindowControls = ComposeKWebWindowControls.open(window)
}

private class ComposeKWebWindowControls private constructor(
    private val window: ComposeWindow,
    initialState: KWebWindowState,
) : KWebWindowControls {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val mutableState = MutableStateFlow(initialState)
    private val mutableEvents = MutableSharedFlow<KWebWindowEvent>(
        replay = EVENT_REPLAY,
        extraBufferCapacity = EVENT_REPLAY,
    )
    private val nextSequence = AtomicLong(1L)
    private var listenersInstalled = false
    private var placementBeforeMinimize: KWebWindowPlacement = initialState.placement

    override val descriptor = KWebWindowControls.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
    override val state: StateFlow<KWebWindowState> = mutableState.asStateFlow()
    override val events: Flow<KWebWindowEvent> = mutableEvents.asSharedFlow()

    private val componentListener = object : ComponentAdapter() {
        override fun componentResized(event: ComponentEvent) = publishCurrentState()
        override fun componentMoved(event: ComponentEvent) = publishCurrentState()
        override fun componentShown(event: ComponentEvent) = publishCurrentState()
        override fun componentHidden(event: ComponentEvent) = publishCurrentState()
    }
    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) = publishCurrentState()
        override fun windowLostFocus(event: WindowEvent) = publishCurrentState()
    }
    private val stateListener = WindowStateListener { publishCurrentState() }
    private val closeListener = object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) {
            closeFromWindow()
        }
    }

    override suspend fun snapshot(): KWebWindowState = operation("get-state") { readState() }

    override suspend fun setTitle(title: String): KWebWindowState {
        if (title.length > MAXIMUM_TITLE_LENGTH || title.any { it == '\u0000' }) {
            throw KWebConfigurationException(
                code = KWebServiceErrorCode.REQUEST_INVALID,
                details = mapOf("service" to descriptor.id, "operation" to "set-title"),
                message = "The window title is too long or contains a NUL character.",
            )
        }
        return operation("set-title") {
            window.title = title
            readAndPublish()
        }
    }

    override suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState = operation("set-bounds") {
        if (window.placement != WindowPlacement.Floating || window.isMinimized) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OPERATION_UNAVAILABLE,
                operation = "set-bounds",
                message = "Window bounds can change only while the window is floating and restored.",
            )
        }
        window.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        readAndPublish()
    }

    override suspend fun setVisible(visible: Boolean): KWebWindowState = operation("set-visible") {
        window.isVisible = visible
        readAndPublish()
    }

    override suspend fun focus(): KWebWindowState = operation("focus") {
        if (!window.isVisible) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OPERATION_UNAVAILABLE,
                operation = "focus",
                message = "A hidden window cannot receive focus.",
            )
        }
        window.toFront()
        window.requestFocus()
        readAndPublish()
    }

    override suspend fun minimize(): KWebWindowState = mutateAndAwait(
        name = "minimize",
        expected = KWebWindowState::minimized,
    ) {
        requireNotFullscreen("minimize")
        if (!window.isMinimized) {
            placementBeforeMinimize = currentPlacement()
        }
        window.isMinimized = true
    }

    override suspend fun restore(): KWebWindowState {
        val before = snapshot()
        if (!before.minimized) return before
        var target = before.placement
        return mutateAndAwait(
            name = "restore",
            expected = { state -> !state.minimized && state.placement == target },
        ) {
            requireNotFullscreen("restore")
            target = placementBeforeMinimize
            window.isMinimized = false
            window.placement = target.toComposePlacement()
        }
    }

    override suspend fun setMaximized(maximized: Boolean): KWebWindowState = mutateAndAwait(
        name = "set-maximized",
        expected = { state ->
            !state.minimized && state.placement == if (maximized) {
                KWebWindowPlacement.MAXIMIZED
            } else {
                KWebWindowPlacement.FLOATING
            }
        },
    ) {
        requireNotFullscreen("set-maximized")
        window.isMinimized = false
        window.placement = if (maximized) WindowPlacement.Maximized else WindowPlacement.Floating
        placementBeforeMinimize = if (maximized) KWebWindowPlacement.MAXIMIZED else KWebWindowPlacement.FLOATING
    }

    override suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState = operation("set-always-on-top") {
        if (alwaysOnTop && !window.isAlwaysOnTopSupported) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OPERATION_UNAVAILABLE,
                operation = "set-always-on-top",
                message = "The current desktop cannot keep this window always on top.",
            )
        }
        window.isAlwaysOnTop = alwaysOnTop
        readAndPublish()
    }

    override suspend fun setResizable(resizable: Boolean): KWebWindowState = operation("set-resizable") {
        window.isResizable = resizable
        readAndPublish()
    }

    override fun close() {
        onAwtThread {
            synchronized(lock) {
                if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return@onAwtThread
                removeListeners()
                mutableLifecycle.value = KWebLifecycleState.CLOSED
            }
        }
    }

    private suspend fun operation(
        name: String,
        action: () -> KWebWindowState,
    ): KWebWindowState = withContext(Dispatchers.IO) {
        onAwtThread {
            synchronized(lock) {
                requireOpen(name)
                requireWindow(name)
                try {
                    action()
                } catch (error: KWebConfigurationException) {
                    throw error
                } catch (error: KWebNativeException) {
                    throw error
                } catch (error: Throwable) {
                    throw serviceFailure(
                        code = KWebServiceErrorCode.NATIVE_FAILED,
                        operation = name,
                        message = "The Compose window control operation failed.",
                        cause = error,
                    )
                }
            }
        }
    }

    private suspend fun mutateAndAwait(
        name: String,
        expected: (KWebWindowState) -> Boolean,
        mutation: () -> Unit,
    ): KWebWindowState = withContext(Dispatchers.IO) {
        onAwtThread {
            synchronized(lock) {
                requireOpen(name)
                requireWindow(name)
                mutation()
            }
        }
        val deadline = System.nanoTime() + STATE_TRANSITION_TIMEOUT_NANOS
        while (System.nanoTime() < deadline) {
            val current = onAwtThread {
                synchronized(lock) {
                    requireOpen(name)
                    requireWindow(name)
                    readState()
                }
            }
            if (expected(current)) {
                onAwtThread {
                    synchronized(lock) { publish(current) }
                }
                return@withContext current
            }
            delay(STATE_TRANSITION_POLL_MILLIS)
        }
        throw serviceFailure(
            code = KWebServiceErrorCode.NATIVE_FAILED,
            operation = name,
            message = "The Compose window did not reach the requested state in time.",
        )
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OWNER_CLOSED,
                operation = operation,
                message = "The KWebWindowControls service is closed.",
            )
        }
    }

    private fun requireWindow(operation: String) {
        if (!window.isDisplayable) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OWNER_CLOSED,
                operation = operation,
                message = "The ComposeWindow owner has been disposed.",
            )
        }
    }

    private fun readAndPublish(): KWebWindowState = readState().also(::publish)

    private fun readState(): KWebWindowState = KWebWindowState(
        title = window.title.orEmpty(),
        bounds = KWebWindowBounds(window.x, window.y, window.width, window.height),
        visible = window.isVisible,
        focused = window.isFocused,
        minimized = window.isMinimized,
        placement = currentPlacement(),
        alwaysOnTop = window.isAlwaysOnTop,
        resizable = window.isResizable,
    )

    private fun publishCurrentState() {
        if (!EventQueue.isDispatchThread()) return
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.OPEN && window.isDisplayable) {
                publish(readState())
            }
        }
    }

    private fun publish(value: KWebWindowState) {
        if (mutableState.value == value) return
        mutableState.value = value
        if (!mutableEvents.tryEmit(KWebWindowEvent(nextSequence.getAndIncrement(), value))) {
            throw serviceFailure(
                code = KWebServiceErrorCode.NATIVE_FAILED,
                operation = "publish-event",
                message = "The window state event buffer is exhausted.",
            )
        }
    }

    private fun installListeners() {
        check(EventQueue.isDispatchThread())
        if (listenersInstalled) return
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
            removeListeners()
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }

    private fun serviceFailure(
        code: String,
        operation: String,
        message: String,
        cause: Throwable? = null,
    ): KWebNativeException = KWebNativeException(
        code = code,
        details = mapOf("service" to descriptor.id, "operation" to operation),
        message = message,
        cause = cause,
    )

    private fun requireNotFullscreen(operation: String) {
        if (window.placement == WindowPlacement.Fullscreen) {
            throw serviceFailure(
                code = KWebServiceErrorCode.OPERATION_UNAVAILABLE,
                operation = operation,
                message = "Fullscreen window control is outside the published contract.",
            )
        }
    }

    private fun currentPlacement(): KWebWindowPlacement = when (window.placement) {
        WindowPlacement.Floating -> KWebWindowPlacement.FLOATING
        WindowPlacement.Maximized -> KWebWindowPlacement.MAXIMIZED
        WindowPlacement.Fullscreen -> KWebWindowPlacement.FULLSCREEN
    }

    private fun <T> onAwtThread(action: () -> T): T {
        if (EventQueue.isDispatchThread()) return action()
        val task = FutureTask(action)
        try {
            EventQueue.invokeAndWait(task)
            return task.get()
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Dispatching the window operation was interrupted.", error)
        } catch (error: ExecutionException) {
            throw error.cause ?: error
        }
    }

    companion object {
        private const val EVENT_REPLAY: Int = 64
        private const val MAXIMUM_TITLE_LENGTH: Int = 4096
        private const val STATE_TRANSITION_POLL_MILLIS: Long = 25L
        private const val STATE_TRANSITION_TIMEOUT_NANOS: Long = 10_000_000_000L

        fun open(window: ComposeWindow): ComposeKWebWindowControls = onAwtThreadStatic {
            if (!window.isDisplayable) {
                throw KWebConfigurationException(
                    code = "window.owner.not-displayable",
                    details = emptyMap(),
                    message = "KWebWindowControls requires a displayable caller-owned ComposeWindow.",
                )
            }
            if (window.width <= 0 || window.height <= 0) {
                throw KWebConfigurationException(
                    code = "window.bounds.invalid",
                    details = mapOf("width" to window.width.toString(), "height" to window.height.toString()),
                    message = "KWebWindowControls requires positive initial window bounds.",
                )
            }
            val service = ComposeKWebWindowControls(
                window = window,
                initialState = KWebWindowState(
                    title = window.title.orEmpty(),
                    bounds = KWebWindowBounds(window.x, window.y, window.width, window.height),
                    visible = window.isVisible,
                    focused = window.isFocused,
                    minimized = window.isMinimized,
                    placement = when (window.placement) {
                        WindowPlacement.Floating -> KWebWindowPlacement.FLOATING
                        WindowPlacement.Maximized -> KWebWindowPlacement.MAXIMIZED
                        WindowPlacement.Fullscreen -> KWebWindowPlacement.FULLSCREEN
                    },
                    alwaysOnTop = window.isAlwaysOnTop,
                    resizable = window.isResizable,
                ),
            )
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
}

private fun KWebWindowPlacement.toComposePlacement(): WindowPlacement = when (this) {
    KWebWindowPlacement.FLOATING -> WindowPlacement.Floating
    KWebWindowPlacement.MAXIMIZED -> WindowPlacement.Maximized
    KWebWindowPlacement.FULLSCREEN -> throw KWebNativeException(
        code = KWebServiceErrorCode.OPERATION_UNAVAILABLE,
        details = mapOf("service" to KWebWindowControls.DESCRIPTOR.id, "operation" to "restore"),
        message = "Fullscreen window control is outside the published contract.",
    )
}
