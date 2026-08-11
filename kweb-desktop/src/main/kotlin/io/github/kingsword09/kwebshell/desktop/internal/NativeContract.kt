package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebNativeException

internal const val NATIVE_ABI_VERSION: Int = 1

internal enum class NativeStatus(
    val value: Int,
    val id: String,
) {
    OK(0, "ok"),
    INVALID_ARGUMENT(1, "invalid-argument"),
    ABI_MISMATCH(2, "abi-mismatch"),
    ALLOCATION_FAILED(3, "allocation-failed"),
    THREAD_START_FAILED(4, "thread-start-failed"),
    HANDLE_EXHAUSTED(5, "handle-exhausted"),
    INVALID_HANDLE(6, "invalid-handle"),
    SESSION_CLOSING(7, "session-closing"),
    INVALID_TEXT_ENCODING(8, "invalid-text-encoding"),
    TEXT_TOO_LARGE(9, "text-too-large"),
    INVALID_DIMENSIONS(10, "invalid-dimensions"),
    REENTRANT_CLOSE(11, "reentrant-close"),
    CALLBACK_FAILED(12, "callback-failed"),
    INTERNAL_ERROR(13, "internal-error"),
    ;

    companion object {
        fun fromValue(value: Int): NativeStatus? = entries.singleOrNull { it.value == value }
    }
}

internal enum class NativeEventType(val value: Int) {
    SESSION_OPENED(1),
    NAVIGATION_REQUESTED(2),
    VIEWPORT_CHANGED(3),
    SESSION_CLOSED(4),
    ;

    companion object {
        fun fromValue(value: Int): NativeEventType? = entries.singleOrNull { it.value == value }
    }
}

internal data class NativeContractEvent(
    val type: NativeEventType,
    val handle: Long,
    val sequence: Long,
    val text: String,
    val width: Int,
    val height: Int,
)

internal fun nativeStatusException(
    operation: String,
    value: Int,
    details: Map<String, String> = emptyMap(),
): KWebNativeException {
    val status = NativeStatus.fromValue(value)
    val statusId = status?.id ?: "unknown-status"
    return KWebNativeException(
        code = "native.abi.$statusId",
        details = details + mapOf(
            "operation" to operation,
            "status" to value.toString(),
        ),
        message = "Native operation '$operation' failed with status '$statusId' ($value).",
    )
}
