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

internal data class KWebRuntimeReleaseVerificationRequest(
    val pack: Path,
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
    val trustedPublicKey: Path,
)

internal data class KWebRuntimeReleaseVerificationResult(
    val manifest: KWebRuntimeReleaseManifest,
    val payloadManifest: KWebRuntimePayloadManifest,
    val packSha256: String,
)

internal object KWebRuntimeReleaseVerifier {
    fun verify(request: KWebRuntimeReleaseVerificationRequest): KWebRuntimeReleaseVerificationResult {
        val pack = validatePackPath(request.pack)
        val publicKey = KWebRuntimeReleaseKeys.loadPublicKey(request.trustedPublicKey)
        val trustedKeyId = KWebRuntimeReleaseKeys.keyId(publicKey)
        val temporaryPayload = createTemporaryPayload(pack)
        var primaryFailure: Throwable? = null
        try {
            val contents = readPack(pack, temporaryPayload)
            val manifest = KWebRuntimeReleaseManifestCodec.decode(contents.metadata)
            releaseRequire(
                contents.metadata.contentEquals(KWebRuntimeReleaseManifestCodec.encode(manifest)),
                code = "runtime.release.metadata-non-canonical",
                message = "The signed runtime release metadata is not canonical UTF-8 JSON.",
            )
            verifyAuthenticatedMetadata(manifest, contents, trustedKeyId, publicKey)
            KWebRuntimeReleaseContract.validateManifest(
                manifest = manifest,
                catalog = request.catalog,
                target = request.target,
                productVersion = request.productVersion,
                payloadDigest = contents.payloadDigest,
                trustedKeyId = trustedKeyId,
            )
            val payloadManifest = verifyNestedPayload(request, temporaryPayload)
            KWebRuntimeReleaseContract.validatePayloadManifest(manifest, payloadManifest)
            return KWebRuntimeReleaseVerificationResult(
                manifest = manifest,
                payloadManifest = payloadManifest,
                packSha256 = digestPack(pack),
            )
        } catch (error: KWebRuntimeReleaseException) {
            primaryFailure = error
            throw error
        } catch (error: Exception) {
            primaryFailure = error
            releaseFailure(
                code = "runtime.release.pack-read-failed",
                details = mapOf("path" to pack.toString()),
                message = "Unable to validate the signed runtime release pack.",
                cause = error,
            )
        } finally {
            deleteTemporaryPayload(temporaryPayload, primaryFailure)
        }
    }

    private fun validatePackPath(path: Path): Path {
        releaseRequire(
            path.isAbsolute && path == path.normalize(),
            code = "runtime.release.pack-path-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The signed runtime release pack path must be absolute and normalized.",
        )
        releaseRequire(
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            code = "runtime.release.pack-file-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The signed runtime release pack must be a regular non-symbolic-link file.",
        )
        return path
    }

