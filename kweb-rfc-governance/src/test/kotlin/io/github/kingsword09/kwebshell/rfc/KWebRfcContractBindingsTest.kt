package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.io.TempDir

class KWebRfcContractBindingsTest {
    @TempDir
    lateinit var repositoryRoot: Path

    @Test
    fun digestIsDeterministicAndChangesWithAnyBoundFile() {
        val source = Files.createDirectories(repositoryRoot.resolve("module/src"))
        Files.writeString(source.resolve("B.kt"), "class B")
        Files.writeString(source.resolve("A.kt"), "class A")
        val document = document("module/src")

        val first = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        val second = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        assertEquals(first, second)

        Files.writeString(source.resolve("A.kt"), "class A2")
        val changed = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        assertNotEquals(first, changed)
    }

    @Test
    fun missingOrUnsafeBindingsFailClosed() {
        val missing = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.digest(document("missing"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.MISSING, missing.code)

        val unsafe = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.validate(document("../outside"))
        }
        assertEquals(KWebRfcContractBindingErrorCode.INVALID, unsafe.code)
    }

    @Test
    fun documentDecoderRejectsUnknownFields() {
        val path = repositoryRoot.resolve("contracts.json")
        Files.writeString(
            path,
            """{"schemaVersion":1,"unknown":true,"bindings":[]}""",
        )
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.load(path)
        }
        assertEquals(KWebRfcContractBindingErrorCode.INVALID, error.code)
    }

    private fun document(path: String): KWebRfcContractBindingsDocument =
        KWebRfcContractBindingsDocument(
            schemaVersion = 1,
            bindings = listOf(KWebRfcContractBinding(rfcId = "0001", paths = listOf(path))),
        )
}
