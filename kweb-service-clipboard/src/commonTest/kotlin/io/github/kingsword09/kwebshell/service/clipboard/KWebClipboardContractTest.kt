package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebClipboardContractTest {
    @Test
    fun publishesBoundedApplicationContract() {
        assertEquals("clipboard", KWebClipboard.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebClipboard.DESCRIPTOR.version.toString())
        assertEquals("APPLICATION", KWebClipboard.DESCRIPTOR.scope.name)
        assertEquals(
            setOf("read", "read-payload", "write", "clear", "changes", "close-payload"),
            KWebClipboard.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assertEquals(
            "native.clipboard.read",
            KWebClipboard.DESCRIPTOR.operations.single { it.id == "read" }.rendererPermission,
        )
    }

    @Test
    fun validatesSelectionFormatsAndWriteShape() {
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardReadRequest(KWebClipboardSelection.SYSTEM, emptyList())
        }
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardReadRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(KWebClipboardFormat.TEXT_PLAIN, KWebClipboardFormat.TEXT_PLAIN),
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardWriteRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(KWebClipboardWriteItem(KWebClipboardFormat.TEXT_HTML, KWebClipboardPayloadEncoding.UTF8, "<b>x</b>".encodeToByteArray())),
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardWriteItem(
                KWebClipboardFormat.TEXT_RTF,
                KWebClipboardPayloadEncoding.UTF8,
                byteArrayOf(1),
            )
        }
    }

    @Test
    fun copiesPayloadBytesAndRejectsBounds() {
        val item = KWebClipboardWriteItem(
            KWebClipboardFormat.TEXT_PLAIN,
            KWebClipboardPayloadEncoding.UTF8,
            byteArrayOf(1, 2, 3),
        )
        val bytes = item.bytes
        bytes[0] = 9
        assertContentEquals(byteArrayOf(1, 2, 3), item.bytes)
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardWriteItem(
                KWebClipboardFormat.TEXT_PLAIN,
                KWebClipboardPayloadEncoding.UTF8,
                ByteArray(KWEB_CLIPBOARD_MAX_ITEM_BYTES + 1),
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebClipboardWriteRequest(
                KWebClipboardSelection.SYSTEM,
                listOf(
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_PLAIN,
                        KWebClipboardPayloadEncoding.UTF8,
                        ByteArray(KWEB_CLIPBOARD_MAX_ITEM_BYTES),
                    ),
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_HTML,
                        KWebClipboardPayloadEncoding.UTF8,
                        ByteArray(KWEB_CLIPBOARD_MAX_ITEM_BYTES),
                    ),
                    KWebClipboardWriteItem(
                        KWebClipboardFormat.TEXT_RTF,
                        KWebClipboardPayloadEncoding.RTF_BYTES,
                        ByteArray(KWEB_CLIPBOARD_MAX_ITEM_BYTES),
                    ),
                ),
            )
        }
    }
}
