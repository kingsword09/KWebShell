package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.Deflater

internal data class KWebRuntimePayloadBuildRequest(
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
    val cefRoot: Path,
    val nativeReleaseDirectory: Path,
    val nativeContractDirectory: Path,
    val outputArchive: Path,
)

internal data class KWebRuntimePayloadBuildResult(
    val archive: Path,
    val archiveSha256: String,
    val manifest: KWebRuntimePayloadManifest,
)

internal object KWebRuntimePayloadAssembler {
    fun build(request: KWebRuntimePayloadBuildRequest): KWebRuntimePayloadBuildResult {
        val inputs = validateRequest(request)
        val collected = mutableListOf<CollectedPayloadEntry>()
        collectLicenses(inputs, collected)
        collectNativeClosure(inputs, collected)
        collectRuntime(inputs, collected)

        val sorted = collected.sortedWith { left, right ->
            KWebRuntimePayloadContract.pathComparator.compare(left.entry.path, right.entry.path)
        }
        payloadRequire(
            sorted.map { it.entry.path }.toSet().size == sorted.size,
            code = "runtime.payload.input-entry-duplicate",
            message = "The runtime payload inputs map more than once to the same archive path.",
        )

        val entries = sorted.map(CollectedPayloadEntry::entry)
        val catalogManifest = request.catalog.manifest
        val manifest = KWebRuntimePayloadManifest(
            schemaVersion = KWEB_RUNTIME_PAYLOAD_SCHEMA_VERSION,
            product = KWEB_RUNTIME_PAYLOAD_PRODUCT,
            productVersion = request.productVersion,
            target = request.target.id,
            cefVersion = catalogManifest.cefVersion,
            chromiumVersion = catalogManifest.chromiumVersion,
            sourceArtifact = KWebRuntimePayloadContract.sourceArtifact(request.catalog.artifact(request.target)),
            treeSha256 = KWebRuntimePayloadManifestCodec.treeSha256(entries),
            entries = entries,
        )
        KWebRuntimePayloadContract.validateManifest(
            manifest = manifest,
            catalog = request.catalog,
            target = request.target,
            productVersion = request.productVersion,
        )

        val manifestBytes = KWebRuntimePayloadManifestCodec.encode(manifest)
        val archiveEntries = (sorted.map(CollectedPayloadEntry::toArchiveEntry) + archiveManifest(manifestBytes))
            .sortedWith { left, right ->
                KWebRuntimePayloadContract.pathComparator.compare(left.path, right.path)
            }
        val temporary = createSiblingTemporary(inputs.outputArchive)
        var primaryFailure: Throwable? = null
        val archiveSha256 = try {
            writeArchive(temporary, archiveEntries)
            val verified = KWebRuntimePayloadVerifier.verify(
                KWebRuntimePayloadVerificationRequest(
                    archive = temporary,
                    catalog = request.catalog,
                    target = request.target,
                    productVersion = request.productVersion,
                ),
            )
            payloadRequire(
                verified == manifest,
                code = "runtime.payload.builder-verifier-disagreement",
                message = "The independently verified manifest differs from the assembled manifest.",
            )
            val digest = sha256(temporary)
            publishAtomically(temporary, inputs.outputArchive)
            digest
        } catch (error: KWebRuntimePayloadException) {
            primaryFailure = error
            throw error
        } catch (error: Exception) {
            val failure = KWebRuntimePayloadException(
                code = "runtime.payload.build-failed",
                details = mapOf("output" to inputs.outputArchive.toString()),
                message = "Unable to build the KWebShell runtime payload.",
                cause = error,
            )
            primaryFailure = failure
            throw failure
        } finally {
            try {
                Files.deleteIfExists(temporary)
            } catch (error: Exception) {
                val cleanupFailure = KWebRuntimePayloadException(
                    code = "runtime.payload.temporary-cleanup-failed",
                    details = mapOf("path" to temporary.toString()),
                    message = "Unable to remove the runtime payload temporary archive.",
                    cause = error,
                )
                if (primaryFailure == null) {
                    throw cleanupFailure
                }
                primaryFailure.addSuppressed(cleanupFailure)
            }
        }

        return KWebRuntimePayloadBuildResult(
            archive = inputs.outputArchive,
            archiveSha256 = archiveSha256,
            manifest = manifest,
        )
    }

