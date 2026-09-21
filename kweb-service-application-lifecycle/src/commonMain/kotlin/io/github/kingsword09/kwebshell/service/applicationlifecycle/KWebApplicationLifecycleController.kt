package io.github.kingsword09.kwebshell.service.applicationlifecycle

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

public sealed interface KWebApplicationBackendStart {
    public data object Primary : KWebApplicationBackendStart
    public data object SecondaryForwarded : KWebApplicationBackendStart
}

/**
 * Platform boundary for the typed lifecycle coordinator. Native providers call
 * [onActivation] from a provider-owned coroutine and never expose native
 * handles or authentication material to the common contract.
 */
public interface KWebApplicationLifecycleBackend {
    public suspend fun acquire(
        configuration: KWebApplicationLifecycleConfiguration,
        initial: KWebActivationBatch,
        onActivation: suspend (KWebActivationBatch) -> Unit,
    ): KWebApplicationBackendStart

    public suspend fun release()

    public suspend fun relaunch(preservePendingActivation: Boolean): KWebRelaunchResult

    public suspend fun installAssociations(): KWebApplicationRegistrationReport

    public suspend fun removeAssociations(): KWebApplicationRegistrationReport
}

public class KWebApplicationLifecycleController(
    private val configuration: KWebApplicationLifecycleConfiguration,
    private val backend: KWebApplicationLifecycleBackend,
) : KWebApplicationLifecycle {
    private val lock = Mutex()
    private val eventLock = Mutex()
    private val mutableState = MutableStateFlow(KWebApplicationLifecycleState.NEW)
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val mutableEvents = MutableSharedFlow<KWebApplicationEvent>(
        replay = configuration.activationCapacity,
        extraBufferCapacity = 0,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val participants = linkedMapOf<String, KWebApplicationShutdownParticipant>()
    private val pendingActivations = mutableListOf<KWebActivationBatch>()
    private var sequence: ULong = 0u
    private var quitResult: CompletableDeferred<KWebQuitResult>? = null
    private var quitReason: KWebQuitReason? = null
    private var terminalFailure: Throwable? = null

    override val descriptor: io.github.kingsword09.kwebshell.services.KWebServiceDescriptor =
        KWebApplicationLifecycle.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
    override val state: StateFlow<KWebApplicationLifecycleState> = mutableState.asStateFlow()
    override val events: SharedFlow<KWebApplicationEvent> = mutableEvents.asSharedFlow()

    override suspend fun start(initial: KWebActivationBatch): KWebApplicationStartResult {
        val canonicalInitial = KWebApplicationActivationCanonicalizer.canonicalize(
            configuration,
            initial,
        )
        lock.withLock {
            when (mutableState.value) {
                KWebApplicationLifecycleState.NEW -> mutableState.value = KWebApplicationLifecycleState.STARTING
                else -> lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.ALREADY_STARTED,
                    message = "The application lifecycle has already been started.",
                )
            }
        }

        val result = try {
            backend.acquire(configuration, canonicalInitial) { activation ->
                acceptActivation(activation)
            }
        } catch (error: Throwable) {
            lock.withLock {
                mutableState.value = KWebApplicationLifecycleState.FAILED
                mutableLifecycle.value = KWebLifecycleState.FAILED
                terminalFailure = error
            }
            throw error
        }

        return when (result) {
            KWebApplicationBackendStart.Primary -> {
                val pending = lock.withLock {
                    if (mutableState.value != KWebApplicationLifecycleState.STARTING) {
                        lifecycleFailure(
                            code = KWebApplicationLifecycleErrorCode.CLOSING,
                            message = "The application lifecycle changed while it was starting.",
                        )
                    }
                    mutableState.value = KWebApplicationLifecycleState.PRIMARY_READY
                    pendingActivations.toList().also { pendingActivations.clear() }
                }
                emitEvent(KWebApplicationEvent.Ready)
                emitActivation(canonicalInitial)
                pending.forEach { emitActivation(it) }
                KWebApplicationStartResult.PRIMARY
            }

            KWebApplicationBackendStart.SecondaryForwarded -> {
                lock.withLock {
                    mutableState.value = KWebApplicationLifecycleState.SECONDARY_FORWARDED
                    mutableState.value = KWebApplicationLifecycleState.CLOSED
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                }
                KWebApplicationStartResult.SECONDARY_FORWARDED
            }
        }
    }

    override suspend fun requestQuit(reason: KWebQuitReason): KWebQuitResult {
        val decision = lock.withLock {
            when (mutableState.value) {
                KWebApplicationLifecycleState.NEW -> {
                    mutableState.value = KWebApplicationLifecycleState.CLOSED
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                    QuitDecision(CompletableDeferred(KWebQuitResult.ALREADY_CLOSED), false)
                }

                KWebApplicationLifecycleState.CLOSED,
                KWebApplicationLifecycleState.SECONDARY_FORWARDED,
                -> QuitDecision(CompletableDeferred(KWebQuitResult.ALREADY_CLOSED), false)

                KWebApplicationLifecycleState.FAILED ->
                    QuitDecision(CompletableDeferred(KWebQuitResult.FAILED), false)

                KWebApplicationLifecycleState.QUIESCING ->
                    QuitDecision(quitResult ?: CompletableDeferred(KWebQuitResult.FAILED), false)

                KWebApplicationLifecycleState.STARTING,
                KWebApplicationLifecycleState.PRIMARY_READY,
                -> {
                    val deferred = CompletableDeferred<KWebQuitResult>()
                    quitResult = deferred
                    quitReason = reason
                    mutableState.value = KWebApplicationLifecycleState.QUIESCING
                    QuitDecision(deferred, true)
                }
            }
        }

        if (!decision.perform) return decision.result.await()

        val firstReason = checkNotNull(lock.withLock { quitReason })
        emitEvent(KWebApplicationEvent.QuitStarted(firstReason))
        val result = try {
            withTimeout(configuration.shutdownTimeoutMillis) {
                shutdownParticipants(firstReason)
                backend.release()
                KWebQuitResult.GRACEFUL
            }
        } catch (error: KWebApplicationLifecycleException) {
            if (error.code == KWebApplicationLifecycleErrorCode.QUIT_VETOED) {
                KWebQuitResult.VETOED
            } else {
                terminalFailure = error
                KWebQuitResult.FAILED
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            terminalFailure = KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.QUIT_TIMEOUT,
                details = mapOf("timeoutMillis" to configuration.shutdownTimeoutMillis.toString()),
                message = "The application shutdown sequence exceeded its deadline.",
                cause = error,
            )
            KWebQuitResult.FAILED
        } catch (error: Throwable) {
            terminalFailure = error
            KWebQuitResult.FAILED
        }

        lock.withLock {
            when (result) {
                KWebQuitResult.GRACEFUL -> {
                    mutableState.value = KWebApplicationLifecycleState.CLOSED
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                }

                KWebQuitResult.VETOED -> {
                    mutableState.value = KWebApplicationLifecycleState.PRIMARY_READY
                    mutableLifecycle.value = KWebLifecycleState.OPEN
                }

                else -> {
                    mutableState.value = KWebApplicationLifecycleState.FAILED
                    mutableLifecycle.value = KWebLifecycleState.FAILED
                }
            }
        }
        emitEvent(KWebApplicationEvent.Closed(result))
        decision.result.complete(result)
        return result
    }

    override suspend fun requestRelaunch(): KWebRelaunchResult {
        lock.withLock {
            if (mutableState.value != KWebApplicationLifecycleState.PRIMARY_READY) {
                lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CLOSING,
                    message = "Relaunch is available only while the primary application is ready.",
                )
            }
            if (!configuration.isPackaged || configuration.relaunchExecutable.isNullOrBlank()) {
                return KWebRelaunchResult.UNAVAILABLE
            }
        }
        return backend.relaunch(preservePendingActivation = false)
    }

    override suspend fun installAssociations(): KWebApplicationRegistrationReport {
        lock.withLock {
            if (mutableState.value != KWebApplicationLifecycleState.PRIMARY_READY) {
                lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CLOSING,
                    message = "Application associations can be installed only by the primary ready owner.",
                )
            }
        }
        return backend.installAssociations()
    }

    override suspend fun removeAssociations(): KWebApplicationRegistrationReport {
        lock.withLock {
            if (mutableState.value != KWebApplicationLifecycleState.PRIMARY_READY) {
                lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CLOSING,
                    message = "Application associations can be removed only by the primary ready owner.",
                )
            }
        }
        return backend.removeAssociations()
    }

    override fun registerShutdownParticipant(
        participant: KWebApplicationShutdownParticipant,
    ): KWebApplicationShutdownRegistration {
        require(participant.id.isNotBlank()) { "A shutdown participant requires a stable id." }
        lock.tryLockOrThrow {
            if (mutableState.value != KWebApplicationLifecycleState.NEW &&
                mutableState.value != KWebApplicationLifecycleState.PRIMARY_READY
            ) {
                lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CLOSING,
                    message = "Shutdown participants cannot be registered after quiescence begins.",
                )
            }
            if (participants.putIfAbsent(participant.id, participant) != null) {
                lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CONFIGURATION_INVALID,
                    message = "A shutdown participant id is already registered.",
                )
            }
        }
        return object : KWebApplicationShutdownRegistration {
            override fun unregister() {
                lock.tryLockOrThrow { participants.remove(participant.id, participant) }
            }
        }
    }

    override fun close() {
        runBlocking(Dispatchers.Default) {
            requestQuit(KWebQuitReason.SHUTDOWN)
        }
    }

    public fun terminalFailure(): Throwable? = terminalFailure

    private suspend fun acceptActivation(activation: KWebActivationBatch) {
        val canonical = KWebApplicationActivationCanonicalizer.canonicalize(configuration, activation)
        val shouldQueue = lock.withLock {
            when (mutableState.value) {
                KWebApplicationLifecycleState.STARTING -> {
                    if (pendingActivations.size >= configuration.activationCapacity) {
                        lifecycleFailure(
                            code = KWebApplicationLifecycleErrorCode.QUEUE_FULL,
                            message = "The bounded activation queue is full.",
                        )
                    }
                    pendingActivations += canonical
                    true
                }

                KWebApplicationLifecycleState.PRIMARY_READY -> false
                else -> lifecycleFailure(
                    code = KWebApplicationLifecycleErrorCode.CLOSING,
                    message = "The application lifecycle is not accepting activations.",
                )
            }
        }
        if (!shouldQueue) emitActivation(canonical)
    }

    private suspend fun emitActivation(batch: KWebActivationBatch) {
        eventLock.withLock {
            val event = lock.withLock {
                sequence += 1u
                KWebApplicationEvent.Activation(sequence, batch)
            }
            mutableEvents.emit(event)
        }
    }

    private suspend fun emitEvent(event: KWebApplicationEvent) {
        eventLock.withLock {
            mutableEvents.emit(event)
        }
    }

    private suspend fun shutdownParticipants(reason: KWebQuitReason) {
        val ordered = lock.withLock { participants.values.sortedByDescending { it.order } }
        for (participant in ordered) {
            if (participant.requestClose(reason) == KWebShutdownVote.DENY) {
                lock.withLock { mutableState.value = KWebApplicationLifecycleState.PRIMARY_READY }
                throw KWebApplicationLifecycleException(
                    code = KWebApplicationLifecycleErrorCode.QUIT_VETOED,
                    details = mapOf("participant" to participant.id),
                    message = "The application shutdown was vetoed by '${participant.id}'.",
                )
            }
        }
        ordered.forEach { it.close(reason) }
    }

    private fun lifecycleFailure(code: String, message: String): Nothing = throw KWebApplicationLifecycleException(
        code = code,
        details = emptyMap(),
        message = message,
        cause = terminalFailure,
    )

    private inline fun <T> Mutex.tryLockOrThrow(block: () -> T): T {
        if (!tryLock()) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.CLOSING,
                details = emptyMap(),
                message = "The lifecycle coordinator is busy with another transition.",
            )
        }
        return try {
            block()
        } finally {
            unlock()
        }
    }

    private data class QuitDecision(
        val result: CompletableDeferred<KWebQuitResult>,
        val perform: Boolean,
    )
}

