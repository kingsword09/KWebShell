/**
 * The JVM managed-process session. It requires the packaged provider library and
 * the host-side declaration explicitly, owns process identity and stream delivery,
 * and reports one sticky close failure. There is no fallback provider.
 */
package io.github.kingsword09.kwebshell.service.processes

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.processes.internal.ProcessesFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

public class JvmKWebProcessesSession internal constructor(
    private val native: ProcessesFfm,
    private val declaration: KWebProcessDeclaration,
    private val scope: CoroutineScope,
) : KWebProcesses {

    override val descriptor: KWebServiceDescriptor = KWebProcesses.DESCRIPTOR

    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    private val gate = Mutex()
    private val open = mutableMapOf<KWebProcessId, JvmKWebManagedProcess>()
    private var closeFailure: KWebException? = null
    private var cachedCapabilities: KWebProcessCapabilities? = null

    override suspend fun capabilities(): KWebProcessCapabilities {
        requireOpen()
        cachedCapabilities?.let { return it }
        val result = translate("capabilities") { native.capabilities() }
        // The provider must implement exactly the published bounds; a mismatch is a
        // contract version problem, never a silently different limit.
        val published = KWebProcessesBounds.PUBLISHED
        if (result.maxLiveProcesses != published.maxLiveProcesses ||
            result.maxArguments != published.maxArguments ||
            result.maxEnvironment != published.maxEnvironment ||
            result.minTimeoutSeconds != published.minTimeoutSeconds ||
            result.maxTimeoutSeconds != published.maxTimeoutSeconds ||
            result.minOutputWindowBytes != published.minOutputWindowBytes ||
            result.maxOutputWindowBytes != published.maxOutputWindowBytes ||
            result.maxStdinChunkBytes != published.maxStdinChunkBytes ||
            result.minMemoryBytes != published.minMemoryBytes ||
            result.maxMemoryBytes != published.maxMemoryBytes ||
            result.minOutputQuotaBytes != published.minOutputQuotaBytes ||
            result.maxOutputQuotaBytes != published.maxOutputQuotaBytes
        ) {
            throw KWebServiceException(
                code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                details = mapOf("service" to descriptor.id, "provider" to result.providerId),
                message = "The packaged process provider publishes different bounds than the Kotlin contract.",
            )
        }
        val capabilities = KWebProcessCapabilities(
            providerId = result.providerId,
            declaredIsolation = KWebProcessIsolation.entries
                .filter { isolation -> (result.isolationBits and isolationCode(isolation)) != 0 }
                .toSet(),
            memoryMeasure = KWebProcessMemoryMeasure.RESIDENT,
            maxLiveProcesses = result.maxLiveProcesses,
            liveProcesses = result.liveProcesses,
            bounds = published,
        )
        cachedCapabilities = capabilities
        return capabilities
    }

    override suspend fun start(spec: KWebProcessSpec): KWebManagedProcess {
        requireOpen()
        val capabilities = capabilities()
        val unsupported = spec.requiredIsolation.filterNot(capabilities::supports)
        if (unsupported.isNotEmpty()) {
            throw failure(
                KWebProcessesErrorCode.ISOLATION_UNSUPPORTED,
                "start",
                mapOf(
                    "missing" to unsupported.map { it.name }.sorted().joinToString(),
                    "provider" to capabilities.providerId,
                ),
            )
        }
        val executable = declaration.executable(spec.executableId)
            ?: throw failure(
                KWebProcessesErrorCode.EXECUTABLE_UNKNOWN,
                "start",
                mapOf("executable" to spec.executableId.value),
            )
        val workingDirectory = declaration.workingDirectory(spec.workingDirectoryId)
            ?: throw failure(
                KWebProcessesErrorCode.WORKING_DIRECTORY_INVALID,
                "start",
                mapOf("workingDirectory" to spec.workingDirectoryId.value),
            )
        // A JVM worker declaration supplies its own launcher arguments; a packaged
        // executable uses exactly the declared arguments of this specification.
        val arguments = executable.workerArguments.ifEmpty { spec.arguments }
        val isolationBits = spec.requiredIsolation.fold(0) { bits, isolation -> bits or isolationCode(isolation) }
        val result = translate("start") {
            native.start(
                spec.id.value,
                executable.path.toString(),
                executable.sha256,
                workingDirectory.path.toString(),
                arguments,
                spec.environment.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" },
                isolationBits,
                spec.limits.timeoutSeconds,
                spec.limits.cpuSeconds,
                spec.streams.stdoutWindowBytes,
                spec.streams.stderrWindowBytes,
                spec.limits.memoryBytes,
                spec.limits.stdoutQuotaBytes,
                spec.limits.stderrQuotaBytes,
            )
        }
        val process = JvmKWebManagedProcess(
            id = spec.id,
            executableId = spec.executableId,
            handle = result.processHandle,
            native = native,
            scope = scope,
            maxStdinChunkBytes = capabilities.bounds.maxStdinChunkBytes,
        )
        gate.withLock {
            if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
                throw failure(KWebProcessesErrorCode.OWNER_CLOSED, "start", mapOf("state" to mutableLifecycle.value.name))
            }
            open[spec.id] = process
        }
        process.startMonitoring()
        return process
    }

    override suspend fun processes(): List<KWebProcessSummary> = gate.withLock {
        requireOpen()
        val summaries = mutableListOf<KWebProcessSummary>()
        val entries = open.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            if (entry.value.state.value == KWebProcessState.TERMINATED) {
                // An ended record is released here so a long-lived service never
                // accumulates handles for processes its owner already finished with.
                entry.value.close()
                entries.remove()
                continue
            }
            summaries += entry.value.summary()
        }
        summaries
    }

    override fun close() {
        val processes: List<JvmKWebManagedProcess>
        synchronized(this) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
            processes = open.values.toList()
        }
        var failure: KWebException? = null
        // Owner shutdown terminates every tree before the provider is released, so
        // no child can outlive the service.
        processes.forEach { process ->
            try {
                process.shutdown()
            } catch (error: KWebException) {
                if (failure == null) failure = error
            } catch (error: Throwable) {
                if (failure == null) {
                    failure = KWebServiceException(
                        code = KWebServiceErrorCode.NATIVE_FAILED,
                        details = mapOf("service" to descriptor.id, "process" to process.id.value),
                        message = "The process service failed to terminate a managed process.",
                        cause = error,
                    )
                }
            }
        }
        try {
            native.close()
        } catch (error: KWebException) {
            if (failure == null) failure = error
        } catch (error: Throwable) {
            if (failure == null) {
                failure = KWebServiceException(
                    code = KWebServiceErrorCode.NATIVE_FAILED,
                    details = mapOf("service" to descriptor.id, "operation" to "close"),
                    message = "The process provider failed while closing.",
                    cause = error,
                )
            }
        }
        scope.cancel()
        synchronized(this) {
            open.clear()
            closeFailure = failure
            mutableLifecycle.value = if (failure == null) KWebLifecycleState.CLOSED else KWebLifecycleState.FAILED
        }
        closeFailure?.let { throw it }
    }

    private fun requireOpen() {
        val state = mutableLifecycle.value
        if (state != KWebLifecycleState.OPEN) {
            throw failure(KWebProcessesErrorCode.OWNER_CLOSED, "require-open", mapOf("state" to state.name))
        }
    }

    internal fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to descriptor.id, "operation" to operation),
            message = "The process operation '$operation' failed with $code.",
        )

    /** Maps one provider status to its published typed service error. */
    private fun <T> translate(operation: String, block: () -> T): T = try {
        block()
    } catch (error: ProcessesFfm.ProcessNativeException) {
        throw failure(
            when (error.status) {
                ProcessesFfm.STATUS_ISOLATION_UNSUPPORTED -> KWebProcessesErrorCode.ISOLATION_UNSUPPORTED
                ProcessesFfm.STATUS_EXECUTABLE_MISSING -> KWebProcessesErrorCode.EXECUTABLE_MISSING
                ProcessesFfm.STATUS_EXECUTABLE_UNVERIFIED -> KWebProcessesErrorCode.EXECUTABLE_UNVERIFIED
                ProcessesFfm.STATUS_WORKING_DIRECTORY_INVALID -> KWebProcessesErrorCode.WORKING_DIRECTORY_INVALID
                ProcessesFfm.STATUS_LIMIT_EXCEEDED -> KWebProcessesErrorCode.LIMIT_EXCEEDED
                ProcessesFfm.STATUS_PROCESS_UNKNOWN -> KWebProcessesErrorCode.PROCESS_UNKNOWN
                ProcessesFfm.STATUS_STDIN_CLOSED -> KWebProcessesErrorCode.STDIN_CLOSED
                ProcessesFfm.STATUS_STDIN_TIMEOUT -> KWebProcessesErrorCode.STDIN_TIMEOUT
                ProcessesFfm.STATUS_BUFFER_TOO_SMALL -> KWebProcessesErrorCode.STDIN_BOUNDS
                ProcessesFfm.STATUS_OWNER_CLOSED -> KWebProcessesErrorCode.OWNER_CLOSED
                ProcessesFfm.STATUS_PLATFORM_UNAVAILABLE -> KWebProcessesErrorCode.PLATFORM_UNAVAILABLE
                ProcessesFfm.STATUS_LAUNCH_FAILED -> KWebProcessesErrorCode.LAUNCH_FAILED
                else -> KWebProcessesErrorCode.NATIVE_FAILED
            },
            operation,
            mapOf("native-status" to error.status.toString()),
        )
    }
}

