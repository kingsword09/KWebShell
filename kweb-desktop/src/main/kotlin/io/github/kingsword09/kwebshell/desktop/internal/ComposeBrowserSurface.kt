package io.github.kingsword09.kwebshell.desktop.internal

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.awt.EventQueue

internal class ComposeBrowserSurface private constructor(
    private val window: ComposeWindow,
    internal val nativeParent: Long,
) : AutoCloseable {
    override fun close() {
        requireEventDispatchThread("destroy")
        window.dispose()
        if (window.isDisplayable) {
            throw KWebNativeException(
                code = "native.browser.compose-surface-destroy-failed",
                details = emptyMap(),
                message = "The ComposeWindow native parent remained displayable after disposal.",
            )
        }
    }

    internal companion object {
        internal fun create(width: Int, height: Int): ComposeBrowserSurface {
            requireEventDispatchThread("create")
            require(width > 0 && height > 0)
            val window = ComposeWindow()
            try {
                window.title = "KWebShell Chromium Session"
                window.setSize(width, height)
                window.setLocationRelativeTo(null)
                window.isVisible = true
                if (!window.isDisplayable || !window.isShowing) {
                    throw KWebNativeException(
                        code = "native.browser.compose-surface-create-failed",
                        details = emptyMap(),
                        message = "The browser could not create a visible ComposeWindow native parent.",
                    )
                }
                val nativeParent = window.windowHandle
                if (nativeParent == 0L) {
                    throw KWebNativeException(
                        code = "native.browser.compose-parent-handle-invalid",
                        details = emptyMap(),
                        message = "ComposeWindow.windowHandle returned zero for a visible window.",
                    )
                }
                return ComposeBrowserSurface(window, nativeParent)
            } catch (error: Throwable) {
                window.dispose()
                if (error is KWebNativeException) {
                    throw error
                }
                throw KWebNativeException(
                    code = "native.browser.compose-surface-create-failed",
                    details = emptyMap(),
                    message = "Creating the ComposeWindow native parent failed.",
                    cause = error,
                )
            }
        }

        private fun requireEventDispatchThread(operation: String) {
            if (!EventQueue.isDispatchThread()) {
                throw KWebNativeException(
                    code = "native.browser.compose-surface-wrong-thread",
                    details = mapOf("operation" to operation),
                    message = "The ComposeWindow native parent must be owned by the event-dispatch thread.",
                )
            }
        }
    }
}
