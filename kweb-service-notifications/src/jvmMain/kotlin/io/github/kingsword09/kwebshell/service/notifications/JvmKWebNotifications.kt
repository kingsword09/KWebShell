package io.github.kingsword09.kwebshell.service.notifications

import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.notifications.internal.NotificationFfm
import java.nio.file.Path
import java.util.Locale

public object JvmKWebNotifications {
    public fun open(
        libraryPath: Path,
        applicationId: String,
        packageIdentity: String,
        activationRouter: KWebNotificationActivationRouter,
    ): KWebNotifications = try {
        val factory = { FfmNotificationNative(NotificationFfm.open(libraryPath, applicationId, packageIdentity)) }
        val native = if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) {
            ThreadBoundNotificationNative.open(factory)
        } else factory()
        try {
            NativeKWebNotifications(native, applicationId, activationRouter)
        } catch (error: Throwable) {
            runCatching { native.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    } catch (error: NotificationFfm.NativeFailure) {
        throw KWebNativeException(
            code = KWebNotificationErrorCode.PLATFORM_UNAVAILABLE,
            details = mapOf("service" to KWebNotifications.DESCRIPTOR.id, "operation" to "open"),
            message = "The native notification provider could not be opened.",
            cause = error,
        )
    } catch (error: IllegalCallerException) {
        throw KWebNativeException(
            code = "service.native-access-required",
            details = mapOf("service" to KWebNotifications.DESCRIPTOR.id, "operation" to "open"),
            message = "JDK native access must be enabled for the notification provider.",
            cause = error,
        )
    } catch (error: Exception) {
        throw KWebNativeException(
            code = KWebNotificationErrorCode.PLATFORM_UNAVAILABLE,
            details = mapOf("service" to KWebNotifications.DESCRIPTOR.id, "operation" to "open"),
            message = "The native notification provider could not be opened.",
            cause = error,
        )
    }
}

private class FfmNotificationNative(
    private val native: NotificationFfm,
) : NotificationNativeExecutor {
    override val providerId: String get() = native.providerId

    override fun permission(): NativeNotificationPermission = call { native.permission() }
    override fun requestPermission(): NativeNotificationPermission = call { native.requestPermission() }
    override fun capabilities(): NativeNotificationCapabilities = call { native.capabilities() }
    override fun show(request: NativeNotificationRequest): Unit = call { native.show(request) }
    override fun close(id: String): Unit = call { native.close(id) }
    override fun pollEvent(): NativeNotificationEvent? = call { native.pollEvent() }
    override fun close(): Unit = call { native.close() }

    private fun <T> call(block: () -> T): T = try {
        block()
    } catch (error: NotificationFfm.NativeFailure) {
        throw NotificationNativeFailure(mapStatus(error.status()), error.statusName(), error)
    }

    private fun mapStatus(status: Int): String = when (status) {
        NotificationFfm.STATUS_NATIVE_UNAVAILABLE -> KWebNotificationErrorCode.PLATFORM_UNAVAILABLE
        NotificationFfm.STATUS_PERMISSION_DENIED -> KWebNotificationErrorCode.PERMISSION_DENIED
        NotificationFfm.STATUS_PERMISSION_UNDETERMINED -> KWebNotificationErrorCode.PERMISSION_UNDETERMINED
        NotificationFfm.STATUS_ACTIONS_UNSUPPORTED -> KWebNotificationErrorCode.ACTIONS_UNSUPPORTED
        NotificationFfm.STATUS_REPLY_UNSUPPORTED -> KWebNotificationErrorCode.REPLY_UNSUPPORTED
        NotificationFfm.STATUS_TIMEOUT_UNSUPPORTED -> KWebNotificationErrorCode.TIMEOUT_UNSUPPORTED
        NotificationFfm.STATUS_NOT_FOUND -> KWebNotificationErrorCode.NOT_FOUND
        else -> KWebNotificationErrorCode.NATIVE_FAILED
    }
}
