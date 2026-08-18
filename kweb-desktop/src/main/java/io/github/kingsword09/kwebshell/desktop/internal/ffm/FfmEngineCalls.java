package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class FfmEngineCalls {
    private static final ConcurrentMap<Long, FfmEngineCallbackOwner> OWNERS =
        new ConcurrentHashMap<>();

    static int abiVersion() {
        try {
            return (int) library().handle("kweb_engine_abi_version").invokeExact();
        } catch (Throwable error) {
            return 0;
        }
    }

    static long create(
        FfmCallbacks.EngineEvent sink,
        FfmCallbacks.Failure failureSink,
        String cefRuntimePath,
        String browserSubprocessPath,
        String resourcesPath,
        String localesPath,
        String rootCachePath,
        String logPath,
        int remoteDebuggingPort
    ) {
        FfmEngineCallbackOwner owner = null;
        try {
            FfmEngineLibrary library = library();
            owner = new FfmEngineCallbackOwner(library, sink, failureSink, FfmEngineCalls::register);
            try (Arena callArena = Arena.ofConfined()) {
                MemorySegment config = callArena.allocate(FfmLayouts.ENGINE_CONFIG);
                MemorySegment output = callArena.allocate(FfmLayouts.UINT64);
                config.set(
                    FfmLayouts.UINT32,
                    offset("struct_size"),
                    Math.toIntExact(FfmLayouts.ENGINE_CONFIG.byteSize())
                );
                config.set(FfmLayouts.UINT32, offset("abi_version"), FfmAbi.VERSION);
                config.set(FfmLayouts.POINTER, offset("callback"), owner.stub());
                config.set(FfmLayouts.POINTER, offset("user_data"), MemorySegment.NULL);
                writePath(config, "cef_runtime_path", cefRuntimePath, callArena);
                writePath(config, "browser_subprocess_path", browserSubprocessPath, callArena);
                writePath(config, "resources_path", resourcesPath, callArena);
                writePath(config, "locales_path", localesPath, callArena);
                writePath(config, "root_cache_path", rootCachePath, callArena);
                writePath(config, "log_path", logPath, callArena);
                config.set(
                    FfmLayouts.INT32,
                    offset("remote_debugging_port"),
                    remoteDebuggingPort
                );
                int status = invokeCreate(library.handle("kweb_engine_create"), config, output);
                if (status != FfmStatus.OK) {
                    abort(owner);
                    return encodeFailure(status);
                }
                long handle = output.get(FfmLayouts.UINT64, 0);
                if (!owner.bindHandle(handle)) {
                    abort(owner);
                    return encodeFailure(FfmStatus.INTERNAL_ERROR);
                }
                register(handle, owner);
                return handle;
            }
        } catch (FfmTextException error) {
            abort(owner);
            return encodeFailure(error.status());
        } catch (OutOfMemoryError error) {
            abort(owner);
            return encodeFailure(FfmStatus.ALLOCATION_FAILED);
        } catch (Throwable error) {
            abort(owner);
            return encodeFailure(FfmStatus.INTERNAL_ERROR);
        }
    }

    static int close(long handle) {
        return invokeHandleStatus("kweb_engine_close", handle);
    }

    static long liveCount() {
        try {
            return (long) library().handle("kweb_live_engine_count").invokeExact();
        } catch (Throwable error) {
            return -1;
        }
    }

    static Throwable release(long handle) {
        FfmEngineCallbackOwner owner = OWNERS.get(handle);
        if (owner == null) {
            throw new IllegalStateException("No FFM engine callback owner exists for handle " + handle + '.');
        }
        Throwable failure = owner.releaseAfterTerminal();
        if (!OWNERS.remove(handle, owner)) {
            throw new IllegalStateException("The FFM engine callback owner changed during release.");
        }
        return failure;
    }

    static int liveOwnerCount() {
        return OWNERS.size();
    }

    private static void register(long handle, FfmEngineCallbackOwner owner) {
        if (!owner.bindHandle(handle)) {
            throw new IllegalStateException("The FFM engine owner rejected its native handle.");
        }
        FfmEngineCallbackOwner existing = OWNERS.putIfAbsent(handle, owner);
        if (existing != null && existing != owner) {
            throw new IllegalStateException("A different FFM engine owner already uses handle " + handle + '.');
        }
    }

    private static void abort(FfmEngineCallbackOwner owner) {
        if (owner == null || owner.isClosed()) {
            return;
        }
        long handle = owner.handle();
        if (handle > 0) {
            OWNERS.remove(handle, owner);
        }
        owner.abortBeforeOwnership();
    }

    private static void writePath(
        MemorySegment config,
        String field,
        String value,
        Arena arena
    ) {
        FfmMemory.writeStringView(
            config,
            FfmLayouts.ENGINE_CONFIG,
            field,
            FfmMemory.encode(value, arena, FfmMemory.MAXIMUM_PATH_SIZE)
        );
    }

    private static int invokeCreate(MethodHandle handle, MemorySegment config, MemorySegment output) {
        try {
            return (int) handle.invokeExact(config, output);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    private static int invokeHandleStatus(String symbol, long handle) {
        try {
            return (int) library().handle(symbol).invokeExact(handle);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    private static long offset(String field) {
        return FfmLayouts.offset(FfmLayouts.ENGINE_CONFIG, field);
    }

    private static long encodeFailure(int status) {
        return -Integer.toUnsignedLong(status);
    }

    private static FfmEngineLibrary library() {
        return FfmEngineLibrary.requireLoaded();
    }

    private FfmEngineCalls() {
    }
}
