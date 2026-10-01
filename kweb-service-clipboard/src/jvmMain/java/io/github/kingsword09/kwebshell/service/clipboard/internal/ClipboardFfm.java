package io.github.kingsword09.kwebshell.service.clipboard.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public final class ClipboardFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_NATIVE_UNAVAILABLE = 3;
    public static final int STATUS_READ_UNAVAILABLE = 4;
    public static final int STATUS_WRITE_UNAVAILABLE = 5;
    public static final int STATUS_WRITE_OUTCOME_UNKNOWN = 6;
    public static final int STATUS_FORMAT_UNSUPPORTED = 7;
    public static final int STATUS_BUFFER_SMALL = 8;
    public static final int STATUS_NATIVE_FAILED = 9;

    private static final ValueLayout.OfInt UINT32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong UINT64 = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfLong SIZE_T = ValueLayout.JAVA_LONG;
    private static final AddressLayout POINTER = ValueLayout.ADDRESS;
    private static final GroupLayout ITEM = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("format"),
        UINT32.withName("reserved"),
        POINTER.withName("data"),
        SIZE_T.withName("size")
    );
    private static final GroupLayout SNAPSHOT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT64.withName("sequence"),
        UINT32.withName("format_mask"),
        UINT32.withName("ownership"),
        UINT32.withName("reserved"),
        MemoryLayout.paddingLayout(4)
    );

    private final Arena arena;
    private final MethodHandle open;
    private final MethodHandle snapshot;
    private final MethodHandle read;
    private final MethodHandle write;
    private final MethodHandle clear;
    private final MethodHandle close;
    private final MethodHandle statusName;
    private final MethodHandle providerId;
    private final long handle;
    private final String provider;

    private ClipboardFfm(
        Arena arena,
        MethodHandle open,
        MethodHandle snapshot,
        MethodHandle read,
        MethodHandle write,
        MethodHandle clear,
        MethodHandle close,
        MethodHandle statusName,
        MethodHandle providerId,
        long handle,
        String provider
    ) {
        this.arena = arena;
        this.open = open;
        this.snapshot = snapshot;
        this.read = read;
        this.write = write;
        this.clear = clear;
        this.close = close;
        this.statusName = statusName;
        this.providerId = providerId;
        this.handle = handle;
        this.provider = provider;
    }

    public static ClipboardFfm open(Path requestedPath) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The clipboard native library must be an absolute regular file.");
        }
        final Path libraryPath;
        try {
            libraryPath = requestedPath.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException("The clipboard native library could not be canonicalized.", error);
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            final MethodHandle abi = downcall(lookup, "kweb_clipboard_abi_version",
                FunctionDescriptor.of(UINT32));
            if ((int) abi.invokeExact() != ABI_VERSION) {
                throw new IllegalStateException("KWebClipboard native ABI version mismatch.");
            }
            final MethodHandle open = downcall(lookup, "kweb_clipboard_open",
                FunctionDescriptor.of(UINT32, POINTER));
            final MethodHandle statusName = downcall(lookup, "kweb_clipboard_status_name",
                FunctionDescriptor.of(POINTER, UINT32));
            try (Arena callArena = Arena.ofConfined()) {
                final MemorySegment pointer = callArena.allocate(UINT64);
                final int status = (int) open.invokeExact(pointer);
                if (status != STATUS_OK) {
                    throw new NativeFailure(
                        status,
                        readCString((MemorySegment) statusName.invokeExact(status))
                    );
                }
                final long handle = pointer.get(UINT64, 0);
                final MethodHandle providerId = downcall(lookup, "kweb_clipboard_provider_id",
                    FunctionDescriptor.of(POINTER));
                return new ClipboardFfm(
                    arena,
                    open,
                    downcall(lookup, "kweb_clipboard_snapshot",
                        FunctionDescriptor.of(UINT32, UINT64, POINTER)),
                    downcall(lookup, "kweb_clipboard_read",
                        FunctionDescriptor.of(UINT32, UINT64, UINT32, POINTER, SIZE_T, POINTER)),
                    downcall(lookup, "kweb_clipboard_write",
                        FunctionDescriptor.of(UINT32, UINT64, POINTER, SIZE_T)),
                    downcall(lookup, "kweb_clipboard_clear",
                        FunctionDescriptor.of(UINT32, UINT64)),
                    downcall(lookup, "kweb_clipboard_close",
                        FunctionDescriptor.of(UINT32, UINT64)),
                    statusName,
                    providerId,
                    handle,
                    readCString((MemorySegment) providerId.invokeExact())
                );
            }
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("The KWebClipboard native ABI could not be bound.", error);
        }
    }

    public String providerId() {
        return provider;
    }

    public NativeSnapshot snapshot() {
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment result = callArena.allocate(SNAPSHOT);
            result.set(UINT32, offset(SNAPSHOT, "struct_size"), Math.toIntExact(SNAPSHOT.byteSize()));
            result.set(UINT32, offset(SNAPSHOT, "abi_version"), ABI_VERSION);
            final int status = (int) snapshot.invokeExact(handle, result);
            if (status != STATUS_OK) throw failure(status);
            validateSnapshot(result);
            return new NativeSnapshot(
                result.get(UINT64, offset(SNAPSHOT, "sequence")),
                result.get(UINT32, offset(SNAPSHOT, "format_mask")),
                result.get(UINT32, offset(SNAPSHOT, "ownership"))
            );
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-snapshot-failed", error);
        }
    }

    public byte[] read(int format) {
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment sizePointer = callArena.allocate(SIZE_T);
            int status = (int) read.invokeExact(handle, format, MemorySegment.NULL, 0L, sizePointer);
            if (status != STATUS_BUFFER_SMALL && status != STATUS_OK) throw failure(status);
            final long size = sizePointer.get(SIZE_T, 0);
            if (size > 4L * 1024L * 1024L) {
                throw new NativeFailure(STATUS_READ_UNAVAILABLE, "native-payload-too-large");
            }
            final MemorySegment bytes = callArena.allocate(Math.max(1L, size), 1);
            status = (int) read.invokeExact(handle, format, bytes, size, sizePointer);
            if (status != STATUS_OK) throw failure(status);
            return size == 0 ? new byte[0] : bytes.asSlice(0, size).toArray(ValueLayout.JAVA_BYTE);
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-read-failed", error);
        }
    }

    public void write(NativeItem[] items) {
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment array = callArena.allocate(ITEM.byteSize() * items.length, ITEM.byteAlignment());
            for (int index = 0; index < items.length; ++index) {
                final NativeItem item = items[index];
                final byte[] bytes = item.bytes();
                final MemorySegment data = callArena.allocate(Math.max(1, bytes.length), 1);
                if (bytes.length != 0) data.asByteBuffer().put(bytes);
                final MemorySegment element = array.asSlice(index * ITEM.byteSize(), ITEM.byteSize());
                element.set(UINT32, offset(ITEM, "struct_size"), Math.toIntExact(ITEM.byteSize()));
                element.set(UINT32, offset(ITEM, "abi_version"), ABI_VERSION);
                element.set(UINT32, offset(ITEM, "format"), item.format());
                element.set(UINT32, offset(ITEM, "reserved"), 0);
                element.set(POINTER, offset(ITEM, "data"), data);
                element.set(SIZE_T, offset(ITEM, "size"), bytes.length);
            }
            final int status = (int) write.invokeExact(handle, array, (long) items.length);
            if (status != STATUS_OK) throw failure(status);
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-write-failed", error);
        }
    }

    public void clear() {
        try {
            final int status = (int) clear.invokeExact(handle);
            if (status != STATUS_OK) throw failure(status);
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-clear-failed", error);
        }
    }

    @Override
    public void close() {
        try {
            final int status = (int) close.invokeExact(handle);
            if (status != STATUS_OK) throw failure(status);
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-close-failed", error);
        } finally {
            arena.close();
        }
    }

    private NativeFailure failure(int status) throws Throwable {
        return new NativeFailure(status, readCString((MemorySegment) statusName.invokeExact(status)));
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        final MemorySegment address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Missing KWebClipboard ABI symbol: " + symbol));
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    private static long offset(GroupLayout layout, String field) {
        return layout.byteOffset(groupElement(field));
    }

    private static void validateSnapshot(MemorySegment result) {
        final int size = result.get(UINT32, offset(SNAPSHOT, "struct_size"));
        final int version = result.get(UINT32, offset(SNAPSHOT, "abi_version"));
        if (size < SNAPSHOT.byteSize() || version != ABI_VERSION) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-snapshot-invalid");
        }
    }

    @SuppressWarnings("restricted")
    private static String readCString(MemorySegment pointer) {
        if (pointer == null || pointer.address() == 0) {
            throw new IllegalStateException("The native ABI returned a null string.");
        }
        final byte[] bytes = pointer.reinterpret(256).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) ++length;
        if (length == bytes.length) throw new IllegalStateException("The native ABI returned an unterminated string.");
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public record NativeItem(int format, byte[] bytes) {
        public NativeItem {
            bytes = bytes.clone();
        }
    }

    public record NativeSnapshot(long sequence, int formatMask, int ownership) {
    }

    public static final class NativeFailure extends RuntimeException {
        private final int status;
        private final String statusName;

        public NativeFailure(int status, String statusName) {
            super("KWebClipboard native operation failed with status " + statusName + " (" + status + ").");
            this.status = status;
            this.statusName = statusName;
        }

        public NativeFailure(int status, String statusName, Throwable cause) {
            super("KWebClipboard native operation failed with status " + statusName + " (" + status + ").", cause);
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
}
