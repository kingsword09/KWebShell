package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.function.BiConsumer;

final class FfmEngineCallbackOwner extends FfmCallbackOwner {
    private static final int CLOSED_EVENT = 2;
    private static final MethodHandle CALLBACK_TARGET = callbackTarget();
    private static final long STRUCT_SIZE = FfmLayouts.offset(FfmLayouts.ENGINE_EVENT, "struct_size");
    private static final long ABI_VERSION = FfmLayouts.offset(FfmLayouts.ENGINE_EVENT, "abi_version");
    private static final long TYPE = FfmLayouts.offset(FfmLayouts.ENGINE_EVENT, "type");
    private static final long ENGINE = FfmLayouts.offset(FfmLayouts.ENGINE_EVENT, "engine");
    private static final long SEQUENCE = FfmLayouts.offset(FfmLayouts.ENGINE_EVENT, "sequence");

    private final FfmCallbacks.EngineEvent sink;
    private final BiConsumer<Long, FfmEngineCallbackOwner> registrar;
    private final MemorySegment stub;

    @SuppressWarnings("restricted")
    FfmEngineCallbackOwner(
        FfmEngineLibrary library,
        FfmCallbacks.EngineEvent sink,
        FfmCallbacks.Failure failureSink,
        BiConsumer<Long, FfmEngineCallbackOwner> registrar
    ) {
        super(failureSink);
        this.sink = Objects.requireNonNull(sink, "sink");
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.stub = library.linker().upcallStub(
            CALLBACK_TARGET.bindTo(this),
            FfmAbi.ENGINE_CALLBACK,
            arena()
        );
    }

    MemorySegment stub() {
        return stub;
    }

    @SuppressWarnings("restricted")
    private static void receive(
        FfmEngineCallbackOwner owner,
        MemorySegment userData,
        MemorySegment eventPointer
    ) {
        if (!owner.beginCallback()) {
            owner.recordFailure(
                "native.ffm.engine-callback-after-close",
                "An engine callback entered after its FFM owner closed.",
                null
            );
            return;
        }
        boolean terminal = false;
        try {
            if (eventPointer.address() == 0) {
                throw new IllegalArgumentException("The native engine event pointer is null.");
            }
            MemorySegment event = eventPointer.reinterpret(FfmLayouts.ENGINE_EVENT.byteSize());
            long structureSize = Integer.toUnsignedLong(event.get(FfmLayouts.UINT32, STRUCT_SIZE));
            int abiVersion = event.get(FfmLayouts.UINT32, ABI_VERSION);
            int type = event.get(FfmLayouts.UINT32, TYPE);
            long engine = event.get(FfmLayouts.UINT64, ENGINE);
            long sequence = event.get(FfmLayouts.UINT64, SEQUENCE);
            if (structureSize < FfmLayouts.ENGINE_EVENT.byteSize()
                || abiVersion != FfmAbi.VERSION
                || engine <= 0
                || sequence <= 0
                || (type != 1 && type != CLOSED_EVENT)) {
                throw new IllegalArgumentException("The native engine event violates ABI version 6.");
            }
            if (!owner.bindHandle(engine)) {
                return;
            }
            owner.registrar.accept(engine, owner);
            terminal = type == CLOSED_EVENT;
            owner.sink.onEvent(engine, sequence, type);
        } catch (Throwable error) {
            owner.recordFailure(
                "native.ffm.engine-callback-failed",
                "The FFM engine callback could not be decoded or dispatched.",
                error
            );
        } finally {
            owner.finishCallback(terminal);
        }
    }

    private static MethodHandle callbackTarget() {
        try {
            return MethodHandles.lookup().findStatic(
                FfmEngineCallbackOwner.class,
                "receive",
                MethodType.methodType(
                    void.class,
                    FfmEngineCallbackOwner.class,
                    MemorySegment.class,
                    MemorySegment.class
                )
            );
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
