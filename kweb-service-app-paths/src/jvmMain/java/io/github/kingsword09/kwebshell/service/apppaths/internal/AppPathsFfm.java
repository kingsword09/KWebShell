package io.github.kingsword09.kwebshell.service.apppaths.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public final class AppPathsFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_ALLOCATION_FAILED = 3;
    public static final int STATUS_PATH_KIND_UNKNOWN = 4;
    public static final int STATUS_NATIVE_UNAVAILABLE = 5;
    public static final int STATUS_PATH_INVALID = 6;
    public static final int STATUS_NATIVE_FAILED = 7;
    public static final long MAXIMUM_TEXT_SIZE = 16 * 1024L;

    private static final ValueLayout.OfInt UINT32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong SIZE_T = ValueLayout.JAVA_LONG;
    private static final AddressLayout POINTER = ValueLayout.ADDRESS;
    private static final GroupLayout STRING_VIEW = MemoryLayout.structLayout(
        POINTER.withName("data"),
        SIZE_T.withName("size")
    );
    private static final GroupLayout REQUEST = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("kind"),
        UINT32.withName("reserved"),
        STRING_VIEW.withName("application_id"),
        STRING_VIEW.withName("application_data_root"),
        STRING_VIEW.withName("session_data_root")
    );
    private static final GroupLayout RESULT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("kind"),
        UINT32.withName("reserved"),
        STRING_VIEW.withName("path"),
        STRING_VIEW.withName("source")
    );

    private static final long STRING_DATA_OFFSET = STRING_VIEW.byteOffset(groupElement("data"));
    private static final long STRING_SIZE_OFFSET = STRING_VIEW.byteOffset(groupElement("size"));

    private final Path libraryPath;
    private final Arena arena;
    private final MethodHandle resolve;
    private final MethodHandle free;
    private final MethodHandle statusName;

    private AppPathsFfm(
        Path libraryPath,
        Arena arena,
        MethodHandle resolve,
        MethodHandle free,
        MethodHandle statusName
    ) {
        this.libraryPath = libraryPath;
        this.arena = arena;
        this.resolve = resolve;
        this.free = free;
        this.statusName = statusName;
    }

    public static AppPathsFfm open(Path requestedPath) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The KWebAppPaths native library must be an absolute regular file.");
        }
        final Path libraryPath;
        try {
            libraryPath = requestedPath.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException("The KWebAppPaths native library could not be canonicalized.", error);
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            final MethodHandle abi = downcall(lookup, "kweb_services_abi_version",
                FunctionDescriptor.of(UINT32));
            final int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                throw new IllegalStateException("KWebAppPaths native ABI version mismatch: " + version);
            }
            final MethodHandle resolve = downcall(lookup, "kweb_app_paths_resolve",
                FunctionDescriptor.of(UINT32, POINTER, POINTER));
            final MethodHandle free = downcall(lookup, "kweb_app_paths_result_free",
                FunctionDescriptor.ofVoid(POINTER));
            final MethodHandle statusName = downcall(lookup, "kweb_services_status_name",
                FunctionDescriptor.of(POINTER, UINT32));
            return new AppPathsFfm(libraryPath, arena, resolve, free, statusName);
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("The KWebAppPaths native ABI could not be bound.", error);
        }
    }

    public NativeResult resolve(
        int kind,
        String applicationId,
        String applicationDataRoot,
        String sessionDataRoot
    ) {
        Objects.requireNonNull(applicationId, "applicationId");
        Objects.requireNonNull(applicationDataRoot, "applicationDataRoot");
        Objects.requireNonNull(sessionDataRoot, "sessionDataRoot");
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment request = callArena.allocate(REQUEST);
            final MemorySegment result = callArena.allocate(RESULT);
            request.set(UINT32, offset(REQUEST, "struct_size"), Math.toIntExact(REQUEST.byteSize()));
            request.set(UINT32, offset(REQUEST, "abi_version"), ABI_VERSION);
            request.set(UINT32, offset(REQUEST, "kind"), kind);
            request.set(UINT32, offset(REQUEST, "reserved"), 0);
            writeStringView(request, REQUEST, "application_id", encode(applicationId, callArena));
            writeStringView(request, REQUEST, "application_data_root", encode(applicationDataRoot, callArena));
            writeStringView(request, REQUEST, "session_data_root", encode(sessionDataRoot, callArena));
            final int status = (int) resolve.invokeExact(request, result);
            if (status != STATUS_OK) {
                final String nativeName;
                try {
                    nativeName = readStatusName(status);
                } catch (Throwable error) {
                    throw new NativeFailure(status, "native-status-name-failed", error);
                }
                throw new NativeFailure(status, nativeName);
            }
            try {
                validateResultHeader(result);
                final int resultKind = result.get(UINT32, offset(RESULT, "kind"));
                final String path = readStringView(result, RESULT, "path");
                final String source = readStringView(result, RESULT, "source");
                return new NativeResult(resultKind, path, source);
            } finally {
                free.invokeExact(result);
            }
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-call-failed", error);
        }
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public static long stringViewLayoutSize() {
        return STRING_VIEW.byteSize();
    }

    public static long requestLayoutSize() {
        return REQUEST.byteSize();
    }

    public static long resultLayoutSize() {
        return RESULT.byteSize();
    }

    public static long requestFieldOffset(String field) {
        return offset(REQUEST, field);
    }

    public static long resultFieldOffset(String field) {
        return offset(RESULT, field);
    }

    @Override
    public void close() {
        arena.close();
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        final MemorySegment address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Missing KWebAppPaths ABI symbol: " + symbol));
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    private static long offset(GroupLayout layout, String field) {
        return layout.byteOffset(groupElement(field));
    }

    private static void validateResultHeader(MemorySegment result) {
        final int structSize = result.get(UINT32, offset(RESULT, "struct_size"));
        final int abiVersion = result.get(UINT32, offset(RESULT, "abi_version"));
        final int reserved = result.get(UINT32, offset(RESULT, "reserved"));
        if (structSize < Math.toIntExact(RESULT.byteSize()) ||
            abiVersion != ABI_VERSION || reserved != 0) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-result-invalid");
        }
    }

    private static Encoded encode(String value, Arena arena) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_TEXT_SIZE) {
            throw new NativeFailure(STATUS_INVALID_ARGUMENT, "input-too-large");
        }
        final MemorySegment segment = arena.allocate(bytes.length, 1);
        segment.asByteBuffer().put(bytes);
        return new Encoded(segment, bytes.length);
    }

    private static void writeStringView(
        MemorySegment structure,
        GroupLayout layout,
        String field,
        Encoded value
    ) {
        final MemorySegment view = structure.asSlice(offset(layout, field), STRING_VIEW.byteSize());
        view.set(POINTER, STRING_DATA_OFFSET, value.segment());
        view.set(SIZE_T, STRING_SIZE_OFFSET, value.size());
    }

    @SuppressWarnings("restricted")
    private static String readStringView(MemorySegment structure, GroupLayout layout, String field) {
        final MemorySegment view = structure.asSlice(offset(layout, field), STRING_VIEW.byteSize());
        final MemorySegment pointer = view.get(POINTER, STRING_DATA_OFFSET);
        final long size = view.get(SIZE_T, STRING_SIZE_OFFSET);
        if (size <= 0 || size > MAXIMUM_TEXT_SIZE || pointer.address() == 0) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-result-invalid");
        }
        final byte[] bytes = pointer.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-result-invalid", error);
        }
    }

    private String readStatusName(int status) throws Throwable {
        final MemorySegment pointer = (MemorySegment) statusName.invokeExact(status);
        if (pointer == null || pointer.address() == 0) {
            throw new IllegalStateException("The native status-name symbol returned a null pointer.");
        }
        final byte[] bytes = pointer.reinterpret(128).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        if (length == 0 || length == bytes.length) {
            throw new IllegalStateException("The native status-name symbol returned an invalid string.");
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalStateException("The native status-name symbol returned invalid UTF-8.", error);
        }
    }

    public record NativeResult(int kind, String path, String source) {
    }

    public static final class NativeFailure extends RuntimeException {
        private final int status;
        private final String statusName;

        public NativeFailure(int status, String statusName) {
            this(status, statusName, null);
        }

        public NativeFailure(int status, String statusName, Throwable cause) {
            super("KWebAppPaths native operation failed with status " + statusName + " (" + status + ").", cause);
            this.status = status;
            this.statusName = statusName;
        }

        public int status() {
            return status;
        }

        public String statusName() {
            return statusName;
        }
    }

    private record Encoded(MemorySegment segment, long size) {
    }
}
