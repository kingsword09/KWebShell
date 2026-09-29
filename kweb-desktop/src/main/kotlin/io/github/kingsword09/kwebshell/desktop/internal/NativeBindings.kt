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
        profileDataSink: NativeProfileDataEventSink,
        cefRuntimePath: String,
        browserSubprocessPath: String,
        resourcesPath: String,
        localesPath: String,
        rootCachePath: String,
        logPath: String,
        remoteDebuggingPort: Int,
    ): Long = FfmBindings.engineCreate(
        FfmCallbacks.EngineEvent(sink::onNativeEngineEvent),
        FfmCallbacks.ProfileDataEvent(profileDataSink::onNativeProfileDataEvent),
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

    internal fun engineProfileNetwork(
        engine: Long,
        requestId: Long,
        operation: Int,
        profilePath: String,
        payload: String,
    ): Int = FfmBindings.engineProfileNetwork(
        engine,
        requestId,
        operation,
        profilePath,
        payload,
    )

    internal fun engineClearProfileNetworkPolicy(engine: Long, profilePath: String): Int =
        FfmBindings.engineClearProfileNetworkPolicy(engine, profilePath)

    internal fun engineOpenProfileContext(engine: Long, profilePath: String): Int =
        FfmBindings.engineOpenProfileContext(engine, profilePath)

    internal fun browserCreate(
        engine: Long,
        sink: NativeBrowserEventSink,
        profileDataSink: NativeProfileDataEventSink,
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
        FfmCallbacks.ProfileDataEvent(profileDataSink::onNativeProfileDataEvent),
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
    ): Long = browserCreate(
        engine = engine,
        sink = sink,
        profileDataSink = NativeProfileDataEventSink { _, _, _, _, _, _ -> },
        nativeParent = nativeParent,
        profilePath = profilePath,
        initialUrl = initialUrl,
        x = x,
        y = y,
        width = width,
        height = height,
        bridgeOrigin = bridgeOrigin,
        bridgeSink = bridgeSink,
    )

    internal fun browserNavigate(handle: Long, url: String): Int = FfmBindings.browserNavigate(handle, url)

    internal fun browserReload(handle: Long, ignoreCache: Boolean): Int =
        FfmBindings.browserReload(handle, ignoreCache)

    internal fun browserRespondToBeforeUnload(handle: Long, requestId: Long, proceed: Boolean): Int =
        FfmBindings.browserRespondToBeforeUnload(handle, requestId, proceed)

    internal fun browserRespondToPopup(handle: Long, requestId: Long, allow: Boolean): Int =
        FfmBindings.browserRespondToPopup(handle, requestId, allow)

    internal fun browserSetBounds(handle: Long, x: Int, y: Int, width: Int, height: Int): Int =
        FfmBindings.browserSetBounds(handle, x, y, width, height)

    internal fun browserSetSurfaceState(handle: Long, visible: Boolean, focused: Boolean): Int =
        FfmBindings.browserSetSurfaceState(handle, visible, focused)

    internal fun browserClose(handle: Long): Int = FfmBindings.browserClose(handle)

    internal fun browserOpenDevTools(handle: Long): Int = FfmBindings.browserOpenDevTools(handle)

    internal fun browserCloseDevTools(handle: Long): Int = FfmBindings.browserCloseDevTools(handle)

    internal fun browserCrashRenderer(handle: Long): Int = FfmBindings.browserCrashRenderer(handle)

    internal fun browserBridgeRespond(handle: Long, requestId: Long, responseJson: String): Int =
        FfmBindings.browserBridgeRespond(handle, requestId, responseJson)

    internal fun browserBridgeFail(handle: Long, requestId: Long, failureJson: String): Int =
        FfmBindings.browserBridgeFail(handle, requestId, failureJson)

    internal fun browserSecurityRespond(handle: Long, requestId: Long, decisionJson: String): Int =
        FfmBindings.browserSecurityRespond(handle, requestId, decisionJson)

    internal fun browserProfileData(
        handle: Long,
        requestId: Long,
        operation: Int,
        payloadJson: String,
    ): Int = FfmBindings.browserProfileData(handle, requestId, operation, payloadJson)

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
    private val detailedCallback: (
        Long, Long, Long, Int, Int, String, Int, Int, Int, Long, String, Int, Int, String, String, String, String,
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
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
        requestId: Long,
        frameId: String,
        frameScope: Int,
        reason: Int,
        origin: String,
        url: String,
        title: String,
        details: String,
    ) {
        callback(engine, browser, sequence, type, flags, text, statusCode, width, height)
        detailedCallback(
            engine,
            browser,
            sequence,
            type,
            flags,
            text,
            statusCode,
            width,
            height,
            requestId,
            frameId,
            frameScope,
            reason,
            origin,
            url,
            title,
            details,
        )
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

internal class NativeProfileDataEventSink(
    private val callback: (Long, Long, Long, Int, Int, String) -> Unit,
) {
    internal fun onNativeProfileDataEvent(
        engine: Long,
        browser: Long,
        requestId: Long,
        operation: Int,
        status: Int,
        payload: String,
    ) {
        callback(engine, browser, requestId, operation, status, payload)
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
