package io.github.kingsword09.kwebshell.service.screen.internal;

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
 * The JDK 25 FFM binding over the versioned screen C ABI. Layouts stay internal:
 * Kotlin owns validation, capability policy, and lifecycle, and never sees a
 * MemorySegment.
 */
public final class ScreenFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;

    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_PLATFORM_UNAVAILABLE = 3;
    public static final int STATUS_TOPOLOGY_UNAVAILABLE = 4;
    public static final int STATUS_TOPOLOGY_INVALID = 5;
    public static final int STATUS_DISPLAY_UNKNOWN = 6;
    public static final int STATUS_GEOMETRY_INVALID = 7;
    public static final int STATUS_POINT_INVALID = 8;
    public static final int STATUS_SPACE_UNSUPPORTED = 9;
    public static final int STATUS_CURSOR_UNAVAILABLE = 10;
    public static final int STATUS_NATIVE_FAILED = 11;
    public static final int STATUS_NO_EVENT = 12;
    public static final int STATUS_OWNER_CLOSED = 13;
    public static final int STATUS_ALREADY_OPEN = 14;
    public static final int STATUS_BUFFER_TOO_SMALL = 15;

    public static final int SESSION_WINDOWS = 1;
    public static final int SESSION_MACOS = 2;
    public static final int SESSION_X11 = 3;
    public static final int SESSION_WAYLAND = 4;

    public static final int SPACE_LOGICAL = 1;
    public static final int SPACE_NATIVE = 2;
    public static final int SPACE_PHYSICAL = 3;

    public static final int ROTATION_NONE = 1;
    public static final int ROTATION_QUAD_1 = 2;
    public static final int ROTATION_QUAD_2 = 3;
    public static final int ROTATION_QUAD_3 = 4;

    public static final int FACT_WORK_AREA = 1;
    public static final int FACT_SCALE = 1 << 1;
    public static final int FACT_ROTATION = 1 << 2;
    public static final int FACT_REFRESH_RATE = 1 << 3;
    public static final int FACT_COLOR_DEPTH = 1 << 4;
    public static final int FACT_PHYSICAL_SIZE = 1 << 5;
    public static final int FACT_PRIMARY = 1 << 6;

    public static final int CAP_WORK_AREA = 1;
    public static final int CAP_SCALE = 1 << 1;
    public static final int CAP_ROTATION = 1 << 2;
    public static final int CAP_REFRESH_RATE = 1 << 3;
    public static final int CAP_COLOR_DEPTH = 1 << 4;
    public static final int CAP_PHYSICAL_SIZE = 1 << 5;
    public static final int CAP_CURSOR = 1 << 6;
    public static final int CAP_TOPOLOGY_EVENTS = 1 << 7;
    public static final int CAP_MULTI_DISPLAY = 1 << 8;

    public static final int EVENT_TOPOLOGY_CHANGED = 1;
    public static final int EVENT_FAILED = 2;

    private static final int MAX_ID = 128;
    private static final int MAX_NAME = 128;
    private static final int MAX_PROVIDER = 64;
    private static final int MAX_DISPLAYS = 16;
    private static final int EVENT_IDS = 3 * MAX_DISPLAYS;
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
    private static final GroupLayout DISPLAY_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("id"),
        sequenceLayout(MAX_NAME, JAVA_BYTE).withName("name"),
        JAVA_INT.withName("fact_bits"),
        JAVA_INT.withName("session"),
        JAVA_INT.withName("left"),
        JAVA_INT.withName("top"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height"),
        JAVA_INT.withName("native_left"),
        JAVA_INT.withName("native_top"),
        JAVA_INT.withName("native_width"),
        JAVA_INT.withName("native_height"),
        JAVA_INT.withName("work_left"),
        JAVA_INT.withName("work_top"),
        JAVA_INT.withName("work_width"),
        JAVA_INT.withName("work_height"),
        JAVA_INT.withName("scale_permille"),
        JAVA_INT.withName("rotation"),
        JAVA_INT.withName("refresh_hertz"),
        JAVA_INT.withName("color_depth_bits"),
        JAVA_INT.withName("physical_width_mm"),
        JAVA_INT.withName("physical_height_mm")
    );
    private static final GroupLayout CAPABILITIES_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_PROVIDER, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("session"),
        JAVA_INT.withName("capability_bits"),
        JAVA_INT.withName("space_bits"),
        paddingLayout(4)
    );
    private static final GroupLayout TOPOLOGY_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        sequenceLayout(MAX_PROVIDER, JAVA_BYTE).withName("provider_id"),
        JAVA_INT.withName("session"),
        JAVA_INT.withName("display_count"),
        JAVA_INT.withName("capacity"),
        JAVA_INT.withName("reserved"),
        JAVA_LONG.withName("sequence"),
        ADDRESS.withName("displays")
    );
    private static final GroupLayout ID_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("found"),
        JAVA_INT.withName("reserved"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("id")
    );
    private static final GroupLayout POINT_RESULT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("space"),
        JAVA_INT.withName("display_known"),
        JAVA_INT.withName("x"),
        JAVA_INT.withName("y"),
        sequenceLayout(MAX_ID, JAVA_BYTE).withName("display_id")
    );
    private static final GroupLayout CONVERSION_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        STRING_LAYOUT.withName("display_id"),
        JAVA_INT.withName("from_space"),
        JAVA_INT.withName("to_space"),
        JAVA_INT.withName("x"),
        JAVA_INT.withName("y")
    );
    private static final GroupLayout EVENT_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("kind"),
        JAVA_INT.withName("added_count"),
        JAVA_INT.withName("removed_count"),
        JAVA_INT.withName("changed_count"),
        JAVA_LONG.withName("sequence"),
        sequenceLayout(MAX_ERROR, JAVA_BYTE).withName("code"),
        sequenceLayout(EVENT_IDS, sequenceLayout(MAX_ID, JAVA_BYTE)).withName("ids")
    );

    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();

    public record Capabilities(int session, int capabilityBits, int spaceBits, String providerId) {
        public Capabilities {
            Objects.requireNonNull(providerId, "providerId");
        }
    }

    /** One display as the ABI publishes it. */
    public record FlatDisplay(
        String id,
        String name,
        int factBits,
        int session,
        int left,
        int top,
        int width,
        int height,
        int nativeLeft,
        int nativeTop,
        int nativeWidth,
        int nativeHeight,
        int workLeft,
        int workTop,
        int workWidth,
        int workHeight,
        int scalePermille,
        int rotation,
        int refreshHertz,
        int colorDepthBits,
        int physicalWidthMm,
        int physicalHeightMm
    ) {}

    public record Topology(String providerId, int session, long sequence, List<FlatDisplay> displays) {}

    public record IdResult(boolean found, String id) {}

    public record PointResult(int space, boolean displayKnown, int x, int y, String displayId) {}

    public record Event(int kind, long sequence, List<String> added, List<String> removed, List<String> changed, String code) {
        public Event {
            Objects.requireNonNull(code, "code");
        }
    }

    /** One typed native failure; Kotlin maps the status to a published code. */
    public static final class ScreenNativeException extends RuntimeException {
        public final int status;

        ScreenNativeException(String operation, int status, String statusName) {
            super("The screen " + operation + " call failed with " + statusName + " (" + status + ").");
            this.status = status;
        }
    }

    private final Arena arena;
    private final MemorySegment handle;
    private final MemorySegment topologyBuffer;
    private final MemorySegment displayRecords;
    private final MethodHandle capabilities;
    private final MethodHandle topology;
    private final MethodHandle cursor;
    private final MethodHandle displayAt;
    private final MethodHandle nearest;
    private final MethodHandle convert;
    private final MethodHandle pollEvent;
    private final MethodHandle closeCall;
    private boolean closed;

    private ScreenFfm(
        Arena arena,
        MemorySegment handle,
        MemorySegment topologyBuffer,
        MemorySegment displayRecords,
        MethodHandle capabilities,
        MethodHandle topology,
        MethodHandle cursor,
        MethodHandle displayAt,
        MethodHandle nearest,
        MethodHandle convert,
        MethodHandle pollEvent,
        MethodHandle closeCall
    ) {
        this.arena = arena;
        this.handle = handle;
        this.topologyBuffer = topologyBuffer;
        this.displayRecords = displayRecords;
        this.capabilities = capabilities;
        this.topology = topology;
        this.cursor = cursor;
        this.displayAt = displayAt;
        this.nearest = nearest;
        this.convert = convert;
        this.pollEvent = pollEvent;
        this.closeCall = closeCall;
    }

    public static ScreenFfm open(final Path library, final String applicationId, final String packageIdentity) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(packageIdentity, "packageIdentity");
        verifyLayouts();
        try {
            final SymbolLookup lookup = library(library);
            final Arena arena = Arena.ofShared();
            final MemorySegment configuration = arena.allocate(CONFIGURATION_LAYOUT);
            final byte[] applicationIdBytes = applicationId.getBytes(StandardCharsets.UTF_8);
            final byte[] packageIdentityBytes = packageIdentity.getBytes(StandardCharsets.UTF_8);
            writeString(configuration, 8L, applicationIdBytes, arena);
            writeString(configuration, 24L, packageIdentityBytes, arena);
            configuration.set(JAVA_INT, 0L, (int) CONFIGURATION_LAYOUT.byteSize());
            configuration.set(JAVA_INT, 4L, ABI_VERSION);
            configuration.set(JAVA_INT, 40L, 0);
            final MemorySegment handle = arena.allocate(JAVA_LONG);
            final MethodHandle open = method(lookup, "kweb_screen_open",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            final int status = (int) open.invokeExact(configuration, handle);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("open", status, statusName(status));
            }
            final MemorySegment topologyBuffer = arena.allocate(TOPOLOGY_LAYOUT);
            final MemorySegment displays = arena.allocate(DISPLAY_LAYOUT, MAX_DISPLAYS);
            return new ScreenFfm(
                arena,
                handle,
                topologyBuffer,
                displays,
                method(lookup, "kweb_screen_capabilities", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_screen_topology", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_screen_cursor", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_screen_display_at",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
                method(lookup, "kweb_screen_nearest",
                    FunctionDescriptor.of(JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS)),
                method(lookup, "kweb_screen_convert", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, ADDRESS)),
                method(lookup, "kweb_screen_poll_event", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS)),
                method(lookup, "kweb_screen_close", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("open", -1, error.getClass().getSimpleName());
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
                throw new ScreenNativeException("capabilities", status, statusName(status));
            }
            return new Capabilities(
                result.get(JAVA_INT, 8L + MAX_PROVIDER),
                result.get(JAVA_INT, 12L + MAX_PROVIDER),
                result.get(JAVA_INT, 16L + MAX_PROVIDER),
                result.asSlice(8L, MAX_PROVIDER).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("capabilities", -1, error.getClass().getSimpleName());
        }
    }

    public Topology topology() {
        ensureOpen();
        try {
            topologyBuffer.set(JAVA_INT, 0L, (int) TOPOLOGY_LAYOUT.byteSize());
            topologyBuffer.set(JAVA_INT, 4L, ABI_VERSION);
            topologyBuffer.set(ADDRESS, 96L, displayRecords);
            topologyBuffer.set(JAVA_INT, 80L, MAX_DISPLAYS);
            final int status = (int) topology.invokeExact(handle.get(JAVA_LONG, 0L), topologyBuffer);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("topology", status, statusName(status));
            }
            final int count = topologyBuffer.get(JAVA_INT, 76L);
            final List<FlatDisplay> displays = new ArrayList<>(count);
            for (int index = 0; index < count; index += 1) {
                final MemorySegment record = displayRecords.asSlice(DISPLAY_LAYOUT.byteSize() * index, DISPLAY_LAYOUT.byteSize());
                final String id = record.asSlice(8L, MAX_ID).getString(0L);
                final String name = record.asSlice(8L + MAX_ID, MAX_NAME).getString(0L);
                final int base = 8 + MAX_ID + MAX_NAME;
                displays.add(new FlatDisplay(
                    id,
                    name,
                    record.get(JAVA_INT, base),
                    record.get(JAVA_INT, base + 4),
                    record.get(JAVA_INT, base + 8),
                    record.get(JAVA_INT, base + 12),
                    record.get(JAVA_INT, base + 16),
                    record.get(JAVA_INT, base + 20),
                    record.get(JAVA_INT, base + 24),
                    record.get(JAVA_INT, base + 28),
                    record.get(JAVA_INT, base + 32),
                    record.get(JAVA_INT, base + 36),
                    record.get(JAVA_INT, base + 40),
                    record.get(JAVA_INT, base + 44),
                    record.get(JAVA_INT, base + 48),
                    record.get(JAVA_INT, base + 52),
                    record.get(JAVA_INT, base + 56),
                    record.get(JAVA_INT, base + 60),
                    record.get(JAVA_INT, base + 64),
                    record.get(JAVA_INT, base + 68),
                    record.get(JAVA_INT, base + 72),
                    record.get(JAVA_INT, base + 76)
                ));
            }
            return new Topology(
                topologyBuffer.asSlice(8L, MAX_PROVIDER).getString(0L),
                topologyBuffer.get(JAVA_INT, 8 + MAX_PROVIDER),
                topologyBuffer.get(JAVA_LONG, 88L),
                List.copyOf(displays)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("topology", -1, error.getClass().getSimpleName());
        }
    }

    public PointResult cursor() {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(POINT_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) POINT_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) cursor.invokeExact(handle.get(JAVA_LONG, 0L), result);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("cursor", status, statusName(status));
            }
            return pointResult(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("cursor", -1, error.getClass().getSimpleName());
        }
    }

    public IdResult displayAt(final int x, final int y, final int space) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(ID_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) ID_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) displayAt.invokeExact(handle.get(JAVA_LONG, 0L), x, y, space, result);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("display-at", status, statusName(status));
            }
            return new IdResult(result.get(JAVA_INT, 8L) != 0, result.asSlice(16L, MAX_ID).getString(0L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("display-at", -1, error.getClass().getSimpleName());
        }
    }

    public IdResult nearest(final int x, final int y, final int space) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment result = arena.allocate(ID_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) ID_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) nearest.invokeExact(handle.get(JAVA_LONG, 0L), x, y, space, result);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("nearest", status, statusName(status));
            }
            return new IdResult(result.get(JAVA_INT, 8L) != 0, result.asSlice(16L, MAX_ID).getString(0L));
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("nearest", -1, error.getClass().getSimpleName());
        }
    }

    public PointResult convert(final String displayId, final int fromSpace, final int toSpace, final int x, final int y) {
        ensureOpen();
        try (final Arena arena = Arena.ofConfined()) {
            final MemorySegment request = arena.allocate(CONVERSION_LAYOUT);
            final byte[] idBytes = displayId.getBytes(StandardCharsets.UTF_8);
            writeString(request, 8L, idBytes, arena);
            request.set(JAVA_INT, 0L, (int) CONVERSION_LAYOUT.byteSize());
            request.set(JAVA_INT, 4L, ABI_VERSION);
            request.set(JAVA_INT, 24L, fromSpace);
            request.set(JAVA_INT, 28L, toSpace);
            request.set(JAVA_INT, 32L, x);
            request.set(JAVA_INT, 36L, y);
            final MemorySegment result = arena.allocate(POINT_RESULT_LAYOUT);
            result.set(JAVA_INT, 0L, (int) POINT_RESULT_LAYOUT.byteSize());
            result.set(JAVA_INT, 4L, ABI_VERSION);
            final int status = (int) convert.invokeExact(handle.get(JAVA_LONG, 0L), request, result);
            if (status != STATUS_OK) {
                throw new ScreenNativeException("convert", status, statusName(status));
            }
            return pointResult(result);
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("convert", -1, error.getClass().getSimpleName());
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
                throw new ScreenNativeException("poll-event", status, statusName(status));
            }
            final int added = result.get(JAVA_INT, 12L);
            final int removed = result.get(JAVA_INT, 16L);
            final int changed = result.get(JAVA_INT, 20L);
            final List<String> addedIds = new ArrayList<>(added);
            final List<String> removedIds = new ArrayList<>(removed);
            final List<String> changedIds = new ArrayList<>(changed);
            int cursor = 0;
            for (int index = 0; index < added; index += 1, cursor += 1) {
                addedIds.add(result.asSlice(128L + (long) cursor * MAX_ID, MAX_ID).getString(0L));
            }
            for (int index = 0; index < removed; index += 1, cursor += 1) {
                removedIds.add(result.asSlice(128L + (long) cursor * MAX_ID, MAX_ID).getString(0L));
            }
            for (int index = 0; index < changed; index += 1, cursor += 1) {
                changedIds.add(result.asSlice(128L + (long) cursor * MAX_ID, MAX_ID).getString(0L));
            }
            return new Event(
                result.get(JAVA_INT, 8L),
                result.get(JAVA_LONG, 24L),
                List.copyOf(addedIds),
                List.copyOf(removedIds),
                List.copyOf(changedIds),
                result.asSlice(32L, MAX_ERROR).getString(0L)
            );
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("poll-event", -1, error.getClass().getSimpleName());
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
                throw new ScreenNativeException("close", status, statusName(status));
            }
        } catch (final RuntimeException error) {
            throw error;
        } catch (final Throwable error) {
            throw new ScreenNativeException("close", -1, error.getClass().getSimpleName());
        } finally {
            arena.close();
        }
    }

    private static PointResult pointResult(final MemorySegment result) {
        return new PointResult(
            result.get(JAVA_INT, 8L),
            result.get(JAVA_INT, 12L) != 0,
            result.get(JAVA_INT, 16L),
            result.get(JAVA_INT, 20L),
            result.asSlice(24L, MAX_ID).getString(0L)
        );
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
            .orElseThrow(() -> new ScreenNativeException(name, -1, "missing-symbol"));
    }

    private static SymbolLookup library(final Path library) {
        Objects.requireNonNull(library, "library");
        return LIBRARIES.computeIfAbsent(library.toAbsolutePath().normalize(), path -> {
            try {
                return SymbolLookup.libraryLookup(path, Arena.global());
            } catch (final IllegalArgumentException error) {
                throw new ScreenNativeException("library", -1, "unloadable");
            }
        });
    }

    private void ensureOpen() {
        if (closed) {
            throw new ScreenNativeException("call", STATUS_OWNER_CLOSED, statusName(STATUS_OWNER_CLOSED));
        }
    }

    /** Fails fast when a layout disagrees with the C ABI this binding was built for. */
    private static void verifyLayouts() {
        checkLayout("configuration", CONFIGURATION_LAYOUT, 48L);
        checkLayout("capabilities", CAPABILITIES_LAYOUT, 88L);
        checkLayout("display", DISPLAY_LAYOUT, 344L);
        checkLayout("topology", TOPOLOGY_LAYOUT, 104L);
        checkLayout("id-result", ID_RESULT_LAYOUT, 144L);
        checkLayout("point-result", POINT_RESULT_LAYOUT, 152L);
        checkLayout("conversion", CONVERSION_LAYOUT, 40L);
        checkLayout("event", EVENT_LAYOUT, 6272L);
    }

    private static void checkLayout(final String name, final MemoryLayout layout, final long expected) {
        if (layout.byteSize() != expected) {
            throw new IllegalStateException(
                "The screen " + name + " layout is " + layout.byteSize() + " bytes, expected " + expected + "."
            );
        }
    }

    public static String statusName(final int status) {
        return switch (status) {
            case STATUS_OK -> "ok";
            case STATUS_INVALID_ARGUMENT -> "invalid-argument";
            case STATUS_ABI_MISMATCH -> "abi-mismatch";
            case STATUS_PLATFORM_UNAVAILABLE -> "platform-unavailable";
            case STATUS_TOPOLOGY_UNAVAILABLE -> "topology-unavailable";
            case STATUS_TOPOLOGY_INVALID -> "topology-invalid";
            case STATUS_DISPLAY_UNKNOWN -> "display-unknown";
            case STATUS_GEOMETRY_INVALID -> "geometry-invalid";
            case STATUS_POINT_INVALID -> "point-invalid";
            case STATUS_SPACE_UNSUPPORTED -> "space-unsupported";
            case STATUS_CURSOR_UNAVAILABLE -> "cursor-unavailable";
            case STATUS_NATIVE_FAILED -> "native-failed";
            case STATUS_NO_EVENT -> "no-event";
            case STATUS_OWNER_CLOSED -> "owner-closed";
            case STATUS_ALREADY_OPEN -> "already-open";
            case STATUS_BUFFER_TOO_SMALL -> "buffer-too-small";
            default -> "unknown";
        };
    }
}
