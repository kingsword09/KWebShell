package io.github.kingsword09.kwebshell.extensions

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPair
import java.time.Duration
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking

class JvmKWebExtensionLifecycleCoordinatorTest {
    @Test
    fun commitsCompleteInstallUpdateReloadAndUninstallLifecycle() = withTempDirectory { root ->
        val source = root.resolve("source")
        writePackage(source, "1.0.0", "const version = 1;")
        val runtime = StatefulRuntime()
        val storeRoot = root.resolve("store")
        val coordinator = JvmKWebExtensionLifecycleCoordinator.open(storeRoot, runtime)

        runBlocking {
            assertEquals(
                KWebExtensionLifecycleResolution.COMMITTED,
                coordinator.installUnpacked(source).resolution,
            )
            writePackage(source, "2.0.0", "const version = 2;")
            assertEquals(
                KWebExtensionLifecycleResolution.COMMITTED,
                coordinator.installUnpacked(source).resolution,
            )
            assertEquals(
                KWebExtensionLifecycleResolution.COMMITTED,
                coordinator.reload(extensionId()).resolution,
            )
            assertEquals(
                KWebExtensionLifecycleResolution.COMMITTED,
                coordinator.uninstall(extensionId()).resolution,
            )
        }

        val store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertNull(store.active(extensionId()))
        assertTrue(store.pendingTransactions().isEmpty())
        assertEquals(
            listOf(
                KWebExtensionRuntimeOperation.INSTALL,
                KWebExtensionRuntimeOperation.UPDATE,
                KWebExtensionRuntimeOperation.RELOAD,
                KWebExtensionRuntimeOperation.UNINSTALL,
            ),
            runtime.requests.map(KWebExtensionRuntimeRequest::operation),
        )
    }

    @Test
    fun abortsOnlyWhenRejectedResultProvesThePreviousState() = withTempDirectory { root ->
        val source = root.resolve("source")
        writePackage(source, "1.0.0", "const version = 1;")
        val storeRoot = root.resolve("store")
        val coordinator = JvmKWebExtensionLifecycleCoordinator.open(
            storeRoot,
            KWebExtensionRuntime { request ->
                runtimeResult(
                    request,
                    KWebExtensionRuntimeOutcome.REJECTED,
                    KWebExtensionRuntimeState.ABSENT,
                    errorCode = "install-policy-rejected",
                    errorMessage = "Chromium left the absent state unchanged.",
                )
            },
        )

        val result = runBlocking { coordinator.installUnpacked(source) }

        assertEquals(KWebExtensionLifecycleResolution.ABORTED, result.resolution)
        assertEquals("install-policy-rejected", result.failure?.code)
        assertTrue(coordinator.pendingExtensionIds().isEmpty())
    }

