package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeProtocol
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.awt.Component
import java.awt.EventQueue
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

internal enum class NativeBrowserEventType(val value: Int) {
    CREATED(1),
    NAVIGATION_STARTED(2),
    ADDRESS_CHANGED(3),
    LOADING_STATE_CHANGED(4),
    LOAD_ENDED(5),
    LOAD_FAILED(6),
    RESIZED(7),
    FATAL_ERROR(8),
    TITLE_CHANGED(9),
    CLOSED(10),
    DEVTOOLS_OPENED(11),
    DEVTOOLS_CLOSED(12),
    DEVTOOLS_FAILED(13),
    ;

    companion object {
        fun fromValue(value: Int): NativeBrowserEventType? = entries.singleOrNull { it.value == value }
    }
}

internal data class NativeBrowserEvent(
    val type: NativeBrowserEventType,
    val engine: Long,
    val browser: Long,
    val sequence: Long,
    val flags: Int,
    val text: String,
    val statusCode: Int,
    val width: Int,
    val height: Int,
)

internal enum class NativeBridgeEventType(val value: Int) {
    REQUEST(1),
    CANCELLED(2),
    ;

    companion object {
        fun fromValue(value: Int): NativeBridgeEventType? = entries.singleOrNull { it.value == value }
    }
}

