package io.github.kingsword09.kwebshell.service.image.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    public static final int STATUS_HANDLE_LIMIT = 5;
    public static final int STATUS_OUTCOME_UNKNOWN = 6;
    public static final int MAX_NATIVE_HANDLES = 256;
    private static final long MAX_PIXELS = 6_000_000L;
    private static final GroupLayout RGBA = MemoryLayout.structLayout(
        JAVA_INT.withName("struct_size"),
        JAVA_INT.withName("abi_version"),
        JAVA_INT.withName("width"),
        JAVA_INT.withName("height"),
        ADDRESS.withName("bytes"),
        JAVA_LONG.withName("size")
    );

    // GdkPixbuf registers process-global GTypes whose callbacks require its DSO
    // to remain loaded. Owners release images, never the provider library.
    private static final ConcurrentHashMap<Path, SymbolLookup> LIBRARIES = new ConcurrentHashMap<>();
    private final MethodHandle create;
    private final MethodHandle release;
    private final MethodHandle liveCount;
    private final MethodHandle statusName;
    private final String provider;
    // All provider calls and handle state transitions use this object's monitor.
    // NativeHandle never takes its own monitor, which avoids owner/handle lock inversion.
    private final Set<NativeHandle> handles = new HashSet<>();
    private NativeFailure closeFailure;
    private boolean closing;
    private boolean closed;

    private NativeImageFfm(
        MethodHandle create,
        MethodHandle release,
        MethodHandle liveCount,
        MethodHandle statusName,
        String provider
    ) {
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
        try {
            final SymbolLookup lookup = LIBRARIES.computeIfAbsent(
                requestedPath.toRealPath(), path -> SymbolLookup.libraryLookup(path, Arena.global())
            );
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
                downcall(lookup, "kweb_image_create", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                downcall(lookup, "kweb_image_release", FunctionDescriptor.of(JAVA_INT, JAVA_LONG)),
                downcall(lookup, "kweb_image_live_count", FunctionDescriptor.of(JAVA_INT, ADDRESS)),
                statusName,
                readCString((MemorySegment) providerId.invokeExact())
            );
        } catch (Throwable error) {
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("KWebNativeImage native ABI could not be bound.", error);
        }
    }

    public String providerId() {
        return provider;
    }

    public synchronized NativeHandle create(byte[] rgba, int width, int height) {
        requireOpen();
        Objects.requireNonNull(rgba, "rgba");
        if (width <= 0 || height <= 0 || width > 16_384 || height > 16_384 ||
            (long) width * height > MAX_PIXELS || (long) width * height * 4 != rgba.length) {
            throw new NativeFailure(STATUS_INVALID_ARGUMENT, "invalid-argument");
        }
        if (handles.size() >= MAX_NATIVE_HANDLES) {
            throw new NativeFailure(STATUS_HANDLE_LIMIT, "handle-limit");
        }
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
            final long value = output.get(JAVA_LONG, 0);
            if (value == 0) throw new NativeFailure(STATUS_NATIVE_FAILED, "native-create-returned-zero-handle");
            try {
                final NativeHandle handle = new NativeHandle(value);
                handles.add(handle);
                return handle;
            } catch (Throwable registrationError) {
                final int cleanup = (int) release.invokeExact(value);
                if (cleanup != STATUS_OK) {
                    throw new NativeFailure(STATUS_OUTCOME_UNKNOWN, "native-registration-cleanup-failed", registrationError);
                }
                throw registrationError;
            }
        } catch (NativeFailure error) {
            throw error;
        } catch (Throwable error) {
            throw new NativeFailure(STATUS_NATIVE_FAILED, "native-create-failed", error);
        }
    }

    public synchronized int liveCount() {
        requireOpen();
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
    public synchronized void close() {
        if (closed) return;
        if (closeFailure != null) throw closeFailure;
        closing = true;
        NativeFailure failure = null;
        for (NativeHandle handle : Set.copyOf(handles)) {
            try {
                release(handle);
            } catch (NativeFailure error) {
                if (failure == null) failure = error;
            }
        }
        if (failure != null) {
            closeFailure = failure;
            throw failure;
        }
        closed = true;
    }

    public final class NativeHandle implements AutoCloseable {
        private final long value;
        private boolean closed;
        private NativeFailure releaseFailure;

        private NativeHandle(long value) {
            this.value = value;
        }

        public long value() {
            synchronized (NativeImageFfm.this) {
                return value;
            }
        }

        public boolean isClosed() {
            synchronized (NativeImageFfm.this) {
                return closed;
            }
        }

        @Override
        public void close() {
            NativeImageFfm.this.release(this);
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

    private void requireOpen() {
        if (closing || closed) throw new IllegalStateException("KWebNativeImage native provider is closed.");
    }

    private synchronized void release(NativeHandle handle) {
        if (handle.closed) return;
        if (handle.releaseFailure != null) throw handle.releaseFailure;
        if (closed) throw new IllegalStateException("KWebNativeImage native provider is closed.");
        try {
            final int status = (int) release.invokeExact(handle.value);
            if (status != STATUS_OK) throw failure(status);
            handle.closed = true;
            handles.remove(handle);
        } catch (NativeFailure error) {
            handle.releaseFailure = error;
            throw error;
        } catch (Throwable error) {
            handle.releaseFailure = new NativeFailure(STATUS_OUTCOME_UNKNOWN, "native-release-failed", error);
            throw handle.releaseFailure;
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
