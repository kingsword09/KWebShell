package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Path

internal fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "manifest" -> verifyManifest(arguments)
        "artifact" -> verifyArtifact(arguments)
        "payload-build" -> buildPayload(arguments)
        "payload-verify" -> verifyPayload(arguments)
        "release-build" -> buildRelease(arguments)
        "release-verify" -> verifyRelease(arguments)
        else -> invalidArguments(arguments)
    }
}

private fun verifyManifest(arguments: Array<String>) {
    requireArgumentCount(arguments, 2)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    println(
        "Verified CEF ${catalog.manifest.cefVersion} for " +
            catalog.supportedTargets.sortedBy { it.id }.joinToString { it.id },
    )
}

private fun verifyArtifact(arguments: Array<String>) {
    requireArgumentCount(arguments, 4)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    val target = KWebTarget.parse(arguments[2])
    val artifactPath = Path.of(arguments[3])
    CefRuntimeArtifactVerifier.verify(artifactPath, catalog.artifact(target))
    println("Verified ${artifactPath.toAbsolutePath()} for ${target.id}.")
}

private fun buildPayload(arguments: Array<String>) {
    requireArgumentCount(arguments, 8)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    val target = KWebTarget.parse(arguments[2])
    val result = KWebRuntimePayloadAssembler.build(
        KWebRuntimePayloadBuildRequest(
            catalog = catalog,
            target = target,
            productVersion = arguments[3],
            cefRoot = Path.of(arguments[4]),
            nativeReleaseDirectory = Path.of(arguments[5]),
            nativeContractDirectory = Path.of(arguments[6]),
            outputArchive = Path.of(arguments[7]),
        ),
    )
    println(
        "Built ${result.archive.toAbsolutePath()} for ${target.id}; " +
            "archive SHA-256 ${result.archiveSha256}.",
    )
}

private fun verifyPayload(arguments: Array<String>) {
    requireArgumentCount(arguments, 5)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    val target = KWebTarget.parse(arguments[2])
    val archive = Path.of(arguments[4])
    val manifest = KWebRuntimePayloadVerifier.verify(
        KWebRuntimePayloadVerificationRequest(
            archive = archive,
            catalog = catalog,
            target = target,
            productVersion = arguments[3],
        ),
    )
    println(
        "Verified ${archive.toAbsolutePath()} for ${target.id}; " +
            "${manifest.entries.size} payload entries.",
    )
}

private fun buildRelease(arguments: Array<String>) {
    requireArgumentCount(arguments, 8)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    val target = KWebTarget.parse(arguments[2])
    val result = KWebRuntimeReleaseSigner.sign(
        KWebRuntimeReleaseSignRequest(
            payloadArchive = Path.of(arguments[4]),
            catalog = catalog,
            target = target,
            productVersion = arguments[3],
            privateKey = Path.of(arguments[5]),
            publicKey = Path.of(arguments[6]),
            outputPack = Path.of(arguments[7]),
        ),
    )
    println(
        "Built ${result.pack.toAbsolutePath()} for ${target.id}; " +
            "key ${result.manifest.keyId}; pack SHA-256 ${result.packSha256}.",
    )
}

private fun verifyRelease(arguments: Array<String>) {
    requireArgumentCount(arguments, 6)
    val catalog = CefRuntimeCatalogLoader.load(Path.of(arguments[1]))
    val target = KWebTarget.parse(arguments[2])
    val result = KWebRuntimeReleaseVerifier.verify(
        KWebRuntimeReleaseVerificationRequest(
            pack = Path.of(arguments[4]),
            catalog = catalog,
            target = target,
            productVersion = arguments[3],
            trustedPublicKey = Path.of(arguments[5]),
        ),
    )
    println(
        "Verified ${arguments[4]} for ${target.id}; key ${result.manifest.keyId}; " +
            "pack SHA-256 ${result.packSha256}.",
    )
}

private fun requireArgumentCount(arguments: Array<String>, expected: Int) {
    if (arguments.size != expected) {
        invalidArguments(arguments)
    }
}

private fun invalidArguments(arguments: Array<String>): Nothing {
    throw KWebConfigurationException(
        code = "runtime.cli.invalid-arguments",
        details = mapOf("arguments" to arguments.joinToString(separator = " ")),
        message =
            "Usage: manifest <manifest-path> or " +
                "artifact <manifest-path> <target> <archive-path> or " +
                "payload-build <manifest-path> <target> <product-version> " +
                "<cef-root> <native-release> <native-contract> <output-archive> or " +
                "payload-verify <manifest-path> <target> <product-version> <archive-path> or " +
                "release-build <manifest-path> <target> <product-version> <payload-archive> " +
                "<private-key-pkcs8-der> <public-key-x509-der> <output-pack> or " +
                "release-verify <manifest-path> <target> <product-version> <pack> " +
                "<trusted-public-key-x509-der>.",
    )
}
