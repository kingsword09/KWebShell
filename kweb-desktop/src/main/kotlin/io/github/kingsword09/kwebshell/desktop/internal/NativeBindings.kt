package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmBindings
import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmCallbacks
import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmNativeAccessException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

internal const val NATIVE_LIBRARY_PATH_PROPERTY: String = "kweb.native.library.path"

internal data class NativeLibraryPaths(
    val engine: Path,
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
    configuredEnginePath: String,
    operatingSystem: String,
): NativeLibraryPaths {
    val engineFileName = nativeEngineLibraryFileName(operatingSystem)
    val enginePath = try {
        Path.of(configuredEnginePath)
    } catch (error: InvalidPathException) {
        throw KWebConfigurationException(
            code = "native.library.path-invalid",
            details = mapOf("path" to configuredEnginePath),
            message = "The KWebShell engine library path is invalid.",
            cause = error,
        )
    }
    if (!enginePath.isAbsolute || enginePath.normalize() != enginePath || !Files.isRegularFile(enginePath)) {
        throw KWebConfigurationException(
            code = "native.library.path-invalid",
            details = mapOf("path" to configuredEnginePath),
            message = "The KWebShell engine library path must be absolute, normalized, and identify a regular file.",
        )
    }
    if (enginePath.fileName.toString() != engineFileName) {
        throw KWebConfigurationException(
            code = "native.engine-library.path-invalid",
            details = mapOf("path" to enginePath.toString()),
            message = "The configured file name does not match the current platform engine library.",
        )
    }
    return NativeLibraryPaths(engine = enginePath)
}

internal object NativeBindings {
    internal val libraryPaths: NativeLibraryPaths

    init {
        val configuredPath = System.getProperty(NATIVE_LIBRARY_PATH_PROPERTY)
            ?: throw KWebConfigurationException(
                code = "native.library.path-missing",
                details = mapOf("property" to NATIVE_LIBRARY_PATH_PROPERTY),
                message = "The absolute KWebShell engine library path is required.",
            )
        libraryPaths = resolveNativeLibraryPaths(configuredPath, System.getProperty("os.name"))
    }

    internal fun loadEngineLibrary(enginePath: String, cefRuntimePath: String): Int = try {
        FfmBindings.loadEngineLibrary(enginePath, cefRuntimePath)
    } catch (error: FfmNativeAccessException) {
        throw KWebConfigurationException(
            code = "native.ffm.native-access-disabled",
            details = mapOf("grant" to error.grantTarget()),
            message = "KWebShell FFM native access is disabled for the desktop module.",
            cause = error,
        )
    }

    internal fun lastEngineLibraryLoadFailure(): Throwable? =
        FfmBindings.lastEngineLibraryLoadFailure()

    internal fun engineAbiVersion(): Int = FfmBindings.engineAbiVersion()

    internal fun engineCreate(
        sink: NativeEngineEventSink,
        cefRuntimePath: String,
        browserSubprocessPath: String,
        resourcesPath: String,
        localesPath: String,
        rootCachePath: String,
        logPath: String,
        remoteDebuggingPort: Int,
    ): Long = FfmBindings.engineCreate(
        FfmCallbacks.EngineEvent(sink::onNativeEngineEvent),
        FfmCallbacks.Failure(sink::onNativeCallbackFailure),
        cefRuntimePath,
        browserSubprocessPath,
        resourcesPath,
        localesPath,
        rootCachePath,
        logPath,
        remoteDebuggingPort,
    )

    internal fun engineClose(handle: Long): Int = FfmBindings.engineClose(handle)

    internal fun releaseEngineOwner(handle: Long): Throwable? =
        releaseOwner("engine", handle) { FfmBindings.releaseEngineOwner(handle) }

    internal fun liveEngineCount(): Long = FfmBindings.liveEngineCount()

    internal fun browserCreate(
        engine: Long,
        sink: NativeBrowserEventSink,
        nativeParent: Long,
        profilePath: String,
        initialUrl: String,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        bridgeOrigin: String,
        bridgeSink: NativeBridgeEventSink?,
    ): Long = FfmBindings.browserCreate(
        engine,
        FfmCallbacks.BrowserEvent(sink::onNativeBrowserEvent),
        bridgeSink?.let { FfmCallbacks.BridgeEvent(it::onNativeBridgeEvent) },
        FfmCallbacks.Failure(sink::onNativeCallbackFailure),
        nativeParent,
        profilePath,
        initialUrl,
        x,
        y,
        width,
        height,
        bridgeOrigin,
    )

    internal fun browserNavigate(handle: Long, url: String): Int = FfmBindings.browserNavigate(handle, url)

