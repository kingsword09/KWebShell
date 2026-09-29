package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebNativeException

internal const val NATIVE_ABI_VERSION: Int = 16

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
    EXTENSION_RUNTIME_ABI_MISSING(47, "extension-runtime-abi-missing"),
    EXTENSION_RUNTIME_ABI_MISMATCH(48, "extension-runtime-abi-mismatch"),
    EXTENSION_OPERATION_INVALID(49, "extension-operation-invalid"),
    EXTENSION_OPERATION_ACTIVE(50, "extension-operation-active"),
    EXTENSION_OPERATION_NOT_FOUND(51, "extension-operation-not-found"),
    EXTENSION_RESULT_INVALID(52, "extension-result-invalid"),
    PAGE_REQUEST_NOT_FOUND(53, "page-request-not-found"),
    PAGE_REQUEST_INVALID(54, "page-request-invalid"),
    PAGE_OPERATION_PENDING(55, "page-operation-pending"),
    PROFILE_DATA_OPERATION_ACTIVE(56, "profile-data-operation-active"),
    PROFILE_DATA_NOT_SUPPORTED(57, "profile-data-not-supported"),
    PROFILE_DATA_RESULT_INVALID(58, "profile-data-result-invalid"),
    PROFILE_DATA_TIMEOUT(59, "profile-data-timeout"),
    NETWORK_POLICY_INVALID(60, "network-policy-invalid"),
    NETWORK_PROXY_UNAVAILABLE(61, "network-proxy-unavailable"),
    NETWORK_OPERATION_PENDING(62, "network-operation-pending"),
    NETWORK_USER_AGENT_REQUIRES_PROFILE_REOPEN(63, "network-user-agent-requires-profile-reopen"),
    NETWORK_PROFILE_CLOSING(64, "network-profile-closing"),
    NETWORK_RUNTIME_CAPABILITY_MISSING(65, "network-runtime-capability-missing"),
    NETWORK_PROXY_INVALID(66, "network-proxy-invalid"),
    NETWORK_HEADER_FORBIDDEN(67, "network-header-forbidden"),
    NETWORK_REDIRECT_INVALID(68, "network-redirect-invalid"),
    NETWORK_POLICY_LIMIT_EXCEEDED(69, "network-policy-limit-exceeded"),
    PROFILE_CONTEXT_INITIALIZATION_FAILED(70, "profile-context-initialization-failed"),
    SECURITY_CHALLENGE_NOT_FOUND(71, "security-challenge-not-found"),
    SECURITY_CHALLENGE_ALREADY_RESOLVED(72, "security-challenge-already-resolved"),
    SECURITY_CHALLENGE_DEADLINE_EXPIRED(73, "security-challenge-deadline-expired"),
    SECURITY_CHALLENGE_CAPACITY_EXCEEDED(74, "security-challenge-capacity-exceeded"),
    SECURITY_CHALLENGE_PROFILE_CLOSING(75, "security-challenge-profile-closing"),
    SECURITY_CHALLENGE_DECISION_INVALID(76, "security-challenge-decision-invalid"),
    SECURITY_TLS_EXPIRY_INVALID(77, "security-tls-expiry-invalid"),
    SECURITY_CLIENT_CERTIFICATE_NOT_OFFERED(79, "security-client-certificate-not-offered"),
    SECURITY_CHALLENGE_CALLBACK_FAILED(81, "security-challenge-callback-failed"),
    SECURITY_STORE_UNAVAILABLE(82, "security-store-unavailable"),
    DOWNLOAD_NOT_FOUND(83, "download-not-found"),
    DOWNLOAD_ALREADY_TERMINAL(84, "download-already-terminal"),
    DOWNLOAD_CONTROL_INVALID(85, "download-control-invalid"),
    DOWNLOAD_PROFILE_CLOSING(86, "download-profile-closing"),
    DOWNLOAD_CAPABILITY_MISSING(87, "download-capability-missing"),
    DOWNLOAD_LIMIT_EXCEEDED(88, "download-limit-exceeded"),
    ;

    companion object {
        fun fromValue(value: Int): NativeStatus? = entries.singleOrNull { it.value == value }
    }
}

