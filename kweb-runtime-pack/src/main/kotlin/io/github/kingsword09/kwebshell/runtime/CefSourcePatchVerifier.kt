package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

public class CefSourcePatchVerificationException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public object CefSourcePatchVerifier {
    public fun verifyPatchFiles(catalog: CefSourcePatchCatalog) {
        catalog.manifest.patches.forEach { patch ->
            val patchPath = resolveRegularFile(catalog.root, patch.file, "patch-file-invalid")
            requireDigest(patchPath, patch.sha256, "patch-digest-mismatch")
            val expectedTargets = (patch.modifiedPreimages + patch.createdPostimages)
                .mapTo(linkedSetOf(), CefSourceFileDigest::path)
            val actualTargets = patchTargets(patchPath)
            if (actualTargets != expectedTargets) {
                failure(
                    code = "runtime.source-patch.targets-mismatch",
                    details = mapOf(
                        "expected" to expectedTargets.sorted().joinToString(","),
                        "actual" to actualTargets.sorted().joinToString(","),
                    ),
                    message = "The CEF patch targets differ from its manifest.",
                )
            }
            verifyCreatedPostimages(catalog.manifest, patchPath, patch)
        }
    }

    private fun verifyCreatedPostimages(
        manifest: CefSourcePatchManifest,
        patchPath: Path,
        patch: CefSourcePatch,
    ) {
        val overlay = Files.createTempDirectory("kweb-cef-created-postimages-").toAbsolutePath().normalize()
        try {
            applyPatch(
                workingDirectory = overlay,
                patchPath = patchPath,
                includedPaths = patch.createdPostimages.map(CefSourceFileDigest::path),
                failureCode = "runtime.source-patch.created-postimages-apply-failed",
            )
            val expectedPaths = patch.createdPostimages.mapTo(sortedSetOf(), CefSourceFileDigest::path)
            val actualPaths = collectRegularPaths(overlay)
            if (actualPaths != expectedPaths) {
                failure(
                    code = "runtime.source-patch.created-postimages-targets-mismatch",
                    details = mapOf(
                        "expected" to expectedPaths.joinToString(","),
                        "actual" to actualPaths.joinToString(","),
                    ),
                    message = "Extracting the CEF patch produced unexpected created postimages.",
                )
            }
            patch.createdPostimages.forEach { file ->
                requireDigest(
                    resolveRegularFile(overlay, file.path, "postimage-file-invalid"),
                    file.sha256,
                    "postimage-digest-mismatch",
                )
            }
            requireAdapterAbiFingerprint(
                manifest,
                resolveRegularFile(overlay, ADAPTER_ABI_HEADER, "abi-header-invalid"),
            )
        } finally {
            deleteTree(overlay)
        }
    }

    public fun verifySourceTree(catalog: CefSourcePatchCatalog, sourceRoot: Path) {
        val source = sourceRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(source) || !Files.exists(source.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            failure(
                code = "runtime.source-patch.source-not-git",
                details = mapOf("path" to source.toString()),
                message = "The CEF source root must be a Git worktree.",
            )
        }
        if (runGit(source, "rev-parse", "--is-inside-work-tree").trim() != "true") {
            failure(
                code = "runtime.source-patch.source-not-worktree",
                details = mapOf("path" to source.toString()),
                message = "The CEF source root is not a Git worktree.",
            )
        }
        val actualCommit = runGit(source, "rev-parse", "HEAD").trim()
        if (actualCommit != catalog.manifest.cefCommit) {
            failure(
                code = "runtime.source-patch.commit-mismatch",
                details = mapOf("expected" to catalog.manifest.cefCommit, "actual" to actualCommit),
                message = "The CEF source commit does not match the patch manifest.",
            )
        }
        val status = runGit(source, "status", "--porcelain=v1", "--untracked-files=all")
        if (status.isNotEmpty()) {
            failure(
                code = "runtime.source-patch.source-dirty",
                details = mapOf("path" to source.toString()),
                message = "The CEF source worktree must be clean before patch verification.",
            )
        }

        val patch = catalog.manifest.patches.single()
        patch.modifiedPreimages.forEach { file ->
            val path = resolveRegularFile(source, file.path, "preimage-file-invalid")
            requireDigest(path, file.sha256, "preimage-digest-mismatch")
        }
        patch.createdPostimages.forEach { file ->
            val path = resolve(source, file.path)
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                failure(
                    code = "runtime.source-patch.created-path-exists",
                    details = mapOf("path" to path.toString()),
                    message = "A source path that the CEF patch creates already exists.",
                )
            }
        }
        verifyPatchFiles(catalog)

