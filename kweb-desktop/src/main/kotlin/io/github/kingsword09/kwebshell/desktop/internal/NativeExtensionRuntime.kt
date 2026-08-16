package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntime
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeDispatchState
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeException
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeOperation
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeOutcome
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeRequest
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeResult
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeState
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.suspendCancellableCoroutine

internal class NativeExtensionRuntime(
    private val engine: NativeEngine,
    private val browser: NativeBrowser,
) : KWebExtensionRuntime {
    override suspend fun execute(request: KWebExtensionRuntimeRequest): KWebExtensionRuntimeResult =
        suspendCancellableCoroutine { continuation ->
            val callbackHandle = AtomicLong(0)
            val completed = AtomicBoolean(false)
            val sink = NativeExtensionResultSink { operationHandle, engineHandle, browserHandle, operationValue,
                outcomeValue, stateValue, extensionId, version, path, errorCode, errorMessage ->
                if (!completed.compareAndSet(false, true)) {
                    return@NativeExtensionResultSink
                }
                val result = try {
                    validateResult(
                        request = request,
                        callbackHandle = callbackHandle,
                        operationHandle = operationHandle,
                        engineHandle = engineHandle,
                        browserHandle = browserHandle,
                        operationValue = operationValue,
                        outcomeValue = outcomeValue,
                        stateValue = stateValue,
                        extensionId = extensionId,
                        version = version,
                        path = path,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                    )
                } catch (error: KWebExtensionRuntimeException) {
                    continuation.resumeWith(Result.failure(error))
                    return@NativeExtensionResultSink
                }
                continuation.resumeWith(Result.success(result))
            }
            val nativeHandle = NativeBindings.extensionStart(
                browser = browser.requireLiveHandle("extension-${request.operation.name.lowercase()}"),
                sink = sink,
                operation = request.operation.nativeValue,
                extensionId = request.extensionId,
                expectedVersion = request.expectedVersion.orEmpty(),
                extensionPath = request.extensionPath?.toString().orEmpty(),
            )
            if (nativeHandle <= 0L) {
                completed.set(true)
                val status = if (nativeHandle < 0L) {
                    (-nativeHandle).toInt()
                } else {
                    NativeStatus.INTERNAL_ERROR.value
                }
                val nativeFailure = nativeStatusException(
                    operation = "extension-${request.operation.name.lowercase()}-start",
                    value = status,
                    details = mapOf("extensionId" to request.extensionId),
                )
                continuation.resumeWith(
                    Result.failure(
                        KWebExtensionRuntimeException(
                            dispatchState = KWebExtensionRuntimeDispatchState.NOT_DISPATCHED,
                            code = nativeFailure.code,
                            details = nativeFailure.details,
                            message = nativeFailure.message ?: "The native extension operation was not dispatched.",
                            cause = nativeFailure,
                        ),
                    ),
                )
                return@suspendCancellableCoroutine
            }
            callbackHandle.compareAndSet(0, nativeHandle)
            if (callbackHandle.get() != nativeHandle) {
                if (completed.compareAndSet(false, true)) {
                    continuation.resumeWith(
                        Result.failure(
                            runtimeResultFailure(
                                request,
                                "native.extension.callback-handle-mismatch",
                                mapOf(
                                    "started" to nativeHandle.toString(),
                                    "callback" to callbackHandle.get().toString(),
                                ),
                            ),
                        ),
                    )
                }
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) {
                    val status = NativeBindings.extensionCancel(nativeHandle)
                    if (status != NativeStatus.OK.value &&
                        status != NativeStatus.EXTENSION_OPERATION_ACTIVE.value &&
                        status != NativeStatus.EXTENSION_OPERATION_NOT_FOUND.value
                    ) {
                        browser.recordExtensionCancellationFailure(nativeHandle, status)
                    }
                }
            }
        }

    private fun validateResult(
        request: KWebExtensionRuntimeRequest,
        callbackHandle: AtomicLong,
        operationHandle: Long,
        engineHandle: Long,
        browserHandle: Long,
        operationValue: Int,
        outcomeValue: Int,
        stateValue: Int,
        extensionId: String,
        version: String,
        path: String,
        errorCode: String,
        errorMessage: String,
    ): KWebExtensionRuntimeResult {
        callbackHandle.compareAndSet(0, operationHandle)
        val operation = operationValue.toRuntimeOperation()
        val outcome = outcomeValue.toRuntimeOutcome()
        val state = stateValue.toRuntimeState()
        if (operationHandle <= 0L || callbackHandle.get() != operationHandle ||
            !engine.ownsHandle(engineHandle) || !browser.ownsHandle(browserHandle) ||
            operation != request.operation || extensionId != request.extensionId
        ) {
            throw runtimeResultFailure(
                request,
                "native.extension.callback-identity-mismatch",
                mapOf(
                    "operationHandle" to operationHandle.toString(),
                    "engine" to engineHandle.toString(),
                    "browser" to browserHandle.toString(),
                    "operation" to operation.name,
                    "extensionId" to extensionId,
                ),
            )
        }
        val resultPath = parseResultPath(request, path)
        return KWebExtensionRuntimeResult(
            operation = operation,
            outcome = outcome,
            state = state,
            extensionId = extensionId,
            version = version.ifEmpty { null },
            path = resultPath,
            errorCode = errorCode.ifEmpty { null },
            errorMessage = errorMessage.ifEmpty { null },
        )
    }

    private fun parseResultPath(request: KWebExtensionRuntimeRequest, value: String): Path? {
        if (value.isEmpty()) return null
        val path = try {
            Path.of(value)
        } catch (error: InvalidPathException) {
            throw runtimeResultFailure(
                request,
                "native.extension.callback-path-invalid",
                mapOf("path" to value),
                error,
            )
        }
        if (!path.isAbsolute || path.normalize() != path) {
            throw runtimeResultFailure(
                request,
                "native.extension.callback-path-invalid",
                mapOf("path" to value),
            )
        }
        return path
    }

    private fun runtimeResultFailure(
        request: KWebExtensionRuntimeRequest,
        code: String,
        details: Map<String, String>,
        cause: Throwable? = null,
    ): KWebExtensionRuntimeException = KWebExtensionRuntimeException(
        dispatchState = KWebExtensionRuntimeDispatchState.MAY_HAVE_DISPATCHED,
        code = code,
        details = details + mapOf(
            "expectedOperation" to request.operation.name,
            "expectedExtensionId" to request.extensionId,
        ),
        message = "The native extension result violates the Kotlin runtime contract.",
        cause = cause,
    )

    internal companion object {
        internal fun liveNativeOperationCount(): Long = NativeBindings.liveExtensionOperationCount()
    }
}

