package io.github.kingsword09.kwebshell.interop.probe;

import androidx.compose.ui.awt.ComposeWindow;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class ComposeParentContractMain {
    private static final int STATUS_OK = 0;

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected <absolute-interop-probe-library>.");
        }
        if (GraphicsEnvironment.isHeadless()) {
            throw new IllegalStateException("Compose native-parent verification requires a display.");
        }
        Path library = ExactLibrary.argument(arguments[0], "interop probe library");
        AtomicLong verifiedHandle = new AtomicLong();
        try (FfmProbe probe = FfmProbe.open(library)) {
            invokeOnEventDispatchThread(() -> {
                ComposeWindow window = new ComposeWindow();
                try {
                    window.setTitle("KWebShell FFM native-parent contract");
                    window.setSize(640, 480);
                    window.setLocationRelativeTo(null);
                    window.setVisible(true);
                    require(window.isDisplayable() && window.isShowing(),
                        "ComposeWindow did not create a visible native peer.");
                    long handle = window.getWindowHandle();
                    require(handle != 0, "ComposeWindow.windowHandle returned zero.");
                    require(probe.validateNativeParent(handle) == STATUS_OK,
                        "ComposeWindow.windowHandle is not a valid native top-level parent.");
                    verifiedHandle.set(handle);
                } finally {
                    window.dispose();
                    require(!window.isDisplayable(), "ComposeWindow native peer remained displayable.");
                }
            });
            require(probe.liveNativeBytes() == 0, "Native-parent proof leaked probe memory.");
        }
        require(verifiedHandle.get() != 0, "Native-parent proof did not observe a handle.");
        System.out.println("ComposeWindow.windowHandle native-parent contract passed.");
    }

    private static void invokeOnEventDispatchThread(Runnable operation) {
        try {
            EventQueue.invokeAndWait(operation);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Native-parent verification was interrupted.", error);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException("Native-parent verification failed.", cause);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private ComposeParentContractMain() {
    }
}
