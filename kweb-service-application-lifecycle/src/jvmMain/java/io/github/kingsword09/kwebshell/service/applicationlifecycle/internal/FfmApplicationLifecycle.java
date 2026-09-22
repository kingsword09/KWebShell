package io.github.kingsword09.kwebshell.service.applicationlifecycle.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Internal JDK 25 FFM binding for the versioned lifecycle C ABI. */
public final class FfmApplicationLifecycle {
    public static final int ABI_VERSION = 1;
    public static final int OK_PRIMARY = 0;
    public static final int OK_SECONDARY = 1;
    public static final int TRANSPORT_FAILED = 4;
    public static final int REGISTRATION_FAILED = 8;

    private static final FunctionDescriptor CALLBACK = FunctionDescriptor.ofVoid(
        ValueLayout.ADDRESS,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG
    );
    private static final Map<Long, CallbackOwner> OWNERS = new ConcurrentHashMap<>();
    private static final Map<Long, CallbackOwner> STATE_OWNERS = new ConcurrentHashMap<>();
    private static final MethodHandle CALLBACK_TARGET = callbackTarget();
    private static final FfmApplicationLifecycle LIBRARY = load();

    private final Arena arena;
    private final Linker linker;
    private final SymbolLookup lookup;
    private final MethodHandle abiVersion;
    private final MethodHandle providerId;
    private final MethodHandle acquire;
    private final MethodHandle release;
    private final MethodHandle register;
    private final MethodHandle liveCount;

    private FfmApplicationLifecycle(
        Arena arena,
        Linker linker,
        SymbolLookup lookup,
        MethodHandle abiVersion,
        MethodHandle providerId,
        MethodHandle acquire,
        MethodHandle release,
        MethodHandle register,
        MethodHandle liveCount
    ) {
        this.arena = arena;
        this.linker = linker;
        this.lookup = lookup;
        this.abiVersion = abiVersion;
        this.providerId = providerId;
        this.acquire = acquire;
        this.release = release;
        this.register = register;
        this.liveCount = liveCount;
    }

    public static int abiVersion() {
        try {
            return (int) LIBRARY.abiVersion.invokeExact();
        } catch (Throwable error) {
            return 0;
        }
    }

    public static String providerId() {
        try {
            MemorySegment pointer = (MemorySegment) LIBRARY.providerId.invokeExact();
            return pointer.reinterpret(4096).getString(0);
        } catch (Throwable error) {
            return "unavailable";
        }
    }

