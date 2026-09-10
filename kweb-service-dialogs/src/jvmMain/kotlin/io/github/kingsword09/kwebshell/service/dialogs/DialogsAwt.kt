package io.github.kingsword09.kwebshell.service.dialogs

import java.awt.EventQueue
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

internal fun <T> onDialogsAwtThread(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val task = FutureTask(action)
    EventQueue.invokeLater(task)
    return try {
        task.get()
    } catch (error: ExecutionException) {
        throw requireNotNull(error.cause)
    }
}
