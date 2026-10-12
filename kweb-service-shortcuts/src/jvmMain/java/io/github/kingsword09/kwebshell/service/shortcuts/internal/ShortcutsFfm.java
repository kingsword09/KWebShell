package io.github.kingsword09.kwebshell.service.shortcuts.internal;

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
 * The JDK 25 FFM binding over the versioned shortcut C ABI. Layouts stay internal:
 * Kotlin owns validation, capability policy, and lifecycle, and never sees a
 * MemorySegment.
 */
public final class ShortcutsFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_PLATFORM_UNAVAILABLE = 3;
    public static final int STATUS_ACCELERATOR_UNSUPPORTED = 4;
    public static final int STATUS_MODIFIER_UNSUPPORTED = 5;
    public static final int STATUS_MEDIA_PERMISSION_DENIED = 6;
    public static final int STATUS_CONFLICT = 7;
    public static final int STATUS_RESERVED = 8;
    public static final int STATUS_DUPLICATE_ID = 9;
    public static final int STATUS_LIMIT_EXCEEDED = 10;
    public static final int STATUS_REGISTRATION_UNKNOWN = 11;
    public static final int STATUS_COMMAND_UNDECLARED = 12;
    public static final int STATUS_NATIVE_FAILED = 13;
    public static final int STATUS_NO_EVENT = 14;
    public static final int STATUS_OWNER_CLOSED = 15;
    public static final int STATUS_ALREADY_OPEN = 16;
    public static final int STATUS_BUFFER_TOO_SMALL = 17;

    public static final int MOD_CONTROL = 1;
    public static final int MOD_ALT = 1 << 1;
    public static final int MOD_SHIFT = 1 << 2;
    public static final int MOD_META = 1 << 3;

    public static final int KEY_NAMED = 1;
    public static final int KEY_MEDIA = 2;

    public static final int CAP_GLOBAL_REGISTRATION = 1;
    public static final int CAP_MEDIA_KEYS = 1 << 1;
    public static final int CAP_ATOMIC_REPLACEMENT = 1 << 2;
    public static final int CAP_INVOCATION_REPORTING = 1 << 3;
    public static final int CAP_SESSION_LOCK_RELEASE = 1 << 4;
    public static final int CAP_MEDIA_KEY_PERMISSION = 1 << 5;

    public static final int EVENT_INVOKED = 1;
    public static final int EVENT_LOST = 2;
    public static final int EVENT_REMOVED = 3;
    public static final int EVENT_FAILED = 4;

    public static final int SOURCE_PLATFORM = 1;
    public static final int SOURCE_RENDERER = 2;

    public static final int LOSS_CONFLICT = 1;
    public static final int LOSS_SESSION_LOCK = 2;
    public static final int LOSS_PERMISSION_REVOKED = 3;
    public static final int LOSS_PLATFORM_REMOVED = 4;

    public static final int REMOVAL_UNREGISTERED = 1;
    public static final int REMOVAL_REPLACED = 2;
    public static final int REMOVAL_OWNER_CLOSED = 3;
    public static final int REMOVAL_PLATFORM_REMOVED = 4;

    public static final int MAX_ID = 129;
    public static final int MAX_PROVIDER = 64;
    public static final int MAX_REGISTRATIONS = 32;
    private static final int MAX_ERROR = 96;

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
        JAVA_INT.withName("modifier_bits"),
        JAVA_INT.withName("media_key_bits"),
        JAVA_INT.withName("max_registrations"),
        JAVA_INT.withName("registration_count")
    );
    private static final GroupLayout REQUEST_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("registration_id"),
        STRING_LAYOUT.withName("command_id"),
        JAVA_INT.withName("modifier_bits"),
        JAVA_INT.withName("key_kind"),
        JAVA_INT.withName("key_code"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout REGISTRATION_LAYOUT = MemoryLayout.structLayout(
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("registration_id"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("command_id"),
        paddingLayout(2),
        JAVA_INT.withName("modifier_bits"),
        JAVA_INT.withName("key_kind"),
        JAVA_INT.withName("key_code"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout REGISTRATION_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("registration_id"),
        paddingLayout(7),
        JAVA_LONG.withName("sequence")
    );
    private static final GroupLayout REPLACEMENT_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("applied_count"),
        JAVA_INT.withName("removed_count"),
        JAVA_LONG.withName("sequence")
    );
    private static final GroupLayout UNREGISTRATION_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("was_registered"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout PRESENCE_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("registered"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout QUERY_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("value"),
        JAVA_INT.withName("reserved"),
        paddingLayout(4)
    );
    private static final GroupLayout LIST_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("count"),
        JAVA_INT.withName("capacity"),
        ADDRESS.withName("registrations")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("source"),
        JAVA_INT.withName("reason"),
        paddingLayout(4),
        JAVA_LONG.withName("sequence"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("registration_id"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("command_id"),
        sequenceLayout(MAX_ERROR, JAVA_BYTE).withName("code"),
        paddingLayout(6)
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    public record Capabilities(
        int capabilityBits,
        int modifierBits,
        int mediaKeyBits,
        int maxRegistrations,
        int registrationCount,
        String providerId
    ) {
        public Capabilities {
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    /** One registration request as the ABI accepts it. */
    public record Request(String registrationId, String commandId, int modifierBits, int keyKind, int keyCode) {
        public Request {
            Objects.requireNonNull(registrationId, "registrationId");
            Objects.requireNonNull(commandId, "commandId");
        }
    }

    /** One live registration as the ABI publishes it. */
    public record FlatRegistration(String registrationId, String commandId, int modifierBits, int keyKind, int keyCode) {
        public FlatRegistration {
            Objects.requireNonNull(registrationId, "registrationId");
            Objects.requireNonNull(commandId, "commandId");
        }
    }

    public record RegistrationResult(String registrationId, long sequence) {
        public RegistrationResult {
            Objects.requireNonNull(registrationId, "registrationId");
        }
    }

    public record ReplacementResult(int appliedCount, int removedCount, long sequence) {}

    public record Event(int kind, int source, int reason, long sequence, String registrationId, String commandId, String code) {
        public Event {
            Objects.requireNonNull(registrationId, "registrationId");
            Objects.requireNonNull(commandId, "commandId");
            Objects.requireNonNull(code, "code");
        }
    }

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class ShortcutsNativeException extends RuntimeException {
        public final int status;

        ShortcutsNativeException(String operation, int status, String statusName) {
            super("The shortcuts " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment registrationBuffer;
    private final MethodHandle capabilities;
    private final MethodHandle register;
    private final MethodHandle replaceAll;
    private final MethodHandle unregister;
    private final MethodHandle isRegistered;
    private final MethodHandle registrations;
    private final MethodHandle dispatch;
    private final MethodHandle pollEvent;
    private final MethodHandle closeCall;
    private boolean closed;

    private ShortcutsFfm(
        Arena arena,
        MemorySegment handle,
        MemorySegment registrationBuffer,
        MethodHandle capabilities,
        MethodHandle register,
        MethodHandle replaceAll,
        MethodHandle unregister,
        MethodHandle isRegistered,
        MethodHandle registrations,
        MethodHandle dispatch,
        MethodHandle pollEvent,
        MethodHandle closeCall
    ) {
        this.arena = arena;
        this.handle = handle;
        this.registrationBuffer = registrationBuffer;
        this.capabilities = capabilities;
        this.register = register;
        this.replaceAll = replaceAll;
        this.unregister = unregister;
        this.isRegistered = isRegistered;
        this.registrations = registrations;
        this.dispatch = dispatch;
        this.pollEvent = pollEvent;
        this.closeCall = closeCall;
    }

    public static ShortcutsFfm open(final Path library, final String applicationId, final String packageIdentity) {
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
            final MethodHandle open = method(lookup, "kweb_shortcuts_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("open", status, statusName(status));
            }
            final MemorySegment subscriptionBuffer = arena.allocate(REGISTRATION_LAYOUT, MAX_REGISTRATIONS);
            return new ShortcutsFfm(
                arena,
                handle,
                subscriptionBuffer,
                method(lookup, "kweb_shortcuts_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_shortcuts_register", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_shortcuts_replace_all", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_INT, ADDRESS)),
                method(lookup, "kweb_shortcuts_unregister", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_shortcuts_is_registered", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_shortcuts_registrations", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_shortcuts_dispatch", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_shortcuts_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_shortcuts_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("open", -1, error.getClass().getSimpleName());
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
                throw new ShortcutsNativeException("capabilities", status, statusName(status));
            }
            return new Capabilities(
                result.get(JAVA_INT, 8L + MAX_PROVIDER),
                result.get(JAVA_INT, 12L + MAX_PROVIDER),
                result.get(JAVA_INT, 16L + MAX_PROVIDER),
                result.get(JAVA_INT, 20L + MAX_PROVIDER),
                result.get(JAVA_INT, 24L + MAX_PROVIDER),
                result.asSlice(8L, MAX_PROVIDER).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("capabilities", -1, error.getClass().getSimpleName());
        }
    }

    public RegistrationResult register(final Request request) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment value = arena.allocate(REQUEST_LAYOUT);
            writeRequest(value, request, arena);
            final MemorySegment result = arena.allocate(REGISTRATION_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) REGISTRATION_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) register.invokeExact(handle.get(JAVA_LONG, 0L), value, result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("register", status, statusName(status));
            }
            return new RegistrationResult(result.asSlice(8L, MAX_ID).getString(0L), result.get(JAVA_LONG, 144L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("register", -1, error.getClass().getSimpleName());
        }
    }

    public ReplacementResult replaceAll(final List<Request> requests) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final int count = requests.size();
            final MemorySegment array = arena.allocate(REQUEST_LAYOUT, Math.max(count, 1));
            for (int index = 0; index < count; index += 1) {
                writeRequest(array.asSlice(REQUEST_LAYOUT.byteSize() * index, REQUEST_LAYOUT.byteSize()), requests.get(index), arena);
            }
            final MemorySegment result = arena.allocate(REPLACEMENT_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) REPLACEMENT_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) replaceAll.invokeExact(handle.get(JAVA_LONG, 0L), array, count, result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("replace-all", status, statusName(status));
            }
            return new ReplacementResult(result.get(JAVA_INT, 8L), result.get(JAVA_INT, 12L), result.get(JAVA_LONG, 16L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("replace-all", -1, error.getClass().getSimpleName());
        }
    }

    public boolean unregister(final String registrationId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment query = arena.allocate(QUERY_LAYOUT);
            writeQuery(query, registrationId, arena);
            final MemorySegment result = arena.allocate(UNREGISTRATION_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) UNREGISTRATION_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) unregister.invokeExact(handle.get(JAVA_LONG, 0L), query, result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("unregister", status, statusName(status));
            }
            return result.get(JAVA_INT, 8L) != 0;
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("unregister", -1, error.getClass().getSimpleName());
        }
    }

    public boolean isRegistered(final String registrationId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment query = arena.allocate(QUERY_LAYOUT);
            writeQuery(query, registrationId, arena);
            final MemorySegment result = arena.allocate(PRESENCE_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) PRESENCE_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) isRegistered.invokeExact(handle.get(JAVA_LONG, 0L), query, result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("is-registered", status, statusName(status));
            }
            return result.get(JAVA_INT, 8L) != 0;
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("is-registered", -1, error.getClass().getSimpleName());
        }
    }

    public List<FlatRegistration> registrations() {
        ensureOpen();
        try {
            final MemorySegment result = arena.allocate(LIST_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) LIST_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            result.set(JAVA_INT, 12L, MAX_REGISTRATIONS);
            result.set(ADDRESS, 16L, registrationBuffer);
            final int status = (int) registrations.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("registrations", status, statusName(status));
            }
            final int count = result.get(JAVA_INT, 8L);
            final List<FlatRegistration> values = new ArrayList<>(count);
            for (int index = 0; index < count; index += 1) {
                final MemorySegment record = registrationBuffer.asSlice(REGISTRATION_LAYOUT.byteSize() * index, REGISTRATION_LAYOUT.byteSize());
                values.add(new FlatRegistration(
                    record.asSlice(0L, MAX_ID).getString(0L),
                    record.asSlice(MAX_ID, MAX_ID).getString(0L),
                    record.get(JAVA_INT, 2L * MAX_ID + 2),
                    record.get(JAVA_INT, 2L * MAX_ID + 6),
                    record.get(JAVA_INT, 2L * MAX_ID + 10)
                ));
            }
            return List.copyOf(values);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("registrations", -1, error.getClass().getSimpleName());
        }
    }

    public RegistrationResult dispatch(final String commandId) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment query = arena.allocate(QUERY_LAYOUT);
            writeQuery(query, commandId, arena);
            final MemorySegment result = arena.allocate(REGISTRATION_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) REGISTRATION_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) dispatch.invokeExact(handle.get(JAVA_LONG, 0L), query, result);
            if (status != STATUS_OK) {
                throw new ShortcutsNativeException("dispatch", status, statusName(status));
            }
            return new RegistrationResult(result.asSlice(8L, MAX_ID).getString(0L), result.get(JAVA_LONG, 144L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("dispatch", -1, error.getClass().getSimpleName());
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
                throw new ShortcutsNativeException("poll-event", status, statusName(status));
            }
            return new Event(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L),
                result.get(JAVA_INT, 16L),
                result.get(JAVA_LONG, 24L),
                result.asSlice(32L, MAX_ID).getString(0L),
                result.asSlice(32L + MAX_ID, MAX_ID).getString(0L),
                result.asSlice(32L + 2L * MAX_ID, MAX_ERROR).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("poll-event", -1, error.getClass().getSimpleName());
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
                throw new ShortcutsNativeException("close", status, statusName(status));
            }
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ShortcutsNativeException("close", -1, error.getClass().getSimpleName());
        } finally {
            arena.close();
        }
    }

    private static void writeRequest(final MemorySegment target, final Request value, final Arena arena) {
        writeString(target, 8L, value.registrationId().getBytes(StandardCharsets.UTF_8), arena);
        writeString(target, 24L, value.commandId().getBytes(StandardCharsets.UTF_8), arena);
        target.set(JAVA_INT, 0L, (int) REQUEST_LAYOUT.byteSize());
        target.set(JAVA_INT, 4L, ABI_VERSION);
        target.set(JAVA_INT, 40L, value.modifierBits());
        target.set(JAVA_INT, 44L, value.keyKind());
        target.set(JAVA_INT, 48L, value.keyCode());
        target.set(JAVA_INT, 52L, 0);
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
            .orElseThrow(() -> new ShortcutsNativeException(name, -1, "missing-symbol"));
    }

    private static SymbolLookup library(final Path library) {
        Objects.requireNonNull(library, "library");
        return LIBRARIES.computeIfAbsent(library.toAbsolutePath().normalize(), path -> {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (final IllegalArgumentException error) {
                throw new ShortcutsNativeException("library", -1, "unloadable");
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new ShortcutsNativeException("call", STATUS_OWNER_CLOSED, statusName(STATUS_OWNER_CLOSED));
        }
    }

    /** Fails fast when a layout disagrees with the C ABI this binding was built for. */
    private static void verifyLayouts() {
        checkLayout("configuration", CONFIGURATION_LAYOUT, 48L);
        checkLayout("capabilities", CAPABILITIES_LAYOUT, 92L);
        checkLayout("request", REQUEST_LAYOUT, 56L);
        checkLayout("registration", REGISTRATION_LAYOUT, 276L);
        checkLayout("registration-result", REGISTRATION_RESULT_LAYOUT, 152L);
        checkLayout("replacement-result", REPLACEMENT_RESULT_LAYOUT, 24L);
        checkLayout("unregistration-result", UNREGISTRATION_RESULT_LAYOUT, 16L);
        checkLayout("presence-result", PRESENCE_RESULT_LAYOUT, 16L);
        checkLayout("query", QUERY_LAYOUT, 32L);
        checkLayout("list-result", LIST_RESULT_LAYOUT, 24L);
        checkLayout("event", EVENT_LAYOUT, 392L);
    }

    private static void checkLayout(final String name, final MemoryLayout layout, final long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException(
                "The shortcuts " + name + " layout is " + layout.byteSize() + " bytes, expected " + expected + "."
            );
        }
    }

    public static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case STATUS_INVALID_ARGUMENT -> "invalid-argument";
            case STATUS_ABI_MISMATCH -> "abi-mismatch";
            case STATUS_PLATFORM_UNAVAILABLE -> "platform-unavailable";
            case STATUS_ACCELERATOR_UNSUPPORTED -> "accelerator-unsupported";
            case STATUS_MODIFIER_UNSUPPORTED -> "modifier-unsupported";
            case STATUS_MEDIA_PERMISSION_DENIED -> "media-permission-denied";
            case STATUS_CONFLICT -> "conflict";
            case STATUS_RESERVED -> "reserved";
            case STATUS_DUPLICATE_ID -> "duplicate-id";
            case STATUS_LIMIT_EXCEEDED -> "limit-exceeded";
            case STATUS_REGISTRATION_UNKNOWN -> "registration-unknown";
            case STATUS_COMMAND_UNDECLARED -> "command-undeclared";
            case STATUS_NATIVE_FAILED -> "native-failed";
            case STATUS_NO_EVENT -> "no-event";
            case STATUS_OWNER_CLOSED -> "owner-closed";
            case STATUS_ALREADY_OPEN -> "already-open";
            case STATUS_BUFFER_TOO_SMALL -> "buffer-too-small";
            default -> "unknown";
        };
    }
}
