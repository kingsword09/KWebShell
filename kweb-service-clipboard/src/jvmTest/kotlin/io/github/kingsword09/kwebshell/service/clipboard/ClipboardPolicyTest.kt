package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClipboardPolicyTest {
    @Test
    fun normalizesPlainTextAndAllowsEmptyValue() {
        assertContentEquals(
            "one\ntwo".toByteArray(StandardCharsets.UTF_8),
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_PLAIN, "one\r\ntwo".toByteArray(StandardCharsets.UTF_8)),
        )
        assertContentEquals(
            ByteArray(0),
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_PLAIN, ByteArray(0)),
        )
    }

    @Test
    fun rejectsUnsafeHtmlAndRtf() {
        assertContentEquals(
            "<p><strong>safe</strong></p>".toByteArray(StandardCharsets.UTF_8),
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_HTML, "<p><strong>safe</strong></p>".toByteArray(StandardCharsets.UTF_8)),
        )
        assertPolicy(KWebClipboardErrorCode.HTML_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_HTML, "<script>alert(1)</script>".toByteArray(StandardCharsets.UTF_8))
        }
        assertPolicy(KWebClipboardErrorCode.HTML_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_HTML, "<a href=\"https://example.com\">link</a>".toByteArray(StandardCharsets.UTF_8))
        }
        assertContentEquals(
            "{\\rtf1\\ansi safe}".toByteArray(StandardCharsets.ISO_8859_1),
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_RTF, "{\\rtf1\\ansi safe}".toByteArray(StandardCharsets.ISO_8859_1)),
        )
        assertPolicy(KWebClipboardErrorCode.RTF_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_RTF, "{\\rtf1\\pict unsafe}".toByteArray(StandardCharsets.ISO_8859_1))
        }
    }

    @Test
    fun validatesUriListAndRejectsControls() {
        assertContentEquals(
            "https://example.com\nfile:///tmp/example".toByteArray(StandardCharsets.UTF_8),
            ClipboardPolicy.normalize(KWebClipboardFormat.URI_LIST, "https://example.com\r\nfile:///tmp/example\n".toByteArray(StandardCharsets.UTF_8)),
        )
        assertPolicy(KWebClipboardErrorCode.URI_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.URI_LIST, "# comment\nhttps://example.com".toByteArray(StandardCharsets.UTF_8))
        }
        assertPolicy(KWebClipboardErrorCode.URI_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.URI_LIST, "https://user:pass@example.com".toByteArray(StandardCharsets.UTF_8))
        }
        assertPolicy(KWebClipboardErrorCode.URI_POLICY) {
            ClipboardPolicy.normalize(KWebClipboardFormat.URI_LIST, "javascript:alert(1)".toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun rejectsMalformedUtf8() {
        val error = assertFailsWith<KWebNativeException> {
            ClipboardPolicy.normalize(KWebClipboardFormat.TEXT_PLAIN, byteArrayOf(0xc3.toByte(), 0x28))
        }
        assertEquals(KWebClipboardErrorCode.PAYLOAD_INVALID, error.code)
    }

    private fun assertPolicy(code: String, block: () -> Unit) {
        val error = assertFailsWith<KWebNativeException> { block() }
        assertEquals(code, error.code)
    }
}
