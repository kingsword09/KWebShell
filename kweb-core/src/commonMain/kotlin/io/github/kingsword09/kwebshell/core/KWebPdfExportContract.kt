/**
 * The RFC 0026 PDF output contract: one page exported into a bounded, scoped
 * PDF artifact.
 *
 * The artifact is engine-owned: it lives inside the Profile directory, is
 * readable only through bounded reads, and is removed when the document closes.
 * Interactive printing, printer enumeration, and silent print jobs are separate
 * objectives and are not published here.
 */
package io.github.kingsword09.kwebshell.core

/** The paper size one export renders to, in inches. */
public sealed interface KWebPdfPaper {
    public val widthInches: Double
    public val heightInches: Double

    public data object A4 : KWebPdfPaper {
        override val widthInches: Double = 8.27
        override val heightInches: Double = 11.69
    }

    public data object LETTER : KWebPdfPaper {
        override val widthInches: Double = 8.5
        override val heightInches: Double = 11.0
    }

    public data class Custom(
        override val widthInches: Double,
        override val heightInches: Double,
    ) : KWebPdfPaper {
        init {
            if (!widthInches.isFinite() || !heightInches.isFinite() ||
                widthInches < 1.0 || widthInches > 100.0 ||
                heightInches < 1.0 || heightInches > 100.0
            ) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.SETTINGS_INVALID,
                    details = mapOf(
                        "field" to "paper",
                        "widthInches" to widthInches.toString(),
                        "heightInches" to heightInches.toString(),
                    ),
                    message = "Custom paper dimensions must be finite and between 1 and 100 inches.",
                )
            }
        }
    }
}

/** The page margins of one export, in inches. */
public sealed interface KWebPdfMargins {
    /** Chromium's own default margins. */
    public data object DEFAULT : KWebPdfMargins

    /** No margin at all. */
    public data object NONE : KWebPdfMargins

    public data class Custom(
        public val topInches: Double,
        public val rightInches: Double,
        public val bottomInches: Double,
        public val leftInches: Double,
    ) : KWebPdfMargins {
        init {
            val values = listOf(topInches, rightInches, bottomInches, leftInches)
            if (values.any { !it.isFinite() || it < 0.0 || it > 10.0 }) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.SETTINGS_INVALID,
                    details = mapOf("field" to "margins"),
                    message = "Custom margins must be finite and between 0 and 10 inches.",
                )
            }
        }
    }
}

/** One validated PDF export request. */
public data class KWebPdfExportRequest(
    public val paper: KWebPdfPaper = KWebPdfPaper.A4,
    public val landscape: Boolean = false,
    public val printBackground: Boolean = false,
    public val scale: Double = 1.0,
    public val margins: KWebPdfMargins = KWebPdfMargins.DEFAULT,
    public val displayHeaderFooter: Boolean = false,
    public val headerTemplate: String = "",
    public val footerTemplate: String = "",
    public val pageRanges: String = "",
    public val preferCssPageSize: Boolean = false,
) {
    init {
        if (!scale.isFinite() || scale < MINIMUM_SCALE || scale > MAXIMUM_SCALE) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.SETTINGS_INVALID,
                details = mapOf("field" to "scale", "scale" to scale.toString()),
                message = "A PDF export scale must be finite and between $MINIMUM_SCALE and $MAXIMUM_SCALE.",
            )
        }
        if (headerTemplate.encodeToByteArray().size > MAXIMUM_TEMPLATE_BYTES ||
            footerTemplate.encodeToByteArray().size > MAXIMUM_TEMPLATE_BYTES
        ) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.SETTINGS_INVALID,
                details = mapOf("field" to "template"),
                message = "A PDF header or footer template must not exceed $MAXIMUM_TEMPLATE_BYTES UTF-8 bytes.",
            )
        }
        if (!displayHeaderFooter && (headerTemplate.isNotEmpty() || footerTemplate.isNotEmpty())) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.SETTINGS_INVALID,
                details = mapOf("field" to "displayHeaderFooter"),
                message = "Header and footer templates are only used when displayHeaderFooter is true.",
            )
        }
        if (pageRanges.encodeToByteArray().size > MAXIMUM_PAGE_RANGES_BYTES || !isValidPageRanges(pageRanges)) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.SETTINGS_INVALID,
                details = mapOf("field" to "pageRanges", "value" to pageRanges),
                message = "Page ranges must use Chromium's one-based form, for example '1-3, 5'.",
            )
        }
    }

    public companion object {
        public const val MINIMUM_SCALE: Double = 0.1
        public const val MAXIMUM_SCALE: Double = 2.0
        public const val MAXIMUM_TEMPLATE_BYTES: Int = 8 * 1024
        public const val MAXIMUM_PAGE_RANGES_BYTES: Int = 256

        private fun isValidPageRanges(value: String): Boolean {
            if (value.isEmpty()) return true
            return value.split(',').all { part -> isValidPageRange(part.trim()) }
        }

        private fun isValidPageRange(part: String): Boolean {
            val bounds = part.split('-')
            if (bounds.isEmpty() || bounds.size > 2) return false
            if (bounds.any { bound -> bound.isEmpty() || bound.any { it !in '0'..'9' } }) return false
            val start = bounds[0].toIntOrNull() ?: return false
            if (start < 1) return false
            if (bounds.size == 1) return true
            val end = bounds[1].toIntOrNull() ?: return false
            return end >= start
        }
    }
}

/** One bounded read from an exported PDF artifact. */
public data class KWebPdfReadResult(
    public val bytes: ByteArray,
    public val eof: Boolean,
) {
    init {
        if (bytes.size > KWEB_PDF_MAXIMUM_READ_BYTES) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.ARTIFACT_INVALID,
                details = mapOf("field" to "bytes"),
                message = "A PDF artifact read exceeds the bounded transfer size.",
            )
        }
    }
}

public const val KWEB_PDF_MAXIMUM_READ_BYTES: Int = 1024 * 1024

/**
 * One engine-owned PDF export. Closing it removes the artifact.
 */
public interface KWebPdfDocument : AutoCloseable {
    public val name: String
    public val sizeBytes: Long
    public val sha256: String

    public suspend fun read(offset: Long, length: Int): KWebPdfReadResult

    override fun close()

    public companion object {
        public const val MAXIMUM_SIZE_BYTES: Long = 64L * 1024L * 1024L
    }
}

/** Stable PDF export error identifiers. */
public object KWebPdfErrorCode {
    public const val SETTINGS_INVALID: String = "pdf.settings-invalid"
    public const val EXPORT_ACTIVE: String = "pdf.export-active"
    public const val NATIVE_FAILED: String = "pdf.native-failed"
    public const val ARTIFACT_INVALID: String = "pdf.artifact-invalid"
    public const val ARTIFACT_TOO_LARGE: String = "pdf.artifact-too-large"
    public const val PATH_INVALID: String = "pdf.path-invalid"
}

public class KWebPdfExportException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)
