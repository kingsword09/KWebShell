package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Path

internal fun main(arguments: Array<String>) {
    when (arguments.firstOrNull()) {
        "manifest" -> verifyManifest(arguments)
        "artifact" -> verifyArtifact(arguments)
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
                "artifact <manifest-path> <target> <archive-path>.",
    )
}
