package io.github.kingsword09.kwebshell.desktop.internal.ffm;

public final class FfmBindings {
    public static int loadEngineLibrary(String enginePath, String cefRuntimePath) {
        return FfmEngineLibrary.load(enginePath, cefRuntimePath);
    }

    public static Throwable lastEngineLibraryLoadFailure() {
        return FfmEngineLibrary.lastLoadFailure();
    }

    public static int engineAbiVersion() {
        return FfmEngineCalls.abiVersion();
    }

    public static long engineCreate(
        FfmCallbacks.EngineEvent sink,
        FfmCallbacks.Failure failureSink,
        String cefRuntimePath,
        String browserSubprocessPath,
        String resourcesPath,
        String localesPath,
        String rootCachePath,
        String logPath,
        int remoteDebuggingPort
    ) {
        return FfmEngineCalls.create(
            sink,
            failureSink,
            cefRuntimePath,
            browserSubprocessPath,
            resourcesPath,
            localesPath,
            rootCachePath,
            logPath,
            remoteDebuggingPort
        );
    }

    public static int engineClose(long handle) {
        return FfmEngineCalls.close(handle);
    }

    public static Throwable releaseEngineOwner(long handle) {
        return FfmEngineCalls.release(handle);
    }

    public static long liveEngineCount() {
        return FfmEngineCalls.liveCount();
    }

    public static long browserCreate(
        long engine,
        FfmCallbacks.BrowserEvent browserSink,
        FfmCallbacks.BridgeEvent bridgeSink,
        FfmCallbacks.Failure failureSink,
        long nativeParent,
        String profilePath,
        String initialUrl,
        int x,
        int y,
        int width,
        int height,
        String bridgeOrigin
    ) {
        return FfmBrowserCalls.create(
            engine,
            browserSink,
            bridgeSink,
            failureSink,
            nativeParent,
            profilePath,
            initialUrl,
            x,
            y,
            width,
            height,
            bridgeOrigin
        );
    }

    public static int browserNavigate(long handle, String url) {
        return FfmBrowserCalls.navigate(handle, url);
    }

    public static int browserResize(long handle, int width, int height) {
        return FfmBrowserCalls.resize(handle, width, height);
    }

    public static int browserClose(long handle) {
        return FfmBrowserCalls.close(handle);
    }

    public static int browserOpenDevTools(long handle) {
        return FfmBrowserCalls.openDevTools(handle);
    }

    public static int browserCloseDevTools(long handle) {
        return FfmBrowserCalls.closeDevTools(handle);
    }

    public static int browserBridgeRespond(long handle, long requestId, String responseJson) {
        return FfmBrowserCalls.bridgeRespond(handle, requestId, responseJson);
    }

    public static int browserBridgeFail(long handle, long requestId, String failureJson) {
        return FfmBrowserCalls.bridgeFail(handle, requestId, failureJson);
    }

    public static Throwable releaseBrowserOwner(long handle) {
        return FfmBrowserCalls.release(handle);
    }

    public static long liveBrowserCount() {
        return FfmBrowserCalls.liveCount();
    }

    public static long extensionStart(
        long browser,
        FfmCallbacks.ExtensionResult sink,
        FfmCallbacks.Failure failureSink,
        int operation,
        String extensionId,
        String expectedVersion,
        String extensionPath
    ) {
        return FfmExtensionCalls.start(
            browser,
            sink,
            failureSink,
            operation,
            extensionId,
            expectedVersion,
            extensionPath
        );
    }

    public static int extensionCancel(long operation) {
        return FfmExtensionCalls.cancel(operation);
    }

    public static Throwable releaseExtensionOwner(long operation) {
        return FfmExtensionCalls.release(operation);
    }

    public static long liveExtensionOperationCount() {
        return FfmExtensionCalls.liveCount();
    }

    public static int liveCallbackOwnerCount() {
        return FfmEngineCalls.liveOwnerCount()
            + FfmBrowserCalls.liveOwnerCount()
            + FfmExtensionCalls.liveOwnerCount();
    }

    public static String nativeAccessGrantTarget() {
        return FfmNativeAccess.grantTarget();
    }

    private FfmBindings() {
    }
}
