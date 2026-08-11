package io.github.kingsword09.kwebshell.core

public abstract class KWebException(
    public val code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    public val details: Map<String, String> = details.toMap()
}

public class KWebConfigurationException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)
