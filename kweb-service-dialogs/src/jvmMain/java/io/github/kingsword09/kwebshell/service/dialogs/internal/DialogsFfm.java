package io.github.kingsword09.kwebshell.service.dialogs.internal;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class DialogsFfm implements AutoCloseable {
    public static final int PENDING = 0, VISIBLE = 1, SELECTED = 2, CANCELLED = 3, FAILED = 4;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong I64 = ValueLayout.JAVA_LONG;
    private static final AddressLayout PTR = ValueLayout.ADDRESS;
    private static final int MAX_PATH = 16384;
    private static final MemoryLayout TEXT = MemoryLayout.structLayout(PTR, I64);
    private static final MemoryLayout REQUEST = MemoryLayout.structLayout(I32, I32, I32, I32, I64, TEXT, TEXT, TEXT, PTR);
    private static final MemoryLayout RESULT = MemoryLayout.structLayout(I32, I32, I32, I32, I64);
    private final Arena libraryArena;
    private final MethodHandle start, poll, cancel, release;
    private final Set<Long> owned = new HashSet<>();
    private boolean closed;

    public DialogsFfm(Path library) {
        if (!library.isAbsolute() || !Files.isRegularFile(library)) {
            throw new IllegalArgumentException("The dialogs library must be an absolute regular file.");
        }
        libraryArena = Arena.ofShared();
        try {
            SymbolLookup symbols = SymbolLookup.libraryLookup(library.toRealPath(), libraryArena);
            MethodHandle version = bind(symbols, "kweb_dialog_abi_version", FunctionDescriptor.of(I32));
            if ((int) version.invokeExact() != 1 || PTR.byteSize() != 8) {
                throw new IllegalStateException("The dialogs provider ABI is incompatible.");
            }
            start = bind(symbols, "kweb_dialog_start", FunctionDescriptor.of(I32, PTR, PTR));
            poll = bind(symbols, "kweb_dialog_poll", FunctionDescriptor.of(I32, I64, PTR, PTR, I64));
            cancel = bind(symbols, "kweb_dialog_cancel", FunctionDescriptor.of(I32, I64));
            release = bind(symbols, "kweb_dialog_release", FunctionDescriptor.of(I32, I64));
        } catch (Throwable error) {
            libraryArena.close();
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Could not bind the dialogs ABI.", error);
        }
    }

    public synchronized long start(long owner, int mode, String title, String directory, String name, String[] extensions) {
        requireOpen();
        try (Arena call = Arena.ofConfined()) {
            MemorySegment request = call.allocate(REQUEST);
            request.set(I32, 0, (int) REQUEST.byteSize());
            request.set(I32, 4, 1);
            request.set(I32, 8, mode);
            request.set(I32, 12, extensions.length);
            request.set(I64, 16, owner);
            text(call, request, 24, title);
            text(call, request, 40, directory);
            text(call, request, 56, name);
            if (extensions.length > 0) {
                MemorySegment items = call.allocate(TEXT, extensions.length);
                for (int i = 0; i < extensions.length; ++i) text(call, items, i * TEXT.byteSize(), extensions[i]);
                request.set(PTR, 72, items);
            }
            MemorySegment id = call.allocate(I64);
            check((int) start.invokeExact(request, id));
            long handle = id.get(I64, 0);
            if (handle == 0 || !owned.add(handle)) throw new IllegalStateException("Invalid dialog operation ID.");
            return handle;
        } catch (Throwable error) { throw failure(error); }
    }

    public synchronized Result poll(long id) {
        requireOwned(id);
        try (Arena call = Arena.ofConfined()) {
            MemorySegment result = call.allocate(RESULT);
            result.set(I32, 0, (int) RESULT.byteSize());
            result.set(I32, 4, 1);
            MemorySegment path = call.allocate(MAX_PATH);
            check((int) poll.invokeExact(id, result, path, (long) MAX_PATH));
            int state = result.get(I32, 8);
            int failure = result.get(I32, 12);
            long length = result.get(I64, 16);
            if (state < PENDING || state > FAILED || length < 0 || length > MAX_PATH ||
                (state != SELECTED && length != 0)) throw new IllegalStateException("Invalid dialog result.");
            String selected = null;
            if (state == SELECTED) {
                selected = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(path.asSlice(0, length).asByteBuffer()).toString();
                if (selected.isEmpty() || selected.indexOf('\0') >= 0) throw new IllegalStateException("Invalid selection path.");
            }
            return new Result(state, failure, selected);
        } catch (Throwable error) { throw failure(error); }
    }

    public synchronized void cancel(long id) {
        requireOwned(id);
        try { check((int) cancel.invokeExact(id)); }
        catch (Throwable error) { throw failure(error); }
    }

    public synchronized void release(long id) {
        requireOwned(id);
        try {
            check((int) release.invokeExact(id));
            owned.remove(id);
        } catch (Throwable error) { throw failure(error); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        if (!owned.isEmpty()) throw new IllegalStateException("Native dialogs must terminate before library close.");
        libraryArena.close();
        closed = true;
    }

    public static long requestSize() { return REQUEST.byteSize(); }
    public static long resultSize() { return RESULT.byteSize(); }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("The dialogs binding is closed.");
    }
    private void requireOwned(long id) {
        requireOpen();
        if (!owned.contains(id)) throw new IllegalArgumentException("The dialog operation is not owned by this binding.");
    }
    private static MethodHandle bind(SymbolLookup symbols, String name, FunctionDescriptor descriptor) {
        return Linker.nativeLinker().downcallHandle(symbols.find(name)
            .orElseThrow(() -> new IllegalStateException("Missing dialog symbol: " + name)), descriptor);
    }
    private static void text(Arena arena, MemorySegment struct, long offset, String value) throws Exception {
        if (value == null || value.isEmpty()) return;
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Dialog text contains NUL.");
        ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(value));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        MemorySegment data = arena.allocateFrom(ValueLayout.JAVA_BYTE, bytes);
        struct.set(PTR, offset, data);
        struct.set(I64, offset + 8, bytes.length);
    }
    private static void check(int status) {
        if (status != 0) throw new NativeFailure(status);
    }
    private static RuntimeException failure(Throwable error) {
        return error instanceof RuntimeException runtime ? runtime : new IllegalStateException("Dialog native call failed.", error);
    }
    public record Result(int state, int failure, String path) {}
    public static final class NativeFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;
        NativeFailure(int status) { super("Dialog native status " + status); this.status = status; }
    }
}
