package io.github.kingsword09.kwebshell.service.notifications

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThreadBoundNotificationNativeTest {
    @Test
    fun factoryCallsAndConcurrentCloseShareOneOwnerThread() {
        lateinit var native: ThreadCheckingNative
        val bound = ThreadBoundNotificationNative.open { ThreadCheckingNative().also { native = it } }
        val callers = Executors.newFixedThreadPool(4)
        try {
            val calls = (1..12).map { callers.submit { bound.permission(); bound.capabilities(); bound.pollEvent() } }
            calls.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals("test.thread-bound", bound.providerId)
            bound.requestPermission()
            bound.show(NativeNotificationRequest(
                "id", null, "Title", "Body", KWebNotificationIcon.APPLICATION,
                KWebNotificationUrgency.NORMAL, KWebNotificationTimeout.SYSTEM, emptyList(),
            ))
            bound.close("id")
            (1..4).map { callers.submit { bound.close() } }.forEach { it.get(2, TimeUnit.SECONDS) }
            assertEquals(1, native.closeCount.get())
            assertEquals(41, native.callCount.get())
            native.owner.join(2_000)
            assertFalse(native.owner.isAlive)
            assertEquals("service.owner-closed", assertFailsWith<NotificationNativeFailure> { bound.permission() }.code)
        } finally {
            bound.close()
            callers.shutdownNow()
        }
    }

    @Test
    fun failingFactoryTerminatesThreadAndPreservesTypedFailure() {
        lateinit var owner: Thread
        val expected = NotificationNativeFailure("notifications.platform-unavailable", "Unavailable.")
        val actual = assertFailsWith<NotificationNativeFailure> {
            ThreadBoundNotificationNative.open {
                owner = Thread.currentThread()
                throw expected
            }
        }
        assertSame(expected, actual)
        owner.join(2_000)
        assertFalse(owner.isAlive)
    }

    @Test
    fun nativeFailureIsUnwrappedAndOwnerStillCloses() {
        val expected = NotificationNativeFailure("notifications.permission-denied", "Denied.")
        lateinit var native: ThreadCheckingNative
        val bound = ThreadBoundNotificationNative.open {
            ThreadCheckingNative(permissionFailure = expected).also { native = it }
        }
        assertSame(expected, assertFailsWith<NotificationNativeFailure> { bound.permission() })
        bound.close()
        assertEquals(1, native.closeCount.get())
    }

    @Test
    fun interruptionWaitsForDispatchedNativeResultAndRestoresInterruptFlag() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val completed = AtomicBoolean()
        val bound = ThreadBoundNotificationNative.open {
            ThreadCheckingNative(beforePermission = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            })
        }
        val caller = thread {
            bound.permission()
            interrupted.set(Thread.currentThread().isInterrupted)
            completed.set(true)
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            caller.interrupt()
            assertFalse(completed.get())
            release.countDown()
            caller.join(2_000)
            assertTrue(completed.get())
            assertTrue(interrupted.get())
        } finally {
            release.countDown()
            caller.join(2_000)
            bound.close()
        }
    }

    private class ThreadCheckingNative(
        private val permissionFailure: NotificationNativeFailure? = null,
        private val beforePermission: (() -> Unit)? = null,
    ) : NotificationNativeExecutor {
        val owner: Thread = Thread.currentThread()
        val closeCount = AtomicInteger()
        val callCount = AtomicInteger()
        private fun verifyOwner() {
            check(Thread.currentThread() === owner)
            callCount.incrementAndGet()
        }
        override val providerId: String get() { verifyOwner(); return "test.thread-bound" }
        override fun permission(): NativeNotificationPermission {
            verifyOwner()
            beforePermission?.invoke()
            permissionFailure?.let { throw it }
            return NativeNotificationPermission(KWebNotificationPermissionStatus.GRANTED, "test.thread-bound")
        }
        override fun requestPermission(): NativeNotificationPermission = permission()
        override fun capabilities(): NativeNotificationCapabilities {
            verifyOwner()
            return NativeNotificationCapabilities(true, true, true, true, true)
        }
        override fun show(request: NativeNotificationRequest) { verifyOwner() }
        override fun close(id: String) { verifyOwner() }
        override fun pollEvent(): NativeNotificationEvent? { verifyOwner(); return null }
        override fun close() { verifyOwner(); closeCount.incrementAndGet() }
    }
}
