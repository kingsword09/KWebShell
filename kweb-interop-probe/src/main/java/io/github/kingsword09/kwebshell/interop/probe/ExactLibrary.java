package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;

final class ExactLibrary {
    static SymbolLookup open(Path path, Arena arena) {
        if (!path.isAbsolute() || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("Native library path must be absolute and normalized: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Native library path must identify a regular file: " + path);
        }
        return SymbolLookup.libraryLookup(path, arena);
    }

    static Path argument(String value, String name) {
        final Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(name + " is not a valid native library path.", error);
        }
        if (!path.isAbsolute() || !path.equals(path.normalize()) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(name + " must be an absolute normalized regular file: " + path);
        }
        return path;
    }

    private ExactLibrary() {
    }
}
