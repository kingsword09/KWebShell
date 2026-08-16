package io.github.kingsword09.kwebshell.extensions

import io.github.kingsword09.kwebshell.core.KWebException
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

public enum class KWebExtensionRuntimeOperation {
    INSTALL,
    UPDATE,
    RELOAD,
    UNINSTALL,
    QUERY,
}

public enum class KWebExtensionRuntimeOutcome {
    SUCCESS,
    REJECTED,
    AMBIGUOUS,
}

public enum class KWebExtensionRuntimeState {
    UNKNOWN,
    ABSENT,
    ENABLED,
    DISABLED,
    TERMINATED,
    BLOCKLISTED,
    BLOCKED,
}

public enum class KWebExtensionRuntimeDispatchState {
    NOT_DISPATCHED,
    MAY_HAVE_DISPATCHED,
}

public data class KWebExtensionRuntimeRequest(
    public val operation: KWebExtensionRuntimeOperation,
    public val extensionId: String,
    public val expectedVersion: String?,
    public val extensionPath: Path?,
)

public data class KWebExtensionRuntimeResult(
    public val operation: KWebExtensionRuntimeOperation,
    public val outcome: KWebExtensionRuntimeOutcome,
    public val state: KWebExtensionRuntimeState,
    public val extensionId: String,
    public val version: String?,
    public val path: Path?,
    public val errorCode: String?,
    public val errorMessage: String?,
)

public fun interface KWebExtensionRuntime {
    public suspend fun execute(request: KWebExtensionRuntimeRequest): KWebExtensionRuntimeResult
}