    private fun readPack(pack: Path, temporaryPayload: Path): PackContents {
        val envelope = verifyEnvelope(pack)
        try {
            ZipFile.builder()
                .setPath(pack)
                .setCharset(StandardCharsets.UTF_8)
                .setUseUnicodeExtraFields(false)
                .setIgnoreLocalFileHeader(false)
                .get()
                .use { zip ->
                    releaseRequire(
                        zip.firstLocalFileHeaderOffset == 0L,
                        code = "runtime.release.pack-prefix-invalid",
                        message = "The signed runtime release ZIP has a non-zero first local header offset.",
                    )
                    zip.contentBeforeFirstLocalFileHeader?.use { prefix ->
                        releaseRequire(
                            prefix.read() == -1,
                            code = "runtime.release.pack-prefix-invalid",
                            message = "The signed runtime release ZIP contains a preamble.",
                        )
                    }
                    val physical = zip.entriesInPhysicalOrder.toList()
                    val central = zip.entries.toList()
                    releaseRequire(
                        physical.size == envelope.entryCount && central.size == envelope.entryCount,
                        code = "runtime.release.pack-entry-count-mismatch",
                        message = "The signed runtime release ZIP entry counts do not agree.",
                    )
                    validateEntryNames(physical, central)
                    validateEntryOffsets(physical, envelope)
                    physical.forEach { validateEntryMetadata(zip, it) }
                    val entries = physical.associateBy(ZipArchiveEntry::getName)
                    val metadata = readSmallEntry(
                        zip,
                        entries.getValue(KWEB_RUNTIME_RELEASE_METADATA_PATH),
                        KWEB_RUNTIME_RELEASE_MAX_METADATA_BYTES,
                    )
                    val signature = readSmallEntry(
                        zip,
                        entries.getValue(KWEB_RUNTIME_RELEASE_SIGNATURE_PATH),
                        KWEB_RUNTIME_RELEASE_SIGNATURE_SIZE.toLong(),
                    )
                    val payloadDigest = copyPayloadEntry(
                        zip,
                        entries.getValue(KWEB_RUNTIME_RELEASE_PAYLOAD_PATH),
                        temporaryPayload,
                    )
                    return PackContents(metadata, signature, payloadDigest)
                }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.pack-zip-read-failed",
                details = mapOf("path" to pack.toString()),
                message = "Unable to read the signed runtime release ZIP.",
                cause = error,
            )
        }
    }

    private fun validateEntryNames(
        physical: List<ZipArchiveEntry>,
        central: List<ZipArchiveEntry>,
    ) {
        val physicalNames = physical.map(ZipArchiveEntry::getName)
        val centralNames = central.map(ZipArchiveEntry::getName)
        releaseRequire(
            physicalNames.size == physicalNames.toSet().size,
            code = "runtime.release.pack-entry-duplicate",
            message = "The signed runtime release ZIP contains duplicate entry names.",
        )
        releaseRequire(
            physicalNames == KWEB_RUNTIME_RELEASE_PACK_ENTRY_NAMES,
            code = "runtime.release.pack-entry-layout-invalid",
            details = { mapOf("entries" to physicalNames.joinToString(",")) },
            message = "The signed runtime release ZIP does not contain the exact canonical entries.",
        )
        releaseRequire(
            centralNames == physicalNames,
            code = "runtime.release.pack-central-order-invalid",
            message = "The signed runtime release central directory order differs from physical order.",
        )
    }

    private fun validateEntryOffsets(entries: List<ZipArchiveEntry>, envelope: ZipEnvelope) {
        releaseRequire(
            entries.first().localHeaderOffset == 0L &&
                entries.zipWithNext().all { (left, right) ->
                    left.localHeaderOffset < right.localHeaderOffset &&
                        left.dataOffset + left.compressedSize == right.localHeaderOffset
                } &&
                entries.last().dataOffset + entries.last().compressedSize == envelope.centralDirectoryOffset,
            code = "runtime.release.pack-hidden-data",
            message = "The signed runtime release ZIP contains non-contiguous or hidden bytes.",
        )
    }

    private fun validateEntryMetadata(zip: ZipFile, entry: ZipArchiveEntry) {
        val decodedName = decodeUtf8(
            entry.rawName,
            "runtime.release.pack-entry-name-encoding-invalid",
        )
        releaseRequire(
            decodedName == entry.name,
            code = "runtime.release.pack-entry-name-encoding-invalid",
            details = { mapOf("name" to entry.name) },
            message = "A signed runtime release entry name is not canonical UTF-8.",
        )
        releaseRequire(
            entry.platform == ZipArchiveEntry.PLATFORM_UNIX &&
                entry.generalPurposeBit.usesUTF8ForNames() &&
                !entry.generalPurposeBit.usesEncryption() &&
                !entry.generalPurposeBit.usesStrongEncryption() &&
                !entry.generalPurposeBit.usesDataDescriptor() &&
                entry.rawFlag == UTF8_NAMES_FLAG,
            code = "runtime.release.pack-entry-flags-invalid",
            details = { mapOf("name" to entry.name, "flags" to entry.rawFlag.toString()) },
            message = "A signed runtime release entry has non-canonical ZIP flags.",
        )
        releaseRequire(
            entry.timeLocal == FIXED_TIMESTAMP,
            code = "runtime.release.pack-entry-timestamp-invalid",
            details = { mapOf("name" to entry.name, "timestamp" to entry.timeLocal.toString()) },
            message = "A signed runtime release entry does not use the fixed timestamp.",
        )
        releaseRequire(
            (entry.extra == null || entry.extra.isEmpty()) &&
                entry.centralDirectoryExtra.isEmpty() &&
                entry.localFileDataExtra.isEmpty() &&
                entry.comment.isNullOrEmpty(),
            code = "runtime.release.pack-entry-extra-invalid",
            details = { mapOf("name" to entry.name) },
            message = "A signed runtime release entry contains an extra field or comment.",
        )
        val type = entry.unixMode and UnixStat.FILE_TYPE_FLAG
        val permissions = entry.unixMode and UnixStat.PERM_MASK
        releaseRequire(
            type == UnixStat.FILE_FLAG && permissions == FILE_PERMISSIONS && !entry.name.endsWith('/'),
            code = "runtime.release.pack-entry-mode-invalid",
            details = { mapOf("name" to entry.name, "unixMode" to entry.unixMode.toString(8)) },
            message = "A signed runtime release entry is not a regular mode-0644 file.",
        )
        releaseRequire(
            entry.diskNumberStart == 0L && entry.isStreamContiguous && zip.canReadEntryData(entry),
            code = "runtime.release.pack-entry-storage-invalid",
            details = { mapOf("name" to entry.name) },
            message = "A signed runtime release entry has unsupported or non-contiguous storage.",
        )
        releaseRequire(
            entry.size > 0L && entry.compressedSize == entry.size && entry.crc >= 0L &&
                entry.method == ZipArchiveOutputStream.STORED,
            code = "runtime.release.pack-entry-method-invalid",
            details = {
                mapOf(
                    "name" to entry.name,
                    "size" to entry.size.toString(),
                    "compressedSize" to entry.compressedSize.toString(),
                    "method" to entry.method.toString(),
                )
            },
            message = "A signed runtime release entry is not canonical STORED data.",
        )
        when (entry.name) {
            KWEB_RUNTIME_RELEASE_METADATA_PATH -> releaseRequire(
                entry.size <= KWEB_RUNTIME_RELEASE_MAX_METADATA_BYTES,
                code = "runtime.release.metadata-too-large",
                message = "The signed runtime release metadata exceeds its size limit.",
            )

            KWEB_RUNTIME_RELEASE_SIGNATURE_PATH -> releaseRequire(
                entry.size == KWEB_RUNTIME_RELEASE_SIGNATURE_SIZE.toLong(),
                code = "runtime.release.signature-size-invalid",
                details = { mapOf("size" to entry.size.toString()) },
                message = "The signed runtime release signature is not exactly 64 bytes.",
            )
        }
    }

    private fun readSmallEntry(zip: ZipFile, entry: ZipArchiveEntry, maximumSize: Long): ByteArray {
        releaseRequire(
            entry.size in 1..maximumSize && entry.size <= Int.MAX_VALUE,
            code = "runtime.release.pack-entry-size-invalid",
            details = { mapOf("name" to entry.name, "size" to entry.size.toString()) },
            message = "A signed runtime release metadata entry has an invalid size.",
        )
        val output = ByteArrayOutputStream(entry.size.toInt())
        val crc = CRC32()
        var size = 0L
        try {
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    releaseRequire(
                        size <= entry.size,
                        code = "runtime.release.pack-entry-size-overflow",
                        details = { mapOf("name" to entry.name) },
                        message = "A signed runtime release entry expands beyond its declared size.",
                    )
                    crc.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.pack-entry-read-failed",
                details = mapOf("name" to entry.name),
                message = "Unable to read a signed runtime release entry.",
                cause = error,
            )
        }
        releaseRequire(
            size == entry.size && crc.value == entry.crc,
            code = "runtime.release.pack-entry-crc-mismatch",
            details = { mapOf("name" to entry.name) },
            message = "A signed runtime release entry size or CRC is invalid.",
        )
        return output.toByteArray()
    }

    private fun copyPayloadEntry(
        zip: ZipFile,
        entry: ZipArchiveEntry,
        destination: Path,
    ): KWebRuntimeReleaseContentDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            zip.getInputStream(entry).use { input ->
                Files.newOutputStream(
                    destination,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        releaseRequire(
                            size <= entry.size,
                            code = "runtime.release.payload-size-overflow",
                            message = "The nested payload expands beyond its declared size.",
                        )
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        crc.update(buffer, 0, count)
                    }
                }
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.payload-extract-failed",
                details = mapOf("path" to destination.toString()),
                message = "Unable to extract the nested payload for independent verification.",
                cause = error,
            )
        }
        releaseRequire(
            size == entry.size && crc.value == entry.crc,
            code = "runtime.release.payload-crc-mismatch",
            message = "The nested payload size or CRC does not match its ZIP metadata.",
        )
        return KWebRuntimeReleaseContentDigest(size, digest.digest().toHex(), crc.value)
    }

    private fun verifyAuthenticatedMetadata(
        manifest: KWebRuntimeReleaseManifest,
        contents: PackContents,
        trustedKeyId: String,
        publicKey: java.security.PublicKey,
    ) {
        releaseRequire(
            manifest.signatureAlgorithm == KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM,
            code = "runtime.release.metadata-signature-algorithm-invalid",
            details = { mapOf("algorithm" to manifest.signatureAlgorithm) },
            message = "The signed runtime release metadata does not declare Ed25519.",
        )
        releaseRequire(
            manifest.keyId.matches(KWEB_RUNTIME_RELEASE_KEY_ID_PATTERN) &&
                manifest.keyId == trustedKeyId,
            code = "runtime.release.metadata-key-id-mismatch",
            details = { mapOf("actual" to manifest.keyId, "expected" to trustedKeyId) },
            message = "The signed runtime release key ID does not match the trusted public key.",
        )
        releaseRequire(
            KWebRuntimeReleaseKeys.verify(publicKey, contents.metadata, contents.signature),
            code = "runtime.release.signature-invalid",
            message = "The Ed25519 runtime release signature is invalid.",
        )
        releaseRequire(
            manifest.payload.size == contents.payloadDigest.size,
            code = "runtime.release.metadata-payload-size-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.payload.size.toString(),
                    "expected" to contents.payloadDigest.size.toString(),
                )
            },
            message = "The signed payload size does not match authenticated metadata.",
        )
        releaseRequire(
            manifest.payload.sha256.matches(KWEB_RUNTIME_RELEASE_SHA256_PATTERN) &&
                manifest.payload.sha256 == contents.payloadDigest.sha256,
            code = "runtime.release.metadata-payload-digest-mismatch",
            details = {
                mapOf(
                    "actual" to manifest.payload.sha256,
                    "expected" to contents.payloadDigest.sha256,
                )
            },
            message = "The signed payload digest does not match authenticated metadata.",
        )
    }

    private fun verifyNestedPayload(
        request: KWebRuntimeReleaseVerificationRequest,
        payload: Path,
    ): KWebRuntimePayloadManifest = try {
        KWebRuntimePayloadVerifier.verify(
            KWebRuntimePayloadVerificationRequest(
                archive = payload,
                catalog = request.catalog,
                target = request.target,
                productVersion = request.productVersion,
            ),
        )
    } catch (error: KWebRuntimePayloadException) {
        releaseFailure(
            code = "runtime.release.payload-invalid",
            details = mapOf("payloadCode" to error.code),
            message = "The signed runtime release contains an invalid nested payload.",
            cause = error,
        )
    }

    private fun digestPack(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.pack-digest-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to compute the signed runtime release pack digest.",
                cause = error,
            )
        }
        return digest.digest().toHex()
    }

    private fun createTemporaryPayload(pack: Path): Path = try {
        Files.createTempFile(checkNotNull(pack.parent), ".${pack.fileName}.payload.", ".zip")
    } catch (error: Exception) {
        releaseFailure(
            code = "runtime.release.payload-temporary-create-failed",
            details = mapOf("pack" to pack.toString()),
            message = "Unable to create a temporary nested payload for verification.",
            cause = error,
        )
    }

    private fun deleteTemporaryPayload(path: Path, primaryFailure: Throwable?) {
        try {
            Files.deleteIfExists(path)
        } catch (error: Exception) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(error)
            } else {
                releaseFailure(
                    code = "runtime.release.payload-temporary-delete-failed",
                    details = mapOf("path" to path.toString()),
                    message = "Unable to remove the temporary verified payload.",
                    cause = error,
                )
            }
        }
    }

    private fun verifyEnvelope(path: Path): ZipEnvelope {
        try {
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                val size = channel.size()
                releaseRequire(
                    size in MIN_ZIP_SIZE..MAX_CLASSIC_ZIP_SIZE,
                    code = "runtime.release.pack-size-invalid",
                    details = { mapOf("size" to size.toString()) },
                    message = "The signed runtime release must be a non-empty classic ZIP.",
                )
                releaseRequire(
                    readUnsignedInt(channel, 0L) == LOCAL_FILE_HEADER_SIGNATURE,
                    code = "runtime.release.pack-prefix-invalid",
                    message = "The signed runtime release does not start with a local ZIP header.",
                )
                val tailSize = minOf(size, MAX_EOCD_SEARCH.toLong()).toInt()
                val tailOffset = size - tailSize
                val tail = ByteArray(tailSize)
                readFully(channel, ByteBuffer.wrap(tail), tailOffset)
                val eocdIndex = findValidEocd(tail)
                if (eocdIndex < 0) {
                    val lastSignature = findLastSignature(tail, EOCD_SIGNATURE_BYTES)
                    if (lastSignature >= 0 && lastSignature + EOCD_MIN_SIZE <= tail.size) {
                        val declaredEnd = lastSignature + EOCD_MIN_SIZE +
                            unsignedShort(tail, lastSignature + 20)
                        if (declaredEnd < tail.size) {
                            releaseFailure(
                                code = "runtime.release.pack-trailing-data",
                                details = mapOf("trailingBytes" to (tail.size - declaredEnd).toString()),
                                message = "The signed runtime release ZIP contains trailing data.",
                            )
                        }
                    }
                    releaseFailure(
                        code = "runtime.release.pack-eocd-invalid",
                        message = "The signed runtime release ZIP has no terminal EOCD record.",
                    )
                }
                val eocdOffset = tailOffset + eocdIndex
                val diskNumber = unsignedShort(tail, eocdIndex + 4)
                val centralDisk = unsignedShort(tail, eocdIndex + 6)
                val entriesOnDisk = unsignedShort(tail, eocdIndex + 8)
                val totalEntries = unsignedShort(tail, eocdIndex + 10)
                val centralSize = unsignedInt(tail, eocdIndex + 12)
                val centralOffset = unsignedInt(tail, eocdIndex + 16)
                val commentLength = unsignedShort(tail, eocdIndex + 20)
                releaseRequire(
                    commentLength == 0,
                    code = "runtime.release.pack-comment-invalid",
                    message = "The signed runtime release ZIP must not have an archive comment.",
                )
                releaseRequire(
                    diskNumber == 0 && centralDisk == 0 && entriesOnDisk == totalEntries,
                    code = "runtime.release.pack-multidisk-invalid",
                    message = "The signed runtime release ZIP cannot span multiple disks.",
                )
                releaseRequire(
                    totalEntries == KWEB_RUNTIME_RELEASE_PACK_ENTRY_NAMES.size,
                    code = "runtime.release.pack-entry-count-invalid",
                    details = { mapOf("entries" to totalEntries.toString()) },
                    message = "The signed runtime release ZIP must contain exactly three entries.",
                )
                releaseRequire(
                    centralOffset != ZIP64_SENTINEL_32 &&
                        centralSize != ZIP64_SENTINEL_32 &&
                        centralOffset + centralSize == eocdOffset,
                    code = "runtime.release.pack-central-directory-invalid",
                    details = {
                        mapOf(
                            "offset" to centralOffset.toString(),
                            "size" to centralSize.toString(),
                            "eocd" to eocdOffset.toString(),
                        )
                    },
                    message = "The signed runtime release central directory extent is invalid.",
                )
                releaseRequire(
                    readUnsignedInt(channel, centralOffset) == CENTRAL_DIRECTORY_HEADER_SIGNATURE,
                    code = "runtime.release.pack-central-directory-invalid",
                    message = "The signed runtime release central directory is not at its declared offset.",
                )
                return ZipEnvelope(totalEntries, centralOffset)
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.pack-envelope-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to inspect the signed runtime release ZIP envelope.",
                cause = error,
            )
        }
    }

    private fun decodeUtf8(bytes: ByteArray, code: String): String = try {
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        releaseFailure(code, message = "The signed runtime release contains malformed UTF-8.", cause = error)
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
        (unsignedShort(bytes, offset).toLong() or
            (unsignedShort(bytes, offset + 2).toLong() shl 16)) and 0xffff_ffffL

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
            releaseRequire(
                count > 0,
                code = "runtime.release.pack-truncated",
                details = { mapOf("offset" to position.toString()) },
                message = "The signed runtime release ZIP is truncated.",
            )
            position += count
        }
    }

    private fun <T> Enumeration<T>.toList(): List<T> = buildList {
        while (this@toList.hasMoreElements()) add(this@toList.nextElement())
    }

    private data class PackContents(
        val metadata: ByteArray,
        val signature: ByteArray,
        val payloadDigest: KWebRuntimeReleaseContentDigest,
    )

    private data class ZipEnvelope(
        val entryCount: Int,
        val centralDirectoryOffset: Long,
    )

    private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
    private val EOCD_SIGNATURE_BYTES: ByteArray = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    private const val LOCAL_FILE_HEADER_SIGNATURE: Long = 0x04034b50L
    private const val CENTRAL_DIRECTORY_HEADER_SIGNATURE: Long = 0x02014b50L
    private const val MIN_ZIP_SIZE: Long = 22L
    private const val MAX_CLASSIC_ZIP_SIZE: Long = 0xffff_ffffL
    private const val MAX_EOCD_SEARCH: Int = 65_557
    private const val EOCD_MIN_SIZE: Int = 22
    private const val ZIP64_SENTINEL_32: Long = 0xffff_ffffL
    private const val UTF8_NAMES_FLAG: Int = 0x0800
    private const val FILE_PERMISSIONS: Int = 0b110100100
}
