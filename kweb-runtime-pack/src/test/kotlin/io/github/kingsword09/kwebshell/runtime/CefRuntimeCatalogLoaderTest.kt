package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CefRuntimeCatalogLoaderTest {
    @Test
    fun loadsCompleteStrictCatalog() {
        val catalog = CefRuntimeCatalogLoader.decode(encode(validManifest()))

        assertEquals(KWebTarget.supported, catalog.supportedTargets)
        assertEquals(
            "https://cef.example/cef_binary_1.2.3+gabc+chromium-4.5.6.7_linux64_minimal.tar.bz2",
            catalog.downloadUri(KWebTarget.parse("linux-x64")).toString(),
        )
    }

    @Test
    fun rejectsUnknownFields() {
        val manifest = validManifest()
        val fields = json.parseToJsonElement(encode(manifest)).jsonObject
        val invalid = JsonObject(fields + ("unknown" to JsonPrimitive(true))).toString()

        val error = assertFailsWith<KWebConfigurationException> {
            CefRuntimeCatalogLoader.decode(invalid)
        }

        assertEquals("runtime.manifest.invalid-json", error.code)
    }

    @Test
    fun rejectsMissingTarget() {
        val manifest = validManifest()
        val invalid = encode(manifest.copy(artifacts = manifest.artifacts.dropLast(1)))

        val error = assertFailsWith<KWebConfigurationException> {
            CefRuntimeCatalogLoader.decode(invalid)
        }

        assertEquals("runtime.manifest.incomplete-targets", error.code)
    }

    @Test
    fun rejectsDuplicateTarget() {
        val manifest = validManifest()
        val invalid = encode(manifest.copy(artifacts = manifest.artifacts + manifest.artifacts.first()))

        val error = assertFailsWith<KWebConfigurationException> {
            CefRuntimeCatalogLoader.decode(invalid)
        }

        assertEquals("runtime.manifest.duplicate-target", error.code)
    }

    @Test
    fun rejectsNonHttpsCatalogs() {
        val invalid = encode(validManifest().copy(baseUrl = "http://cef.example"))

        val error = assertFailsWith<KWebConfigurationException> {
            CefRuntimeCatalogLoader.decode(invalid)
        }

        assertEquals("runtime.manifest.non-https-uri", error.code)
    }

    @Test
    fun rejectsInvalidTopLevelMetadataWithSpecificErrors() {
        val valid = validManifest()
        val cases = listOf(
            "runtime.manifest.unsupported-schema" to valid.copy(schemaVersion = 2),
            "runtime.manifest.invalid-cef-version" to valid.copy(cefVersion = ""),
            "runtime.manifest.invalid-cef-version" to valid.copy(cefVersion = "151.latest"),
            "runtime.manifest.invalid-chromium-version" to valid.copy(chromiumVersion = "151"),
            "runtime.manifest.version-mismatch" to valid.copy(chromiumVersion = "4.5.7.0"),
            "runtime.manifest.non-stable-channel" to valid.copy(channel = "beta"),
            "runtime.manifest.invalid-distribution" to valid.copy(distributionType = "standard"),
            "runtime.manifest.trailing-base-url-slash" to valid.copy(baseUrl = "https://cef.example/"),
        )

        cases.forEach { (expectedCode, manifest) ->
            val error = assertFailsWith<KWebConfigurationException>(message = expectedCode) {
                CefRuntimeCatalogLoader.decode(encode(manifest))
            }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun rejectsMalformedAndInsecureUris() {
        val valid = validManifest()
        val cases = listOf(
            "runtime.manifest.invalid-uri" to valid.copy(baseUrl = "https://["),
            "runtime.manifest.non-https-uri" to valid.copy(sourceCatalog = "http://cef.example/index.json"),
            "runtime.manifest.non-https-uri" to valid.copy(sourceCatalog = "index.json"),
        )

        cases.forEach { (expectedCode, manifest) ->
            val error = assertFailsWith<KWebConfigurationException>(message = expectedCode) {
                CefRuntimeCatalogLoader.decode(encode(manifest))
            }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun rejectsInvalidArtifactMetadataWithSpecificErrors() {
        val valid = validManifest()
        val cases = listOf(
            "target.invalid" to valid.withFirstArtifact { copy(target = "linux-riscv64") },
            "runtime.manifest.platform-mismatch" to valid.withFirstArtifact { copy(cefPlatform = "linux64") },
            "runtime.manifest.filename-mismatch" to valid.withFirstArtifact { copy(fileName = "renamed.tar.bz2") },
            "runtime.manifest.invalid-size" to valid.withFirstArtifact { copy(size = 0) },
            "runtime.manifest.unsupported-checksum" to valid.withFirstArtifact {
                copy(checksum = checksum.copy(algorithm = "SHA-256"))
            },
            "runtime.manifest.invalid-checksum" to valid.withFirstArtifact {
                copy(checksum = checksum.copy(value = checksum.value.uppercase()))
            },
        )

        cases.forEach { (expectedCode, manifest) ->
            val error = assertFailsWith<KWebConfigurationException>(message = expectedCode) {
                CefRuntimeCatalogLoader.decode(encode(manifest))
            }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun reportsManifestReadFailuresAsTypedErrors() {
        val directory = Files.createTempDirectory("kweb-runtime-manifest-")
        try {
            val missing = directory.resolve("missing.json")
            val error = assertFailsWith<KWebConfigurationException> {
                CefRuntimeCatalogLoader.load(missing)
            }
            assertEquals("runtime.manifest.read-failed", error.code)
            assertEquals(missing.toAbsolutePath().toString(), error.details["path"])
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun rejectsUnsafeFileNames() {
        val manifest = validManifest()
        val invalid = encode(manifest.withFirstArtifact { copy(fileName = "../$fileName") })

        val error = assertFailsWith<KWebConfigurationException> {
            CefRuntimeCatalogLoader.decode(invalid)
        }

        assertEquals("runtime.manifest.unsafe-filename", error.code)
    }

    private fun encode(manifest: CefRuntimeManifest): String = json.encodeToString(manifest)

    private fun validManifest(): CefRuntimeManifest = CefRuntimeManifest(
        schemaVersion = 1,
        cefVersion = "1.2.3+gabc+chromium-4.5.6.7",
        chromiumVersion = "4.5.6.7",
        channel = "stable",
        distributionType = "minimal",
        baseUrl = "https://cef.example",
        sourceCatalog = "https://cef.example/index.json",
        artifacts = listOf(
            artifact("windows-x64", "windows64"),
            artifact("windows-arm64", "windowsarm64"),
            artifact("macos-x64", "macosx64"),
            artifact("macos-arm64", "macosarm64"),
            artifact("linux-x64", "linux64"),
            artifact("linux-arm64", "linuxarm64"),
        ),
    )

    private fun artifact(target: String, cefPlatform: String): CefRuntimeArtifact = CefRuntimeArtifact(
        target = target,
        cefPlatform = cefPlatform,
        fileName = "cef_binary_1.2.3+gabc+chromium-4.5.6.7_${cefPlatform}_minimal.tar.bz2",
        size = 12,
        checksum = CefRuntimeChecksum(
            algorithm = "SHA-1",
            value = "0123456789abcdef0123456789abcdef01234567",
        ),
    )

    private fun CefRuntimeManifest.withFirstArtifact(
        transform: CefRuntimeArtifact.() -> CefRuntimeArtifact,
    ): CefRuntimeManifest = copy(artifacts = listOf(artifacts.first().transform()) + artifacts.drop(1))

    private companion object {
        val json: Json = Json
    }
}
