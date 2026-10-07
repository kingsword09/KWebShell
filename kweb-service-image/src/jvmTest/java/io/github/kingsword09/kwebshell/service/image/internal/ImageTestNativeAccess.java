package io.github.kingsword09.kwebshell.service.image.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

public final class ImageTestNativeAccess {
    private ImageTestNativeAccess() {}

    public static int releaseFromAnotherOwner(Path library, long handle) {
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup(library, arena);
            var release = Linker.nativeLinker().downcallHandle(
                lookup.find("kweb_image_release").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, JAVA_LONG)
            );
            return (int) release.invokeExact(handle);
        } catch (Throwable error) {
            throw new AssertionError("The real native release probe failed.", error);
        }
    }
}
