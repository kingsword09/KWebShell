package io.github.kingsword09.kwebshell.service.dialogs

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class KWebDialogsContractTest {
    @Test
    fun descriptorPublishesOnlyScopedDialogOperations() {
        assertEquals("dialogs", KWebDialogs.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebDialogs.DESCRIPTOR.version.toString())
        assertEquals(
            setOf("select-file", "read-file", "write-file", "truncate-file", "close-file"),
            KWebDialogs.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        // write-file mutates caller-provided handles and is gated on a
        // native-verified user gesture; the remaining operations are not.
        assertEquals(
            setOf("write-file"),
            KWebDialogs.DESCRIPTOR.operations.filter { it.requiresUserGesture }.map { it.id }.toSet(),
        )
        assertEquals("open", KWebFileDialogMode.OPEN.id)
        assertEquals(KWebFileDialogMode.SAVE, KWebFileDialogMode.fromId("save"))
    }

    @Test
    fun requestAndFilterValidationRejectsUnsafeValues() {
        assertEquals(setOf("txt", "json"), KWebFileFilter("Text", setOf(".TXT", "json")).extensions)
        assertFailsWith<KWebConfigurationException> { KWebFileFilter("Text", emptySet()) }
        assertFailsWith<KWebConfigurationException> { KWebFileFilter("Text", setOf("../secret")) }
        listOf("txt.", "tar..gz", "*", " txt", ".").forEach { extension ->
            assertFailsWith<KWebConfigurationException> { KWebFileFilter("Text", setOf(extension)) }
        }
        listOf("a:b", "a\\b", "a/b", "a\n", "name.", "name ").forEach { name ->
            assertFailsWith<KWebConfigurationException> {
                KWebFileDialogRequest(KWebFileDialogMode.SAVE, "Pick", defaultName = name)
            }
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Pick", defaultName = "../escape")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileDialogRequest(KWebFileDialogMode.OPEN, "\u0000")
        }
    }

    @Test
    fun transferResultsEnforceByteAndSizeContracts() {
        assertEquals(listOf(0, 255), KWebFileReadResult(listOf(0, 255), eof = true).bytes)
        assertFailsWith<KWebConfigurationException> { KWebFileReadResult(listOf(-1), eof = false) }
        assertFailsWith<KWebConfigurationException> {
            KWebFileReadResult(List(KWEB_DIALOGS_MAX_TRANSFER_BYTES + 1) { 0 }, eof = false)
        }
        assertFailsWith<KWebConfigurationException> { KWebFileWriteResult(-1) }
        assertFailsWith<KWebConfigurationException> { KWebFileTruncateResult(-1) }
    }

    @Test
    fun selectionRequiresOpaqueHandleMetadata() {
        val selection = KWebFileSelection(
            handle = "A".repeat(43),
            name = "report.txt",
            sizeBytes = 12,
            mode = KWebFileDialogMode.OPEN,
        )
        assertEquals("report.txt", selection.name)
        assertFailsWith<KWebConfigurationException> {
            KWebFileSelection("/tmp/report.txt", "report.txt", 12, KWebFileDialogMode.OPEN)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileSelection("A".repeat(43), "/tmp/report.txt", 12, KWebFileDialogMode.OPEN)
        }
    }

    @Test
    fun collectionsCannotMutateValidatedRequestsAndResults() {
        val extensions = mutableListOf("txt", "json")
        val filter = KWebFileFilter("Text", extensions)
        extensions.add("../secret")
        (filter.extensions as MutableSet).add("exe")
        assertEquals(setOf("txt", "json"), filter.extensions)
        val filters = mutableListOf(filter, KWebFileFilter("Data", listOf("csv")))
        val request = KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Pick", filters = filters)
        filters.clear()
        (request.filters as MutableList).clear()
        assertEquals(2, request.filters.size)
        val input = mutableListOf(0, 255)
        val read = KWebFileReadResult(input, true)
        input[0] = -1
        (read.bytes as MutableList)[0] = -1
        assertFalse(-1 in read.bytes)
    }
}