    @Test
    fun retainsAmbiguityThenCommitsOrAbortsFromRestartQueryEvidence() = withTempDirectory { root ->
        val source = root.resolve("source")
        writePackage(source, "1.0.0", "const version = 1;")
        var target: KWebExtensionRuntimeRequest? = null
        var queryShowsTarget = true
        val runtime = KWebExtensionRuntime { request ->
            when (request.operation) {
                KWebExtensionRuntimeOperation.INSTALL -> {
                    target = request
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.AMBIGUOUS,
                        KWebExtensionRuntimeState.UNKNOWN,
                        errorCode = "callback-lost",
                        errorMessage = "The install callback was lost after dispatch.",
                    )
                }
                KWebExtensionRuntimeOperation.QUERY -> if (queryShowsTarget) {
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.SUCCESS,
                        KWebExtensionRuntimeState.ENABLED,
                        version = target?.expectedVersion,
                        path = target?.extensionPath,
                    )
                } else {
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.SUCCESS,
                        KWebExtensionRuntimeState.ABSENT,
                    )
                }
                else -> error("Unexpected operation ${request.operation}")
            }
        }
        val committedRoot = root.resolve("committed-store")
        var coordinator = JvmKWebExtensionLifecycleCoordinator.open(committedRoot, runtime)

        val ambiguous = runBlocking { coordinator.installUnpacked(source) }
        assertEquals(KWebExtensionLifecycleResolution.RETAINED, ambiguous.resolution)
        assertEquals(setOf(extensionId()), coordinator.pendingExtensionIds())

        coordinator = JvmKWebExtensionLifecycleCoordinator.open(committedRoot, runtime)
        val reconciled = runBlocking { coordinator.reconcile() }.single()
        assertEquals(KWebExtensionLifecycleResolution.COMMITTED, reconciled.resolution)
        assertEquals("1.0.0", JvmKWebExtensionProfileStore.open(committedRoot).active(extensionId())?.packageInfo?.manifest?.version)

        queryShowsTarget = false
        target = null
        val abortedRoot = root.resolve("aborted-store")
        coordinator = JvmKWebExtensionLifecycleCoordinator.open(abortedRoot, runtime)
        assertEquals(
            KWebExtensionLifecycleResolution.RETAINED,
            runBlocking { coordinator.installUnpacked(source) }.resolution,
        )
        val aborted = runBlocking { coordinator.reconcile() }.single()
        assertEquals(KWebExtensionLifecycleResolution.ABORTED, aborted.resolution)
        assertNull(JvmKWebExtensionProfileStore.open(abortedRoot).active(extensionId()))
    }

    @Test
    fun retriesReloadDuringReconciliationAndRetainsConflictingState() = withTempDirectory { root ->
        val source = root.resolve("source")
        writePackage(source, "1.0.0", "const version = 1;")
        val runtime = StatefulRuntime()
        val storeRoot = root.resolve("store")
        var coordinator = JvmKWebExtensionLifecycleCoordinator.open(storeRoot, runtime)
        runBlocking { coordinator.installUnpacked(source) }
        runtime.ambiguousReload = true

        val reload = runBlocking { coordinator.reload(extensionId()) }
        assertEquals(KWebExtensionLifecycleResolution.RETAINED, reload.resolution)
        runtime.ambiguousReload = false
        coordinator = JvmKWebExtensionLifecycleCoordinator.open(storeRoot, runtime)

        val reconciled = runBlocking { coordinator.reconcile() }.single()

        assertEquals(KWebExtensionLifecycleResolution.COMMITTED, reconciled.resolution)
        assertEquals(
            listOf(KWebExtensionRuntimeOperation.QUERY, KWebExtensionRuntimeOperation.RELOAD),
            runtime.requests.takeLast(2).map(KWebExtensionRuntimeRequest::operation),
        )

        val conflictRoot = root.resolve("conflict-store")
        var target: KWebExtensionRuntimeRequest? = null
        val conflictRuntime = KWebExtensionRuntime { request ->
            if (request.operation == KWebExtensionRuntimeOperation.INSTALL) {
                target = request
                runtimeResult(
                    request,
                    KWebExtensionRuntimeOutcome.AMBIGUOUS,
                    KWebExtensionRuntimeState.UNKNOWN,
                    errorCode = "process-exited",
                    errorMessage = "The process exited after dispatch.",
                )
            } else {
                runtimeResult(
                    request,
                    KWebExtensionRuntimeOutcome.SUCCESS,
                    KWebExtensionRuntimeState.DISABLED,
                    version = target?.expectedVersion,
                    path = target?.extensionPath,
                )
            }
        }
        coordinator = JvmKWebExtensionLifecycleCoordinator.open(conflictRoot, conflictRuntime)
        runBlocking { coordinator.installUnpacked(source) }

        val conflict = runBlocking { coordinator.reconcile() }.single()

        assertEquals(KWebExtensionLifecycleResolution.RETAINED, conflict.resolution)
        assertEquals("extensions.lifecycle.runtime-identity-conflict", conflict.failure?.code)
        assertEquals(setOf(extensionId()), coordinator.pendingExtensionIds())
    }

    @Test
    fun distinguishesPreDispatchFailureFromTimeoutAndAcceptedFailure() = withTempDirectory { root ->
        val source = root.resolve("source")
        writePackage(source, "1.0.0", "const version = 1;")
        val notDispatchedRoot = root.resolve("not-dispatched")
        val notDispatched = JvmKWebExtensionLifecycleCoordinator.open(
            notDispatchedRoot,
            failingRuntime(KWebExtensionRuntimeDispatchState.NOT_DISPATCHED),
        )

        val aborted = runBlocking { notDispatched.installUnpacked(source) }
        assertEquals(KWebExtensionLifecycleResolution.ABORTED, aborted.resolution)
        assertTrue(notDispatched.pendingExtensionIds().isEmpty())

        val acceptedRoot = root.resolve("accepted")
        val accepted = JvmKWebExtensionLifecycleCoordinator.open(
            acceptedRoot,
            failingRuntime(KWebExtensionRuntimeDispatchState.MAY_HAVE_DISPATCHED),
        )
        val retained = runBlocking { accepted.installUnpacked(source) }
        assertEquals(KWebExtensionLifecycleResolution.RETAINED, retained.resolution)
        assertEquals(setOf(extensionId()), accepted.pendingExtensionIds())

        val timeoutRoot = root.resolve("timeout")
        val timeout = JvmKWebExtensionLifecycleCoordinator.open(
            timeoutRoot,
            KWebExtensionRuntime { awaitCancellation() },
            operationTimeout = Duration.ofMillis(25),
        )
        val timeoutError = runBlocking {
            try {
                timeout.installUnpacked(source)
                null
            } catch (error: TimeoutCancellationException) {
                error
            }
        }
        assertIs<TimeoutCancellationException>(timeoutError)
        assertEquals(setOf(extensionId()), timeout.pendingExtensionIds())
    }

    private inner class StatefulRuntime : KWebExtensionRuntime {
        val requests = mutableListOf<KWebExtensionRuntimeRequest>()
        var installed: KWebExtensionRuntimeRequest? = null
        var ambiguousReload: Boolean = false

        override suspend fun execute(request: KWebExtensionRuntimeRequest): KWebExtensionRuntimeResult {
            requests += request
            return when (request.operation) {
                KWebExtensionRuntimeOperation.INSTALL,
                KWebExtensionRuntimeOperation.UPDATE,
                -> {
                    installed = request
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.SUCCESS,
                        KWebExtensionRuntimeState.ENABLED,
                        version = request.expectedVersion,
                        path = request.extensionPath,
                    )
                }
                KWebExtensionRuntimeOperation.RELOAD -> {
                    val current = requireNotNull(installed)
                    if (ambiguousReload) {
                        runtimeResult(
                            request,
                            KWebExtensionRuntimeOutcome.AMBIGUOUS,
                            KWebExtensionRuntimeState.ENABLED,
                            version = current.expectedVersion,
                            path = current.extensionPath,
                            errorCode = "reload-callback-lost",
                            errorMessage = "The reload callback was lost after dispatch.",
                        )
                    } else {
                        runtimeResult(
                            request,
                            KWebExtensionRuntimeOutcome.SUCCESS,
                            KWebExtensionRuntimeState.ENABLED,
                            version = current.expectedVersion,
                            path = current.extensionPath,
                        )
                    }
                }
                KWebExtensionRuntimeOperation.UNINSTALL -> {
                    installed = null
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.SUCCESS,
                        KWebExtensionRuntimeState.ABSENT,
                    )
                }
                KWebExtensionRuntimeOperation.QUERY -> installed?.let { current ->
                    runtimeResult(
                        request,
                        KWebExtensionRuntimeOutcome.SUCCESS,
                        KWebExtensionRuntimeState.ENABLED,
                        version = current.expectedVersion,
                        path = current.extensionPath,
                    )
                } ?: runtimeResult(
                    request,
                    KWebExtensionRuntimeOutcome.SUCCESS,
                    KWebExtensionRuntimeState.ABSENT,
                )
            }
        }
    }

    private fun failingRuntime(dispatchState: KWebExtensionRuntimeDispatchState): KWebExtensionRuntime =
        KWebExtensionRuntime {
            throw KWebExtensionRuntimeException(
                dispatchState = dispatchState,
                code = "native.abi.extension-runtime-abi-missing",
                details = emptyMap(),
                message = "The pinned CEF extension adapter is unavailable.",
            )
        }

    private fun runtimeResult(
        request: KWebExtensionRuntimeRequest,
        outcome: KWebExtensionRuntimeOutcome,
        state: KWebExtensionRuntimeState,
        version: String? = null,
        path: Path? = null,
        errorCode: String? = null,
        errorMessage: String? = null,
    ): KWebExtensionRuntimeResult = KWebExtensionRuntimeResult(
        operation = request.operation,
        outcome = outcome,
        state = state,
        extensionId = request.extensionId,
        version = version,
        path = path,
        errorCode = errorCode,
        errorMessage = errorMessage,
    )

    private fun writePackage(root: Path, version: String, worker: String, keyPair: KeyPair = TEST_KEY_PAIR) {
        Files.createDirectories(root)
        val key = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Lifecycle Fixture","version":"$version","key":"$key","background":{"service_worker":"worker.js"}}""",
        )
        root.resolve("worker.js").writeText(worker)
    }

    private fun extensionId(): String = JvmKWebCrx3TestFixture.extensionId(TEST_KEY_PAIR)

    private inline fun <T> withTempDirectory(operation: (Path) -> T): T {
        val root = Files.createTempDirectory("kweb-extension-lifecycle-test-").toRealPath()
        try {
            return operation(root)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private companion object {
        val TEST_KEY_PAIR: KeyPair = JvmKWebCrx3TestFixture.rsaKeyPair()
    }
}
