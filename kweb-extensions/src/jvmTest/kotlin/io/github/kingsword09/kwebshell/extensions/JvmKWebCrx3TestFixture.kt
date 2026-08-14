package io.github.kingsword09.kwebshell.extensions

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object JvmKWebCrx3TestFixture {
    fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    fun ecKeyPair(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    fun extensionId(keyPair: KeyPair): String =
        KWebExtensionId.fromSha256Hash(MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded))

    fun extensionIdBytes(keyPair: KeyPair): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).copyOf(16)

    fun zipArchive(entries: Map<String, String>, useDataDescriptor: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, value) ->
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                val entry = ZipEntry(name)
                if (!useDataDescriptor) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    fun buildCrx3(
        keyPair: KeyPair,
        archive: ByteArray,
        corruptSignature: Boolean = false,
    ): ByteArray {
        val signedHeader = signedData(extensionIdBytes(keyPair))
        val proof = proof(keyPair, signedHeader, archive, corruptSignature)
        val proofField = if (keyPair.public.algorithm == "RSA") 2 else 3
        return crxContainer(field(proofField, proof) + field(10000, signedHeader), archive)
    }

    fun proof(
        keyPair: KeyPair,
        signedHeader: ByteArray,
        archive: ByteArray,
        corruptSignature: Boolean = false,
    ): ByteArray {
        val context = SIGNATURE_CONTEXT + leInt(signedHeader.size) + signedHeader + archive
        val algorithm = if (keyPair.public.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
        val signature = Signature.getInstance(algorithm).apply {
            initSign(keyPair.private)
            update(context)
        }.sign()
        if (corruptSignature) signature[0] = (signature[0].toInt() xor 1).toByte()
        return field(1, keyPair.public.encoded) + field(2, signature)
    }

    fun signedData(id: ByteArray): ByteArray = field(1, id)

    fun field(number: Int, value: ByteArray): ByteArray = fieldKey(number) + varint(value.size.toLong()) + value

    fun fieldKey(number: Int): ByteArray = varint((number.toLong() shl 3) or 2)

    fun crxContainer(header: ByteArray, archive: ByteArray): ByteArray =
        "Cr24".toByteArray(StandardCharsets.US_ASCII) + leInt(3) + leInt(header.size) + header + archive

    fun varint(value: Long): ByteArray {
        var current = value
        val output = ByteArrayOutputStream()
        while (current >= 0x80L) {
            output.write(((current and 0x7fL) or 0x80L).toInt())
            current = current ushr 7
        }
        output.write(current.toInt())
        return output.toByteArray()
    }

    fun leInt(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private val SIGNATURE_CONTEXT: ByteArray =
        "CRX3 SignedData\u0000".toByteArray(StandardCharsets.UTF_8)
}
