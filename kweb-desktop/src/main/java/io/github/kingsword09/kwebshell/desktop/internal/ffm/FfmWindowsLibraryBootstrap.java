package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

final class FfmWindowsLibraryBootstrap implements AutoCloseable {
    private static final int LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR = 0x00000100;
    private static final int LOAD_LIBRARY_SEARCH_DEFAULT_DIRS = 0x00001000;
    private static final int LOAD_FLAGS =
        LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS;

    private final Arena arena;
    private final MethodHandle loadLibraryEx;
    private final MethodHandle freeLibrary;
    private final MethodHandle getLastError;
    private final Deque<MemorySegment> modules = new ArrayDeque<>();
    private boolean closed;

    private FfmWindowsLibraryBootstrap(
        Arena arena,
        MethodHandle loadLibraryEx,
        MethodHandle freeLibrary,
        MethodHandle getLastError
    ) {
        this.arena = arena;
        this.loadLibraryEx = loadLibraryEx;
        this.freeLibrary = freeLibrary;
        this.getLastError = getLastError;
    }

    @SuppressWarnings("restricted")
    static FfmWindowsLibraryBootstrap open(Linker linker) {
        Arena arena = Arena.ofConfined();
        try {
            String systemRoot = System.getenv("SystemRoot");
            if (systemRoot == null || systemRoot.isBlank()) {
                throw new IllegalStateException("The Windows SystemRoot environment variable is unavailable.");
            }
            Path kernel32 = Path.of(systemRoot, "System32", "kernel32.dll").toRealPath();
            if (!Files.isRegularFile(kernel32)) {
                throw new IllegalStateException("The Windows kernel32 library is not a regular file: " + kernel32);
            }
            SymbolLookup lookup = SymbolLookup.libraryLookup(kernel32, arena);
            return new FfmWindowsLibraryBootstrap(
                arena,
                downcall(
                    linker,
                    lookup,
                    "LoadLibraryExW",
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
                ),
                downcall(
                    linker,
                    lookup,
                    "FreeLibrary",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS)
                ),
                downcall(
                    linker,
                    lookup,
                    "GetLastError",
                    FunctionDescriptor.of(JAVA_INT)
                )
            );
        } catch (Throwable error) {
            arena.close();
            throw new IllegalStateException("The Windows FFM library bootstrap could not be initialized.", error);
        }
    }

    void preload(Path path) {
        requireOpen();
        MemorySegment widePath = wideString(path.toString());
        final MemorySegment module;
        try {
            module = (MemorySegment) loadLibraryEx.invokeExact(
                widePath,
                MemorySegment.NULL,
                LOAD_FLAGS
            );
        } catch (Throwable error) {
            throw new IllegalStateException(
                "LoadLibraryExW could not be invoked for the exact path '" + path + "'.",
                error
            );
        }
        if (module.address() == 0) {
            throw new IllegalStateException(
                "LoadLibraryExW could not load the exact path '" + path
                    + "' with Win32 error " + Integer.toUnsignedLong(lastError()) + '.'
            );
        }
        modules.addFirst(module);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        while (!modules.isEmpty()) {
            MemorySegment module = modules.removeFirst();
            try {
                int released = (int) freeLibrary.invokeExact(module);
                if (released == 0) {
                    throw new IllegalStateException(
                        "FreeLibrary failed with Win32 error "
                            + Integer.toUnsignedLong(lastError()) + '.'
                    );
                }
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        arena.close();
        if (failure != null) {
            throw new IllegalStateException("The Windows FFM preload handles could not be released.", failure);
        }
    }

    private MemorySegment wideString(String value) {
        long codeUnits = Math.addExact(value.length(), 1);
        MemorySegment result = arena.allocate(
            Math.multiplyExact(codeUnits, Character.BYTES),
            JAVA_CHAR.byteAlignment()
        );
        for (int index = 0; index < value.length(); index++) {
            result.setAtIndex(JAVA_CHAR, index, value.charAt(index));
        }
        result.setAtIndex(JAVA_CHAR, value.length(), '\0');
        return result;
    }

    private int lastError() {
        try {
            return (int) getLastError.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("GetLastError could not be invoked.", error);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("The Windows FFM library bootstrap is closed.");
        }
    }

    @SuppressWarnings("restricted")
    private static MethodHandle downcall(
        Linker linker,
        SymbolLookup lookup,
        String name,
        FunctionDescriptor descriptor
    ) {
        MemorySegment symbol = lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("The Windows symbol '" + name + "' is unavailable."));
        return linker.downcallHandle(symbol, descriptor);
    }

}
