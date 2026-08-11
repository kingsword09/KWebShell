package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

public object CefRuntimeCatalogLoader {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    public fun load(path: Path): CefRuntimeCatalog {
        val text = try {
            Files.readString(path)
        } catch (exception: Exception) {
            throw KWebConfigurationException(
                code = "runtime.manifest.read-failed",
                details = mapOf("path" to path.toAbsolutePath().toString()),
                message = "Unable to read the CEF runtime manifest at '$path'.",
                cause = exception,
            )
        }
        return decode(text)
    }

    public fun decode(text: String): CefRuntimeCatalog {
        val manifest = try {
            json.decodeFromString<CefRuntimeManifest>(text)
        } catch (exception: SerializationException) {
            throw KWebConfigurationException(
                code = "runtime.manifest.invalid-json",
                details = emptyMap(),
                message = "The CEF runtime manifest is not valid JSON for schema version 1.",
                cause = exception,
            )
        }
        return validate(manifest)
    }

    private fun validate(manifest: CefRuntimeManifest): CefRuntimeCatalog {
        requireManifest(manifest.schemaVersion == 1, "runtime.manifest.unsupported-schema") {
            mapOf("schemaVersion" to manifest.schemaVersion.toString())
        }
        requireManifest(manifest.cefVersion.matches(cefVersionPattern), "runtime.manifest.invalid-cef-version") {
            mapOf("cefVersion" to manifest.cefVersion)
        }
        requireManifest(
            manifest.chromiumVersion.matches(chromiumVersionPattern),
            "runtime.manifest.invalid-chromium-version",
        ) {
            mapOf("chromiumVersion" to manifest.chromiumVersion)
        }
        requireManifest(
            manifest.cefVersion.endsWith("+chromium-${manifest.chromiumVersion}"),
            "runtime.manifest.version-mismatch",
        ) {
            mapOf("cefVersion" to manifest.cefVersion, "chromiumVersion" to manifest.chromiumVersion)
        }
        requireManifest(manifest.channel == "stable", "runtime.manifest.non-stable-channel") {
            mapOf("channel" to manifest.channel)
        }
        requireManifest(manifest.distributionType == "minimal", "runtime.manifest.invalid-distribution") {
            mapOf("distributionType" to manifest.distributionType)
        }
        requireHttpsUri(manifest.baseUrl, "baseUrl")
        requireHttpsUri(manifest.sourceCatalog, "sourceCatalog")
        requireManifest(!manifest.baseUrl.endsWith('/'), "runtime.manifest.trailing-base-url-slash")

        val artifactsByTarget = linkedMapOf<KWebTarget, CefRuntimeArtifact>()
        manifest.artifacts.forEach { artifact ->
            val target = KWebTarget.parse(artifact.target)
            requireManifest(target !in artifactsByTarget, "runtime.manifest.duplicate-target") {
                mapOf("target" to target.id)
            }
            validateArtifact(manifest, target, artifact)
            artifactsByTarget[target] = artifact
        }

        requireManifest(artifactsByTarget.keys == KWebTarget.supported, "runtime.manifest.incomplete-targets") {
            mapOf(
                "actual" to artifactsByTarget.keys.joinToString { it.id },
                "expected" to KWebTarget.supported.joinToString { it.id },
            )
        }

        return CefRuntimeCatalog(manifest, artifactsByTarget)
    }

    private fun validateArtifact(
        manifest: CefRuntimeManifest,
        target: KWebTarget,
        artifact: CefRuntimeArtifact,
    ) {
        val expectedPlatform = cefPlatformByTarget.getValue(target)
        requireManifest(artifact.cefPlatform == expectedPlatform, "runtime.manifest.platform-mismatch") {
            mapOf("target" to target.id, "actual" to artifact.cefPlatform, "expected" to expectedPlatform)
        }

        val expectedFileName =
            "cef_binary_${manifest.cefVersion}_${expectedPlatform}_${manifest.distributionType}.tar.bz2"
        requireManifest(artifact.fileName.matches(fileNamePattern), "runtime.manifest.unsafe-filename") {
            mapOf("target" to target.id, "fileName" to artifact.fileName)
        }
        requireManifest(artifact.fileName == expectedFileName, "runtime.manifest.filename-mismatch") {
            mapOf("target" to target.id, "actual" to artifact.fileName, "expected" to expectedFileName)
        }
        requireManifest(artifact.size > 0L, "runtime.manifest.invalid-size") {
            mapOf("target" to target.id, "size" to artifact.size.toString())
        }
        requireManifest(artifact.checksum.algorithm == "SHA-1", "runtime.manifest.unsupported-checksum") {
            mapOf("target" to target.id, "algorithm" to artifact.checksum.algorithm)
        }
        requireManifest(artifact.checksum.value.matches(sha1Pattern), "runtime.manifest.invalid-checksum") {
            mapOf("target" to target.id, "checksum" to artifact.checksum.value)
        }
    }

    private fun requireHttpsUri(value: String, field: String) {
        val uri = try {
            URI(value)
        } catch (exception: Exception) {
            throw KWebConfigurationException(
                code = "runtime.manifest.invalid-uri",
                details = mapOf("field" to field, "value" to value),
                message = "CEF runtime manifest field '$field' is not a valid URI.",
                cause = exception,
            )
        }
        requireManifest(uri.scheme == "https" && !uri.host.isNullOrBlank(), "runtime.manifest.non-https-uri") {
            mapOf("field" to field, "value" to value)
        }
    }

    private inline fun requireManifest(
        condition: Boolean,
        code: String,
        details: () -> Map<String, String> = { emptyMap() },
    ) {
        if (!condition) {
            throw KWebConfigurationException(
                code = code,
                details = details(),
                message = "Invalid CEF runtime manifest: $code.",
            )
        }
    }

    private val cefPlatformByTarget: Map<KWebTarget, String> = mapOf(
        KWebTarget.parse("windows-x64") to "windows64",
        KWebTarget.parse("windows-arm64") to "windowsarm64",
        KWebTarget.parse("macos-x64") to "macosx64",
        KWebTarget.parse("macos-arm64") to "macosarm64",
        KWebTarget.parse("linux-x64") to "linux64",
        KWebTarget.parse("linux-arm64") to "linuxarm64",
    )
    private val fileNamePattern: Regex = Regex("[A-Za-z0-9+._-]+")
    private val cefVersionPattern: Regex =
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\+g[0-9a-f]+\\+chromium-[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
    private val chromiumVersionPattern: Regex = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
    private val sha1Pattern: Regex = Regex("[0-9a-f]{40}")
}
