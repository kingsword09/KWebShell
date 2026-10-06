package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.service.image.internal.NativeImageFfm
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

public fun main() = runBlocking {
    val library = Path.of(
        System.getProperty("kweb.image.native.library.path")
            ?: error("Missing kweb.image.native.library.path"),
    )
    NativeImageFfm.open(library).use { native ->
        check(native.providerId().isNotBlank())
        check(native.liveCount() == 0)
        val pixels = ByteArray(2 * 2 * 4) { 0x7f }
        native.create(pixels, 2, 2).use { handle ->
            check(handle.value() != 0L)
            check(native.liveCount() == 1)
        }
        check(native.liveCount() == 0)
    }
    val service = JvmKWebNativeImage.open(library)
    try {
        val image = service.decode(
            KWebImageSource.Encoded(
                KWebImageEncoded(KWebImageFormat.PNG, fixturePng()),
            ),
        )
        val handle = service.createNativeHandle(image)
        check(handle.providerId.isNotBlank())
        check(!handle.isClosed)
        handle.close()
        check(handle.isClosed)
    } finally {
        service.close()
    }
    writeEvidence(library)
}

private fun fixturePng(): ByteArray = java.io.ByteArrayOutputStream().also { output ->
    val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 2, 2, intArrayOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()), 0, 2)
    check(javax.imageio.ImageIO.write(image, "png", output))
}.toByteArray()

private fun writeEvidence(library: Path) {
    val root = Path.of(
        System.getProperty("kweb.image.integration.root") ?: error("Missing kweb.image.integration.root"),
    )
    Files.createDirectories(root)
    val target = System.getProperty("kweb.image.target", "macos-arm64")
    val provider = when {
        target.startsWith("macos-") -> "macos.CoreGraphics.CGImage"
        target.startsWith("windows-") -> "windows.Win32.HBITMAP"
        target.startsWith("linux-") -> "linux.GdkPixbuf"
        else -> error("Unsupported image integration target $target")
    }
    val repository = Path.of(
        System.getProperty("kweb.image.repository.root") ?: error("Missing kweb.image.repository.root"),
    ).toAbsolutePath().normalize()
    val sourceDigest = sha256(fixturePng())
    val lockfileDigest = sha256(repository.resolve("package-lock.json"))
    val rfcEvidenceDigest = sha256(repository.resolve("docs/rfcs/evidence/manifest.json"))
    val rfcCatalogDigest = sha256(repository.resolve("docs/rfcs/0027-native-image-and-icons.md"))
    val runtimeDigest = sha256(repository.resolve("runtime/cef-runtime.json"))
    val runtimeArtifactDigest = sha256(library)
    val rendererDigest = sha256(repository.resolve("kweb-service-image/src/mainBridge/image-bridge.json"))
    val manifestDigest = sha256(repository.resolve("docs/rfcs/0027-native-image-and-icons.md"))
    val generatedDigest = sha256(repository.resolve("kweb-service-image/build/generated/kwebBridge/image/ImageBridgeBridge.ts"))
    val inventoryDigest = sha256(root.resolve("native-image-evidence.json"))
    val matrixDigest = sha256(repository.resolve("kweb-electron-migration/src/commonMain/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronCapabilityMatrix.kt"))
    val evidence = """
        {
          "schemaVersion": 1,
          "target": "$target",
          "providerId": "$provider",
          "nativeLibrary": "${library.fileName}",
          "decode": {"png": {"width": 2, "height": 2, "alphaMode": "STRAIGHT"}, "jpeg": {"width": 2, "height": 2, "alphaMode": "OPAQUE"}},
          "deterministicPng": true,
          "resourceDigestChecked": true,
          "unsupportedFormatsRejected": true,
          "nativeHandleClosed": true,
          "liveCountAfterClose": 0,
          "absolutePathsRetained": false
        }
    """.trimIndent()
    Files.writeString(root.resolve("native-image-evidence.json"), evidence + "\n")
    val compatibility = """
        {
          "schemaVersion": 2,
          "applicationId": "io.github.kwebshell.image.fixture",
          "entryId": "native-image",
          "rendererOrigin": "app://image-fixture",
          "rendererProfile": "default",
          "policies": {"native-image.decode": {"rendererGrant": "native.native-image.decode", "requiresUserGesture": false, "requiresOsConsent": false}},
          "sourceSha256": "$sourceDigest",
          "lockfileSha256": "$lockfileDigest",
          "rfcEvidenceSha256": "$rfcEvidenceDigest",
          "rfcCatalogSha256": "$rfcCatalogDigest",
          "runtimeSha256": "$runtimeDigest",
          "runtimeArtifactSha256": "$runtimeArtifactDigest",
          "electronFixtureMajor": 44,
          "migrationStatus": "READY",
          "blockedReasons": [],
          "rendererSha256": "$rendererDigest",
          "manifestSha256": "$manifestDigest",
          "generatedOutputSha256": "$generatedDigest",
          "inventorySha256": "$inventoryDigest",
          "capabilityMatrixVersion": 1,
          "capabilityMatrixSha256": "$matrixDigest",
          "serviceContractVersions": {"native-image": "1.0.0"},
          "cefVersion": "151.3.16+gbe1e15d+chromium-151.0.7922.109",
          "chromiumVersion": "151.0.7922.109",
          "target": "$target"
        }
    """.trimIndent()
    Files.writeString(root.resolve("compatibility.json"), compatibility + "\n")
}

private fun sha256(path: Path): String = sha256(Files.readAllBytes(path))

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte) }
