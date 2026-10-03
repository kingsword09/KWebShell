package io.github.kingsword09.kwebshell.service.notifications

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeKWebNotificationsTest {
    @Test
    fun replacementPublishesOldCloseBeforeNewShown() = runBlocking {
        val native = FakeNative()
        val service = NativeKWebNotifications(native, "io.github.kwebshell.test") { }
        try {
            val events = async { service.events.take(3).toList() }
            service.show(request("first", "mail"))
            val result = service.show(request("second", "mail"))
            assertEquals(KWebNotificationShowOutcome.REPLACED, result.outcome)
            assertEquals("first", result.replacedId?.value)
            val observed = events.await()
            assertEquals(
                listOf(
                    KWebNotificationEvent.Shown(1u, KWebNotificationId("first"), "mail", null),
                    KWebNotificationEvent.Closed(2u, KWebNotificationId("first"), KWebNotificationCloseReason.REPLACED),
                    KWebNotificationEvent.Shown(3u, KWebNotificationId("second"), "mail", KWebNotificationId("first")),
                ),
                observed,
            )
        } finally {
            service.close()
        }
    }

    @Test
    fun rejectsReplyBeforeNativeMutationWhenProviderDoesNotAdvertiseIt() = runBlocking {
        val native = FakeNative(replies = false)
        val service = NativeKWebNotifications(native, "io.github.kwebshell.test") { }
        try {
            val error = assertFailsWith<io.github.kingsword09.kwebshell.core.KWebNativeException> {
                service.show(
                    request("reply", null).copy(
                        actions = listOf(
                            KWebNotificationAction(
                                "reply",
                                "Reply",
                                KWebNotificationActionKind.REPLY,
                                "Type reply",
                            ),
                        ),
                    ),
                )
            }
            assertEquals(KWebNotificationErrorCode.REPLY_UNSUPPORTED, error.code)
            assertEquals(0, native.showCalls)
        } finally {
            service.close()
        }
    }

    @Test
    fun nativeActionIsPublishedAndRoutedOnce() = runBlocking {
        var routed: KWebNotificationActivation? = null
        val native = FakeNative()
        val service = NativeKWebNotifications(native, "io.github.kwebshell.test") { routed = it }
        try {
            service.show(request("action", null))
            native.events.add(NativeNotificationEvent("action", NativeNotificationEventKind.ACTION, "open"))
            val event = withTimeout(2_000) { service.events.filterIsInstance<KWebNotificationEvent.Action>().first() }
            assertEquals("open", event.actionId)
            assertEquals("action", routed?.notificationId?.value)
        } finally {
            service.close()
        }
    }

    private fun request(id: String, tag: String?): KWebNotificationRequest = KWebNotificationRequest(
        id = KWebNotificationId(id),
        tag = tag,
        title = "Title",
        body = "Body",
    )

    private class FakeNative(
        private val replies: Boolean = true,
    ) : NotificationNativeExecutor {
        override val providerId: String = "test.notifications"
        val events = ArrayDeque<NativeNotificationEvent>()
        var showCalls: Int = 0

        override fun permission(): NativeNotificationPermission =
            NativeNotificationPermission(KWebNotificationPermissionStatus.GRANTED, providerId)

        override fun requestPermission(): NativeNotificationPermission = permission()

        override fun capabilities(): NativeNotificationCapabilities = NativeNotificationCapabilities(
            actions = true,
            replies = replies,
            replacement = true,
            timeout = true,
            activation = true,
        )

        override fun show(request: NativeNotificationRequest) {
            showCalls++
        }

        override fun close(id: String) = Unit

        override fun pollEvent(): NativeNotificationEvent? = synchronized(events) {
            if (events.isEmpty()) null else events.removeFirst()
        }

        override fun close() = Unit
    }
}