    public static Start acquire(
        String applicationId,
        String transportRoot,
        byte[] initialPayload,
        Consumer<byte[]> sink
    ) {
        CallbackOwner owner = new CallbackOwner(sink);
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment application = callArena.allocateFrom(applicationId);
            MemorySegment root = callArena.allocateFrom(transportRoot);
            MemorySegment payload = callArena.allocateFrom(ValueLayout.JAVA_BYTE, initialPayload);
            MemorySegment output = callArena.allocate(ValueLayout.JAVA_LONG);
            int status = (int) LIBRARY.acquire.invokeExact(
                application, (long) applicationId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                root, (long) transportRoot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                payload, (long) initialPayload.length,
                owner.stub,
                owner.state,
                output
            );
            if (status == OK_PRIMARY) {
                long handle = output.get(ValueLayout.JAVA_LONG, 0);
                if (handle == 0) throw new IllegalStateException("The lifecycle provider returned an empty primary handle.");
                owner.bind(handle);
                OWNERS.put(handle, owner);
                return new Start(status, handle);
            }
            owner.close();
            return new Start(status, 0L);
        } catch (Throwable error) {
            owner.close();
            throw new IllegalStateException("The lifecycle native provider could not acquire its lease.", error);
        }
    }

    public static void release(long handle) {
        CallbackOwner owner = OWNERS.remove(handle);
        try {
            int status = (int) LIBRARY.release.invokeExact(handle);
            if (status != 0) throw new IllegalStateException("The lifecycle native provider returned status " + status + '.');
        } catch (Throwable error) {
            throw new IllegalStateException("The lifecycle native provider could not release its lease.", error);
        } finally {
            if (owner != null) owner.close();
        }
    }

    public static String registerAssociations(
        String applicationId,
        String packageRoot,
        String executable,
        String schemes,
        String extensions,
        boolean remove
    ) {
        try (Arena callArena = Arena.ofConfined()) {
            MemorySegment application = callArena.allocateFrom(applicationId);
            MemorySegment root = callArena.allocateFrom(packageRoot);
            MemorySegment executablePath = callArena.allocateFrom(executable);
            MemorySegment schemeBytes = callArena.allocateFrom(schemes);
            MemorySegment extensionBytes = callArena.allocateFrom(extensions);
            MemorySegment digest = callArena.allocate(128);
            int status = (int) LIBRARY.register.invokeExact(
                application, (long) applicationId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                root, (long) packageRoot.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                executablePath, (long) executable.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                schemeBytes, (long) schemes.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                extensionBytes, (long) extensions.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                (byte) (remove ? 1 : 0), digest, 128L
            );
            if (status != 0) {
                throw new IllegalStateException("The native application registration provider returned status " + status + '.');
            }
            return digest.getString(0);
        } catch (Throwable error) {
            throw new IllegalStateException("The native application registration provider failed.", error);
        }
    }

    public static long liveCount() {
        try {
            return (long) LIBRARY.liveCount.invokeExact();
        } catch (Throwable error) {
            return -1L;
        }
    }

    public record Start(int status, long handle) {}

    private static void onCallback(MemorySegment userData, MemorySegment payload, long size) {
        CallbackOwner owner = STATE_OWNERS.get(userData.address());
        if (owner == null || size <= 0 || size > 256L * 1024L) return;
        byte[] bytes = payload.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        owner.sink.accept(bytes);
    }

    private static MethodHandle callbackTarget() {
        try {
            return MethodHandles.lookup().findStatic(
                FfmApplicationLifecycle.class,
                "onCallback",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, long.class)
            );
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    private static FfmApplicationLifecycle load() {
        String configured = System.getProperty("kweb.application.lifecycle.native.library.path");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("The application lifecycle native library path is required.");
        }
        Path path = Path.of(configured).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalStateException("The application lifecycle native library is missing: " + path);
        Arena arena = Arena.ofShared();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.libraryLookup(path, arena);
        MethodHandle abi = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_abi_version"), FunctionDescriptor.of(ValueLayout.JAVA_INT));
        MethodHandle provider = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_provider_id"), FunctionDescriptor.of(ValueLayout.ADDRESS));
        MethodHandle acquire = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_acquire"), FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS
        ));
        MethodHandle release = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_release"), FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        MethodHandle register = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_register_associations"), FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG
        ));
        MethodHandle live = linker.downcallHandle(symbol(lookup, "kweb_application_lifecycle_live_count"), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        FfmApplicationLifecycle result = new FfmApplicationLifecycle(arena, linker, lookup, abi, provider, acquire, release, register, live);
        final int version;
        try {
            version = (int) abi.invokeExact();
        } catch (Throwable error) {
            arena.close();
            throw new IllegalStateException("The application lifecycle native ABI could not be queried.", error);
        }
        if (version != ABI_VERSION) {
            arena.close();
            throw new IllegalStateException("The application lifecycle native ABI version is unsupported.");
        }
        return result;
    }

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException("The lifecycle native symbol is missing: " + name));
    }

    private static final class CallbackOwner implements AutoCloseable {
        private final Arena arena = Arena.ofShared();
        private final Consumer<byte[]> sink;
        private final MemorySegment state = arena.allocate(ValueLayout.JAVA_LONG);
        private final MemorySegment stub;
        private volatile long handle;

        private CallbackOwner(Consumer<byte[]> sink) {
            this.sink = sink;
            state.set(ValueLayout.JAVA_LONG, 0, 0L);
            this.stub = Linker.nativeLinker().upcallStub(CALLBACK_TARGET, CALLBACK, arena);
            STATE_OWNERS.put(state.address(), this);
        }

        private void bind(long handle) {
            this.handle = handle;
            state.set(ValueLayout.JAVA_LONG, 0, handle);
        }

        @Override
        public void close() {
            STATE_OWNERS.remove(state.address(), this);
            arena.close();
        }
    }

    private FfmApplicationLifecycle() {
        this(null, null, null, null, null, null, null, null, null);
    }
}
