package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebNativeException
import java.awt.EventQueue
import java.awt.Frame

internal class AwtEngineLifecycleAnchor private constructor(
    private val frame: Frame,
) : AutoCloseable {
    override fun close() {
        requireEventDispatchThread("destroy")
        frame.dispose()
        if (frame.isDisplayable) {
            throw KWebNativeException(
                code = "native.engine.awt-anchor-destroy-failed",
                details = emptyMap(),
                message = "The native engine AWT lifecycle peer remained displayable after disposal.",
            )
        }
    }

    internal companion object {
        internal fun create(): AwtEngineLifecycleAnchor {
            requireEventDispatchThread("create")
            val frame = Frame()
            try {
                frame.name = "KWebShell-engine-lifecycle-anchor"
                frame.isUndecorated = true
                frame.addNotify()
                if (!frame.isDisplayable) {
                    throw KWebNativeException(
                        code = "native.engine.awt-anchor-create-failed",
                        details = emptyMap(),
                        message = "The native engine could not create its non-visible AWT lifecycle peer.",
                    )
                }
                return AwtEngineLifecycleAnchor(frame)
            } catch (error: Throwable) {
                frame.dispose()
                if (error is KWebNativeException) {
                    throw error
                }
                throw KWebNativeException(
                    code = "native.engine.awt-anchor-create-failed",
                    details = emptyMap(),
                    message = "Creating the native engine AWT lifecycle peer failed.",
                    cause = error,
                )
            }
        }

        private fun requireEventDispatchThread(operation: String) {
            if (!EventQueue.isDispatchThread()) {
                throw KWebNativeException(
                    code = "native.engine.awt-anchor-wrong-thread",
                    details = mapOf("operation" to operation),
                    message = "The native engine AWT lifecycle peer must be owned by the event-dispatch thread.",
                )
            }
        }
    }
}