/** One managed process owned by one application session. */
public class JvmKWebManagedProcess internal constructor(
    override val id: KWebProcessId,
    override val executableId: KWebProcessExecutableId,
    internal val handle: Long,
    private val native: ProcessesFfm,
    private val scope: CoroutineScope,
    private val maxStdinChunkBytes: Int,
) : KWebManagedProcess {

    private val mutableState = MutableStateFlow(KWebProcessState.RUNNING)
    override val state: StateFlow<KWebProcessState> = mutableState.asStateFlow()

    private val terminal = MutableSharedFlow<KWebProcessTermination>(replay = 1, extraBufferCapacity = 1)
    override val termination: SharedFlow<KWebProcessTermination> get() = terminal.asSharedFlow()

    /**
     * The ordered output stream of this process: a cold, single-consumer flow over
     * the provider's bounded window. The reader never buffers beyond that window, so
     * a consumer's own pace is the backpressure the child observes.
     */
    override val output: Flow<KWebProcessOutputChunk> = flow {
        if (!consuming.compareAndSet(false, true)) {
            throw failure(KWebProcessesErrorCode.OUTPUT_BOUNDS, "output", mapOf("reason" to "already-consuming"))
        }
        try {
            while (true) {
                val chunk = native.readOutput(handle, OUTPUT_POLL_MILLIS) ?: continue
                if (chunk.ended) break
                emit(
                    KWebProcessOutputChunk(
                        sequence = chunk.sequence,
                        stream = streamOf(chunk.stream),
                        bytes = chunk.bytes,
                        totalBytes = chunk.totalBytes,
                    ),
                )
            }
        } finally {
            consuming.set(false)
        }
    }

    private val consuming = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val terminalPublished = AtomicBoolean(false)
    private var monitor: Job? = null
    private var lastSummary: KWebProcessSummary? = null

    internal fun startMonitoring() {
        monitor = scope.launch(Dispatchers.IO) {
            while (true) {
                val termination = try {
                    native.waitFor(handle, MONITOR_POLL_MILLIS)
                } catch (error: Throwable) {
                    null
                }
                if (termination == null) continue
                val decoded = termination.toTermination()
                if (terminalPublished.compareAndSet(false, true)) {
                    mutableState.value = KWebProcessState.TERMINATED
                    lastSummary = summaryOf(termination)
                    terminal.tryEmit(decoded)
                }
                return@launch
            }
        }
    }

    override suspend fun writeStdin(bytes: ByteArray): Int {
        if (bytes.isEmpty()) {
            throw failure(KWebProcessesErrorCode.STDIN_BOUNDS, "write-stdin", mapOf("requested" to bytes.size.toString()))
        }
        if (bytes.size > maxStdinChunkBytes) {
            throw failure(
                KWebProcessesErrorCode.STDIN_BOUNDS,
                "write-stdin",
                mapOf("requested" to bytes.size.toString(), "maximum" to maxStdinChunkBytes.toString()),
            )
        }
        return try {
            native.writeStdin(handle, bytes, 0, bytes.size, STDIN_BUDGET_MILLIS).toInt()
        } catch (error: ProcessesFfm.ProcessNativeException) {
            throw failure(
                when (error.status) {
                    ProcessesFfm.STATUS_STDIN_TIMEOUT -> KWebProcessesErrorCode.STDIN_TIMEOUT
                    ProcessesFfm.STATUS_STDIN_CLOSED -> KWebProcessesErrorCode.STDIN_CLOSED
                    ProcessesFfm.STATUS_BUFFER_TOO_SMALL -> KWebProcessesErrorCode.STDIN_BOUNDS
                    else -> KWebProcessesErrorCode.NATIVE_FAILED
                },
                "write-stdin",
                mapOf("native-status" to error.status.toString()),
            )
        }
    }

    override suspend fun closeStdin() {
        try {
            native.closeStdin(handle)
        } catch (error: ProcessesFfm.ProcessNativeException) {
            if (error.status == ProcessesFfm.STATUS_STDIN_CLOSED) {
                throw failure(KWebProcessesErrorCode.STDIN_CLOSED, "close-stdin", emptyMap())
            }
            throw failure(KWebProcessesErrorCode.NATIVE_FAILED, "close-stdin", emptyMap())
        }
    }

    override suspend fun kill(): KWebProcessTermination {
        val termination = try {
            native.terminate(handle, false)
        } catch (error: ProcessesFfm.ProcessNativeException) {
            throw failure(
                when (error.status) {
                    ProcessesFfm.STATUS_PROCESS_UNKNOWN -> KWebProcessesErrorCode.PROCESS_UNKNOWN
                    ProcessesFfm.STATUS_OWNER_CLOSED -> KWebProcessesErrorCode.OWNER_CLOSED
                    else -> KWebProcessesErrorCode.NATIVE_FAILED
                },
                "kill",
                emptyMap(),
            )
        }
        publish(termination)
        return termination.toTermination()
    }

    override suspend fun await(): KWebProcessTermination = termination.first()

    internal suspend fun summary(): KWebProcessSummary {
        lastSummary?.let { if (mutableState.value == KWebProcessState.TERMINATED) return it }
        val status = native.status(handle)
        return status.toSummary()
    }

    private fun publish(termination: ProcessesFfm.Termination) {
        if (terminalPublished.compareAndSet(false, true)) {
            mutableState.value = KWebProcessState.TERMINATED
            lastSummary = summaryOf(termination)
            terminal.tryEmit(termination.toTermination())
        }
    }

    /** Owner shutdown: terminate the whole tree, stop monitoring, release the record. */
    internal fun shutdown() {
        runCatching { native.terminate(handle, true) }
        monitor?.cancel()
        release()
    }

    override fun close() {
        shutdown()
    }

    private fun release() {
        if (!released.compareAndSet(false, true)) return
        runCatching { native.release(handle) }
    }

    private fun summaryOf(termination: ProcessesFfm.Termination): KWebProcessSummary = KWebProcessSummary(
        id = id,
        executableId = executableId,
        state = KWebProcessState.TERMINATED,
        stdoutBytes = termination.stdoutBytes,
        stderrBytes = termination.stderrBytes,
        pendingOutputBytes = 0,
        cpuMillis = termination.cpuMillis,
        peakMemoryBytes = termination.peakMemoryBytes,
        durationMillis = termination.durationMillis,
    )

    private fun ProcessesFfm.Status.toSummary(): KWebProcessSummary = KWebProcessSummary(
        id = id,
        executableId = executableId,
        state = if (state == ProcessesFfm.STATE_RUNNING) KWebProcessState.RUNNING else KWebProcessState.TERMINATED,
        stdoutBytes = stdoutBytes,
        stderrBytes = stderrBytes,
        pendingOutputBytes = pendingOutputBytes,
        cpuMillis = cpuMillis,
        peakMemoryBytes = peakMemoryBytes,
        durationMillis = durationMillis,
    )

    private fun failure(code: String, operation: String, details: Map<String, String>): KWebServiceException =
        KWebServiceException(
            code = code,
            details = details + mapOf("service" to KWebProcesses.DESCRIPTOR.id, "process" to id.value, "operation" to operation),
            message = "The process operation '$operation' failed with $code.",
        )

    private companion object {
        const val OUTPUT_POLL_MILLIS: Int = 200

        const val MONITOR_POLL_MILLIS: Int = 200

        const val STDIN_BUDGET_MILLIS: Int = 5000
    }
}

