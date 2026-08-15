package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.nio.file.Path
import kotlin.system.exitProcess

public fun main(arguments: Array<String>) {
    try {
        when (arguments.firstOrNull()) {
            "manifest" -> {
                require(arguments.size == 2) { "Expected: manifest <manifest-path>" }
                val catalog = CefSourcePatchManifestLoader.load(Path.of(arguments[1]))
                CefSourcePatchVerifier.verifyPatchFiles(catalog)
            }
            "source" -> {
                require(arguments.size == 3) { "Expected: source <manifest-path> <cef-source-root>" }
                val catalog = CefSourcePatchManifestLoader.load(Path.of(arguments[1]))
                CefSourcePatchVerifier.verifySourceTree(catalog, Path.of(arguments[2]))
            }
            "artifact" -> {
                require(arguments.size == 4) {
                    "Expected: artifact <manifest-path> <target> <archive-path>"
                }
                val catalog = CefSourcePatchManifestLoader.load(Path.of(arguments[1]))
                CefCustomRuntimeArtifactVerifier.verify(
                    path = Path.of(arguments[3]),
                    catalog = catalog,
                    target = KWebTarget.parse(arguments[2]),
                )
            }
            "publication" -> {
                require(arguments.size == 2) { "Expected: publication <manifest-path>" }
                val catalog = CefSourcePatchManifestLoader.load(Path.of(arguments[1]))
                catalog.requirePackageLifecyclePublicationReady()
            }
            else -> error("Expected command 'manifest', 'source', 'artifact', or 'publication'.")
        }
    } catch (error: Exception) {
        if (error is KWebException) {
            System.err.println("${error.code}: ${error.message} ${error.details}")
        } else {
            System.err.println(error.message)
        }
        exitProcess(2)
    }
}
