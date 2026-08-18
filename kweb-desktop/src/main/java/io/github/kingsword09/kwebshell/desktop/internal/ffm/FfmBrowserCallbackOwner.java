package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;
import java.util.function.BiConsumer;

final class FfmBrowserCallbackOwner extends FfmCallbackOwner {
    private static final int CLOSED_EVENT = 10;
    private static final MethodHandle BROWSER_TARGET = callbackTarget("receiveBrowser");
    private static final MethodHandle BRIDGE_TARGET = callbackTarget("receiveBridge");

    private static final long BROWSER_STRUCT_SIZE = offset(FfmLayouts.BROWSER_EVENT, "struct_size");
    private static final long BROWSER_ABI_VERSION = offset(FfmLayouts.BROWSER_EVENT, "abi_version");
    private static final long BROWSER_TYPE = offset(FfmLayouts.BROWSER_EVENT, "type");
    private static final long BROWSER_FLAGS = offset(FfmLayouts.BROWSER_EVENT, "flags");
    private static final long BROWSER_ENGINE = offset(FfmLayouts.BROWSER_EVENT, "engine");
    private static final long BROWSER_HANDLE = offset(FfmLayouts.BROWSER_EVENT, "browser");
    private static final long BROWSER_SEQUENCE = offset(FfmLayouts.BROWSER_EVENT, "sequence");
    private static final long BROWSER_STATUS = offset(FfmLayouts.BROWSER_EVENT, "status_code");
    private static final long BROWSER_WIDTH = offset(FfmLayouts.BROWSER_EVENT, "width");
    private static final long BROWSER_HEIGHT = offset(FfmLayouts.BROWSER_EVENT, "height");

    private static final long BRIDGE_STRUCT_SIZE = offset(FfmLayouts.BRIDGE_EVENT, "struct_size");
    private static final long BRIDGE_ABI_VERSION = offset(FfmLayouts.BRIDGE_EVENT, "abi_version");
    private static final long BRIDGE_TYPE = offset(FfmLayouts.BRIDGE_EVENT, "type");
    private static final long BRIDGE_ENGINE = offset(FfmLayouts.BRIDGE_EVENT, "engine");
    private static final long BRIDGE_BROWSER = offset(FfmLayouts.BRIDGE_EVENT, "browser");
    private static final long BRIDGE_REQUEST = offset(FfmLayouts.BRIDGE_EVENT, "request_id");

    private final FfmCallbacks.BrowserEvent browserSink;
    private final FfmCallbacks.BridgeEvent bridgeSink;
    private final BiConsumer<Long, FfmBrowserCallbackOwner> registrar;
    private final MemorySegment browserStub;
    private final MemorySegment bridgeStub;

    @SuppressWarnings("restricted")
    FfmBrowserCallbackOwner(
        FfmEngineLibrary library,
        FfmCallbacks.BrowserEvent browserSink,
        FfmCallbacks.BridgeEvent bridgeSink,
        FfmCallbacks.Failure failureSink,
        BiConsumer<Long, FfmBrowserCallbackOwner> registrar
    ) {
        super(failureSink);
        this.browserSink = Objects.requireNonNull(browserSink, "browserSink");
        this.bridgeSink = bridgeSink;
        this.registrar = Objects.requireNonNull(registrar, "registrar");
        this.browserStub = library.linker().upcallStub(
            BROWSER_TARGET.bindTo(this),
            FfmAbi.BROWSER_CALLBACK,
            arena()
        );
        this.bridgeStub = bridgeSink == null
            ? MemorySegment.NULL
            : library.linker().upcallStub(
                BRIDGE_TARGET.bindTo(this),
                FfmAbi.BRIDGE_CALLBACK,
                arena()
            );
    }

    MemorySegment browserStub() {
        return browserStub;
    }

    MemorySegment bridgeStub() {
        return bridgeStub;
    }

