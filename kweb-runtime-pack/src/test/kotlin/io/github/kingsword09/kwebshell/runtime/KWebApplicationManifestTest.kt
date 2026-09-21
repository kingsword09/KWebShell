package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebApplicationManifestTest {
    @Test
    fun repositoryManifestIsCanonicalAndTargetComplete() {
        val root = repositoryRoot()
        val path = root.resolve("runtime/application-manifest.json")
        val manifest = KWebApplicationManifestLoader.load(path)

        assertEquals(KWebApplicationManifestContract.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("io.github.kingsword09.kwebshell", manifest.applicationId)
        assertEquals(KWebTarget.supported.map { it.id }.toSet(), manifest.targets.keys)
        assertTrue(Files.readAllBytes(path).contentEquals(KWebApplicationManifestCodec.encode(manifest)))
    }

    @Test
    fun unknownFieldsAreRejected() {
        val source = Files.readString(repositoryRoot().resolve("runtime/application-manifest.json"))
        val invalid = source.replaceFirst("{\n", "{\n  \"unknownField\": true,\n")
        val path = Files.createTempFile("kweb-application-manifest-invalid-", ".json")
        try {
            Files.writeString(path, invalid)
            val error = assertFailsWith<KWebApplicationPackageException> {
                KWebApplicationManifestLoader.load(path)
            }
            assertEquals("application.manifest.json-invalid", error.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun missingTargetIsRejectedBeforePackaging() {
        val manifest = KWebApplicationManifestLoader.load(
            repositoryRoot().resolve("runtime/application-manifest.json"),
        )
        val invalid = manifest.copy(targets = manifest.targets - "linux-arm64")
        val error = assertFailsWith<KWebApplicationPackageException> {
            KWebApplicationManifestContract.validate(invalid)
        }
        assertEquals("application.manifest.target-set-invalid", error.code)
    }

    @Test
    fun absoluteProviderResourcePathIsRejected() {
        val manifest = KWebApplicationManifestLoader.load(
            repositoryRoot().resolve("runtime/application-manifest.json"),
        )
        val invalid = manifest.copy(
            providerResources = listOf(
                KWebApplicationProviderResource("service", "1.0.0", "/absolute/path"),
            ),
        )
        val error = assertFailsWith<KWebApplicationPackageException> {
            KWebApplicationManifestContract.validate(invalid)
        }
        assertEquals("application.manifest.path-invalid", error.code)
    }

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("runtime/application-manifest.json"))) return current
            current = current.parent
        }
        error("Unable to locate runtime/application-manifest.json.")
    }
}
