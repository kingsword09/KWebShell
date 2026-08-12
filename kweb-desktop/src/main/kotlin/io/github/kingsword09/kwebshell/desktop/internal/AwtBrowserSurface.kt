package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.awt.BorderLayout
import java.awt.Canvas
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame

internal class AwtBrowserSurface private constructor(
    private val frame: Frame,
    internal val component: Canvas,
) : AutoCloseable {
    override fun close() {
        requireEventDispatchThread("destroy")
        frame.dispose()
        if (frame.isDisplayable || component.isDisplayable) {
            throw KWebNativeException(
                code = "native.browser.awt-surface-destroy-failed",
                details = emptyMap(),
                message = "The browser AWT peer remained displayable after disposal.",
            )
        }
    }

    internal companion object {
        internal fun create(width: Int, height: Int): AwtBrowserSurface {
            requireEventDispatchThread("create")
            require(width > 0 && height > 0)
            val frame = Frame("KWebShell Chromium Session")
            val canvas = Canvas()
            try {
                frame.layout = BorderLayout()
                canvas.name = "KWebShell-browser-native-parent"
                canvas.preferredSize = Dimension(width, height)
                frame.add(canvas, BorderLayout.CENTER)
                frame.pack()
                frame.setLocationRelativeTo(null)
                frame.isVisible = true
                if (!frame.isDisplayable || !canvas.isDisplayable || !canvas.isShowing) {
                    throw KWebNativeException(
                        code = "native.browser.awt-surface-create-failed",
                        details = emptyMap(),
                        message = "The browser could not create a visible AWT native parent.",
                    )
                }
                return AwtBrowserSurface(frame, canvas)
            } catch (error: Throwable) {
                frame.dispose()
                if (error is KWebNativeException) {
                    throw error
                }
                throw KWebNativeException(
                    code = "native.browser.awt-surface-create-failed",
                    details = emptyMap(),
                    message = "Creating the browser AWT native parent failed.",
                    cause = error,
                )
            }
        }

        private fun requireEventDispatchThread(operation: String) {
            if (!EventQueue.isDispatchThread()) {
                throw KWebNativeException(
                    code = "native.browser.awt-surface-wrong-thread",
                    details = mapOf("operation" to operation),
                    message = "The browser AWT native parent must be owned by the event-dispatch thread.",
                )
            }
        }
    }
}
