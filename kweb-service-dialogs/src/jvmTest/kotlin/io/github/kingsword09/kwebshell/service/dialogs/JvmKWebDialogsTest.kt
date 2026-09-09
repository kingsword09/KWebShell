package io.github.kingsword09.kwebshell.service.dialogs

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.awt.EventQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertSame

class JvmKWebDialogsTest {
    @Test
    fun openAndSaveHandlesEnforceModesAndOwnerLifetime() {
        val root = Files.createTempDirectory("kweb-dialog-provider")
        val input = root.resolve("input.txt")
        val output = root.resolve("output.txt")
        Files.write(input, byteArrayOf(1, 2, 3, -1))
        val owner = composeWindow()
        val selector = FixedSelector(input, output)
        val service = JvmKWebDialogs.openForTesting(owner, selector)
        try {
            runBlocking {
                val opened = requireNotNull(service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Read")))
                assertEquals("input.txt", opened.name)
                assertEquals(4L, opened.sizeBytes)
                assertEquals(43, opened.handle.length)
                assertEquals(listOf(2, 3), service.readFile(opened.handle, 1, 2).bytes)
                assertTrue(service.readFile(opened.handle, 4, 2).eof)
                assertEquals(
                    KWebDialogsErrorCode.HANDLE_MODE,
                    assertFailsWith<KWebNativeException> { service.writeFile(opened.handle, 0, listOf(7)) }.code,
                )
                val saved = requireNotNull(service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.SAVE, "Write")))
                assertEquals(3, service.writeFile(saved.handle, 0, listOf(9, 8, 7)).written)
                assertEquals(2L, service.truncateFile(saved.handle, 2).sizeBytes)
                service.closeFile(saved.handle)
                assertEquals(listOf(9, 8), Files.readAllBytes(output).map(Byte::toInt))
                assertEquals(
                    KWebDialogsErrorCode.HANDLE_NOT_FOUND,
                    assertFailsWith<KWebNativeException> { service.closeFile(saved.handle) }.code,
                )
                service.close()
                assertEquals(
                    "service.owner-closed",
                    assertFailsWith<KWebNativeException> { service.readFile(opened.handle, 0, 1) }.code,
                )
            }
            assertTrue(selector.closed)
        } finally {
            service.close()
            dispose(owner)
            Files.deleteIfExists(input)
            Files.deleteIfExists(output)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun handlesAreIsolatedAndBoundsFailBeforeIo() {
        val root = Files.createTempDirectory("kweb-dialog-isolation")
        val input = Files.write(root.resolve("input.txt"), byteArrayOf(1))
        val owner = composeWindow()
        val first = JvmKWebDialogs.openForTesting(owner, FixedSelector(input, root.resolve("output.txt")))
        val second = JvmKWebDialogs.openForTesting(owner, FixedSelector(input, root.resolve("other.txt")))
        try {
            runBlocking {
                val handle = requireNotNull(first.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Read"))).handle
                assertEquals(
                    KWebDialogsErrorCode.HANDLE_NOT_FOUND,
                    assertFailsWith<KWebNativeException> { second.readFile(handle, 0, 1) }.code,
                )
                assertEquals(
                    KWebDialogsErrorCode.IO_BOUNDS,
                    assertFailsWith<KWebNativeException> { first.readFile(handle, -1, 1) }.code,
                )
                assertEquals(
                    KWebDialogsErrorCode.IO_BOUNDS,
                    assertFailsWith<KWebNativeException> { first.readFile(handle, 0, KWEB_DIALOGS_MAX_TRANSFER_BYTES + 1) }.code,
                )
                assertEquals(
                    KWebDialogsErrorCode.IO_BOUNDS,
                    assertFailsWith<KWebNativeException> { first.readFile(handle, Long.MAX_VALUE, 1) }.code,
                )
                assertEquals(
                    KWebDialogsErrorCode.HANDLE_INVALID,
                    assertFailsWith<KWebNativeException> { first.readFile(input.toString(), 0, 1) }.code,
                )
            }
        } finally {
            first.close()
            second.close()
            dispose(owner)
            Files.deleteIfExists(input)
            Files.deleteIfExists(root)
        }
    }

    @Test
    fun cancelledDeliveryReclaimsHandleBeforeTheNextSelection() {
        val root = Files.createTempDirectory("kweb-dialog-cancel")
        val input = Files.write(root.resolve("input.txt"), byteArrayOf(1))
        val owner = composeWindow()
        val service = JvmKWebDialogs.openForTesting(owner, FixedSelector(input, input))
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            repeat(70) {
                val selected = scope.async { service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Pick")) }
                dispatcher.next().run() // Start validation on IO.
                dispatcher.next().run() // Select, then open the actual file on IO.
                val delivery = dispatcher.next() // The channel is open; caller has not received it.
                selected.cancel()
                delivery.run()
                runBlocking { withTimeout(5_000) { selected.join() } }
                assertTrue(selected.isCancelled)
            }
            runBlocking {
                val handles = List(64) {
                    requireNotNull(service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Pick"))).handle
                }
                assertEquals(64, handles.toSet().size)
                assertEquals(KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
                    assertFailsWith<KWebNativeException> {
                        service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Limit"))
                    }.code)
                handles.forEach { service.closeFile(it) }
            }
        } finally {
            scope.cancel()
            service.close()
            dispose(owner)
            Files.delete(input)
            Files.delete(root)
        }
    }

    @Test
    fun ownerCloseRejectsLateSaveWithoutCreatingAFile() = runBlocking {
        val root = Files.createTempDirectory("kweb-dialog-late")
        val output = root.resolve("must-not-exist.txt")
        val entered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Path>()
        val owner = composeWindow()
        val selector = object : KWebFileDialogSelector {
            override suspend fun select(request: KWebFileDialogRequest): Path = withContext(NonCancellable) {
                entered.complete(Unit)
                result.await()
            }
            override fun close() { result.complete(output) }
        }
        val service = JvmKWebDialogs.openForTesting(owner, selector)
        try {
            val pending = async(Dispatchers.Default) {
                runCatching { service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.SAVE, "Save")) }
            }
            withTimeout(5_000) { entered.await() }
            assertEquals(KWebDialogsErrorCode.DIALOG_UNAVAILABLE,
                assertFailsWith<KWebNativeException> {
                    service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Second"))
                }.code)
            service.close()
            val error = withTimeout(5_000) { pending.await() }.exceptionOrNull()
            assertTrue(error is KWebNativeException)
            assertEquals("service.owner-closed", error.code)
            assertFalse(Files.exists(output))
        } finally {
            service.close()
            dispose(owner)
            Files.deleteIfExists(output)
            Files.delete(root)
        }
    }

    @Test
    fun channelCloseRacingReadHasOnlyTypedOutcomes() = runBlocking {
        val root = Files.createTempDirectory("kweb-dialog-io-close")
        val input = Files.write(root.resolve("input.txt"), ByteArray(KWEB_DIALOGS_MAX_TRANSFER_BYTES) { 255.toByte() })
        val owner = composeWindow()
        val service = JvmKWebDialogs.openForTesting(owner, FixedSelector(input, input))
        try {
            repeat(40) {
                val token = requireNotNull(service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Read"))).handle
                val start = CompletableDeferred<Unit>()
                val read = async(Dispatchers.Default) {
                    start.await()
                    runCatching { service.readFile(token, 0, KWEB_DIALOGS_MAX_TRANSFER_BYTES) }
                }
                val close = async(Dispatchers.Default) { start.await(); service.closeFile(token) }
                start.complete(Unit)
                close.await()
                val outcome = read.await()
                if (outcome.isSuccess) {
                    assertEquals(KWEB_DIALOGS_MAX_TRANSFER_BYTES, outcome.getOrThrow().bytes.size)
                } else {
                    val error = outcome.exceptionOrNull()
                    assertTrue(error is KWebNativeException)
                    assertEquals(KWebDialogsErrorCode.HANDLE_NOT_FOUND, error.code)
                }
            }
        } finally {
            service.close()
            dispose(owner)
            Files.delete(input)
            Files.delete(root)
        }
    }

    @Test
    fun invalidPathsNeverBecomeHandlesAndMultiPartFiltersMatch() = runBlocking {
        val root = Files.createTempDirectory("kweb-dialog-paths")
        val input = Files.write(root.resolve("archive.tar.gz"), byteArrayOf(1))
        val link = Files.createSymbolicLink(root.resolve("linked.tar.gz"), input)
        val owner = composeWindow()
        var selected = input
        val selector = object : KWebFileDialogSelector {
            override suspend fun select(request: KWebFileDialogRequest): Path = selected
            override fun close() = Unit
        }
        val service = JvmKWebDialogs.openForTesting(owner, selector)
        val request = KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Read",
            filters = listOf(KWebFileFilter("Archives", listOf("tar.gz"))))
        try {
            assertEquals("archive.tar.gz", requireNotNull(service.selectFile(request)).name)
            for (invalid in listOf(link, root, root.resolve("missing.tar.gz"), Path.of("relative.txt"))) {
                selected = invalid
                assertEquals(KWebDialogsErrorCode.PATH_INVALID,
                    assertFailsWith<KWebNativeException> { service.selectFile(request) }.code)
            }
            selected = link
            assertEquals(KWebDialogsErrorCode.PATH_INVALID,
                assertFailsWith<KWebNativeException> {
                    service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.SAVE, "Save"))
                }.code)
            assertEquals(byteArrayOf(1).toList(), Files.readAllBytes(input).toList())
        } finally {
            service.close()
            dispose(owner)
            Files.delete(link)
            Files.delete(input)
            Files.delete(root)
        }
    }

    @Test
    fun failedSelectorCloseStillClosesFilesAndKeepsTheOriginalFailure() = runBlocking {
        val root = Files.createTempDirectory("kweb-dialog-close-failure")
        val input = Files.write(root.resolve("input.txt"), byteArrayOf(1))
        val owner = composeWindow()
        val selectorFailure = IllegalStateException("Native cancellation failed")
        val service = JvmKWebDialogs.openForTesting(owner, object : KWebFileDialogSelector {
            override suspend fun select(request: KWebFileDialogRequest): Path = input
            override fun close(): Unit = throw selectorFailure
        })
        try {
            val handle = requireNotNull(service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.OPEN, "Read"))).handle
            val closeFailure = assertFailsWith<KWebNativeException> { service.close() }
            assertSame(selectorFailure, closeFailure.cause)
            assertSame(closeFailure, assertFailsWith<KWebNativeException> { service.close() })
            assertEquals(KWebLifecycleState.FAILED, service.lifecycle.value)
            assertEquals("service.owner-closed",
                assertFailsWith<KWebNativeException> { service.readFile(handle, 0, 1) }.code)
        } finally {
            dispose(owner)
            Files.delete(input)
            Files.delete(root)
        }
    }

    private class QueuedDispatcher : CoroutineDispatcher() {
        private val queue = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
        fun next(): Runnable = requireNotNull(queue.poll(30, TimeUnit.SECONDS)) {
            "Coroutine did not reach the next IO boundary."
        }
    }

    private class FixedSelector(private val input: Path, private val output: Path) : KWebFileDialogSelector {
        var closed = false
        override suspend fun select(request: KWebFileDialogRequest): Path =
            if (request.mode == KWebFileDialogMode.OPEN) input else output

        override fun close() {
            closed = true
        }
    }

    private fun composeWindow(): ComposeWindow {
        val owner = AtomicReference<ComposeWindow>()
        EventQueue.invokeAndWait {
            owner.set(ComposeWindow().apply { setBounds(100, 100, 320, 240); isVisible = true })
        }
        return requireNotNull(owner.get())
    }

    private fun dispose(owner: ComposeWindow) {
        EventQueue.invokeAndWait { owner.dispose() }
    }
}
