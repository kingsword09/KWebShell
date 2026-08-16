package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import java.nio.charset.StandardCharsets
import java.nio.file.AccessDeniedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CefSourcePatchVerifierTest {
    @Test
    fun verifiesPinnedManifestAndPatchContents() {
        val catalog = CefSourcePatchManifestLoader.load(pinnedManifest())

        CefSourcePatchVerifier.verifyPatchFiles(catalog)

        assertEquals(1, catalog.manifest.adapterAbiVersion)
        assertEquals(
            "8561856986d1c16cbb95294d7ad3f1e27bed9e102abc0f669a073161614a9c44",
            catalog.manifest.adapterAbiFingerprint,
        )
        assertEquals(4, catalog.manifest.exports.size)
        val patch = catalog.manifest.patches.single()
        assertTrue(
            patch.modifiedPreimages.any {
                it.path == "libcef/browser/chrome/chrome_context_menu_handler.cc"
            },
        )
        val patchText = Files.readString(catalog.root.resolve(patch.file))
        assertTrue(patchText.contains("kweb-chrome-context-menu"))
        assertTrue(patchText.contains("!base::CommandLine::ForCurrentProcess()->HasSwitch("))
    }

    @Test
    fun appliesCreatedPostimagesWithLfWhenGitAutocrlfIsEnabled() = withTempDirectory { root ->
        val fixture = createFixture(root)
        val patch = fixture.catalog.manifest.patches.single()
        val overlay = root.resolve("autocrlf-overlay")
        val globalConfig = root.resolve("gitconfig")
        Files.createDirectories(overlay)
        Files.writeString(globalConfig, "[core]\n\tautocrlf = true\n")

        val process = ProcessBuilder(
            CefSourcePatchVerifier.canonicalPatchApplyCommand(
                patchPath = fixture.patchPath,
                includedPaths = patch.createdPostimages.map(CefSourceFileDigest::path),
            ),
        )
            .directory(overlay.toFile())
            .redirectErrorStream(true)
        process.environment()["GIT_CONFIG_GLOBAL"] = globalConfig.toString()
        process.environment()["GIT_CONFIG_NOSYSTEM"] = "1"
        val started = process.start()
        val output = started.inputStream.use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
        assertTrue(started.waitFor() == 0, "Git apply failed:\n$output")

        patch.createdPostimages.forEach { file ->
            assertEquals(file.sha256, sha256(Files.readString(overlay.resolve(file.path))))
        }
    }

    @Test
    fun rejectsUnknownFieldsAndInvalidManifestContracts() {
        val pinned = CefSourcePatchManifestLoader.load(pinnedManifest())
        val encoded = JSON.encodeToString(pinned.manifest)
        val fields = JSON.parseToJsonElement(encoded).jsonObject
        val unknown = JsonObject(fields + ("unknown" to JsonPrimitive(true))).toString()
        val unknownError = assertFailsWith<KWebConfigurationException> {
            CefSourcePatchManifestLoader.decode(unknown, pinned.root)
        }
        assertEquals("runtime.source-patch.manifest-invalid-json", unknownError.code)

        val cases = listOf(
            "runtime.source-patch.manifest-unsupported-schema" to pinned.manifest.copy(schemaVersion = 2),
            "runtime.source-patch.manifest-commit-version-mismatch" to pinned.manifest.copy(
                cefCommit = "0123456789abcdef0123456789abcdef01234567",
            ),
            "runtime.source-patch.manifest-abi-version-invalid" to pinned.manifest.copy(adapterAbiVersion = 2),
            "runtime.source-patch.manifest-exports-invalid" to pinned.manifest.copy(exports = pinned.manifest.exports.reversed()),
            "runtime.source-patch.manifest-patch-count-invalid" to pinned.manifest.copy(patches = emptyList()),
            "runtime.source-patch.manifest-patch-path-invalid" to pinned.manifest.copy(
                patches = pinned.manifest.patches.map { it.copy(file = "../adapter.patch") },
            ),
            "runtime.source-patch.manifest-source-path-duplicate" to pinned.manifest.copy(
                patches = pinned.manifest.patches.map { patch ->
                    patch.copy(createdPostimages = patch.createdPostimages + patch.createdPostimages.first())
                },
            ),
        )
        cases.forEach { (expectedCode, manifest) ->
            val error = assertFailsWith<KWebConfigurationException>(message = expectedCode) {
                CefSourcePatchManifestLoader.decode(JSON.encodeToString(manifest), pinned.root)
            }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun validatesCustomRuntimeArtifactOrderUniquenessAndUrl() {
        val pinned = CefSourcePatchManifestLoader.load(pinnedManifest())
        val macos = runtimeArtifact(pinned.manifest, "macos-arm64")
        val windows = runtimeArtifact(pinned.manifest, "windows-x64")

        val cases = listOf(
            "runtime.source-patch.manifest-artifact-target-duplicate" to
                pinned.manifest.copy(customRuntimeArtifacts = listOf(macos, macos)),
            "runtime.source-patch.manifest-artifact-order-invalid" to
                pinned.manifest.copy(customRuntimeArtifacts = listOf(windows, macos)),
            "runtime.source-patch.manifest-artifact-url-invalid" to
                pinned.manifest.copy(
                    customRuntimeArtifacts = listOf(macos.copy(downloadUrl = "http://example.invalid/${macos.fileName}")),
                ),
        )
        cases.forEach { (expectedCode, manifest) ->
            val error = assertFailsWith<KWebConfigurationException>(message = expectedCode) {
                CefSourcePatchManifestLoader.decode(JSON.encodeToString(manifest), pinned.root)
            }
            assertEquals(expectedCode, error.code)
        }
    }

    @Test
    fun requiresEveryDesktopCustomRuntimeBeforePublication() {
        val pinned = CefSourcePatchManifestLoader.load(pinnedManifest())
        val incomplete = assertFailsWith<KWebConfigurationException> {
            pinned.requirePackageLifecyclePublicationReady()
        }
        assertEquals("runtime.custom-runtime.publication-incomplete", incomplete.code)

        val artifacts = CefSourcePatchCatalog.REQUIRED_CUSTOM_RUNTIME_TARGETS.map { target ->
            runtimeArtifact(pinned.manifest, target.id)
        }
        val complete = CefSourcePatchManifestLoader.decode(
            JSON.encodeToString(pinned.manifest.copy(customRuntimeArtifacts = artifacts)),
            pinned.root,
        )
        complete.requirePackageLifecyclePublicationReady()
        assertTrue(complete.packageLifecyclePublicationReady)
    }

    @Test
    fun rejectsPatchDigestAndTargetTampering() = withTempDirectory { root ->
        val fixture = createFixture(root)
        val originalPatch = Files.readString(fixture.patchPath)
        Files.writeString(fixture.patchPath, "$originalPatch\n")
        assertVerificationCode("runtime.source-patch.patch-digest-mismatch") {
            CefSourcePatchVerifier.verifyPatchFiles(fixture.catalog)
        }

        Files.writeString(fixture.patchPath, originalPatch)
        val wrongTargets = fixture.catalog.manifest.copy(
            patches = fixture.catalog.manifest.patches.map { patch ->
                patch.copy(
                    createdPostimages = patch.createdPostimages.mapIndexed { index, file ->
                        if (index == 0) file.copy(path = "include/internal/unrelated.h") else file
                    },
                )
            },
        )
        assertVerificationCode("runtime.source-patch.targets-mismatch") {
            CefSourcePatchVerifier.verifyPatchFiles(CefSourcePatchCatalog(wrongTargets, fixture.catalog.root))
        }

        val wrongChromiumCommit = fixture.catalog.manifest.copy(chromiumCommit = "4".repeat(40))
        assertVerificationCode("runtime.source-patch.abi-fingerprint-derived-mismatch") {
            CefSourcePatchVerifier.verifyPatchFiles(
                CefSourcePatchCatalog(wrongChromiumCommit, fixture.catalog.root),
            )
        }
    }

    @Test
    fun appliesPatchToCleanPinnedSourceAndRejectsDirtyTree() = withTempDirectory { root ->
        val fixture = createFixture(root)

        CefSourcePatchVerifier.verifySourceTree(fixture.catalog, fixture.sourceRoot)

        Files.writeString(fixture.sourceRoot.resolve("dirty.txt"), "dirty\n")
        assertVerificationCode("runtime.source-patch.source-dirty") {
            CefSourcePatchVerifier.verifySourceTree(fixture.catalog, fixture.sourceRoot)
        }
    }

    @Test
    fun rejectsWrongCommitPreimageAndOccupiedCreatedPath() = withTempDirectory { root ->
        val wrongCommitFixture = createFixture(root.resolve("wrong-commit"))
        val wrongCommitManifest = wrongCommitFixture.catalog.manifest.copy(
            cefCommit = "0123456789abcdef0123456789abcdef01234567",
            cefVersion = "1.2.3+g0123456+chromium-4.5.6.7",
        )
        assertVerificationCode("runtime.source-patch.commit-mismatch") {
            CefSourcePatchVerifier.verifySourceTree(
                CefSourcePatchCatalog(wrongCommitManifest, wrongCommitFixture.catalog.root),
                wrongCommitFixture.sourceRoot,
            )
        }

        val wrongPreimageFixture = createFixture(root.resolve("wrong-preimage"))
        val patch = wrongPreimageFixture.catalog.manifest.patches.single()
        val wrongPreimageManifest = wrongPreimageFixture.catalog.manifest.copy(
            patches = listOf(
                patch.copy(
                    modifiedPreimages = patch.modifiedPreimages.mapIndexed { index, file ->
                        if (index == 0) file.copy(sha256 = "0".repeat(64)) else file
                    },
                ),
            ),
        )
        assertVerificationCode("runtime.source-patch.preimage-digest-mismatch") {
            CefSourcePatchVerifier.verifySourceTree(
                CefSourcePatchCatalog(wrongPreimageManifest, wrongPreimageFixture.catalog.root),
                wrongPreimageFixture.sourceRoot,
            )
        }

        val occupiedFixture = createFixture(root.resolve("occupied"))
        val occupiedPath = occupiedFixture.sourceRoot.resolve("include/internal/cef_kweb_extension_abi.h")
        Files.createDirectories(requireNotNull(occupiedPath.parent))
        Files.writeString(occupiedPath, "occupied\n")
        runGit(occupiedFixture.sourceRoot, "add", ".")
        runGit(occupiedFixture.sourceRoot, "commit", "-m", "occupy target")
        val occupiedCommit = runGit(occupiedFixture.sourceRoot, "rev-parse", "HEAD").trim()
        val occupiedManifest = occupiedFixture.catalog.manifest.copy(
            cefCommit = occupiedCommit,
            cefVersion = "1.2.3+g${occupiedCommit.take(7)}+chromium-4.5.6.7",
        )
        assertVerificationCode("runtime.source-patch.created-path-exists") {
            CefSourcePatchVerifier.verifySourceTree(
                CefSourcePatchCatalog(occupiedManifest, occupiedFixture.catalog.root),
                occupiedFixture.sourceRoot,
            )
        }
    }

    private fun createFixture(root: Path): Fixture {
        Files.createDirectories(root)
        val source = root.resolve("cef-source")
        Files.createDirectories(source)
        runGit(source, "init")
        runGit(source, "config", "user.name", "KWebShell Test")
        runGit(source, "config", "user.email", "kwebshell-test@example.invalid")

        val buildFile = source.resolve("BUILD.gn")
        val pathsFile = source.resolve("cef_paths2.gypi")
        val contextMenuFile = source.resolve("libcef/browser/chrome/chrome_context_menu_handler.cc")
        val buildBefore = "source_set(\"libcef_static\") {\n}\n"
        val pathsBefore = "{\n  'includes_common_capi': []\n}\n"
        val contextMenuBefore = "bool HandleContextMenu() {\n  return false;\n}\n"
        Files.createDirectories(requireNotNull(contextMenuFile.parent))
        Files.writeString(buildFile, buildBefore)
        Files.writeString(pathsFile, pathsBefore)
        Files.writeString(contextMenuFile, contextMenuBefore)
        runGit(source, "add", ".")
        runGit(source, "commit", "-m", "pinned source")
        val commit = runGit(source, "rev-parse", "HEAD").trim()

        val chromiumCommit = "2".repeat(40)
        val depotToolsCommit = "3".repeat(40)
        val fingerprintPlaceholder = "0".repeat(64)
        val headerTemplate =
            "#define CEF_KWEB_EXTENSION_ABI_VERSION 1 /* fingerprint=$fingerprintPlaceholder */\n"
        val adapterAbiFingerprint = CefSourcePatchVerifier.deriveAdapterAbiFingerprint(
            cefCommit = commit,
            chromiumCommit = chromiumCommit,
            chromiumVersion = "4.5.6.7",
            header = headerTemplate,
        )
        val headerContent = headerTemplate.replace(fingerprintPlaceholder, adapterAbiFingerprint)
        val implementationContent = "int cef_kweb_extension_start(void) { return 0; }\n"
        val patchText = fixturePatch(headerContent, implementationContent)
        val manifestRoot = root.resolve("manifest")
        val patchPath = manifestRoot.resolve("patches/adapter.patch")
        Files.createDirectories(requireNotNull(patchPath.parent))
        Files.writeString(patchPath, patchText)

        val manifest = CefSourcePatchManifest(
            schemaVersion = 1,
            cefVersion = "1.2.3+g${commit.take(7)}+chromium-4.5.6.7",
            cefCommit = commit,
            chromiumVersion = "4.5.6.7",
            chromiumCommit = chromiumCommit,
            depotToolsCommit = depotToolsCommit,
            adapterAbiVersion = 1,
            adapterAbiFingerprint = adapterAbiFingerprint,
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
                    sha256 = sha256(patchText),
                    modifiedPreimages = listOf(
                        CefSourceFileDigest("BUILD.gn", sha256(buildBefore)),
                        CefSourceFileDigest("cef_paths2.gypi", sha256(pathsBefore)),
                        CefSourceFileDigest(
                            "libcef/browser/chrome/chrome_context_menu_handler.cc",
                            sha256(contextMenuBefore),
                        ),
                    ),
                    createdPostimages = listOf(
                        CefSourceFileDigest(
                            "include/internal/cef_kweb_extension_abi.h",
                            sha256(headerContent),
                        ),
                        CefSourceFileDigest(
                            "libcef/browser/extensions/kweb_extension_adapter.cc",
                            sha256(implementationContent),
                        ),
                    ),
                ),
            ),
            customRuntimeArtifacts = emptyList(),
        )
        val catalog = CefSourcePatchManifestLoader.decode(JSON.encodeToString(manifest), manifestRoot)
        return Fixture(source, patchPath, catalog)
    }

    private fun fixturePatch(headerContent: String, implementationContent: String): String =
        """diff --git a/BUILD.gn b/BUILD.gn
--- a/BUILD.gn
+++ b/BUILD.gn
@@ -1,2 +1,3 @@
 source_set("libcef_static") {
+  sources = []
 }
diff --git a/cef_paths2.gypi b/cef_paths2.gypi
--- a/cef_paths2.gypi
+++ b/cef_paths2.gypi
@@ -1,3 +1,4 @@
 {
   'includes_common_capi': []
+  'adapter': true
 }
diff --git a/include/internal/cef_kweb_extension_abi.h b/include/internal/cef_kweb_extension_abi.h
new file mode 100644
--- /dev/null
+++ b/include/internal/cef_kweb_extension_abi.h
@@ -0,0 +1 @@
+${headerContent.trimEnd()}
diff --git a/libcef/browser/chrome/chrome_context_menu_handler.cc b/libcef/browser/chrome/chrome_context_menu_handler.cc
--- a/libcef/browser/chrome/chrome_context_menu_handler.cc
+++ b/libcef/browser/chrome/chrome_context_menu_handler.cc
@@ -1,3 +1,3 @@
 bool HandleContextMenu() {
-  return false;
+  return true;
 }
diff --git a/libcef/browser/extensions/kweb_extension_adapter.cc b/libcef/browser/extensions/kweb_extension_adapter.cc
new file mode 100644
--- /dev/null
+++ b/libcef/browser/extensions/kweb_extension_adapter.cc
@@ -0,0 +1 @@
+${implementationContent.trimEnd()}
"""

    private fun pinnedManifest(): Path = Path.of(
        requireNotNull(System.getProperty("kweb.cef.source.patch.manifest")) {
            "The pinned CEF source patch manifest test property is required."
        },
    )

    private fun runGit(root: Path, vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.use { input -> input.readBytes().toString(StandardCharsets.UTF_8) }
        assertTrue(process.waitFor() == 0, "Git failed: ${arguments.joinToString(" ")}\n$output")
        return output
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun runtimeArtifact(manifest: CefSourcePatchManifest, target: String): CefCustomRuntimeArtifact {
        val fileName =
            "kwebshell-cef_${manifest.cefVersion}_${target}_abi${manifest.adapterAbiVersion}.zip"
        return CefCustomRuntimeArtifact(
            target = target,
            fileName = fileName,
            downloadUrl = "https://example.invalid/releases/$fileName",
            size = 1L,
            sha256 = "a".repeat(64),
            librarySha256 = "b".repeat(64),
        )
    }

    private fun assertVerificationCode(code: String, operation: () -> Unit) {
        val error = assertFailsWith<CefSourcePatchVerificationException>(message = code, block = operation)
        assertEquals(code, error.code)
    }

    private inline fun <T> withTempDirectory(operation: (Path) -> T): T {
        val root = Files.createTempDirectory("kweb-cef-source-patch-test-").toAbsolutePath().normalize()
        try {
            return operation(root)
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        repeat(TEMP_DIRECTORY_DELETE_ATTEMPTS) { attempt ->
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
            try {
                deleteTreeOnce(root)
                return
            } catch (error: AccessDeniedException) {
                if (attempt == TEMP_DIRECTORY_DELETE_ATTEMPTS - 1) throw error
                Thread.sleep(TEMP_DIRECTORY_DELETE_RETRY_MILLIS)
            }
        }
    }

    private fun deleteTreeOnce(root: Path) {
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                clearDosReadOnly(file)
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                clearDosReadOnly(dir)
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun clearDosReadOnly(path: Path) {
        Files.getFileAttributeView(path, DosFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?.setReadOnly(false)
    }

    private data class Fixture(
        val sourceRoot: Path,
        val patchPath: Path,
        val catalog: CefSourcePatchCatalog,
    )

    private companion object {
        const val TEMP_DIRECTORY_DELETE_ATTEMPTS: Int = 10
        const val TEMP_DIRECTORY_DELETE_RETRY_MILLIS: Long = 100

        val JSON: Json = Json { explicitNulls = false }
    }
}
