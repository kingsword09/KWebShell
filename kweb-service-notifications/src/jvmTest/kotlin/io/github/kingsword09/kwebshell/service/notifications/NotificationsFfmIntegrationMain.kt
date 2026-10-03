package io.github.kingsword09.kwebshell.service.notifications

import java.nio.file.Path
import kotlinx.coroutines.runBlocking

public fun main(): Unit = runBlocking {
    val library = Path.of(System.getProperty("kweb.notifications.native.library.path") ?: error("Missing notification library path"))
    val applicationId = System.getProperty("kweb.notifications.application.id") ?: "io.github.kwebshell.notifications.fixture"
    val packageIdentity = System.getProperty("kweb.notifications.package.identity") ?: applicationId
    try {
        val service = JvmKWebNotifications.open(library, applicationId, packageIdentity) { }
        try {
            val permission = service.permission()
            val capabilities = service.capabilities()
            check(permission.provider.isNotBlank())
            check(capabilities.activation)
            println(
                "KWebNotifications FFM provider=${permission.provider} permission=${permission.status} " +
                    "actions=${capabilities.actions} replies=${capabilities.replies} timeout=${capabilities.timeout}",
            )
        } finally {
            service.close()
        }
    } catch (error: io.github.kingsword09.kwebshell.core.KWebNativeException) {
        if (System.getProperty("kweb.notifications.integration.strict") == "true") throw error
        check(error.code == KWebNotificationErrorCode.PLATFORM_UNAVAILABLE) { "Unexpected notification absence: ${error.code}" }
        println("KWebNotifications FFM provider unavailable in the unbundled local process: ${error.code}")
    }
}
