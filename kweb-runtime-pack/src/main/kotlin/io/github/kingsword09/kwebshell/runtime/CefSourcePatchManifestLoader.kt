package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public object CefSourcePatchManifestLoader {
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    public fun load(path: Path): CefSourcePatchCatalog {
        val absolute = path.toAbsolutePath().normalize()
        val text = try {
            Files.readString(absolute)
        } catch (error: Exception) {
            throw KWebConfigurationException(
                code = "runtime.source-patch.manifest-read-failed",
                details = mapOf("path" to absolute.toString()),
                message = "Unable to read the CEF source patch manifest.",
                cause = error,
            )
        }
        val manifest = decode(text)
        val root = absolute.parent ?: invalid(
            code = "runtime.source-patch.manifest-root-missing",
            details = mapOf("path" to absolute.toString()),
        )
        return catalog(manifest, root)
    }

    public fun decode(text: String, root: Path): CefSourcePatchCatalog =
        catalog(decode(text), root.toAbsolutePath().normalize())

    private fun decode(text: String): CefSourcePatchManifest {
        val manifest = try {
            json.decodeFromString<CefSourcePatchManifest>(text)
        } catch (error: SerializationException) {
            throw KWebConfigurationException(
                code = "runtime.source-patch.manifest-invalid-json",
                details = emptyMap(),
                message = "The CEF source patch manifest is not valid strict JSON.",
                cause = error,
            )
        }
        validate(manifest)
        return manifest
    }

    private fun validate(manifest: CefSourcePatchManifest) {
        requireManifest(manifest.schemaVersion == SCHEMA_VERSION, "unsupported-schema")
        val cefMatch = CEF_VERSION.matchEntire(manifest.cefVersion)
        requireManifest(cefMatch != null, "cef-version-invalid")
        requireManifest(CHROMIUM_VERSION.matches(manifest.chromiumVersion), "chromium-version-invalid")
        requireManifest(COMMIT.matches(manifest.cefCommit), "cef-commit-invalid")
        requireManifest(COMMIT.matches(manifest.chromiumCommit), "chromium-commit-invalid")
        requireManifest(COMMIT.matches(manifest.depotToolsCommit), "depot-tools-commit-invalid")
        requireManifest(
            manifest.cefVersion.endsWith("+chromium-${manifest.chromiumVersion}"),
            "version-mismatch",
        )
        requireManifest(
            manifest.cefCommit.startsWith(requireNotNull(cefMatch).groupValues[1]),
            "commit-version-mismatch",
        )
        requireManifest(manifest.adapterAbiVersion == ADAPTER_ABI_VERSION, "abi-version-invalid")
        requireManifest(SHA256.matches(manifest.adapterAbiFingerprint), "abi-fingerprint-invalid")
        requireManifest(SHA256.matches(manifest.networkAbiFingerprint), "network-abi-fingerprint-invalid")
        requireManifest(SISO_VERSION.matches(manifest.sisoVersion), "siso-version-invalid")
        requireManifest(manifest.gnDefines == EXPECTED_GN_DEFINES, "gn-defines-invalid")
        val expectedExports = if (manifest.patches.size == 1) {
            EXPECTED_EXTENSION_EXPORTS
        } else {
            EXPECTED_EXPORTS
        }
        requireManifest(manifest.exports == expectedExports, "exports-invalid")
        requireManifest(manifest.patches.isNotEmpty(), "patch-count-invalid")
        requireManifest(
            manifest.patches.map { it.file } == manifest.patches.map { it.file }.sorted(),
            "patch-order-invalid",
        )

        val targetPaths = mutableSetOf<String>()
        manifest.patches.forEach { patch ->
            requireManifest(isSafeRelativePath(patch.file) && patch.file.endsWith(".patch"), "patch-path-invalid")
            requireManifest(SHA256.matches(patch.sha256), "patch-digest-invalid")
            requireManifest(patch.modifiedPreimages.isNotEmpty(), "preimages-empty")
            requireManifest(patch.createdPostimages.isNotEmpty(), "postimages-empty")
            val patchPaths = mutableSetOf<String>()
            (patch.modifiedPreimages + patch.createdPostimages).forEach { file ->
                requireManifest(isSafeRelativePath(file.path), "source-path-invalid")
                requireManifest(SHA256.matches(file.sha256), "source-digest-invalid")
                requireManifest(patchPaths.add(file.path), "source-path-duplicate")
                targetPaths += file.path
            }
        }
        val expectedSourcePaths = if (manifest.patches.size == 1) {
            EXPECTED_EXTENSION_SOURCE_PATHS
        } else {
            EXPECTED_SOURCE_PATHS
        }
        requireManifest(targetPaths == expectedSourcePaths, "source-paths-invalid")

        val artifactTargets = mutableListOf<KWebTarget>()
        manifest.customRuntimeArtifacts.forEach { artifact ->
            val target = try {
                KWebTarget.parse(artifact.target)
            } catch (error: KWebConfigurationException) {
                invalid(
                    code = "runtime.source-patch.manifest-artifact-target-invalid",
                    details = mapOf("target" to artifact.target),
                    cause = error,
                )
            }
            requireManifest(
                target in CefSourcePatchCatalog.REQUIRED_CUSTOM_RUNTIME_TARGETS,
                "artifact-target-invalid",
            )
            requireManifest(target !in artifactTargets, "artifact-target-duplicate")
            artifactTargets += target
            val expectedFileName =
                "kwebshell-cef_${manifest.cefVersion}_${target.id}_abi${manifest.adapterAbiVersion}.zip"
            requireManifest(artifact.fileName == expectedFileName, "artifact-filename-invalid")
            requireManifest(artifact.size > 0L, "artifact-size-invalid")
            requireManifest(SHA256.matches(artifact.sha256), "artifact-digest-invalid")
            requireManifest(SHA256.matches(artifact.librarySha256), "artifact-library-digest-invalid")
            val uri = try {
                URI(artifact.downloadUrl)
            } catch (error: Exception) {
                invalid(
                    code = "runtime.source-patch.manifest-artifact-url-invalid",
                    details = mapOf("url" to artifact.downloadUrl),
                    cause = error,
                )
            }
            requireManifest(
                uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                    uri.path.endsWith("/${artifact.fileName}") && uri.query == null && uri.fragment == null,
                "artifact-url-invalid",
            )
        }
        val expectedOrder = CefSourcePatchCatalog.REQUIRED_CUSTOM_RUNTIME_TARGETS.filter { it in artifactTargets }
        requireManifest(artifactTargets == expectedOrder, "artifact-order-invalid")
    }

    private fun catalog(manifest: CefSourcePatchManifest, root: Path): CefSourcePatchCatalog {
        val artifacts = manifest.customRuntimeArtifacts.associateBy { KWebTarget.parse(it.target) }
        return CefSourcePatchCatalog(manifest, root, artifacts)
    }

    private fun isSafeRelativePath(value: String): Boolean =
        SAFE_PATH.matches(value) && value.split('/').none { it == "." || it == ".." }

    private fun requireManifest(condition: Boolean, suffix: String) {
        if (!condition) {
            invalid("runtime.source-patch.manifest-$suffix")
        }
    }

    private fun invalid(
        code: String,
        details: Map<String, String> = emptyMap(),
        cause: Throwable? = null,
    ): Nothing = throw KWebConfigurationException(
        code = code,
        details = details,
        message = "Invalid CEF source patch manifest: $code.",
        cause = cause,
    )

    private const val SCHEMA_VERSION: Int = 1
    private const val ADAPTER_ABI_VERSION: Int = 1
    private val CEF_VERSION: Regex =
        Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\+g([0-9a-f]+)\\+chromium-[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
    private val CHROMIUM_VERSION: Regex = Regex("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")
    private val COMMIT: Regex = Regex("[0-9a-f]{40}")
    private val SHA256: Regex = Regex("[0-9a-f]{64}")
    private val SISO_VERSION: Regex = Regex("git_revision:[0-9a-f]{40}")
    private val SAFE_PATH: Regex = Regex("[A-Za-z0-9_+.-]+(?:/[A-Za-z0-9_+.-]+)*")
    private val EXPECTED_EXPORTS: List<String> = listOf(
        "cef_kweb_extension_abi_fingerprint",
        "cef_kweb_extension_cancel",
        "cef_kweb_extension_live_operation_count",
        "cef_kweb_extension_start",
        "cef_kweb_network_abi_fingerprint",
        "cef_kweb_network_clear_config",
        "cef_kweb_network_resolve_proxy",
        "cef_kweb_network_set_config",
    )
    private val EXPECTED_EXTENSION_EXPORTS: List<String> = EXPECTED_EXPORTS.take(4)
    private val EXPECTED_GN_DEFINES: List<String> = listOf(
        "is_official_build=true",
        "symbol_level=0",
    )
    private val EXPECTED_SOURCE_PATHS: Set<String> = setOf(
        "BUILD.gn",
        "cef_paths2.gypi",
        "libcef/browser/chrome/chrome_context_menu_handler.cc",
        "include/internal/cef_kweb_extension_abi.h",
        "libcef/browser/extensions/kweb_extension_adapter.cc",
        "include/internal/cef_kweb_network_abi.h",
        "libcef/browser/kweb_network_adapter.cc",
        "libcef/browser/kweb_network_adapter.h",
        "libcef/browser/chrome/chrome_content_browser_client_cef.cc",
    )
    private val EXPECTED_EXTENSION_SOURCE_PATHS: Set<String> = EXPECTED_SOURCE_PATHS - setOf(
        "include/internal/cef_kweb_network_abi.h",
        "libcef/browser/kweb_network_adapter.cc",
        "libcef/browser/kweb_network_adapter.h",
        "libcef/browser/chrome/chrome_content_browser_client_cef.cc",
    )
}