internal class NativeBrowser private constructor(
    private val engine: NativeEngine,
    private val listener: (NativeBrowserEvent) -> Unit,
    private val component: Component,
    private val bridgeDispatcher: KWebBridgeDispatcher?,
) : AutoCloseable {
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPENING)
    private val nativeHandle = AtomicLong(0)
    private val callbackHandle = AtomicLong(0)
    private val nextSequence = AtomicLong(1)
    private val opened = CountDownLatch(1)
    private val terminal = CountDownLatch(1)
    private val closeStarted = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val callbackFailure = AtomicReference<KWebNativeException?>()
    private val closeFailure = AtomicReference<KWebNativeException?>()
    private val fatalFailure = AtomicReference<KWebNativeException?>()
    private val devtoolsFailure = AtomicReference<KWebNativeException?>()
    private val devtoolsOpenedEvent = AtomicReference(CountDownLatch(0))
    private val devtoolsClosedEvent = AtomicReference(CountDownLatch(0))
    private val callbackThread = AtomicReference<Thread?>()
    private val callbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "KWebShell-browser-callback-${dispatcherIds.incrementAndGet()}").also { thread ->
            thread.isDaemon = true
            callbackThread.set(thread)
        }
    }
    private val sink = NativeBrowserEventSink(::receiveNativeEvent)
    private val bridgeScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineName("KWebShell-bridge-${dispatcherIds.incrementAndGet()}"),
    )
    private val bridgeJobs = ConcurrentHashMap<Long, Job>()
    private val bridgeSink = bridgeDispatcher?.let { NativeBridgeEventSink(::receiveNativeBridgeEvent) }

    internal val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    internal fun navigate(url: String) {
        val handle = requireOpenHandle("navigate")
        requireNativeSuccess("browser-navigate", NativeBindings.browserNavigate(handle, url), handle)
    }

    internal fun resize(width: Int, height: Int) {
        val handle = requireOpenHandle("resize")
        requireNativeSuccess("browser-resize", NativeBindings.browserResize(handle, width, height), handle)
    }

    internal fun requireLiveHandle(operation: String): Long =
        requireOpenHandle(operation)

    internal fun openDevTools() {
        val handle = requireOpenHandle("open-devtools")
        devtoolsOpenedEvent.set(CountDownLatch(1))
        devtoolsFailure.set(null)
        requireNativeSuccess(
            "browser-open-devtools",
            NativeBindings.browserOpenDevTools(handle),
            handle,
        )
        awaitBrowserEvent(devtoolsOpenedEvent.get(), "open-devtools")
    }

    internal fun closeDevTools() {
        val handle = requireOpenHandle("close-devtools")
        devtoolsClosedEvent.set(CountDownLatch(1))
        devtoolsFailure.set(null)
        requireNativeSuccess(
            "browser-close-devtools",
            NativeBindings.browserCloseDevTools(handle),
            handle,
        )
        awaitBrowserEvent(devtoolsClosedEvent.get(), "close-devtools")
    }

    override fun close() {
        if (Thread.currentThread() === callbackThread.get()) {
            throw KWebNativeException(
                code = "native.browser.close-from-callback",
                details = emptyMap(),
                message = "The native browser cannot close from its callback dispatcher.",
            )
        }
        if (!closeStarted.compareAndSet(false, true)) {
            awaitConcurrentClose()
            closeFailure.get()?.let { throw it }
            return
        }

        var failure: KWebNativeException? = null
        try {
            if (mutableLifecycle.value != KWebLifecycleState.FAILED) {
                mutableLifecycle.value = KWebLifecycleState.CLOSING
            }
            val handle = nativeHandle.getAndSet(0)
            if (handle == 0L) {
                failure = KWebNativeException(
                    code = "native.browser.handle-missing",
                    details = emptyMap(),
                    message = "The native browser lost ownership of its handle.",
                )
            } else {
                val status = NativeBindings.browserClose(handle)
                if (status != NativeStatus.OK.value && status != NativeStatus.BROWSER_CLOSING.value) {
                    failure = nativeStatusException(
                        operation = "browser-close",
                        value = status,
                        details = mapOf("handle" to handle.toString()),
                    )
                } else if (!awaitTerminalEvent()) {
                    failure = KWebNativeException(
                        code = "native.browser.closed-event-timeout",
                        details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                        message = "The native browser terminal callback did not arrive in time.",
                    )
                }
            }
            callbackExecutor.shutdown()
            closeBridgeScope()
            if (!awaitExecutorTermination()) {
                failure = failure ?: KWebNativeException(
                    code = "native.browser.callback-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "The native browser callback dispatcher did not terminate in time.",
                )
            }
            failure = failure ?: callbackFailure.get() ?: fatalFailure.get()
            if (failure == null && mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                failure = KWebNativeException(
                    code = "native.browser.closed-event-missing",
                    details = mapOf("state" to mutableLifecycle.value.name),
                    message = "The native browser closed without its terminal callback.",
                )
            }
        } finally {
            if (failure != null) {
                mutableLifecycle.value = KWebLifecycleState.FAILED
                closeFailure.compareAndSet(null, failure)
            }
            closeCompleted.countDown()
        }
        failure?.let { throw it }
    }

    private fun receiveNativeEvent(
        engineHandle: Long,
        browserHandle: Long,
        sequence: Long,
        type: Int,
        flags: Int,
        text: String,
        statusCode: Int,
        width: Int,
        height: Int,
    ) {
        if (callbackHandle.get() == 0L) {
            callbackHandle.compareAndSet(0, browserHandle)
        }
        try {
            callbackExecutor.execute {
                processNativeEvent(
                    NativeBrowserEvent(
                        NativeBrowserEventType.fromValue(type) ?: run {
                            recordCallbackFailure(
                                "native.browser.callback-type-unknown",
                                mapOf("type" to type.toString()),
                                "Native browser callback type is unknown.",
                            )
                            return@execute
                        },
                        engineHandle,
                        browserHandle,
                        sequence,
                        flags,
                        text,
                        statusCode,
                        width,
                        height,
                    ),
                )
            }
        } catch (error: RejectedExecutionException) {
            recordCallbackFailure(
                "native.browser.callback-after-close",
                mapOf("sequence" to sequence.toString()),
                "A native browser callback arrived after dispatcher shutdown.",
                error,
            )
        }
    }

    private fun receiveNativeBridgeEvent(
        engineHandle: Long,
        browserHandle: Long,
        requestId: Long,
        typeValue: Int,
        payload: String,
    ) {
        try {
            callbackExecutor.execute {
                if (!engine.ownsHandle(engineHandle) || browserHandle != callbackHandle.get() ||
                    requestId <= 0L
                ) {
                    recordCallbackFailure(
                        "native.bridge.callback-handle-mismatch",
                        mapOf(
                            "engine" to engineHandle.toString(),
                            "browser" to browserHandle.toString(),
                            "requestId" to requestId.toString(),
                        ),
                        "A native bridge callback targeted the wrong owner.",
                    )
                    return@execute
                }
                when (NativeBridgeEventType.fromValue(typeValue)) {
                    NativeBridgeEventType.REQUEST -> launchBridgeRequest(browserHandle, requestId, payload)
                    NativeBridgeEventType.CANCELLED -> bridgeJobs.remove(requestId)?.cancel(
                        CancellationException("The page cancelled bridge request $requestId."),
                    )
                    null -> recordCallbackFailure(
                        "native.bridge.callback-type-unknown",
                        mapOf("type" to typeValue.toString()),
                        "Native bridge callback type is unknown.",
                    )
                }
            }
        } catch (error: RejectedExecutionException) {
            recordCallbackFailure(
                "native.bridge.callback-after-close",
                mapOf("requestId" to requestId.toString()),
                "A native bridge callback arrived after dispatcher shutdown.",
                error,
            )
        }
    }

    private fun launchBridgeRequest(browserHandle: Long, requestId: Long, payload: String) {
        val dispatcher = bridgeDispatcher ?: run {
            recordCallbackFailure(
                "native.bridge.dispatcher-missing",
                mapOf("requestId" to requestId.toString()),
                "Native delivered a bridge request without a configured dispatcher.",
            )
            return
        }
        val job = bridgeScope.launch(start = CoroutineStart.LAZY) {
            try {
                val response = dispatcher.dispatch(payload)
                requireBridgeNativeSuccess(
                    "browser-bridge-respond",
                    NativeBindings.browserBridgeRespond(browserHandle, requestId, response),
                    requestId,
                )
            } catch (_: CancellationException) {
                throw CancellationException("Bridge request $requestId was cancelled.")
            } catch (error: Throwable) {
                val status = NativeBindings.browserBridgeFail(
                    browserHandle,
                    requestId,
                    KWebBridgeProtocol.encodeFailure(error),
                )
                if (status != NativeStatus.OK.value &&
                    status != NativeStatus.BRIDGE_REQUEST_NOT_FOUND.value
                ) {
                    recordCallbackFailure(
                        "native.bridge.failure-response-rejected",
                        mapOf("requestId" to requestId.toString(), "status" to status.toString()),
                        "The native bridge rejected a typed failure response.",
                        error,
                    )
                }
            } finally {
                bridgeJobs.remove(requestId)
            }
        }
        if (bridgeJobs.putIfAbsent(requestId, job) != null) {
            job.cancel(CancellationException("Duplicate bridge request ID $requestId."))
            recordCallbackFailure(
                "native.bridge.request-duplicate",
                mapOf("requestId" to requestId.toString()),
                "Native reused a live bridge request ID.",
            )
        } else {
            job.start()
        }
    }

    private fun processNativeEvent(event: NativeBrowserEvent) {
        if (!engine.ownsHandle(event.engine) || event.browser != callbackHandle.get() || event.browser == 0L
        ) {
            recordCallbackFailure(
                "native.browser.callback-handle-mismatch",
                mapOf("browser" to event.browser.toString(), "engine" to event.engine.toString()),
                "A native callback targeted the wrong browser or engine.",
            )
            return
        }
        val expectedSequence = nextSequence.getAndIncrement()
        if (event.sequence != expectedSequence) {
            recordCallbackFailure(
                "native.browser.callback-sequence-invalid",
                mapOf("expected" to expectedSequence.toString(), "actual" to event.sequence.toString()),
                "Native browser callback sequence is not contiguous.",
            )
            return
        }
        when (event.type) {
            NativeBrowserEventType.DEVTOOLS_FAILED -> {
                val failure = nativeStatusException(
                    operation = "browser-devtools",
                    value = event.statusCode,
                    details = mapOf("reason" to event.text),
                )
                devtoolsFailure.compareAndSet(null, failure)
            }
            NativeBrowserEventType.CREATED -> {
                if (mutableLifecycle.value != KWebLifecycleState.OPENING) {
                    recordCallbackFailure(
                        "native.browser.open-state-invalid",
                        mapOf("state" to mutableLifecycle.value.name),
                        "Browser-created arrived in an invalid state.",
                    )
                    return
                }
                mutableLifecycle.value = KWebLifecycleState.OPEN
            }
            NativeBrowserEventType.FATAL_ERROR -> {
                val failure = KWebNativeException(
                    code = "native.browser.fatal",
                    details = mapOf("nativeCode" to event.text, "statusCode" to event.statusCode.toString()),
                    message = "Chromium reported a terminal browser failure '${event.text}'.",
                )
                fatalFailure.compareAndSet(null, failure)
                mutableLifecycle.value = KWebLifecycleState.FAILED
                opened.countDown()
            }
            NativeBrowserEventType.CLOSED -> {
                if (fatalFailure.get() == null && callbackFailure.get() == null) {
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                }
            }
            else -> Unit
        }
        when (event.type) {
            NativeBrowserEventType.DEVTOOLS_OPENED -> devtoolsOpenedEvent.get().countDown()
            NativeBrowserEventType.DEVTOOLS_CLOSED -> devtoolsClosedEvent.get().countDown()
            else -> Unit
        }
        try {
            listener(event)
            if (event.type == NativeBrowserEventType.CREATED) {
                opened.countDown()
            }
        } catch (error: Throwable) {
            recordCallbackFailure(
                "native.browser.listener-failed",
                mapOf("event" to event.type.name, "sequence" to event.sequence.toString()),
                "The native browser event listener failed.",
                error,
            )
        } finally {
            if (event.type == NativeBrowserEventType.CLOSED) {
                terminal.countDown()
            }
        }
    }

    private fun requireOpenHandle(operation: String): Long {
        if (closeStarted.get() || mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "native.browser.closed",
                details = mapOf("operation" to operation),
                message = "The native browser is not open.",
            )
        }
        return nativeHandle.get().takeIf { it != 0L } ?: throw KWebNativeException(
            code = "native.browser.handle-missing",
            details = mapOf("operation" to operation),
            message = "The native browser handle is unavailable.",
        )
    }

    private fun recordCallbackFailure(
        code: String,
        details: Map<String, String>,
        message: String,
        cause: Throwable? = null,
    ) {
        callbackFailure.compareAndSet(null, KWebNativeException(code, details, message, cause))
        mutableLifecycle.value = KWebLifecycleState.FAILED
        opened.countDown()
    }

    private fun awaitTerminalEvent(): Boolean {
        if (!EventQueue.isDispatchThread()) {
            return try {
                terminal.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KWebNativeException(
                    code = "native.browser.closed-event-interrupted",
                    details = emptyMap(),
                    message = "Waiting for the native browser terminal callback was interrupted.",
                    cause = error,
                )
            }
        }
        if (terminal.count == 0L) return true
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        Thread({
            try {
                terminal.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                EventQueue.invokeLater { loop.exit() }
            }
        }, "KWebShell-browser-close-wait").also { it.isDaemon = true; it.start() }
        loop.enter()
        return terminal.count == 0L
    }

    private fun awaitConcurrentClose() {
        try {
            if (!closeCompleted.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw KWebNativeException(
                    code = "native.browser.concurrent-close-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "A concurrent native browser close did not complete in time.",
                )
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebNativeException(
                code = "native.browser.concurrent-close-interrupted",
                details = emptyMap(),
                message = "Waiting for a concurrent browser close was interrupted.",
                cause = error,
            )
        }
    }

    private fun awaitExecutorTermination(): Boolean = try {
        callbackExecutor.awaitTermination(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        recordCallbackFailure(
            code = "native.browser.callback-interrupted",
            details = emptyMap(),
            message = "Waiting for native browser callbacks was interrupted.",
            cause = error,
        )
        false
    }

    private fun requireNativeSuccess(operation: String, status: Int, handle: Long) {
        if (status != NativeStatus.OK.value) {
            throw nativeStatusException(operation, status, mapOf("handle" to handle.toString()))
        }
    }

    private fun requireBridgeNativeSuccess(operation: String, status: Int, requestId: Long) {
        if (status != NativeStatus.OK.value && status != NativeStatus.BRIDGE_REQUEST_NOT_FOUND.value) {
            throw nativeStatusException(
                operation,
                status,
                mapOf("requestId" to requestId.toString()),
            )
        }
    }

    private fun closeBridgeScope() {
        val jobs = bridgeJobs.values.toList()
        bridgeScope.cancel(CancellationException("The native browser is closing."))
        val completed = runBlocking {
            withTimeoutOrNull(CALLBACK_TIMEOUT.toMillis()) {
                jobs.joinAll()
                true
            } ?: false
        }
        if (!completed) {
            recordCallbackFailure(
                "native.bridge.shutdown-timeout",
                mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                "Bridge handlers did not stop after browser close.",
            )
        }
        bridgeJobs.clear()
    }

    private fun awaitBrowserEvent(event: CountDownLatch, operation: String) {
        val deadline = System.nanoTime() + CALLBACK_TIMEOUT.toNanos()
        while (System.nanoTime() < deadline) {
            if (event.count == 0L) return
            devtoolsFailure.get()?.let { throw it }
            fatalFailure.get()?.let { throw it }
            Thread.sleep(10)
        }
        throw KWebNativeException(
            code = "native.browser.$operation-timeout",
            details = emptyMap(),
            message = "The native browser did not publish the expected DevTools event.",
        )
    }

    private fun abortOpen(error: KWebNativeException): Nothing {
        try {
            close()
        } catch (closeError: Throwable) {
            if (closeError !== error) error.addSuppressed(closeError)
        }
        throw error
    }

    internal companion object {
        private val dispatcherIds = AtomicLong(0)
        private val CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(30)

        internal fun open(
            engine: NativeEngine,
            component: Component,
            profilePath: Path,
            initialUrl: String,
            width: Int,
            height: Int,
            bridgeOrigin: String = "",
            bridgeDispatcher: KWebBridgeDispatcher? = null,
            listener: (NativeBrowserEvent) -> Unit = {},
        ): NativeBrowser {
            if ((bridgeDispatcher == null) != bridgeOrigin.isEmpty()) {
                throw KWebConfigurationException(
                    code = "native.bridge.configuration-incomplete",
                    details = emptyMap(),
                    message = "Bridge origin and dispatcher must be configured together.",
                )
            }
            if (!component.isDisplayable || !component.isShowing) {
                throw KWebConfigurationException(
                    code = "native.browser.awt-parent-not-displayable",
                    details = emptyMap(),
                    message = "The browser AWT parent must be displayable and showing.",
                )
            }
            val root = engine.rootCachePath()
            val normalizedProfile = profilePath.toAbsolutePath().normalize()
            if (!profilePath.isAbsolute || normalizedProfile.parent != root ||
                normalizedProfile.fileName.toString().equals("Default", ignoreCase = true)
            ) {
                throw KWebConfigurationException(
                    code = "native.browser.profile-path-invalid",
                    details = mapOf("profile" to profilePath.toString(), "root" to root.toString()),
                    message = "The browser Profile must be an absolute direct child of the engine root cache and cannot be Default.",
                )
            }
            if (width <= 0 || height <= 0) {
                throw nativeStatusException(
                    operation = "browser-create",
                    value = NativeStatus.INVALID_DIMENSIONS.value,
                    details = mapOf("width" to width.toString(), "height" to height.toString()),
                )
            }
            try {
                Files.createDirectories(normalizedProfile)
            } catch (error: Throwable) {
                throw KWebConfigurationException(
                    code = "native.browser.profile-create-failed",
                    details = mapOf("profile" to normalizedProfile.toString()),
                    message = "The browser Profile directory could not be created.",
                    cause = error,
                )
            }
            val browser = NativeBrowser(engine, listener, component, bridgeDispatcher)
            val result = NativeEngine.onAwtEventDispatchThread {
                NativeBindings.browserCreate(
                    engine.requireLiveHandle("browser-create"),
                    browser.sink,
                    component,
                    normalizedProfile.toString(),
                    initialUrl,
                    0,
                    0,
                    width,
                    height,
                    bridgeOrigin,
                    browser.bridgeSink,
                )
            }
            if (result <= 0L) {
                browser.callbackExecutor.shutdownNow()
                browser.closeBridgeScope()
                val status = if (result < 0) (-result).toInt() else NativeStatus.INTERNAL_ERROR.value
                throw nativeStatusException("browser-create", status)
            }
            browser.nativeHandle.set(result)
            browser.callbackHandle.compareAndSet(0, result)
            if (!browser.opened.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                browser.abortOpen(
                    KWebNativeException(
                        code = "native.browser.open-timeout",
                        details = emptyMap(),
                        message = "The real Chromium browser creation callback timed out.",
                    ),
                )
            }
            browser.callbackFailure.get()?.let(browser::abortOpen)
            browser.fatalFailure.get()?.let(browser::abortOpen)
            if (browser.mutableLifecycle.value != KWebLifecycleState.OPEN) {
                browser.abortOpen(
                    KWebNativeException(
                        code = "native.browser.open-state-invalid",
                        details = mapOf("state" to browser.mutableLifecycle.value.name),
                        message = "The real Chromium browser did not reach the open state.",
                    ),
                )
            }
            return browser
        }

        internal fun liveNativeBrowserCount(): Long = NativeBindings.liveBrowserCount()
    }
}
