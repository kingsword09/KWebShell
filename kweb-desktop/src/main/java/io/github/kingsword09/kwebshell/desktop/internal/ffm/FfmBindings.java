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
        FfmCallbacks.ProfileDataEvent profileDataSink,
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
            profileDataSink,
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

    public static int engineProfileNetwork(
        long engine,
        long requestId,
        int operation,
        String profilePath,
        String payload
    ) {
        return FfmEngineCalls.profileNetwork(engine, requestId, operation, profilePath, payload);
    }

    public static int engineClearProfileNetworkPolicy(long engine, String profilePath) {
        return FfmEngineCalls.clearProfileNetworkPolicy(engine, profilePath);
    }

    public static int engineOpenProfileContext(long engine, String profilePath) {
        return FfmEngineCalls.openProfileContext(engine, profilePath);
    }

    public static long browserCreate(
        long engine,
        FfmCallbacks.BrowserEvent browserSink,
        FfmCallbacks.BridgeEvent bridgeSink,
        FfmCallbacks.ProfileDataEvent profileDataSink,
        FfmCallbacks.Failure failureSink,
        long nativeParent,
        String profilePath,
        String initialUrl,
        int x,
        int y,
        int width,
        int height,
        String bridgeOrigin,
        boolean downloadsEnabled,
        boolean contextMenusEnabled
    ) {
        return FfmBrowserCalls.create(
            engine,
            browserSink,
            bridgeSink,
            profileDataSink,
            failureSink,
            nativeParent,
            profilePath,
            initialUrl,
            x,
            y,
            width,
            height,
            bridgeOrigin,
            downloadsEnabled,
            contextMenusEnabled
        );
    }

    public static int browserNavigate(long handle, String url) {
        return FfmBrowserCalls.navigate(handle, url);
    }

    public static int browserReload(long handle, boolean ignoreCache) {
        return FfmBrowserCalls.reload(handle, ignoreCache);
    }

    public static int browserRespondToBeforeUnload(long handle, long requestId, boolean proceed) {
        return FfmBrowserCalls.respondToBeforeUnload(handle, requestId, proceed);
    }

    public static int browserRespondToPopup(long handle, long requestId, boolean allow) {
        return FfmBrowserCalls.respondToPopup(handle, requestId, allow);
    }

    public static int browserSetBounds(long handle, int x, int y, int width, int height) {
        return FfmBrowserCalls.setBounds(handle, x, y, width, height);
    }

    public static int browserSetSurfaceState(long handle, boolean visible, boolean focused) {
        return FfmBrowserCalls.setSurfaceState(handle, visible, focused);
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

    public static int browserCrashRenderer(long handle) {
        return FfmBrowserCalls.crashRenderer(handle);
    }

    public static int browserBridgeRespond(long handle, long requestId, String responseJson) {
        return FfmBrowserCalls.bridgeRespond(handle, requestId, responseJson);
    }

    public static int browserBridgeFail(long handle, long requestId, String failureJson) {
        return FfmBrowserCalls.bridgeFail(handle, requestId, failureJson);
    }

    public static int browserContextMenuRespond(long handle, long requestId, String decisionJson) {
        return FfmBrowserCalls.contextMenuRespond(handle, requestId, decisionJson);
    }

    public static int browserSecurityRespond(long handle, long requestId, String decisionJson) {
        return FfmBrowserCalls.securityRespond(handle, requestId, decisionJson);
    }

    public static int browserProfileData(long handle, long requestId, int operation, String payloadJson) {
        return FfmBrowserCalls.profileData(handle, requestId, operation, payloadJson);
    }

    public static int browserDownloadControl(long handle, long downloadId, int operation) {
        return FfmBrowserCalls.downloadControl(handle, downloadId, operation);
    }

    public static int browserStartDownload(long handle, String url) {
        return FfmBrowserCalls.startDownload(handle, url);
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
