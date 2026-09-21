package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.ZipFile
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebApplicationPackageTest {
    @Test
    fun packageRoundTripIsDeterministicAndVerifiesNestedRelease() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-arm64")).use { fixture ->
            val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
            val release = fixture.sign()
            val signature = testSignature(KWebTarget.parse("macos-arm64"))
            val first = fixture.outputDirectory.resolve("KWebShell-first.zip")
            val second = fixture.outputDirectory.resolve("KWebShell-second.zip")
            val request = request(fixture, manifestPath, release.pack, signature, first)

            val firstResult = KWebApplicationPackageAssembler.build(request)
            val secondResult = KWebApplicationPackageAssembler.build(request.copy(outputPackage = second))
            assertContentEquals(Files.readAllBytes(first), Files.readAllBytes(second))
            assertEquals(firstResult.packageSha256, secondResult.packageSha256)
            assertEquals(firstResult.manifestSha256, secondResult.manifestSha256)

            val verified = KWebApplicationPackageVerifier.verify(
                KWebApplicationPackageVerificationRequest(
                    applicationPackage = first,
                    applicationManifest = manifestPath,
                    catalog = fixture.catalog,
                    target = fixture.target,
                    productVersion = KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                    trustedPublicKey = fixture.publicKey,
                ),
            )
            assertEquals(KWebRuntimeReleaseTestFixture.PRODUCT_VERSION, verified.manifest.productVersion)
            assertEquals(signature, verified.platformSignature)
            assertEquals(firstResult.runtimeReleaseSha256, verified.runtimeReleaseSha256)
        }
    }

    @Test
    fun wrongPlatformIdentityFailsBeforeWritingOutput() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-arm64")).use { fixture ->
            val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
            val release = fixture.sign()
            val output = fixture.outputDirectory.resolve("KWebShell-invalid.zip")
            val invalid = testSignature(fixture.target).copy(identity = "io.github.kwebshell.other")
            val error = assertFailsWith<KWebApplicationPackageException> {
                KWebApplicationPackageAssembler.build(
                    request(fixture, manifestPath, release.pack, invalid, output),
                )
            }
            assertEquals("application.package.platform-signature-invalid", error.code)
            kotlin.test.assertFalse(Files.exists(output))
        }
    }

    @Test
    fun targetProvidersIncludeTheirRegistrationArtifacts() {
        listOf("macos-arm64", "windows-x64", "linux-x64").forEach { targetId ->
            KWebRuntimeReleaseTestFixture.create(KWebTarget.parse(targetId)).use { fixture ->
                val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
                val release = fixture.sign()
                val extension = when (fixture.target.operatingSystem.id) {
                    "macos" -> "zip"
                    "windows" -> "msix"
                    else -> "deb"
                }
                val packagePath = fixture.outputDirectory.resolve("KWebShell-$targetId.$extension")
                KWebApplicationPackageAssembler.build(
                    request(fixture, manifestPath, release.pack, testSignature(fixture.target), packagePath),
                )
                if (fixture.target.operatingSystem.id == "linux") {
                    kotlin.test.assertTrue(Files.size(packagePath) > 0L)
                } else {
                    ZipFile.builder().setPath(packagePath).get().use { zip ->
                        val names = zip.entries.asSequence().map { it.name }.toSet()
                        when (fixture.target.operatingSystem.id) {
                            "macos" -> assertEquals(true, "platform/macos/Info.plist" in names)
                            "windows" -> assertEquals(true, "platform/windows/AppxManifest.xml" in names)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun tamperedPackageCannotPassVerification() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("macos-arm64")).use { fixture ->
            val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
            val release = fixture.sign()
            val packagePath = fixture.outputDirectory.resolve("KWebShell-tampered.zip")
            KWebApplicationPackageAssembler.build(
                request(fixture, manifestPath, release.pack, testSignature(fixture.target), packagePath),
            )
            val bytes = Files.readAllBytes(packagePath)
            val needle = "KWebShell".toByteArray()
            val index = (0..bytes.size - needle.size).first { start ->
                needle.indices.all { offset -> bytes[start + offset] == needle[offset] }
            }
            bytes[index] = (bytes[index].toInt() xor 0x01).toByte()
            Files.write(packagePath, bytes)

            assertFailsWith<Throwable> {
                KWebApplicationPackageVerifier.verify(
                    KWebApplicationPackageVerificationRequest(
                        applicationPackage = packagePath,
                        applicationManifest = manifestPath,
                        catalog = fixture.catalog,
                        target = fixture.target,
                        productVersion = KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                        trustedPublicKey = fixture.publicKey,
                    ),
                )
            }
        }
    }

    private fun request(
        fixture: KWebRuntimeReleaseTestFixture,
        manifestPath: Path,
        release: Path,
        signature: KWebApplicationPlatformSignature,
        output: Path,
    ): KWebApplicationPackageBuildRequest = KWebApplicationPackageBuildRequest(
        applicationManifest = manifestPath,
        runtimeRelease = release,
        catalog = fixture.catalog,
        target = fixture.target,
        productVersion = KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
        trustedPublicKey = fixture.publicKey,
        packageSigningPrivateKey = fixture.privateKey,
        platformSignature = signature,
        outputPackage = output,
    )

    private fun writeManifest(root: Path, productVersion: String): Path {
        val source = KWebApplicationManifestLoader.load(repositoryRoot().resolve("runtime/application-manifest.json"))
        val path = root.resolve("application-manifest.json")
        Files.write(path, KWebApplicationManifestCodec.encode(source.copy(productVersion = productVersion)))
        return path
    }

    private fun testSignature(target: KWebTarget): KWebApplicationPlatformSignature =
        KWebApplicationPlatformSignature(
            schemaVersion = 1,
            target = target.id,
            format = when (target.operatingSystem.id) {
                "macos" -> KWebApplicationPackageFormat.MACOS_APP_ZIP
                "windows" -> KWebApplicationPackageFormat.WINDOWS_MSIX
                else -> KWebApplicationPackageFormat.LINUX_DEB
            },
            mode = KWebApplicationSigningMode.TEST,
            identity = if (target.operatingSystem.id == "linux") {
                "io.github.kingsword09.kwebshell.desktop"
            } else {
                "io.github.kingsword09.kwebshell"
            },
            signer = "test-ed25519",
            signatureStatus = "VERIFIED",
            notarizationStatus = "NOT_APPLICABLE",
            registrationDigest = "0".repeat(64),
        )

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("runtime/application-manifest.json"))) return current
            current = current.parent
        }
        error("Unable to locate runtime/application-manifest.json.")
    }
}