public object KWebApplicationActivationCanonicalizer {
    public fun canonicalize(
        configuration: KWebApplicationLifecycleConfiguration,
        batch: KWebActivationBatch,
    ): KWebActivationBatch {
        if (batch.uris.size + batch.files.size == 0 || batch.files.size > configuration.maximumFileCount) {
            invalid("An activation contains no payload or too many files.")
        }
        val uris = batch.uris.map { uri -> canonicalUri(configuration, uri) }
        val files = batch.files.map { file ->
            val path = canonicalPath(configuration, file.absolutePath)
            file.copy(absolutePath = path)
        }
        return batch.copy(uris = uris, files = files)
    }

    private fun canonicalUri(configuration: KWebApplicationLifecycleConfiguration, value: String): String {
        requireUnicodeScalarString(value)
        val bytes = value.encodeToByteArray()
        if (bytes.size > configuration.maximumUriBytes || value.any { it.code <= 0x1f || it.code == 0x7f }) {
            invalid("A URI is oversized or contains a control character.")
        }
        val colon = value.indexOf(':')
        if (colon <= 0) invalid("A URI must contain a scheme.")
        val scheme = value.substring(0, colon).lowercase()
        if (scheme !in configuration.registeredSchemes) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.UNREGISTERED_SCHEME,
                details = mapOf("scheme" to scheme),
                message = "The URI scheme is not registered for this application.",
            )
        }
        var index = colon + 1
        while (index < value.length) {
            if (value[index] == '%') {
                if (index + 2 >= value.length || value[index + 1].digitToIntOrNull(16) == null ||
                    value[index + 2].digitToIntOrNull(16) == null
                ) {
                    invalid("A URI contains a malformed percent escape.")
                }
                index += 3
            } else {
                index += 1
            }
        }
        val remainder = value.substring(colon + 1)
        if (remainder.startsWith("//")) {
            val authority = remainder.substring(2).substringBeforeAny('/', '?', '#')
            if (authority.contains('@')) invalid("A URI containing user-info is not accepted.")
        }
        return scheme + ":" + value.substring(colon + 1)
    }

    private fun canonicalPath(configuration: KWebApplicationLifecycleConfiguration, value: String): String {
        requireUnicodeScalarString(value)
        val bytes = value.encodeToByteArray()
        val windowsAbsolute = value.length >= 3 && value[0].isLetter() && value[1] == ':' &&
            (value[2] == '/' || value[2] == '\\')
        if (bytes.size > configuration.maximumFilePathBytes || value.any { it == '\u0000' } ||
            (!value.startsWith('/') && !windowsAbsolute)
        ) {
            invalid("A file path must be absolute, bounded, and NUL-free.")
        }
        val normalized = value.replace('\\', '/')
        if (normalized.split('/').any { it == ".." || it == "." }) {
            invalid("A file path must already be normalized.")
        }
        return normalized
    }

    private fun requireUnicodeScalarString(value: String) {
        var index = 0
        while (index < value.length) {
            val code = value[index].code
            if (code in 0xd800..0xdbff) {
                if (index + 1 >= value.length || value[index + 1].code !in 0xdc00..0xdfff) {
                    invalid("Activation text contains an unpaired UTF-16 surrogate.")
                }
                index += 2
            } else if (code in 0xdc00..0xdfff) {
                invalid("Activation text contains an unpaired UTF-16 surrogate.")
            } else {
                index += 1
            }
        }
    }

    private fun String.substringBeforeAny(vararg delimiters: Char): String {
        val index = indexOfFirst { it in delimiters }
        return if (index < 0) this else substring(0, index)
    }

    private fun invalid(message: String): Nothing = throw KWebApplicationLifecycleException(
        code = KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID,
        details = emptyMap(),
        message = message,
    )
}
