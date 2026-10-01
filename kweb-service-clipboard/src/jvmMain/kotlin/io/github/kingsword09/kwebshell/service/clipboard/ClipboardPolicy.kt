package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal object ClipboardPolicy {
    fun normalize(format: KWebClipboardFormat, bytes: ByteArray): ByteArray = when (format) {
        KWebClipboardFormat.TEXT_PLAIN -> normalizePlain(bytes)
        KWebClipboardFormat.TEXT_HTML -> normalizeHtml(bytes)
        KWebClipboardFormat.TEXT_RTF -> normalizeRtf(bytes)
        KWebClipboardFormat.URI_LIST -> normalizeUris(bytes)
    }

    private fun normalizePlain(bytes: ByteArray): ByteArray {
        val value = decodeUtf8(bytes, KWebClipboardErrorCode.PAYLOAD_INVALID)
        if ('\u0000' in value) {
            throw policy(KWebClipboardErrorCode.PAYLOAD_INVALID, "Plain clipboard text cannot contain NUL.")
        }
        return normalizeNewlines(value).toByteArray(StandardCharsets.UTF_8)
    }

    private fun normalizeHtml(bytes: ByteArray): ByteArray {
        val value = decodeUtf8(bytes, KWebClipboardErrorCode.HTML_POLICY)
        if (value.isBlank()) {
            throw policy(KWebClipboardErrorCode.HTML_POLICY, "HTML clipboard data cannot be empty.")
        }
        if (UNSAFE_HTML.containsMatchIn(value) || value.contains("<!--")) {
            throw policy(KWebClipboardErrorCode.HTML_POLICY, "The HTML clipboard payload contains an unsafe construct.")
        }
        TAG.findAll(value).forEach { match ->
            val tag = match.groupValues[1].lowercase()
            if (tag !in ALLOWED_TAGS) {
                throw policy(KWebClipboardErrorCode.HTML_POLICY, "The HTML clipboard tag is not allowlisted.")
            }
            val source = match.value
            if (UNSAFE_ATTRIBUTE.containsMatchIn(source)) {
                throw policy(KWebClipboardErrorCode.HTML_POLICY, "The HTML clipboard attribute is not allowlisted.")
            }
        }
        val stripped = TAG.replace(value, "")
        if ('<' in stripped || '>' in stripped) {
            throw policy(KWebClipboardErrorCode.HTML_POLICY, "The HTML clipboard markup is malformed.")
        }
        return normalizeNewlines(value).toByteArray(StandardCharsets.UTF_8)
    }

    private fun normalizeRtf(bytes: ByteArray): ByteArray {
        if (bytes.isEmpty() || bytes.size > KWEB_CLIPBOARD_MAX_ITEM_BYTES) {
            throw policy(KWebClipboardErrorCode.RTF_POLICY, "The RTF clipboard payload is empty or too large.")
        }
        val value = bytes.toString(StandardCharsets.ISO_8859_1)
        if (!value.startsWith("{\\rtf", ignoreCase = true) || UNSAFE_RTF.containsMatchIn(value)) {
            throw policy(KWebClipboardErrorCode.RTF_POLICY, "The RTF clipboard payload is not an allowed data-only document.")
        }
        return bytes.copyOf()
    }

    private fun normalizeUris(bytes: ByteArray): ByteArray {
        val value = normalizeNewlines(decodeUtf8(bytes, KWebClipboardErrorCode.URI_POLICY)).trimEnd('\n')
        if (value.isBlank()) {
            throw policy(KWebClipboardErrorCode.URI_POLICY, "The URI-list clipboard payload cannot be empty.")
        }
        val lines = value.split('\n')
        if (lines.size > 1_024 || lines.any { it.length > 8 * 1024 }) {
            throw policy(KWebClipboardErrorCode.URI_POLICY, "The URI-list clipboard payload exceeds its bounds.")
        }
        lines.forEach { line ->
            if (line.isBlank() || line.startsWith("#") || line.any(Char::isISOControl)) {
                throw policy(KWebClipboardErrorCode.URI_POLICY, "The URI-list contains a comment, blank line, or control.")
            }
            val uri = runCatching { URI(line) }.getOrElse {
                throw policy(KWebClipboardErrorCode.URI_POLICY, "The URI-list contains a malformed URI.", it)
            }
            if (!uri.isAbsolute || uri.userInfo != null || uri.fragment != null ||
                uri.scheme.lowercase() !in setOf("http", "https", "file")
            ) {
                throw policy(KWebClipboardErrorCode.URI_POLICY, "The URI-list contains a disallowed URI.")
            }
        }
        return value.toByteArray(StandardCharsets.UTF_8)
    }

    private fun decodeUtf8(bytes: ByteArray, code: String): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: CharacterCodingException) {
        throw policy(code, "The clipboard payload is not valid UTF-8.", error)
    }

    private fun normalizeNewlines(value: String): String =
        value.replace("\r\n", "\n").replace('\r', '\n')

    private fun policy(code: String, message: String, cause: Throwable? = null): KWebNativeException =
        KWebNativeException(
            code = code,
            details = mapOf("service" to KWebClipboard.DESCRIPTOR.id),
            message = message,
            cause = cause,
        )

    private val TAG = Regex("<\\s*/?\\s*([A-Za-z][A-Za-z0-9]*)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val UNSAFE_HTML = Regex(
        "(?is)<\\s*/?\\s*(script|style|object|embed|form|iframe|frame|svg|math|meta|link)\\b|" +
            "\\bon[a-z0-9_-]+\\s*=|(?:javascript|data|file)\\s*:",
    )
    private val UNSAFE_ATTRIBUTE = Regex(
        "(?is)\\b(on[a-z0-9_-]+|style|src|href|action|formaction|poster|background)\\s*=",
    )
    private val UNSAFE_RTF = Regex(
        "(?is)\\\\(object|pict|field|filetbl|fldinst|hyperlink|result|bin)\\b",
    )
    private val ALLOWED_TAGS = setOf(
        "b", "strong", "i", "em", "u", "s", "br", "p", "div", "span",
        "ul", "ol", "li", "pre", "code", "blockquote",
        "h1", "h2", "h3", "h4", "h5", "h6",
    )
}
