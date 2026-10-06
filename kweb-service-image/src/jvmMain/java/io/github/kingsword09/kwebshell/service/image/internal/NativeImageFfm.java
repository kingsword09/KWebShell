package io.github.kingsword09.kwebshell.service.image.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

public final class NativeImageFfm implements AutoCloseable {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_NATIVE_UNAVAILABLE = 3;
    public static final int STATUS_NATIVE_FAILED = 4;
    private static final GroupLayout RGBA = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height"),
        ADDRESS.withName("bytes"),
        JAVA_LONG.withName("size")
    );

    private final Arena arena;
    private final MethodHandle create;
    private final MethodHandle release;
    private final MethodHandle liveCount;
    private final MethodHandle statusName;
    private final String provider;

    private NativeImageFfm(
        Arena arena,
        MethodHandle create,
        MethodHandle release,
        MethodHandle liveCount,
        MethodHandle statusName,
        String provider
    ) {
        this.arena = arena;
        this.create = create;
        this.release = release;
        this.liveCount = liveCount;
        this.statusName = statusName;
        this.provider = provider;
    }

    public static NativeImageFfm open(Path requestedPath) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException("The image native library must be an absolute regular file.");
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(requestedPath.toRealPath(), arena);
            final MethodHandle abi = downcall(lookup, "kweb_image_abi_version", FunctionDescriptor.of(JAVA_INT));
            if ((int) abi.invokeExact() != ABI_VERSION) {
                throw new IllegalStateException("KWebNativeImage ABI version mismatch.");
            }
            final MethodHandle statusName = downcall(
                lookup, "kweb_image_status_name", FunctionDescriptor.of(ADDRESS, JAVA_INT)
            );
            final MethodHandle providerId = downcall(
                lookup, "kweb_image_provider_id", FunctionDescriptor.of(ADDRESS)
            );
            return new NativeImageFfm(
                arena,
                downcall(lookup, "kweb_image_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                downcall(lookup, "kweb_image_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                downcall(lookup, "kweb_image_live_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                statusName,
                readCString((MemorySegment) providerId.invokeExact())
            );
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("KWebNativeImage native ABI could not be bound.", error);
        }
    }

    public String providerId() {
        return provider;
    }

    public NativeHandle create(byte[] rgba, int width, int height) {
        Objects.requireNonNull(rgba, "rgba");
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment bytes = callArena.allocate(Math.max(1, rgba.length), 1);
            if (rgba.length != 0) bytes.copyFrom(MemorySegment.ofArray(rgba));
            final MemorySegment input = callArena.allocate(RGBA);
            input.set(JAVA_INT, offset("struct_size"), (int) RGBA.byteSize());
            input.set(JAVA_INT, offset("abi_version"), ABI_VERSION);
            input.set(JAVA_INT, offset("width"), width);
            input.set(JAVA_INT, offset("height"), height);
            input.set(ADDRESS, offset("bytes"), bytes);
            input.set(JAVA_LONG, offset("size"), rgba.length);
            final MemorySegment output = callArena.allocate(JAVA_LONG);
            final int status = (int) create.invokeExact(input, output);
            if (status != STATUS_OK) throw failure(status);
            return new NativeHandle(output.get(JAVA_LONG, 0));
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-create-failed", error);
        }
    }

    public int liveCount() {
        try (Arena callArena = Arena.ofConfined()) {
            final MemorySegment output = callArena.allocate(JAVA_INT);
            final int status = (int) liveCount.invokeExact(output);
            if (status != STATUS_OK) throw failure(status);
            return output.get(JAVA_INT, 0);
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-live-count-failed", error);
        }
    }

    @Override
    public void close() {
        arena.close();
    }

    public final class NativeHandle implements AutoCloseable {
        private final long value;
        private boolean closed;

        private NativeHandle(long value) {
            this.value = value;
        }

        public long value() {
            return value;
        }

        public synchronized boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            try {
                final int status = (int) release.invokeExact(value);
                if (status != STATUS_OK) throw failure(status);
                closed = true;
            } catch (NativeFailure error) {
                throw error;
            } catch (Throwable error) {
                throw new NativeFailure(STATUS_NATIVE_FAILED, "native-release-failed", error);
            }
        }
    }

    public static final class NativeFailure extends RuntimeException {
        private final int status;

        public NativeFailure(int status, String message) {
            super(message);
            this.status = status;
        }

        public NativeFailure(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private NativeFailure failure(int status) {
        try {
            final String name = readCString((MemorySegment) statusName.invokeExact(status));
            return new NativeFailure(status, name);
        } catch (Throwable error) {
            return new NativeFailure(status, "native-status-" + status, error);
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(
            lookup.find(symbol).orElseThrow(() -> new IllegalStateException("Missing native symbol " + symbol)),
            descriptor
        );
    }

    private static long offset(String name) {
        return RGBA.byteOffset(groupElement(name));
    }

    private static String readCString(MemorySegment value) {
        return value.reinterpret(Long.MAX_VALUE).getString(0);
    }
}