        val patchPath = resolveRegularFile(catalog.root, patch.file, "patch-file-invalid")
        runGit(
            source,
            "apply",
            "--check",
            "--index",
            "--whitespace=error-all",
            patchPath.toString(),
        )
        verifyAppliedOverlay(source, patchPath, patch)
    }

    private fun verifyAppliedOverlay(source: Path, patchPath: Path, patch: CefSourcePatch) {
        val overlay = Files.createTempDirectory("kweb-cef-patch-overlay-").toAbsolutePath().normalize()
        try {
            patch.modifiedPreimages.forEach { file ->
                val sourceFile = resolve(source, file.path)
                val destination = resolve(overlay, file.path)
                Files.createDirectories(requireNotNull(destination.parent))
                Files.copy(sourceFile, destination)
            }
            applyPatch(
                workingDirectory = overlay,
                patchPath = patchPath,
                failureCode = "runtime.source-patch.overlay-apply-failed",
            )

            val expectedPaths = (patch.modifiedPreimages + patch.createdPostimages)
                .mapTo(sortedSetOf(), CefSourceFileDigest::path)
            val actualPaths = collectRegularPaths(overlay)
            if (actualPaths != expectedPaths) {
                failure(
                    code = "runtime.source-patch.overlay-targets-mismatch",
                    details = mapOf(
                        "expected" to expectedPaths.joinToString(","),
                        "actual" to actualPaths.sorted().joinToString(","),
                    ),
                    message = "Applying the CEF patch produced an unexpected source tree.",
                )
            }
            patch.createdPostimages.forEach { file ->
                requireDigest(resolveRegularFile(overlay, file.path, "postimage-file-invalid"), file.sha256, "postimage-digest-mismatch")
            }
        } finally {
            deleteTree(overlay)
        }
    }

    private fun collectRegularPaths(root: Path): java.util.SortedSet<String> {
        val paths = sortedSetOf<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) {
                    overlayInvalid(dir)
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile || Files.isSymbolicLink(file)) {
                    overlayInvalid(file)
                }
                paths += root.relativize(file).joinToString("/")
                return FileVisitResult.CONTINUE
            }
        })
        return paths
    }

    private fun requireAdapterAbiFingerprint(manifest: CefSourcePatchManifest, header: Path) {
        val text = try {
            Files.readString(header)
        } catch (error: Exception) {
            failure(
                code = "runtime.source-patch.abi-header-read-failed",
                details = mapOf("path" to header.toString()),
                message = "The patched CEF extension ABI header could not be read.",
                cause = error,
            )
        }
        val fingerprints = SHA256_HEX.findAll(text).toList()
        if (fingerprints.size != 1 || fingerprints.single().value != manifest.adapterAbiFingerprint) {
            failure(
                code = "runtime.source-patch.abi-header-fingerprint-mismatch",
                details = mapOf(
                    "expected" to manifest.adapterAbiFingerprint,
                    "actual" to fingerprints.joinToString(",") { match -> match.value },
                ),
                message = "The patched CEF ABI header does not embed the manifest fingerprint exactly once.",
            )
        }
        val derived = deriveAdapterAbiFingerprint(
            cefCommit = manifest.cefCommit,
            chromiumCommit = manifest.chromiumCommit,
            chromiumVersion = manifest.chromiumVersion,
            header = text,
        )
        if (derived != manifest.adapterAbiFingerprint) {
            failure(
                code = "runtime.source-patch.abi-fingerprint-derived-mismatch",
                details = mapOf("expected" to manifest.adapterAbiFingerprint, "actual" to derived),
                message = "The CEF extension adapter fingerprint was not derived from its pinned ABI schema.",
            )
        }
    }

    internal fun deriveAdapterAbiFingerprint(
        cefCommit: String,
        chromiumCommit: String,
        chromiumVersion: String,
        header: String,
    ): String {
        val fingerprints = SHA256_HEX.findAll(header).toList()
        require(fingerprints.size == 1) { "The ABI header must contain exactly one SHA-256 fingerprint." }
        val normalized = header.replaceRange(fingerprints.single().range, "0".repeat(64))
        val material = buildString {
            appendLine("kwebshell-cef-extension-abi-v1")
            appendLine("cef-commit=$cefCommit")
            appendLine("chromium-commit=$chromiumCommit")
            appendLine("chromium-version=$chromiumVersion")
            append(normalized)
        }
        return sha256(material.toByteArray(Charsets.UTF_8))
    }

    private fun patchTargets(patchPath: Path): Set<String> {
        val bytes = Files.readAllBytes(patchPath)
        if (bytes.size > MAXIMUM_PATCH_BYTES) {
            failure(
                code = "runtime.source-patch.patch-too-large",
                details = mapOf("path" to patchPath.toString(), "size" to bytes.size.toString()),
                message = "The CEF source patch exceeds its bounded size.",
            )
        }
        val targets = linkedSetOf<String>()
        bytes.toString(Charsets.UTF_8).lineSequence()
            .filter { it.startsWith("diff --git ") }
            .forEach { line ->
                val match = PATCH_TARGET.matchEntire(line)
                if (match == null || match.groupValues[1] != match.groupValues[2]) {
                    failure(
                        code = "runtime.source-patch.patch-header-invalid",
                        details = mapOf("line" to line),
                        message = "The CEF source patch contains an invalid target header.",
                    )
                }
                targets += match.groupValues[1]
            }
        if (targets.isEmpty()) {
            failure(
                code = "runtime.source-patch.patch-empty",
                details = mapOf("path" to patchPath.toString()),
                message = "The CEF source patch contains no file changes.",
            )
        }
        return targets
    }

    private fun requireDigest(path: Path, expected: String, suffix: String) {
        val actual = sha256(path)
        if (actual != expected) {
            failure(
                code = "runtime.source-patch.$suffix",
                details = mapOf("path" to path.toString(), "expected" to expected, "actual" to actual),
                message = "A CEF source patch file failed SHA-256 verification.",
            )
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Files.newInputStream(path).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (error: Exception) {
            failure(
                code = "runtime.source-patch.file-read-failed",
                details = mapOf("path" to path.toString()),
                message = "A CEF source patch file could not be read.",
                cause = error,
            )
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }

    private fun resolveRegularFile(root: Path, relative: String, suffix: String): Path {
        val path = resolve(root, relative)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            failure(
                code = "runtime.source-patch.$suffix",
                details = mapOf("path" to path.toString()),
                message = "A CEF source patch path must identify a regular non-symbolic-link file.",
            )
        }
        return path
    }

    private fun resolve(root: Path, relative: String): Path {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val resolved = normalizedRoot.resolve(relative).normalize()
        if (!resolved.startsWith(normalizedRoot)) {
            failure(
                code = "runtime.source-patch.path-escape",
                details = mapOf("root" to normalizedRoot.toString(), "path" to relative),
                message = "A CEF source patch path escapes its declared root.",
            )
        }
        return resolved
    }

    private fun runGit(root: Path, vararg arguments: String): String = runProcess(
        workingDirectory = root,
        command = listOf("git", "-C", root.toString()) + arguments,
        failureCode = "runtime.source-patch.git-failed",
    )

    private fun applyPatch(
        workingDirectory: Path,
        patchPath: Path,
        includedPaths: List<String> = emptyList(),
        failureCode: String,
    ): String = runProcess(
        workingDirectory = workingDirectory,
        command = canonicalPatchApplyCommand(patchPath, includedPaths),
        failureCode = failureCode,
    )

    internal fun canonicalPatchApplyCommand(patchPath: Path, includedPaths: List<String> = emptyList()): List<String> =
        listOf(
            "git",
            "-c",
            "core.autocrlf=false",
            "-c",
            "core.eol=lf",
            "apply",
            "--whitespace=error-all",
        ) + includedPaths.map { path -> "--include=$path" } + patchPath.toString()

    private fun runProcess(workingDirectory: Path, command: List<String>, failureCode: String): String {
        val process = try {
            ProcessBuilder(command)
                .directory(workingDirectory.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (error: Exception) {
            failure(
                code = failureCode,
                details = mapOf("command" to command.first()),
                message = "Unable to start CEF source patch verification.",
                cause = error,
            )
        }
        if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            failure(
                code = "runtime.source-patch.process-timeout",
                details = mapOf("command" to command.joinToString(" ")),
                message = "CEF source patch verification exceeded its time limit.",
            )
        }
        val output = process.inputStream.readBytes()
        if (output.size > MAXIMUM_PROCESS_OUTPUT_BYTES) {
            failure(
                code = "runtime.source-patch.process-output-too-large",
                details = mapOf("command" to command.first(), "size" to output.size.toString()),
                message = "CEF source patch verification produced excessive process output.",
            )
        }
        val text = output.toString(Charsets.UTF_8)
        if (process.exitValue() != 0) {
            failure(
                code = failureCode,
                details = mapOf(
                    "command" to command.joinToString(" "),
                    "exitCode" to process.exitValue().toString(),
                    "output" to text.trim().take(MAXIMUM_ERROR_TEXT_LENGTH),
                ),
                message = "CEF source patch verification command failed.",
            )
        }
        return text
    }

    private fun overlayInvalid(path: Path): Nothing = failure(
        code = "runtime.source-patch.overlay-entry-invalid",
        details = mapOf("path" to path.toString()),
        message = "Applying the CEF patch produced a non-regular overlay entry.",
    )

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun failure(
        code: String,
        details: Map<String, String>,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw CefSourcePatchVerificationException(code, details, message, cause)

    private const val PROCESS_TIMEOUT_SECONDS: Long = 30
    private const val MAXIMUM_PATCH_BYTES: Int = 2 * 1024 * 1024
    private const val MAXIMUM_PROCESS_OUTPUT_BYTES: Int = 1024 * 1024
    private const val MAXIMUM_ERROR_TEXT_LENGTH: Int = 4096
    private const val ADAPTER_ABI_HEADER: String = "include/internal/cef_kweb_extension_abi.h"
    private val PATCH_TARGET: Regex = Regex("diff --git a/([^ ]+) b/([^ ]+)")
    private val SHA256_HEX: Regex = Regex("[0-9a-f]{64}")
}