    internal fun browserResize(handle: Long, width: Int, height: Int): Int =
        FfmBindings.browserResize(handle, width, height)

    internal fun browserClose(handle: Long): Int = FfmBindings.browserClose(handle)

    internal fun browserOpenDevTools(handle: Long): Int = FfmBindings.browserOpenDevTools(handle)

    internal fun browserCloseDevTools(handle: Long): Int = FfmBindings.browserCloseDevTools(handle)

    internal fun browserBridgeRespond(handle: Long, requestId: Long, responseJson: String): Int =
        FfmBindings.browserBridgeRespond(handle, requestId, responseJson)

    internal fun browserBridgeFail(handle: Long, requestId: Long, failureJson: String): Int =
        FfmBindings.browserBridgeFail(handle, requestId, failureJson)

    internal fun releaseBrowserOwner(handle: Long): Throwable? =
        releaseOwner("browser", handle) { FfmBindings.releaseBrowserOwner(handle) }

    internal fun liveBrowserCount(): Long = FfmBindings.liveBrowserCount()

    internal fun extensionStart(
        browser: Long,
        sink: NativeExtensionResultSink,
        operation: Int,
        extensionId: String,
        expectedVersion: String,
        extensionPath: String,
    ): Long = FfmBindings.extensionStart(
        browser,
        FfmCallbacks.ExtensionResult(sink::onNativeExtensionResult),
        FfmCallbacks.Failure(sink::onNativeCallbackFailure),
        operation,
        extensionId,
        expectedVersion,
        extensionPath,
    )

    internal fun extensionCancel(operation: Long): Int = FfmBindings.extensionCancel(operation)

    internal fun releaseExtensionOwner(operation: Long): Throwable? =
        releaseOwner("extension", operation) { FfmBindings.releaseExtensionOwner(operation) }

    internal fun liveExtensionOperationCount(): Long = FfmBindings.liveExtensionOperationCount()

    internal fun liveCallbackOwnerCount(): Int = FfmBindings.liveCallbackOwnerCount()

    private inline fun releaseOwner(kind: String, handle: Long, release: () -> Throwable?): Throwable? = try {
        release()
    } catch (error: Throwable) {
        throw KWebNativeException(
            code = "native.ffm.$kind-owner-release-failed",
            details = mapOf("handle" to handle.toString()),
            message = "The terminal native callback owner could not be released.",
            cause = error,
        )
    }
}

internal class NativeEngineEventSink(
    private val failureCallback: (String, String, Throwable) -> Unit = ::throwNativeCallbackFailure,
    private val callback: (Long, Long, Int) -> Unit,
) {
    internal fun onNativeEngineEvent(
        handle: Long,
        sequence: Long,
        type: Int,
    ) {
        callback(handle, sequence, type)
    }

    internal fun onNativeCallbackFailure(code: String, message: String, cause: Throwable) {
        failureCallback(code, message, cause)
    }
}

internal class NativeBrowserEventSink(
    private val failureCallback: (String, String, Throwable) -> Unit = ::throwNativeCallbackFailure,
    private val callback: (Long, Long, Long, Int, Int, String, Int, Int, Int) -> Unit,
) {
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

    internal fun onNativeCallbackFailure(code: String, message: String, cause: Throwable) {
        failureCallback(code, message, cause)
    }
}

internal class NativeBridgeEventSink(
    private val callback: (Long, Long, Long, Int, String) -> Unit,
) {
    internal fun onNativeBridgeEvent(
        engine: Long,
        browser: Long,
        requestId: Long,
        type: Int,
        payload: String,
    ) {
        callback(engine, browser, requestId, type, payload)
    }
}

internal class NativeExtensionResultSink(
    private val failureCallback: (String, String, Throwable) -> Unit = ::throwNativeCallbackFailure,
    private val callback: (Long, Long, Long, Int, Int, Int, String, String, String, String, String) -> Unit,
) {
    internal fun onNativeExtensionResult(
        operationHandle: Long,
        engine: Long,
        browser: Long,
        operation: Int,
        outcome: Int,
        state: Int,
        extensionId: String,
        version: String,
        path: String,
        errorCode: String,
        errorMessage: String,
    ) {
        callback(
            operationHandle,
            engine,
            browser,
            operation,
            outcome,
            state,
            extensionId,
            version,
            path,
            errorCode,
            errorMessage,
        )
    }

    internal fun onNativeCallbackFailure(code: String, message: String, cause: Throwable) {
        failureCallback(code, message, cause)
    }
}

private fun throwNativeCallbackFailure(code: String, message: String, cause: Throwable): Nothing =
    throw KWebNativeException(
        code = code,
        details = emptyMap(),
        message = message,
        cause = cause,
    )
