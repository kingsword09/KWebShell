package io.github.kingsword09.kwebshell.extensions

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.text.Normalizer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

public data class KWebVerifiedExtension(
    public val packageInfo: KWebExtensionPackage,
    public val source: Path,
)

public object JvmKWebExtensionPackageVerifier {
    public fun verifyUnpacked(
        root: Path,
        policy: KWebExtensionPermissionPolicy = KWebExtensionPermissionPolicy(),
    ): KWebVerifiedExtension = ioBoundary(
        code = "extensions.package.io-failed",
        path = root,
        message = "The unpacked extension could not be inspected.",
    ) {
        val normalizedRoot = root.toAbsolutePath().normalize()
        requireDirectory(normalizedRoot, "extensions.package.root-invalid")
        rejectSymlinkTree(normalizedRoot)
        val manifestPath = normalizedRoot.resolve("manifest.json")
        if (!manifestPath.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            extensionFailure(
                code = "extensions.manifest.file-missing",
                details = mapOf("path" to manifestPath.toString()),
                message = "An unpacked extension must contain a regular manifest.json file.",
            )
        }
        val manifestBytes = readRegularFileBounded(
            manifestPath,
            MAX_MANIFEST_BYTES,
            "extensions.manifest.file-too-large",
            "The unpacked manifest exceeds the bounded size.",
        )
        val manifest = KWebExtensionManifestParser.parse(decodeUtf8(manifestBytes), requirePublicKey = true)
        val publicKeyDer = decodeKWebPublicKeyDer(manifest.key!!)
        decodePublicKeyBytes(publicKeyDer)
        val extensionId = extensionId(publicKeyDer)
        validateReferencedResources(normalizedRoot, manifest)
        KWebVerifiedExtension(
            packageInfo = KWebExtensionPackage(
                manifest = manifest,
                extensionId = extensionId,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKeyDer),
                permissionReview = policy.review(manifest),
                format = KWebExtensionPackageFormat.UNPACKED,
            ),
            source = normalizedRoot,
        )
    }

    public fun verifyCrx3(
        path: Path,
        policy: KWebExtensionPermissionPolicy = KWebExtensionPermissionPolicy(),
    ): KWebVerifiedExtension = ioBoundary(
        code = "extensions.crx3.io-failed",
        path = path,
        message = "The CRX3 package could not be inspected.",
    ) {
        requireRegularFile(path, "extensions.crx3.file-invalid")
        val bytes = readRegularFileBounded(
            path,
            MAX_CRX_BYTES,
            "extensions.crx3.file-too-large",
            "The CRX3 package exceeds the bounded file size.",
        )
        val crx = Crx3Reader(bytes).read()
        val archive = crx.archive
        val verifiedArchive = validateZipArchive(archive)
        val manifest = KWebExtensionManifestParser.parse(
            decodeUtf8(verifiedArchive.manifestJson),
            requirePublicKey = false,
        )
        if (manifest.key != null && decodeKWebPublicKeyDer(manifest.key).contentEquals(crx.publicKeyDer).not()) {
            extensionFailure(
                code = "extensions.crx3.manifest-key-mismatch",
                message = "The CRX3 public key does not match manifest key.",
            )
        }
        val extensionId = extensionId(crx.publicKeyDer)
        validateZipReferencedResources(verifiedArchive.entryNames, manifest)
        KWebVerifiedExtension(
            packageInfo = KWebExtensionPackage(
                manifest = manifest,
                extensionId = extensionId,
                publicKeyBase64 = Base64.getEncoder().encodeToString(crx.publicKeyDer),
                permissionReview = policy.review(manifest),
                format = KWebExtensionPackageFormat.CRX3,
            ),
            source = path.toAbsolutePath().normalize(),
        )
    }

    private inline fun <T> ioBoundary(
        code: String,
        path: Path,
        message: String,
        operation: () -> T,
    ): T = try {
        operation()
    } catch (error: KWebExtensionVerificationException) {
        throw error
    } catch (error: Exception) {
        extensionFailure(
            code = code,
            details = mapOf("path" to path.toAbsolutePath().normalize().toString()),
            message = message,
            cause = error,
        )
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        extensionFailure(
            code = "extensions.manifest.utf8-invalid",
            message = "The extension manifest is not valid UTF-8.",
            cause = error,
        )
    }

    private fun readRegularFileBounded(
        path: Path,
        limit: Long,
        tooLargeCode: String,
        tooLargeMessage: String,
    ): ByteArray {
        val options = setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
        return Files.newByteChannel(path, options).use { channel ->
            val size = channel.size()
            if (size > limit || size > Int.MAX_VALUE) {
                extensionFailure(
                    code = tooLargeCode,
                    details = mapOf("size" to size.toString(), "limit" to limit.toString()),
                    message = tooLargeMessage,
                )
            }
            val bytes = ByteArray(size.toInt())
            val target = ByteBuffer.wrap(bytes)
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) fileChangedDuringRead(path, size)
            }
            val probe = ByteBuffer.allocate(1)
            var trailingRead = channel.read(probe)
            while (trailingRead == 0) trailingRead = channel.read(probe)
            if (trailingRead >= 0) fileChangedDuringRead(path, size)
            bytes
        }
    }

    private fun fileChangedDuringRead(path: Path, expectedSize: Long): Nothing = extensionFailure(
        code = "extensions.package.file-changed-during-read",
        details = mapOf("path" to path.toString(), "expectedSize" to expectedSize.toString()),
        message = "The extension package file changed while it was being verified.",
    )

    private fun extensionId(publicKeyDer: ByteArray): String =
        KWebExtensionId.fromSha256Hash(MessageDigest.getInstance("SHA-256").digest(publicKeyDer))

    private fun validateReferencedResources(root: Path, manifest: KWebExtensionManifest) {
        referencedResourcePaths(manifest).forEach { value ->
            val path = resolvePackagePath(root, value)
            if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
                extensionFailure(
                    code = "extensions.package.resource-missing",
                    details = mapOf("path" to value),
                    message = "Manifest resource '$value' is missing or not a regular file.",
                )
            }
        }
        manifest.webAccessibleResources.flatMap { it.resources }.forEach { pattern ->
            requireUnpackedPatternMatch(root, canonicalWebAccessibleResourcePath(pattern))
        }
    }

    private fun validateZipReferencedResources(entryNames: Set<String>, manifest: KWebExtensionManifest) {
        referencedResourcePaths(manifest).forEach { value ->
            if (value !in entryNames) {
                extensionFailure(
                    code = "extensions.package.resource-missing",
                    details = mapOf("path" to value),
                    message = "Manifest resource '$value' is missing from the CRX3 archive.",
                )
            }
        }
        manifest.webAccessibleResources.flatMap { it.resources }.forEach { pattern ->
            val canonicalPattern = canonicalWebAccessibleResourcePath(pattern)
            if (entryNames.none { wildcardMatches(canonicalPattern, it) }) {
                extensionFailure(
                    code = "extensions.package.resource-pattern-unmatched",
                    details = mapOf("pattern" to pattern),
                    message = "Web accessible resource pattern '$pattern' matches no CRX3 resource.",
                )
            }
        }
    }

    private fun referencedResourcePaths(manifest: KWebExtensionManifest): Set<String> = buildSet {
        addAll(manifest.icons.values)
        manifest.background?.serviceWorker?.let(::add)
        addAll(manifest.contentScripts.flatMap { it.js + it.css })
        manifest.action?.defaultPopup?.let(::add)
        addAll(manifest.action?.defaultIcon?.values.orEmpty())
        manifest.optionsPage?.let(::add)
        manifest.optionsUi?.page?.let(::add)
        manifest.devtoolsPage?.let(::add)
        manifest.sidePanel?.defaultPath?.let(::add)
        addAll(manifest.declarativeNetRequest?.ruleResources.orEmpty().map { it.path })
    }

    private fun validateZipArchive(bytes: ByteArray): VerifiedZipArchive {
        if (bytes.size > MAX_ARCHIVE_BYTES) {
            extensionFailure(
                code = "extensions.crx3.archive-too-large",
                details = mapOf("size" to bytes.size.toString()),
                message = "The CRX3 archive exceeds the bounded package size.",
            )
        }
        val centralNames = validateZipCentralDirectory(bytes)
        val temporary = createTemporaryZip("kweb-extension-crx3-")
        try {
            writeTemporaryZip(temporary, bytes)
            ZipFile(temporary.toFile(), ZIP_LEGACY_CHARSET).use { zip ->
                val names = mutableSetOf<String>()
                val fileNames = mutableSetOf<String>()
                var declaredTotal = 0L
                var extractedTotal = 0L
                var manifestJson: ByteArray? = null
                val entries = zip.entries().asSequence().toList()
                if (entries.size > MAX_ENTRY_COUNT) {
                    extensionFailure(
                        code = "extensions.crx3.zip-entry-count-exceeded",
                        details = mapOf("count" to entries.size.toString()),
                        message = "The CRX3 archive contains too many ZIP entries.",
                    )
                }
                entries.forEach { entry ->
                    validateArchiveEntry(entry)
                    if (entry.name !in centralNames || !names.add(entry.name)) {
                        extensionFailure(
                            code = "extensions.crx3.zip-duplicate-entry",
                            details = mapOf("entry" to entry.name),
                            message = "The CRX3 archive contains a duplicate ZIP entry.",
                        )
                    }
                    declaredTotal += entry.size.coerceAtLeast(0L)
                    if (declaredTotal > MAX_UNCOMPRESSED_BYTES) {
                        extensionFailure(
                            code = "extensions.crx3.zip-uncompressed-too-large",
                            message = "The CRX3 archive exceeds the bounded uncompressed size.",
                        )
                    }
                    if (!entry.isDirectory) fileNames += entry.name
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        val manifestOutput = if (entry.name == MANIFEST_FILE_NAME && !entry.isDirectory) {
                            ByteArrayOutputStream()
                        } else {
                            null
                        }
                        val crc = CRC32()
                        var extracted = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            extracted += count
                            extractedTotal += count
                            crc.update(buffer, 0, count)
                            if (extracted > MAX_ENTRY_BYTES) {
                                extensionFailure(
                                    code = "extensions.crx3.zip-entry-too-large",
                                    details = mapOf("entry" to entry.name),
                                    message = "The CRX3 archive contains an oversized expanded ZIP entry.",
                                )
                            }
                            if (extractedTotal > MAX_UNCOMPRESSED_BYTES) {
                                extensionFailure(
                                    code = "extensions.crx3.zip-uncompressed-too-large",
                                    message = "The CRX3 archive exceeds the bounded expanded size.",
                                )
                            }
                            if (manifestOutput != null) {
                                if (extracted > MAX_MANIFEST_BYTES) {
                                    extensionFailure(
                                        code = "extensions.crx3.manifest-too-large",
                                        message = "The extension manifest exceeds the bounded size.",
                                    )
                                }
                                manifestOutput.write(buffer, 0, count)
                            }
                        }
                        if (entry.size >= 0 && extracted != entry.size) zipStructureInvalid()
                        if (crc.value != entry.crc) {
                            extensionFailure(
                                code = "extensions.crx3.zip-crc-mismatch",
                                details = mapOf("entry" to entry.name),
                                message = "The CRX3 ZIP entry content does not match its declared CRC-32.",
                            )
                        }
                        if (manifestOutput != null) manifestJson = manifestOutput.toByteArray()
                    }
                }
                if (names != centralNames) zipStructureInvalid()
                val verifiedManifest = manifestJson ?: extensionFailure(
                    code = "extensions.manifest.file-missing",
                    details = mapOf("path" to MANIFEST_FILE_NAME),
                    message = "The CRX3 archive must contain a regular manifest.json file.",
                )
                return VerifiedZipArchive(verifiedManifest, fileNames.toSet())
            }
        } catch (error: KWebExtensionVerificationException) {
            throw error
        } catch (error: Exception) {
            extensionFailure(
                code = "extensions.crx3.zip-invalid",
                message = "The CRX3 archive does not contain a valid ZIP payload.",
                cause = error,
            )
        } finally {
            deleteTemporaryZip(temporary)
        }
    }

    private fun createTemporaryZip(prefix: String): Path = try {
        Files.createTempFile(prefix, ".zip")
    } catch (error: Exception) {
        extensionFailure(
            code = "extensions.crx3.temporary-file-failed",
            message = "The verifier could not allocate its bounded temporary ZIP file.",
            cause = error,
        )
    }

    private fun writeTemporaryZip(path: Path, bytes: ByteArray) {
        try {
            Files.write(path, bytes)
        } catch (error: Exception) {
            extensionFailure(
                code = "extensions.crx3.temporary-file-failed",
                details = mapOf("path" to path.toString()),
                message = "The verifier could not write its bounded temporary ZIP file.",
                cause = error,
            )
        }
    }

    private fun deleteTemporaryZip(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (error: Exception) {
            extensionFailure(
                code = "extensions.crx3.temporary-file-cleanup-failed",
                details = mapOf("path" to path.toString()),
                message = "The verifier could not remove its temporary ZIP file.",
                cause = error,
            )
        }
    }

    private fun validateArchiveEntry(entry: ZipEntry) {
        val path = entry.name
        validateArchivePath(path)
        if (entry.isDirectory && (entry.size != 0L || entry.crc != 0L)) {
            extensionFailure(
                code = "extensions.crx3.zip-directory-not-empty",
                details = mapOf("entry" to path),
                message = "CRX3 ZIP directory entries cannot contain expanded data.",
            )
        }
        if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
            extensionFailure(
                code = "extensions.crx3.zip-compression-unsupported",
                details = mapOf("entry" to path, "method" to entry.method.toString()),
                message = "The CRX3 archive uses an unsupported ZIP compression method.",
            )
        }
        if (entry.size > MAX_ENTRY_BYTES || entry.compressedSize > MAX_ENTRY_BYTES) {
            extensionFailure(
                code = "extensions.crx3.zip-entry-too-large",
                details = mapOf("entry" to path),
                message = "The CRX3 archive contains an oversized ZIP entry.",
            )
        }
    }

    private fun resolvePackagePath(root: Path, resource: String): Path {
        val candidate = root.resolve(resource).normalize()
        if (!candidate.startsWith(root)) {
            extensionFailure(
                code = "extensions.package.resource-escape",
                details = mapOf("resource" to resource),
                message = "Manifest resource '$resource' escapes the package root.",
            )
        }
        return candidate
    }

    private fun requireUnpackedPatternMatch(root: Path, pattern: String) {
        val matched = Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile(LinkOption.NOFOLLOW_LINKS) }
                .map { root.relativize(it).toString().replace(java.io.File.separatorChar, '/') }
                .anyMatch { wildcardMatches(pattern, it) }
        }
        if (!matched) {
            extensionFailure(
                code = "extensions.package.resource-pattern-unmatched",
                details = mapOf("pattern" to pattern),
                message = "Web accessible resource pattern '$pattern' matches no unpacked resource.",
            )
        }
    }

    private fun wildcardMatches(pattern: String, value: String): Boolean {
        var patternIndex = 0
        var valueIndex = 0
        var wildcardIndex = -1
        var wildcardValueIndex = -1
        while (valueIndex < value.length) {
            when {
                patternIndex < pattern.length && pattern[patternIndex] == value[valueIndex] -> {
                    patternIndex += 1
                    valueIndex += 1
                }
                patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                    wildcardIndex = patternIndex++
                    wildcardValueIndex = valueIndex
                }
                wildcardIndex >= 0 -> {
                    patternIndex = wildcardIndex + 1
                    valueIndex = ++wildcardValueIndex
                }
                else -> return false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex += 1
        return patternIndex == pattern.length
    }

    private fun rejectSymlinkTree(root: Path) {
        var entryCount = 0
        var totalSize = 0L
        val portableNames = mutableMapOf<String, String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file)) {
                    extensionFailure(
                        code = "extensions.package.symlink-rejected",
                        details = mapOf("path" to file.toString()),
                        message = "Unpacked extension packages cannot contain symbolic links.",
                    )
                }
                validatePortableTreePath(root, file, portableNames)
                if (!attrs.isRegularFile) {
                    extensionFailure(
                        code = "extensions.package.file-type-invalid",
                        details = mapOf("path" to file.toString()),
                        message = "Unpacked extension packages may contain only regular files and directories.",
                    )
                }
                entryCount += 1
                totalSize += attrs.size()
                if (entryCount > MAX_ENTRY_COUNT || totalSize > MAX_UNCOMPRESSED_BYTES || attrs.size() > MAX_ENTRY_BYTES) {
                    extensionFailure(
                        code = "extensions.package.bounds-exceeded",
                        details = mapOf("entries" to entryCount.toString(), "size" to totalSize.toString()),
                        message = "The unpacked extension exceeds package file or size limits.",
                    )
                }
                return FileVisitResult.CONTINUE
            }

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir)) {
                    extensionFailure(
                        code = "extensions.package.symlink-rejected",
                        details = mapOf("path" to dir.toString()),
                        message = "Unpacked extension packages cannot contain symbolic links.",
                    )
                }
                if (dir != root) {
                    entryCount += 1
                    if (entryCount > MAX_ENTRY_COUNT) {
                        extensionFailure(
                            code = "extensions.package.bounds-exceeded",
                            details = mapOf("entries" to entryCount.toString(), "size" to totalSize.toString()),
                            message = "The unpacked extension exceeds package entry limits.",
                        )
                    }
                    validatePortableTreePath(root, dir, portableNames)
                }
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun validatePortableTreePath(root: Path, path: Path, names: MutableMap<String, String>) {
        val relative = root.relativize(path).toString().replace(java.io.File.separatorChar, '/')
        val issue = portablePathIssue(relative)
        if (issue != null) {
            extensionFailure(
                code = "extensions.package.path-nonportable",
                details = mapOf("path" to relative, "issue" to issue.name),
                message = "The unpacked extension contains a path that is not portable across desktop filesystems.",
            )
        }
        val previous = registerPortableName(relative, names)
        if (previous != null && previous != relative) {
            extensionFailure(
                code = "extensions.package.path-collision",
                details = mapOf("first" to previous, "second" to relative),
                message = "The unpacked extension contains case-insensitive or Unicode-normalized path collisions.",
            )
        }
    }

    private fun requireDirectory(path: Path, code: String) {
        if (!path.isDirectory(LinkOption.NOFOLLOW_LINKS)) {
            extensionFailure(code, mapOf("path" to path.toString()), "The unpacked extension root is not a directory.")
        }
    }

    private fun requireRegularFile(path: Path, code: String) {
        if (!path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            extensionFailure(code, mapOf("path" to path.toString()), "The extension package is not a regular file.")
        }
    }

    private fun validateZipCentralDirectory(bytes: ByteArray): Set<String> {
        val eocd = findEndOfCentralDirectory(bytes)
        val entryCount = readUnsignedShort(bytes, eocd + 10)
        val diskNumber = readUnsignedShort(bytes, eocd + 4)
        val centralDisk = readUnsignedShort(bytes, eocd + 6)
        val entriesOnDisk = readUnsignedShort(bytes, eocd + 8)
        val centralSize = readUnsignedInt(bytes, eocd + 12)
        val centralOffset = readUnsignedInt(bytes, eocd + 16)
        if (diskNumber != 0 || centralDisk != 0 || entriesOnDisk != entryCount ||
            entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL ||
            centralOffset + centralSize != eocd.toLong() || entryCount > MAX_ENTRY_COUNT
        ) {
            extensionFailure(
                code = "extensions.crx3.zip64-unsupported",
                message = "Zip64 and inconsistent central directory metadata are not accepted.",
            )
        }
        var cursor = centralOffset.toInt()
        val ranges = mutableListOf<IntRange>()
        val portableNames = mutableMapOf<String, String>()
        repeat(entryCount) {
            if (cursor < 0 || cursor > bytes.size - 46 || readUnsignedInt(bytes, cursor) != CENTRAL_DIRECTORY_SIGNATURE) {
                zipStructureInvalid()
            }
            val flags = readUnsignedShort(bytes, cursor + 8)
            val compressionMethod = readUnsignedShort(bytes, cursor + 10)
            if (flags and ENCRYPTED_FLAG != 0) {
                extensionFailure(
                    code = "extensions.crx3.zip-encrypted-entry",
                    message = "Encrypted ZIP entries are not accepted in CRX3 packages.",
                )
            }
            if (compressionMethod != ZipEntry.STORED && compressionMethod != ZipEntry.DEFLATED) {
                extensionFailure(
                    code = "extensions.crx3.zip-compression-unsupported",
                    details = mapOf("method" to compressionMethod.toString()),
                    message = "The CRX3 archive uses an unsupported ZIP compression method.",
                )
            }
            val allowedFlags = DATA_DESCRIPTOR_FLAG or UTF8_FLAG or
                if (compressionMethod == ZipEntry.DEFLATED) DEFLATE_OPTION_FLAGS else 0
            if (flags and allowedFlags.inv() and 0xffff != 0) {
                extensionFailure(
                    code = "extensions.crx3.zip-flags-unsupported",
                    details = mapOf("flags" to flags.toString()),
                    message = "The CRX3 archive uses unsupported or ambiguous ZIP entry flags.",
                )
            }
            val crc = readUnsignedInt(bytes, cursor + 16)
            val compressedSize = readUnsignedInt(bytes, cursor + 20)
            val uncompressedSize = readUnsignedInt(bytes, cursor + 24)
            val nameLength = readUnsignedShort(bytes, cursor + 28)
            val extraLength = readUnsignedShort(bytes, cursor + 30)
            val commentLength = readUnsignedShort(bytes, cursor + 32)
            val externalAttributes = readUnsignedInt(bytes, cursor + 38)
            val entryDisk = readUnsignedShort(bytes, cursor + 34)
            val localOffset = readUnsignedInt(bytes, cursor + 42)
            if (entryDisk != 0 || compressedSize == 0xffffffffL ||
                uncompressedSize == 0xffffffffL || localOffset == 0xffffffffL
            ) {
                extensionFailure(
                    code = "extensions.crx3.zip64-unsupported",
                    message = "Zip64 entry metadata is not accepted.",
                )
            }
            val madeBySystem = bytes[cursor + 5].toInt() and 0xff
            val unixMode = if (madeBySystem in UNIX_ZIP_SYSTEMS) (externalAttributes ushr 16).toInt() else 0
            if (unixMode and 0xf000 == 0xa000) {
                extensionFailure(
                    code = "extensions.crx3.zip-symlink-rejected",
                    message = "CRX3 ZIP archives cannot contain Unix symbolic links.",
                )
            }
            if (unixMode != 0) {
                val fileType = unixMode and 0xf000
                if (fileType != 0x8000 && fileType != 0x4000) {
                    extensionFailure(
                        code = "extensions.crx3.zip-file-type-invalid",
                        message = "CRX3 ZIP archives may contain only regular files and directories.",
                    )
                }
            }
            val local = localOffset.toInt()
            if (local < 0 || local > bytes.size - 30 || readUnsignedInt(bytes, local) != LOCAL_FILE_SIGNATURE) {
                zipStructureInvalid()
            }
            val localFlags = readUnsignedShort(bytes, local + 6)
            val localMethod = readUnsignedShort(bytes, local + 8)
            val localCrc = readUnsignedInt(bytes, local + 14)
            val localCompressedSize = readUnsignedInt(bytes, local + 18)
            val localUncompressedSize = readUnsignedInt(bytes, local + 22)
            val localNameLength = readUnsignedShort(bytes, local + 26)
            val localExtraLength = readUnsignedShort(bytes, local + 28)
            validateZipExtraFields(bytes, cursor + 46 + nameLength, extraLength)
            validateZipExtraFields(bytes, local + 30 + localNameLength, localExtraLength)
            if (localFlags != flags || localMethod != compressionMethod) {
                extensionFailure(
                    code = "extensions.crx3.zip-flags-mismatch",
                    message = "The CRX3 ZIP local and central entry flags do not match.",
                )
            }
            if (flags and 8 == 0 &&
                (localCrc != crc || localCompressedSize != compressedSize || localUncompressedSize != uncompressedSize)
            ) {
                extensionFailure(
                    code = "extensions.crx3.zip-entry-metadata-mismatch",
                    message = "The CRX3 ZIP local and central entry metadata do not match.",
                )
            }
            if (flags and DATA_DESCRIPTOR_FLAG != 0) {
                val metadataEmpty = localCrc == 0L && localCompressedSize == 0L && localUncompressedSize == 0L
                val metadataComplete = localCrc == crc && localCompressedSize == compressedSize &&
                    localUncompressedSize == uncompressedSize
                if (!metadataEmpty && !metadataComplete) {
                    extensionFailure(
                        code = "extensions.crx3.zip-entry-metadata-mismatch",
                        message = "The CRX3 ZIP local descriptor metadata is partial or inconsistent.",
                    )
                }
            }
            if (compressedSize > MAX_ENTRY_BYTES || uncompressedSize > MAX_ENTRY_BYTES) {
                extensionFailure(
                    code = "extensions.crx3.zip-entry-too-large",
                    message = "The CRX3 archive contains an oversized ZIP entry.",
                )
            }
            val centralNameStart = cursor + 46
            val localNameStart = local + 30
            if (centralNameStart > bytes.size - nameLength || localNameStart > bytes.size - localNameLength ||
                cursor > bytes.size - 46 - nameLength - extraLength - commentLength ||
                local > bytes.size - 30 - localNameLength - localExtraLength
            ) {
                zipStructureInvalid()
            }
            if (nameLength != localNameLength ||
                !bytes.copyOfRange(centralNameStart, centralNameStart + nameLength)
                    .contentEquals(bytes.copyOfRange(localNameStart, localNameStart + localNameLength))
            ) {
                extensionFailure(
                    code = "extensions.crx3.zip-entry-name-mismatch",
                    message = "The CRX3 ZIP local and central entry names do not match.",
                )
            }
            val entryName = decodeZipEntryName(bytes, centralNameStart, nameLength, flags)
            validateArchivePath(entryName)
            val declaredDirectory = entryName.endsWith('/')
            val unixFileType = unixMode and 0xf000
            if (unixFileType != 0 &&
                (declaredDirectory && unixFileType != 0x4000 || !declaredDirectory && unixFileType != 0x8000)
            ) {
                extensionFailure(
                    code = "extensions.crx3.zip-file-type-mismatch",
                    details = mapOf("entry" to entryName),
                    message = "The CRX3 ZIP path and Unix file type disagree.",
                )
            }
            val previous = registerPortableName(entryName, portableNames)
            if (previous != null) {
                extensionFailure(
                    code = if (previous == entryName) {
                        "extensions.crx3.zip-duplicate-entry"
                    } else {
                        "extensions.crx3.zip-path-collision"
                    },
                    details = mapOf("first" to previous, "second" to entryName),
                    message = "The CRX3 archive contains duplicate or non-portable colliding entry paths.",
                )
            }
            val dataStart = local + 30 + localNameLength + localExtraLength
            val dataEnd = dataStart + compressedSize.toInt()
            if (dataStart < 0 || dataEnd < dataStart || dataEnd > centralOffset) zipStructureInvalid()
            val entryEnd = if (flags and DATA_DESCRIPTOR_FLAG != 0) {
                validateDataDescriptor(bytes, dataEnd, centralOffset.toInt(), crc, compressedSize, uncompressedSize)
            } else {
                dataEnd
            }
            ranges += local until entryEnd
            cursor += 46 + nameLength + extraLength + commentLength
        }
        if (cursor.toLong() != centralOffset + centralSize) zipStructureInvalid()
        val sortedRanges = ranges.sortedBy { it.first }
        if (sortedRanges.isEmpty() || sortedRanges.first().first != 0 ||
            sortedRanges.last().last + 1 != centralOffset.toInt()
        ) {
            extensionFailure(
                code = "extensions.crx3.zip-unreferenced-data",
                message = "The CRX3 ZIP payload contains unreferenced data outside declared entries.",
            )
        }
        sortedRanges.zipWithNext().forEach { (current, next) ->
            if (next.first <= current.last) {
                extensionFailure(
                    code = "extensions.crx3.zip-overlapping-entry",
                    message = "The CRX3 ZIP archive contains overlapping local entries.",
                )
            }
            if (current.last + 1 != next.first) {
                extensionFailure(
                    code = "extensions.crx3.zip-unreferenced-data",
                    message = "The CRX3 ZIP payload contains data between declared entries.",
                )
            }
        }
        return portableNames.values.toSet()
    }

    private fun registerPortableName(value: String, names: MutableMap<String, String>): String? {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).uppercase()
        return names.putIfAbsent(normalized, value)
    }

    private fun validateZipExtraFields(bytes: ByteArray, offset: Int, length: Int) {
        var cursor = offset
        val end = offset + length
        if (offset < 0 || length < 0 || end < offset || end > bytes.size) zipStructureInvalid()
        while (cursor < end) {
            if (cursor > end - 4) zipStructureInvalid()
            val id = readUnsignedShort(bytes, cursor)
            val size = readUnsignedShort(bytes, cursor + 2)
            cursor += 4
            if (cursor > end - size) zipStructureInvalid()
            if (id == ZIP64_EXTRA_FIELD_ID) {
                extensionFailure(
                    code = "extensions.crx3.zip64-unsupported",
                    message = "Zip64 entry extra fields are not accepted.",
                )
            }
            if (id == UNICODE_PATH_EXTRA_FIELD_ID) {
                extensionFailure(
                    code = "extensions.crx3.zip-unicode-path-extra-unsupported",
                    message = "Unicode path extra fields are rejected to prevent alternate ZIP entry names.",
                )
            }
            cursor += size
        }
        if (cursor != end) zipStructureInvalid()
    }

    private fun decodeZipEntryName(bytes: ByteArray, offset: Int, length: Int, flags: Int): String {
        val charset = if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else ZIP_LEGACY_CHARSET
        return try {
            charset.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString()
        } catch (error: Exception) {
            extensionFailure(
                code = "extensions.crx3.zip-entry-name-encoding-invalid",
                message = "The CRX3 archive contains an entry name with invalid ZIP encoding.",
                cause = error,
            )
        }
    }

    private fun validateArchivePath(path: String) {
        val normalized = path.removeSuffix("/")
        val issue = portablePathIssue(normalized)
        if (issue != null) {
            extensionFailure(
                code = if (issue == KWebPortablePathIssue.TRAVERSAL) {
                    "extensions.crx3.zip-entry-traversal"
                } else {
                    "extensions.crx3.zip-entry-path-nonportable"
                },
                details = mapOf("entry" to path, "issue" to issue.name),
                message = "The CRX3 archive contains a path that is invalid or not portable across desktop filesystems.",
            )
        }
    }

    private fun validateDataDescriptor(
        bytes: ByteArray,
        offset: Int,
        centralOffset: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ): Int {
        val unsignedMatches = dataDescriptorMatches(
            bytes,
            offset,
            centralOffset,
            crc,
            compressedSize,
            uncompressedSize,
        )
        val signedMatches = offset + 4 <= centralOffset &&
            readUnsignedInt(bytes, offset) == DATA_DESCRIPTOR_SIGNATURE &&
            dataDescriptorMatches(
                bytes,
                offset + 4,
                centralOffset,
                crc,
                compressedSize,
                uncompressedSize,
            )
        if (unsignedMatches == signedMatches) {
            extensionFailure(
                code = "extensions.crx3.zip-data-descriptor-invalid",
                message = if (unsignedMatches) {
                    "The CRX3 ZIP data descriptor has an ambiguous optional signature."
                } else {
                    "The CRX3 ZIP data descriptor does not match its central directory metadata."
                },
            )
        }
        return offset + DATA_DESCRIPTOR_METADATA_BYTES + if (signedMatches) 4 else 0
    }

    private fun dataDescriptorMatches(
        bytes: ByteArray,
        metadataOffset: Int,
        centralOffset: Int,
        crc: Long,
        compressedSize: Long,
        uncompressedSize: Long,
    ): Boolean = metadataOffset >= 0 &&
        metadataOffset <= centralOffset - DATA_DESCRIPTOR_METADATA_BYTES &&
        readUnsignedInt(bytes, metadataOffset) == crc &&
        readUnsignedInt(bytes, metadataOffset + 4) == compressedSize &&
        readUnsignedInt(bytes, metadataOffset + 8) == uncompressedSize

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = maxOf(0, bytes.size - 65_557)
        for (index in bytes.size - 22 downTo minimum) {
            if (readUnsignedInt(bytes, index) == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                val commentLength = readUnsignedShort(bytes, index + 20)
                if (index + 22 + commentLength == bytes.size) return index
            }
        }
        zipStructureInvalid()
    }

    private fun readUnsignedShort(bytes: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 2 > bytes.size) zipStructureInvalid()
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun readUnsignedInt(bytes: ByteArray, offset: Int): Long {
        if (offset < 0 || offset + 4 > bytes.size) zipStructureInvalid()
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun zipStructureInvalid(): Nothing = extensionFailure(
        code = "extensions.crx3.zip-structure-invalid",
        message = "The CRX3 ZIP central directory or local entry structure is inconsistent.",
    )

    private class Crx3Reader(private val bytes: ByteArray) {
        fun read(): Crx3Payload {
            if (bytes.size < 16 || bytes.copyOfRange(0, 4).decodeToString() != "Cr24") {
                extensionFailure(
                    code = "extensions.crx3.magic-invalid",
                    message = "The extension package is not a CRX3 container.",
                )
            }
            val version = littleEndianInt(4)
            if (version != 3) {
                extensionFailure(
                    code = "extensions.crx3.version-unsupported",
                    details = mapOf("version" to version.toString()),
                    message = "Only CRX3 packages are accepted.",
                )
            }
            val headerSize = littleEndianInt(8)
            if (headerSize <= 0 || headerSize > MAX_HEADER_BYTES || 12L + headerSize > bytes.size) {
                extensionFailure(
                    code = "extensions.crx3.header-size-invalid",
                    details = mapOf("headerSize" to headerSize.toString()),
                    message = "The CRX3 header length is outside the bounded container range.",
                )
            }
            val header = bytes.copyOfRange(12, 12 + headerSize)
            if (CRX_HEADER_FORBIDDEN_ZIP_TOKENS.any { header.containsSequence(it) }) {
                extensionFailure(
                    code = "extensions.crx3.header-zip-token-invalid",
                    message = "The CRX3 header contains a ZIP boundary token rejected by Chromium.",
                )
            }
            val archiveOffset = 12 + headerSize
            val archiveSize = bytes.size - archiveOffset
            if (archiveSize > MAX_ARCHIVE_BYTES) {
                extensionFailure(
                    code = "extensions.crx3.archive-too-large",
                    details = mapOf("size" to archiveSize.toString()),
                    message = "The CRX3 archive exceeds the bounded package size.",
                )
            }
            val parsed = Crx3ProtoReader(header).readHeader()
            val signedHeader = parsed.signedHeaderData ?: extensionFailure(
                code = "extensions.crx3.signed-header-missing",
                message = "The CRX3 signed header is missing.",
            )
            val signed = Crx3ProtoReader(signedHeader).readSignedData()
            val publicKeyProofs = parsed.proofs
            if (publicKeyProofs.isEmpty()) {
                extensionFailure(
                    code = "extensions.crx3.proof-missing",
                    message = "The CRX3 header contains no supported signature proof.",
                )
            }
            if (publicKeyProofs.size > MAX_PROOF_COUNT) {
                extensionFailure(
                    code = "extensions.crx3.proof-count-exceeded",
                    details = mapOf("count" to publicKeyProofs.size.toString()),
                    message = "The CRX3 header contains too many signature proofs.",
                )
            }
            val declaredId = KWebExtensionId.fromSha256Hash(signed.crxId)
            val developerProofs = publicKeyProofs.filter { extensionId(it.publicKey) == declaredId }
            if (developerProofs.size != 1) {
                extensionFailure(
                    code = if (developerProofs.isEmpty()) {
                        "extensions.crx3.developer-proof-missing"
                    } else {
                        "extensions.crx3.developer-proof-ambiguous"
                    },
                    message = "The CRX3 package must contain exactly one developer proof for its declared ID.",
                )
            }
            val signedHeaderLength = littleEndianIntBytes(signedHeader.size)
            publicKeyProofs.forEach { proof ->
                val publicKey = decodePublicKeyBytes(proof.publicKey)
                val algorithm = when (proof.algorithm) {
                    ProofAlgorithm.RSA -> {
                        if (publicKey.algorithm != "RSA") proofKeyMismatch(proof.algorithm)
                        "SHA256withRSA"
                    }
                    ProofAlgorithm.ECDSA -> {
                        if (publicKey !is ECPublicKey || !isP256(publicKey)) {
                            proofKeyMismatch(proof.algorithm)
                        }
                        "SHA256withECDSA"
                    }
                }
                try {
                    val verifier = Signature.getInstance(algorithm)
                    verifier.initVerify(publicKey)
                    verifier.update(CRX3_SIGNATURE_CONTEXT)
                    verifier.update(signedHeaderLength)
                    verifier.update(signedHeader)
                    verifier.update(bytes, archiveOffset, archiveSize)
                    if (!verifier.verify(proof.signature)) {
                        extensionFailure(
                            code = "extensions.crx3.signature-mismatch",
                            message = "A CRX3 proof signature does not match the signed archive.",
                        )
                    }
                } catch (error: KWebExtensionVerificationException) {
                    throw error
                } catch (error: Exception) {
                    extensionFailure(
                        code = "extensions.crx3.signature-invalid",
                        message = "The CRX3 signature could not be initialized or verified.",
                        cause = error,
                    )
                }
            }
            return Crx3Payload(
                archive = bytes.copyOfRange(archiveOffset, bytes.size),
                publicKeyDer = developerProofs.single().publicKey,
            )
        }

        private fun littleEndianInt(offset: Int): Int =
            ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private data class Crx3Payload(
        val archive: ByteArray,
        val publicKeyDer: ByteArray,
    )

    private enum class ProofAlgorithm { RSA, ECDSA }

    private data class Crx3Proof(
        val algorithm: ProofAlgorithm,
        val publicKey: ByteArray,
        val signature: ByteArray,
    )

    private class Crx3ProtoReader(private val bytes: ByteArray) {
        private var offset = 0

        fun readHeader(): Header {
            val proofs = mutableListOf<Crx3Proof>()
            var signedHeaderData: ByteArray? = null
            var verifiedContentsSeen = false
            while (offset < bytes.size) {
                val field = readKey()
                when (field.first) {
                    2, 3 -> {
                        if (field.second != 2) invalid()
                        if (proofs.size >= MAX_PROOF_COUNT) {
                            extensionFailure(
                                code = "extensions.crx3.proof-count-exceeded",
                                details = mapOf("count" to (proofs.size + 1).toString()),
                                message = "The CRX3 header contains too many signature proofs.",
                            )
                        }
                        val proof = readLengthDelimited()
                        proofs += readProof(
                            proof,
                            if (field.first == 2) ProofAlgorithm.RSA else ProofAlgorithm.ECDSA,
                        )
                    }
                    10000 -> {
                        if (field.second != 2 || signedHeaderData != null) invalid()
                        signedHeaderData = readLengthDelimited()
                    }
                    4 -> {
                        if (field.second != 2 || verifiedContentsSeen) invalid()
                        verifiedContentsSeen = true
                        readLengthDelimited(MAX_VERIFIED_CONTENTS_BYTES)
                    }
                    else -> invalid()
                }
            }
            return Header(proofs, signedHeaderData)
        }

        fun readSignedData(): SignedData {
            var crxId: ByteArray? = null
            while (offset < bytes.size) {
                val field = readKey()
                if (field.first == 1 && field.second == 2) {
                    if (crxId != null) invalid()
                    crxId = readLengthDelimited(16)
                } else {
                    invalid()
                }
            }
            val value = crxId ?: invalid()
            if (value.size != 16 || offset != bytes.size) invalid()
            return SignedData(value)
        }

        private fun readProof(value: ByteArray, algorithm: ProofAlgorithm): Crx3Proof {
            val nested = Crx3ProtoReader(value)
            var key: ByteArray? = null
            var signature: ByteArray? = null
            while (nested.offset < value.size) {
                val field = nested.readKey()
                when (field.first) {
                    1 -> if (field.second == 2 && key == null) {
                        key = nested.readLengthDelimited(KWEB_EXTENSION_MAX_PUBLIC_KEY_BYTES)
                    } else {
                        nested.invalid()
                    }
                    2 -> if (field.second == 2 && signature == null) {
                        signature = nested.readLengthDelimited(MAX_SIGNATURE_BYTES)
                    } else {
                        nested.invalid()
                    }
                    else -> nested.invalid()
                }
            }
            return Crx3Proof(algorithm, key ?: invalid(), signature ?: invalid())
        }

        private fun readKey(): Pair<Int, Int> {
            val value = readVarint()
            val number = value ushr 3
            if (number !in 1..MAX_PROTO_FIELD_NUMBER) invalid()
            return number.toInt() to (value and 7).toInt()
        }

        private fun readLengthDelimited(limit: Int = bytes.size): ByteArray {
            val size = readVarint()
            if (size > limit || size > Int.MAX_VALUE || size < 0 || size > bytes.size - offset) invalid()
            return bytes.copyOfRange(offset, offset + size.toInt()).also { offset += size.toInt() }
        }

        private fun readVarint(): Long {
            var result = 0L
            repeat(10) { index ->
                if (offset >= bytes.size) invalid()
                val value = bytes[offset++].toInt() and 0xff
                if (index == 9 && value > 1) invalid()
                result = result or ((value and 0x7f).toLong() shl (index * 7))
                if (value and 0x80 == 0) {
                    if (index > 0 && value == 0) invalid()
                    return result
                }
            }
            invalid()
        }

        private fun invalid(): Nothing = extensionFailure(
            code = "extensions.crx3.protobuf-invalid",
            message = "The CRX3 header contains malformed protobuf data.",
        )
    }

    private data class Header(val proofs: List<Crx3Proof>, val signedHeaderData: ByteArray?)
    private data class SignedData(val crxId: ByteArray)
    private data class VerifiedZipArchive(val manifestJson: ByteArray, val entryNames: Set<String>)

    private const val MAX_HEADER_BYTES = 4 * 1024 * 1024
    private const val MAX_CRX_BYTES = 132L * 1024 * 1024 + 12
    private const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
    private const val MAX_UNCOMPRESSED_BYTES = 512L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 1024L * 1024
    private const val MAX_ENTRY_COUNT = 10_000
    private const val MAX_PROOF_COUNT = 32
    private const val MAX_SIGNATURE_BYTES = 16 * 1024
    private const val MAX_VERIFIED_CONTENTS_BYTES = 4 * 1024 * 1024
    private const val MAX_PROTO_FIELD_NUMBER = 536_870_911L
    private const val LOCAL_FILE_SIGNATURE = 0x04034b50L
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    private const val DATA_DESCRIPTOR_SIGNATURE = 0x08074b50L
    private const val ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06064b50L
    private const val ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE = 0x07064b50L
    private const val ENCRYPTED_FLAG = 0x0001
    private const val DATA_DESCRIPTOR_FLAG = 0x0008
    private const val DEFLATE_OPTION_FLAGS = 0x0006
    private const val UTF8_FLAG = 0x0800
    private const val DATA_DESCRIPTOR_METADATA_BYTES = 12
    private const val MANIFEST_FILE_NAME = "manifest.json"
    private const val ZIP64_EXTRA_FIELD_ID = 0x0001
    private const val UNICODE_PATH_EXTRA_FIELD_ID = 0x7075
    private val UNIX_ZIP_SYSTEMS = setOf(3, 19)
    private val ZIP_LEGACY_CHARSET = java.nio.charset.Charset.forName("IBM437")
    private val CRX3_SIGNATURE_CONTEXT = "CRX3 SignedData\u0000".toByteArray(StandardCharsets.UTF_8)
    private val CRX_HEADER_FORBIDDEN_ZIP_TOKENS = listOf(
        littleEndianIntBytes(END_OF_CENTRAL_DIRECTORY_SIGNATURE.toInt()),
        littleEndianIntBytes(ZIP64_END_OF_CENTRAL_DIRECTORY_SIGNATURE.toInt()),
        littleEndianIntBytes(ZIP64_END_OF_CENTRAL_DIRECTORY_LOCATOR_SIGNATURE.toInt()),
    )

    private fun littleEndianIntBytes(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun ByteArray.containsSequence(value: ByteArray): Boolean {
        if (value.isEmpty() || value.size > size) return false
        for (start in 0..size - value.size) {
            var matches = true
            for (index in value.indices) {
                if (this[start + index] != value[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun decodePublicKeyBytes(value: ByteArray): PublicKey {
        val spec = X509EncodedKeySpec(value)
        val key = try {
            KeyFactory.getInstance("RSA").generatePublic(spec)
        } catch (_: Exception) {
            try {
                KeyFactory.getInstance("EC").generatePublic(spec)
            } catch (error: Exception) {
                extensionFailure(
                    code = "extensions.public-key.invalid",
                    message = "The extension public key is not a supported RSA or EC SubjectPublicKeyInfo value.",
                    cause = error,
                )
            }
        }
        when (key) {
            is RSAPublicKey -> if (key.modulus.bitLength() < 2048) {
                extensionFailure(
                    code = "extensions.public-key.rsa-too-small",
                    details = mapOf("bits" to key.modulus.bitLength().toString()),
                    message = "Extension RSA public keys must be at least 2048 bits.",
                )
            }
            is ECPublicKey -> if (!isP256(key)) {
                extensionFailure(
                    code = "extensions.public-key.ec-curve-unsupported",
                    message = "Extension EC public keys must use NIST P-256.",
                )
            }
            else -> extensionFailure(
                code = "extensions.public-key.type-unsupported",
                message = "The extension public key type is unsupported.",
            )
        }
        return key
    }

    private fun proofKeyMismatch(algorithm: ProofAlgorithm): Nothing = extensionFailure(
        code = "extensions.crx3.proof-key-type-mismatch",
        details = mapOf("algorithm" to algorithm.name),
        message = "The CRX3 proof public key type does not match its declared proof algorithm.",
    )

    private val P256_ORDER = BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16)
    private val P256_PRIME = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val P256_A = BigInteger("ffffffff00000001000000000000000000000000fffffffffffffffffffffffc", 16)
    private val P256_B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)
    private val P256_GX = BigInteger("6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296", 16)
    private val P256_GY = BigInteger("4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5", 16)

    private fun isP256(key: ECPublicKey): Boolean {
        val params = key.params
        val field = params.curve.field as? java.security.spec.ECFieldFp ?: return false
        return field.p == P256_PRIME &&
            params.curve.a == P256_A &&
            params.curve.b == P256_B &&
            params.generator.affineX == P256_GX &&
            params.generator.affineY == P256_GY &&
            params.order == P256_ORDER &&
            params.cofactor == 1
    }
}
