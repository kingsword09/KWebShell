package io.github.kingsword09.kwebshell.service.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebNotificationsContractTest {
    @Test
    fun publishesApplicationScopedOperationsAndBounds() {
        assertEquals("notifications", KWebNotifications.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebNotifications.DESCRIPTOR.version.toString())
        assertEquals(
            setOf("permission", "request-permission", "capabilities", "show", "close", "events"),
            KWebNotifications.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assertEquals(KWebNotifications.DESCRIPTOR.operations.single { it.id == "events" }.rendererPermission, null)
        assertEquals(true, KWebNotifications.DESCRIPTOR.operations.single { it.id == "request-permission" }.requiresUserGesture)
    }

    @Test
    fun rejectsInvalidContentAndDuplicateActions() {
        assertFailsWith<Exception> { KWebNotificationId("bad id") }
        assertFailsWith<Exception> {
            KWebNotificationRequest(
                id = KWebNotificationId("notification-1"),
                title = "title",
                body = "body",
                actions = listOf(
                    KWebNotificationAction("open", "Open", KWebNotificationActionKind.BUTTON),
                    KWebNotificationAction("open", "Again", KWebNotificationActionKind.BUTTON),
                ),
            )
        }
        assertFailsWith<Exception> {
            KWebNotificationAction("reply", "Reply", KWebNotificationActionKind.REPLY)
        }
        assertFailsWith<Exception> {
            KWebNotificationRequest(
                id = KWebNotificationId("notification-2"),
                title = "title",
                body = "x".repeat(KWEB_NOTIFICATION_MAX_BODY_BYTES + 1),
            )
        }
    }

    @Test
    fun activationProtocolContainsOnlyBoundedIdentityFields() {
        val activation = KWebNotificationActivation(
            applicationId = "io.github.kwebshell.test",
            notificationId = KWebNotificationId("notification-1"),
            tag = "mail",
            actionId = "open",
            reply = "yes",
        )
        assertEquals(
            "kweb://notification?app=io.github.kwebshell.test&id=notification-1&tag=mail&action=open&reply=yes",
            activation.toProtocolUri(),
        )
    }
}
