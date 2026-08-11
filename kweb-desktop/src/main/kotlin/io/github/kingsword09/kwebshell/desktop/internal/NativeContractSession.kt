package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal class NativeContractSession private constructor(
    private val listener: (NativeContractEvent) -> Unit,
) : AutoCloseable {
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPENING)
    private val nativeHandle = AtomicLong(0)
    private val callbackHandle = AtomicLong(0)
    private val nextSequence = AtomicLong(1)
    private val opened = CountDownLatch(1)
    private val closeStarted = AtomicBoolean(false)
    private val closeCompleted = CountDownLatch(1)
    private val callbackFailure = AtomicReference<KWebNativeException?>()
    private val closeFailure = AtomicReference<KWebNativeException?>()
    private val callbackThread = AtomicReference<Thread?>()
    private val callbackExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "KWebShell-JNI-callback-${dispatcherIds.incrementAndGet()}").also { thread ->
            thread.isDaemon = true
            callbackThread.set(thread)
        }
    }
    private val sink = NativeEventSink(::receiveNativeEvent)

    internal val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    internal fun requestNavigation(url: String) {
        val handle = requireOpenHandle("request-navigation")
        requireNativeSuccess(
            operation = "request-navigation",
            status = NativeBindings.requestNavigation(handle, url),
            handle = handle,
        )
    }

    internal fun resize(width: Int, height: Int) {
        val handle = requireOpenHandle("resize")
        requireNativeSuccess(
            operation = "resize",
            status = NativeBindings.resize(handle, width, height),
            handle = handle,
        )
    }

    override fun close() {
        if (Thread.currentThread() === callbackThread.get()) {
            throw KWebNativeException(
                code = "native.session.close-from-callback",
                details = emptyMap(),
                message = "A native session cannot close from its callback dispatcher.",
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
                    code = "native.session.handle-missing",
                    details = emptyMap(),
                    message = "The native session lost ownership of its handle.",
                )
            } else {
                val status = NativeBindings.close(handle)
                if (status != NativeStatus.OK.value) {
                    failure = nativeStatusException(
                        operation = "close",
                        value = status,
                        details = mapOf("handle" to handle.toString()),
                    )
                }
            }

            callbackExecutor.shutdown()
            if (!awaitExecutorTermination()) {
                failure = failure ?: KWebNativeException(
                    code = "native.callback.dispatch-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "The native callback dispatcher did not terminate in time.",
                )
            }
            failure = failure ?: callbackFailure.get()
            if (failure == null && mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                failure = KWebNativeException(
                    code = "native.callback.closed-event-missing",
                    details = mapOf("state" to mutableLifecycle.value.name),
                    message = "The native session closed without its terminal callback.",
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
        handle: Long,
        sequence: Long,
        type: Int,
        text: String,
        width: Int,
        height: Int,
    ) {
        val ownedHandle = callbackHandle.get()
        if (ownedHandle == 0L) {
            callbackHandle.compareAndSet(0, handle)
        }
        try {
            callbackExecutor.execute {
                processNativeEvent(handle, sequence, type, text, width, height)
            }
        } catch (error: RejectedExecutionException) {
            recordCallbackFailure(
                code = "native.callback.after-dispatch-close",
                details = mapOf("sequence" to sequence.toString()),
                message = "A native callback arrived after dispatcher shutdown.",
                cause = error,
            )
        } catch (error: Throwable) {
            recordCallbackFailure(
                code = "native.callback.dispatch-failed",
                details = mapOf("sequence" to sequence.toString()),
                message = "A native callback could not be dispatched.",
                cause = error,
            )
        }
    }

    private fun processNativeEvent(
        handle: Long,
        sequence: Long,
        typeValue: Int,
        text: String,
        width: Int,
        height: Int,
    ) {
        val expectedHandle = callbackHandle.get()
        if (expectedHandle != handle || handle == 0L) {
            recordCallbackFailure(
                code = "native.callback.handle-mismatch",
                details = mapOf(
                    "expected" to expectedHandle.toString(),
                    "actual" to handle.toString(),
                ),
                message = "A native callback targeted the wrong session.",
            )
            return
        }
        val expectedSequence = nextSequence.getAndIncrement()
        if (sequence != expectedSequence) {
            recordCallbackFailure(
                code = "native.callback.sequence-invalid",
                details = mapOf(
                    "expected" to expectedSequence.toString(),
                    "actual" to sequence.toString(),
                ),
                message = "Native callback sequence is not contiguous.",
            )
            return
        }
        val type = NativeEventType.fromValue(typeValue)
        if (type == null) {
            recordCallbackFailure(
                code = "native.callback.type-unknown",
                details = mapOf("type" to typeValue.toString()),
                message = "Native callback type is unknown.",
            )
            return
        }

        when (type) {
            NativeEventType.SESSION_OPENED -> {
                if (mutableLifecycle.value != KWebLifecycleState.OPENING) {
                    recordCallbackFailure(
                        code = "native.callback.open-state-invalid",
                        details = mapOf("state" to mutableLifecycle.value.name),
                        message = "Session-opened arrived in an invalid state.",
                    )
                    return
                }
                mutableLifecycle.value = KWebLifecycleState.OPEN
            }

            NativeEventType.SESSION_CLOSED -> {
                if (mutableLifecycle.value != KWebLifecycleState.FAILED) {
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                }
            }

            NativeEventType.NAVIGATION_REQUESTED,
            NativeEventType.VIEWPORT_CHANGED,
            -> Unit
        }

        val event = NativeContractEvent(type, handle, sequence, text, width, height)
        try {
            listener(event)
            if (type == NativeEventType.SESSION_OPENED) {
                opened.countDown()
            }
        } catch (error: Throwable) {
            recordCallbackFailure(
                code = "native.callback.listener-failed",
                details = mapOf(
                    "event" to type.name,
                    "sequence" to sequence.toString(),
                ),
                message = "The native event listener failed.",
                cause = error,
            )
        }
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

    private fun requireOpenHandle(operation: String): Long {
        if (closeStarted.get()) {
            throw KWebNativeException(
                code = "native.session.closed",
                details = mapOf("operation" to operation),
                message = "The native session is closing or closed.",
            )
        }
        val handle = nativeHandle.get()
        if (handle == 0L || mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "native.session.not-open",
                details = mapOf(
                    "operation" to operation,
                    "state" to mutableLifecycle.value.name,
                ),
                message = "The native session is not open.",
            )
        }
        return handle
    }

    private fun requireNativeSuccess(operation: String, status: Int, handle: Long) {
        if (status != NativeStatus.OK.value) {
            throw nativeStatusException(
                operation = operation,
                value = status,
                details = mapOf("handle" to handle.toString()),
            )
        }
    }

    private fun awaitConcurrentClose() {
        try {
            if (!closeCompleted.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw KWebNativeException(
                    code = "native.session.concurrent-close-timeout",
                    details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                    message = "A concurrent native close did not complete in time.",
                )
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebNativeException(
                code = "native.session.concurrent-close-interrupted",
                details = emptyMap(),
                message = "Waiting for a concurrent native close was interrupted.",
                cause = error,
            )
        }
    }

    private fun awaitExecutorTermination(): Boolean = try {
        callbackExecutor.awaitTermination(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: InterruptedException) {
        Thread.currentThread().interrupt()
        recordCallbackFailure(
            code = "native.callback.dispatch-interrupted",
            details = emptyMap(),
            message = "Waiting for native callback dispatch was interrupted.",
            cause = error,
        )
        false
    }

    private fun abortOpen(error: KWebNativeException): Nothing {
        try {
            close()
        } catch (_: Throwable) {
            // The opening failure remains the primary typed error.
        }
        throw error
    }

    internal companion object {
        private val dispatcherIds = AtomicLong(0)
        private val CALLBACK_TIMEOUT: Duration = Duration.ofSeconds(10)

        internal fun open(
            listener: (NativeContractEvent) -> Unit = {},
        ): NativeContractSession {
            val runtimeVersion = NativeBindings.abiVersion()
            if (runtimeVersion != NATIVE_ABI_VERSION) {
                throw KWebNativeException(
                    code = "native.abi.version-mismatch",
                    details = mapOf(
                        "expected" to NATIVE_ABI_VERSION.toString(),
                        "actual" to runtimeVersion.toString(),
                    ),
                    message = "The loaded KWebShell native ABI version is incompatible.",
                )
            }

            val session = NativeContractSession(listener)
            val createResult = NativeBindings.create(session.sink)
            if (createResult <= 0L) {
                session.callbackExecutor.shutdownNow()
                val statusValue = (-createResult).toInt()
                throw nativeStatusException("create", statusValue)
            }
            session.nativeHandle.set(createResult)
            val observedCallbackHandle = session.callbackHandle.get()
            if (observedCallbackHandle == 0L) {
                session.callbackHandle.compareAndSet(0, createResult)
            }
            if (session.callbackHandle.get() != createResult) {
                NativeBindings.close(createResult)
                session.nativeHandle.set(0)
                session.callbackExecutor.shutdownNow()
                throw KWebNativeException(
                    code = "native.session.handle-mismatch",
                    details = mapOf(
                        "created" to createResult.toString(),
                        "callback" to session.callbackHandle.get().toString(),
                    ),
                    message = "Native create and callback handles do not match.",
                )
            }

            try {
                if (!session.opened.await(CALLBACK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    session.abortOpen(
                        KWebNativeException(
                            code = "native.callback.open-timeout",
                            details = mapOf("timeoutMs" to CALLBACK_TIMEOUT.toMillis().toString()),
                            message = "The native session-opened callback timed out.",
                        ),
                    )
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                session.abortOpen(
                    KWebNativeException(
                        code = "native.callback.open-interrupted",
                        details = emptyMap(),
                        message = "Waiting for native session open was interrupted.",
                        cause = error,
                    ),
                )
            }
            session.callbackFailure.get()?.let(session::abortOpen)
            if (session.mutableLifecycle.value != KWebLifecycleState.OPEN) {
                session.abortOpen(
                    KWebNativeException(
                        code = "native.callback.open-state-invalid",
                        details = mapOf("state" to session.mutableLifecycle.value.name),
                        message = "The native session did not reach the open state.",
                    ),
                )
            }
            return session
        }

        internal fun liveNativeSessionCount(): Long = NativeBindings.liveSessionCount()
    }
}
