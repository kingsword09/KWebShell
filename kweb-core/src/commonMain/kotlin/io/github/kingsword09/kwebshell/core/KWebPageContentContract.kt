/**
 * The RFC 0047 page content contract: one bounded read of the page's HTML source
 * or of its rendered text.
 *
 * Both kinds are read from Chromium's own frame: `source` is the document's
 * serialized HTML and `text` is the display text Chromium reports for the same
 * document. The read is bounded by the caller's declared byte limit and never
 * truncates silently: a shortened document reports `truncated` together with the
 * full size Chromium produced. Only the main frame of the page the application
 * owns is readable, so a page can never read another document's content and no
 * script is executed to obtain either value.
 */
package io.github.kingsword09.kwebshell.core

/** Which document view one read returns. */
public enum class KWebPageContentKind(public val id: String) {
    /** The document's serialized HTML, including markup. */
    SOURCE("source"),

    /** The document's display text, without markup. */
    TEXT("text"),
}

/**
 * One bounded content read.
 *
 * [maximumBytes] bounds what crosses into the application. Chromium materializes
 * the whole document before the engine can observe it, so the limit does not
 * bound Chromium's own allocation; it bounds the transfer and the application's
 * copy of it.
 */
public data class KWebPageContentRequest(
    public val kind: KWebPageContentKind = KWebPageContentKind.SOURCE,
    public val maximumBytes: Int = DEFAULT_MAXIMUM_BYTES,
) {
    init {
        if (maximumBytes < 1 || maximumBytes > MAXIMUM_BYTES) {
            throw KWebPageContentException(
                code = KWebPageContentErrorCode.REQUEST_INVALID,
                details = mapOf("field" to "maximumBytes", "maximumBytes" to maximumBytes.toString()),
                message = "A page content read must request between 1 and $MAXIMUM_BYTES bytes.",
            )
        }
    }

    public companion object {
        public const val DEFAULT_MAXIMUM_BYTES: Int = 256 * 1024

        /** The largest transfer the engine ABI carries for one content field. */
        public const val MAXIMUM_BYTES: Int = 1024 * 1024
    }
}

/**
 * One observed document view.
 *
 * [totalBytes] is the size Chromium produced for the whole document and
 * [text] is the delivered prefix, cut at a UTF-8 character boundary. A read is
 * truncated exactly when [totalBytes] exceeds the delivered size, so a caller
 * can always tell a complete document from a shortened one.
 */
public data class KWebPageContent(
    public val kind: KWebPageContentKind,
    
    /** The frame URL Chromium reported with the delivered document. */
    public val url: String,
    public val text: String,
    public val totalBytes: Long,
    public val truncated: Boolean,
) {
    init {
        val delivered = utf8SizeOf(text)
        if (totalBytes < delivered.toLong()) {
            throw KWebPageContentException(
                code = KWebPageContentErrorCode.CONTRACT_INVALID,
                details = mapOf(
                    "field" to "totalBytes",
                    "totalBytes" to totalBytes.toString(),
                    "deliveredBytes" to delivered.toString(),
                ),
                message = "A page content read cannot deliver more bytes than the document states.",
            )
        }
        if (delivered > KWebPageContentRequest.MAXIMUM_BYTES) {
            throw KWebPageContentException(
                code = KWebPageContentErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "text", "deliveredBytes" to delivered.toString()),
                message = "A page content read cannot deliver more than " +
                    "${KWebPageContentRequest.MAXIMUM_BYTES} bytes.",
            )
        }
        if (truncated != (delivered.toLong() < totalBytes)) {
            throw KWebPageContentException(
                code = KWebPageContentErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "truncated", "truncated" to truncated.toString()),
                message = "A page content read is truncated exactly when it delivered less than " +
                    "the document states.",
            )
        }
    }
}

/** Stable page content error identifiers. */
public object KWebPageContentErrorCode {
    public const val REQUEST_INVALID: String = "page.content-request-invalid"
    public const val CONTRACT_INVALID: String = "page.content-contract-invalid"
    public const val TIMEOUT: String = "page.content-timeout"
}

public class KWebPageContentException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

/**
 * The UTF-8 size of a string, refusing lone surrogates instead of encoding them
 * as a replacement sequence that would misreport the delivered size.
 */
private fun utf8SizeOf(text: String): Int {
    var bytes = 0
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) {
                throw KWebPageContentException(
                    code = KWebPageContentErrorCode.CONTRACT_INVALID,
                    details = mapOf("field" to "text", "reason" to "unpaired-surrogate"),
                    message = "A page content read must carry valid Unicode text.",
                )
            }
            bytes += 4
            index += 2
            continue
        }
        if (character.isLowSurrogate()) {
            throw KWebPageContentException(
                code = KWebPageContentErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "text", "reason" to "unpaired-surrogate"),
                message = "A page content read must carry valid Unicode text.",
            )
        }
        bytes += when {
            character.code < 0x80 -> 1
            character.code < 0x800 -> 2
            else -> 3
        }
        index += 1
    }
    return bytes
}
