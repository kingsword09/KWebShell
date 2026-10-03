package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPairGenerator

internal fun main() {
    val root = Files.createTempDirectory("kweb-application-package-integration-")
    try {
        val catalog = CefRuntimeCatalogLoader.load(requiredPath("kweb.application.package.catalog"))
        val target = KWebTarget.parse(requiredText("kweb.application.package.target"))
        val productVersion = requiredText("kweb.application.package.version")
        val manifestPath = requiredPath("kweb.application.package.manifest")
        val applicationManifest = KWebApplicationManifestLoader.load(manifestPath)
        val payload = requiredPath("kweb.application.package.payload")
        val outputDirectory = requiredPath("kweb.application.package.output-directory")
        Files.createDirectories(outputDirectory)
        val isWindows = target.operatingSystem.id == "windows"

        val keyPair = KeyPairGenerator.getInstance(KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM).generateKeyPair()
        val privateKey = root.resolve("package-private.pk8")
        val publicKey = root.resolve("package-public.der")
        Files.write(privateKey, keyPair.private.encoded)
        Files.write(publicKey, keyPair.public.encoded)
        val release = KWebRuntimeReleaseSigner.sign(
            KWebRuntimeReleaseSignRequest(
                payloadArchive = payload,
                catalog = catalog,
                target = target,
                productVersion = productVersion,
                privateKey = privateKey,
                publicKey = publicKey,
                outputPack = root.resolve("runtime-release.zip"),
            ),
        )
        val format = when (target.operatingSystem.id) {
            "macos" -> KWebApplicationPackageFormat.MACOS_APP_ZIP
            "windows" -> KWebApplicationPackageFormat.WINDOWS_MSIX
            else -> KWebApplicationPackageFormat.LINUX_DEB
        }
        val identity = if (target.operatingSystem.id == "linux") {
            "io.github.kingsword09.kwebshell.desktop"
        } else {
            "io.github.kingsword09.kwebshell"
        }
        val extension = when (format) {
            KWebApplicationPackageFormat.MACOS_APP_ZIP -> "zip"
            KWebApplicationPackageFormat.WINDOWS_MSIX -> "msix"
            KWebApplicationPackageFormat.LINUX_DEB -> "deb"
        }
        val packagePath = outputDirectory.resolve("KWebShell-$productVersion-${target.id}.$extension")
        val metadataArchive = if (isWindows) root.resolve("windows-package-records.zip") else packagePath
        val platformSignature = KWebApplicationPlatformSignature(
            schemaVersion = 1,
            target = target.id,
            format = format,
            mode = KWebApplicationSigningMode.TEST,
            identity = identity,
            signer = if (isWindows) "CN=${applicationManifest.publisher}" else "hosted-test-ed25519",
            signatureStatus = "VERIFIED",
            notarizationStatus = "NOT_APPLICABLE",
            registrationDigest = applicationRegistrationDigest(applicationManifest, target),
        )
        val result = KWebApplicationPackageAssembler.build(
            KWebApplicationPackageBuildRequest(
                applicationManifest = manifestPath,
                applicationAssetRoot = requiredPath("kweb.application.package.asset-root"),
                runtimeRelease = release.pack,
                catalog = catalog,
                target = target,
                productVersion = productVersion,
                trustedPublicKey = publicKey,
                packageSigningPrivateKey = privateKey,
                platformSignature = platformSignature,
                outputPackage = metadataArchive,
            ),
        )
        val verificationRequest = KWebApplicationPackageVerificationRequest(
                applicationPackage = metadataArchive,
                applicationManifest = manifestPath,
                applicationAssetRoot = requiredPath("kweb.application.package.asset-root"),
                catalog = catalog,
                target = target,
                productVersion = productVersion,
                trustedPublicKey = publicKey,
            )
        val verified = if (isWindows) {
            KWebApplicationPackageVerifier.verifyWindowsMetadataArchive(verificationRequest)
        } else {
            KWebApplicationPackageVerifier.verify(verificationRequest)
        }
        check(verified.packageSha256 == result.packageSha256) {
            "The independently verified package digest does not match the build result."
        }
        check(verified.manifest.productVersion == productVersion) {
            "The independently verified package manifest version does not match the build request."
        }
        check(verified.platformSignature == platformSignature) {
            "The independently verified platform signature facts do not match the build request."
        }
        check(verified.runtimeReleaseSha256 == result.runtimeReleaseSha256) {
            "The independently verified nested runtime release digest does not match the build result."
        }
        if (isWindows) {
            val applicationImage = requiredPath("kweb.application.package.app-image")
            val metadataVerification = root.resolve("metadata-verification.json")
            val signedEntryCount = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                .setPath(metadataArchive)
                .get()
                .use { zip ->
                    val statementBytes = zip.getInputStream(zip.getEntry("signatures/package.json")).use { it.readBytes() }
                    kotlinx.serialization.json.Json.decodeFromString(
                        KWebApplicationPackageSignatureStatement.serializer(),
                        statementBytes.toString(Charsets.UTF_8),
                    ).entrySha256.size
                }
            Files.writeString(
                metadataVerification,
                """
                {
                  "status": "PASS",
                  "packageSignatureVerification": "PASS",
                  "metadataArchiveSha256": "${sha256(metadataArchive)}",
                  "signedEntryCount": $signedEntryCount
                }
                """.trimIndent() + "\n",
            )
            val script = repositoryRoot().resolve(".github/scripts/build-and-verify-windows-msix.ps1")
            val process = ProcessBuilder(
                "powershell.exe",
                "-NoLogo",
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.toString(),
                "-MetadataArchive",
                metadataArchive.toString(),
                "-MetadataVerification",
                metadataVerification.toString(),
                "-ApplicationImage",
                applicationImage.toString(),
                "-OutputPackage",
                packagePath.toString(),
                "-ReportPath",
                outputDirectory.resolve("application-package-report.json").toString(),
                "-SourceRevision",
                System.getenv("GITHUB_SHA").orEmpty(),
            ).inheritIO().start()
            check(process.waitFor() == 0) { "The Windows SDK MSIX build/install/launch/uninstall verification failed." }
            check(Files.isRegularFile(packagePath)) { "The Windows SDK did not produce a signed MSIX: $packagePath" }
            println("KWebShell Windows MSIX integration passed for ${target.id}: ${sha256(packagePath)}")
            return
        }
        val reportPath = outputDirectory.resolve("application-package-report.json")
        Files.writeString(
            reportPath,
            buildString {
                appendLine("{")
                appendLine("  \"schemaVersion\": 1,")
                appendLine("  \"target\": \"${target.id}\",")
                appendLine("  \"format\": \"${format.name}\",")
                appendLine("  \"packageSha256\": \"${result.packageSha256}\",")
                appendLine("  \"manifestSha256\": \"${result.manifestSha256}\",")
                appendLine("  \"runtimeReleaseSha256\": \"${result.runtimeReleaseSha256}\",")
                appendLine("  \"signingMode\": \"TEST\",")
                appendLine("  \"status\": \"PASS\"")
                appendLine("}")
            },
        )
        println("KWebShell application package integration passed for ${target.id}: ${result.packageSha256}")
    } finally {
        deleteTree(root)
    }
}

private fun requiredPath(property: String): Path =
    Path.of(requiredText(property)).toAbsolutePath().normalize()

private fun requiredText(property: String): String =
    System.getProperty(property)?.takeIf(String::isNotBlank)
        ?: error("Missing system property $property")

private fun repositoryRoot(): Path {
    var current: Path? = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
    while (current != null) {
        if (Files.isRegularFile(current.resolve("runtime/application-manifest.json"))) return current
        current = current.parent
    }
    error("Unable to locate runtime/application-manifest.json.")
}

private fun deleteTree(root: Path) {
    if (!Files.exists(root)) return
    Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: BasicFileAttributes): java.nio.file.FileVisitResult {
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
