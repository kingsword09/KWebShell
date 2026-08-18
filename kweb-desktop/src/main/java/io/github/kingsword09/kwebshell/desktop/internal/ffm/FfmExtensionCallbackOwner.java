package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.function.BiConsumer;

final class FfmExtensionCallbackOwner extends FfmCallbackOwner {
    private static final MethodHandle CALLBACK_TARGET = callbackTarget();
    private static final long STRUCT_SIZE = offset("struct_size");
    private static final long ABI_VERSION = offset("abi_version");
    private static final long OPERATION_HANDLE = offset("operation_handle");
    private static final long OPERATION = offset("operation");
    private static final long OUTCOME = offset("outcome");
    private static final long STATE = offset("state");
    private static final long ENGINE = offset("engine");
    private static final long BROWSER = offset("browser");

    private final FfmCallbacks.ExtensionResult sink;
    private final BiConsumer<Long, FfmExtensionCallbackOwner> registrar;
    private final MemorySegment stub;

    @SuppressWarnings("restricted")
    FfmExtensionCallbackOwner(
        FfmEngineLibrary library,
        FfmCallbacks.ExtensionResult sink,
        FfmCallbacks.Failure failureSink,
        BiConsumer<Long, FfmExtensionCallbackOwner> registrar
    ) {
        super(failureSink);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.stub = library.linker().upcallStub(
            CALLBACK_TARGET.bindTo(this),
            FfmAbi.EXTENSION_CALLBACK,
            arena()
        );
    }

    MemorySegment stub() {
        return stub;
    }

    @SuppressWarnings("restricted")
    private static void receive(
        FfmExtensionCallbackOwner owner,
        MemorySegment userData,
        MemorySegment resultPointer
    ) {
        if (!owner.beginCallback()) {
            owner.recordFailure(
                "native.ffm.extension-callback-after-close",
                "An extension callback entered after its FFM owner closed.",
                null
            );
            return;
        }
        try {
            if (resultPointer.address() == 0) {
                throw new IllegalArgumentException("The native extension result pointer is null.");
            }
            MemorySegment result = resultPointer.reinterpret(FfmLayouts.EXTENSION_RESULT.byteSize());
            long structureSize = Integer.toUnsignedLong(result.get(FfmLayouts.UINT32, STRUCT_SIZE));
            int abiVersion = result.get(FfmLayouts.UINT32, ABI_VERSION);
            long operationHandle = result.get(FfmLayouts.UINT64, OPERATION_HANDLE);
            int operation = result.get(FfmLayouts.UINT32, OPERATION);
            int outcome = result.get(FfmLayouts.UINT32, OUTCOME);
            int state = result.get(FfmLayouts.UINT32, STATE);
            long engine = result.get(FfmLayouts.UINT64, ENGINE);
            long browser = result.get(FfmLayouts.UINT64, BROWSER);
            if (structureSize < FfmLayouts.EXTENSION_RESULT.byteSize()
                || abiVersion != FfmAbi.VERSION
                || operationHandle <= 0
                || operation < 1
                || operation > 5
                || outcome < 1
                || outcome > 3
                || state < 0
                || state > 6
                || engine <= 0
                || browser <= 0
                || !owner.bindHandle(operationHandle)) {
                throw new IllegalArgumentException("The native extension result violates ABI version 6.");
            }
            owner.registrar.accept(operationHandle, owner);
            owner.sink.onResult(
                operationHandle,
                engine,
                browser,
                operation,
                outcome,
                state,
                read(result, "extension_id", 32),
                read(result, "version", 128),
                read(result, "path", FfmMemory.MAXIMUM_PATH_SIZE),
                read(result, "error_code", 128),
                read(result, "error_message", FfmMemory.MAXIMUM_TEXT_SIZE)
            );
        } catch (Throwable error) {
            owner.recordFailure(
                "native.ffm.extension-callback-failed",
                "The FFM extension callback could not be decoded or dispatched.",
                error
            );
        } finally {
            owner.finishCallback(true);
        }
    }

    private static String read(MemorySegment result, String field, long maximumSize) {
        return FfmMemory.readStringView(
            result,
            FfmLayouts.EXTENSION_RESULT,
            field,
            maximumSize
        );
    }

    private static long offset(String field) {
        return FfmLayouts.offset(FfmLayouts.EXTENSION_RESULT, field);
    }

    private static MethodHandle callbackTarget() {
        try {
            return MethodHandles.lookup().findStatic(
                FfmExtensionCallbackOwner.class,
                "receive",
                MethodType.methodType(
                    void.class,
                    FfmExtensionCallbackOwner.class,
                    MemorySegment.class,
                    MemorySegment.class
                )
            );
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
