package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellAccess
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResolver
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResource
import io.github.kingsword09.kwebshell.service.files.KWebFileNodeKind
import io.github.kingsword09.kwebshell.service.shell.internal.ShellFfm
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmKWebShellLifecycleTest {
    @Test
    fun navigationCancelsAnAdmittedActionBeforeNativeBoundary() = runBlocking {
        val resolver = BlockingResolver()
        val native = RecordingNative()
        val shell = NativeKWebShell(native, KWebShellConfiguration(), resolver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val request = scope.async {
                shell.openResource(KWebShellResourceHandle("a".repeat(16)))
            }
            resolver.entered.await()
            shell.onNavigationStarted()
            resolver.release.countDown()

            val failure = runCatching { request.await() }.exceptionOrNull()
            check(failure is KWebNativeException) { "Expected a typed lifecycle failure, got $failure" }
            assertEquals(KWebServiceErrorCode.CANCELLED, failure.code)
            assertEquals(0, native.executions.get())

            shell.onNavigationCommitted()
            val result = shell.openExternal(KWebShellExternalUriRequest("https://example.invalid"))
            assertEquals(KWebShellActionOutcome.HANDLER_ACCEPTED, result.outcome)
        } finally {
            scope.cancel()
            shell.close()
        }
    }

    @Test
    fun closeCancelsAnAdmittedActionBeforeNativeBoundaryAndClosesOnce() = runBlocking {
        val resolver = BlockingResolver()
        val native = RecordingNative()
        val shell = NativeKWebShell(native, KWebShellConfiguration(), resolver)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val request = scope.async {
                shell.openResource(KWebShellResourceHandle("b".repeat(16)))
            }
            resolver.entered.await()
            val closer = thread(start = true, name = "kweb-shell-test-close") { shell.close() }
            while (shell.lifecycle.value != KWebLifecycleState.CLOSING) Thread.yield()
            resolver.release.countDown()

            val failure = runCatching { request.await() }.exceptionOrNull()
            check(failure is KWebNativeException) { "Expected a typed lifecycle failure, got $failure" }
            assertEquals(KWebServiceErrorCode.OWNER_CLOSED, failure.code)
            closer.join(5_000)
            check(!closer.isAlive) { "Shell close did not finish after the pre-boundary action was cancelled." }
            assertEquals(0, native.executions.get())
            assertEquals(1, native.closes.get())
            assertEquals(KWebLifecycleState.CLOSED, shell.lifecycle.value)
        } finally {
            scope.cancel()
            if (shell.lifecycle.value != KWebLifecycleState.CLOSED) shell.close()
        }
    }

    private class BlockingResolver : JvmKWebFilesShellResolver {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override suspend fun <T> withResource(
            token: String,
            access: JvmKWebFilesShellAccess,
            action: (JvmKWebFilesShellResource) -> T,
        ): T {
            entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) { "Resolver was not released." }
            return action(
                JvmKWebFilesShellResource(
                    Path.of(System.getProperty("java.io.tmpdir"), "kweb-shell-lifecycle-test"),
                    KWebFileNodeKind.FILE,
                ),
            )
        }
    }

    private class RecordingNative : ShellNativeExecutor {
        val executions = AtomicInteger()
        val closes = AtomicInteger()

        override fun execute(action: Int, resourceKind: Int, value: String): ShellFfm.NativeResult {
            executions.incrementAndGet()
            return ShellFfm.NativeResult(
                if (action == 4) ShellFfm.OUTCOME_MOVED_TO_TRASH else ShellFfm.OUTCOME_HANDLER_ACCEPTED,
                resourceKind,
            )
        }

        override fun close() {
            closes.incrementAndGet()
        }
    }
}
