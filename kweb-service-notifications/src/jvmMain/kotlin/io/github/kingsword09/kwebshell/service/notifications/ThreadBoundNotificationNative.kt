package io.github.kingsword09.kwebshell.service.notifications

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Keeps WinRT objects and the apartment that created them on one live thread. */
internal class ThreadBoundNotificationNative private constructor(
    private val executor: ExecutorService,
    private val native: NotificationNativeExecutor,
) : NotificationNativeExecutor {
    private var closed = false

    override val providerId: String get() = call { native.providerId }
    override fun permission(): NativeNotificationPermission = call { native.permission() }
    override fun requestPermission(): NativeNotificationPermission = call { native.requestPermission() }
    override fun capabilities(): NativeNotificationCapabilities = call { native.capabilities() }
    override fun show(request: NativeNotificationRequest): Unit = call { native.show(request) }
    override fun close(id: String): Unit = call { native.close(id) }
    override fun pollEvent(): NativeNotificationEvent? = call { native.pollEvent() }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            await(executor.submit(Callable { native.close() }))
        } finally {
            executor.shutdown()
        }
    }

    @Synchronized
    private fun <T> call(block: () -> T): T {
        if (closed) throw NotificationNativeFailure("service.owner-closed", "The notification provider is closed.")
        return await(executor.submit(Callable { block() }))
    }

    companion object {
        fun open(factory: () -> NotificationNativeExecutor): ThreadBoundNotificationNative {
            val executor = Executors.newSingleThreadExecutor { task ->
                Thread(task, "kweb-notifications-winrt").apply { isDaemon = true }
            }
            return try {
                ThreadBoundNotificationNative(executor, await(executor.submit(Callable { factory() })))
            } catch (error: Throwable) {
                executor.shutdown()
                throw error
            }
        }

        private fun <T> await(future: Future<T>): T {
            var interrupted = false
            try {
                while (true) {
                    try {
                        return future.get()
                    } catch (_: InterruptedException) {
                        // Once admitted, the native operation must finish before
                        // arena/apartment teardown. Preserve caller interruption.
                        interrupted = true
                    } catch (error: ExecutionException) {
                        throw checkNotNull(error.cause)
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
