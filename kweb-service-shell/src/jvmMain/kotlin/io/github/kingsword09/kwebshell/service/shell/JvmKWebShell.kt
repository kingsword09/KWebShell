package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellAccess
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResolver
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResource
import io.github.kingsword09.kwebshell.service.files.KWebFileNodeKind
import io.github.kingsword09.kwebshell.service.shell.internal.ShellFfm
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.withLock
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock

public object JvmKWebShell {
    public fun open(
        libraryPath: Path,
        configuration: KWebShellConfiguration,
        resolver: JvmKWebFilesShellResolver,
    ): KWebShell = NativeKWebShell(ShellFfm.open(libraryPath), configuration, resolver)
}

internal class NativeKWebShell(
    private val native: ShellFfm,
    private val configuration: KWebShellConfiguration,
    private val resolver: JvmKWebFilesShellResolver,
) : KWebShell {
    private val lock = ReentrantLock()
    private val closeCondition = lock.newCondition()
    private val actionMutex = Mutex()
    private val lifecycleState = MutableStateFlow(KWebLifecycleState.OPEN)
    private var pendingActions = 0
    private var closeFailure: KWebNativeException? = null

    override val descriptor = KWebShell.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = lifecycleState.asStateFlow()

    override suspend fun openExternal(request: KWebShellExternalUriRequest): KWebShellActionResult =
        action("open-external") {
            val uri = ShellPolicy.normalizeExternalUri(request, configuration)
            executeNative(KWebShellAction.OPEN_EXTERNAL, null, uri)
        }

    override suspend fun openResource(handle: KWebShellResourceHandle): KWebShellActionResult =
        resourceAction("open-resource", handle, JvmKWebFilesShellAccess.OPEN) { resource ->
            executeNative(KWebShellAction.OPEN_RESOURCE, resource, resource.path.toString())
        }

    override suspend fun revealResource(handle: KWebShellResourceHandle): KWebShellActionResult =
        resourceAction("reveal-resource", handle, JvmKWebFilesShellAccess.REVEAL) { resource ->
            executeNative(KWebShellAction.REVEAL_RESOURCE, resource, resource.path.toString())
        }

    override suspend fun trashResource(handle: KWebShellResourceHandle): KWebShellActionResult =
        resourceAction("trash-resource", handle, JvmKWebFilesShellAccess.TRASH) { resource ->
            if (resource.kind == KWebFileNodeKind.DIRECTORY && !configuration.allowDirectoryTrash) {
                throw ShellPolicy.failure(
                    KWebShellErrorCode.DIRECTORY_NOT_ALLOWED,
                    "Directory trash is not enabled by host policy.",
                    "trash-resource",
                )
            }
            executeNative(KWebShellAction.TRASH_RESOURCE, resource, resource.path.toString())
        }

    override fun close() {
        lock.withLock {
            if (lifecycleState.value == KWebLifecycleState.CLOSED) return
            closeFailure?.let { throw it }
            lifecycleState.value = KWebLifecycleState.CLOSING
            while (pendingActions != 0) {
                try {
                    closeCondition.await()
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw ShellPolicy.failure(
                        KWebServiceErrorCode.OWNER_CLOSED,
                        "The shell service close was interrupted.",
                        "close",
                        error,
                    )
                }
            }
            try {
                native.close()
                lifecycleState.value = KWebLifecycleState.CLOSED
            } catch (error: Throwable) {
                val failure = ShellPolicy.failure(
                    KWebShellErrorCode.NATIVE_UNAVAILABLE,
                    "The shell native provider could not close.",
                    "close",
                    error,
                )
                closeFailure = failure
                lifecycleState.value = KWebLifecycleState.FAILED
                throw failure
            }
        }
    }

    private suspend fun <T> action(
        operation: String,
        block: suspend () -> T,
    ): T {
        lock.withLock {
            if (lifecycleState.value != KWebLifecycleState.OPEN) {
                throw ShellPolicy.failure(KWebServiceErrorCode.OWNER_CLOSED, "The shell service is not open.", operation)
            }
            if (pendingActions >= MAX_PENDING_ACTIONS) {
                throw ShellPolicy.failure(KWebShellErrorCode.BUSY, "The shell action queue is full.", operation)
            }
            pendingActions++
        }
        try {
            val result: T = actionMutex.withLock { withContext(Dispatchers.IO) { block() } }
            return result
        } finally {
            lock.withLock {
                pendingActions--
                closeCondition.signalAll()
            }
        }
    }

    private suspend fun resourceAction(
        operation: String,
        handle: KWebShellResourceHandle,
        access: JvmKWebFilesShellAccess,
        block: (JvmKWebFilesShellResource) -> KWebShellActionResult,
    ): KWebShellActionResult {
        ShellPolicy.validateHandle(handle.token, operation)
        return action(operation) { resolver.withResource(handle.token, access, block) }
    }

    private fun executeNative(
        action: KWebShellAction,
        resource: JvmKWebFilesShellResource?,
        value: String,
    ): KWebShellActionResult {
        val kind = resource?.kind?.let {
            when (it) {
                KWebFileNodeKind.FILE -> 1
                KWebFileNodeKind.DIRECTORY -> 2
            }
        } ?: 0
        return try {
            val result = native.execute(action.nativeId, kind, value)
            val expected = if (action == KWebShellAction.TRASH_RESOURCE) {
                ShellFfm.OUTCOME_MOVED_TO_TRASH
            } else {
                ShellFfm.OUTCOME_HANDLER_ACCEPTED
            }
            if (result.outcome() != expected) {
                throw ShellPolicy.failure(
                    KWebServiceErrorCode.NATIVE_FAILED,
                    "The shell provider returned an invalid outcome.",
                    action.operationId,
                )
            }
            KWebShellActionResult(action, action.outcome, resource?.let {
                when (it.kind) {
                    KWebFileNodeKind.FILE -> KWebShellResourceKind.FILE
                    KWebFileNodeKind.DIRECTORY -> KWebShellResourceKind.DIRECTORY
                }
            })
        } catch (error: ShellFfm.NativeFailure) {
            throw mapFailure(action, error)
        }
    }

    private fun mapFailure(action: KWebShellAction, error: ShellFfm.NativeFailure): KWebNativeException {
        val code = when (error.status()) {
            ShellFfm.STATUS_INVALID_ARGUMENT -> KWebShellErrorCode.URI_INVALID
            ShellFfm.STATUS_NATIVE_UNAVAILABLE -> KWebShellErrorCode.NATIVE_UNAVAILABLE
            ShellFfm.STATUS_HANDLER_REJECTED -> KWebShellErrorCode.HANDLER_REJECTED
            ShellFfm.STATUS_REVEAL_UNAVAILABLE -> KWebShellErrorCode.REVEAL_UNAVAILABLE
            ShellFfm.STATUS_TRASH_FAILED -> KWebShellErrorCode.TRASH_FAILED
            ShellFfm.STATUS_TRASH_VERIFICATION_FAILED -> KWebShellErrorCode.TRASH_VERIFICATION_FAILED
            else -> KWebServiceErrorCode.NATIVE_FAILED
        }
        return ShellPolicy.failure(code, "The shell native provider rejected the operation.", action.operationId, error)
    }

    private companion object {
        const val MAX_PENDING_ACTIONS = 8
    }
}

private val KWebShellAction.nativeId: Int
    get() = when (this) {
        KWebShellAction.OPEN_EXTERNAL -> 1
        KWebShellAction.OPEN_RESOURCE -> 2
        KWebShellAction.REVEAL_RESOURCE -> 3
        KWebShellAction.TRASH_RESOURCE -> 4
    }

private val KWebShellAction.operationId: String
    get() = when (this) {
        KWebShellAction.OPEN_EXTERNAL -> "open-external"
        KWebShellAction.OPEN_RESOURCE -> "open-resource"
        KWebShellAction.REVEAL_RESOURCE -> "reveal-resource"
        KWebShellAction.TRASH_RESOURCE -> "trash-resource"
    }

private val KWebShellAction.outcome: KWebShellActionOutcome
    get() = if (this == KWebShellAction.TRASH_RESOURCE) {
        KWebShellActionOutcome.MOVED_TO_TRASH
    } else {
        KWebShellActionOutcome.HANDLER_ACCEPTED
    }
