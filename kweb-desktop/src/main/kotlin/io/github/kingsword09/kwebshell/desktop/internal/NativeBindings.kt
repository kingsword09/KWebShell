package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import java.awt.Component
import kotlin.jvm.JvmName

internal const val NATIVE_LIBRARY_PATH_PROPERTY: String = "kweb.native.library.path"

internal data class NativeLibraryPaths(
    val engine: Path,
    val jni: Path,
)

internal fun nativeEngineLibraryFileName(operatingSystem: String): String =
    when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_engine.so"
        else -> throw KWebConfigurationException(
            code = "native.platform.unsupported",
            details = mapOf("osName" to operatingSystem),
            message = "The current operating system is not supported by the KWebShell native engine.",
        )
    }

internal fun resolveNativeLibraryPaths(
    configuredJniPath: String,
    operatingSystem: String,
): NativeLibraryPaths {
    val engineFileName = nativeEngineLibraryFileName(operatingSystem)
    val jniPath = try {
        Path.of(configuredJniPath)
    } catch (error: InvalidPathException) {
        throw KWebConfigurationException(
            code = "native.library.path-invalid",
            details = mapOf("path" to configuredJniPath),
            message = "The KWebShell JNI library path is invalid.",
            cause = error,
        )
    }
    if (!jniPath.isAbsolute || !Files.isRegularFile(jniPath)) {
        throw KWebConfigurationException(
            code = "native.library.path-invalid",
            details = mapOf("path" to configuredJniPath),
            message = "The KWebShell JNI library path must identify a regular file.",
        )
    }
    val enginePath = jniPath.resolveSibling(engineFileName)
    if (!Files.isRegularFile(enginePath)) {
        throw KWebConfigurationException(
            code = "native.engine-library.path-invalid",
            details = mapOf("path" to enginePath.toString()),
            message = "The KWebShell engine library must be adjacent to the JNI library.",
        )
    }
    return NativeLibraryPaths(engine = enginePath, jni = jniPath)
}

internal object NativeBindings {
    internal val libraryPaths: NativeLibraryPaths

    init {
        val configuredPath = System.getProperty(NATIVE_LIBRARY_PATH_PROPERTY)
            ?: throw KWebConfigurationException(
                code = "native.library.path-missing",
                details = mapOf("property" to NATIVE_LIBRARY_PATH_PROPERTY),
                message = "The absolute KWebShell JNI library path is required.",
            )
        val paths = resolveNativeLibraryPaths(configuredPath, System.getProperty("os.name"))
        libraryPaths = paths
        loadNativeLibrary(
            path = paths.jni,
            errorCode = "native.library.load-failed",
            errorMessage = "The KWebShell JNI library could not be loaded.",
        )
    }

    private fun loadNativeLibrary(path: Path, errorCode: String, errorMessage: String) {
        try {
            System.load(path.toString())
        } catch (error: UnsatisfiedLinkError) {
            throw KWebNativeException(
                code = errorCode,
                details = mapOf("path" to path.toString()),
                message = errorMessage,
                cause = error,
            )
        }
    }

    @JvmName("loadEngineLibrary")
    internal external fun loadEngineLibrary(enginePath: String, cefRuntimePath: String): Int

    @JvmName("engineAbiVersion")
    internal external fun engineAbiVersion(): Int

    @JvmName("engineCreate")
    internal external fun engineCreate(
        sink: NativeEngineEventSink,
        cefRuntimePath: String,
        browserSubprocessPath: String,
        resourcesPath: String,
        localesPath: String,
        rootCachePath: String,
        logPath: String,
    ): Long

    @JvmName("engineClose")
    internal external fun engineClose(handle: Long): Int

    @JvmName("liveEngineCount")
    internal external fun liveEngineCount(): Long

    @JvmName("browserCreate")
    internal external fun browserCreate(
        engine: Long,
        sink: NativeBrowserEventSink,
        component: Component,
        profilePath: String,
        initialUrl: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Long

    @JvmName("browserNavigate")
    internal external fun browserNavigate(handle: Long, url: String): Int

    @JvmName("browserResize")
    internal external fun browserResize(handle: Long, width: Int, height: Int): Int

    @JvmName("browserClose")
    internal external fun browserClose(handle: Long): Int

    @JvmName("liveBrowserCount")
    internal external fun liveBrowserCount(): Long
}

internal class NativeEngineEventSink(
    private val callback: (Long, Long, Int) -> Unit,
) {
    @JvmName("onNativeEngineEvent")
    internal fun onNativeEngineEvent(
        handle: Long,
        sequence: Long,
        type: Int,
    ) {
        callback(handle, sequence, type)
    }
}

internal class NativeBrowserEventSink(
    private val callback: (Long, Long, Long, Int, Int, String, Int, Int, Int) -> Unit,
) {
    @JvmName("onNativeBrowserEvent")
    internal fun onNativeBrowserEvent(
        engine: Long,
        browser: Long,
        sequence: Long,
        type: Int,
        flags: Int,
        text: String,
        statusCode: Int,
        width: Int,
        height: Int,
    ) {
        callback(engine, browser, sequence, type, flags, text, statusCode, width, height)
    }
}
