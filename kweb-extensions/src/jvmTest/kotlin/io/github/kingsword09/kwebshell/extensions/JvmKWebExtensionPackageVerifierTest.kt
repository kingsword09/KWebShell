package io.github.kingsword09.kwebshell.extensions

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmKWebExtensionPackageVerifierTest {
    @Test
    fun verifiesACompleteUnpackedPackage() = withTempDirectory { root ->
        val keyPair = rsaKeyPair()
        writeUnpackedPackage(root, keyPair)

        val verified = JvmKWebExtensionPackageVerifier.verifyUnpacked(root)

        assertEquals(KWebExtensionPackageFormat.UNPACKED, verified.packageInfo.format)
        assertEquals(extensionId(keyPair), verified.packageInfo.extensionId)
        assertEquals(KWebExtensionPermissionKind.API_PERMISSION, verified.packageInfo.permissionReview.required.single().kind)
    }

    @Test
    fun rejectsMissingKeysResourcesAndTraversal() = withTempDirectory { root ->
        root.resolve("manifest.json").writeText(baseManifest(backgroundPath = "worker.js"))
        root.resolve("worker.js").writeText("self.oninstall=()=>{};")
        assertCode("extensions.manifest.public-key-missing") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        val keyPair = rsaKeyPair()
        val key = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        root.resolve("manifest.json").writeText(baseManifest(key = key, backgroundPath = "missing.js"))
        assertCode("extensions.package.resource-missing") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        root.resolve("manifest.json").writeText(baseManifest(key = key, backgroundPath = "../worker.js"))
        assertCode("extensions.manifest.resource-path-traversal") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }
    }

    @Test
    fun rejectsMalformedWeakAndUnsupportedPublicKeys() = withTempDirectory { root ->
        root.resolve("worker.js").writeText("self.oninstall=()=>{};")

        root.resolve("manifest.json").writeText(baseManifest(key = "%%%", backgroundPath = "worker.js"))
        assertCode("extensions.manifest.public-key-invalid-base64") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        val malformedDer = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        root.resolve("manifest.json").writeText(baseManifest(key = malformedDer, backgroundPath = "worker.js"))
        assertCode("extensions.public-key.invalid") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        val weakRsa = Base64.getEncoder().encodeToString(rsaKeyPair(1024).public.encoded)
        root.resolve("manifest.json").writeText(baseManifest(key = weakRsa, backgroundPath = "worker.js"))
        assertCode("extensions.public-key.rsa-too-small") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        val unsupportedCurve = Base64.getEncoder().encodeToString(ecKeyPair("secp384r1").public.encoded)
        root.resolve("manifest.json").writeText(baseManifest(key = unsupportedCurve, backgroundPath = "worker.js"))
        assertCode("extensions.public-key.ec-curve-unsupported") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        val oversizedBase64 = "A".repeat(24 * 1024 + 1)
        root.resolve("manifest.json").writeText(baseManifest(key = oversizedBase64, backgroundPath = "worker.js"))
        assertCode("extensions.manifest.public-key-too-large") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }
    }

    @Test
    fun rejectsInvalidUtf8AndBoundedUnpackedFiles() = withTempDirectory { root ->
        Files.write(root.resolve("manifest.json"), byteArrayOf(0xc3.toByte(), 0x28))
        assertCode("extensions.manifest.utf8-invalid") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        makeSparseFile(root.resolve("manifest.json"), 1024L * 1024 + 1)
        assertCode("extensions.manifest.file-too-large") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }

        writeUnpackedPackage(root, rsaKeyPair())
        makeSparseFile(root.resolve("oversized.bin"), 128L * 1024 * 1024 + 1)
        assertCode("extensions.package.bounds-exceeded") {
            JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
        }
    }

    @Test
    fun rejectsBoundedCrxFileBeforeReadingItsContents() = withTempCrx(byteArrayOf()) { path ->
        makeSparseFile(path, 132L * 1024 * 1024 + 13)

        assertCode("extensions.crx3.file-too-large") {
            JvmKWebExtensionPackageVerifier.verifyCrx3(path)
        }
    }

    @Test
    fun rejectsUnpackedSymlinks() = withTempDirectory { root ->
        val keyPair = rsaKeyPair()
        writeUnpackedPackage(root, keyPair)
        val symlink = root.resolve("linked.js")
        try {
            Files.createSymbolicLink(symlink, root.resolve("worker.js"))
            assertCode("extensions.package.symlink-rejected") {
                JvmKWebExtensionPackageVerifier.verifyUnpacked(root)
            }
        } finally {
            Files.deleteIfExists(symlink)
        }
    }

    @Test
    fun verifiesRsaEcdsaAndDataDescriptorCrx3Packages() {
        val fixtures = listOf(
            rsaKeyPair() to false,
            ecKeyPair() to true,
        )
        fixtures.forEach { (keyPair, useDataDescriptor) ->
            val archive = validArchive(useDataDescriptor)
            withTempCrx(buildCrx3(keyPair, archive)) { path ->
                val verified = JvmKWebExtensionPackageVerifier.verifyCrx3(path)
                assertEquals(extensionId(keyPair), verified.packageInfo.extensionId)
                assertEquals(KWebExtensionPackageFormat.CRX3, verified.packageInfo.format)
            }
        }

        val unsignedDescriptorArchive = unsignedDataDescriptorArchive()
        withTempCrx(buildCrx3(rsaKeyPair(), unsignedDescriptorArchive)) { path ->
            assertEquals(
                KWebExtensionPackageFormat.CRX3,
                JvmKWebExtensionPackageVerifier.verifyCrx3(path).packageInfo.format,
            )
        }
    }

    @Test
    fun rejectsTamperedSignedHeaderArchiveAndSignature() {
        val keyPair = rsaKeyPair()
        val archive = validArchive()
        val valid = buildCrx3(keyPair, archive)

        val archiveTampered = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertCrxCode("extensions.crx3.signature-mismatch", archiveTampered)

        val headerTampered = valid.copyOf()
        val headerSize = readLeInt(headerTampered, 8)
        val signedHeaderMarker = fieldKey(10000)
        val markerOffset = headerTampered.indexOfSubsequence(signedHeaderMarker, 12, 12 + headerSize)
        val signedHeaderLengthOffset = markerOffset + signedHeaderMarker.size
        val signedHeaderStart = signedHeaderLengthOffset + encodedVarintLength(headerTampered, signedHeaderLengthOffset)
        val crxIdLengthOffset = signedHeaderStart + fieldKey(1).size
        val crxIdStart = crxIdLengthOffset + encodedVarintLength(headerTampered, crxIdLengthOffset)
        headerTampered[crxIdStart] = (headerTampered[crxIdStart].toInt() xor 1).toByte()
        assertCrxCode("extensions.crx3.developer-proof-missing", headerTampered)

        val signatureTampered = buildCrx3(keyPair, archive, corruptSignature = true)
        assertCrxCode("extensions.crx3.signature-mismatch", signatureTampered)
    }

    @Test
    fun rejectsUnknownProtobufAndAmbiguousDeveloperProofs() {
        val keyPair = rsaKeyPair()
        val archive = validArchive()
        val signedHeader = signedData(extensionIdBytes(keyPair))
        val proof = proof(keyPair, signedHeader, archive)

        val unknownHeader = field(2, proof) + field(99, byteArrayOf(1)) + field(10000, signedHeader)
        assertCrxCode("extensions.crx3.protobuf-invalid", crxContainer(unknownHeader, archive))

        val ambiguousHeader = field(2, proof) + field(2, proof) + field(10000, signedHeader)
        assertCrxCode("extensions.crx3.developer-proof-ambiguous", crxContainer(ambiguousHeader, archive))

        val confusedHeader = field(2, proof) +
            field(4, leInt(END_OF_CENTRAL_DIRECTORY_SIGNATURE)) +
            field(10000, signedHeader)
        assertCrxCode("extensions.crx3.header-zip-token-invalid", crxContainer(confusedHeader, archive))

        val ecKeyPair = ecKeyPair()
        val ecSignedHeader = signedData(extensionIdBytes(ecKeyPair))
        val mislabeledProof = proof(ecKeyPair, ecSignedHeader, archive)
        val mislabeledHeader = field(2, mislabeledProof) + field(10000, ecSignedHeader)
        assertCrxCode("extensions.crx3.proof-key-type-mismatch", crxContainer(mislabeledHeader, archive))
    }

    @Test
    fun rejectsZipTraversalPortableCollisionsAndUnreferencedData() {
        val keyPair = rsaKeyPair()
        assertSignedArchiveCode(
            "extensions.crx3.zip-entry-traversal",
            keyPair,
            zipArchive(linkedMapOf("manifest.json" to crxManifest(), "worker.js" to "ok", "../escape.js" to "bad")),
        )
        assertSignedArchiveCode(
            "extensions.crx3.zip-path-collision",
            keyPair,
            zipArchive(linkedMapOf("manifest.json" to crxManifest(), "worker.js" to "ok", "Case.js" to "a", "case.js" to "b")),
        )

        val archive = validArchive()
        val centralOffset = findSignature(archive, CENTRAL_DIRECTORY_SIGNATURE)
        val withGap = archive.copyOfRange(0, centralOffset) + byteArrayOf(1) + archive.copyOfRange(centralOffset, archive.size)
        val eocd = findSignature(withGap, END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writeLeInt(withGap, eocd + 16, centralOffset + 1)
        assertSignedArchiveCode("extensions.crx3.zip-unreferenced-data", keyPair, withGap)
    }

    @Test
    fun rejectsInvalidDataDescriptorAndCrcMetadata() {
        val keyPair = rsaKeyPair()
        val descriptorArchive = validArchive(useDataDescriptor = true)
        val descriptorOffset = findSignature(descriptorArchive, DATA_DESCRIPTOR_SIGNATURE)
        descriptorArchive[descriptorOffset + 4] = (descriptorArchive[descriptorOffset + 4].toInt() xor 1).toByte()
        assertSignedArchiveCode("extensions.crx3.zip-data-descriptor-invalid", keyPair, descriptorArchive)

        val crcArchive = validArchive()
        val centralOffset = findSignature(crcArchive, CENTRAL_DIRECTORY_SIGNATURE)
        crcArchive[centralOffset + 16] = (crcArchive[centralOffset + 16].toInt() xor 1).toByte()
        assertSignedArchiveCode("extensions.crx3.zip-entry-metadata-mismatch", keyPair, crcArchive)

        val contentArchive = validArchive()
        val workerBytes = "self.oninstall=()=>{};".toByteArray(StandardCharsets.UTF_8)
        val workerOffset = contentArchive.indexOfSubsequence(workerBytes)
        require(workerOffset >= 0)
        contentArchive[workerOffset] = (contentArchive[workerOffset].toInt() xor 1).toByte()
        assertSignedArchiveCode("extensions.crx3.zip-crc-mismatch", keyPair, contentArchive)
    }

    @Test
    fun rejectsEncryptedSymlinkZip64AndOverlappingEntries() {
        val keyPair = rsaKeyPair()

        val encrypted = validArchive()
        val encryptedCentral = findSignature(encrypted, CENTRAL_DIRECTORY_SIGNATURE)
        val encryptedLocal = readLeInt(encrypted, encryptedCentral + 42)
        writeLeShort(encrypted, encryptedCentral + 8, readLeShort(encrypted, encryptedCentral + 8) or 1)
        writeLeShort(encrypted, encryptedLocal + 6, readLeShort(encrypted, encryptedLocal + 6) or 1)
        assertSignedArchiveCode("extensions.crx3.zip-encrypted-entry", keyPair, encrypted)

        val symlink = validArchive()
        val symlinkCentral = findSignature(symlink, CENTRAL_DIRECTORY_SIGNATURE)
        symlink[symlinkCentral + 5] = 3
        writeLeInt(symlink, symlinkCentral + 38, (0xa1ffL shl 16).toInt())
        assertSignedArchiveCode("extensions.crx3.zip-symlink-rejected", keyPair, symlink)

        val zip64 = validArchive()
        val zip64Central = findSignature(zip64, CENTRAL_DIRECTORY_SIGNATURE)
        writeLeInt(zip64, zip64Central + 20, -1)
        assertSignedArchiveCode("extensions.crx3.zip64-unsupported", keyPair, zip64)

        val overlapping = validArchive()
        val overlappingCentral = findSignature(overlapping, CENTRAL_DIRECTORY_SIGNATURE)
        val overlappingLocal = readLeInt(overlapping, overlappingCentral + 42)
        val expandedSize = readLeInt(overlapping, overlappingCentral + 20) + 1
        writeLeInt(overlapping, overlappingCentral + 20, expandedSize)
        writeLeInt(overlapping, overlappingCentral + 24, expandedSize)
        writeLeInt(overlapping, overlappingLocal + 18, expandedSize)
        writeLeInt(overlapping, overlappingLocal + 22, expandedSize)
        assertSignedArchiveCode("extensions.crx3.zip-overlapping-entry", keyPair, overlapping)

        val nonEmptyDirectory = zipArchive(
            linkedMapOf(
                "assets/" to "x",
                "manifest.json" to crxManifest(),
                "worker.js" to "ok",
            ),
        )
        assertSignedArchiveCode("extensions.crx3.zip-directory-not-empty", keyPair, nonEmptyDirectory)
    }

    @Test
    fun rejectsManifestKeyMismatchAndMissingReferencedResourceInCrx3() {
        val signingKey = rsaKeyPair()
        val differentKey = rsaKeyPair()
        val mismatchedManifest = crxManifest(Base64.getEncoder().encodeToString(differentKey.public.encoded))
        val mismatchArchive = zipArchive(linkedMapOf("manifest.json" to mismatchedManifest, "worker.js" to "ok"))
        assertSignedArchiveCode("extensions.crx3.manifest-key-mismatch", signingKey, mismatchArchive)

        val missingArchive = zipArchive(linkedMapOf("manifest.json" to crxManifest()))
        assertSignedArchiveCode("extensions.package.resource-missing", signingKey, missingArchive)
    }

    private fun writeUnpackedPackage(root: Path, keyPair: KeyPair) {
        val key = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        root.resolve("manifest.json").writeText(unpackedManifest(key))
        root.resolve("worker.js").writeText("self.oninstall=()=>{};")
        root.resolve("content.js").writeText("console.log('ok');")
        root.resolve("icon.png").writeText("png")
    }

    private fun unpackedManifest(key: String): String =
        """{"manifest_version":3,"name":"Fixture","version":"1.0.0","key":"$key","permissions":["storage"],"background":{"service_worker":"worker.js"},"content_scripts":[{"matches":["https://example.com/*"],"js":["content.js"]}],"icons":{"128":"icon.png"},"web_accessible_resources":[{"resources":["/icon.png"],"matches":["https://example.com/*"]}]}"""

    private fun baseManifest(key: String? = null, backgroundPath: String): String {
        val keyField = key?.let { "\"key\":\"$it\"," }.orEmpty()
        return """{"manifest_version":3,"name":"Fixture","version":"1",$keyField"background":{"service_worker":"$backgroundPath"}}"""
    }

    private fun crxManifest(key: String? = null): String {
        val keyField = key?.let { ",\"key\":\"$it\"" }.orEmpty()
        return """{"manifest_version":3,"name":"Fixture","version":"1.0.0"$keyField,"background":{"service_worker":"worker.js"}}"""
    }

    private fun validArchive(useDataDescriptor: Boolean = false): ByteArray = zipArchive(
        linkedMapOf("manifest.json" to crxManifest(), "worker.js" to "self.oninstall=()=>{};"),
        useDataDescriptor,
    )

    private fun unsignedDataDescriptorArchive(): ByteArray {
        val manifest = """{"manifest_version":3,"name":"Fixture","version":"1"}"""
        val archive = zipArchive(linkedMapOf("manifest.json" to manifest), useDataDescriptor = true)
        val descriptorOffset = findSignature(archive, DATA_DESCRIPTOR_SIGNATURE)
        val centralOffset = findSignature(archive, CENTRAL_DIRECTORY_SIGNATURE)
        val unsigned = archive.copyOfRange(0, descriptorOffset) + archive.copyOfRange(descriptorOffset + 4, archive.size)
        val eocd = findSignature(unsigned, END_OF_CENTRAL_DIRECTORY_SIGNATURE)
        writeLeInt(unsigned, eocd + 16, centralOffset - 4)
        return unsigned
    }

    private fun zipArchive(entries: Map<String, String>, useDataDescriptor: Boolean = false): ByteArray {
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

    private fun buildCrx3(
        keyPair: KeyPair,
        archive: ByteArray,
        corruptSignature: Boolean = false,
    ): ByteArray {
        val signedHeader = signedData(extensionIdBytes(keyPair))
        val proof = proof(keyPair, signedHeader, archive, corruptSignature)
        val proofField = if (keyPair.public.algorithm == "RSA") 2 else 3
        return crxContainer(field(proofField, proof) + field(10000, signedHeader), archive)
    }

    private fun proof(
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

    private fun signedData(id: ByteArray): ByteArray = field(1, id)

    private fun field(number: Int, value: ByteArray): ByteArray = fieldKey(number) + varint(value.size.toLong()) + value

    private fun fieldKey(number: Int): ByteArray = varint((number.toLong() shl 3) or 2)

    private fun crxContainer(header: ByteArray, archive: ByteArray): ByteArray =
        "Cr24".toByteArray(StandardCharsets.US_ASCII) + leInt(3) + leInt(header.size) + header + archive

    private fun assertSignedArchiveCode(code: String, keyPair: KeyPair, archive: ByteArray) {
        assertCrxCode(code, buildCrx3(keyPair, archive))
    }

    private fun assertCrxCode(code: String, bytes: ByteArray) = withTempCrx(bytes) { path ->
        assertCode(code) { JvmKWebExtensionPackageVerifier.verifyCrx3(path) }
    }

    private fun assertCode(code: String, operation: () -> Unit) {
        val error = assertFailsWith<KWebExtensionVerificationException>(message = code, block = operation)
        assertEquals(code, error.code)
    }

    private fun rsaKeyPair(bits: Int = 2048): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    private fun ecKeyPair(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec(curve))
    }.generateKeyPair()

    private fun extensionId(keyPair: KeyPair): String =
        KWebExtensionId.fromSha256Hash(MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded))

    private fun extensionIdBytes(keyPair: KeyPair): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).copyOf(16)

    private fun varint(value: Long): ByteArray {
        var current = value
        val output = ByteArrayOutputStream()
        while (current >= 0x80L) {
            output.write(((current and 0x7fL) or 0x80L).toInt())
            current = current ushr 7
        }
        output.write(current.toInt())
        return output.toByteArray()
    }

    private fun encodedVarintLength(bytes: ByteArray, offset: Int): Int {
        var cursor = offset
        do {
            val continued = bytes[cursor++].toInt() and 0x80 != 0
        } while (continued)
        return cursor - offset
    }

    private fun findSignature(bytes: ByteArray, signature: Int): Int =
        bytes.indexOfSubsequence(leInt(signature)).also { require(it >= 0) }

    private fun ByteArray.indexOfSubsequence(needle: ByteArray, start: Int = 0, end: Int = size): Int {
        outer@ for (index in start..end - needle.size) {
            for (offset in needle.indices) if (this[index + offset] != needle[offset]) continue@outer
            return index
        }
        return -1
    }

    private fun readLeInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun readLeShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff

    private fun writeLeInt(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun writeLeShort(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
    }

    private fun leInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private inline fun <T> withTempDirectory(operation: (Path) -> T): T {
        val root = Files.createTempDirectory("kweb-extension-test-")
        try {
            return operation(root)
        } finally {
            deleteRecursively(root)
        }
    }

    private inline fun <T> withTempCrx(bytes: ByteArray, operation: (Path) -> T): T {
        val path = Files.createTempFile("kweb-extension-crx3-test-", ".crx")
        try {
            Files.write(path, bytes)
            return operation(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun makeSparseFile(path: Path, size: Long) {
        Files.newByteChannel(
            path,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        ).use { channel ->
            channel.position(size - 1)
            channel.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private companion object {
        val SIGNATURE_CONTEXT: ByteArray = "CRX3 SignedData\u0000".toByteArray(StandardCharsets.UTF_8)
        const val CENTRAL_DIRECTORY_SIGNATURE: Int = 0x02014b50
        const val END_OF_CENTRAL_DIRECTORY_SIGNATURE: Int = 0x06054b50
        const val DATA_DESCRIPTOR_SIGNATURE: Int = 0x08074b50
    }
}
