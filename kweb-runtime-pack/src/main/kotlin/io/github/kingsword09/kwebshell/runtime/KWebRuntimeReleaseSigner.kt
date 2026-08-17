package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.zip.CRC32

internal data class KWebRuntimeReleaseSignRequest(
    val payloadArchive: Path,
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
    val privateKey: Path,
    val publicKey: Path,
    val outputPack: Path,
)

internal data class KWebRuntimeReleaseSignResult(
    val pack: Path,
    val packSha256: String,
    val manifest: KWebRuntimeReleaseManifest,
)

internal object KWebRuntimeReleaseSigner {
    fun sign(request: KWebRuntimeReleaseSignRequest): KWebRuntimeReleaseSignResult {
        val payload = validatePayloadPath(request.payloadArchive)
        val output = validateOutputPath(request.outputPack)
        releaseRequire(
            payload != output,
            code = "runtime.release.output-input-same",
            details = { mapOf("path" to output.toString()) },
            message = "The signed runtime release output cannot replace its payload input.",
        )

        val payloadManifest = KWebRuntimePayloadVerifier.verify(
            KWebRuntimePayloadVerificationRequest(
                archive = payload,
                catalog = request.catalog,
                target = request.target,
                productVersion = request.productVersion,
            ),
        )
        val payloadDigest = KWebRuntimeReleaseFileIO.digest(payload)
        releaseRequire(
            payloadDigest.size <= ZIP_CLASSIC_MAX_SIZE,
            code = "runtime.release.payload-too-large",
            details = { mapOf("size" to payloadDigest.size.toString()) },
            message = "The signed runtime release payload requires unsupported ZIP64 metadata.",
        )

        val privateKey = KWebRuntimeReleaseKeys.loadPrivateKey(request.privateKey)
        val publicKey = KWebRuntimeReleaseKeys.loadPublicKey(request.publicKey)
        KWebRuntimeReleaseKeys.requireMatchingPair(privateKey, publicKey)
        val keyId = KWebRuntimeReleaseKeys.keyId(publicKey)
        val manifest = KWebRuntimeReleaseManifest(
            schemaVersion = KWEB_RUNTIME_RELEASE_SCHEMA_VERSION,
            product = KWEB_RUNTIME_RELEASE_PRODUCT,
            productVersion = request.productVersion,
            target = request.target.id,
            cefVersion = payloadManifest.cefVersion,
            chromiumVersion = payloadManifest.chromiumVersion,
            payload = KWebRuntimeReleasePayload(
                fileName = KWEB_RUNTIME_RELEASE_PAYLOAD_FILE_NAME,
                size = payloadDigest.size,
                sha256 = payloadDigest.sha256,
                treeSha256 = payloadManifest.treeSha256,
            ),
            signatureAlgorithm = KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM,
            keyId = keyId,
        )
        KWebRuntimeReleaseContract.validateManifest(
            manifest = manifest,
            catalog = request.catalog,
            target = request.target,
            productVersion = request.productVersion,
            payloadDigest = payloadDigest,
            trustedKeyId = keyId,
        )
        KWebRuntimeReleaseContract.validatePayloadManifest(manifest, payloadManifest)
        val metadataBytes = KWebRuntimeReleaseManifestCodec.encode(manifest)
        releaseRequire(
            metadataBytes.size.toLong() <= KWEB_RUNTIME_RELEASE_MAX_METADATA_BYTES,
            code = "runtime.release.metadata-too-large",
            details = { mapOf("size" to metadataBytes.size.toString()) },
            message = "The signed runtime release metadata exceeds its size limit.",
        )
        val signature = KWebRuntimeReleaseKeys.sign(privateKey, metadataBytes)

        val temporary = createSiblingTemporary(output)
        try {
            writePack(
                path = temporary,
                metadata = metadataBytes,
                payload = payload,
                payloadDigest = payloadDigest,
                signature = signature,
            )
            KWebRuntimeReleaseVerifier.verify(
                KWebRuntimeReleaseVerificationRequest(
                    pack = temporary,
                    catalog = request.catalog,
                    target = request.target,
                    productVersion = request.productVersion,
                    trustedPublicKey = request.publicKey,
                ),
            )
            publishAtomically(temporary, output)
        } catch (error: KWebRuntimeReleaseException) {
            deleteTemporary(temporary, error)
            throw error
        } catch (error: Exception) {
            deleteTemporary(temporary, error)
            releaseFailure(
                code = "runtime.release.pack-write-failed",
                details = mapOf("path" to output.toString()),
                message = "Unable to build and verify the signed runtime release pack.",
                cause = error,
            )
        }
        return KWebRuntimeReleaseSignResult(
            pack = output,
            packSha256 = sha256(output),
            manifest = manifest,
        )
    }

