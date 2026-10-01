package io.github.kingsword09.kwebshell.service.shell.internal;

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

public final class ShellFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_NATIVE_UNAVAILABLE = 3;
    public static final int STATUS_HANDLER_REJECTED = 4;
    public static final int STATUS_REVEAL_UNAVAILABLE = 5;
    public static final int STATUS_TRASH_FAILED = 6;
    public static final int STATUS_TRASH_VERIFICATION_FAILED = 7;
    public static final int STATUS_NATIVE_FAILED = 8;
    public static final int OUTCOME_HANDLER_ACCEPTED = 1;
    public static final int OUTCOME_MOVED_TO_TRASH = 2;
    public static final int MAXIMUM_TEXT_SIZE = 8192;

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong SIZE_T = ValueLayout.JAVA_LONG;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final GroupLayout STRING = MemoryLayout.structLayout(
        PTR.withName("data"), SIZE_T.withName("size")
    );
    private static final GroupLayout REQUEST = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("action"),
        I32.withName("resource_kind"), I32.withName("reserved"),
        MemoryLayout.paddingLayout(4), STRING.withName("value")
    );
    private static final GroupLayout RESULT = MemoryLayout.structLayout(
        I32.withName("struct_size"), I32.withName("abi_version"), I32.withName("outcome"),
        I32.withName("resource_kind"), I32.withName("reserved")
    );

    private final Path libraryPath;
    private final Arena arena;
    private final MethodHandle execute;
    private final MethodHandle statusName;
    private final MethodHandle providerId;

    private ShellFfm(Path libraryPath, Arena arena, MethodHandle execute,
                     MethodHandle statusName, MethodHandle providerId) {
        this.libraryPath = libraryPath;
        this.arena = arena;
        this.execute = execute;
        this.statusName = statusName;
        this.providerId = providerId;
    }

    public static ShellFfm open(Path requestedPath) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The KWebShell native library must be an absolute regular file.");
        }
        final Path libraryPath;
        try {
            libraryPath = requestedPath.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException("The KWebShell native library could not be canonicalized.", error);
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            final int version = (int) bind(lookup, "kweb_shell_abi_version",
                FunctionDescriptor.of(I32)).invokeExact();
            if (version != ABI_VERSION) throw new IllegalStateException("KWebShell ABI version mismatch: " + version);
            return new ShellFfm(
                libraryPath,
                arena,
                bind(lookup, "kweb_shell_execute", FunctionDescriptor.of(I32, PTR, PTR)),
                bind(lookup, "kweb_shell_status_name", FunctionDescriptor.of(PTR, I32)),
                bind(lookup, "kweb_shell_provider_id", FunctionDescriptor.of(PTR))
            );
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("The KWebShell native ABI could not be bound.", error);
        }
    }

    public NativeResult execute(int action, int resourceKind, String value) {
        Objects.requireNonNull(value, "value");
        try (Arena call = Arena.ofConfined()) {
            final MemorySegment request = call.allocate(REQUEST);
            final MemorySegment result = call.allocate(RESULT);
            request.set(I32, REQUEST.byteOffset(groupElement("struct_size")), (int) REQUEST.byteSize());
            request.set(I32, REQUEST.byteOffset(groupElement("abi_version")), ABI_VERSION);
            request.set(I32, REQUEST.byteOffset(groupElement("action")), action);
            request.set(I32, REQUEST.byteOffset(groupElement("resource_kind")), resourceKind);
            request.set(I32, REQUEST.byteOffset(groupElement("reserved")), 0);
            final byte[] bytes = encode(value);
            final MemorySegment data = call.allocate(bytes.length, 1);
            data.asByteBuffer().put(bytes);
            final MemorySegment view = request.asSlice(REQUEST.byteOffset(groupElement("value")), STRING.byteSize());
            view.set(PTR, STRING.byteOffset(groupElement("data")), data);
            view.set(SIZE_T, STRING.byteOffset(groupElement("size")), bytes.length);
            final int status = (int) execute.invokeExact(request, result);
            if (status != STATUS_OK) throw new NativeFailure(status, readStatusName(status));
            validateResult(result);
            return new NativeResult(
                result.get(I32, RESULT.byteOffset(groupElement("outcome"))),
                result.get(I32, RESULT.byteOffset(groupElement("resource_kind")))
            );
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-call-failed", error);
        }
    }

    public String providerId() {
        try {
            return readCString((MemorySegment) providerId.invokeExact(), 256);
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "provider-id-failed", error);
        }
    }

    public Path libraryPath() { return libraryPath; }

    @Override public void close() { arena.close(); }

    private static MethodHandle bind(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(
            lookup.find(name).orElseThrow(() -> new IllegalStateException("Missing KWebShell ABI symbol: " + name)),
            descriptor
        );
    }

    private String readStatusName(int status) throws Throwable {
        return readCString((MemorySegment) statusName.invokeExact(status), 128);
    }

    private static byte[] encode(String value) {
        if (value.indexOf('\0') >= 0) throw new NativeFailure(STATUS_INVALID_ARGUMENT, "input-nul");
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > MAXIMUM_TEXT_SIZE) {
            throw new NativeFailure(STATUS_INVALID_ARGUMENT, "input-too-large");
        }
        return bytes;
    }

    private static void validateResult(MemorySegment result) {
        final int structSize = result.get(I32, RESULT.byteOffset(groupElement("struct_size")));
        final int abi = result.get(I32, RESULT.byteOffset(groupElement("abi_version")));
        final int reserved = result.get(I32, RESULT.byteOffset(groupElement("reserved")));
        if (structSize < RESULT.byteSize() || abi != ABI_VERSION || reserved != 0) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-result-invalid");
        }
    }

    private static String readCString(MemorySegment pointer, long maximum) {
        if (pointer == null || pointer.address() == 0) throw new NativeFailure(STATUS_NATIVE_FAILED, "null-string");
        final byte[] bytes = pointer.reinterpret(maximum).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) length++;
        if (length == 0 || length == bytes.length) throw new NativeFailure(STATUS_NATIVE_FAILED, "invalid-string");
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length)).toString();
        } catch (CharacterCodingException error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "invalid-string", error);
        }
    }

    public record NativeResult(int outcome, int resourceKind) {}

    public static final class NativeFailure extends RuntimeException {
        private final int status;
        private final String statusName;
        public NativeFailure(int status, String statusName) { this(status, statusName, null); }
        public NativeFailure(int status, String statusName, Throwable cause) {
            super("KWebShell native operation failed with status " + statusName + " (" + status + ").", cause);
            this.status = status;
            this.statusName = statusName;
        }
        public int status() { return status; }
        public String statusName() { return statusName; }
    }
}
