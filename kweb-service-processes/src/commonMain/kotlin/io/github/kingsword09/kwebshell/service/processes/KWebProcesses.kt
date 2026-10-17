/**
 * The RFC 0024 managed-process contract shared by the macOS, Windows, and Linux
 * providers. Values are immutable and bounded before a provider sees them; Kotlin
 * owns process identity, the declared limits, capability reporting, ordered stream
 * delivery, and the single terminal result.
 *
 * The service never accepts a renderer-provided executable, shell string, or PATH
 * lookup: a specification names an application-declared executable and working
 * directory, and the provider verifies the resolved file before anything starts.
 */
package io.github.kingsword09.kwebshell.service.processes

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One stable application-owned process identifier. */
public class KWebProcessId(public val value: String) {
    init {
        if (!PROCESS_IDENTIFIER.matches(value)) {
            invalid(KWebProcessesErrorCode.ID_INVALID, "A process identifier must match the published identifier form.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebProcessId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One application-declared packaged executable the service may start. */
public class KWebProcessExecutableId(public val value: String) {
    init {
        if (!PROCESS_IDENTIFIER.matches(value)) {
            invalid(
                KWebProcessesErrorCode.EXECUTABLE_UNKNOWN,
                "An executable identifier must match the published identifier form.",
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebProcessExecutableId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/** One application-declared working directory the service may enter. */
public class KWebProcessWorkingDirectoryId(public val value: String) {
    init {
        if (!PROCESS_IDENTIFIER.matches(value)) {
            invalid(
                KWebProcessesErrorCode.WORKING_DIRECTORY_INVALID,
                "A working directory identifier must match the published identifier form.",
            )
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebProcessWorkingDirectoryId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value
}

/**
 * One isolation profile a specification may require. The packaged provider
 * declares which profiles it implements, and a requirement it does not declare
 * fails before anything is started.
 */
public enum class KWebProcessIsolation {
    PROCESS_TREE,
    RESOURCE_LIMITS,
    RESTRICTED_TOKEN,
    SYSTEMD_SCOPE,
    SECCOMP,
    NAMESPACES,
    SEATBELT,
}

/** One declared output stream. */
public enum class KWebProcessStream { STDOUT, STDERR }

/** The observable state of one managed process. */
public enum class KWebProcessState { RUNNING, TERMINATED }

/** Why a managed process ended. Exactly one reason is reported per process. */
public enum class KWebProcessTerminalReason {
    EXITED,
    CRASHED,
    TIMEOUT,
    CPU_LIMIT,
    MEMORY_LIMIT,
    OUTPUT_LIMIT,
    KILLED,
    OWNER_CLOSED,
}

/** The memory measure a declared cap is enforced against. */
public enum class KWebProcessMemoryMeasure { RESIDENT }

/**
 * Every declared limit of one managed process. A declared limit is enforced by
 * the provider; there is no unbounded process and no implicit default.
 */
public data class KWebProcessLimits(
    public val timeoutSeconds: Int,
    public val cpuSeconds: Int,
    public val memoryBytes: Long,
    public val stdoutQuotaBytes: Long,
    public val stderrQuotaBytes: Long,
) {
    init {
        if (timeoutSeconds < KWebProcessesBounds.MIN_TIMEOUT_SECONDS) {
            invalid(KWebProcessesErrorCode.LIMIT_INVALID, "A managed process requires a positive timeout.")
        }
        if (timeoutSeconds > KWebProcessesBounds.MAX_TIMEOUT_SECONDS) {
            invalid(
                KWebProcessesErrorCode.LIMIT_INVALID,
                "A timeout must not exceed ${KWebProcessesBounds.MAX_TIMEOUT_SECONDS} seconds.",
            )
        }
        if (cpuSeconds < KWebProcessesBounds.MIN_CPU_SECONDS) {
            invalid(KWebProcessesErrorCode.LIMIT_INVALID, "A managed process requires a positive CPU limit.")
        }
        if (cpuSeconds > KWebProcessesBounds.MAX_CPU_SECONDS) {
            invalid(KWebProcessesErrorCode.LIMIT_INVALID, "A CPU limit must not exceed ${KWebProcessesBounds.MAX_CPU_SECONDS} seconds.")
        }
        if (memoryBytes < KWebProcessesBounds.MIN_MEMORY_BYTES || memoryBytes > KWebProcessesBounds.MAX_MEMORY_BYTES) {
            invalid(KWebProcessesErrorCode.LIMIT_INVALID, "A memory cap is outside the published range.")
        }
        validateQuota(stdoutQuotaBytes, "stdout")
        validateQuota(stderrQuotaBytes, "stderr")
    }

    private fun validateQuota(quota: Long, stream: String) {
        if (quota < KWebProcessesBounds.MIN_OUTPUT_QUOTA_BYTES || quota > KWebProcessesBounds.MAX_OUTPUT_QUOTA_BYTES) {
            invalid(KWebProcessesErrorCode.LIMIT_INVALID, "A $stream output quota is outside the published range.")
        }
    }
}

/** One declared stream window: the backpressure bound of a pipe consumer. */
public data class KWebProcessStreamPolicy(
    public val stdoutWindowBytes: Int,
    public val stderrWindowBytes: Int,
) {
    init {
        validateWindow(stdoutWindowBytes, "stdout")
        validateWindow(stderrWindowBytes, "stderr")
    }

    private fun validateWindow(window: Int, stream: String) {
        if (window < KWebProcessesBounds.MIN_OUTPUT_WINDOW_BYTES || window > KWebProcessesBounds.MAX_OUTPUT_WINDOW_BYTES) {
            invalid(KWebProcessesErrorCode.STREAM_INVALID, "A $stream window is outside the published range.")
        }
    }
}

/**
 * One immutable process specification. The environment map is the child's whole
 * environment; it is never inherited. The working directory is required.
 */
public data class KWebProcessSpec(
    public val id: KWebProcessId,
    public val executableId: KWebProcessExecutableId,
    public val workingDirectoryId: KWebProcessWorkingDirectoryId,
    public val arguments: List<String>,
    public val environment: Map<String, String>,
    public val limits: KWebProcessLimits,
    public val streams: KWebProcessStreamPolicy,
    public val requiredIsolation: Set<KWebProcessIsolation> = setOf(
        KWebProcessIsolation.PROCESS_TREE,
        KWebProcessIsolation.RESOURCE_LIMITS,
    ),
) {
    init {
        if (arguments.size > KWebProcessesBounds.MAX_ARGUMENTS) {
            invalid(KWebProcessesErrorCode.ARGUMENT_INVALID, "At most ${KWebProcessesBounds.MAX_ARGUMENTS} arguments are accepted.")
        }
        arguments.forEach { argument ->
            if (argument.encodeToByteArray().size > KWebProcessesBounds.MAX_ARGUMENT_BYTES || argument.contains('\u0000')) {
                invalid(KWebProcessesErrorCode.ARGUMENT_INVALID, "An argument exceeds its published bound.")
            }
        }
        if (environment.size > KWebProcessesBounds.MAX_ENVIRONMENT) {
            invalid(KWebProcessesErrorCode.ENVIRONMENT_INVALID, "At most ${KWebProcessesBounds.MAX_ENVIRONMENT} environment entries are accepted.")
        }
        environment.forEach { (name, value) ->
            if (!ENVIRONMENT_NAME.matches(name)) {
                invalid(KWebProcessesErrorCode.ENVIRONMENT_INVALID, "An environment name is invalid.")
            }
            if (value.encodeToByteArray().size > KWebProcessesBounds.MAX_ENVIRONMENT_VALUE_BYTES || value.contains('\u0000')) {
                invalid(KWebProcessesErrorCode.ENVIRONMENT_INVALID, "An environment value exceeds its published bound.")
            }
        }
        if (KWebProcessIsolation.PROCESS_TREE !in requiredIsolation) {
            invalid(KWebProcessesErrorCode.ISOLATION_INVALID, "A managed process must require its process tree.")
        }
    }
}

/** The declared provider facts of the current target and session. */
public data class KWebProcessCapabilities(
    public val providerId: String,
    public val declaredIsolation: Set<KWebProcessIsolation>,
    public val memoryMeasure: KWebProcessMemoryMeasure,
    public val maxLiveProcesses: Int,
    public val liveProcesses: Int,
    public val bounds: KWebProcessesBounds,
) {
    public fun supports(isolation: KWebProcessIsolation): Boolean = isolation in declaredIsolation
}

/** One ordered output chunk. Sequences increase per process and never repeat. */
public class KWebProcessOutputChunk(
    public val sequence: Long,
    public val stream: KWebProcessStream,
    bytes: ByteArray,
    public val totalBytes: Long,
) {
    private val content: ByteArray = bytes.copyOf()

    public val size: Int get() = content.size

    /** Returns a copy so a caller can never mutate the delivered chunk. */
    public fun bytes(): ByteArray = content.copyOf()

    override fun equals(other: Any?): Boolean =
        other is KWebProcessOutputChunk && sequence == other.sequence && stream == other.stream &&
            totalBytes == other.totalBytes && content.contentEquals(other.content)

    override fun hashCode(): Int = 31 * (31 * sequence.hashCode() + stream.hashCode()) + size

    override fun toString(): String = "KWebProcessOutputChunk(sequence=$sequence, stream=$stream, size=$size)"
}

/** The single terminal result of one managed process. */
public data class KWebProcessTermination(
    public val reason: KWebProcessTerminalReason,
    public val exitCode: Int?,
    public val signal: Int,
    public val sequence: Long,
    public val stdoutBytes: Long,
    public val stderrBytes: Long,
    public val cpuMillis: Long,
    public val peakMemoryBytes: Long,
    public val durationMillis: Long,
)

/** One live or ended process, redacted to declared identifiers and counters. */
public data class KWebProcessSummary(
    public val id: KWebProcessId,
    public val executableId: KWebProcessExecutableId,
    public val state: KWebProcessState,
    public val stdoutBytes: Long,
    public val stderrBytes: Long,
    public val pendingOutputBytes: Long,
    public val cpuMillis: Long,
    public val peakMemoryBytes: Long,
    public val durationMillis: Long,
)

/**
 * One application-owned managed process. Closing it terminates its whole tree and
 * releases every provider handle, so an owner can never leave an orphan behind.
 */
public interface KWebManagedProcess : AutoCloseable {
    public val id: KWebProcessId

    public val executableId: KWebProcessExecutableId

    public val state: StateFlow<KWebProcessState>

    /**
     * The ordered output stream: a cold, single-consumer flow over the provider's
     * bounded window. A consumer's own pace is the backpressure the child observes,
     * and one process cannot be consumed by two collectors at once. The terminal
     * result is published separately, so an owner can await it without consuming
     * output.
     */
    public val output: Flow<KWebProcessOutputChunk>

    /** The single terminal result, replayed to every collector. */
    public val termination: Flow<KWebProcessTermination>

    /** Accepts one bounded stdin chunk, or fails typed without silent truncation. */
    public suspend fun writeStdin(bytes: ByteArray): Int

    /** Closes the child's stdin after delivering every queued byte. */
    public suspend fun closeStdin()

    /** Terminates the whole tree and reports the terminal result. */
    public suspend fun kill(): KWebProcessTermination

    /** Suspends until the process ended and returns its terminal result. */
    public suspend fun await(): KWebProcessTermination

    override fun close()
}

public interface KWebProcesses : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun capabilities(): KWebProcessCapabilities

    /** Verifies identity, isolation, and limits, then starts one process tree. */
    public suspend fun start(spec: KWebProcessSpec): KWebManagedProcess

    /** Summaries of the live processes this service owns, without paths or arguments. */
    public suspend fun processes(): List<KWebProcessSummary>

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "processes",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            // The service publishes host operations only: no renderer operation can
            // name an executable, an argument, or a working directory.
            operations = setOf(
                hostOperation("capabilities"),
                hostOperation("start"),
                hostOperation("processes"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebProcesses> = object : KWebServiceKey<KWebProcesses> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun hostOperation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = null,
            requiresUserGesture = false,
        )
    }
}

public object KWebProcessesErrorCode {
    public const val ID_INVALID: String = "processes.id-invalid"
    public const val ARGUMENT_INVALID: String = "processes.argument-invalid"
    public const val ENVIRONMENT_INVALID: String = "processes.environment-invalid"
    public const val LIMIT_INVALID: String = "processes.limit-invalid"
    public const val STREAM_INVALID: String = "processes.stream-invalid"
    public const val ISOLATION_INVALID: String = "processes.isolation-invalid"
    public const val ISOLATION_UNSUPPORTED: String = "processes.isolation-unsupported"
    public const val EXECUTABLE_UNKNOWN: String = "processes.executable-unknown"
    public const val EXECUTABLE_MISSING: String = "processes.executable-missing"
    public const val EXECUTABLE_UNVERIFIED: String = "processes.executable-unverified"
    public const val WORKING_DIRECTORY_INVALID: String = "processes.working-directory-invalid"
    public const val LAUNCH_FAILED: String = "processes.launch-failed"
    public const val LIMIT_EXCEEDED: String = "processes.limit-exceeded"
    public const val PROCESS_UNKNOWN: String = "processes.process-unknown"
    public const val STDIN_CLOSED: String = "processes.stdin-closed"
    public const val STDIN_TIMEOUT: String = "processes.stdin-timeout"
    public const val STDIN_BOUNDS: String = "processes.stdin-bounds"
    public const val OUTPUT_BOUNDS: String = "processes.output-bounds"
    public const val PLATFORM_UNAVAILABLE: String = "processes.platform-unavailable"
    public const val NATIVE_FAILED: String = "processes.native-failed"
    public const val OWNER_CLOSED: String = "processes.owner-closed"
}

/** Every published bound of the v1 process contract. */
public data class KWebProcessesBounds(
    public val maxLiveProcesses: Int,
    public val maxArguments: Int,
    public val maxEnvironment: Int,
    public val maxArgumentBytes: Int,
    public val maxEnvironmentValueBytes: Int,
    public val minTimeoutSeconds: Int,
    public val maxTimeoutSeconds: Int,
    public val minCpuSeconds: Int,
    public val maxCpuSeconds: Int,
    public val minMemoryBytes: Long,
    public val maxMemoryBytes: Long,
    public val minOutputQuotaBytes: Long,
    public val maxOutputQuotaBytes: Long,
    public val minOutputWindowBytes: Int,
    public val maxOutputWindowBytes: Int,
    public val maxStdinChunkBytes: Int,
    public val maxOutputChunkBytes: Int,
) {
    public companion object {
        public const val MAX_LIVE_PROCESSES: Int = 8
        public const val MAX_ARGUMENTS: Int = 64
        public const val MAX_ENVIRONMENT: Int = 64
        public const val MAX_ARGUMENT_BYTES: Int = 4096
        public const val MAX_ENVIRONMENT_VALUE_BYTES: Int = 4096
        public const val MIN_TIMEOUT_SECONDS: Int = 1
        public const val MAX_TIMEOUT_SECONDS: Int = 3600
        public const val MIN_CPU_SECONDS: Int = 1
        public const val MAX_CPU_SECONDS: Int = 3600
        public const val MIN_MEMORY_BYTES: Long = 16L * 1024L * 1024L
        public const val MAX_MEMORY_BYTES: Long = 16L * 1024L * 1024L * 1024L
        public const val MIN_OUTPUT_QUOTA_BYTES: Long = 256L * 1024L
        public const val MAX_OUTPUT_QUOTA_BYTES: Long = 64L * 1024L * 1024L
        public const val MIN_OUTPUT_WINDOW_BYTES: Int = 4096
        public const val MAX_OUTPUT_WINDOW_BYTES: Int = 1024 * 1024
        public const val MAX_STDIN_CHUNK_BYTES: Int = 65536
        public const val MAX_OUTPUT_CHUNK_BYTES: Int = 65536

        public val PUBLISHED: KWebProcessesBounds = KWebProcessesBounds(
            maxLiveProcesses = MAX_LIVE_PROCESSES,
            maxArguments = MAX_ARGUMENTS,
            maxEnvironment = MAX_ENVIRONMENT,
            maxArgumentBytes = MAX_ARGUMENT_BYTES,
            maxEnvironmentValueBytes = MAX_ENVIRONMENT_VALUE_BYTES,
            minTimeoutSeconds = MIN_TIMEOUT_SECONDS,
            maxTimeoutSeconds = MAX_TIMEOUT_SECONDS,
            minCpuSeconds = MIN_CPU_SECONDS,
            maxCpuSeconds = MAX_CPU_SECONDS,
            minMemoryBytes = MIN_MEMORY_BYTES,
            maxMemoryBytes = MAX_MEMORY_BYTES,
            minOutputQuotaBytes = MIN_OUTPUT_QUOTA_BYTES,
            maxOutputQuotaBytes = MAX_OUTPUT_QUOTA_BYTES,
            minOutputWindowBytes = MIN_OUTPUT_WINDOW_BYTES,
            maxOutputWindowBytes = MAX_OUTPUT_WINDOW_BYTES,
            maxStdinChunkBytes = MAX_STDIN_CHUNK_BYTES,
            maxOutputChunkBytes = MAX_OUTPUT_CHUNK_BYTES,
        )
    }
}

internal val PROCESS_IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")

internal val ENVIRONMENT_NAME = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