    private fun validatePayloadPath(path: Path): Path {
        releaseRequire(
            path.isAbsolute && path == path.normalize(),
            code = "runtime.release.payload-path-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The unsigned payload path must be absolute and normalized.",
        )
        releaseRequire(
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            code = "runtime.release.payload-file-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The unsigned payload must be a regular non-symbolic-link file.",
        )
        return path
    }

    private fun validateOutputPath(path: Path): Path {
        releaseRequire(
            path.isAbsolute && path == path.normalize(),
            code = "runtime.release.output-path-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The signed runtime release output path must be absolute and normalized.",
        )
        val parent = path.parent
        releaseRequire(
            parent != null && Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS),
            code = "runtime.release.output-directory-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The signed runtime release output directory must already exist.",
        )
        releaseRequire(
            !Files.isSymbolicLink(path) &&
                (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) ||
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)),
            code = "runtime.release.output-file-invalid",
            details = { mapOf("path" to path.toString()) },
            message = "The signed runtime release output must be absent or a regular file.",
        )
        return path
    }

    private fun writePack(
        path: Path,
        metadata: ByteArray,
        payload: Path,
        payloadDigest: KWebRuntimeReleaseContentDigest,
        signature: ByteArray,
    ) {
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
                archive.setLevel(java.util.zip.Deflater.BEST_COMPRESSION)
                writeBytesEntry(archive, KWEB_RUNTIME_RELEASE_METADATA_PATH, metadata)
                writePayloadEntry(archive, payload, payloadDigest)
                writeBytesEntry(archive, KWEB_RUNTIME_RELEASE_SIGNATURE_PATH, signature)
                archive.finish()
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.pack-write-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to write the deterministic signed runtime release ZIP.",
                cause = error,
            )
        }
    }

    private fun writeBytesEntry(archive: ZipArchiveOutputStream, name: String, bytes: ByteArray) {
        val crc = CRC32().apply { update(bytes) }
        val entry = regularEntry(name, bytes.size.toLong(), crc.value)
        archive.putArchiveEntry(entry)
        archive.write(bytes)
        archive.closeArchiveEntry()
    }

    private fun writePayloadEntry(
        archive: ZipArchiveOutputStream,
        path: Path,
        expected: KWebRuntimeReleaseContentDigest,
    ) {
        val entry = regularEntry(KWEB_RUNTIME_RELEASE_PAYLOAD_PATH, expected.size, expected.crc32)
        archive.putArchiveEntry(entry)
        var primaryFailure: Throwable? = null
        try {
            KWebRuntimeReleaseFileIO.copyVerified(path, archive, expected)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                archive.closeArchiveEntry()
            } catch (closeError: Exception) {
                if (primaryFailure != null) primaryFailure.addSuppressed(closeError) else throw closeError
            }
        }
    }

    private fun regularEntry(name: String, size: Long, crc: Long): ZipArchiveEntry =
        ZipArchiveEntry(name).also { entry ->
            entry.setTimeLocal(FIXED_TIMESTAMP)
            entry.setUnixMode(UnixStat.FILE_FLAG or FILE_PERMISSIONS)
            entry.size = size
            entry.crc = crc
            entry.method = ZipArchiveOutputStream.STORED
        }

    private fun createSiblingTemporary(output: Path): Path = try {
        Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
    } catch (error: Exception) {
        releaseFailure(
            code = "runtime.release.temporary-create-failed",
            details = mapOf("output" to output.toString()),
            message = "Unable to create a sibling temporary signed runtime release pack.",
            cause = error,
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
            releaseFailure(
                code = "runtime.release.atomic-move-unsupported",
                details = mapOf("temporary" to temporary.toString(), "output" to output.toString()),
                message = "The output filesystem does not support atomic signed release publication.",
                cause = error,
            )
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.atomic-move-failed",
                details = mapOf("temporary" to temporary.toString(), "output" to output.toString()),
                message = "Unable to publish the signed runtime release atomically.",
                cause = error,
            )
        }
    }

    private fun deleteTemporary(path: Path, original: Throwable) {
        try {
            Files.deleteIfExists(path)
        } catch (cleanup: Exception) {
            original.addSuppressed(cleanup)
        }
    }

    private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
    private const val FILE_PERMISSIONS: Int = 0b110100100
    private const val ZIP_CLASSIC_MAX_SIZE: Long = 0xffff_ffffL
}
