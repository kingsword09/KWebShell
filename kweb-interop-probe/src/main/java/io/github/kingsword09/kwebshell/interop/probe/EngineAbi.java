package io.github.kingsword09.kwebshell.interop.probe;

import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmAbi;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

final class EngineAbi {
    static final int ABI_VERSION = FfmAbi.VERSION;

    @SuppressWarnings("restricted")
    static void verifyAllBindings(Linker linker, SymbolLookup lookup) {
        if (FfmAbi.FUNCTIONS.size() != 18) {
            throw new IllegalStateException("The frozen engine ABI must contain exactly 18 functions.");
        }
        for (FfmAbi.FunctionSpec function : FfmAbi.FUNCTIONS) {
            linker.downcallHandle(find(lookup, function.name()), function.descriptor());
        }
    }

    @SuppressWarnings("restricted")
    static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name) {
        return linker.downcallHandle(find(lookup, name), FfmAbi.descriptor(name));
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Required engine symbol is missing: " + name));
    }

    private EngineAbi() {
    }
}
