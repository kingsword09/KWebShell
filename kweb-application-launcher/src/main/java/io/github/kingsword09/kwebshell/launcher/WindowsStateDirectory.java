package io.github.kingsword09.kwebshell.launcher;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** Resolves the selected directory's physical identity, including MSIX redirection. */
final class WindowsStateDirectory {
    private static final int CAPACITY = 32768;
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
    private static final java.lang.foreign.StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long ERROR_OFFSET = CALL_STATE.byteOffset(
            java.lang.foreign.MemoryLayout.PathElement.groupElement("GetLastError"));
    private static final MethodHandle OPEN = downcall("CreateFileW", FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
    private static final MethodHandle FINAL_PATH = downcall("GetFinalPathNameByHandleW", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
    private static final MethodHandle CLOSE = downcall("CloseHandle", FunctionDescriptor.of(
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(KERNEL.findOrThrow(name), descriptor,
                Linker.Option.captureCallState("GetLastError"));
    }

    private static IOException failure(String operation, MemorySegment state) {
        return new IOException(operation + " failed with Win32 error " +
                Integer.toUnsignedString(state.get(ValueLayout.JAVA_INT, ERROR_OFFSET)) + ".");
    }

    static Path resolve(Path directory) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            byte[] utf16 = (directory.toString() + "\0").getBytes(StandardCharsets.UTF_16LE);
            MemorySegment input = arena.allocate(utf16.length, 2);
            input.copyFrom(MemorySegment.ofArray(utf16));
            MemorySegment handle = (MemorySegment) OPEN.invokeExact(state, input, 0, 7,
                    MemorySegment.NULL, 3, 0x02000000, MemorySegment.NULL);
            if (handle.address() == -1L) throw failure("CreateFileW(directory)", state);
            Throwable primaryFailure = null;
            try {
                MemorySegment output = arena.allocate((long) CAPACITY * 2, 2);
                int length = (int) FINAL_PATH.invokeExact(state, handle, output, CAPACITY, 0);
                if (length == 0) throw failure("GetFinalPathNameByHandleW", state);
                if (length >= CAPACITY) throw new IOException("The physical directory path exceeds 32767 UTF-16 units.");
                String physical = new String(output.asSlice(0, (long) length * 2)
                        .toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_16LE);
                if (physical.startsWith("\\\\?\\UNC\\")) physical = "\\\\" + physical.substring(8);
                else if (physical.startsWith("\\\\?\\")) physical = physical.substring(4);
                Path result = Path.of(physical);
                if (!result.isAbsolute()) throw new IOException("Windows returned a non-absolute physical directory path.");
                return result;
            } catch (Throwable error) {
                primaryFailure = error;
                throw error;
            } finally {
                try {
                    if ((int) CLOSE.invokeExact(state, handle) == 0) throw failure("CloseHandle(directory)", state);
                } catch (Throwable closeError) {
                    if (primaryFailure != null) primaryFailure.addSuppressed(closeError);
                    else throw closeError;
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Throwable error) {
            throw new IOException("Unable to resolve the Windows application-data directory.", error);
        }
    }

    private WindowsStateDirectory() {}
}
