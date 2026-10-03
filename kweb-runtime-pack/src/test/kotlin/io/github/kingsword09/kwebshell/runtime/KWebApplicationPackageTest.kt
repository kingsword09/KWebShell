package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
                    applicationAssetRoot = repositoryRoot().resolve("runtime"),
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
                    "windows" -> "zip"
                    else -> "deb"
                }
                val packagePath = fixture.outputDirectory.resolve("KWebShell-$targetId.$extension")
                KWebApplicationPackageAssembler.build(
                    request(
                        fixture,
                        manifestPath,
                        release.pack,
                        testSignature(fixture.target),
                        packagePath,
                    ),
                )
                if (fixture.target.operatingSystem.id == "linux") {
                    kotlin.test.assertTrue(Files.size(packagePath) > 0L)
                } else {
                    ZipFile.builder().setPath(packagePath).get().use { zip ->
                        val names = zip.entries.asSequence().map { it.name }.toSet()
                        when (fixture.target.operatingSystem.id) {
                            "macos" -> assertEquals(true, "platform/macos/Info.plist" in names)
                            "windows" -> {
                                assertEquals(true, "AppxManifest.xml" in names)
                                assertEquals(true, "Assets/Square44x44Logo.png" in names)
                                assertEquals(true, "Assets/Square150x150Logo.png" in names)
                                assertEquals(true, "Assets/Square310x310Logo.png" in names)
                                assertEquals(true, "Assets/StoreLogo.png" in names)
                                val manifestBytes = zip.getInputStream(zip.getEntry("AppxManifest.xml")).use { it.readBytes() }
                                val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                                    .newDocumentBuilder()
                                val xml = parser.parse(ByteArrayInputStream(manifestBytes))
                                val sourceManifest = KWebApplicationManifestLoader.load(manifestPath)
                                assertEquals("Package", xml.documentElement.localName)
                                assertEquals(
                                    "10.0.17763.0",
                                    xml.getElementsByTagNameNS(
                                        "http://schemas.microsoft.com/appx/manifest/foundation/windows10",
                                        "TargetDeviceFamily",
                                    ).item(0).attributes.getNamedItem("MinVersion").nodeValue,
                                )
                                assertEquals(1, xml.getElementsByTagNameNS(
                                    "http://schemas.microsoft.com/appx/manifest/foundation/windows10",
                                    "Identity",
                                ).length)
                                assertEquals(1, xml.getElementsByTagNameNS(
                                    "http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities",
                                    "Capability",
                                ).length)
                                assertEquals(
                                    sourceManifest.protocols.size + sourceManifest.fileTypes.size,
                                    xml.getElementsByTagNameNS(
                                        "http://schemas.microsoft.com/appx/manifest/uap/windows10",
                                        "Extension",
                                    ).length,
                                )
                                val manifestText = manifestBytes.toString(Charsets.UTF_8)
                                assertTrue(!manifestText.contains("WindowsAppRuntime"))
                                assertTrue(!manifestText.contains("AppNotification"))
                                assertTrue(manifestText.contains("<uap:Protocol Name=\"kweb\">"))
                                assertTrue(manifestText.contains("<uap:FileType>.kweb</uap:FileType>"))
                                sourceManifest.icons.forEach { icon ->
                                    val packagePath = when (icon.kind) {
                                        "windows-msix-square44" -> "Assets/Square44x44Logo.png"
                                        "windows-msix-square150" -> "Assets/Square150x150Logo.png"
                                        "windows-msix-square310" -> "Assets/Square310x310Logo.png"
                                        "windows-msix-store" -> "Assets/StoreLogo.png"
                                        else -> error("Unexpected Windows icon kind: ${icon.kind}")
                                    }
                                    val packaged = zip.getInputStream(zip.getEntry(packagePath)).use { it.readBytes() }
                                    assertContentEquals(Files.readAllBytes(repositoryRoot().resolve("runtime").resolve(icon.path)), packaged)
                                }
                                val rejected = assertFailsWith<KWebApplicationPackageException> {
                                    KWebApplicationPackageVerifier.verify(
                                        KWebApplicationPackageVerificationRequest(
                                            applicationPackage = packagePath,
                                            applicationManifest = manifestPath,
                                            applicationAssetRoot = repositoryRoot().resolve("runtime"),
                                            catalog = fixture.catalog,
                                            target = fixture.target,
                                            productVersion = KWebRuntimeReleaseTestFixture.PRODUCT_VERSION,
                                            trustedPublicKey = fixture.publicKey,
                                        ),
                                    )
                                }
                                assertEquals("application.package.windows-msix-sdk-verification-required", rejected.code)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun windowsMsixMetadataRejectsArm64AndMalformedAssets() {
        val root = repositoryRoot()
        val manifest = KWebApplicationManifestLoader.load(root.resolve("runtime/application-manifest.json"))
        val arm64 = assertFailsWith<KWebApplicationPackageException> {
            windowsMsixMetadataEntries(manifest, KWebTarget.parse("windows-arm64"), root.resolve("runtime"))
        }
        assertEquals("application.package.windows-target-unsupported", arm64.code)

        val temporaryAssets = Files.createTempDirectory("kweb-windows-msix-assets-")
        try {
            manifest.icons.forEach { icon ->
                val source = root.resolve("runtime").resolve(icon.path)
                val destination = temporaryAssets.resolve(icon.path)
                Files.createDirectories(destination.parent)
                Files.copy(source, destination)
            }
            val wrongDimension = temporaryAssets.resolve("assets/windows/Square44x44Logo.png")
            Files.copy(
                temporaryAssets.resolve("assets/windows/Square150x150Logo.png"),
                wrongDimension,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            val invalid = assertFailsWith<KWebApplicationPackageException> {
                windowsMsixMetadataEntries(manifest, KWebTarget.parse("windows-x64"), temporaryAssets)
            }
            assertEquals("application.package.windows-icon-invalid", invalid.code)

            Files.copy(
                root.resolve("runtime/assets/windows/Square44x44Logo.png"),
                wrongDimension,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
            val malformedPng = temporaryAssets.resolve("assets/windows/StoreLogo.png")
            Files.write(malformedPng, byteArrayOf(1, 2, 3, 4))
            val malformed = assertFailsWith<KWebApplicationPackageException> {
                windowsMsixMetadataEntries(manifest, KWebTarget.parse("windows-x64"), temporaryAssets)
            }
            assertEquals("application.package.windows-icon-invalid", malformed.code)
        } finally {
            deleteTree(temporaryAssets)
        }
    }

    @Test
    fun windowsMetadataArchiveCannotBePublishedWithMsixSuffix() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("windows-x64")).use { fixture ->
            val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
            val release = fixture.sign()
            val output = fixture.outputDirectory.resolve("KWebShell-not-a-package.msix")
            val error = assertFailsWith<KWebApplicationPackageException> {
                KWebApplicationPackageAssembler.build(
                    request(fixture, manifestPath, release.pack, testSignature(fixture.target), output),
                )
            }
            assertEquals("application.package.extension-invalid", error.code)
            assertTrue(error.message.orEmpty().contains("Windows SDK"))
            assertEquals(false, Files.exists(output))
        }
    }

    @Test
    fun windowsArm64PackageRequestFailsBeforeRuntimeInspection() {
        KWebRuntimeReleaseTestFixture.create(KWebTarget.parse("windows-arm64")).use { fixture ->
            val manifestPath = writeManifest(fixture.root, KWebRuntimeReleaseTestFixture.PRODUCT_VERSION)
            val output = fixture.outputDirectory.resolve("KWebShell-windows-arm64.zip")
            val error = assertFailsWith<KWebApplicationPackageException> {
                KWebApplicationPackageAssembler.build(
                    request(
                        fixture,
                        manifestPath,
                        fixture.root.resolve("must-not-be-opened.zip"),
                        testSignature(fixture.target),
                        output,
                    ),
                )
            }
            assertEquals("application.package.windows-target-unsupported", error.code)
            assertEquals(false, Files.exists(output))
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
                        applicationAssetRoot = repositoryRoot().resolve("runtime"),
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
        applicationAssetRoot = repositoryRoot().resolve("runtime"),
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
        KWebApplicationManifestLoader.load(repositoryRoot().resolve("runtime/application-manifest.json")).let { manifest ->
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
            signer = if (target.operatingSystem.id == "windows") "CN=${manifest.publisher}" else "test-ed25519",
            signatureStatus = "VERIFIED",
            notarizationStatus = "NOT_APPLICABLE",
            registrationDigest = applicationRegistrationDigest(manifest, target),
            )
        }

    private fun repositoryRoot(): Path {
        var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            if (Files.isRegularFile(current.resolve("runtime/application-manifest.json"))) return current
            current = current.parent
        }
        error("Unable to locate runtime/application-manifest.json.")
    }

    private fun deleteTree(root: Path) {
        Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                Files.delete(file)
                return java.nio.file.FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: Path,
                error: java.io.IOException?,
            ): java.nio.file.FileVisitResult {
                if (error != null) throw error
                Files.delete(directory)
                return java.nio.file.FileVisitResult.CONTINUE
            }
        })
    }
}
