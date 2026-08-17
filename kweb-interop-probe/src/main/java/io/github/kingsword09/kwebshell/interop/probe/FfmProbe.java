package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class FfmProbe implements AutoCloseable {
    static final long INVALID_VALUE = -1L;
    static final long MAXIMUM_UTF8_SIZE = 1024L * 1024L;

    private static final FunctionDescriptor FIXED_CALLBACK_DESCRIPTOR = FunctionDescriptor.of(
        NativeLayouts.UINT64,
        NativeLayouts.POINTER,
        NativeLayouts.UINT64
    );
    private static final FunctionDescriptor UTF8_CALLBACK_DESCRIPTOR = FunctionDescriptor.of(
        NativeLayouts.UINT64,
        NativeLayouts.POINTER,
        NativeLayouts.POINTER,
        NativeLayouts.SIZE_T,
        NativeLayouts.UINT64
    );
    private static final MethodHandle FIXED_CALLBACK_TARGET = callbackTarget(
        "receiveFixed",
        MethodType.methodType(long.class, FixedState.class, MemorySegment.class, long.class)
    );
    private static final MethodHandle UTF8_CALLBACK_TARGET = callbackTarget(
        "receiveUtf8",
        MethodType.methodType(
            long.class,
            Utf8State.class,
            MemorySegment.class,
            MemorySegment.class,
            long.class,
            long.class
        )
    );

    private final Arena arena;
    private final Linker linker;
    private final SymbolLookup lookup;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MethodHandle abiVersion;
    private final MethodHandle layoutSize;
    private final MethodHandle layoutAlignment;
    private final MethodHandle layoutFieldCount;
    private final MethodHandle layoutFieldOffset;
    private final MethodHandle integerCall;
    private final MethodHandle utf8Call;
    private final MethodHandle fixedUpcall;
    private final MethodHandle threadedFixedUpcall;
    private final MethodHandle utf8Upcall;
    private final MethodHandle malformedUtf8Upcall;
    private final MethodHandle ownerCycles;
    private final MethodHandle liveNativeBytes;
    private final MethodHandle validateNativeParent;

    static FfmProbe open(Path library) {
        Arena arena = Arena.ofShared();
        try {
            return new FfmProbe(arena, ExactLibrary.open(library, arena));
        } catch (Throwable error) {
            arena.close();
            throw error;
        }
    }

    private FfmProbe(Arena arena, SymbolLookup lookup) {
        this.arena = arena;
        this.linker = Linker.nativeLinker();
        this.lookup = lookup;
        // ABI version is a bounded leaf query that cannot block or call back into Java.
        abiVersion = bind(
            "kweb_probe_abi_version",
            FunctionDescriptor.of(NativeLayouts.UINT32),
            Linker.Option.critical(false)
        );
        layoutSize = bind(
            "kweb_probe_layout_size",
            FunctionDescriptor.of(NativeLayouts.UINT64, NativeLayouts.UINT32)
        );
        layoutAlignment = bind(
            "kweb_probe_layout_alignment",
            FunctionDescriptor.of(NativeLayouts.UINT64, NativeLayouts.UINT32)
        );
        layoutFieldCount = bind(
            "kweb_probe_layout_field_count",
            FunctionDescriptor.of(NativeLayouts.UINT32, NativeLayouts.UINT32)
        );
        layoutFieldOffset = bind(
            "kweb_probe_layout_field_offset",
            FunctionDescriptor.of(NativeLayouts.UINT64, NativeLayouts.UINT32, NativeLayouts.UINT32)
        );
        integerCall = bind(
            "kweb_probe_integer_call",
            FunctionDescriptor.of(
                NativeLayouts.UINT64,
                NativeLayouts.UINT64,
                NativeLayouts.INT32,
                NativeLayouts.INT32
            )
        );
        utf8Call = bind(
            "kweb_probe_utf8_call",
            FunctionDescriptor.of(NativeLayouts.UINT64, NativeLayouts.POINTER, NativeLayouts.SIZE_T)
        );
        fixedUpcall = bind(
            "kweb_probe_fixed_upcall",
            FunctionDescriptor.of(
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER,
                NativeLayouts.UINT32
            )
        );
        threadedFixedUpcall = bind(
            "kweb_probe_threaded_fixed_upcall",
            FunctionDescriptor.of(
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER,
                NativeLayouts.UINT32
            )
        );
        utf8Upcall = bind(
            "kweb_probe_utf8_upcall",
            FunctionDescriptor.of(
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER,
                NativeLayouts.SIZE_T,
                NativeLayouts.UINT32
            )
        );
        malformedUtf8Upcall = bind(
            "kweb_probe_malformed_utf8_upcall",
            FunctionDescriptor.of(NativeLayouts.UINT64, NativeLayouts.POINTER, NativeLayouts.POINTER)
        );
        ownerCycles = bind(
            "kweb_probe_owner_cycles",
            FunctionDescriptor.of(
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER,
                NativeLayouts.UINT32
            )
        );
        liveNativeBytes = bind(
            "kweb_probe_live_native_bytes",
            FunctionDescriptor.of(NativeLayouts.UINT64)
        );
        validateNativeParent = bind(
            "kweb_probe_validate_native_parent",
            FunctionDescriptor.of(NativeLayouts.UINT32, NativeLayouts.SIZE_T)
        );
    }

    int abiVersion() {
        try {
            return (int) abiVersion.invokeExact();
        } catch (Throwable error) {
            throw failure("ABI version query", error);
        }
    }

    long abiVersionBatch(int operations) {
        if (operations <= 0) {
            throw new IllegalArgumentException("operations must be positive.");
        }
        requireOpen();
        MethodHandle call = abiVersion;
        long result = 0;
        try {
            for (int index = 0; index < operations; ++index) {
                result ^= (int) call.invokeExact();
            }
            return result;
        } catch (Throwable error) {
            throw failure("ABI version batch", error);
        }
    }

    long layoutSize(int type) {
        return invokeLong(layoutSize, type);
    }

    long layoutAlignment(int type) {
        return invokeLong(layoutAlignment, type);
    }

    int layoutFieldCount(int type) {
        requireOpen();
        try {
            return (int) layoutFieldCount.invokeExact(type);
        } catch (Throwable error) {
            throw failure("layout field count", error);
        }
    }

    long layoutFieldOffset(int type, int field) {
        requireOpen();
        try {
            return (long) layoutFieldOffset.invokeExact(type, field);
        } catch (Throwable error) {
            throw failure("layout field offset", error);
        }
    }

    long integerCall(long handle, int width, int height) {
        requireOpen();
        try {
            return (long) integerCall.invokeExact(handle, width, height);
        } catch (Throwable error) {
            throw failure("integer call", error);
        }
    }

    long utf8Call(String text) {
        requireOpen();
        try (Arena callArena = Arena.ofConfined()) {
            EncodedUtf8 encoded = encodeStrict(text, callArena);
            try {
                return (long) utf8Call.invokeExact(encoded.segment(), encoded.size());
            } catch (Throwable error) {
                throw failure("UTF-8 call", error);
            }
        }
    }

    long fixedUpcall(FixedCallback callback, int count) {
        return invokeFixedBoundary(fixedUpcall, callback, count, "fixed upcall");
    }

    long threadedFixedUpcall(FixedCallback callback, int count) {
        return invokeFixedBoundary(threadedFixedUpcall, callback, count, "native-thread fixed upcall");
    }

    long ownerCycles(FixedCallback callback, int count) {
        Objects.requireNonNull(callback, "callback");
        if (count <= 0) {
            throw new IllegalArgumentException("owner lifecycle count must be positive.");
        }
        long result = 0;
        for (int sequence = 1; sequence <= count; ++sequence) {
            long deliveredSequence = sequence;
            result ^= invokeFixedBoundary(
                ownerCycles,
                ignored -> callback.receive(deliveredSequence),
                1,
                "owner lifecycle"
            );
        }
        return result;
    }

    private long invokeFixedBoundary(
        MethodHandle operation,
        FixedCallback callback,
        int count,
        String name
    ) {
        Objects.requireNonNull(callback, "callback");
        if (count <= 0) {
            throw new IllegalArgumentException(name + " count must be positive.");
        }
        requireOpen();
        FixedState state = new FixedState(callback, new AtomicReference<>());
        MethodHandle target = FIXED_CALLBACK_TARGET.bindTo(state);
        long result;
        try (Arena callbackArena = Arena.ofShared()) {
            MemorySegment stub = linker.upcallStub(target, FIXED_CALLBACK_DESCRIPTOR, callbackArena);
            result = (long) operation.invokeExact(stub, MemorySegment.NULL, count);
        } catch (Throwable error) {
            throw failure(name, error);
        }
        state.rethrow();
        return result;
    }

    long utf8Upcall(Utf8Callback callback, String text, int count) {
        Objects.requireNonNull(callback, "callback");
        if (count <= 0) {
            throw new IllegalArgumentException("UTF-8 upcall count must be positive.");
        }
        requireOpen();
        Utf8State state = new Utf8State(callback, new AtomicReference<>());
        MethodHandle target = UTF8_CALLBACK_TARGET.bindTo(state);
        long result;
        try (Arena callbackArena = Arena.ofShared()) {
            MemorySegment stub = linker.upcallStub(target, UTF8_CALLBACK_DESCRIPTOR, callbackArena);
            EncodedUtf8 encoded = encodeStrict(text, callbackArena);
            result = (long) utf8Upcall.invokeExact(
                stub,
                MemorySegment.NULL,
                encoded.segment(),
                encoded.size(),
                count
            );
        } catch (Throwable error) {
            throw failure("UTF-8 upcall", error);
        }
        state.rethrow();
        return result;
    }

    long malformedUtf8Upcall(Utf8Callback callback) {
        Objects.requireNonNull(callback, "callback");
        requireOpen();
        Utf8State state = new Utf8State(callback, new AtomicReference<>());
        MethodHandle target = UTF8_CALLBACK_TARGET.bindTo(state);
        long result;
        try (Arena callbackArena = Arena.ofShared()) {
            MemorySegment stub = linker.upcallStub(target, UTF8_CALLBACK_DESCRIPTOR, callbackArena);
            result = (long) malformedUtf8Upcall.invokeExact(stub, MemorySegment.NULL);
        } catch (Throwable error) {
            throw failure("malformed UTF-8 upcall", error);
        }
        state.rethrow();
        return result;
    }

    long liveNativeBytes() {
        requireOpen();
        try {
            return (long) liveNativeBytes.invokeExact();
        } catch (Throwable error) {
            throw failure("native live-byte query", error);
        }
    }

    int validateNativeParent(long handle) {
        requireOpen();
        try {
            return (int) validateNativeParent.invokeExact(handle);
        } catch (Throwable error) {
            throw failure("native parent validation", error);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            throw new IllegalStateException("FFM probe Arena is already closed.");
        }
        arena.close();
    }

    private MethodHandle bind(
        String name,
        FunctionDescriptor descriptor,
        Linker.Option... options
    ) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Required probe symbol is missing: " + name));
        return linker.downcallHandle(symbol, descriptor, options);
    }

    private long invokeLong(MethodHandle handle, int argument) {
        requireOpen();
        try {
            return (long) handle.invokeExact(argument);
        } catch (Throwable error) {
            throw failure("native long call", error);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FFM probe Arena is closed.");
        }
    }

    private static EncodedUtf8 encodeStrict(String value, Arena arena) {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(arena, "arena");
        long capacity = Math.min(
            MAXIMUM_UTF8_SIZE,
            Math.max(1L, Math.multiplyExact((long) value.length(), 3L))
        );
        MemorySegment segment = arena.allocate(capacity, 1);
        ByteBuffer output = segment.asByteBuffer();
        try {
            var encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
            CoderResult encoding = encoder.encode(CharBuffer.wrap(value), output, true);
            requireEncodingResult(encoding);
            CoderResult flushing = encoder.flush(output);
            requireEncodingResult(flushing);
            return new EncodedUtf8(segment, output.position());
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Probe text is not valid Unicode.", error);
        }
    }

    private static void requireEncodingResult(CoderResult result) throws CharacterCodingException {
        if (result.isOverflow()) {
            throw new IllegalArgumentException(
                "Probe UTF-8 payload exceeds " + MAXIMUM_UTF8_SIZE + " bytes."
            );
        }
        if (result.isError()) {
            result.throwException();
        }
    }

    private static String decodeStrict(MemorySegment text, long size) {
        if (size < 0 || size > MAXIMUM_UTF8_SIZE) {
            throw new IllegalArgumentException("Callback UTF-8 payload size is invalid: " + size);
        }
        if (size == 0) {
            return "";
        }
        if (text.address() == 0) {
            throw new IllegalArgumentException("Callback UTF-8 payload pointer is null.");
        }
        byte[] bytes = text.reinterpret(size).toArray(ValueLayout.JAVA_BYTE);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException error) {
            throw new IllegalArgumentException("Callback payload is not valid UTF-8.", error);
        }
    }

    private static long receiveFixed(FixedState state, MemorySegment userData, long sequence) {
        try {
            return state.callback().receive(sequence);
        } catch (Throwable error) {
            state.failure().compareAndSet(null, error);
            return INVALID_VALUE;
        }
    }

    private static long receiveUtf8(
        Utf8State state,
        MemorySegment userData,
        MemorySegment text,
        long size,
        long sequence
    ) {
        try {
            return state.callback().receive(decodeStrict(text, size), sequence);
        } catch (Throwable error) {
            state.failure().compareAndSet(null, error);
            return INVALID_VALUE;
        }
    }

    private static MethodHandle callbackTarget(String name, MethodType type) {
        try {
            return MethodHandles.lookup().findStatic(FfmProbe.class, name, type);
        } catch (ReflectiveOperationException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    @FunctionalInterface
    interface FixedCallback {
        long receive(long sequence);
    }

    @FunctionalInterface
    interface Utf8Callback {
        long receive(String text, long sequence);
    }

    private record EncodedUtf8(MemorySegment segment, long size) {
    }

    private record FixedState(FixedCallback callback, AtomicReference<Throwable> failure) {
        void rethrow() {
            Throwable error = failure.get();
            if (error != null) {
                throw new CallbackFailureException(error);
            }
        }
    }

    private record Utf8State(Utf8Callback callback, AtomicReference<Throwable> failure) {
        void rethrow() {
            Throwable error = failure.get();
            if (error != null) {
                throw new CallbackFailureException(error);
            }
        }
    }

    static final class CallbackFailureException extends RuntimeException {
        CallbackFailureException(Throwable cause) {
            super("Java callback failed after the native upcall returned.", cause);
        }
    }

    private static IllegalStateException failure(String operation, Throwable error) {
        return new IllegalStateException("FFM probe " + operation + " failed.", error);
    }
}
