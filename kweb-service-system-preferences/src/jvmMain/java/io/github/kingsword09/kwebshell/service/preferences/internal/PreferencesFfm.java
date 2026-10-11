package io.github.kingsword09.kwebshell.service.preferences.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.MemoryLayout.sequenceLayout;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The JDK 25 FFM binding over the versioned system-preferences C ABI. Layouts
 * stay internal: Kotlin owns validation, policy, and lifecycle, and never sees a
 * MemorySegment.
 */
public final class PreferencesFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_PLATFORM_UNAVAILABLE = 3;
    public static final int STATUS_FACILITY_UNSUPPORTED = 4;
    public static final int STATUS_SENSITIVE_KEY_UNKNOWN = 5;
    public static final int STATUS_APPEARANCE_INVALID = 6;
    public static final int STATUS_APPEARANCE_UNSUPPORTED = 7;
    public static final int STATUS_NATIVE_FAILED = 8;
    public static final int STATUS_NO_EVENT = 9;
    public static final int STATUS_OWNER_CLOSED = 10;
    public static final int STATUS_ALREADY_OPEN = 11;

    public static final int FACT_APPEARANCE_SOURCE = 1;
    public static final int FACT_COLOR_SCHEME = 1 << 1;
    public static final int FACT_CONTRAST = 1 << 2;
    public static final int FACT_REDUCED_MOTION = 1 << 3;
    public static final int FACT_REDUCED_TRANSPARENCY = 1 << 4;
    public static final int FACT_DIFFERENTIATE_WITHOUT_COLOR = 1 << 5;
    public static final int FACT_INVERT_COLORS = 1 << 6;
    public static final int FACT_ACCENT_COLOR = 1 << 7;
    public static final int FACT_TEXT_SCALE = 1 << 8;
    public static final int FACT_SCREEN_READER = 1 << 9;

    public static final int KEY_MACOS_VOICEOVER = 1;
    public static final int KEY_WINDOWS_SCREEN_READER = 1 << 1;
    public static final int KEY_LINUX_A11Y = 1 << 2;

    public static final int SCHEME_LIGHT = 1;
    public static final int SCHEME_DARK = 2;

    public static final int CONTRAST_NONE = 1;
    public static final int CONTRAST_MORE = 2;
    public static final int CONTRAST_FORCED_COLORS = 3;

    public static final int TRI_UNKNOWN = 0;
    public static final int TRI_FALSE = 1;
    public static final int TRI_TRUE = 2;

    public static final int ACCENT_SYSTEM_COLORIZATION = 1;
    public static final int ACCENT_CONTROL_ACCENT = 2;
    public static final int ACCENT_DESKTOP_PORTAL = 3;

    public static final int APPEARANCE_SYSTEM = 1;
    public static final int APPEARANCE_LIGHT = 2;
    public static final int APPEARANCE_DARK = 3;

    public static final int EVENT_CHANGED = 1;
    public static final int EVENT_FAILED = 2;

    private static final int MAX_PROVIDER = 64;
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
        JAVA_INT.withName("sensitive_key_bits"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_PROVIDER, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("published_fact_bits"),
        JAVA_INT.withName("live_fact_bits"),
        JAVA_INT.withName("sensitive_key_bits"),
        JAVA_INT.withName("reserved")
    );
    private static final GroupLayout SNAPSHOT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("fact_bits"),
        JAVA_INT.withName("color_scheme"),
        JAVA_INT.withName("contrast"),
        JAVA_INT.withName("appearance_source"),
        JAVA_INT.withName("reduced_motion"),
        JAVA_INT.withName("reduced_transparency"),
        JAVA_INT.withName("differentiate_without_color"),
        JAVA_INT.withName("invert_colors"),
        JAVA_INT.withName("screen_reader"),
        JAVA_INT.withName("accent_source"),
        JAVA_INT.withName("accent_red"),
        JAVA_INT.withName("accent_green"),
        JAVA_INT.withName("accent_blue"),
        JAVA_INT.withName("text_scale_percent"),
        JAVA_LONG.withName("sequence")
    );
    private static final GroupLayout APPEARANCE_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("requested"),
        JAVA_INT.withName("effective"),
        JAVA_LONG.withName("sequence")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("changed_fact_bits"),
        JAVA_LONG.withName("sequence"),
        sequenceLayout(MAX_ERROR, JAVA_BYTE).withName("code")
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    public record Capabilities(int publishedFacts, int liveFacts, int sensitiveKeys, String providerId) {
        public Capabilities {
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    public record Snapshot(
        int factBits,
        int colorScheme,
        int contrast,
        int appearanceSource,
        int reducedMotion,
        int reducedTransparency,
        int differentiateWithoutColor,
        int invertColors,
        int screenReader,
        int accentSource,
        int accentRed,
        int accentGreen,
        int accentBlue,
        int textScalePercent,
        long sequence
    ) {}

    public record Appearance(int requested, int effective, long sequence) {}

    public record Event(int kind, int changedFacts, long sequence, String code) {
        public Event {
            Objects.requireNonNull(code, "code");
        }
    }

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class PreferencesNativeException extends RuntimeException {
        public final int status;

        PreferencesNativeException(String operation, int status, String statusName) {
            super("The system-preferences " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment handle;
    private final MethodHandle capabilities;
    private final MethodHandle snapshot;
    private final MethodHandle requestAppearanceCall;
    private final MethodHandle pollEventCall;
    private final MethodHandle closeCall;
    private boolean closed;

    private PreferencesFfm(
        Arena arena,
        MemorySegment handle,
        MethodHandle capabilities,
        MethodHandle snapshot,
        MethodHandle requestAppearanceCall,
        MethodHandle pollEventCall,
        MethodHandle closeCall
    ) {
        this.arena = arena;
        this.handle = handle;
        this.capabilities = capabilities;
        this.snapshot = snapshot;
        this.requestAppearanceCall = requestAppearanceCall;
        this.pollEventCall = pollEventCall;
        this.closeCall = closeCall;
    }

    /** Loads the provider library exactly once per path and asserts the ABI revision. */
    public static int abiVersion(Path library) {
        try {
            final SymbolLookup lookup = library(library);
            final MethodHandle abiVersion = lookup.find("kweb_preferences_abi_version")
                .map(symbol -> linker().downcallHandle(symbol, FunctionDescriptor.of(JAVA_INT)))
                .orElseThrow(() -> new PreferencesNativeException("abi-version", -1, "missing-symbol"));
            final int version = (int) abiVersion.invokeExact();
            if (version != ABI_VERSION) {
                throw new PreferencesNativeException("abi-version", version, "unsupported-abi");
            }
            return version;
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("abi-version", -1, error.getClass().getSimpleName());
        }
    }

    public static PreferencesFfm open(
        final Path library,
        final String applicationId,
        final String packageIdentity,
        final int sensitiveKeys
    ) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        verifyLayouts();
        try {
            final SymbolLookup lookup = library(library);
            final Arena arena = Arena.ofShared();
            final MemorySegment configuration = arena.allocate(CONFIGURATION_LAYOUT);
            final byte[] applicationIdBytes = applicationId.getBytes(StandardCharsets.UTF_8);
            final byte[] packageIdentityBytes = packageIdentity.getBytes(StandardCharsets.UTF_8);
            final MemorySegment applicationIdSegment = arena.allocate(applicationIdBytes.length);
            applicationIdSegment.asByteBuffer().put(applicationIdBytes);
            final MemorySegment packageIdentitySegment = arena.allocate(packageIdentityBytes.length);
            packageIdentitySegment.asByteBuffer().put(packageIdentityBytes);
            final MemorySegment applicationIdString = arena.allocate(STRING_LAYOUT);
            applicationIdString.set(ADDRESS, 0L, applicationIdSegment);
            applicationIdString.set(JAVA_LONG, 8L, applicationIdBytes.length);
            final MemorySegment packageIdentityString = arena.allocate(STRING_LAYOUT);
            packageIdentityString.set(ADDRESS, 0L, packageIdentitySegment);
            packageIdentityString.set(JAVA_LONG, 8L, packageIdentityBytes.length);
            configuration.set(JAVA_INT, 0L, (int) CONFIGURATION_LAYOUT.byteSize());
            configuration.set(JAVA_INT, 4L, ABI_VERSION);
            configuration.asSlice(8L, STRING_LAYOUT.byteSize()).copyFrom(applicationIdString);
            configuration.asSlice(24L, STRING_LAYOUT.byteSize()).copyFrom(packageIdentityString);
            configuration.set(JAVA_INT, 40L, sensitiveKeys);
            configuration.set(JAVA_INT, 44L, 0);
            final MemorySegment handle = arena.allocate(JAVA_LONG);
            final MethodHandle open = lookup.find("kweb_preferences_open")
                .map(symbol -> linker().downcallHandle(
                    symbol,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)
                ))
                .orElseThrow(() -> new PreferencesNativeException("open", -1, "missing-symbol"));
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) {
                throw new PreferencesNativeException("open", status, statusName(status));
            }
            return new PreferencesFfm(
                arena,
                handle,
                method(lookup, "kweb_preferences_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_preferences_snapshot", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_preferences_request_appearance",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS)),
                method(lookup, "kweb_preferences_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_preferences_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("open", -1, error.getClass().getSimpleName());
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
                throw new PreferencesNativeException("capabilities", status, statusName(status));
            }
            final String providerId = result.asSlice(8L, MAX_PROVIDER).getString(0L);
            return new Capabilities(
                result.get(JAVA_INT, 8L + MAX_PROVIDER),
                result.get(JAVA_INT, 12L + MAX_PROVIDER),
                result.get(JAVA_INT, 16L + MAX_PROVIDER),
                providerId
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("capabilities", -1, error.getClass().getSimpleName());
        }
    }

    public Snapshot snapshot() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(SNAPSHOT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) SNAPSHOT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) snapshot.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new PreferencesNativeException("snapshot", status, statusName(status));
            }
            return new Snapshot(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L),
                result.get(JAVA_INT, 16L),
                result.get(JAVA_INT, 20L),
                result.get(JAVA_INT, 24L),
                result.get(JAVA_INT, 28L),
                result.get(JAVA_INT, 32L),
                result.get(JAVA_INT, 36L),
                result.get(JAVA_INT, 40L),
                result.get(JAVA_INT, 44L),
                result.get(JAVA_INT, 48L),
                result.get(JAVA_INT, 52L),
                result.get(JAVA_INT, 56L),
                result.get(JAVA_INT, 60L),
                result.get(JAVA_LONG, 64L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("snapshot", -1, error.getClass().getSimpleName());
        }
    }

    public Appearance requestAppearance(final int source) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(APPEARANCE_LAYOUT);
            result.set(JAVA_INT, 0L, (int) APPEARANCE_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) requestAppearanceCall.invokeExact(handle.get(JAVA_LONG, 0L), source, result);
            if (status != STATUS_OK) {
                throw new PreferencesNativeException("request-appearance", status, statusName(status));
            }
            return new Appearance(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L),
                result.get(JAVA_LONG, 16L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("request-appearance", -1, error.getClass().getSimpleName());
        }
    }

    /** Pops one ordered event, or returns null when the queue is empty. */
    public Event pollEvent() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(EVENT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) EVENT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) pollEventCall.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status == STATUS_NO_EVENT) {
                return null;
            }
            if (status != STATUS_OK) {
                throw new PreferencesNativeException("poll-event", status, statusName(status));
            }
            return new Event(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_INT, 12L),
                result.get(JAVA_LONG, 16L),
                result.asSlice(24L, MAX_ERROR).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("poll-event", -1, error.getClass().getSimpleName());
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
            if (status != STATUS_OK) {
                throw new PreferencesNativeException("close", status, statusName(status));
            }
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new PreferencesNativeException("close", -1, error.getClass().getSimpleName());
        } finally {
            arena.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new PreferencesNativeException("call", STATUS_OWNER_CLOSED, statusName(STATUS_OWNER_CLOSED));
        }
    }

    private static MethodHandle method(
        final SymbolLookup lookup,
        final String name,
        final FunctionDescriptor descriptor
    ) {
        return lookup.find(name)
            .map(symbol -> linker().downcallHandle(symbol, descriptor))
            .orElseThrow(() -> new PreferencesNativeException(name, -1, "missing-symbol"));
    }

    private static java.lang.foreign.Linker linker() {
        return java.lang.foreign.Linker.nativeLinker();
    }

    private static SymbolLookup library(final Path library) {
        Objects.requireNonNull(library, "library");
        return LIBRARIES.computeIfAbsent(library.toAbsolutePath().normalize(), path -> {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (final IllegalArgumentException error) {
                throw new PreferencesNativeException("library", -1, "unloadable");
            }
        });
    }

    /** Fails fast when a layout disagrees with the C ABI this binding was built for. */
    private static void verifyLayouts() {
        checkLayout("configuration", CONFIGURATION_LAYOUT, 48L);
        checkLayout("capabilities", CAPABILITIES_LAYOUT, 88L);
        checkLayout("snapshot", SNAPSHOT_LAYOUT, 72L);
        checkLayout("appearance", APPEARANCE_LAYOUT, 24L);
        checkLayout("event", EVENT_LAYOUT, 120L);
    }

    private static void checkLayout(final String name, final MemoryLayout layout, final long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException(
                "The system-preferences " + name + " layout is " + layout.byteSize() + " bytes, expected " + expected + "."
            );
        }
    }

    public static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case STATUS_INVALID_ARGUMENT -> "invalid-argument";
            case STATUS_ABI_MISMATCH -> "abi-mismatch";
            case STATUS_PLATFORM_UNAVAILABLE -> "platform-unavailable";
            case STATUS_FACILITY_UNSUPPORTED -> "facility-unsupported";
            case STATUS_SENSITIVE_KEY_UNKNOWN -> "sensitive-key-unknown";
            case STATUS_APPEARANCE_INVALID -> "appearance-invalid";
            case STATUS_APPEARANCE_UNSUPPORTED -> "appearance-unsupported";
            case STATUS_NATIVE_FAILED -> "native-failed";
            case STATUS_NO_EVENT -> "no-event";
            case STATUS_OWNER_CLOSED -> "owner-closed";
            case STATUS_ALREADY_OPEN -> "already-open";
            default -> "unknown";
        };
    }
}
