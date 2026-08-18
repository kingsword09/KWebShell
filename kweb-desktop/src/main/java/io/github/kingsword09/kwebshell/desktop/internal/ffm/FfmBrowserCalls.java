package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class FfmBrowserCalls {
    private static final ConcurrentMap<Long, FfmBrowserCallbackOwner> OWNERS =
        new ConcurrentHashMap<>();

    static long create(
        long engine,
        FfmCallbacks.BrowserEvent browserSink,
        FfmCallbacks.BridgeEvent bridgeSink,
        FfmCallbacks.Failure failureSink,
        long nativeParent,
        String profilePath,
        String initialUrl,
        int x,
        int y,
        int width,
        int height,
        String bridgeOrigin
    ) {
        if (nativeParent == 0 || (bridgeSink == null) != bridgeOrigin.isEmpty()) {
            return encodeFailure(FfmStatus.INVALID_ARGUMENT);
        }
        FfmBrowserCallbackOwner owner = null;
        try {
            FfmEngineLibrary library = library();
            owner = new FfmBrowserCallbackOwner(
                library,
                browserSink,
                bridgeSink,
                failureSink,
                FfmBrowserCalls::register
            );
            try (Arena callArena = Arena.ofConfined()) {
                MemorySegment config = callArena.allocate(FfmLayouts.BROWSER_CONFIG);
                MemorySegment output = callArena.allocate(FfmLayouts.UINT64);
                config.set(
                    FfmLayouts.UINT32,
                    offset("struct_size"),
                    Math.toIntExact(FfmLayouts.BROWSER_CONFIG.byteSize())
                );
                config.set(FfmLayouts.UINT32, offset("abi_version"), FfmAbi.VERSION);
                config.set(FfmLayouts.UINT64, offset("engine"), engine);
                config.set(FfmLayouts.SIZE_T, offset("native_parent"), nativeParent);
                config.set(FfmLayouts.INT32, offset("x"), x);
                config.set(FfmLayouts.INT32, offset("y"), y);
                config.set(FfmLayouts.INT32, offset("width"), width);
                config.set(FfmLayouts.INT32, offset("height"), height);
                write(config, "profile_path", profilePath, callArena, FfmMemory.MAXIMUM_PATH_SIZE);
                write(config, "initial_url", initialUrl, callArena, FfmMemory.MAXIMUM_TEXT_SIZE);
                config.set(FfmLayouts.POINTER, offset("callback"), owner.browserStub());
                config.set(FfmLayouts.POINTER, offset("user_data"), MemorySegment.NULL);
                write(config, "bridge_origin", bridgeOrigin, callArena, FfmMemory.MAXIMUM_TEXT_SIZE);
                config.set(FfmLayouts.POINTER, offset("bridge_callback"), owner.bridgeStub());
                config.set(FfmLayouts.POINTER, offset("bridge_user_data"), MemorySegment.NULL);
                int status = invokeCreate(library.handle("kweb_browser_create"), config, output);
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

    static int navigate(long handle, String url) {
        return invokeUtf8("kweb_browser_navigate", handle, 0, url, false);
    }

    static int resize(long handle, int width, int height) {
        try {
            return (int) library().handle("kweb_browser_resize").invokeExact(handle, width, height);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    static int close(long handle) {
        return invokeHandleStatus("kweb_browser_close", handle);
    }

    static int openDevTools(long handle) {
        return invokeHandleStatus("kweb_browser_open_devtools", handle);
    }

    static int closeDevTools(long handle) {
        return invokeHandleStatus("kweb_browser_close_devtools", handle);
    }

    static int bridgeRespond(long handle, long requestId, String response) {
        return invokeUtf8("kweb_browser_bridge_respond", handle, requestId, response, true);
    }

    static int bridgeFail(long handle, long requestId, String failure) {
        return invokeUtf8("kweb_browser_bridge_fail", handle, requestId, failure, true);
    }

    static long liveCount() {
        try {
            return (long) library().handle("kweb_live_browser_count").invokeExact();
        } catch (Throwable error) {
            return -1;
        }
    }

    static Throwable release(long handle) {
        FfmBrowserCallbackOwner owner = OWNERS.get(handle);
        if (owner == null) {
            throw new IllegalStateException("No FFM browser callback owner exists for handle " + handle + '.');
        }
        Throwable failure = owner.releaseAfterTerminal();
        if (!OWNERS.remove(handle, owner)) {
            throw new IllegalStateException("The FFM browser callback owner changed during release.");
        }
        return failure;
    }

    static int liveOwnerCount() {
        return OWNERS.size();
    }

    private static int invokeUtf8(
        String symbol,
        long handle,
        long requestId,
        String value,
        boolean bridgeCall
    ) {
        try (Arena arena = Arena.ofConfined()) {
            FfmMemory.EncodedUtf8 encoded = FfmMemory.encode(
                value,
                arena,
                FfmMemory.MAXIMUM_TEXT_SIZE
            );
            MethodHandle operation = library().handle(symbol);
            return bridgeCall
                ? (int) operation.invokeExact(handle, requestId, encoded.segment(), encoded.size())
                : (int) operation.invokeExact(handle, encoded.segment(), encoded.size());
        } catch (FfmTextException error) {
            return error.status();
        } catch (OutOfMemoryError error) {
            return FfmStatus.ALLOCATION_FAILED;
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

    private static int invokeCreate(MethodHandle handle, MemorySegment config, MemorySegment output) {
        try {
            return (int) handle.invokeExact(config, output);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    private static void write(
        MemorySegment config,
        String field,
        String value,
        Arena arena,
        long maximumSize
    ) {
        FfmMemory.writeStringView(
            config,
            FfmLayouts.BROWSER_CONFIG,
            field,
            FfmMemory.encode(value, arena, maximumSize)
        );
    }

    private static void register(long handle, FfmBrowserCallbackOwner owner) {
        if (!owner.bindHandle(handle)) {
            throw new IllegalStateException("The FFM browser owner rejected its native handle.");
        }
        FfmBrowserCallbackOwner existing = OWNERS.putIfAbsent(handle, owner);
        if (existing != null && existing != owner) {
            throw new IllegalStateException("A different FFM browser owner already uses handle " + handle + '.');
        }
    }

    private static void abort(FfmBrowserCallbackOwner owner) {
        if (owner == null || owner.isClosed()) {
            return;
        }
        long handle = owner.handle();
        if (handle > 0) {
            OWNERS.remove(handle, owner);
        }
        owner.abortBeforeOwnership();
    }

    private static long offset(String field) {
        return FfmLayouts.offset(FfmLayouts.BROWSER_CONFIG, field);
    }

    private static long encodeFailure(int status) {
        return -Integer.toUnsignedLong(status);
    }

    private static FfmEngineLibrary library() {
        return FfmEngineLibrary.requireLoaded();
    }

    private FfmBrowserCalls() {
    }
}
