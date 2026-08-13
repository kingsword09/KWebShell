package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebNativeException

internal const val NATIVE_ABI_VERSION: Int = 5

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
    ENGINE_LIBRARY_LOAD_FAILED(14, "engine-library-load-failed"),
    ENGINE_SYMBOL_MISSING(15, "engine-symbol-missing"),
    CEF_RUNTIME_LOAD_FAILED(16, "cef-runtime-load-failed"),
    CEF_RUNTIME_MISMATCH(17, "cef-runtime-mismatch"),
    PATH_REQUIRED(18, "path-required"),
    PATH_NOT_ABSOLUTE(19, "path-not-absolute"),
    PATH_NOT_FOUND(20, "path-not-found"),
    PATH_TYPE_INVALID(21, "path-type-invalid"),
    PATH_MISMATCH(22, "path-mismatch"),
    PATH_NOT_WRITABLE(23, "path-not-writable"),
    PLATFORM_INITIALIZATION_FAILED(24, "platform-initialization-failed"),
    ENGINE_ALREADY_EXISTS(25, "engine-already-exists"),
    ENGINE_RESTART_FORBIDDEN(26, "engine-restart-forbidden"),
    WRONG_THREAD(27, "wrong-thread"),
    CEF_INITIALIZE_FAILED(28, "cef-initialize-failed"),
    ENGINE_CLOSING(29, "engine-closing"),
    ENGINE_HAS_LIVE_BROWSERS(30, "engine-has-live-browsers"),
    PROFILE_PATH_INVALID(31, "profile-path-invalid"),
    PARENT_SURFACE_INVALID(32, "parent-surface-invalid"),
    BROWSER_CREATE_FAILED(33, "browser-create-failed"),
    BROWSER_NOT_READY(34, "browser-not-ready"),
    BROWSER_CLOSING(35, "browser-closing"),
    CEF_UI_TASK_FAILED(36, "cef-ui-task-failed"),
    NAVIGATION_INVALID(37, "navigation-invalid"),
    REMOTE_DEBUGGING_PORT_INVALID(38, "remote-debugging-port-invalid"),
    REMOTE_DEBUGGING_PORT_UNAVAILABLE(39, "remote-debugging-port-unavailable"),
    DEVTOOLS_ALREADY_OPEN(40, "devtools-already-open"),
    DEVTOOLS_NOT_OPEN(41, "devtools-not-open"),
    DEVTOOLS_OPEN_FAILED(42, "devtools-open-failed"),
    DEVTOOLS_CLOSING(43, "devtools-closing"),
    BRIDGE_ORIGIN_INVALID(44, "bridge-origin-invalid"),
    BRIDGE_REQUEST_NOT_FOUND(45, "bridge-request-not-found"),
    BRIDGE_RESPONSE_INVALID(46, "bridge-response-invalid"),
    ;

    companion object {
        fun fromValue(value: Int): NativeStatus? = entries.singleOrNull { it.value == value }
    }
}

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
