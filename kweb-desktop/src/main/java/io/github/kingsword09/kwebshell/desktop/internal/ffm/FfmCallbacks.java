package io.github.kingsword09.kwebshell.desktop.internal.ffm;

public final class FfmCallbacks {
    @FunctionalInterface
    public interface Failure {
        void onFailure(String code, String message, Throwable cause);
    }

    @FunctionalInterface
    public interface EngineEvent {
        void onEvent(long engine, long sequence, int type);
    }

    @FunctionalInterface
    public interface BrowserEvent {
        void onEvent(
            long engine,
            long browser,
            long sequence,
            int type,
            int flags,
            String text,
            int statusCode,
            int width,
            int height
        );
    }

    @FunctionalInterface
    public interface BridgeEvent {
        void onEvent(long engine, long browser, long requestId, int type, String payload);
    }

    @FunctionalInterface
    public interface ExtensionResult {
        void onResult(
            long operationHandle,
            long engine,
            long browser,
            int operation,
            int outcome,
            int state,
            String extensionId,
            String version,
            String path,
            String errorCode,
            String errorMessage
        );
    }

    private FfmCallbacks() {
    }
}
