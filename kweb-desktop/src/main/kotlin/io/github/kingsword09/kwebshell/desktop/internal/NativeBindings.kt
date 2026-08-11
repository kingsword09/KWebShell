package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale
import kotlin.jvm.JvmName

internal const val NATIVE_LIBRARY_PATH_PROPERTY: String = "kweb.native.library.path"

internal data class NativeLibraryPaths(
    val abi: Path,
    val jni: Path,
)

internal fun nativeAbiLibraryFileName(operatingSystem: String): String =
    when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_abi.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_abi.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_abi.so"
        else -> throw KWebConfigurationException(
            code = "native.platform.unsupported",
            details = mapOf("osName" to operatingSystem),
            message = "The current operating system is not supported by the KWebShell native contract.",
        )
    }

internal fun resolveNativeLibraryPaths(
    configuredJniPath: String,
    operatingSystem: String,
): NativeLibraryPaths {
    val abiFileName = nativeAbiLibraryFileName(operatingSystem)
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
    val abiPath = jniPath.resolveSibling(abiFileName)
    if (!Files.isRegularFile(abiPath)) {
        throw KWebConfigurationException(
            code = "native.abi-library.path-invalid",
            details = mapOf("path" to abiPath.toString()),
            message = "The KWebShell ABI library must be adjacent to the JNI library.",
        )
    }
    return NativeLibraryPaths(abi = abiPath, jni = jniPath)
}

internal object NativeBindings {
    init {
        val configuredPath = System.getProperty(NATIVE_LIBRARY_PATH_PROPERTY)
            ?: throw KWebConfigurationException(
                code = "native.library.path-missing",
                details = mapOf("property" to NATIVE_LIBRARY_PATH_PROPERTY),
                message = "The absolute KWebShell JNI library path is required.",
            )
        val paths = resolveNativeLibraryPaths(configuredPath, System.getProperty("os.name"))
        loadNativeLibrary(
            path = paths.abi,
            errorCode = "native.abi-library.load-failed",
            errorMessage = "The KWebShell ABI library could not be loaded.",
        )
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

    @JvmName("abiVersion")
    internal external fun abiVersion(): Int

    @JvmName("create")
    internal external fun create(sink: NativeEventSink): Long

    @JvmName("requestNavigation")
    internal external fun requestNavigation(handle: Long, url: String): Int

    @JvmName("resize")
    internal external fun resize(handle: Long, width: Int, height: Int): Int

    @JvmName("close")
    internal external fun close(handle: Long): Int

    @JvmName("liveSessionCount")
    internal external fun liveSessionCount(): Long
}

internal class NativeEventSink(
    private val callback: (Long, Long, Int, String, Int, Int) -> Unit,
) {
    @JvmName("onNativeEvent")
    internal fun onNativeEvent(
        handle: Long,
        sequence: Long,
        type: Int,
        text: String,
        width: Int,
        height: Int,
    ) {
        callback(handle, sequence, type, text, width, height)
    }
}
