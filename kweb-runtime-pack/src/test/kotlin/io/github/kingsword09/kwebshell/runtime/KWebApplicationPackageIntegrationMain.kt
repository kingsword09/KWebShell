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
        val payload = requiredPath("kweb.application.package.payload")
        val outputDirectory = requiredPath("kweb.application.package.output-directory")
        Files.createDirectories(outputDirectory)

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
        val result = KWebApplicationPackageAssembler.build(
            KWebApplicationPackageBuildRequest(
                applicationManifest = manifestPath,
                runtimeRelease = release.pack,
                catalog = catalog,
                target = target,
                productVersion = productVersion,
                trustedPublicKey = publicKey,
                packageSigningPrivateKey = privateKey,
                platformSignature = KWebApplicationPlatformSignature(
                    schemaVersion = 1,
                    target = target.id,
                    format = format,
                    mode = KWebApplicationSigningMode.TEST,
                    identity = identity,
                    signer = "hosted-test-ed25519",
                    signatureStatus = "VERIFIED",
                    notarizationStatus = "NOT_APPLICABLE",
                    registrationDigest = "0".repeat(64),
                ),
                outputPackage = packagePath,
            ),
        )
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