private val KWebExtensionRuntimeOperation.nativeValue: Int
    get() = ordinal + 1

private fun Int.toRuntimeOperation(): KWebExtensionRuntimeOperation =
    KWebExtensionRuntimeOperation.entries.getOrNull(this - 1) ?: throw KWebExtensionRuntimeException(
        dispatchState = KWebExtensionRuntimeDispatchState.MAY_HAVE_DISPATCHED,
        code = "native.extension.callback-operation-unknown",
        details = mapOf("operation" to toString()),
        message = "The native extension callback contains an unknown operation.",
    )

private fun Int.toRuntimeOutcome(): KWebExtensionRuntimeOutcome =
    KWebExtensionRuntimeOutcome.entries.getOrNull(this - 1) ?: throw KWebExtensionRuntimeException(
        dispatchState = KWebExtensionRuntimeDispatchState.MAY_HAVE_DISPATCHED,
        code = "native.extension.callback-outcome-unknown",
        details = mapOf("outcome" to toString()),
        message = "The native extension callback contains an unknown outcome.",
    )

private fun Int.toRuntimeState(): KWebExtensionRuntimeState =
    KWebExtensionRuntimeState.entries.getOrNull(this) ?: throw KWebExtensionRuntimeException(
        dispatchState = KWebExtensionRuntimeDispatchState.MAY_HAVE_DISPATCHED,
        code = "native.extension.callback-state-unknown",
        details = mapOf("state" to toString()),
        message = "The native extension callback contains an unknown state.",
    )