    @SuppressWarnings("restricted")
    private static void receiveBrowser(
        FfmBrowserCallbackOwner owner,
        MemorySegment userData,
        MemorySegment eventPointer
    ) {
        if (!owner.beginCallback()) {
            owner.recordFailure(
                "native.ffm.browser-callback-after-close",
                "A browser callback entered after its FFM owner closed.",
                null
            );
            return;
        }
        boolean terminal = false;
        try {
            if (eventPointer.address() == 0) {
                throw new IllegalArgumentException("The native browser event pointer is null.");
            }
            MemorySegment event = eventPointer.reinterpret(FfmLayouts.BROWSER_EVENT.byteSize());
            long structureSize = Integer.toUnsignedLong(event.get(FfmLayouts.UINT32, BROWSER_STRUCT_SIZE));
            int abiVersion = event.get(FfmLayouts.UINT32, BROWSER_ABI_VERSION);
            int type = event.get(FfmLayouts.UINT32, BROWSER_TYPE);
            int flags = event.get(FfmLayouts.UINT32, BROWSER_FLAGS);
            long engine = event.get(FfmLayouts.UINT64, BROWSER_ENGINE);
            long browser = event.get(FfmLayouts.UINT64, BROWSER_HANDLE);
            long sequence = event.get(FfmLayouts.UINT64, BROWSER_SEQUENCE);
            if (structureSize < FfmLayouts.BROWSER_EVENT.byteSize()
                || abiVersion != FfmAbi.VERSION
                || type < 1
                || type > 13
                || engine <= 0
                || browser <= 0
                || sequence <= 0) {
                throw new IllegalArgumentException("The native browser event violates ABI version 6.");
            }
            if (!owner.bindHandle(browser)) {
                return;
            }
            owner.registrar.accept(browser, owner);
            terminal = type == CLOSED_EVENT;
            String text = FfmMemory.readStringView(
                event,
                FfmLayouts.BROWSER_EVENT,
                "text",
                FfmMemory.MAXIMUM_TEXT_SIZE
            );
            owner.browserSink.onEvent(
                engine,
                browser,
                sequence,
                type,
                flags,
                text,
                event.get(FfmLayouts.INT32, BROWSER_STATUS),
                event.get(FfmLayouts.INT32, BROWSER_WIDTH),
                event.get(FfmLayouts.INT32, BROWSER_HEIGHT)
            );
        } catch (Throwable error) {
            owner.recordFailure(
                "native.ffm.browser-callback-failed",
                "The FFM browser callback could not be decoded or dispatched.",
                error
            );
        } finally {
            owner.finishCallback(terminal);
        }
    }

    @SuppressWarnings("restricted")
    private static void receiveBridge(
        FfmBrowserCallbackOwner owner,
        MemorySegment userData,
        MemorySegment eventPointer
    ) {
        if (!owner.beginCallback()) {
            owner.recordFailure(
                "native.ffm.bridge-callback-after-close",
                "A bridge callback entered after its FFM owner closed.",
                null
            );
            return;
        }
        try {
            if (eventPointer.address() == 0 || owner.bridgeSink == null) {
                throw new IllegalArgumentException("The native bridge callback is not configured.");
            }
            MemorySegment event = eventPointer.reinterpret(FfmLayouts.BRIDGE_EVENT.byteSize());
            long structureSize = Integer.toUnsignedLong(event.get(FfmLayouts.UINT32, BRIDGE_STRUCT_SIZE));
            int abiVersion = event.get(FfmLayouts.UINT32, BRIDGE_ABI_VERSION);
            int type = event.get(FfmLayouts.UINT32, BRIDGE_TYPE);
            long engine = event.get(FfmLayouts.UINT64, BRIDGE_ENGINE);
            long browser = event.get(FfmLayouts.UINT64, BRIDGE_BROWSER);
            long requestId = event.get(FfmLayouts.UINT64, BRIDGE_REQUEST);
            if (structureSize < FfmLayouts.BRIDGE_EVENT.byteSize()
                || abiVersion != FfmAbi.VERSION
                || (type != 1 && type != 2)
                || engine <= 0
                || browser <= 0
                || requestId <= 0
                || !owner.bindHandle(browser)) {
                throw new IllegalArgumentException("The native bridge event violates ABI version 6.");
            }
            owner.registrar.accept(browser, owner);
            String payload = FfmMemory.readStringView(
                event,
                FfmLayouts.BRIDGE_EVENT,
                "payload",
                FfmMemory.MAXIMUM_TEXT_SIZE
            );
            owner.bridgeSink.onEvent(engine, browser, requestId, type, payload);
        } catch (Throwable error) {
            owner.recordFailure(
                "native.ffm.bridge-callback-failed",
                "The FFM bridge callback could not be decoded or dispatched.",
                error
            );
        } finally {
            owner.finishCallback(false);
        }
    }

    private static long offset(java.lang.foreign.GroupLayout layout, String field) {
        return FfmLayouts.offset(layout, field);
    }

    private static MethodHandle callbackTarget(String name) {
        try {
            return MethodHandles.lookup().findStatic(
                FfmBrowserCallbackOwner.class,
                name,
                MethodType.methodType(
                    void.class,
                    FfmBrowserCallbackOwner.class,
                    MemorySegment.class,
                    MemorySegment.class
                )
            );
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }
}
