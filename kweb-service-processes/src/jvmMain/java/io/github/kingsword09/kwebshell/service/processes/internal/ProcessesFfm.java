package io.github.kingsword09.kwebshell.service.processes.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The JDK 25 FFM binding over the versioned managed-process C ABI. Layouts and
 * segments stay internal: Kotlin owns specification validation, process identity,
 * stream delivery, and lifecycle, and never sees a MemorySegment.
 */
public final class ProcessesFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_PLATFORM_UNAVAILABLE = 3;
    public static final int STATUS_ISOLATION_UNSUPPORTED = 4;
    public static final int STATUS_EXECUTABLE_UNVERIFIED = 5;
    public static final int STATUS_EXECUTABLE_MISSING = 6;
    public static final int STATUS_WORKING_DIRECTORY_INVALID = 7;
    public static final int STATUS_LIMIT_EXCEEDED = 8;
    public static final int STATUS_PROCESS_UNKNOWN = 9;
    public static final int STATUS_OWNER_CLOSED = 10;
    public static final int STATUS_NATIVE_FAILED = 11;
    public static final int STATUS_NO_OUTPUT = 12;
    public static final int STATUS_STILL_RUNNING = 13;
    public static final int STATUS_BUFFER_TOO_SMALL = 14;
    public static final int STATUS_STDIN_CLOSED = 15;
    public static final int STATUS_STDIN_TIMEOUT = 16;
    public static final int STATUS_ALREADY_OPEN = 17;
    public static final int STATUS_LAUNCH_FAILED = 18;
    public static final int STATUS_OUTPUT_ENDED = 19;

    public static final int ISOLATION_PROCESS_TREE = 1;
    public static final int ISOLATION_RESOURCE_LIMITS = 1 << 1;
    public static final int ISOLATION_RESTRICTED_TOKEN = 1 << 2;
    public static final int ISOLATION_SYSTEMD_SCOPE = 1 << 3;
    public static final int ISOLATION_SECCOMP = 1 << 4;
    public static final int ISOLATION_NAMESPACES = 1 << 5;
    public static final int ISOLATION_SEATBELT = 1 << 6;

    public static final int STREAM_STDOUT = 1;
    public static final int STREAM_STDERR = 2;

    public static final int STATE_RUNNING = 1;
    public static final int STATE_TERMINATED = 2;

    public static final int MEMORY_RESIDENT = 1;

    public static final int REASON_EXITED = 1;
    public static final int REASON_CRASHED = 2;
    public static final int REASON_TIMEOUT = 3;
    public static final int REASON_CPU_LIMIT = 4;
    public static final int REASON_MEMORY_LIMIT = 5;
    public static final int REASON_OUTPUT_LIMIT = 6;
    public static final int REASON_KILLED = 7;
    public static final int REASON_OWNER_CLOSED = 8;

    private static final GroupLayout STRING_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final GroupLayout STRING_LIST_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("items"),
        JAVA_LONG.withName("count")
    );
    private static final GroupLayout CONFIGURATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("application_id"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4)
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(64, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("isolation_bits"),
        JAVA_INT.withName("memory_measure"),
        JAVA_INT.withName("max_live_processes"),
        JAVA_INT.withName("min_timeout_seconds"),
        JAVA_INT.withName("max_timeout_seconds"),
        JAVA_INT.withName("min_cpu_seconds"),
        JAVA_INT.withName("max_cpu_seconds"),
        JAVA_INT.withName("min_output_window_bytes"),
        JAVA_INT.withName("max_output_window_bytes"),
        JAVA_INT.withName("max_stdin_chunk_bytes"),
        JAVA_INT.withName("max_arguments"),
        JAVA_INT.withName("max_environment"),
        JAVA_INT.withName("live_processes"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("min_memory_bytes"),
        JAVA_LONG.withName("max_memory_bytes"),
        JAVA_LONG.withName("min_output_quota_bytes"),
        JAVA_LONG.withName("max_output_quota_bytes")
    );
    private static final GroupLayout START_REQUEST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("process_id"),
        STRING_LAYOUT.withName("executable_path"),
        STRING_LAYOUT.withName("executable_sha256"),
        STRING_LAYOUT.withName("working_directory"),
        STRING_LIST_LAYOUT.withName("arguments"),
        STRING_LIST_LAYOUT.withName("environment"),
        JAVA_INT.withName("requested_isolation_bits"),
        JAVA_INT.withName("timeout_seconds"),
        JAVA_INT.withName("cpu_seconds"),
        JAVA_INT.withName("stdout_window_bytes"),
        JAVA_INT.withName("stderr_window_bytes"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("memory_bytes"),
        JAVA_LONG.withName("stdout_quota_bytes"),
        JAVA_LONG.withName("stderr_quota_bytes")
    );
    private static final GroupLayout START_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("isolation_bits"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("process_handle")
    );
    private static final GroupLayout OUTPUT_CHUNK_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("stream"),
        JAVA_INT.withName("size"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4),
        JAVA_LONG.withName("sequence"),
        JAVA_LONG.withName("total_bytes")
    );
    private static final GroupLayout TERMINATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("reason"),
        JAVA_INT.withName("exit_code"),
        JAVA_INT.withName("signal"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("sequence"),
        JAVA_LONG.withName("stdout_bytes"),
        JAVA_LONG.withName("stderr_bytes"),
        JAVA_LONG.withName("cpu_millis"),
        JAVA_LONG.withName("peak_memory_bytes"),
        JAVA_LONG.withName("duration_millis")
    );
    private static final GroupLayout STATUS_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("state"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("sequence"),
        JAVA_LONG.withName("stdout_bytes"),
        JAVA_LONG.withName("stderr_bytes"),
        JAVA_LONG.withName("pending_output_bytes"),
        JAVA_LONG.withName("cpu_millis"),
        JAVA_LONG.withName("peak_memory_bytes"),
        JAVA_LONG.withName("duration_millis"),
        TERMINATION_LAYOUT.withName("termination")
    );

    private static final VarHandle I = JAVA_INT.varHandle();
    private static final VarHandle L = JAVA_LONG.varHandle();

    private static final VarHandle CONFIGURATION_SIZE = CONFIGURATION_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle CONFIGURATION_VERSION = CONFIGURATION_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle CONFIGURATION_APPLICATION_ADDRESS =
        CONFIGURATION_LAYOUT.varHandle(groupElement("application_id"), groupElement("data"));
    private static final VarHandle CONFIGURATION_APPLICATION_SIZE =
        CONFIGURATION_LAYOUT.varHandle(groupElement("application_id"), groupElement("size"));

    private static final VarHandle CAPABILITIES_SIZE = CAPABILITIES_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle CAPABILITIES_VERSION = CAPABILITIES_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle CAPABILITIES_ISOLATION = CAPABILITIES_LAYOUT.varHandle(groupElement("isolation_bits"));
    private static final VarHandle CAPABILITIES_MEMORY_MEASURE = CAPABILITIES_LAYOUT.varHandle(groupElement("memory_measure"));
    private static final VarHandle CAPABILITIES_MAX_LIVE = CAPABILITIES_LAYOUT.varHandle(groupElement("max_live_processes"));
    private static final VarHandle CAPABILITIES_MIN_TIMEOUT = CAPABILITIES_LAYOUT.varHandle(groupElement("min_timeout_seconds"));
    private static final VarHandle CAPABILITIES_MAX_TIMEOUT = CAPABILITIES_LAYOUT.varHandle(groupElement("max_timeout_seconds"));
    private static final VarHandle CAPABILITIES_MIN_CPU = CAPABILITIES_LAYOUT.varHandle(groupElement("min_cpu_seconds"));
    private static final VarHandle CAPABILITIES_MAX_CPU = CAPABILITIES_LAYOUT.varHandle(groupElement("max_cpu_seconds"));
    private static final VarHandle CAPABILITIES_MIN_WINDOW =
        CAPABILITIES_LAYOUT.varHandle(groupElement("min_output_window_bytes"));
    private static final VarHandle CAPABILITIES_MAX_WINDOW =
        CAPABILITIES_LAYOUT.varHandle(groupElement("max_output_window_bytes"));
    private static final VarHandle CAPABILITIES_STDIN_CHUNK = CAPABILITIES_LAYOUT.varHandle(groupElement("max_stdin_chunk_bytes"));
    private static final VarHandle CAPABILITIES_MAX_ARGUMENTS = CAPABILITIES_LAYOUT.varHandle(groupElement("max_arguments"));
    private static final VarHandle CAPABILITIES_MAX_ENVIRONMENT = CAPABILITIES_LAYOUT.varHandle(groupElement("max_environment"));
    private static final VarHandle CAPABILITIES_LIVE = CAPABILITIES_LAYOUT.varHandle(groupElement("live_processes"));
    private static final VarHandle CAPABILITIES_MIN_MEMORY = CAPABILITIES_LAYOUT.varHandle(groupElement("min_memory_bytes"));
    private static final VarHandle CAPABILITIES_MAX_MEMORY = CAPABILITIES_LAYOUT.varHandle(groupElement("max_memory_bytes"));
    private static final VarHandle CAPABILITIES_MIN_QUOTA = CAPABILITIES_LAYOUT.varHandle(groupElement("min_output_quota_bytes"));
    private static final VarHandle CAPABILITIES_MAX_QUOTA = CAPABILITIES_LAYOUT.varHandle(groupElement("max_output_quota_bytes"));

    private static final VarHandle START_SIZE = START_REQUEST_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle START_VERSION = START_REQUEST_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle START_ISOLATION = START_REQUEST_LAYOUT.varHandle(groupElement("requested_isolation_bits"));
    private static final VarHandle START_TIMEOUT = START_REQUEST_LAYOUT.varHandle(groupElement("timeout_seconds"));
    private static final VarHandle START_CPU = START_REQUEST_LAYOUT.varHandle(groupElement("cpu_seconds"));
    private static final VarHandle START_STDOUT_WINDOW = START_REQUEST_LAYOUT.varHandle(groupElement("stdout_window_bytes"));
    private static final VarHandle START_STDERR_WINDOW = START_REQUEST_LAYOUT.varHandle(groupElement("stderr_window_bytes"));
    private static final VarHandle START_MEMORY = START_REQUEST_LAYOUT.varHandle(groupElement("memory_bytes"));
    private static final VarHandle START_STDOUT_QUOTA = START_REQUEST_LAYOUT.varHandle(groupElement("stdout_quota_bytes"));
    private static final VarHandle START_STDERR_QUOTA = START_REQUEST_LAYOUT.varHandle(groupElement("stderr_quota_bytes"));
    private static final VarHandle START_ARGUMENT_COUNT = START_REQUEST_LAYOUT.varHandle(groupElement("arguments"), groupElement("count"));
    private static final VarHandle START_ENVIRONMENT_COUNT = START_REQUEST_LAYOUT.varHandle(groupElement("environment"), groupElement("count"));

    private static final VarHandle START_RESULT_SIZE = START_RESULT_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle START_RESULT_VERSION = START_RESULT_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle START_RESULT_HANDLE = START_RESULT_LAYOUT.varHandle(groupElement("process_handle"));
    private static final VarHandle START_RESULT_ISOLATION = START_RESULT_LAYOUT.varHandle(groupElement("isolation_bits"));

    private static final VarHandle CHUNK_SIZE = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle CHUNK_VERSION = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle CHUNK_STREAM = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("stream"));
    private static final VarHandle CHUNK_BYTES = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("size"));
    private static final VarHandle CHUNK_SEQUENCE = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("sequence"));
    private static final VarHandle CHUNK_TOTAL = OUTPUT_CHUNK_LAYOUT.varHandle(groupElement("total_bytes"));

    private static final VarHandle TERMINATION_SIZE = TERMINATION_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle TERMINATION_VERSION = TERMINATION_LAYOUT.varHandle(groupElement("abi_version"));

    private static final VarHandle STATUS_SIZE = STATUS_LAYOUT.varHandle(groupElement("struct_size"));
    private static final VarHandle STATUS_VERSION = STATUS_LAYOUT.varHandle(groupElement("abi_version"));
    private static final VarHandle STATUS_STATE = STATUS_LAYOUT.varHandle(groupElement("state"));
    private static final VarHandle STATUS_SEQUENCE = STATUS_LAYOUT.varHandle(groupElement("sequence"));
    private static final VarHandle STATUS_STDOUT = STATUS_LAYOUT.varHandle(groupElement("stdout_bytes"));
    private static final VarHandle STATUS_STDERR = STATUS_LAYOUT.varHandle(groupElement("stderr_bytes"));
    private static final VarHandle STATUS_PENDING = STATUS_LAYOUT.varHandle(groupElement("pending_output_bytes"));
    private static final VarHandle STATUS_CPU = STATUS_LAYOUT.varHandle(groupElement("cpu_millis"));
    private static final VarHandle STATUS_PEAK = STATUS_LAYOUT.varHandle(groupElement("peak_memory_bytes"));
    private static final VarHandle STATUS_DURATION = STATUS_LAYOUT.varHandle(groupElement("duration_millis"));

    /** Reads one termination record out of any struct that embeds it. */
    public record Termination(
        int reason,
        int exitCode,
        int signal,
        long sequence,
        long stdoutBytes,
        long stderrBytes,
        long cpuMillis,
        long peakMemoryBytes,
        long durationMillis
    ) {}

    public record Capabilities(
        String providerId,
        int isolationBits,
        int memoryMeasure,
        int maxLiveProcesses,
        int minTimeoutSeconds,
        int maxTimeoutSeconds,
        int minCpuSeconds,
        int maxCpuSeconds,
        int minOutputWindowBytes,
        int maxOutputWindowBytes,
        int maxStdinChunkBytes,
        int maxArguments,
        int maxEnvironment,
        int liveProcesses,
        long minMemoryBytes,
        long maxMemoryBytes,
        long minOutputQuotaBytes,
        long maxOutputQuotaBytes
    ) {}

    public record StartResult(long processHandle, int isolationBits) {}

    public record Chunk(int stream, byte[] bytes, long sequence, long totalBytes, boolean ended) {}

    public record Status(
        int state,
        long sequence,
        long stdoutBytes,
        long stderrBytes,
        long pendingOutputBytes,
        long cpuMillis,
        long peakMemoryBytes,
        long durationMillis,
        Termination termination
    ) {}

    public static final class ProcessNativeException extends RuntimeException {
        public final int status;

        ProcessNativeException(int status, String operation) {
            super("The managed-process provider rejected '" + operation + "' with status " + status + ".");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment outputBuffer;
    private final MethodHandle open;
    private final MethodHandle close;
    private final MethodHandle capabilities;
    private final MethodHandle start;
    private final MethodHandle writeStdin;
    private final MethodHandle closeStdin;
    private final MethodHandle readOutput;
    private final MethodHandle state;
    private final MethodHandle wait;
    private final MethodHandle terminate;
    private final MethodHandle release;

    private ProcessesFfm(Path library) {
        this.arena = Arena.ofShared();
        this.outputBuffer = arena.allocate(65536);
        System.load(library.toAbsolutePath().toString());
        final SymbolLookup lookup = SymbolLookup.loaderLookup();
        this.open = downcall(lookup, "kweb_processes_open", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.close = downcall(lookup, "kweb_processes_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        this.capabilities = downcall(lookup, "kweb_processes_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        this.start = downcall(lookup, "kweb_processes_start", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS));
        this.writeStdin = downcall(lookup, "kweb_processes_write_stdin",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        this.closeStdin = downcall(lookup, "kweb_processes_close_stdin", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
        this.readOutput = downcall(lookup, "kweb_processes_read_output",
            FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
        this.state = downcall(lookup, "kweb_processes_state", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS));
        this.wait = downcall(lookup, "kweb_processes_wait", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        this.terminate = downcall(lookup, "kweb_processes_terminate", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        this.release = downcall(lookup, "kweb_processes_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG));
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        final MemorySegment symbol = lookup.find(name).orElseThrow(() ->
            new IllegalStateException("The managed-process native library does not export " + name + "."));
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, descriptor);
    }

    /** Binds the packaged provider library; a missing library fails immediately. */
    public static ProcessesFfm openLibrary(Path library, String applicationId) {
        final ProcessesFfm binding = new ProcessesFfm(library);
        binding.openService(applicationId);
        return binding;
    }

    private void openService(String applicationId) {
        try (Arena confined = Arena.ofConfined()) {
            final byte[] bytes = applicationId.getBytes(StandardCharsets.UTF_8);
            final MemorySegment identifier = confined.allocate(bytes.length);
            MemorySegment.copy(bytes, 0, identifier, JAVA_BYTE, 0, bytes.length);
            final MemorySegment configuration = confined.allocate(CONFIGURATION_LAYOUT);
            CONFIGURATION_SIZE.set(configuration, 0L, (int) CONFIGURATION_LAYOUT.byteSize());
            CONFIGURATION_VERSION.set(configuration, 0L, ABI_VERSION);
            CONFIGURATION_APPLICATION_ADDRESS.set(configuration, 0L, identifier);
            CONFIGURATION_APPLICATION_SIZE.set(configuration, 0L, (long) bytes.length);
            final MemorySegment handle = confined.allocate(JAVA_LONG);
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "open");
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    public Capabilities capabilities() {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment result = confined.allocate(CAPABILITIES_LAYOUT);
            CAPABILITIES_SIZE.set(result, 0L, (int) CAPABILITIES_LAYOUT.byteSize());
            CAPABILITIES_VERSION.set(result, 0L, ABI_VERSION);
            final int status = (int) capabilities.invokeExact(1L, result);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "capabilities");
            return new Capabilities(
                readString(result.asSlice(CAPABILITIES_LAYOUT.byteOffset(groupElement("provider_id"))), 64),
                (int) CAPABILITIES_ISOLATION.get(result, 0L),
                (int) CAPABILITIES_MEMORY_MEASURE.get(result, 0L),
                (int) CAPABILITIES_MAX_LIVE.get(result, 0L),
                (int) CAPABILITIES_MIN_TIMEOUT.get(result, 0L),
                (int) CAPABILITIES_MAX_TIMEOUT.get(result, 0L),
                (int) CAPABILITIES_MIN_CPU.get(result, 0L),
                (int) CAPABILITIES_MAX_CPU.get(result, 0L),
                (int) CAPABILITIES_MIN_WINDOW.get(result, 0L),
                (int) CAPABILITIES_MAX_WINDOW.get(result, 0L),
                (int) CAPABILITIES_STDIN_CHUNK.get(result, 0L),
                (int) CAPABILITIES_MAX_ARGUMENTS.get(result, 0L),
                (int) CAPABILITIES_MAX_ENVIRONMENT.get(result, 0L),
                (int) CAPABILITIES_LIVE.get(result, 0L),
                (long) CAPABILITIES_MIN_MEMORY.get(result, 0L),
                (long) CAPABILITIES_MAX_MEMORY.get(result, 0L),
                (long) CAPABILITIES_MIN_QUOTA.get(result, 0L),
                (long) CAPABILITIES_MAX_QUOTA.get(result, 0L)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    public StartResult start(
        String processId,
        String executablePath,
        String executableSha256,
        String workingDirectory,
        List<String> arguments,
        List<String> environment,
        int isolationBits,
        int timeoutSeconds,
        int cpuSeconds,
        int stdoutWindowBytes,
        int stderrWindowBytes,
        long memoryBytes,
        long stdoutQuotaBytes,
        long stderrQuotaBytes
    ) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment request = confined.allocate(START_REQUEST_LAYOUT);
            START_SIZE.set(request, 0L, (int) START_REQUEST_LAYOUT.byteSize());
            START_VERSION.set(request, 0L, ABI_VERSION);
            writeStringView(confined, request, START_REQUEST_LAYOUT, "process_id", processId);
            writeStringView(confined, request, START_REQUEST_LAYOUT, "executable_path", executablePath);
            writeStringView(confined, request, START_REQUEST_LAYOUT, "executable_sha256", executableSha256);
            writeStringView(confined, request, START_REQUEST_LAYOUT, "working_directory", workingDirectory);
            writeStringList(confined, request, "arguments", arguments);
            writeStringList(confined, request, "environment", environment);
            START_ISOLATION.set(request, 0L, isolationBits);
            START_TIMEOUT.set(request, 0L, timeoutSeconds);
            START_CPU.set(request, 0L, cpuSeconds);
            START_STDOUT_WINDOW.set(request, 0L, stdoutWindowBytes);
            START_STDERR_WINDOW.set(request, 0L, stderrWindowBytes);
            START_MEMORY.set(request, 0L, memoryBytes);
            START_STDOUT_QUOTA.set(request, 0L, stdoutQuotaBytes);
            START_STDERR_QUOTA.set(request, 0L, stderrQuotaBytes);
            final MemorySegment result = confined.allocate(START_RESULT_LAYOUT);
            START_RESULT_SIZE.set(result, 0L, (int) START_RESULT_LAYOUT.byteSize());
            START_RESULT_VERSION.set(result, 0L, ABI_VERSION);
            final int status = (int) start.invokeExact(1L, request, result);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "start");
            return new StartResult((long) START_RESULT_HANDLE.get(result, 0L), (int) START_RESULT_ISOLATION.get(result, 0L));
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    /** One bounded write attempt; the caller owns the byte array. */
    public long writeStdin(long processHandle, byte[] bytes, int offset, int length, int timeoutMillis) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment payload = confined.allocate(length);
            MemorySegment.copy(bytes, offset, payload, JAVA_BYTE, 0, length);
            final MemorySegment accepted = confined.allocate(JAVA_LONG);
            final int status = (int) writeStdin.invokeExact(processHandle, payload, (long) length, timeoutMillis, accepted);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "write-stdin");
            return (long) accepted.get(JAVA_LONG, 0L);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    public void closeStdin(long processHandle) {
        invokeVoid("close-stdin", () -> (int) closeStdin.invokeExact(processHandle));
    }

    /**
     * Returns null when nothing is available yet, or an ended chunk at stream end.
     * The bytes are copied while the shared read buffer is owned by this call, so
     * several processes can read concurrently through one binding.
     */
    public synchronized Chunk readOutput(long processHandle, int timeoutMillis) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment chunk = confined.allocate(OUTPUT_CHUNK_LAYOUT);
            CHUNK_SIZE.set(chunk, 0L, (int) OUTPUT_CHUNK_LAYOUT.byteSize());
            CHUNK_VERSION.set(chunk, 0L, ABI_VERSION);
            final int status = (int) readOutput.invokeExact(processHandle, outputBuffer, outputBuffer.byteSize(), timeoutMillis, chunk);
            if (status == STATUS_OK) {
                final int size = (int) CHUNK_BYTES.get(chunk, 0L);
                final byte[] bytes = new byte[size];
                MemorySegment.copy(outputBuffer, JAVA_BYTE, 0L, bytes, 0, size);
                return new Chunk((int) CHUNK_STREAM.get(chunk, 0L), bytes, (long) CHUNK_SEQUENCE.get(chunk, 0L),
                    (long) CHUNK_TOTAL.get(chunk, 0L), false);
            }
            if (status == STATUS_OUTPUT_ENDED) return new Chunk(0, new byte[0], 0L, 0L, true);
            if (status == STATUS_NO_OUTPUT) return null;
            throw new ProcessNativeException(status, "read-output");
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    public Status status(long processHandle) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment result = confined.allocate(STATUS_LAYOUT);
            STATUS_SIZE.set(result, 0L, (int) STATUS_LAYOUT.byteSize());
            STATUS_VERSION.set(result, 0L, ABI_VERSION);
            final int status = (int) state.invokeExact(processHandle, result);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "state");
            final MemorySegment termination = result.asSlice(STATUS_LAYOUT.byteOffset(groupElement("termination")));
            return new Status(
                (int) STATUS_STATE.get(result, 0L),
                (long) STATUS_SEQUENCE.get(result, 0L),
                (long) STATUS_STDOUT.get(result, 0L),
                (long) STATUS_STDERR.get(result, 0L),
                (long) STATUS_PENDING.get(result, 0L),
                (long) STATUS_CPU.get(result, 0L),
                (long) STATUS_PEAK.get(result, 0L),
                (long) STATUS_DURATION.get(result, 0L),
                readTermination(termination)
            );
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    /** Returns null while the process is still running. */
    public Termination waitFor(long processHandle, int timeoutMillis) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment termination = confined.allocate(TERMINATION_LAYOUT);
            TERMINATION_SIZE.set(termination, 0L, (int) TERMINATION_LAYOUT.byteSize());
            TERMINATION_VERSION.set(termination, 0L, ABI_VERSION);
            final int status = (int) wait.invokeExact(processHandle, timeoutMillis, termination);
            if (status == STATUS_STILL_RUNNING) return null;
            if (status != STATUS_OK) throw new ProcessNativeException(status, "wait");
            return readTermination(termination);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    /** Terminates one whole tree and returns its single terminal result. */
    public Termination terminate(long processHandle, boolean ownerClosed) {
        try (Arena confined = Arena.ofConfined()) {
            final MemorySegment termination = confined.allocate(TERMINATION_LAYOUT);
            TERMINATION_SIZE.set(termination, 0L, (int) TERMINATION_LAYOUT.byteSize());
            TERMINATION_VERSION.set(termination, 0L, ABI_VERSION);
            final int status = (int) terminate.invokeExact(processHandle, ownerClosed ? 1 : 0, termination);
            if (status != STATUS_OK) throw new ProcessNativeException(status, "terminate");
            return readTermination(termination);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    public void release(long processHandle) {
        invokeVoid("release", () -> (int) release.invokeExact(processHandle));
    }

    private Termination readTermination(MemorySegment termination) {
        return new Termination(
            (int) TERMINATION_LAYOUT.varHandle(groupElement("reason")).get(termination, 0L),
            (int) TERMINATION_LAYOUT.varHandle(groupElement("exit_code")).get(termination, 0L),
            (int) TERMINATION_LAYOUT.varHandle(groupElement("signal")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("sequence")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("stdout_bytes")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("stderr_bytes")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("cpu_millis")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("peak_memory_bytes")).get(termination, 0L),
            (long) TERMINATION_LAYOUT.varHandle(groupElement("duration_millis")).get(termination, 0L)
        );
    }

    private interface IntCall {
        int invoke() throws Throwable;
    }

    private void invokeVoid(String operation, IntCall call) {
        try {
            final int status = call.invoke();
            if (status != STATUS_OK) throw new ProcessNativeException(status, operation);
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        }
    }

    private static void writeStringView(Arena arena, MemorySegment owner, GroupLayout ownerLayout, String field, String value) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final MemorySegment storage = arena.allocate(Math.max(bytes.length, 1));
        if (bytes.length > 0) MemorySegment.copy(bytes, 0, storage, JAVA_BYTE, 0, bytes.length);
        final long base = ownerLayout.byteOffset(groupElement(field));
        STRING_LAYOUT.varHandle(groupElement("data")).set(owner, base, storage);
        STRING_LAYOUT.varHandle(groupElement("size")).set(owner, base, (long) bytes.length);
    }

    private static void writeStringList(Arena arena, MemorySegment owner, String field, List<String> values) {
        final long base = START_REQUEST_LAYOUT.byteOffset(groupElement(field));
        if (values.isEmpty()) {
            STRING_LIST_LAYOUT.varHandle(groupElement("items")).set(owner, base, MemorySegment.NULL);
            STRING_LIST_LAYOUT.varHandle(groupElement("count")).set(owner, base, 0L);
            return;
        }
        final MemorySegment views = arena.allocate(STRING_LAYOUT, values.size());
        for (int index = 0; index < values.size(); index++) {
            final byte[] bytes = values.get(index).getBytes(StandardCharsets.UTF_8);
            final MemorySegment storage = arena.allocate(Math.max(bytes.length, 1));
            if (bytes.length > 0) MemorySegment.copy(bytes, 0, storage, JAVA_BYTE, 0, bytes.length);
            final MemorySegment view = views.asSlice(index * STRING_LAYOUT.byteSize());
            STRING_LAYOUT.varHandle(groupElement("data")).set(view, 0L, storage);
            STRING_LAYOUT.varHandle(groupElement("size")).set(view, 0L, (long) bytes.length);
        }
        STRING_LIST_LAYOUT.varHandle(groupElement("items")).set(owner, base, views);
        STRING_LIST_LAYOUT.varHandle(groupElement("count")).set(owner, base, (long) values.size());
    }

    private static String readString(MemorySegment slice, int capacity) {
        int length = 0;
        while (length < capacity && slice.get(JAVA_BYTE, length) != 0) length++;
        final byte[] bytes = new byte[length];
        MemorySegment.copy(slice, JAVA_BYTE, 0L, bytes, 0, length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The published C layouts are a contract, so the binding fails closed when the
     * compiled provider disagrees with the offsets this binding writes.
     */
    public static List<String> layoutMismatches() {
        final List<String> mismatches = new ArrayList<>();
        expect(mismatches, CONFIGURATION_LAYOUT.byteSize(), 32, "configuration size");
        expect(mismatches, CAPABILITIES_LAYOUT.byteSize(), 160, "capabilities size");
        expect(mismatches, CAPABILITIES_LAYOUT.byteOffset(groupElement("provider_id")), 8, "provider_id");
        expect(mismatches, CAPABILITIES_LAYOUT.byteOffset(groupElement("min_memory_bytes")), 128, "min_memory_bytes");
        expect(mismatches, START_REQUEST_LAYOUT.byteSize(), 152, "start request size");
        expect(mismatches, START_REQUEST_LAYOUT.byteOffset(groupElement("arguments")), 72, "arguments");
        expect(mismatches, START_REQUEST_LAYOUT.byteOffset(groupElement("memory_bytes")), 128, "memory_bytes");
        expect(mismatches, START_RESULT_LAYOUT.byteSize(), 24, "start result size");
        expect(mismatches, OUTPUT_CHUNK_LAYOUT.byteSize(), 40, "output chunk size");
        expect(mismatches, OUTPUT_CHUNK_LAYOUT.byteOffset(groupElement("sequence")), 24, "sequence");
        expect(mismatches, TERMINATION_LAYOUT.byteSize(), 72, "termination size");
        expect(mismatches, TERMINATION_LAYOUT.byteOffset(groupElement("sequence")), 24, "termination sequence");
        expect(mismatches, STATUS_LAYOUT.byteSize(), 144, "status size");
        expect(mismatches, STATUS_LAYOUT.byteOffset(groupElement("termination")), 72, "status termination");
        return mismatches;
    }

    private static void expect(List<String> mismatches, long actual, long expected, String field) {
        if (actual != expected) {
            mismatches.add(field + " is " + actual + " but the published ABI declares " + expected);
        }
    }

    @Override
    public void close() {
        try {
            final int status = (int) close.invokeExact(1L);
            if (status != STATUS_OK && status != STATUS_OWNER_CLOSED) throw new ProcessNativeException(status, "close");
        } catch (RuntimeException error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("The managed-process ABI call failed.", error);
        } finally {
            arena.close();
        }
    }
}