public class KWebExtensionRuntimeException(
    public val dispatchState: KWebExtensionRuntimeDispatchState,
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public enum class KWebExtensionLifecycleResolution {
    COMMITTED,
    ABORTED,
    RETAINED,
}

public data class KWebExtensionLifecycleFailure(
    public val code: String,
    public val details: Map<String, String>,
    public val message: String,
)

public data class KWebExtensionLifecycleResult(
    public val transactionToken: String,
    public val operation: KWebExtensionRuntimeOperation,
    public val extensionId: String,
    public val resolution: KWebExtensionLifecycleResolution,
    public val runtimeResult: KWebExtensionRuntimeResult?,
    public val failure: KWebExtensionLifecycleFailure?,
)

public class JvmKWebExtensionLifecycleCoordinator private constructor(
    private val store: JvmKWebExtensionProfileStore,
    private val runtime: KWebExtensionRuntime,
    private val operationTimeoutMillis: Long,
) {
    private val lifecycleMutex: Mutex = Mutex()

    public suspend fun installUnpacked(source: Path): KWebExtensionLifecycleResult = lifecycleMutex.withLock {
        executeTransaction(store.prepareUnpacked(source))
    }

    public suspend fun installCrx3(source: Path): KWebExtensionLifecycleResult = lifecycleMutex.withLock {
        executeTransaction(store.prepareCrx3(source))
    }

    public suspend fun reload(extensionId: String): KWebExtensionLifecycleResult = lifecycleMutex.withLock {
        executeTransaction(store.prepareReload(extensionId))
    }

    public suspend fun uninstall(extensionId: String): KWebExtensionLifecycleResult = lifecycleMutex.withLock {
        executeTransaction(store.prepareUninstall(extensionId))
    }

    public suspend fun reconcile(): List<KWebExtensionLifecycleResult> = lifecycleMutex.withLock {
        store.pendingTransactions().map { transaction ->
            reconcileTransaction(transaction)
        }
    }

    public fun pendingExtensionIds(): Set<String> = store.pendingTransactions()
        .mapTo(linkedSetOf()) { transaction -> transaction.extension.packageInfo.extensionId }

    private suspend fun executeTransaction(
        transaction: JvmKWebExtensionStoreTransaction,
    ): KWebExtensionLifecycleResult {
        val request = transaction.toRuntimeRequest()
        val runtimeResult = try {
            withTimeout(operationTimeoutMillis) {
                runtime.execute(request)
            }
        } catch (error: CancellationException) {
            store.retain(transaction.token)
            throw error
        } catch (error: Exception) {
            val notDispatched = error is KWebExtensionRuntimeException &&
                error.dispatchState == KWebExtensionRuntimeDispatchState.NOT_DISPATCHED
            if (notDispatched) {
                store.abort(transaction.token)
            } else {
                store.retain(transaction.token)
            }
            return lifecycleResult(
                transaction = transaction,
                resolution = if (notDispatched) {
                    KWebExtensionLifecycleResolution.ABORTED
                } else {
                    KWebExtensionLifecycleResolution.RETAINED
                },
                runtimeResult = null,
                failure = error.toLifecycleFailure(),
            )
        }

        validateRuntimeResult(request, runtimeResult)?.let { failure ->
            store.retain(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.RETAINED,
                runtimeResult,
                failure,
            )
        }
        return when (runtimeResult.outcome) {
            KWebExtensionRuntimeOutcome.SUCCESS -> {
                if (matchesTarget(transaction, runtimeResult)) {
                    store.commit(transaction.token)
                    lifecycleResult(
                        transaction,
                        KWebExtensionLifecycleResolution.COMMITTED,
                        runtimeResult,
                        null,
                    )
                } else {
                    store.retain(transaction.token)
                    lifecycleResult(
                        transaction,
                        KWebExtensionLifecycleResolution.RETAINED,
                        runtimeResult,
                        identityFailure(transaction, runtimeResult),
                    )
                }
            }
            KWebExtensionRuntimeOutcome.REJECTED -> {
                if (matchesPrevious(transaction, runtimeResult)) {
                    store.abort(transaction.token)
                    lifecycleResult(
                        transaction,
                        KWebExtensionLifecycleResolution.ABORTED,
                        runtimeResult,
                        runtimeResult.toLifecycleFailure(),
                    )
                } else {
                    store.retain(transaction.token)
                    lifecycleResult(
                        transaction,
                        KWebExtensionLifecycleResolution.RETAINED,
                        runtimeResult,
                        identityFailure(transaction, runtimeResult),
                    )
                }
            }
            KWebExtensionRuntimeOutcome.AMBIGUOUS -> {
                store.retain(transaction.token)
                lifecycleResult(
                    transaction,
                    KWebExtensionLifecycleResolution.RETAINED,
                    runtimeResult,
                    runtimeResult.toLifecycleFailure(),
                )
            }
        }
    }

    private suspend fun reconcileTransaction(
        transaction: JvmKWebExtensionStoreTransaction,
    ): KWebExtensionLifecycleResult {
        val request = KWebExtensionRuntimeRequest(
            operation = KWebExtensionRuntimeOperation.QUERY,
            extensionId = transaction.extension.packageInfo.extensionId,
            expectedVersion = null,
            extensionPath = null,
        )
        val query = try {
            withTimeout(operationTimeoutMillis) {
                runtime.execute(request)
            }
        } catch (error: CancellationException) {
            store.retain(transaction.token)
            throw error
        } catch (error: Exception) {
            store.retain(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.RETAINED,
                null,
                error.toLifecycleFailure(),
            )
        }
        validateRuntimeResult(request, query)?.let { failure ->
            store.retain(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.RETAINED,
                query,
                failure,
            )
        }
        if (query.outcome != KWebExtensionRuntimeOutcome.SUCCESS) {
            store.retain(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.RETAINED,
                query,
                query.toLifecycleFailure(),
            )
        }
        if (transaction.operation == JvmKWebExtensionStoreOperation.RELOAD) {
            return if (matchesTarget(transaction, query)) {
                executeTransaction(transaction)
            } else {
                store.retain(transaction.token)
                lifecycleResult(
                    transaction,
                    KWebExtensionLifecycleResolution.RETAINED,
                    query,
                    identityFailure(transaction, query),
                )
            }
        }
        if (matchesTarget(transaction, query)) {
            store.commit(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.COMMITTED,
                query,
                null,
            )
        }
        if (matchesPrevious(transaction, query)) {
            store.abort(transaction.token)
            return lifecycleResult(
                transaction,
                KWebExtensionLifecycleResolution.ABORTED,
                query,
                KWebExtensionLifecycleFailure(
                    code = "extensions.lifecycle.reconcile-previous-state",
                    details = mapOf("extensionId" to query.extensionId),
                    message = "Chromium still contains the exact state from before the interrupted operation.",
                ),
            )
        }
        store.retain(transaction.token)
        return lifecycleResult(
            transaction,
            KWebExtensionLifecycleResolution.RETAINED,
            query,
            identityFailure(transaction, query),
        )
    }

    private fun matchesTarget(
        transaction: JvmKWebExtensionStoreTransaction,
        result: KWebExtensionRuntimeResult,
    ): Boolean = when (transaction.operation) {
        JvmKWebExtensionStoreOperation.UNINSTALL -> result.state == KWebExtensionRuntimeState.ABSENT &&
            result.version == null && result.path == null
        JvmKWebExtensionStoreOperation.INSTALL,
        JvmKWebExtensionStoreOperation.UPDATE,
        JvmKWebExtensionStoreOperation.RELOAD,
        -> result.matches(transaction.extension)
    }

    private fun matchesPrevious(
        transaction: JvmKWebExtensionStoreTransaction,
        result: KWebExtensionRuntimeResult,
    ): Boolean {
        if (transaction.previousContentDigest == null) {
            return result.state == KWebExtensionRuntimeState.ABSENT &&
                result.version == null && result.path == null
        }
        val previous = store.active(transaction.extension.packageInfo.extensionId) ?: return false
        return previous.contentDigest == transaction.previousContentDigest && result.matches(previous)
    }

    private fun validateRuntimeResult(
        request: KWebExtensionRuntimeRequest,
        result: KWebExtensionRuntimeResult,
    ): KWebExtensionLifecycleFailure? {
        val success = result.outcome == KWebExtensionRuntimeOutcome.SUCCESS
        val errorsValid = if (success) {
            result.errorCode == null && result.errorMessage == null
        } else {
            !result.errorCode.isNullOrBlank() && !result.errorMessage.isNullOrBlank()
        }
        val stateShapeValid = when (result.state) {
            KWebExtensionRuntimeState.UNKNOWN,
            KWebExtensionRuntimeState.ABSENT,
            -> result.version == null && result.path == null
            else -> !result.version.isNullOrBlank() && result.path != null
        }
        if (result.operation == request.operation && result.extensionId == request.extensionId &&
            errorsValid && stateShapeValid
        ) {
            return null
        }
        return KWebExtensionLifecycleFailure(
            code = "extensions.lifecycle.runtime-result-invalid",
            details = mapOf(
                "expectedOperation" to request.operation.name,
                "actualOperation" to result.operation.name,
                "expectedExtensionId" to request.extensionId,
                "actualExtensionId" to result.extensionId,
            ),
            message = "The Chromium extension runtime returned a result that violates the lifecycle contract.",
        )
    }

    private fun identityFailure(
        transaction: JvmKWebExtensionStoreTransaction,
        result: KWebExtensionRuntimeResult,
    ): KWebExtensionLifecycleFailure = KWebExtensionLifecycleFailure(
        code = "extensions.lifecycle.runtime-identity-conflict",
        details = mapOf(
            "extensionId" to result.extensionId,
            "operation" to transaction.operation.name,
            "runtimeState" to result.state.name,
            "runtimeVersion" to (result.version ?: "absent"),
            "runtimePath" to (result.path?.toString() ?: "absent"),
            "managedVersion" to transaction.extension.packageInfo.manifest.version,
            "managedPath" to transaction.extension.directory.toString(),
        ),
        message = "Chromium extension state conflicts with the prepared managed-store transaction.",
    )

    private fun lifecycleResult(
        transaction: JvmKWebExtensionStoreTransaction,
        resolution: KWebExtensionLifecycleResolution,
        runtimeResult: KWebExtensionRuntimeResult?,
        failure: KWebExtensionLifecycleFailure?,
    ): KWebExtensionLifecycleResult = KWebExtensionLifecycleResult(
        transactionToken = transaction.token,
        operation = transaction.operation.toRuntimeOperation(),
        extensionId = transaction.extension.packageInfo.extensionId,
        resolution = resolution,
        runtimeResult = runtimeResult,
        failure = failure,
    )

    public companion object {
        public fun open(
            storeRoot: Path,
            runtime: KWebExtensionRuntime,
            permissionPolicy: KWebExtensionPermissionPolicy = KWebExtensionPermissionPolicy(),
            operationTimeout: Duration = Duration.ofSeconds(30),
        ): JvmKWebExtensionLifecycleCoordinator {
            val timeoutMillis = try {
                operationTimeout.toMillis()
            } catch (error: ArithmeticException) {
                extensionFailure(
                    code = "extensions.lifecycle.timeout-invalid",
                    details = mapOf("timeout" to operationTimeout.toString()),
                    message = "The extension lifecycle timeout is outside the supported millisecond range.",
                    cause = error,
                )
            }
            if (timeoutMillis <= 0) {
                extensionFailure(
                    code = "extensions.lifecycle.timeout-invalid",
                    details = mapOf("timeout" to operationTimeout.toString()),
                    message = "The extension lifecycle timeout must be positive.",
                )
            }
            return JvmKWebExtensionLifecycleCoordinator(
                store = JvmKWebExtensionProfileStore.open(storeRoot, permissionPolicy),
                runtime = runtime,
                operationTimeoutMillis = timeoutMillis,
            )
        }
    }
}

private fun JvmKWebExtensionStoreTransaction.toRuntimeRequest(): KWebExtensionRuntimeRequest =
    KWebExtensionRuntimeRequest(
        operation = operation.toRuntimeOperation(),
        extensionId = extension.packageInfo.extensionId,
        expectedVersion = extension.packageInfo.manifest.version,
        extensionPath = extension.directory,
    )

private fun JvmKWebExtensionStoreOperation.toRuntimeOperation(): KWebExtensionRuntimeOperation = when (this) {
    JvmKWebExtensionStoreOperation.INSTALL -> KWebExtensionRuntimeOperation.INSTALL
    JvmKWebExtensionStoreOperation.UPDATE -> KWebExtensionRuntimeOperation.UPDATE
    JvmKWebExtensionStoreOperation.RELOAD -> KWebExtensionRuntimeOperation.RELOAD
    JvmKWebExtensionStoreOperation.UNINSTALL -> KWebExtensionRuntimeOperation.UNINSTALL
}

private fun KWebExtensionRuntimeResult.matches(extension: JvmKWebManagedExtension): Boolean =
    state == KWebExtensionRuntimeState.ENABLED &&
        extensionId == extension.packageInfo.extensionId &&
        version == extension.packageInfo.manifest.version &&
        path == extension.directory

private fun Throwable.toLifecycleFailure(): KWebExtensionLifecycleFailure =
    if (this is KWebException) {
        KWebExtensionLifecycleFailure(code, details, message ?: code)
    } else {
        KWebExtensionLifecycleFailure(
            code = "extensions.lifecycle.runtime-call-failed",
            details = mapOf("exception" to this::class.java.name),
            message = message ?: "The Chromium extension runtime call failed.",
        )
    }

private fun KWebExtensionRuntimeResult.toLifecycleFailure(): KWebExtensionLifecycleFailure =
    KWebExtensionLifecycleFailure(
        code = errorCode ?: "extensions.lifecycle.runtime-ambiguous",
        details = mapOf(
            "extensionId" to extensionId,
            "operation" to operation.name,
            "outcome" to outcome.name,
            "state" to state.name,
        ),
        message = errorMessage ?: "Chromium could not prove the extension lifecycle outcome.",
    )
