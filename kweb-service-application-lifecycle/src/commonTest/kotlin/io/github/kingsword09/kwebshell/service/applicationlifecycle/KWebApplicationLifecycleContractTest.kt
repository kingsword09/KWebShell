package io.github.kingsword09.kwebshell.service.applicationlifecycle

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebApplicationLifecycleContractTest {
    @Test
    fun canonicalizesRegisteredUnicodeActivationAndReplaysOrderedEvents() = runBlocking {
        val backend = FakeBackend()
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)
        val initial = activation(KWebActivationSource.INITIAL_ARGUMENTS, "kweb://例子/文档")

        assertEquals(KWebApplicationStartResult.PRIMARY, lifecycle.start(initial))
        backend.emit(activation(KWebActivationSource.SECOND_INSTANCE, "kweb://例子/第二"))

        val events = lifecycle.events.take(3).toList()
        assertEquals(KWebApplicationEvent.Ready, events[0])
        assertEquals("kweb://例子/文档", (events[1] as KWebApplicationEvent.Activation).batch.uris.single())
        assertEquals("kweb://例子/第二", (events[2] as KWebApplicationEvent.Activation).batch.uris.single())
        assertEquals(1uL, (events[1] as KWebApplicationEvent.Activation).sequence)
        assertEquals(2uL, (events[2] as KWebApplicationEvent.Activation).sequence)
    }

    @Test
    fun rejectsUnregisteredSchemeMalformedUriAndUnnormalizedFile() = runBlocking {
        val backend = FakeBackend()
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)

        val scheme = assertFailsWith<KWebApplicationLifecycleException> {
            lifecycle.start(activation(KWebActivationSource.TEST, "https://example.test"))
        }
        assertEquals(KWebApplicationLifecycleErrorCode.UNREGISTERED_SCHEME, scheme.code)

        val malformed = assertFailsWith<KWebApplicationLifecycleException> {
            lifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/%ZZ"))
        }
        assertEquals(KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID, malformed.code)

        val path = assertFailsWith<KWebApplicationLifecycleException> {
            lifecycle.start(
                KWebActivationBatch(
                    source = KWebActivationSource.TEST,
                    files = listOf(KWebOpenedFile("/tmp/../unsafe.kweb", false)),
                ),
            )
        }
        assertEquals(KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID, path.code)
    }

    @Test
    fun secondaryProcessForwardsAndClosesWithoutRendererEvents() = runBlocking {
        val backend = FakeBackend(startResult = KWebApplicationBackendStart.SecondaryForwarded)
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)

        assertEquals(
            KWebApplicationStartResult.SECONDARY_FORWARDED,
            lifecycle.start(activation(KWebActivationSource.SECOND_INSTANCE, "kweb://example/forwarded")),
        )
        assertEquals(KWebApplicationLifecycleState.CLOSED, lifecycle.state.value)
        assertEquals(KWebLifecycleState.CLOSED, lifecycle.lifecycle.value)
        assertTrue(backend.releaseCalls == 0)
    }

    @Test
    fun quitParticipantsCloseInDescendingOrderAndVetoRestoresReady() = runBlocking {
        val backend = FakeBackend()
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)
        lifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/start"))
        val order = mutableListOf<String>()
        lifecycle.registerShutdownParticipant(participant("low", 1, order, KWebShutdownVote.ALLOW))
        lifecycle.registerShutdownParticipant(participant("high", 10, order, KWebShutdownVote.ALLOW))

        assertEquals(KWebQuitResult.GRACEFUL, lifecycle.requestQuit(KWebQuitReason.USER_REQUEST))
        assertEquals(listOf("high-vote", "low-vote", "high-close", "low-close"), order)
        assertEquals(KWebLifecycleState.CLOSED, lifecycle.lifecycle.value)
        assertEquals(1, backend.releaseCalls)

        val vetoBackend = FakeBackend()
        val vetoLifecycle = KWebApplicationLifecycleController(configuration(), vetoBackend)
        vetoLifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/start"))
        vetoLifecycle.registerShutdownParticipant(participant("veto", 1, mutableListOf(), KWebShutdownVote.DENY))
        assertEquals(KWebQuitResult.VETOED, vetoLifecycle.requestQuit(KWebQuitReason.USER_REQUEST))
        assertEquals(KWebApplicationLifecycleState.PRIMARY_READY, vetoLifecycle.state.value)
        assertEquals(0, vetoBackend.releaseCalls)
    }

    @Test
    fun rendererCannotUseLifecycleOperationBecauseDescriptorHasNoPermissionRoute() {
        val operation = KWebApplicationLifecycle.DESCRIPTOR.operations.single()
        assertEquals("activation-events", operation.id)
        assertEquals(null, operation.rendererPermission)
    }

    @Test
    fun concurrentQuitRequestsCoalesceToOneOrderedClose() = runBlocking {
        val backend = FakeBackend(releaseDelayMillis = 40)
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)
        lifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/start"))

        val results = coroutineScope {
            listOf(
                async { lifecycle.requestQuit(KWebQuitReason.USER_REQUEST) },
                async { lifecycle.requestQuit(KWebQuitReason.OS_REQUEST) },
            ).map { it.await() }
        }

        assertEquals(listOf(KWebQuitResult.GRACEFUL, KWebQuitResult.GRACEFUL), results)
        assertEquals(1, backend.releaseCalls)
        assertEquals(KWebApplicationLifecycleState.CLOSED, lifecycle.state.value)
    }

    @Test
    fun concurrentNativeActivationsRetainSequenceOrder() = runBlocking {
        val backend = FakeBackend()
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)
        lifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/start"))

        coroutineScope {
            (1..16).map { index ->
                async {
                    backend.emit(activation(KWebActivationSource.SECOND_INSTANCE, "kweb://example/$index"))
                }
            }.forEach { it.await() }
        }
        val activations = lifecycle.events.replayCache.filterIsInstance<KWebApplicationEvent.Activation>()
        assertEquals((1uL..17uL).toList(), activations.map { it.sequence })
    }

    @Test
    fun activationCodecRejectsInvalidUtf8() {
        val error = assertFailsWith<KWebApplicationLifecycleException> {
            KWebApplicationActivationCodec.decode(byteArrayOf(0x7b, 0x22, 0xc3.toByte(), 0x28))
        }
        assertEquals(KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID, error.code)
    }

    @Test
    fun canonicalizerRejectsUnpairedSurrogate() {
        val error = assertFailsWith<KWebApplicationLifecycleException> {
            KWebApplicationActivationCanonicalizer.canonicalize(
                configuration(),
                activation(KWebActivationSource.TEST, "kweb://example/\uD800"),
            )
        }
        assertEquals(KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID, error.code)
    }

    @Test
    fun associationMutationIsHostOnlyAndReturnsObservedProviderReport() = runBlocking {
        val backend = FakeBackend()
        val lifecycle = KWebApplicationLifecycleController(configuration(), backend)
        lifecycle.start(activation(KWebActivationSource.TEST, "kweb://example/start"))

        val installed = lifecycle.installAssociations()
        assertEquals(KWebApplicationRegistrationOperation.INSTALL, installed.operation)
        assertTrue(installed.registered)
        assertEquals("test", installed.provider)

        val removed = lifecycle.removeAssociations()
        assertEquals(KWebApplicationRegistrationOperation.REMOVE, removed.operation)
        assertTrue(!removed.registered)
    }

    private fun configuration(): KWebApplicationLifecycleConfiguration =
        KWebApplicationLifecycleConfiguration(
            applicationId = "io.github.kingsword09.kwebshell",
            target = KWebTarget.parse("linux-x64"),
            packageIdentity = "io.github.kwebshell.desktop",
            registeredSchemes = setOf("kweb"),
            registeredExtensions = setOf(".kweb"),
            packageRoot = "/opt/kwebshell",
            transportRoot = "/tmp/kwebshell-lifecycle",
            relaunchExecutable = "bin/KWebShell",
            isPackaged = true,
        )

    private fun activation(source: KWebActivationSource, uri: String): KWebActivationBatch =
        KWebActivationBatch(source = source, uris = listOf(uri))

    private fun participant(
        id: String,
        order: Int,
        events: MutableList<String>,
        vote: KWebShutdownVote,
    ): KWebApplicationShutdownParticipant = object : KWebApplicationShutdownParticipant {
        override val id: String = id
        override val order: Int = order
        override suspend fun requestClose(reason: KWebQuitReason): KWebShutdownVote {
            events += "$id-vote"
            return vote
        }

        override suspend fun close(reason: KWebQuitReason) {
            events += "$id-close"
        }
    }

    private class FakeBackend(
        private val startResult: KWebApplicationBackendStart = KWebApplicationBackendStart.Primary,
        private val releaseDelayMillis: Long = 0,
    ) : KWebApplicationLifecycleBackend {
        private var sink: (suspend (KWebActivationBatch) -> Unit)? = null
        var releaseCalls: Int = 0
            private set

        override suspend fun acquire(
            configuration: KWebApplicationLifecycleConfiguration,
            initial: KWebActivationBatch,
            onActivation: suspend (KWebActivationBatch) -> Unit,
        ): KWebApplicationBackendStart {
            sink = onActivation
            return startResult
        }

        suspend fun emit(batch: KWebActivationBatch) {
            sink?.invoke(batch)
        }

        override suspend fun release() {
            releaseCalls += 1
            if (releaseDelayMillis > 0) delay(releaseDelayMillis)
        }

        override suspend fun relaunch(preservePendingActivation: Boolean): KWebRelaunchResult =
            KWebRelaunchResult.ACCEPTED

        override suspend fun installAssociations(): KWebApplicationRegistrationReport =
            KWebApplicationRegistrationReport(
                operation = KWebApplicationRegistrationOperation.INSTALL,
                target = KWebTarget.parse("linux-x64"),
                applicationId = "io.github.kwebshell.kwebshell",
                provider = "test",
                registered = true,
                observedDigest = "test-install",
            )

        override suspend fun removeAssociations(): KWebApplicationRegistrationReport =
            KWebApplicationRegistrationReport(
                operation = KWebApplicationRegistrationOperation.REMOVE,
                target = KWebTarget.parse("linux-x64"),
                applicationId = "io.github.kwebshell.kwebshell",
                provider = "test",
                registered = false,
                observedDigest = "test-remove",
            )
    }
}
