package io.github.kingsword09.kwebshell.service.power.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.MemoryLayout.paddingLayout;
import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The JDK 25 FFM binding over the versioned power C ABI. Layouts stay internal:
 * Kotlin owns validation, lease policy, and lifecycle, and never sees a
 * MemorySegment.
 */
public final class PowerFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_PLATFORM_UNAVAILABLE = 3;
    public static final int STATUS_SERVICE_UNAVAILABLE = 4;
    public static final int STATUS_TYPE_UNSUPPORTED = 5;
    public static final int STATUS_DURATION_EXCEEDED = 6;
    public static final int STATUS_LIMIT_EXCEEDED = 7;
    public static final int STATUS_LEASE_UNKNOWN = 8;
    public static final int STATUS_RENEWAL_EXCEEDED = 9;
    public static final int STATUS_NATIVE_FAILED = 10;
    public static final int STATUS_NO_EVENT = 11;
    public static final int STATUS_OWNER_CLOSED = 12;
    public static final int STATUS_ALREADY_OPEN = 13;
    public static final int STATUS_ALREADY_EXISTS = 14;
    public static final int STATUS_BUFFER_TOO_SMALL = 15;

    public static final int TYPE_SYSTEM_SLEEP = 1;
    public static final int TYPE_DISPLAY_SLEEP = 2;

    public static final int THERMAL_NOMINAL = 1;
    public static final int THERMAL_FAIR = 2;
    public static final int THERMAL_SERIOUS = 3;
    public static final int THERMAL_CRITICAL = 4;

    public static final int CAP_SUSPEND_EVENTS = 1;
    public static final int CAP_LOCK_EVENTS = 1 << 1;
    public static final int CAP_POWER_SOURCE_STATE = 1 << 2;
    public static final int CAP_THERMAL_STATE = 1 << 3;
    public static final int CAP_SYSTEM_SLEEP_BLOCKER = 1 << 4;
    public static final int CAP_DISPLAY_SLEEP_BLOCKER = 1 << 5;
    public static final int CAP_LEASE_RENEWAL = 1 << 6;

    public static final int FACT_ON_BATTERY = 1;
    public static final int FACT_BATTERY_PERCENT = 1 << 1;
    public static final int FACT_THERMAL = 1 << 2;
    public static final int FACT_LOCKED = 1 << 3;

    public static final int EVENT_SUSPENDED = 1;
    public static final int EVENT_RESUMED = 2;
    public static final int EVENT_LOCKED = 3;
    public static final int EVENT_UNLOCKED = 4;
    public static final int EVENT_POWER_SOURCE_CHANGED = 5;
    public static final int EVENT_THERMAL_CHANGED = 6;
    public static final int EVENT_LEASE_ENDED = 7;
    public static final int EVENT_FAILED = 8;

    public static final int END_EXPIRED = 1;
    public static final int END_RELEASED = 2;
    public static final int END_OWNER_CLOSED = 3;

    private static final int MAX_ID = 129;
    private static final int MAX_PROVIDER = 64;
    private static final int MAX_LEASES = 8;
    private static final int MAX_REASON = 96;

    private static final GroupLayout STRING_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("data"),
        JAVA_LONG.withName("size")
    );
    private static final GroupLayout CONFIGURATION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("application_id"),
        STRING_LAYOUT.withName("package_identity"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4)
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_PROVIDER, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("capability_bits"),
        JAVA_INT.withName("max_leases"),
        JAVA_INT.withName("max_leases_per_type"),
        JAVA_INT.withName("min_lease_seconds"),
        JAVA_INT.withName("max_lease_seconds"),
        JAVA_INT.withName("lease_count")
    );
    private static final GroupLayout STATE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("fact_bits"),
        JAVA_INT.withName("on_battery"),
        JAVA_INT.withName("battery_percent"),
        JAVA_INT.withName("thermal"),
        JAVA_INT.withName("locked")
    );
    private static final GroupLayout REQUEST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("lease_id"),
        STRING_LAYOUT.withName("reason"),
        JAVA_INT.withName("type"),
        JAVA_INT.withName("max_duration_seconds")
    );
    private static final GroupLayout LEASE_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("lease_id"),
        paddingLayout(3),
        JAVA_INT.withName("type"),
        JAVA_INT.withName("granted_seconds"),
        JAVA_INT.withName("remaining_seconds"),
        JAVA_INT.withName("renew_count"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout QUERY_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("lease_id"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4)
    );
    private static final GroupLayout RELEASE_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("released"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout LEASE_LAYOUT = MemoryLayout.structLayout(
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("lease_id"),
        sequenceLayout(MAX_REASON, JAVA_BYTE).withName("reason"),
        paddingLayout(3),
        JAVA_INT.withName("type"),
        JAVA_INT.withName("granted_seconds"),
        JAVA_INT.withName("remaining_seconds"),
        JAVA_INT.withName("renew_count"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout LEASE_LIST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("count"),
        JAVA_INT.withName("capacity"),
        ADDRESS.withName("leases")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("value"),
        JAVA_INT.withName("reason"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("sequence"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("lease_id"),
        sequenceLayout(MAX_REASON, JAVA_BYTE).withName("code"),
        paddingLayout(7)
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    public record Capabilities(
        int capabilityBits,
        int maxLeases,
        int maxLeasesPerType,
        int minLeaseSeconds,
        int maxLeaseSeconds,
        int leaseCount,
        String providerId
    ) {
        public Capabilities {
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    public record State(int factBits, boolean onBattery, int batteryPercent, int thermal, boolean locked) {}

    public record LeaseResult(String leaseId, int type, int grantedSeconds, int remainingSeconds, int renewCount) {
        public LeaseResult {
            Objects.requireNonNull(leaseId, "leaseId");
        }
    }

    public record FlatLease(String leaseId, String reason, int type, int grantedSeconds, int remainingSeconds, int renewCount) {
        public FlatLease {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    public record Event(int kind, int value, int reason, long sequence, String leaseId, String code) {
        public Event {
            Objects.requireNonNull(leaseId, "leaseId");
            Objects.requireNonNull(code, "code");
        }
    }

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class PowerNativeException extends RuntimeException {
        public final int status;

        PowerNativeException(String operation, int status, String statusName) {
            super("The power " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment leaseBuffer;
    private final MethodHandle capabilities;
    private final MethodHandle state;
    private final MethodHandle acquire;
    private final MethodHandle renew;
    private final MethodHandle release;
    private final MethodHandle leases;
    private final MethodHandle pollEvent;
    private final MethodHandle closeCall;
    private boolean closed;

    private PowerFfm(
        Arena arena,
        MemorySegment handle,
        MemorySegment leaseBuffer,
        MethodHandle capabilities,
        MethodHandle state,
        MethodHandle acquire,
        MethodHandle renew,
        MethodHandle release,
        MethodHandle leases,
        MethodHandle pollEvent,
        MethodHandle closeCall
    ) {
        this.arena = arena;
        this.handle = handle;
        this.leaseBuffer = leaseBuffer;
        this.capabilities = capabilities;
        this.state = state;
        this.acquire = acquire;
        this.renew = renew;
        this.release = release;
        this.leases = leases;
        this.pollEvent = pollEvent;
        this.closeCall = closeCall;
    }

    public static PowerFfm open(final Path library, final String applicationId, final String packageIdentity) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        verifyLayouts();
        try {
            final SymbolLookup lookup = library(library);
            final Arena arena = Arena.ofShared();
            final MemorySegment configuration = arena.allocate(CONFIGURATION_LAYOUT);
            writeString(configuration, 8L, applicationId.getBytes(StandardCharsets.UTF_8), arena);
            writeString(configuration, 24L, packageIdentity.getBytes(StandardCharsets.UTF_8), arena);
            configuration.set(JAVA_INT, 0L, (int) CONFIGURATION_LAYOUT.byteSize());
            configuration.set(JAVA_INT, 4L, ABI_VERSION);
            configuration.set(JAVA_INT, 40L, 0);
            final MemorySegment handle = arena.allocate(JAVA_LONG);
            final MethodHandle open = method(lookup, "kweb_power_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) {
                throw new PowerNativeException("open", status, statusName(status));
            }
            return new PowerFfm(
                arena,
                handle,
                arena.allocate(LEASE_LAYOUT, MAX_LEASES),
                method(lookup, "kweb_power_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_power_state", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_power_acquire", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_power_renew", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_power_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_power_leases", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_power_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_power_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("open", -1, error.getClass().getSimpleName());
        }
    }

    public Capabilities capabilities() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(CAPABILITIES_LAYOUT);
            result.set(JAVA_INT, 0L, (int) CAPABILITIES_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) capabilities.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("capabilities", status, statusName(status));
            }
            return new Capabilities(
                result.get(JAVA_INT, 8L + MAX_PROVIDER),
                result.get(JAVA_INT, 12L + MAX_PROVIDER),
                result.get(JAVA_INT, 16L + MAX_PROVIDER),
                result.get(JAVA_INT, 20L + MAX_PROVIDER),
                result.get(JAVA_INT, 24L + MAX_PROVIDER),
                result.get(JAVA_INT, 28L + MAX_PROVIDER),
                result.asSlice(8L, MAX_PROVIDER).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("capabilities", -1, error.getClass().getSimpleName());
        }
    }

    public State state() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(STATE_LAYOUT);
            result.set(JAVA_INT, 0L, (int) STATE_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) state.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("state", status, statusName(status));
            }
            return new State(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L) != 0,
                result.get(JAVA_INT, 16L),
                result.get(JAVA_INT, 20L),
                result.get(JAVA_INT, 24L) != 0
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("state", -1, error.getClass().getSimpleName());
        }
    }

    public LeaseResult acquire(final String leaseId, final String reason, final int type, final int seconds) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment request = arena.allocate(REQUEST_LAYOUT);
            writeString(request, 8L, leaseId.getBytes(StandardCharsets.UTF_8), arena);
            writeString(request, 24L, reason.getBytes(StandardCharsets.UTF_8), arena);
            request.set(JAVA_INT, 0L, (int) REQUEST_LAYOUT.byteSize());
            request.set(JAVA_INT, 4L, ABI_VERSION);
            request.set(JAVA_INT, 40L, type);
            request.set(JAVA_INT, 44L, seconds);
            final MemorySegment result = arena.allocate(LEASE_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) LEASE_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) acquire.invokeExact(handle.get(JAVA_LONG, 0L), request, result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("acquire", status, statusName(status));
            }
            return leaseResult(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("acquire", -1, error.getClass().getSimpleName());
        }
    }

    public LeaseResult renew(final String leaseId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment query = arena.allocate(QUERY_LAYOUT);
            writeQuery(query, leaseId, arena);
            final MemorySegment result = arena.allocate(LEASE_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) LEASE_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) renew.invokeExact(handle.get(JAVA_LONG, 0L), query, result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("renew", status, statusName(status));
            }
            return leaseResult(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("renew", -1, error.getClass().getSimpleName());
        }
    }

    public boolean release(final String leaseId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment query = arena.allocate(QUERY_LAYOUT);
            writeQuery(query, leaseId, arena);
            final MemorySegment result = arena.allocate(RELEASE_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) RELEASE_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) release.invokeExact(handle.get(JAVA_LONG, 0L), query, result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("release", status, statusName(status));
            }
            return result.get(JAVA_INT, 8L) != 0;
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("release", -1, error.getClass().getSimpleName());
        }
    }

    public List<FlatLease> leases() {
        ensureOpen();
        try {
            final MemorySegment result = arena.allocate(LEASE_LIST_LAYOUT);
            result.set(JAVA_INT, 0L, (int) LEASE_LIST_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            result.set(JAVA_INT, 12L, MAX_LEASES);
            result.set(ADDRESS, 16L, leaseBuffer);
            final int status = (int) leases.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new PowerNativeException("leases", status, statusName(status));
            }
            final int count = result.get(JAVA_INT, 8L);
            final List<FlatLease> values = new ArrayList<>(count);
            for (int index = 0; index < count; index += 1) {
                final MemorySegment record = leaseBuffer.asSlice(LEASE_LAYOUT.byteSize() * index, LEASE_LAYOUT.byteSize());
                values.add(new FlatLease(
                    record.asSlice(0L, MAX_ID).getString(0L),
                    record.asSlice(MAX_ID, MAX_REASON).getString(0L),
                    record.get(JAVA_INT, 228L),
                    record.get(JAVA_INT, 232L),
                    record.get(JAVA_INT, 236L),
                    record.get(JAVA_INT, 240L)
                ));
            }
            return List.copyOf(values);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("leases", -1, error.getClass().getSimpleName());
        }
    }

    /** Pops one ordered event, or returns null when the queue is empty. */
    public Event pollEvent() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(EVENT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) EVENT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) pollEvent.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status == STATUS_NO_EVENT) {
                return null;
            }
            if (status != STATUS_OK) {
                throw new PowerNativeException("poll-event", status, statusName(status));
            }
            return new Event(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L),
                result.get(JAVA_INT, 16L),
                result.get(JAVA_LONG, 24L),
                result.asSlice(32L, MAX_ID).getString(0L),
                result.asSlice(32L + MAX_ID, MAX_REASON).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("poll-event", -1, error.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            final int status = (int) closeCall.invokeExact(handle.get(JAVA_LONG, 0L));
            if (status != STATUS_OK && status != STATUS_OWNER_CLOSED) {
                throw new PowerNativeException("close", status, statusName(status));
            }
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PowerNativeException("close", -1, error.getClass().getSimpleName());
        } finally {
            arena.close();
        }
    }

    private static LeaseResult leaseResult(final MemorySegment result) {
        return new LeaseResult(
            result.asSlice(8L, MAX_ID).getString(0L),
            result.get(JAVA_INT, 140L),
            result.get(JAVA_INT, 144L),
            result.get(JAVA_INT, 148L),
            result.get(JAVA_INT, 152L)
        );
    }

    private static void writeQuery(final MemorySegment target, final String value, final Arena arena) {
        writeString(target, 8L, value.getBytes(StandardCharsets.UTF_8), arena);
        target.set(JAVA_INT, 0L, (int) QUERY_LAYOUT.byteSize());
        target.set(JAVA_INT, 4L, ABI_VERSION);
        target.set(JAVA_INT, 24L, 0);
    }

    private static void writeString(final MemorySegment target, final long offset, final byte[] value, final Arena arena) {
        final MemorySegment buffer = arena.allocate(Math.max(value.length, 1));
        buffer.asByteBuffer().put(value);
        target.set(ADDRESS, offset, buffer);
        target.set(JAVA_LONG, offset + 8, value.length);
    }

    private static MethodHandle method(final SymbolLookup lookup, final String name, final FunctionDescriptor descriptor) {
        return lookup.find(name)
            .map(symbol -> java.lang.foreign.Linker.nativeLinker().downcallHandle(symbol, descriptor))
            .orElseThrow(() -> new PowerNativeException(name, -1, "missing-symbol"));
    }

    private static SymbolLookup library(final Path library) {
        Objects.requireNonNull(library, "library");
        return LIBRARIES.computeIfAbsent(library.toAbsolutePath().normalize(), path -> {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (final IllegalArgumentException error) {
                throw new PowerNativeException("library", -1, "unloadable");
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new PowerNativeException("call", STATUS_OWNER_CLOSED, statusName(STATUS_OWNER_CLOSED));
        }
    }

    /** Fails fast when a layout disagrees with the C ABI this binding was built for. */
    private static void verifyLayouts() {
        checkLayout("configuration", CONFIGURATION_LAYOUT, 48L);
        checkLayout("capabilities", CAPABILITIES_LAYOUT, 96L);
        checkLayout("state", STATE_LAYOUT, 28L);
        checkLayout("request", REQUEST_LAYOUT, 48L);
        checkLayout("lease-result", LEASE_RESULT_LAYOUT, 160L);
        checkLayout("query", QUERY_LAYOUT, 32L);
        checkLayout("release-result", RELEASE_RESULT_LAYOUT, 16L);
        checkLayout("lease", LEASE_LAYOUT, 248L);
        checkLayout("lease-list", LEASE_LIST_LAYOUT, 24L);
        checkLayout("event", EVENT_LAYOUT, 264L);
    }

    private static void checkLayout(final String name, final MemoryLayout layout, final long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException(
                "The power " + name + " layout is " + layout.byteSize() + " bytes, expected " + expected + "."
            );
        }
    }

    public static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case STATUS_INVALID_ARGUMENT -> "invalid-argument";
            case STATUS_ABI_MISMATCH -> "abi-mismatch";
            case STATUS_PLATFORM_UNAVAILABLE -> "platform-unavailable";
            case STATUS_SERVICE_UNAVAILABLE -> "service-unavailable";
            case STATUS_TYPE_UNSUPPORTED -> "type-unsupported";
            case STATUS_DURATION_EXCEEDED -> "duration-exceeded";
            case STATUS_LIMIT_EXCEEDED -> "limit-exceeded";
            case STATUS_LEASE_UNKNOWN -> "lease-unknown";
            case STATUS_RENEWAL_EXCEEDED -> "renewal-exceeded";
            case STATUS_NATIVE_FAILED -> "native-failed";
            case STATUS_NO_EVENT -> "no-event";
            case STATUS_OWNER_CLOSED -> "owner-closed";
            case STATUS_ALREADY_OPEN -> "already-open";
            case STATUS_ALREADY_EXISTS -> "already-exists";
            case STATUS_BUFFER_TOO_SMALL -> "buffer-too-small";
            default -> "unknown";
        };
    }
}
