package io.github.kingsword09.kwebshell.service.printers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The published RFC 0046 printer-discovery contract. */
class KWebPrintersContractTest {

    @Test
    fun theDescriptorPublishesOneHostOnlyListOperation() {
        val descriptor = KWebPrinters.DESCRIPTOR
        assertEquals("printers", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(1, descriptor.operations.size)
        val operation = descriptor.operations.single()
        assertEquals("list", operation.id)
        assertNull(operation.rendererPermission)
        assertEquals(3, descriptor.supportedTargets.size)
        assertTrue(descriptor.requiredCapabilities.isEmpty())
    }

    @Test
    fun aPrinterNameIsBounded() {
        KWebPrinterName("Office printer")
        assertEquals(
            KWebPrintersErrorCode.CONTRACT_INVALID,
            assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
                KWebPrinterName("")
            }.code,
        )
        assertEquals(
            KWebPrintersErrorCode.CONTRACT_INVALID,
            assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
                KWebPrinterName("a".repeat(257))
            }.code,
        )
    }

    @Test
    fun factsKeepAbsentValuesAbsentAndBoundTheMediaList() {
        assertEquals(
            KWebPrinterFacts(colorSupported = null, duplexSupported = null, mediaSizes = emptyList()),
            KWebPrinterFacts.ABSENT,
        )
        KWebPrinterFacts(
            colorSupported = true,
            duplexSupported = false,
            mediaSizes = listOf("iso_a4_210x297mm"),
        )
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebPrinterFacts(colorSupported = null, duplexSupported = null, mediaSizes = listOf("a4", "a4"))
        }
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebPrinterFacts(
                colorSupported = null,
                duplexSupported = null,
                mediaSizes = (0 until KWEB_PRINTERS_MAXIMUM_MEDIA + 1).map { "media-$it" },
            )
        }
    }

    @Test
    fun aSnapshotRejectsDuplicateNamesAndMultipleDefaults() {
        val a4 = KWebPrinterFacts(colorSupported = null, duplexSupported = null, mediaSizes = listOf("iso_a4_210x297mm"))
        fun printer(name: String, isDefault: Boolean = false) = KWebPrinter(
            name = KWebPrinterName(name),
            info = null,
            location = null,
            isDefault = isDefault,
            state = KWebPrinterState.IDLE,
            facts = a4.copy(),
        )
        val snapshot = KWebPrinterSnapshot(
            provider = KWEB_PRINTERS_PROVIDER,
            printers = listOf(printer("A", isDefault = true), printer("B")),
        )
        assertEquals("A", snapshot.defaultPrinter?.name?.value)
        assertNull(
            KWebPrinterSnapshot(KWEB_PRINTERS_PROVIDER, listOf(printer("A"), printer("B")))
                .defaultPrinter,
        )
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebPrinterSnapshot(KWEB_PRINTERS_PROVIDER, listOf(printer("A"), printer("A")))
        }
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebPrinterSnapshot(
                KWEB_PRINTERS_PROVIDER,
                listOf(printer("A", isDefault = true), printer("B", isDefault = true)),
            )
        }
    }

    @Test
    fun anEmptySnapshotIsAValidObservation() {
        val snapshot = KWebPrinterSnapshot(provider = KWEB_PRINTERS_PROVIDER, printers = emptyList())
        assertTrue(snapshot.printers.isEmpty())
        assertNull(snapshot.defaultPrinter)
    }
}
