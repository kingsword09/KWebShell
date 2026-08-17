package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.util.Enumeration
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebRuntimeReleaseTest {
    @Test
    fun signsAndVerifiesEverySupportedTargetDeterministically() {
        KWebTarget.supported.sortedBy { it.id }.forEach { target ->
            KWebRuntimeReleaseTestFixture.create(target).use { fixture ->
                val first = fixture.sign("first-release.zip")
                val second = fixture.sign("second-release.zip")
                assertContentEquals(Files.readAllBytes(first.pack), Files.readAllBytes(second.pack))
                assertEquals(first.packSha256, second.packSha256)
                val verified = fixture.verify(first.pack)
                assertEquals(first.manifest, verified.manifest)
                assertEquals(fixture.payload.manifest, verified.payloadManifest)
                assertEquals(target.id, verified.manifest.target)
                assertEquals(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM, verified.manifest.signatureAlgorithm)
                assertTrue(verified.manifest.keyId.matches(KWEB_RUNTIME_RELEASE_KEY_ID_PATTERN))
            }
        }
    }

    @Test
    fun rejectsWrongTargetVersionAndTrustedKey() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-x64")).use { fixture ->
            val release = fixture.sign()
            val targetError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.verify(release.pack, expectedTarget = KWebTarget.parse("windows-x64"))
            }
            assertEquals("runtime.release.metadata-target-mismatch", targetError.code)
            val versionError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.verify(release.pack, expectedVersion = "9.9.9")
            }
            assertEquals("runtime.release.metadata-product-version-mismatch", versionError.code)
            val (_, otherPublicKey) = fixture.createKeyPair("other-trusted")
            val keyError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.verify(release.pack, trustedPublicKey = otherPublicKey)
            }
            assertEquals("runtime.release.metadata-key-id-mismatch", keyError.code)
        }
    }

    @Test
    fun rejectsMalformedAndMismatchedKeys() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("windows-x64")).use { fixture ->
            val (_, otherPublicKey) = fixture.createKeyPair("other-signing")
            val mismatch = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.sign(
                    name = "mismatch.zip",
                    signingPrivateKey = fixture.privateKey,
                    signingPublicKey = otherPublicKey,
                )
            }
            assertEquals("runtime.release.key-pair-mismatch", mismatch.code)
            assertTrue(Files.notExists(fixture.outputDirectory.resolve("mismatch.zip")))

            val malformedPrivate = fixture.root.resolve("malformed-private.pk8")
            Files.writeString(malformedPrivate, "not a private key")
            val privateError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.sign("malformed-private.zip", signingPrivateKey = malformedPrivate)
            }
            assertEquals("runtime.release.private-key-invalid", privateError.code)

            val malformedPublic = fixture.root.resolve("malformed-public.der")
            Files.writeString(malformedPublic, "not a public key")
            val publicError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.sign("malformed-public.zip", signingPublicKey = malformedPublic)
            }
            assertEquals("runtime.release.public-key-invalid", publicError.code)
        }
    }

    @Test
    fun preservesExistingOutputWhenSigningFails() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-arm64")).use { fixture ->
            val output = fixture.outputDirectory.resolve("release.zip")
            val original = "existing-release".toByteArray(StandardCharsets.UTF_8)
            Files.write(output, original)
            val (_, otherPublicKey) = fixture.createKeyPair("wrong")
            val error = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.sign(signingPublicKey = otherPublicKey)
            }
            assertEquals("runtime.release.key-pair-mismatch", error.code)
            assertContentEquals(original, Files.readAllBytes(output))
            Files.newDirectoryStream(fixture.outputDirectory).use { stream ->
                assertEquals(listOf("payload.zip", "release.zip"), stream.map { it.fileName.toString() }.sorted())
            }
        }
    }

    @Test
    fun rejectsMetadataSignatureAndPayloadTampering() {
        val cases = listOf(
            KWEB_RUNTIME_RELEASE_METADATA_PATH to "runtime.release.signature-invalid",
            KWEB_RUNTIME_RELEASE_SIGNATURE_PATH to "runtime.release.signature-invalid",
            KWEB_RUNTIME_RELEASE_PAYLOAD_PATH to "runtime.release.metadata-payload-digest-mismatch",
        )
        cases.forEach { (tamperedEntry, expectedCode) ->
            KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-x64")).use { fixture ->
                val release = fixture.sign("tampered-${tamperedEntry.substringBefore('.')}.zip")
                rewritePack(release.pack, transform = { name, bytes ->
                    if (name != tamperedEntry) {
                        bytes
                    } else if (name == KWEB_RUNTIME_RELEASE_METADATA_PATH) {
                        String(bytes, StandardCharsets.UTF_8)
                            .replace("KWebShell", "KWebSheLl")
                            .toByteArray(StandardCharsets.UTF_8)
                    } else {
                        bytes.copyOf().also { changed ->
                            changed[0] = (changed[0].toInt() xor 0x01).toByte()
                        }
                    }
                })
                val error = assertFailsWith<KWebRuntimeReleaseException> {
                    fixture.verify(release.pack)
                }
                assertEquals(expectedCode, error.code)
            }
        }
    }

    @Test
    fun rejectsNonCanonicalTimestampAndMode() {
        val cases = listOf<Pair<String, (String, ZipArchiveEntry) -> Unit>>(
            "runtime.release.pack-entry-timestamp-invalid" to { name, entry ->
                if (name == KWEB_RUNTIME_RELEASE_PAYLOAD_PATH) {
                    entry.setTimeLocal(LocalDateTime.of(2001, 1, 1, 0, 0, 0))
                }
            },
            "runtime.release.pack-entry-mode-invalid" to { name, entry ->
                if (name == KWEB_RUNTIME_RELEASE_PAYLOAD_PATH) {
                    entry.setUnixMode(UnixStat.FILE_FLAG or 0b110000000)
                }
            },
        )
        cases.forEach { (expectedCode, mutation) ->
            KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-arm64")).use { fixture ->
                val release = fixture.sign("metadata-attack.zip")
                rewritePack(release.pack, mutateEntry = mutation)
                val error = assertFailsWith<KWebRuntimeReleaseException> {
                    fixture.verify(release.pack)
                }
                assertEquals(expectedCode, error.code)
            }
        }
    }

    @Test
    fun rejectsOrderDuplicateAndTrailingDataAttacks() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("windows-arm64")).use { fixture ->
            val release = fixture.sign("order.zip")
            rewritePack(release.pack, reorder = { it.reversed() })
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.pack-entry-layout-invalid", error.code)
        }
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-x64")).use { fixture ->
            val release = fixture.sign("duplicate.zip")
            rewritePack(release.pack, reorder = { entries ->
                listOf(entries[0], entries[1], entries[1])
            })
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.pack-entry-duplicate", error.code)
        }
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-x64")).use { fixture ->
            val release = fixture.sign("trailing.zip")
            Files.write(release.pack, byteArrayOf(0x01, 0x02), StandardOpenOption.APPEND)
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.pack-trailing-data", error.code)
        }
    }

    @Test
    fun rejectsNonCanonicalMetadataAndSignatureLength() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-arm64")).use { fixture ->
            val release = fixture.sign("non-canonical.zip")
            rewritePack(release.pack, transform = { name, bytes ->
                if (name == KWEB_RUNTIME_RELEASE_METADATA_PATH) {
                    String(bytes, StandardCharsets.UTF_8)
                        .replace("}\n", "} \n")
                        .toByteArray(StandardCharsets.UTF_8)
                } else {
                    bytes
                }
            })
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.metadata-non-canonical", error.code)
        }
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("windows-x64")).use { fixture ->
            val release = fixture.sign("short-signature.zip")
            rewritePack(release.pack, transform = { name, bytes ->
                if (name == KWEB_RUNTIME_RELEASE_SIGNATURE_PATH) bytes.copyOf(bytes.size - 1) else bytes
            })
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.signature-size-invalid", error.code)
        }
    }

    @Test
    fun rejectsRelativePackKeyAndOutputPaths() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-x64")).use { fixture ->
            val release = fixture.sign()
            val packError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.verify(Path.of("relative-release.zip"))
            }
            assertEquals("runtime.release.pack-path-invalid", packError.code)

            val publicKeyError = assertFailsWith<KWebRuntimeReleaseException> {
                fixture.verify(release.pack, trustedPublicKey = Path.of("relative-public.der"))
            }
            assertEquals("runtime.release.public-key-path-invalid", publicKeyError.code)

            val outputError = assertFailsWith<KWebRuntimeReleaseException> {
                KWebRuntimeReleaseSigner.sign(
                    KWebRuntimeReleaseSignRequest(
                        payloadArchive = fixture.payload.archive,
                        catalog = fixture.catalog,
                        target = fixture.target,
                        productVersion = KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                        privateKey = fixture.privateKey,
                        publicKey = fixture.publicKey,
                        outputPack = Path.of("relative-output.zip"),
                    ),
                )
            }
            assertEquals("runtime.release.output-path-invalid", outputError.code)
        }
    }

    @Test
    fun rejectsResignedButInvalidNestedPayload() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-arm64")).use { fixture ->
            val release = fixture.sign("resigned-invalid-payload.zip")
            val originalEntries = ZipFile.builder()
                .setPath(release.pack)
                .setCharset(StandardCharsets.UTF_8)
                .get()
                .use { zip ->
                    zip.entriesInPhysicalOrder.asList().associate { entry ->
                        entry.name to zip.getInputStream(entry).use { it.readAllBytes() }
                    }
                }
            val corruptedPayload = originalEntries.getValue(KWEB_RUNTIME_RELEASE_PAYLOAD_PATH).copyOf().also {
                it[0] = (it[0].toInt() xor 0x01).toByte()
            }
            val originalManifest = KWebRuntimeReleaseManifestCodec.decode(
                originalEntries.getValue(KWEB_RUNTIME_RELEASE_METADATA_PATH),
            )
            val changedManifest = originalManifest.copy(
                payload = originalManifest.payload.copy(
                    size = corruptedPayload.size.toLong(),
                    sha256 = sha256(corruptedPayload),
                ),
            )
            val changedMetadata = KWebRuntimeReleaseManifestCodec.encode(changedManifest)
            val changedSignature = KWebRuntimeReleaseKeys.sign(
                KWebRuntimeReleaseKeys.loadPrivateKey(fixture.privateKey),
                changedMetadata,
            )
            val replacements = mapOf(
                KWEB_RUNTIME_RELEASE_METADATA_PATH to changedMetadata,
                KWEB_RUNTIME_RELEASE_PAYLOAD_PATH to corruptedPayload,
                KWEB_RUNTIME_RELEASE_SIGNATURE_PATH to changedSignature,
            )
            rewritePack(release.pack, transform = { name, _ -> replacements.getValue(name) })
            val error = assertFailsWith<KWebRuntimeReleaseException> { fixture.verify(release.pack) }
            assertEquals("runtime.release.payload-invalid", error.code)
            assertTrue(error.details.getValue("payloadCode").startsWith("runtime.payload.archive-"))
        }
    }

    @Test
    fun rejectsPayloadSourceMutationDuringVerifiedCopy() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("linux-x64")).use { fixture ->
            val expected = KWebRuntimeReleaseFileIO.digest(fixture.payload.archive)
            Files.write(fixture.payload.archive, byteArrayOf(0x01), StandardOpenOption.APPEND)
            val error = assertFailsWith<KWebRuntimeReleaseException> {
                KWebRuntimeReleaseFileIO.copyVerified(
                    fixture.payload.archive,
                    ByteArrayOutputStream(),
                    expected,
                )
            }
            assertEquals("runtime.release.payload-mutated", error.code)
        }
    }

    @Test
    fun cliBuildsAndVerifiesARelease() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-x64")).use { fixture ->
            val output = fixture.outputDirectory.resolve("cli-release.zip")
            main(
                arrayOf(
                    "release-build",
                    fixture.catalogPath.toString(),
                    fixture.target.id,
                    KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                    fixture.payload.archive.toString(),
                    fixture.privateKey.toString(),
                    fixture.publicKey.toString(),
                    output.toString(),
                ),
            )
            assertTrue(Files.isRegularFile(output))
            main(
                arrayOf(
                    "release-verify",
                    fixture.catalogPath.toString(),
                    fixture.target.id,
                    KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                    output.toString(),
                    fixture.publicKey.toString(),
                ),
            )
        }
    }

    private fun rewritePack(
        pack: Path,
        transform: (String, ByteArray) -> ByteArray = { _, bytes -> bytes },
        mutateEntry: (String, ZipArchiveEntry) -> Unit = { _, _ -> },
        reorder: (List<ZipArchiveEntry>) -> List<ZipArchiveEntry> = { it },
    ) {
        val temporary = pack.resolveSibling(".${pack.fileName}.rewrite")
        ZipFile.builder().setPath(pack).setCharset(StandardCharsets.UTF_8).get().use { input ->
            val entries = reorder(input.entriesInPhysicalOrder.asList())
            ZipArchiveOutputStream(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { output ->
                configureArchive(output)
                entries.forEach { original ->
                    val bytes = input.getInputStream(original).use { it.readAllBytes() }
                    val changed = transform(original.name, bytes)
                    val entry = ZipArchiveEntry(original.name)
                    entry.setTimeLocal(LocalDateTime.of(2000, 1, 1, 0, 0, 0))
                    entry.setUnixMode(original.unixMode)
                    entry.method = ZipArchiveOutputStream.STORED
                    entry.size = changed.size.toLong()
                    entry.crc = CRC32().apply { update(changed) }.value
                    mutateEntry(original.name, entry)
                    output.putArchiveEntry(entry)
                    output.write(changed)
                    output.closeArchiveEntry()
                }
                output.finish()
            }
        }
        Files.move(temporary, pack, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun configureArchive(output: ZipArchiveOutputStream) {
        output.setEncoding(StandardCharsets.UTF_8.name())
        output.setUseLanguageEncodingFlag(true)
        output.setFallbackToUTF8(false)
        output.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
        output.setUseZip64(Zip64Mode.Never)
    }

    private fun <T> Enumeration<T>.asList(): List<T> = buildList {
        while (this@asList.hasMoreElements()) add(this@asList.nextElement())
    }
}
