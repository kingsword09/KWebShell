package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CefCustomRuntimeArtifactVerifierTest {
    @Test
    fun verifiesPinnedArchiveLibraryHeaderAndAdapterEvidence() = withFixture { fixture ->
        CefCustomRuntimeArtifactVerifier.verify(fixture.archive, fixture.catalog, TARGET)
    }

    @Test
    fun rejectsArchiveAndLibraryDigestMismatch() = withFixture { fixture ->
        val archiveMismatch = fixture.withArtifact(fixture.artifact.copy(sha256 = "0".repeat(64)))
        assertCode("runtime.custom-runtime.archive-digest-mismatch") {
            CefCustomRuntimeArtifactVerifier.verify(fixture.archive, archiveMismatch, TARGET)
        }

        val libraryMismatch = fixture.withArtifact(fixture.artifact.copy(librarySha256 = "0".repeat(64)))
        assertCode("runtime.custom-runtime.library-digest-mismatch") {
            CefCustomRuntimeArtifactVerifier.verify(fixture.archive, libraryMismatch, TARGET)
        }
    }

    @Test
    fun rejectsMissingAdapterEvidence() = withFixture(includeAllEvidence = false) { fixture ->
        assertCode("runtime.custom-runtime.adapter-evidence-missing") {
            CefCustomRuntimeArtifactVerifier.verify(fixture.archive, fixture.catalog, TARGET)
        }
    }

    @Test
    fun rejectsUnsafeArchiveEntries() = withFixture(extraEntry = "../escaped") { fixture ->
        assertCode("runtime.custom-runtime.archive-entry-invalid") {
            CefCustomRuntimeArtifactVerifier.verify(fixture.archive, fixture.catalog, TARGET)
        }
    }

    private fun withFixture(
        includeAllEvidence: Boolean = true,
        extraEntry: String? = null,
        operation: (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("kweb-custom-runtime-test-").toAbsolutePath().normalize()
        try {
            val manifest = manifest()
            val fileName =
                "kwebshell-cef_${manifest.cefVersion}_${TARGET.id}_abi${manifest.adapterAbiVersion}.zip"
            val archive = root.resolve(fileName)
            val distributionRoot = "cef_binary_${manifest.cefVersion}_macosarm64_minimal"
            val header = "pinned ABI header\n".toByteArray(StandardCharsets.UTF_8)
            val evidence = manifest.exports + manifest.adapterAbiFingerprint
            val included = if (includeAllEvidence) evidence else evidence.dropLast(1)
            val library = included.joinToString("\u0000", postfix = "\u0000")
                .toByteArray(StandardCharsets.US_ASCII)
            ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                zip.writeEntry(
                    "$distributionRoot/Release/Chromium Embedded Framework.framework/Chromium Embedded Framework",
                    library,
                )
                zip.writeEntry("$distributionRoot/include/internal/cef_kweb_extension_abi.h", header)
                if (extraEntry != null) zip.writeEntry(extraEntry, byteArrayOf(1))
            }
            val artifact = CefCustomRuntimeArtifact(
                target = TARGET.id,
                fileName = fileName,
                downloadUrl = "https://example.invalid/releases/$fileName",
                size = Files.size(archive),
                sha256 = sha256(Files.readAllBytes(archive)),
                librarySha256 = sha256(library),
            )
            val patchedManifest = manifest.copy(
                patches = manifest.patches.map { patch ->
                    patch.copy(
                        createdPostimages = patch.createdPostimages.map { image ->
                            if (image.path == "include/internal/cef_kweb_extension_abi.h") {
                                image.copy(sha256 = sha256(header))
                            } else {
                                image
                            }
                        },
                    )
                },
                customRuntimeArtifacts = listOf(artifact),
            )
            val catalog = CefSourcePatchCatalog(patchedManifest, root, mapOf(TARGET to artifact))
            operation(Fixture(archive, artifact, catalog))
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }

    private fun manifest(): CefSourcePatchManifest = CefSourcePatchManifest(
        schemaVersion = 1,
        cefVersion = "1.2.3+g1234567+chromium-4.5.6.7",
        cefCommit = "1234567${"0".repeat(33)}",
        chromiumVersion = "4.5.6.7",
        chromiumCommit = "2".repeat(40),
        depotToolsCommit = "3".repeat(40),
        adapterAbiVersion = 1,
        adapterAbiFingerprint = "a".repeat(64),
        sisoVersion = "git_revision:${"4".repeat(40)}",
        gnDefines = listOf("is_official_build=true", "symbol_level=0"),
        exports = listOf(
            "cef_kweb_extension_abi_fingerprint",
            "cef_kweb_extension_cancel",
            "cef_kweb_extension_live_operation_count",
            "cef_kweb_extension_start",
        ),
        patches = listOf(
            CefSourcePatch(
                file = "patches/adapter.patch",
                sha256 = "5".repeat(64),
                modifiedPreimages = listOf(CefSourceFileDigest("BUILD.gn", "6".repeat(64))),
                createdPostimages = listOf(
                    CefSourceFileDigest("include/internal/cef_kweb_extension_abi.h", "7".repeat(64)),
                ),
            ),
        ),
        customRuntimeArtifacts = emptyList(),
    )

    private fun ZipOutputStream.writeEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun Fixture.withArtifact(replacement: CefCustomRuntimeArtifact): CefSourcePatchCatalog =
        CefSourcePatchCatalog(
            catalog.manifest.copy(customRuntimeArtifacts = listOf(replacement)),
            catalog.root,
            mapOf(TARGET to replacement),
        )

    private fun assertCode(expected: String, operation: () -> Unit) {
        val error = assertFailsWith<CefCustomRuntimeVerificationException>(block = operation)
        assertEquals(expected, error.code)
    }

    private data class Fixture(
        val archive: Path,
        val artifact: CefCustomRuntimeArtifact,
        val catalog: CefSourcePatchCatalog,
    )

    private companion object {
        val TARGET: KWebTarget = KWebTarget.parse("macos-arm64")
    }
}