public object JvmKWebProcesses {
    /**
     * Binds the packaged managed-process provider. A missing native library, a
     * missing declaration entry, or a mismatched ABI layout fails immediately
     * instead of falling back to another launcher.
     */
    public fun open(declaration: KWebProcessDeclaration, nativeLibrary: Path): JvmKWebProcessesSession {
        val mismatches = ProcessesFfm.layoutMismatches()
        if (mismatches.isNotEmpty()) {
            throw KWebServiceException(
                code = KWebServiceErrorCode.VERSION_INCOMPATIBLE,
                details = mismatches.associate { it.substringBefore(" is ") to it },
                message = "The packaged process ABI layout does not match this binding.",
            )
        }
        return JvmKWebProcessesSession(
            native = ProcessesFfm.openLibrary(nativeLibrary, declaration.applicationId),
            declaration = declaration,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
    }
}

internal fun isolationCode(isolation: KWebProcessIsolation): Int = when (isolation) {
    KWebProcessIsolation.PROCESS_TREE -> ProcessesFfm.ISOLATION_PROCESS_TREE
    KWebProcessIsolation.RESOURCE_LIMITS -> ProcessesFfm.ISOLATION_RESOURCE_LIMITS
    KWebProcessIsolation.RESTRICTED_TOKEN -> ProcessesFfm.ISOLATION_RESTRICTED_TOKEN
    KWebProcessIsolation.SYSTEMD_SCOPE -> ProcessesFfm.ISOLATION_SYSTEMD_SCOPE
    KWebProcessIsolation.SECCOMP -> ProcessesFfm.ISOLATION_SECCOMP
    KWebProcessIsolation.NAMESPACES -> ProcessesFfm.ISOLATION_NAMESPACES
    KWebProcessIsolation.SEATBELT -> ProcessesFfm.ISOLATION_SEATBELT
}

internal fun streamOf(code: Int): KWebProcessStream =
    if (code == ProcessesFfm.STREAM_STDERR) KWebProcessStream.STDERR else KWebProcessStream.STDOUT

internal fun ProcessesFfm.Termination.toTermination(): KWebProcessTermination = KWebProcessTermination(
    reason = when (reason) {
        ProcessesFfm.REASON_EXITED -> KWebProcessTerminalReason.EXITED
        ProcessesFfm.REASON_CRASHED -> KWebProcessTerminalReason.CRASHED
        ProcessesFfm.REASON_TIMEOUT -> KWebProcessTerminalReason.TIMEOUT
        ProcessesFfm.REASON_CPU_LIMIT -> KWebProcessTerminalReason.CPU_LIMIT
        ProcessesFfm.REASON_MEMORY_LIMIT -> KWebProcessTerminalReason.MEMORY_LIMIT
        ProcessesFfm.REASON_OUTPUT_LIMIT -> KWebProcessTerminalReason.OUTPUT_LIMIT
        ProcessesFfm.REASON_KILLED -> KWebProcessTerminalReason.KILLED
        else -> KWebProcessTerminalReason.OWNER_CLOSED
    },
    exitCode = if (exitCode < 0) null else exitCode,
    signal = signal,
    sequence = sequence,
    stdoutBytes = stdoutBytes,
    stderrBytes = stderrBytes,
    cpuMillis = cpuMillis,
    peakMemoryBytes = peakMemoryBytes,
    durationMillis = durationMillis,
)
