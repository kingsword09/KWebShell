package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The published RFC 0047 page source and text contract. */
class KWebPageContentContractTest {

    @Test
    fun theRequestBoundsAreDeclaredAndEnforced() {
        assertEquals(256 * 1024, KWebPageContentRequest.DEFAULT_MAXIMUM_BYTES)
        assertEquals(1024 * 1024, KWebPageContentRequest.MAXIMUM_BYTES)
        assertEquals(
            KWebPageContentKind.SOURCE,
            KWebPageContentRequest().kind,
        )
        KWebPageContentRequest(maximumBytes = 1)
        KWebPageContentRequest(maximumBytes = KWebPageContentRequest.MAXIMUM_BYTES)
        listOf(0, -1, KWebPageContentRequest.MAXIMUM_BYTES + 1).forEach { maximumBytes ->
            assertEquals(
                KWebPageContentErrorCode.REQUEST_INVALID,
                assertFailsWith<KWebPageContentException> {
                    KWebPageContentRequest(maximumBytes = maximumBytes)
                }.code,
                "maximumBytes $maximumBytes must be refused",
            )
        }
    }

    @Test
    fun aCompleteReadReportsTheWholeDocument() {
        val content = KWebPageContent(
            kind = KWebPageContentKind.SOURCE,
            url = "http://127.0.0.1:8080/page",
            text = "<html><body><p>hello</p></body></html>",
            totalBytes = 38,
            truncated = false,
        )
        assertEquals(KWebPageContentKind.SOURCE, content.kind)
        assertEquals(38L, content.totalBytes)
        assertFalse(content.truncated)
    }

    @Test
    fun aTruncatedReadReportsItsFullSize() {
        val content = KWebPageContent(
            kind = KWebPageContentKind.TEXT,
            url = "",
            text = "hello",
            totalBytes = 11,
            truncated = true,
        )
        assertTrue(content.truncated)
        assertEquals(11L, content.totalBytes)
    }

    @Test
    fun aReadThatContradictsItsOwnSizeIsRefused() {
        // Delivered more bytes than the document claims to contain.
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(KWebPageContentKind.SOURCE, "u", "hello", 4, false)
            }.code,
        )
        // Claimed complete while the delivered prefix is shorter than the document.
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(KWebPageContentKind.SOURCE, "u", "hello", 6, false)
            }.code,
        )
        // Claimed truncated while nothing was left out.
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(KWebPageContentKind.SOURCE, "u", "hello", 5, true)
            }.code,
        )
        // Negative document size.
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(KWebPageContentKind.SOURCE, "u", "", -1, false)
            }.code,
        )
    }

    @Test
    fun aReadAboveTheTransferCeilingIsRefused() {
        val oversized = "a".repeat(KWebPageContentRequest.MAXIMUM_BYTES + 1)
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(
                    kind = KWebPageContentKind.SOURCE,
                    url = "u",
                    text = oversized,
                    totalBytes = oversized.length.toLong(),
                    truncated = false,
                )
            }.code,
        )
    }

    @Test
    fun loneSurrogatesAreRefusedInsteadOfBeingReencoded() {
        assertEquals(
            KWebPageContentErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageContentException> {
                KWebPageContent(
                    kind = KWebPageContentKind.TEXT,
                    url = "u",
                    text = "bad\uD800text",
                    totalBytes = 20,
                    truncated = true,
                )
            }.code,
        )
    }

    @Test
    fun multiByteTextIsMeasuredInUtf8Bytes() {
        // Two code points, three bytes each, and one surrogate pair of four bytes.
        val text = "\u4f60\u597d\uD83D\uDE00"
        val content = KWebPageContent(
            kind = KWebPageContentKind.TEXT,
            url = "u",
            text = text,
            totalBytes = 10,
            truncated = false,
        )
        assertEquals(10L, content.totalBytes)
        assertFalse(content.truncated)
    }
}
