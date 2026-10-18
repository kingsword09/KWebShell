package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The published RFC 0026 PDF export contract. */
class KWebPdfExportContractTest {

    @Test
    fun theNamedPaperSizesCarryTheirDeclaredDimensions() {
        assertEquals(8.27, KWebPdfPaper.A4.widthInches)
        assertEquals(11.69, KWebPdfPaper.A4.heightInches)
        assertEquals(8.5, KWebPdfPaper.LETTER.widthInches)
        assertEquals(11.0, KWebPdfPaper.LETTER.heightInches)
    }

    @Test
    fun customPaperAndMarginsAreBounded() {
        KWebPdfPaper.Custom(4.0, 6.0)
        KWebPdfMargins.Custom(0.0, 0.5, 1.0, 10.0)
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> { KWebPdfPaper.Custom(0.5, 11.0) }.code,
        )
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> { KWebPdfPaper.Custom(Double.NaN, 11.0) }.code,
        )
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> {
                KWebPdfMargins.Custom(topInches = 11.0, rightInches = 0.0, bottomInches = 0.0, leftInches = 0.0)
            }.code,
        )
    }

    @Test
    fun theExportScaleIsBounded() {
        KWebPdfExportRequest(scale = KWebPdfExportRequest.MINIMUM_SCALE)
        KWebPdfExportRequest(scale = KWebPdfExportRequest.MAXIMUM_SCALE)
        listOf(0.05, 2.5, Double.NaN, Double.POSITIVE_INFINITY).forEach { scale ->
            assertEquals(
                KWebPdfErrorCode.SETTINGS_INVALID,
                assertFailsWith<KWebPdfExportException> { KWebPdfExportRequest(scale = scale) }.code,
                "scale $scale must be refused",
            )
        }
    }

    @Test
    fun templatesRequireHeaderFooterDisplayAndStayBounded() {
        KWebPdfExportRequest(
            displayHeaderFooter = true,
            headerTemplate = "<span class=title></span>",
            footerTemplate = "<span class=pageNumber></span>",
        )
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> {
                KWebPdfExportRequest(headerTemplate = "<span class=title></span>")
            }.code,
        )
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> {
                KWebPdfExportRequest(
                    displayHeaderFooter = true,
                    headerTemplate = "x".repeat(KWebPdfExportRequest.MAXIMUM_TEMPLATE_BYTES + 1),
                )
            }.code,
        )
    }

    @Test
    fun pageRangesUseChromiumsOneBasedGrammar() {
        listOf("", "1", "1-3", "1-3,5", "1-3, 5").forEach { ranges ->
            KWebPdfExportRequest(pageRanges = ranges)
        }
        listOf("-1", "1-", "1,,2", "a1", "1;2", "0x10", "0", "3-1").forEach { ranges ->
            assertEquals(
                KWebPdfErrorCode.SETTINGS_INVALID,
                assertFailsWith<KWebPdfExportException> { KWebPdfExportRequest(pageRanges = ranges) }.code,
                "page ranges '$ranges' must be refused",
            )
        }
        assertEquals(
            KWebPdfErrorCode.SETTINGS_INVALID,
            assertFailsWith<KWebPdfExportException> {
                KWebPdfExportRequest(pageRanges = "1".repeat(KWebPdfExportRequest.MAXIMUM_PAGE_RANGES_BYTES + 1))
            }.code,
        )
    }

    @Test
    fun aReadResultIsBounded() {
        KWebPdfReadResult(ByteArray(4), eof = false)
        assertEquals(
            KWebPdfErrorCode.ARTIFACT_INVALID,
            assertFailsWith<KWebPdfExportException> {
                KWebPdfReadResult(ByteArray(KWEB_PDF_MAXIMUM_READ_BYTES + 1), eof = false)
            }.code,
        )
        assertTrue(KWebPdfDocument.MAXIMUM_SIZE_BYTES >= KWEB_PDF_MAXIMUM_READ_BYTES.toLong())
    }
}