    private fun validateRequest(request: KWebRuntimePayloadBuildRequest): ValidatedInputs {
        KWebRuntimePayloadContract.validateProductVersion(request.productVersion)
        val cefRoot = requireAbsoluteDirectory(request.cefRoot, "cefRoot")
        val nativeRelease = requireAbsoluteDirectory(request.nativeReleaseDirectory, "nativeReleaseDirectory")
        val nativeContract = requireAbsoluteDirectory(request.nativeContractDirectory, "nativeContractDirectory")
        val output = requireAbsoluteNormalized(request.outputArchive, "outputArchive")

        val artifact = request.catalog.artifact(request.target)
        val expectedCefRootName = KWebRuntimePayloadContract.expectedCefRootName(artifact)
        payloadRequire(
            cefRoot.fileName?.toString() == expectedCefRootName,
            code = "runtime.payload.cef-root-name-mismatch",
            details = mapOf(
                "actual" to (cefRoot.fileName?.toString() ?: ""),
                "expected" to expectedCefRootName,
            ),
            message = "The extracted CEF root does not match the catalog artifact directory.",
        )
        payloadRequire(
            nativeRelease.fileName?.toString() == "Release",
            code = "runtime.payload.native-release-name-invalid",
            details = mapOf("path" to nativeRelease.toString()),
            message = "The native runtime input must be the exact CMake Release directory.",
        )
        payloadRequire(
            nativeContract.fileName?.toString() == "contract",
            code = "runtime.payload.native-contract-name-invalid",
            details = mapOf("path" to nativeContract.toString()),
            message = "The native binding input must be the exact CMake contract directory.",
        )
        payloadRequire(
            output.fileName?.toString()?.endsWith(".zip") == true,
            code = "runtime.payload.output-name-invalid",
            details = mapOf("path" to output.toString()),
            message = "The runtime payload output must have a .zip file name.",
        )

        val outputParent = output.parent
            ?: payloadFailure(
                code = "runtime.payload.output-parent-missing",
                details = mapOf("path" to output.toString()),
                message = "The runtime payload output does not have a parent directory.",
            )
        requireDirectory(outputParent, "outputParent")
        payloadRequire(
            listOf(cefRoot, nativeRelease, nativeContract).none(output::startsWith),
            code = "runtime.payload.output-overlaps-input",
            details = mapOf("path" to output.toString()),
            message = "The runtime payload output cannot be placed inside an input tree.",
        )
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            payloadRequire(
                Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output),
                code = "runtime.payload.output-type-invalid",
                details = mapOf("path" to output.toString()),
                message = "The existing runtime payload output is not a regular file.",
            )
        }

        val license = requireNonEmptyRegularFile(cefRoot.resolve("LICENSE.txt"), "CEF license")
        val credits = requireNonEmptyRegularFile(cefRoot.resolve("CREDITS.html"), "CEF credits")
        return ValidatedInputs(
            request = request,
            nativeRelease = nativeRelease,
            nativeContract = nativeContract,
            outputArchive = output,
            license = license,
            credits = credits,
        )
    }

    private fun collectLicenses(inputs: ValidatedInputs, output: MutableList<CollectedPayloadEntry>) {
        output += directoryEntry("licenses/")
        output += regularFileEntry(
            source = inputs.license,
            payloadPath = "licenses/CEF-LICENSE.txt",
            mode = KWEB_RUNTIME_PAYLOAD_FILE_MODE,
            requireNonEmpty = true,
        )
        output += regularFileEntry(
            source = inputs.credits,
            payloadPath = "licenses/CEF-CREDITS.html",
            mode = KWEB_RUNTIME_PAYLOAD_FILE_MODE,
            requireNonEmpty = true,
        )
    }

    private fun collectNativeClosure(inputs: ValidatedInputs, output: MutableList<CollectedPayloadEntry>) {
        output += directoryEntry("native/")
        val expected = KWebRuntimePayloadContract.nativeClosure(inputs.request.target)
        val expectedNames = expected.mapTo(linkedSetOf(), KWebRuntimePayloadNativeSpec::name)
        val presentKnownNames = try {
            Files.newDirectoryStream(inputs.nativeContract).use { stream ->
                stream
                    .mapNotNull { it.fileName?.toString() }
                    .filterTo(linkedSetOf()) { it in KWebRuntimePayloadContract.knownNativeRuntimeNames() }
            }
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.native-contract-read-failed",
                details = mapOf("path" to inputs.nativeContract.toString()),
                message = "Unable to enumerate the native contract directory.",
                cause = error,
            )
        }
        payloadRequire(
            (presentKnownNames - expectedNames).isEmpty(),
            code = "runtime.payload.native-target-mismatch",
            details = mapOf(
                "target" to inputs.request.target.id,
                "unexpected" to (presentKnownNames - expectedNames).joinToString(),
            ),
            message = "The native contract directory contains runtime libraries for another target.",
        )

        expected.forEach { spec ->
            val source = inputs.nativeContract.resolve(spec.name)
            when (spec.type) {
                KWebRuntimePayloadEntryType.FILE -> output += regularFileEntry(
                    source = source,
                    payloadPath = "native/${spec.name}",
                    mode = KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE,
                    requireNonEmpty = true,
                )

                KWebRuntimePayloadEntryType.SYMLINK -> output += symbolicLinkEntry(
                    sourceRoot = inputs.nativeContract,
                    source = source,
                    payloadPath = "native/${spec.name}",
                    expectedTarget = spec.linkTarget,
                )

                KWebRuntimePayloadEntryType.DIRECTORY -> payloadFailure(
                    code = "runtime.payload.native-spec-invalid",
                    details = mapOf("name" to spec.name),
                    message = "A native runtime closure entry cannot be a directory.",
                )
            }
        }
    }

    private fun collectRuntime(inputs: ValidatedInputs, output: MutableList<CollectedPayloadEntry>) {
        val target = inputs.request.target
        val runtimeRoot: Path
        val payloadPrefix: String
        if (target.operatingSystem == KWebOperatingSystem.MACOS) {
            runtimeRoot = requireDirectory(inputs.nativeRelease.resolve("KWebShell.app"), "macOS KWebShell.app")
            payloadPrefix = "runtime/KWebShell.app"
            output += directoryEntry("runtime/")
        } else {
            runtimeRoot = inputs.nativeRelease
            payloadPrefix = "runtime"
        }
        val startSize = output.size
        collectTree(
            sourceRoot = runtimeRoot,
            payloadPrefix = payloadPrefix,
            target = target,
            output = output,
        )
        payloadRequire(
            output.drop(startSize).any { it.entry.type == KWebRuntimePayloadEntryType.FILE },
            code = "runtime.payload.runtime-input-empty",
            details = mapOf("path" to runtimeRoot.toString()),
            message = "The native Release tree does not contain runtime files.",
        )
    }

    private fun collectTree(
        sourceRoot: Path,
        payloadPrefix: String,
        target: KWebTarget,
        output: MutableList<CollectedPayloadEntry>,
    ) {
        try {
            Files.walkFileTree(
                sourceRoot,
                emptySet(),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                        payloadRequire(
                            attributes.isDirectory && !attributes.isSymbolicLink,
                            code = "runtime.payload.input-directory-invalid",
                            details = mapOf("path" to directory.toString()),
                            message = "The runtime payload input contains an invalid directory.",
                        )
                        output += directoryEntry(payloadPath(sourceRoot, directory, payloadPrefix, directory = true))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        val path = payloadPath(sourceRoot, file, payloadPrefix, directory = false)
                        when {
                            attributes.isSymbolicLink -> output += symbolicLinkEntry(
                                sourceRoot = sourceRoot,
                                source = file,
                                payloadPath = path,
                                expectedTarget = null,
                            )

                            attributes.isRegularFile -> output += regularFileEntry(
                                source = file,
                                payloadPath = path,
                                mode = normalizedRuntimeFileMode(target, path, file),
                                requireNonEmpty = false,
                            )

                            else -> payloadFailure(
                                code = "runtime.payload.input-special-file",
                                details = mapOf("path" to file.toString()),
                                message = "The runtime payload input contains a special filesystem entry.",
                            )
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: java.io.IOException): FileVisitResult =
                        payloadFailure(
                            code = "runtime.payload.input-walk-failed",
                            details = mapOf("path" to file.toString()),
                            message = "Unable to inspect a runtime payload input path.",
                            cause = error,
                        )
                },
            )
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.input-walk-failed",
                details = mapOf("path" to sourceRoot.toString()),
                message = "Unable to walk the runtime payload input tree.",
                cause = error,
            )
        }
    }

    private fun regularFileEntry(
        source: Path,
        payloadPath: String,
        mode: String,
        requireNonEmpty: Boolean,
    ): CollectedPayloadEntry {
        KWebRuntimePayloadContract.validatePayloadEntryPath(payloadPath, KWebRuntimePayloadEntryType.FILE)
        val attributes = readAttributes(source)
        payloadRequire(
            attributes.isRegularFile && !attributes.isSymbolicLink,
            code = "runtime.payload.input-file-invalid",
            details = mapOf("path" to source.toString()),
            message = "A required runtime payload input is not a regular file.",
        )
        val digest = digestFile(source)
        payloadRequire(
            !requireNonEmpty || digest.size > 0L,
            code = "runtime.payload.input-file-empty",
            details = mapOf("path" to source.toString()),
            message = "A required runtime payload input is empty.",
        )
        return CollectedPayloadEntry(
            entry = KWebRuntimePayloadEntry(
                path = payloadPath,
                type = KWebRuntimePayloadEntryType.FILE,
                mode = mode,
                size = digest.size,
                sha256 = digest.sha256,
            ),
            crc32 = digest.crc32,
            content = PayloadContent.File(source),
        )
    }

    private fun symbolicLinkEntry(
        sourceRoot: Path,
        source: Path,
        payloadPath: String,
        expectedTarget: String?,
    ): CollectedPayloadEntry {
        KWebRuntimePayloadContract.validatePayloadEntryPath(payloadPath, KWebRuntimePayloadEntryType.SYMLINK)
        payloadRequire(
            Files.isSymbolicLink(source),
            code = "runtime.payload.input-symlink-missing",
            details = mapOf("path" to source.toString()),
            message = "A required runtime payload symbolic link is missing.",
        )
        val rawTarget = try {
            Files.readSymbolicLink(source)
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.input-symlink-read-failed",
                details = mapOf("path" to source.toString()),
                message = "Unable to read a runtime payload symbolic link.",
                cause = error,
            )
        }
        payloadRequire(
            !rawTarget.isAbsolute,
            code = "runtime.payload.symlink-target-invalid",
            details = mapOf("path" to source.toString(), "linkTarget" to rawTarget.toString()),
            message = "The runtime payload symbolic link target must be relative.",
        )
        val linkTarget = rawTarget.joinToString("/") { it.toString() }
        val normalizedRoot = sourceRoot.toAbsolutePath().normalize()
        val resolved = source.parent.resolve(rawTarget).toAbsolutePath().normalize()
        payloadRequire(
            resolved.startsWith(normalizedRoot),
            code = "runtime.payload.input-symlink-escape",
            details = mapOf("path" to source.toString(), "linkTarget" to linkTarget),
            message = "The runtime payload input symbolic link escapes its declared input tree.",
        )
        KWebRuntimePayloadContract.validateLinkTarget(payloadPath, linkTarget)
        payloadRequire(
            expectedTarget == null || linkTarget == expectedTarget,
            code = "runtime.payload.native-symlink-target-mismatch",
            details = mapOf(
                "path" to source.toString(),
                "actual" to linkTarget,
                "expected" to (expectedTarget ?: ""),
            ),
            message = "The native engine symbolic link does not match the versioned closure.",
        )
        val bytes = linkTarget.toByteArray(StandardCharsets.UTF_8)
        return CollectedPayloadEntry(
            entry = KWebRuntimePayloadEntry(
                path = payloadPath,
                type = KWebRuntimePayloadEntryType.SYMLINK,
                mode = KWEB_RUNTIME_PAYLOAD_SYMLINK_MODE,
                size = bytes.size.toLong(),
                sha256 = sha256(bytes),
                linkTarget = linkTarget,
            ),
            crc32 = crc32(bytes),
            content = PayloadContent.Bytes(bytes),
        )
    }

    private fun directoryEntry(payloadPath: String): CollectedPayloadEntry {
        KWebRuntimePayloadContract.validatePayloadEntryPath(payloadPath, KWebRuntimePayloadEntryType.DIRECTORY)
        return CollectedPayloadEntry(
            entry = KWebRuntimePayloadEntry(
                path = payloadPath,
                type = KWebRuntimePayloadEntryType.DIRECTORY,
                mode = KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE,
                size = 0L,
                sha256 = KWEB_RUNTIME_PAYLOAD_EMPTY_SHA256,
            ),
            crc32 = 0L,
            content = PayloadContent.Empty,
        )
    }

    private fun archiveManifest(bytes: ByteArray): ArchiveEntry = ArchiveEntry(
        path = KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH,
        type = KWebRuntimePayloadEntryType.FILE,
        mode = KWEB_RUNTIME_PAYLOAD_FILE_MODE,
        size = bytes.size.toLong(),
        sha256 = sha256(bytes),
        crc32 = crc32(bytes),
        content = PayloadContent.Bytes(bytes),
    )

    private fun writeArchive(path: Path, entries: List<ArchiveEntry>) {
        try {
            ZipArchiveOutputStream(
                path,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { archive ->
                archive.setEncoding(StandardCharsets.UTF_8.name())
                archive.setUseLanguageEncodingFlag(true)
                archive.setFallbackToUTF8(false)
                archive.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
                archive.setUseZip64(Zip64Mode.Never)
                archive.setLevel(Deflater.BEST_COMPRESSION)
                entries.forEach { entry -> writeArchiveEntry(archive, entry) }
                archive.finish()
            }
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.archive-write-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to write the deterministic runtime payload ZIP.",
                cause = error,
            )
        }
    }

    private fun writeArchiveEntry(archive: ZipArchiveOutputStream, item: ArchiveEntry) {
        val entry = ZipArchiveEntry(item.path)
        entry.setTimeLocal(FIXED_TIMESTAMP)
        entry.setUnixMode(unixMode(item.type, item.mode))
        entry.size = item.size
        entry.crc = item.crc32
        entry.method = if (item.type == KWebRuntimePayloadEntryType.DIRECTORY) {
            ZipArchiveOutputStream.STORED
        } else {
            ZipArchiveOutputStream.DEFLATED
        }
        archive.putArchiveEntry(entry)
        when (val content = item.content) {
            PayloadContent.Empty -> Unit
            is PayloadContent.Bytes -> archive.write(content.value)
            is PayloadContent.File -> writeFileContent(archive, content.path, item)
        }
        archive.closeArchiveEntry()
    }

    private fun writeFileContent(archive: ZipArchiveOutputStream, path: Path, expected: ArchiveEntry) {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        openRegularFileWithoutFollowingLinks(path).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                archive.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                crc.update(buffer, 0, count)
                size += count
            }
        }
        payloadRequire(
            size == expected.size && digest.digest().toHex() == expected.sha256 && crc.value == expected.crc32,
            code = "runtime.payload.input-mutated",
            details = mapOf("path" to path.toString()),
            message = "A runtime payload input changed while the archive was being written.",
        )
    }

    private fun publishAtomically(temporary: Path, output: Path) {
        try {
            Files.move(
                temporary,
                output,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            payloadFailure(
                code = "runtime.payload.atomic-move-unsupported",
                details = mapOf("temporary" to temporary.toString(), "output" to output.toString()),
                message = "The output filesystem does not support atomic runtime payload publication.",
                cause = error,
            )
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.atomic-move-failed",
                details = mapOf("temporary" to temporary.toString(), "output" to output.toString()),
                message = "Unable to publish the verified runtime payload atomically.",
                cause = error,
            )
        }
    }

    private fun createSiblingTemporary(output: Path): Path = try {
        Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
    } catch (error: Exception) {
        payloadFailure(
            code = "runtime.payload.temporary-create-failed",
            details = mapOf("output" to output.toString()),
            message = "Unable to create a sibling temporary runtime payload archive.",
            cause = error,
        )
    }

    private fun digestFile(path: Path): ContentDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            openRegularFileWithoutFollowingLinks(path).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    size += count
                }
            }
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.input-file-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to read a runtime payload input file.",
                cause = error,
            )
        }
        return ContentDigest(size = size, sha256 = digest.digest().toHex(), crc32 = crc.value)
    }

    private fun openRegularFileWithoutFollowingLinks(path: Path): InputStream {
        val channel = try {
            Files.newByteChannel(
                path,
                setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            )
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.input-file-open-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to open a runtime payload input without following symbolic links.",
                cause = error,
            )
        }
        return Channels.newInputStream(channel)
    }

    private fun readAttributes(path: Path): BasicFileAttributes = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (error: Exception) {
        payloadFailure(
            code = "runtime.payload.input-attributes-failed",
            details = mapOf("path" to path.toString()),
            message = "Unable to read runtime payload input attributes.",
            cause = error,
        )
    }

    private fun requireNonEmptyRegularFile(path: Path, description: String): Path {
        val normalized = requireAbsoluteNormalized(path, description)
        val attributes = try {
            Files.readAttributes(normalized, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.notice-invalid",
                details = mapOf("path" to normalized.toString(), "description" to description),
                message = "The required $description file is missing or unreadable.",
                cause = error,
            )
        }
        payloadRequire(
            attributes.isRegularFile && !attributes.isSymbolicLink && attributes.size() > 0L,
            code = "runtime.payload.notice-invalid",
            details = mapOf("path" to normalized.toString(), "description" to description),
            message = "The required $description file is missing, empty, or not a regular file.",
        )
        return normalized
    }

    private fun requireAbsoluteDirectory(path: Path, description: String): Path =
        requireDirectory(requireAbsoluteNormalized(path, description), description)

    private fun requireDirectory(path: Path, description: String): Path {
        val attributes = readAttributes(path)
        payloadRequire(
            attributes.isDirectory && !attributes.isSymbolicLink,
            code = "runtime.payload.input-directory-invalid",
            details = mapOf("path" to path.toString(), "description" to description),
            message = "The runtime payload $description is missing or not a real directory.",
        )
        return path
    }

    private fun requireAbsoluteNormalized(path: Path, description: String): Path {
        payloadRequire(
            path.isAbsolute,
            code = "runtime.payload.input-path-not-absolute",
            details = mapOf("path" to path.toString(), "description" to description),
            message = "Every runtime payload path must be absolute.",
        )
        val normalized = path.normalize()
        payloadRequire(
            path == normalized,
            code = "runtime.payload.input-path-not-normalized",
            details = mapOf("path" to path.toString(), "description" to description),
            message = "Every runtime payload path must be lexically normalized.",
        )
        return normalized
    }

    private fun payloadPath(root: Path, path: Path, prefix: String, directory: Boolean): String {
        val relative = root.relativize(path).joinToString("/") { it.toString() }
        val combined = if (relative.isEmpty()) prefix else "$prefix/$relative"
        return if (directory) "$combined/" else combined
    }

    private fun normalizedRuntimeFileMode(target: KWebTarget, payloadPath: String, source: Path): String {
        val name = payloadPath.substringAfterLast('/')
        val lowerName = name.lowercase(Locale.ROOT)
        val targetExecutable = when (target.operatingSystem) {
            KWebOperatingSystem.WINDOWS -> lowerName.endsWith(".exe") || lowerName.endsWith(".dll")
            KWebOperatingSystem.MACOS ->
                payloadPath.contains("/Contents/MacOS/") ||
                    lowerName.endsWith(".dylib") ||
                    name == "Chromium Embedded Framework"

            KWebOperatingSystem.LINUX ->
                name in setOf("KWebShell", "chrome-sandbox") ||
                    lowerName.endsWith(".so") ||
                    lowerName.contains(".so.")
        }
        return if (targetExecutable || isPosixExecutable(target, source)) {
            KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE
        } else {
            KWEB_RUNTIME_PAYLOAD_FILE_MODE
        }
    }

    private fun isPosixExecutable(target: KWebTarget, source: Path): Boolean {
        if (target.operatingSystem == KWebOperatingSystem.WINDOWS) return false
        return try {
            val view = Files.getFileAttributeView(
                source,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ) ?: return false
            view.readAttributes()
                .permissions()
                .any { permission -> permission in EXECUTE_PERMISSIONS }
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.input-permissions-read-failed",
                details = mapOf("path" to source.toString()),
                message = "Unable to read runtime payload executable permissions.",
                cause = error,
            )
        }
    }

    private fun unixMode(type: KWebRuntimePayloadEntryType, mode: String): Int {
        val permissions = mode.toInt(radix = 8)
        return when (type) {
            KWebRuntimePayloadEntryType.DIRECTORY -> UnixStat.DIR_FLAG or permissions
            KWebRuntimePayloadEntryType.FILE -> UnixStat.FILE_FLAG or permissions
            KWebRuntimePayloadEntryType.SYMLINK -> UnixStat.LINK_FLAG or permissions
        }
    }

    private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private data class ValidatedInputs(
        val request: KWebRuntimePayloadBuildRequest,
        val nativeRelease: Path,
        val nativeContract: Path,
        val outputArchive: Path,
        val license: Path,
        val credits: Path,
    )

    private data class ContentDigest(
        val size: Long,
        val sha256: String,
        val crc32: Long,
    )

    private sealed interface PayloadContent {
        data object Empty : PayloadContent

        data class Bytes(val value: ByteArray) : PayloadContent

        data class File(val path: Path) : PayloadContent
    }

    private data class CollectedPayloadEntry(
        val entry: KWebRuntimePayloadEntry,
        val crc32: Long,
        val content: PayloadContent,
    ) {
        fun toArchiveEntry(): ArchiveEntry = ArchiveEntry(
            path = entry.path,
            type = entry.type,
            mode = entry.mode,
            size = entry.size,
            sha256 = entry.sha256,
            crc32 = crc32,
            content = content,
        )
    }

    private data class ArchiveEntry(
        val path: String,
        val type: KWebRuntimePayloadEntryType,
        val mode: String,
        val size: Long,
        val sha256: String,
        val crc32: Long,
        val content: PayloadContent,
    )

    private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
    private val EXECUTE_PERMISSIONS: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_EXECUTE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_EXECUTE,
    )
}
