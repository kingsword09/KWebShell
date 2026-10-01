package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

internal object ShellPolicy {
    private val schemePattern = Regex("[a-z][a-z0-9+.-]{0,31}")
    private val dangerousNested = Regex("(?i)(?:javascript|data|file|vbscript|command|shell):")
    private val dangerousSchemes = setOf(
        "file", "javascript", "data", "vbscript", "about", "blob", "filesystem",
        "command", "shell", "chrome", "devtools",
    )

    fun normalizeExternalUri(request: KWebShellExternalUriRequest, configuration: KWebShellConfiguration): String {
        val uri = request.uri
        val bytes = uri.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > KWEB_SHELL_MAX_URI_BYTES ||
            uri.any { it == '\u0000' || it.code < 0x20 || it == '\\' }
        ) {
            throw failure(KWebShellErrorCode.URI_INVALID, "The URI contains invalid bytes.", "open-external")
        }
        val colon = uri.indexOf(':')
        if (colon <= 0) throw failure(KWebShellErrorCode.URI_INVALID, "The URI has no valid scheme.", "open-external")
        val scheme = uri.substring(0, colon)
        if (!scheme.all { it.code < 128 } || !schemePattern.matches(scheme.lowercase())) {
            throw failure(KWebShellErrorCode.URI_INVALID, "The URI scheme is invalid.", "open-external")
        }
        val normalizedScheme = scheme.lowercase()
        if (normalizedScheme in dangerousSchemes || normalizedScheme !in configuration.allowedExternalSchemes) {
            throw failure(KWebShellErrorCode.SCHEME_DENIED, "The URI scheme is not allowlisted.", "open-external")
        }
        val parsed = try {
            URI(uri)
        } catch (error: Exception) {
            throw failure(KWebShellErrorCode.URI_INVALID, "The URI could not be parsed.", "open-external", error)
        }
        if (parsed.scheme?.lowercase() != normalizedScheme || parsed.rawSchemeSpecificPart.isNullOrEmpty()) {
            throw failure(KWebShellErrorCode.URI_INVALID, "The URI structure is invalid.", "open-external")
        }
        if (normalizedScheme == "http" || normalizedScheme == "https") {
            if (parsed.host.isNullOrBlank() || parsed.rawUserInfo != null || parsed.rawAuthority.isNullOrBlank()) {
                throw failure(KWebShellErrorCode.URI_INVALID, "HTTP(S) URIs require a host and cannot contain user-info.", "open-external")
            }
        }
        if (normalizedScheme == "mailto" && parsed.rawSchemeSpecificPart.startsWith("//")) {
            throw failure(KWebShellErrorCode.URI_INVALID, "Mailto URIs cannot contain an authority.", "open-external")
        }
        val decoded = runCatching { URLDecoder.decode(uri, StandardCharsets.UTF_8) }.getOrDefault(uri)
        if (dangerousNested.containsMatchIn(decoded.substringAfter(':'))) {
            throw failure(KWebShellErrorCode.SCHEME_DENIED, "The URI contains a nested dangerous scheme.", "open-external")
        }
        return uri
    }

    fun validateHandle(token: String, operation: String) {
        if (token.toByteArray(StandardCharsets.UTF_8).isEmpty() ||
            token.toByteArray(StandardCharsets.UTF_8).size > KWEB_SHELL_MAX_HANDLE_BYTES ||
            !token.matches(Regex("[A-Za-z0-9_-]{16,512}"))
        ) {
            throw failure(KWebShellErrorCode.HANDLE_INVALID, "The resource handle token is invalid.", operation)
        }
    }

    fun failure(code: String, message: String, operation: String, cause: Throwable? = null): KWebNativeException =
        KWebNativeException(
            code = code,
            details = mapOf("service" to KWebShell.DESCRIPTOR.id, "operation" to operation),
            message = message,
            cause = cause,
        )
}
