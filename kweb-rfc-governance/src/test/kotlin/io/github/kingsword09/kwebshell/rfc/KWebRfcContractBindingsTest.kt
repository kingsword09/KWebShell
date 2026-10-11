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
    fun digestIsDeterministicAndChangesWithAnyTrackedBoundFile() {
        git("init", "-q")
        val source = Files.createDirectories(repositoryRoot.resolve("module/src"))
        Files.writeString(source.resolve("B.kt"), "class B")
        Files.writeString(source.resolve("A.kt"), "class A")
        git("add", "-A")
        val document = document("module/src")

        val first = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        val second = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        assertEquals(first, second)

        Files.writeString(source.resolve("A.kt"), "class A2")
        val changed = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        assertNotEquals(first, changed)
    }

    @Test
    fun untrackedLocalOutputUnderABoundPathKeepsTheRevisionDigest() {
        git("init", "-q")
        val source = Files.createDirectories(repositoryRoot.resolve("module/native"))
        Files.writeString(source.resolve("provider.cc"), "provider")
        git("add", "-A")
        val document = document("module/native")

        val recorded = KWebRfcContractBindings.digest(document, repositoryRoot, "0001")
        // A local build directory is not part of the revision the evidence binds.
        val build = Files.createDirectories(source.resolve("build/contract"))
        Files.writeString(build.resolve("CMakeCache.txt"), "CMAKE_PROJECT_NAME=local")
        Files.writeString(build.resolve("provider.o"), "object")
        assertEquals(recorded, KWebRfcContractBindings.digest(document, repositoryRoot, "0001"))

        // A tracked change still invalidates the record.
        Files.writeString(source.resolve("provider.cc"), "provider2")
        assertNotEquals(recorded, KWebRfcContractBindings.digest(document, repositoryRoot, "0001"))
    }

    @Test
    fun aRootWithoutARevisionAndMissingTrackedFilesFailClosed() {
        val source = Files.createDirectories(repositoryRoot.resolve("module/native"))
        Files.writeString(source.resolve("provider.cc"), "provider")
        val notAWorkTree = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.digest(document("module/native"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.NOT_A_WORK_TREE, notAWorkTree.code)

        git("init", "-q")
        val notTracked = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.digest(document("module/native"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.NOT_TRACKED, notTracked.code)

        git("add", "-A")
        Files.delete(source.resolve("provider.cc"))
        val missing = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.digest(document("module/native"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.TRACKED_FILE_MISSING, missing.code)
    }

    @Test
    fun missingOrUnsafeBindingsFailClosed() {
        val missing = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcContractBindings.digest(document("missing"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.MISSING, missing.code)

        val trackedDirectoryMissing = assertFailsWith<KWebRfcGovernanceException> {
            git("init", "-q")
            KWebRfcContractBindings.digest(document("module/absent"), repositoryRoot, "0001")
        }
        assertEquals(KWebRfcContractBindingErrorCode.MISSING, trackedDirectoryMissing.code)

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

    /** Runs git in the fixture repository, so the digest binds a real revision. */
    private fun git(vararg arguments: String) {
        val command = listOf("git", "-C", repositoryRoot.toString()) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            "git ${arguments.joinToString(" ")} failed: $output"
        }
    }

    private fun document(path: String): KWebRfcContractBindingsDocument =
        KWebRfcContractBindingsDocument(
            schemaVersion = 1,
            bindings = listOf(KWebRfcContractBinding(rfcId = "0001", paths = listOf(path))),
        )
}
