package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebFilesContractTest {
    @Test
    fun publishesBoundedPageScopedContract() {
        assertEquals("files", KWebFiles.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebFiles.DESCRIPTOR.version.toString())
        assertEquals("PAGE", KWebFiles.DESCRIPTOR.scope.name)
        assertEquals(
            setOf(
                "open-workspace", "open-file", "open-directory", "read-file", "write-file",
                "truncate-file", "list-directory", "metadata", "copy-file", "move-file",
                "watch-directory", "close-handle",
            ),
            KWebFiles.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assertEquals("native.files.open-workspace", KWebFiles.DESCRIPTOR.operations.first { it.id == "open-workspace" }.rendererPermission)
    }

    @Test
    fun rejectsUnsafeWorkspaceNamesAndComponents() {
        assertFailsWith<KWebConfigurationException> {
            KWebWorkspaceRequest("../outside", setOf(KWebFileGrant.READ))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebWorkspaceRequest("workspace", emptySet())
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileOpenRequest(
                parent = KWebFileHandle("A".repeat(43)),
                name = "../secret",
                mode = KWebFileOpenMode.READ,
            )
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileOpenRequest(
                parent = KWebFileHandle("A".repeat(43)),
                name = "CON.txt",
                mode = KWebFileOpenMode.READ,
            )
        }
    }

    @Test
    fun boundsAreImmutableAndHandleIsOpaque() {
        val result = KWebFileReadResult(ByteArray(4) { it.toByte() }, eof = false)
        val bytes = result.bytes
        bytes[0] = 99
        assertEquals(0, result.bytes[0])
        assertFailsWith<KWebConfigurationException> {
            KWebFileReadResult(ByteArray(KWEB_FILES_MAX_TRANSFER_BYTES + 1), eof = false)
        }
        assertFailsWith<KWebConfigurationException> {
            KWebFileHandle("not-a-token")
        }
    }
}
