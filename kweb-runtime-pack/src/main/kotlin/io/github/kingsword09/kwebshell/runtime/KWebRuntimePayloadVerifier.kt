package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.Enumeration
import java.util.zip.CRC32

internal data class KWebRuntimePayloadVerificationRequest(
    val archive: Path,
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
)

internal object KWebRuntimePayloadVerifier {
    fun verify(request: KWebRuntimePayloadVerificationRequest): KWebRuntimePayloadManifest {
        val archive = validateArchivePath(request.archive)
        val envelope = verifyEnvelope(archive)
        try {
            ZipFile.builder()
                .setPath(archive)
                .setCharset(StandardCharsets.UTF_8)
                .setUseUnicodeExtraFields(false)
                .setIgnoreLocalFileHeader(false)
                .get()
                .use { zip ->
                    payloadRequire(
                        zip.firstLocalFileHeaderOffset == 0L,
                        code = "runtime.payload.archive-prefix-invalid",
                        details = mapOf("offset" to zip.firstLocalFileHeaderOffset.toString()),
                        message = "The runtime payload ZIP contains bytes before its first local header.",
                    )
                    zip.contentBeforeFirstLocalFileHeader?.use { prefix ->
                        payloadRequire(
                            prefix.read() == -1,
                            code = "runtime.payload.archive-prefix-invalid",
                            message = "The runtime payload ZIP contains a preamble.",
                        )
                    }

                    val physicalEntries = zip.entriesInPhysicalOrder.toList()
                    val centralEntries = zip.entries.toList()
                    payloadRequire(
                        physicalEntries.size == envelope.entryCount && centralEntries.size == envelope.entryCount,
                        code = "runtime.payload.archive-entry-count-mismatch",
                        details = mapOf(
                            "physical" to physicalEntries.size.toString(),
                            "central" to centralEntries.size.toString(),
                            "eocd" to envelope.entryCount.toString(),
                        ),
                        message = "The runtime payload ZIP entry counts do not agree.",
                    )
                    val physicalNames = physicalEntries.map(ZipArchiveEntry::getName)
                    val centralNames = centralEntries.map(ZipArchiveEntry::getName)
                    validateNames(physicalNames)
                    payloadRequire(
                        centralNames == physicalNames,
                        code = "runtime.payload.archive-central-order-invalid",
                        message = "The runtime payload central directory order differs from local entry order.",
                    )
                    payloadRequire(
                        physicalEntries.firstOrNull()?.localHeaderOffset == 0L &&
                            physicalEntries.zipWithNext().all { (left, right) ->
                                left.localHeaderOffset < right.localHeaderOffset
                            },
                        code = "runtime.payload.archive-offset-order-invalid",
                        message = "The runtime payload local headers are not contiguous in physical order.",
                    )

                    physicalEntries.forEach { validateArchiveEntryMetadata(zip, it) }
                    payloadRequire(
                        physicalEntries.zipWithNext().all { (left, right) ->
                            left.dataOffset + left.compressedSize == right.localHeaderOffset
                        } &&
                            physicalEntries.last().dataOffset + physicalEntries.last().compressedSize ==
                            envelope.centralDirectoryOffset,
                        code = "runtime.payload.archive-hidden-data",
                        message = "The runtime payload ZIP contains bytes between entries or before its central directory.",
                    )
                    val byName = physicalEntries.associateBy(ZipArchiveEntry::getName)
                    val manifestEntry = byName[KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH]
                        ?: payloadFailure(
                            code = "runtime.payload.manifest-missing",
                            message = "The runtime payload ZIP does not contain manifest.json.",
                        )
                    payloadRequire(
                        archiveEntryType(manifestEntry) == KWebRuntimePayloadEntryType.FILE &&
                            permissionMode(manifestEntry) == KWEB_RUNTIME_PAYLOAD_FILE_MODE &&
                            manifestEntry.size in 1..MAX_MANIFEST_BYTES,
                        code = "runtime.payload.manifest-entry-invalid",
                        message = "The runtime payload manifest ZIP entry metadata is invalid.",
                    )
                    val manifestContent = readEntry(zip, manifestEntry, capture = true)
                    val manifestBytes = manifestContent.bytes ?: payloadFailure(
                        code = "runtime.payload.manifest-read-incomplete",
                        message = "The runtime payload verifier did not capture manifest.json.",
                    )
                    val manifest = KWebRuntimePayloadManifestCodec.decode(manifestBytes)
                    payloadRequire(
                        manifestBytes.contentEquals(KWebRuntimePayloadManifestCodec.encode(manifest)),
                        code = "runtime.payload.manifest-non-canonical",
                        message = "The runtime payload manifest is not canonical UTF-8 JSON.",
                    )
                    KWebRuntimePayloadContract.validateManifest(
                        manifest = manifest,
                        catalog = request.catalog,
                        target = request.target,
                        productVersion = request.productVersion,
                    )

                    val payloadEntries = physicalEntries.filterNot {
                        it.name == KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH
                    }
                    payloadRequire(
                        payloadEntries.map(ZipArchiveEntry::getName) == manifest.entries.map { it.path },
                        code = "runtime.payload.archive-manifest-path-mismatch",
                        message = "The runtime payload ZIP paths do not exactly match manifest.json.",
                    )
                    manifest.entries.forEach { declared ->
                        val actual = byName.getValue(declared.path)
                        verifyDeclaredEntry(zip, actual, declared)
                    }
                    return manifest
                }
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.archive-read-failed",
                details = mapOf("path" to archive.toString()),
                message = "Unable to read or decompress the runtime payload ZIP.",
                cause = error,
            )
        }
    }

    private fun validateArchivePath(path: Path): Path {
        payloadRequire(
            path.isAbsolute && path == path.normalize(),
            code = "runtime.payload.archive-path-invalid",
            details = mapOf("path" to path.toString()),
            message = "The runtime payload archive path must be absolute and normalized.",
        )
        payloadRequire(
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),
            code = "runtime.payload.archive-file-invalid",
            details = mapOf("path" to path.toString()),
            message = "The runtime payload archive is missing or not a regular file.",
        )
        return path
    }

    private fun verifyEnvelope(path: Path): ZipEnvelope {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                payloadRequire(
                    size in MIN_ZIP_SIZE..MAX_CLASSIC_ZIP_SIZE,
                    code = "runtime.payload.archive-size-invalid",
                    details = mapOf("size" to size.toString()),
                    message = "The runtime payload must be a non-empty classic ZIP archive.",
                )
                val firstSignature = readUnsignedInt(channel, 0L)
                payloadRequire(
                    firstSignature == LOCAL_FILE_HEADER_SIGNATURE,
                    code = "runtime.payload.archive-prefix-invalid",
                    message = "The runtime payload ZIP does not start with a local file header.",
                )

                val tailSize = minOf(size, MAX_EOCD_SEARCH.toLong()).toInt()
                val tailOffset = size - tailSize
                val tail = ByteArray(tailSize)
                readFully(channel, ByteBuffer.wrap(tail), tailOffset)
                val validEocd = findValidEocd(tail)
                if (validEocd < 0) {
                    val lastSignature = findLastSignature(tail, EOCD_SIGNATURE_BYTES)
                    if (lastSignature >= 0 && lastSignature + EOCD_MIN_SIZE <= tail.size) {
                        val commentLength = unsignedShort(tail, lastSignature + 20)
                        val declaredEnd = lastSignature + EOCD_MIN_SIZE + commentLength
                        if (declaredEnd < tail.size) {
                            payloadFailure(
                                code = "runtime.payload.archive-trailing-data",
                                details = mapOf("trailingBytes" to (tail.size - declaredEnd).toString()),
                                message = "The runtime payload ZIP contains data after its EOCD record.",
                            )
                        }
                    }
                    payloadFailure(
                        code = "runtime.payload.archive-eocd-invalid",
                        message = "The runtime payload ZIP has no terminal EOCD record.",
                    )
                }

                val eocdOffset = tailOffset + validEocd
                val diskNumber = unsignedShort(tail, validEocd + 4)
                val centralDisk = unsignedShort(tail, validEocd + 6)
                val entriesOnDisk = unsignedShort(tail, validEocd + 8)
                val totalEntries = unsignedShort(tail, validEocd + 10)
                val centralSize = unsignedInt(tail, validEocd + 12)
                val centralOffset = unsignedInt(tail, validEocd + 16)
                val commentLength = unsignedShort(tail, validEocd + 20)
                payloadRequire(
                    commentLength == 0,
                    code = "runtime.payload.archive-comment-invalid",
                    message = "The runtime payload ZIP must not contain an archive comment.",
                )
                payloadRequire(
                    diskNumber == 0 && centralDisk == 0 && entriesOnDisk == totalEntries,
                    code = "runtime.payload.archive-multidisk-invalid",
                    message = "The runtime payload ZIP cannot span multiple disks.",
                )
                payloadRequire(
                    totalEntries in 1 until ZIP64_SENTINEL_16,
                    code = "runtime.payload.archive-entry-count-invalid",
                    details = mapOf("entries" to totalEntries.toString()),
                    message = "The runtime payload ZIP entry count requires unsupported ZIP64 metadata.",
                )
                payloadRequire(
                    centralOffset != ZIP64_SENTINEL_32 &&
                        centralSize != ZIP64_SENTINEL_32 &&
                        centralOffset + centralSize == eocdOffset,
                    code = "runtime.payload.archive-central-directory-invalid",
                    details = mapOf(
                        "offset" to centralOffset.toString(),
                        "size" to centralSize.toString(),
                        "eocd" to eocdOffset.toString(),
                    ),
                    message = "The runtime payload central directory has an invalid extent.",
                )
                payloadRequire(
                    readUnsignedInt(channel, centralOffset) == CENTRAL_DIRECTORY_HEADER_SIGNATURE,
                    code = "runtime.payload.archive-central-directory-invalid",
                    message = "The runtime payload central directory does not start at its declared offset.",
                )
                return ZipEnvelope(
                    entryCount = totalEntries,
                    centralDirectoryOffset = centralOffset,
                )
            }
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.archive-envelope-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to validate the runtime payload ZIP envelope.",
                cause = error,
            )
        }
    }

    private fun validateNames(names: List<String>) {
        payloadRequire(
            names.size == names.toSet().size,
            code = "runtime.payload.archive-entry-duplicate",
            message = "The runtime payload ZIP contains duplicate entry names.",
        )
        payloadRequire(
            names == names.sortedWith(KWebRuntimePayloadContract.pathComparator),
            code = "runtime.payload.archive-entry-order-invalid",
            message = "The runtime payload ZIP entries are not in lexical UTF-8 order.",
        )
    }

    private fun validateArchiveEntryMetadata(zip: ZipFile, entry: ZipArchiveEntry) {
        val type = archiveEntryType(entry)
        if (entry.name == KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH) {
            payloadRequire(
                type == KWebRuntimePayloadEntryType.FILE,
                code = "runtime.payload.manifest-entry-invalid",
                message = "manifest.json is not a regular ZIP entry.",
            )
        } else {
            KWebRuntimePayloadContract.validatePayloadEntryPath(entry.name, type)
        }
        val decodedRawName = decodeUtf8(entry.rawName, "runtime.payload.archive-entry-name-encoding-invalid")
        payloadRequire(
            decodedRawName == entry.name,
            code = "runtime.payload.archive-entry-name-encoding-invalid",
            details = mapOf("name" to entry.name),
            message = "The runtime payload entry name is not canonical UTF-8.",
        )
        payloadRequire(
            entry.platform == ZipArchiveEntry.PLATFORM_UNIX &&
                entry.generalPurposeBit.usesUTF8ForNames() &&
                !entry.generalPurposeBit.usesEncryption() &&
                !entry.generalPurposeBit.usesStrongEncryption() &&
                !entry.generalPurposeBit.usesDataDescriptor() &&
                entry.rawFlag == UTF8_NAMES_FLAG,
            code = "runtime.payload.archive-entry-flags-invalid",
            details = mapOf("name" to entry.name, "flags" to entry.rawFlag.toString()),
            message = "The runtime payload entry flags are not canonical UTF-8 flags.",
        )
        payloadRequire(
            entry.timeLocal == FIXED_TIMESTAMP,
            code = "runtime.payload.archive-entry-timestamp-invalid",
            details = mapOf("name" to entry.name, "timestamp" to entry.timeLocal.toString()),
            message = "The runtime payload entry timestamp is not the fixed canonical timestamp.",
        )
        payloadRequire(
            (entry.extra == null || entry.extra.isEmpty()) &&
                entry.centralDirectoryExtra.isEmpty() &&
                entry.localFileDataExtra.isEmpty() &&
                entry.comment.isNullOrEmpty(),
            code = "runtime.payload.archive-entry-extra-invalid",
            details = mapOf("name" to entry.name),
            message = "The runtime payload entry contains an extra field or comment.",
        )
        payloadRequire(
            entry.diskNumberStart == 0L && entry.isStreamContiguous && zip.canReadEntryData(entry),
            code = "runtime.payload.archive-entry-storage-invalid",
            details = mapOf("name" to entry.name),
            message = "The runtime payload entry storage is unsupported or non-contiguous.",
        )
        payloadRequire(
            entry.size >= 0L && entry.compressedSize >= 0L && entry.crc >= 0L,
            code = "runtime.payload.archive-entry-metadata-invalid",
            details = mapOf("name" to entry.name),
            message = "The runtime payload entry size or CRC is missing.",
        )
        val expectedMethod = if (type == KWebRuntimePayloadEntryType.DIRECTORY) {
            ZipArchiveOutputStream.STORED
        } else {
            ZipArchiveOutputStream.DEFLATED
        }
        payloadRequire(
            entry.method == expectedMethod,
            code = "runtime.payload.archive-entry-method-invalid",
            details = mapOf("name" to entry.name, "method" to entry.method.toString()),
            message = "The runtime payload entry compression method is not canonical.",
        )
        if (type == KWebRuntimePayloadEntryType.DIRECTORY) {
            payloadRequire(
                entry.size == 0L && entry.compressedSize == 0L && entry.crc == 0L,
                code = "runtime.payload.archive-directory-data-invalid",
                details = mapOf("name" to entry.name),
                message = "The runtime payload directory contains data.",
            )
        }
    }

    private fun verifyDeclaredEntry(
        zip: ZipFile,
        actual: ZipArchiveEntry,
        declared: KWebRuntimePayloadEntry,
    ) {
        payloadRequire(
            archiveEntryType(actual) == declared.type &&
                permissionMode(actual) == declared.mode &&
                actual.size == declared.size,
            code = "runtime.payload.archive-entry-manifest-mismatch",
            details = mapOf("path" to declared.path),
            message = "The runtime payload ZIP entry metadata differs from manifest.json.",
        )
        val content = readEntry(
            zip = zip,
            entry = actual,
            capture = declared.type == KWebRuntimePayloadEntryType.SYMLINK,
        )
        payloadRequire(
            content.size == declared.size &&
                content.sha256 == declared.sha256 &&
                content.crc32 == actual.crc,
            code = "runtime.payload.archive-entry-digest-mismatch",
            details = mapOf("path" to declared.path),
            message = "The runtime payload ZIP entry bytes do not match manifest.json.",
        )
        if (declared.type == KWebRuntimePayloadEntryType.SYMLINK) {
            val linkBytes = content.bytes ?: payloadFailure(
                code = "runtime.payload.archive-symlink-read-incomplete",
                details = mapOf("path" to declared.path),
                message = "The runtime payload verifier did not capture a symbolic link target.",
            )
            val target = decodeUtf8(
                linkBytes,
                "runtime.payload.archive-symlink-encoding-invalid",
            )
            payloadRequire(
                target == declared.linkTarget,
                code = "runtime.payload.archive-symlink-target-mismatch",
                details = mapOf("path" to declared.path),
                message = "The runtime payload symbolic link target differs from manifest.json.",
            )
            KWebRuntimePayloadContract.validateLinkTarget(declared.path, target)
        }
    }

    private fun readEntry(zip: ZipFile, entry: ZipArchiveEntry, capture: Boolean): EntryContent {
        val output = if (capture) ByteArrayOutputStream(entry.size.toInt()) else null
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    payloadRequire(
                        size <= entry.size,
                        code = "runtime.payload.archive-entry-size-overflow",
                        details = mapOf("name" to entry.name),
                        message = "The runtime payload entry expands beyond its declared size.",
                    )
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    output?.write(buffer, 0, count)
                }
            }
        } catch (error: KWebRuntimePayloadException) {
            throw error
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.archive-entry-read-failed",
                details = mapOf("name" to entry.name),
                message = "Unable to decompress a runtime payload entry.",
                cause = error,
            )
        }
        payloadRequire(
            size == entry.size && crc.value == entry.crc,
            code = "runtime.payload.archive-entry-crc-mismatch",
            details = mapOf("name" to entry.name),
            message = "The runtime payload entry size or CRC does not match its ZIP metadata.",
        )
        return EntryContent(
            size = size,
            sha256 = digest.digest().toHex(),
            crc32 = crc.value,
            bytes = output?.toByteArray(),
        )
    }

    private fun archiveEntryType(entry: ZipArchiveEntry): KWebRuntimePayloadEntryType {
        val unixMode = entry.unixMode
        val typeBits = unixMode and UnixStat.FILE_TYPE_FLAG
        val type = when (typeBits) {
            UnixStat.DIR_FLAG -> KWebRuntimePayloadEntryType.DIRECTORY
            UnixStat.FILE_FLAG -> KWebRuntimePayloadEntryType.FILE
            UnixStat.LINK_FLAG -> KWebRuntimePayloadEntryType.SYMLINK
            else -> payloadFailure(
                code = "runtime.payload.archive-entry-type-invalid",
                details = mapOf("name" to entry.name, "unixMode" to unixMode.toString(8)),
                message = "The runtime payload ZIP entry has an unsupported Unix file type.",
            )
        }
        payloadRequire(
            (type == KWebRuntimePayloadEntryType.DIRECTORY) == entry.name.endsWith('/'),
            code = "runtime.payload.archive-entry-type-invalid",
            details = mapOf("name" to entry.name),
            message = "The runtime payload ZIP directory marker does not match its Unix type.",
        )
        val permissions = unixMode and UnixStat.PERM_MASK
        val allowed = when (type) {
            KWebRuntimePayloadEntryType.DIRECTORY -> permissions == DIRECTORY_PERMISSIONS
            KWebRuntimePayloadEntryType.FILE ->
                permissions == FILE_PERMISSIONS || permissions == EXECUTABLE_PERMISSIONS

            KWebRuntimePayloadEntryType.SYMLINK -> permissions == SYMLINK_PERMISSIONS
        }
        payloadRequire(
            allowed,
            code = "runtime.payload.archive-entry-mode-invalid",
            details = mapOf("name" to entry.name, "unixMode" to unixMode.toString(8)),
            message = "The runtime payload ZIP entry has non-canonical Unix permissions.",
        )
        return type
    }

    private fun permissionMode(entry: ZipArchiveEntry): String =
        (entry.unixMode and UnixStat.PERM_MASK).toString(8).padStart(4, '0')

    private fun decodeUtf8(bytes: ByteArray, code: String): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        payloadFailure(
            code = code,
            message = "The runtime payload contains malformed UTF-8.",
            cause = error,
        )
    }

    private fun findValidEocd(bytes: ByteArray): Int {
        var index = bytes.size - EOCD_MIN_SIZE
        while (index >= 0) {
            if (matches(bytes, index, EOCD_SIGNATURE_BYTES)) {
                val commentLength = unsignedShort(bytes, index + 20)
                if (index + EOCD_MIN_SIZE + commentLength == bytes.size) return index
            }
            index -= 1
        }
        return -1
    }

    private fun findLastSignature(bytes: ByteArray, signature: ByteArray): Int {
        var index = bytes.size - signature.size
        while (index >= 0) {
            if (matches(bytes, index, signature)) return index
            index -= 1
        }
        return -1
    }

    private fun matches(bytes: ByteArray, offset: Int, expected: ByteArray): Boolean =
        expected.indices.all { index -> bytes[offset + index] == expected[index] }

    private fun unsignedShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun unsignedInt(bytes: ByteArray, offset: Int): Long =
        (unsignedShort(bytes, offset).toLong() or (unsignedShort(bytes, offset + 2).toLong() shl 16)) and
            0xffff_ffffL

    private fun readUnsignedInt(channel: FileChannel, offset: Long): Long {
        val buffer = ByteBuffer.allocate(Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        readFully(channel, buffer, offset)
        buffer.flip()
        return buffer.int.toLong() and 0xffff_ffffL
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer, offset: Long) {
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            payloadRequire(
                count > 0,
                code = "runtime.payload.archive-truncated",
                details = mapOf("offset" to position.toString()),
                message = "The runtime payload ZIP is truncated.",
            )
            position += count
        }
    }

    private fun <T> Enumeration<T>.toList(): List<T> = buildList {
        while (this@toList.hasMoreElements()) add(this@toList.nextElement())
    }

    private data class ZipEnvelope(
        val entryCount: Int,
        val centralDirectoryOffset: Long,
    )

    private data class EntryContent(
        val size: Long,
        val sha256: String,
        val crc32: Long,
        val bytes: ByteArray?,
    )

    private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
    private val EOCD_SIGNATURE_BYTES: ByteArray = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    private const val LOCAL_FILE_HEADER_SIGNATURE: Long = 0x04034b50L
    private const val CENTRAL_DIRECTORY_HEADER_SIGNATURE: Long = 0x02014b50L
    private const val MIN_ZIP_SIZE: Long = 22L
    private const val MAX_CLASSIC_ZIP_SIZE: Long = 0xffff_ffffL
    private const val MAX_EOCD_SEARCH: Int = 65_557
    private const val EOCD_MIN_SIZE: Int = 22
    private const val ZIP64_SENTINEL_16: Int = 0xffff
    private const val ZIP64_SENTINEL_32: Long = 0xffff_ffffL
    private const val MAX_MANIFEST_BYTES: Long = 16L * 1024L * 1024L
    private const val UTF8_NAMES_FLAG: Int = 0x0800
    private const val DIRECTORY_PERMISSIONS: Int = 0b111101101
    private const val EXECUTABLE_PERMISSIONS: Int = 0b111101101
    private const val FILE_PERMISSIONS: Int = 0b110100100
    private const val SYMLINK_PERMISSIONS: Int = 0b111111111
}
