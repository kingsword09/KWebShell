package io.github.kingsword09.kwebshell.probe

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.desktop.internal.NATIVE_LIBRARY_PATH_PROPERTY
import io.github.kingsword09.kwebshell.desktop.internal.NativeBindings
import io.github.kingsword09.kwebshell.desktop.internal.NativeStatus
import io.github.kingsword09.kwebshell.desktop.internal.nativeEngineLibraryFileName
import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmBindings
import java.nio.file.Files

internal fun main(arguments: Array<String>) {
    require(arguments.size == 1)
    val directory = Files.createTempDirectory("kwebshell-native-access-")
    try {
        val engine = directory.resolve(nativeEngineLibraryFileName(System.getProperty("os.name")))
        val runtime = directory.resolve("invalid-cef-runtime")
        Files.createFile(engine)
        Files.createFile(runtime)
        System.setProperty(NATIVE_LIBRARY_PATH_PROPERTY, engine.toString())
        when (arguments.single()) {
            "disabled" -> {
                val error = try {
                    NativeBindings.loadEngineLibrary(engine.toString(), runtime.toString())
                    null
                } catch (failure: KWebConfigurationException) {
                    failure
                }
                require(error?.code == "native.ffm.native-access-disabled") {
                    "Expected the typed FFM native-access diagnostic, got $error"
                }
                require(error.details["grant"] == "io.github.kingsword09.kwebshell.desktop")
                println("KWEBSHELL_NATIVE_ACCESS_TYPED_DIAGNOSTIC_OK")
            }
            "enabled" -> {
                val status = NativeBindings.loadEngineLibrary(engine.toString(), runtime.toString())
                require(status == NativeStatus.ENGINE_LIBRARY_LOAD_FAILED.value ||
                    status == NativeStatus.CEF_RUNTIME_LOAD_FAILED.value
                ) {
                    "The granted named module returned unexpected fake-library status $status."
                }
                require(FfmBindings.nativeAccessGrantTarget() == "io.github.kingsword09.kwebshell.desktop")
                println("KWEBSHELL_NAMED_NATIVE_ACCESS_OK")
            }
            else -> error("Unknown native-access probe mode '${arguments.single()}'.")
        }
    } finally {
        directory.toFile().deleteRecursively()
    }
}
