package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.EventQueue
import java.awt.Toolkit
import java.time.Duration
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal enum class NativeEngineEventType(val value: Int) {
    OPENED(1),
    CLOSED(2),
    ;

    companion object {
        fun fromValue(value: Int): NativeEngineEventType? = entries.singleOrNull { it.value == value }
    }
}

internal data class NativeEngineEvent(
    val type: NativeEngineEventType,
    val handle: Long,
    val sequence: Long,
)

internal class NativeEngine private constructor(
    private val listener: (NativeEngineEvent) -> Unit,
    private val awtLifecycleAnchor: AwtEngineLifecycleAnchor,
    private val configuration: NativeEngineConfiguration,
) : AutoCloseable {
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPENING)
    private val nativeHandle = AtomicLong(0)
    private val callbackHandle = AtomicLong(0)
    private val nextSequence = AtomicLong(1)
    private val opened = CountDownLatch(1)
    private val closed = CountDownLatch(1)
    private val callbackFailure = AtomicReference<KWebNativeException?>()
    private val closeFailure = AtomicReference<KWebNativeException?>()
    private val callbackThread = AtomicReference<Thread?>()
    private val callbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "KWebShell-engine-callback-${dispatcherIds.incrementAndGet()}").also { thread ->
            thread.isDaemon = true
            callbackThread.set(thread)
        }
    }
    private val sink = NativeEngineEventSink(
        failureCallback = ::receiveFfmCallbackFailure,
        callback = ::receiveNativeEvent,
    )

    internal val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
    internal val remoteDebuggingPort: Int = configuration.remoteDebuggingPort

    internal fun requireLiveHandle(operation: String): Long {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "native.engine.closed",
                details = mapOf("operation" to operation),
                message = "The native engine is not open.",
            )
        }
        return nativeHandle.get().takeIf { it != 0L } ?: throw KWebNativeException(
            code = "native.engine.handle-missing",
            details = mapOf("operation" to operation),
            message = "The native engine handle is unavailable.",
        )
    }

    internal fun rootCachePath(): Path = configuration.rootCache

    internal fun ownsHandle(handle: Long): Boolean = handle != 0L && callbackHandle.get() == handle

    @Synchronized
    override fun close() {
        if (Thread.currentThread() === callbackThread.get()) {
            throw KWebNativeException(
                code = "native.engine.close-from-callback",
                details = emptyMap(),
                message = "The native engine cannot close from its callback dispatcher.",
            )
        }
        if (nativeHandle.get() == 0L) {
            closeFailure.get()?.let { throw it }
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            throw KWebNativeException(
                code = "native.engine.handle-missing",
                details = emptyMap(),
                message = "The native engine lost ownership of its handle.",
            )
        }

        val stateBeforeClose = mutableLifecycle.value
        var failure: KWebNativeException? = null
        if (stateBeforeClose != KWebLifecycleState.FAILED) {
            mutableLifecycle.value = KWebLifecycleState.CLOSING
        }
        val handle = nativeHandle.get()
        val status = onAwtEventDispatchThread { NativeBindings.engineClose(handle) }
        val closeAccepted = status == NativeStatus.OK.value ||
            status == NativeStatus.CALLBACK_FAILED.value ||
            status == NativeStatus.PLATFORM_INITIALIZATION_FAILED.value
        if (!closeAccepted) {
            if (stateBeforeClose != KWebLifecycleState.FAILED) {
                mutableLifecycle.value = stateBeforeClose
            }
            throw nativeStatusException(
                operation = "engine-close",
                value = status,
                details = mapOf("handle" to handle.toString()),
            )
        }
        if (!nativeHandle.compareAndSet(handle, 0L)) {
            failure = KWebNativeException(
                code = "native.engine.handle-mismatch",
                details = mapOf("expected" to handle.toString(), "actual" to nativeHandle.get().toString()),
                message = "The native engine handle changed while close was accepted.",
            )
        }

        var terminalObserved = false
        var dispatcherTerminated = false
        try {
            terminalObserved = awaitTerminalEvent()
            if (!terminalObserved) {
                failure = failure ?: KWebNativeException(
                    code = "native.engine.closed-event-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "The native engine terminal callback did not arrive in time.",
                )
            } else if (status != NativeStatus.OK.value) {
                failure = failure ?: nativeStatusException(
                    operation = "engine-close",
                    value = status,
                    details = mapOf("handle" to handle.toString()),
                )
            }

            callbackExecutor.shutdown()
            dispatcherTerminated = awaitExecutorTermination()
            if (!dispatcherTerminated) {
                failure = failure ?: KWebNativeException(
                    code = "native.engine.callback-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "The native engine callback dispatcher did not terminate in time.",
                )
            }
            if (terminalObserved && dispatcherTerminated) {
                try {
                    val ownerFailure = NativeBindings.releaseEngineOwner(handle)
                    if (ownerFailure != null && callbackFailure.get() == null) {
                        failure = failure ?: KWebNativeException(
                            code = "native.ffm.engine-callback-failed",
                            details = mapOf("handle" to handle.toString()),
                            message = "The FFM engine callback owner recorded an unreported failure.",
                            cause = ownerFailure,
                        )
                    }
                } catch (error: KWebNativeException) {
                    if (failure == null) {
                        failure = error
                    } else if (failure !== error) {
                        failure.addSuppressed(error)
                    }
                }
            }
            failure = failure ?: callbackFailure.get()
            if (failure == null && mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                failure = KWebNativeException(
                    code = "native.engine.closed-event-missing",
                    details = mapOf("state" to mutableLifecycle.value.name),
                    message = "The native engine closed without its terminal callback.",
                )
            }
        } finally {
            try {
                onAwtEventDispatchThread(awtLifecycleAnchor::close)
            } catch (error: Throwable) {
                val anchorFailure = if (error is KWebNativeException) {
                    error
                } else {
                    KWebNativeException(
                        code = "native.engine.awt-anchor-destroy-failed",
                        details = emptyMap(),
                        message = "Destroying the native engine AWT lifecycle peer failed.",
                        cause = error,
                    )
                }
                if (failure == null) {
                    failure = anchorFailure
                } else if (failure !== anchorFailure) {
                    failure.addSuppressed(anchorFailure)
                }
            }
            if (failure != null) {
                mutableLifecycle.value = KWebLifecycleState.FAILED
                closeFailure.compareAndSet(null, failure)
            }
        }
        failure?.let { throw it }
    }

    private fun receiveNativeEvent(handle: Long, sequence: Long, type: Int) {
        val ownedHandle = callbackHandle.get()
        if (ownedHandle == 0L) {
            callbackHandle.compareAndSet(0, handle)
        }
        try {
            callbackExecutor.execute {
                processNativeEvent(handle, sequence, type)
            }
        } catch (error: RejectedExecutionException) {
            recordCallbackFailure(
                code = "native.engine.callback-after-close",
                details = mapOf("sequence" to sequence.toString()),
                message = "A native engine callback arrived after dispatcher shutdown.",
                cause = error,
            )
        } catch (error: Throwable) {
            recordCallbackFailure(
                code = "native.engine.callback-dispatch-failed",
                details = mapOf("sequence" to sequence.toString()),
                message = "A native engine callback could not be dispatched.",
                cause = error,
            )
        }
    }

    private fun receiveFfmCallbackFailure(code: String, message: String, cause: Throwable) {
        recordCallbackFailure(
            code = code,
            details = emptyMap(),
            message = message,
            cause = cause,
        )
    }

    private fun processNativeEvent(handle: Long, sequence: Long, typeValue: Int) {
        val expectedHandle = callbackHandle.get()
        if (expectedHandle != handle || handle == 0L) {
            recordCallbackFailure(
                code = "native.engine.callback-handle-mismatch",
                details = mapOf("expected" to expectedHandle.toString(), "actual" to handle.toString()),
                message = "A native callback targeted the wrong engine.",
            )
            return
        }
        val expectedSequence = nextSequence.getAndIncrement()
        if (sequence != expectedSequence) {
            recordCallbackFailure(
                code = "native.engine.callback-sequence-invalid",
                details = mapOf("expected" to expectedSequence.toString(), "actual" to sequence.toString()),
                message = "Native engine callback sequence is not contiguous.",
            )
            return
        }
        val type = NativeEngineEventType.fromValue(typeValue)
        if (type == null) {
            recordCallbackFailure(
                code = "native.engine.callback-type-unknown",
                details = mapOf("type" to typeValue.toString()),
                message = "Native engine callback type is unknown.",
            )
            return
        }

        when (type) {
            NativeEngineEventType.OPENED -> {
                if (mutableLifecycle.value != KWebLifecycleState.OPENING) {
                    recordCallbackFailure(
                        code = "native.engine.open-state-invalid",
                        details = mapOf("state" to mutableLifecycle.value.name),
                        message = "Engine-opened arrived in an invalid state.",
                    )
                    return
                }
                mutableLifecycle.value = KWebLifecycleState.OPEN
            }
            NativeEngineEventType.CLOSED -> {
                if (mutableLifecycle.value != KWebLifecycleState.FAILED) {
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                }
            }
        }

        try {
            listener(NativeEngineEvent(type, handle, sequence))
            if (type == NativeEngineEventType.OPENED) {
                opened.countDown()
            }
        } catch (error: Throwable) {
            recordCallbackFailure(
                code = "native.engine.listener-failed",
                details = mapOf("event" to type.name, "sequence" to sequence.toString()),
                message = "The native engine listener failed.",
                cause = error,
            )
        } finally {
            if (type == NativeEngineEventType.CLOSED) {
                closed.countDown()
            }
        }
    }

    private fun awaitTerminalEvent(): Boolean {
        if (!EventQueue.isDispatchThread()) {
            return try {
                closed.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KWebNativeException(
                    code = "native.engine.closed-event-interrupted",
                    details = emptyMap(),
                    message = "Waiting for the native engine terminal callback was interrupted.",
                    cause = error,
                )
            }
        }

        if (closed.count == 0L) {
            return true
        }
        val secondaryLoop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        Thread({
            try {
                closed.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                EventQueue.invokeLater { secondaryLoop.exit() }
            }
        }, "KWebShell-engine-close-wait").also { waiter ->
            waiter.isDaemon = true
            waiter.start()
        }
        secondaryLoop.enter()
        return closed.count == 0L
    }

    private fun recordCallbackFailure(
        code: String,
        details: Map<String, String>,
        message: String,
        cause: Throwable? = null,
    ) {
        val error = KWebNativeException(code, details, message, cause)
        callbackFailure.compareAndSet(null, error)
        mutableLifecycle.value = KWebLifecycleState.FAILED
        opened.countDown()
    }

    private fun awaitExecutorTermination(): Boolean = try {
        callbackExecutor.awaitTermination(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        recordCallbackFailure(
            code = "native.engine.callback-interrupted",
            details = emptyMap(),
            message = "Waiting for native engine callbacks was interrupted.",
            cause = error,
        )
        false
    }

    private fun abortOpen(error: KWebNativeException): Nothing {
        try {
            close()
        } catch (closeError: Throwable) {
            if (closeError !== error) {
                error.addSuppressed(closeError)
            }
        }
        throw error
    }

    private fun failBeforeNativeOwnership(error: Throwable): Nothing {
        callbackExecutor.shutdownNow()
        try {
            onAwtEventDispatchThread(awtLifecycleAnchor::close)
        } catch (cleanupError: Throwable) {
            if (cleanupError !== error) {
                error.addSuppressed(cleanupError)
            }
        }
        throw error
    }

    internal companion object {
        private val dispatcherIds = AtomicLong(0)
        private val CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(30)

        internal fun prepareNativeRuntime(
            configuration: NativeEngineConfiguration,
        ): NativeEngineConfiguration {
            val validated = configuration.validated()
            val loadStatus = NativeBindings.loadEngineLibrary(
                NativeBindings.libraryPaths.engine.toString(),
                validated.cefRuntime.toString(),
            )
            if (loadStatus != NativeStatus.OK.value) {
                throw nativeStatusException(
                    operation = "engine-library-load",
                    value = loadStatus,
                    details = mapOf(
                        "enginePath" to NativeBindings.libraryPaths.engine.toString(),
                        "cefRuntimePath" to validated.cefRuntime.toString(),
                    ),
                    cause = NativeBindings.lastEngineLibraryLoadFailure(),
                )
            }
            val engineAbiVersion = NativeBindings.engineAbiVersion()
            if (engineAbiVersion != NATIVE_ABI_VERSION) {
                throw abiVersionMismatch("engine", engineAbiVersion)
            }
            return validated
        }

        internal fun open(
            configuration: NativeEngineConfiguration,
            listener: (NativeEngineEvent) -> Unit = {},
        ): NativeEngine {
            val validated = prepareNativeRuntime(configuration)
            val awtLifecycleAnchor = onAwtEventDispatchThread(AwtEngineLifecycleAnchor::create)
            val engine = NativeEngine(listener, awtLifecycleAnchor, validated)
            val createResult = try {
                onAwtEventDispatchThread {
                    check(EventQueue.isDispatchThread())
                    NativeBindings.engineCreate(
                        engine.sink,
                        validated.cefRuntime.toString(),
                        validated.browserSubprocess.toString(),
                        validated.resources.toString(),
                        validated.locales.toString(),
                        validated.rootCache.toString(),
                        validated.log.toString(),
                        validated.remoteDebuggingPort,
                    )
                }
            } catch (error: Throwable) {
                engine.failBeforeNativeOwnership(error)
            }
            if (createResult <= 0L) {
                val statusValue = if (createResult < 0L) (-createResult).toInt() else NativeStatus.INTERNAL_ERROR.value
                engine.failBeforeNativeOwnership(nativeStatusException("engine-create", statusValue))
            }
            engine.nativeHandle.set(createResult)
            if (engine.callbackHandle.get() == 0L) {
                engine.callbackHandle.compareAndSet(0, createResult)
            }
            if (engine.callbackHandle.get() != createResult) {
                onAwtEventDispatchThread { NativeBindings.engineClose(createResult) }
                engine.nativeHandle.set(0)
                engine.failBeforeNativeOwnership(
                    KWebNativeException(
                        code = "native.engine.handle-mismatch",
                        details = mapOf(
                            "created" to createResult.toString(),
                            "callback" to engine.callbackHandle.get().toString(),
                        ),
                        message = "Native engine creation and callback handles do not match.",
                    ),
                )
            }

            try {
                if (!engine.opened.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    engine.abortOpen(
                        KWebNativeException(
                            code = "native.engine.open-timeout",
                            details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                            message = "The real CEF context initialization callback timed out.",
                        ),
                    )
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                engine.abortOpen(
                    KWebNativeException(
                        code = "native.engine.open-interrupted",
                        details = emptyMap(),
                        message = "Waiting for the real CEF engine was interrupted.",
                        cause = error,
                    ),
                )
            }
            engine.callbackFailure.get()?.let(engine::abortOpen)
            if (engine.mutableLifecycle.value != KWebLifecycleState.OPEN) {
                engine.abortOpen(
                    KWebNativeException(
                        code = "native.engine.open-state-invalid",
                        details = mapOf("state" to engine.mutableLifecycle.value.name),
                        message = "The native CEF engine did not reach the open state.",
                    ),
                )
            }
            return engine
        }

        internal fun liveNativeEngineCount(): Long = NativeBindings.liveEngineCount()

        internal fun <T> onAwtEventDispatchThread(operation: () -> T): T {
            if (EventQueue.isDispatchThread()) {
                return operation()
            }
            val task = FutureTask(operation)
            try {
                EventQueue.invokeAndWait(task)
                return task.get()
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw KWebNativeException(
                    code = "native.engine.awt-dispatch-interrupted",
                    details = emptyMap(),
                    message = "Dispatching the native engine operation to the AWT event thread was interrupted.",
                    cause = error,
                )
            } catch (error: ExecutionException) {
                val cause = error.cause
                if (cause is RuntimeException) {
                    throw cause
                }
                throw KWebNativeException(
                    code = "native.engine.awt-dispatch-failed",
                    details = emptyMap(),
                    message = "The native engine operation failed on the AWT event thread.",
                    cause = cause ?: error,
                )
            }
        }

        private fun abiVersionMismatch(component: String, actual: Int): KWebNativeException =
            KWebNativeException(
                code = "native.abi.version-mismatch",
                details = mapOf(
                    "component" to component,
                    "expected" to NATIVE_ABI_VERSION.toString(),
                    "actual" to actual.toString(),
                ),
                message = "The loaded KWebShell native $component ABI version is incompatible.",
            )
    }
}
