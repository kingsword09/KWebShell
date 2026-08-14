package io.github.kingsword09.kwebshell.extensions

import io.github.kingsword09.kwebshell.core.KWebException

public class KWebExtensionVerificationException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

internal fun extensionFailure(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String = "Manifest V3 extension verification failed with '$code'.",
    cause: Throwable? = null,
): Nothing = throw KWebExtensionVerificationException(code, details, message, cause)
