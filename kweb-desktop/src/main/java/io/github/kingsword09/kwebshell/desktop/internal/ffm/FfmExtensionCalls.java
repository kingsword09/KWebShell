package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class FfmExtensionCalls {
    private static final ConcurrentMap<Long, FfmExtensionCallbackOwner> OWNERS =
        new ConcurrentHashMap<>();

    static long start(
        long browser,
        FfmCallbacks.ExtensionResult sink,
        FfmCallbacks.Failure failureSink,
        int operation,
        String extensionId,
        String expectedVersion,
        String extensionPath
    ) {
        FfmExtensionCallbackOwner owner = null;
        try {
            FfmEngineLibrary library = library();
            owner = new FfmExtensionCallbackOwner(
                library,
                sink,
                failureSink,
                FfmExtensionCalls::register
            );
            try (Arena callArena = Arena.ofConfined()) {
                MemorySegment config = callArena.allocate(FfmLayouts.EXTENSION_CONFIG);
                MemorySegment output = callArena.allocate(FfmLayouts.UINT64);
                config.set(
                    FfmLayouts.UINT32,
                    offset("struct_size"),
                    Math.toIntExact(FfmLayouts.EXTENSION_CONFIG.byteSize())
                );
                config.set(FfmLayouts.UINT32, offset("abi_version"), FfmAbi.VERSION);
                config.set(FfmLayouts.UINT32, offset("operation"), operation);
                write(config, "extension_id", extensionId, callArena, 32);
                write(config, "expected_version", expectedVersion, callArena, 128);
                write(
                    config,
                    "extension_path",
                    extensionPath,
                    callArena,
                    FfmMemory.MAXIMUM_PATH_SIZE
                );
                config.set(FfmLayouts.POINTER, offset("callback"), owner.stub());
                config.set(FfmLayouts.POINTER, offset("user_data"), MemorySegment.NULL);
                int status = invokeStart(
                    library.handle("kweb_extension_start"),
                    browser,
                    config,
                    output
                );
                if (status != FfmStatus.OK) {
                    abort(owner);
                    return encodeFailure(status);
                }
                long handle = output.get(FfmLayouts.UINT64, 0);
                if (!owner.bindHandle(handle)) {
                    abort(owner);
                    return encodeFailure(FfmStatus.EXTENSION_RESULT_INVALID);
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

    static int cancel(long operation) {
        try {
            return (int) library().handle("kweb_extension_cancel").invokeExact(operation);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    static long liveCount() {
        try {
            return (long) library().handle("kweb_live_extension_operation_count").invokeExact();
        } catch (Throwable error) {
            return -1;
        }
    }

    static Throwable release(long handle) {
        FfmExtensionCallbackOwner owner = OWNERS.get(handle);
        if (owner == null) {
            throw new IllegalStateException("No FFM extension callback owner exists for handle " + handle + '.');
        }
        Throwable failure = owner.releaseAfterTerminal();
        if (!OWNERS.remove(handle, owner)) {
            throw new IllegalStateException("The FFM extension callback owner changed during release.");
        }
        return failure;
    }

    static int liveOwnerCount() {
        return OWNERS.size();
    }

    private static void register(long handle, FfmExtensionCallbackOwner owner) {
        if (owner.isClosed()) {
            return;
        }
        if (!owner.bindHandle(handle)) {
            throw new IllegalStateException("The FFM extension owner rejected its native handle.");
        }
        FfmExtensionCallbackOwner existing = OWNERS.putIfAbsent(handle, owner);
        if (existing != null && existing != owner) {
            throw new IllegalStateException(
                "A different FFM extension owner already uses handle " + handle + '.'
            );
        }
        if (owner.isClosed()) {
            OWNERS.remove(handle, owner);
        }
    }

    private static void abort(FfmExtensionCallbackOwner owner) {
        if (owner == null || owner.isClosed()) {
            return;
        }
        long handle = owner.handle();
        if (handle > 0) {
            OWNERS.remove(handle, owner);
        }
        owner.abortBeforeOwnership();
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
            FfmLayouts.EXTENSION_CONFIG,
            field,
            FfmMemory.encode(value, arena, maximumSize)
        );
    }

    private static int invokeStart(
        MethodHandle handle,
        long browser,
        MemorySegment config,
        MemorySegment output
    ) {
        try {
            return (int) handle.invokeExact(browser, config, output);
        } catch (Throwable error) {
            return FfmStatus.INTERNAL_ERROR;
        }
    }

    private static long offset(String field) {
        return FfmLayouts.offset(FfmLayouts.EXTENSION_CONFIG, field);
    }

    private static long encodeFailure(int status) {
        return -Integer.toUnsignedLong(status);
    }

    private static FfmEngineLibrary library() {
        return FfmEngineLibrary.requireLoaded();
    }

    private FfmExtensionCalls() {
    }
}