internal fun securityChallengeStatusException(
    operation: String,
    value: Int,
    details: Map<String, String> = emptyMap(),
): KWebNativeException {
    val code = when (value) {
        NativeStatus.SECURITY_CHALLENGE_NOT_FOUND.value -> "security.challenge.not-found"
        NativeStatus.SECURITY_CHALLENGE_ALREADY_RESOLVED.value ->
            "security.challenge.already-resolved"
        NativeStatus.SECURITY_CHALLENGE_DEADLINE_EXPIRED.value ->
            "security.challenge.deadline-expired"
        NativeStatus.SECURITY_CHALLENGE_CAPACITY_EXCEEDED.value ->
            "security.challenge.capacity-exceeded"
        NativeStatus.SECURITY_CHALLENGE_PROFILE_CLOSING.value ->
            "security.challenge.profile-closing"
        NativeStatus.SECURITY_CHALLENGE_DECISION_INVALID.value ->
            "security.challenge.decision-invalid"
        NativeStatus.SECURITY_TLS_EXPIRY_INVALID.value -> "security.tls.expiry-invalid"
        NativeStatus.SECURITY_CLIENT_CERTIFICATE_NOT_OFFERED.value ->
            "security.client-certificate.not-offered"
        NativeStatus.SECURITY_CHALLENGE_CALLBACK_FAILED.value ->
            "security.challenge.callback-failed"
        NativeStatus.SECURITY_STORE_UNAVAILABLE.value -> "security.store.unavailable"
        else -> return nativeStatusException(operation, value, details)
    }
    return KWebNativeException(
        code = code,
        details = details + mapOf("operation" to operation, "status" to value.toString()),
        message = "Native security challenge operation '$operation' failed with '$code'.",
    )
}

internal fun nativeStatusException(
    operation: String,
    value: Int,
    details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
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
        cause = cause,
    )
}

internal fun profileNetworkStatusException(
    operation: String,
    value: Int,
    details: Map<String, String> = emptyMap(),
): KWebNativeException {
    val code = when (value) {
        NativeStatus.WRONG_THREAD.value -> "network.operation-wrong-thread"
        NativeStatus.NETWORK_POLICY_INVALID.value -> "network.policy.invalid"
        NativeStatus.NETWORK_PROXY_INVALID.value -> "network.proxy.invalid"
        NativeStatus.NETWORK_HEADER_FORBIDDEN.value -> "network.header.forbidden"
        NativeStatus.NETWORK_REDIRECT_INVALID.value -> "network.redirect.invalid"
        NativeStatus.NETWORK_POLICY_LIMIT_EXCEEDED.value -> "network.policy.limit-exceeded"
        NativeStatus.NETWORK_PROXY_UNAVAILABLE.value -> "network.proxy.unavailable"
        NativeStatus.NETWORK_OPERATION_PENDING.value -> "network.operation-pending"
        NativeStatus.NETWORK_USER_AGENT_REQUIRES_PROFILE_REOPEN.value ->
            "network.user-agent-requires-profile-reopen"
        NativeStatus.NETWORK_PROFILE_CLOSING.value -> "network.profile-closing"
        NativeStatus.NETWORK_RUNTIME_CAPABILITY_MISSING.value ->
            "network.runtime-capability-missing"
        NativeStatus.PROFILE_CONTEXT_INITIALIZATION_FAILED.value ->
            "profile.context-initialization-failed"
        else -> return nativeStatusException(operation, value, details)
    }
    return KWebNativeException(
        code = code,
        details = details + mapOf("operation" to operation, "status" to value.toString()),
        message = "Native Profile network operation '$operation' failed with '$code'.",
    )
}

internal fun downloadStatusException(
    operation: String,
    value: Int,
    details: Map<String, String> = emptyMap(),
): KWebNativeException {
    val code = when (value) {
        NativeStatus.DOWNLOAD_NOT_FOUND.value -> "download.not-found"
        NativeStatus.DOWNLOAD_ALREADY_TERMINAL.value -> "download.already-terminal"
        NativeStatus.DOWNLOAD_CONTROL_INVALID.value -> "download.control-invalid"
        NativeStatus.DOWNLOAD_PROFILE_CLOSING.value -> "download.profile-closing"
        NativeStatus.DOWNLOAD_CAPABILITY_MISSING.value -> "download.native-capability-missing"
        NativeStatus.DOWNLOAD_LIMIT_EXCEEDED.value -> "download.limit-exceeded"
        else -> return nativeStatusException(operation, value, details)
    }
    return KWebNativeException(
        code = code,
        details = details + mapOf("operation" to operation, "status" to value.toString()),
        message = "Native download operation '$operation' failed with '$code'.",
    )
}
