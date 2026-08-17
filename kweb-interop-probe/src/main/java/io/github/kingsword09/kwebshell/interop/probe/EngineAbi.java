package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.List;

final class EngineAbi {
    static final int ABI_VERSION = 6;

    private static final FunctionDescriptor STATUS_NAME = FunctionDescriptor.of(
        NativeLayouts.POINTER,
        NativeLayouts.UINT32
    );
    private static final FunctionDescriptor STATUS_FROM_POINTER = FunctionDescriptor.of(
        NativeLayouts.UINT32,
        NativeLayouts.POINTER
    );
    private static final FunctionDescriptor STATUS_FROM_HANDLE = FunctionDescriptor.of(
        NativeLayouts.UINT32,
        NativeLayouts.UINT64
    );
    private static final FunctionDescriptor LIVE_COUNT = FunctionDescriptor.of(NativeLayouts.UINT64);

    static final List<FunctionSpec> FUNCTIONS = List.of(
        function("kweb_engine_abi_version", FunctionDescriptor.of(NativeLayouts.UINT32)),
        function("kweb_status_name", STATUS_NAME),
        function(
            "kweb_engine_platform_startup",
            FunctionDescriptor.of(NativeLayouts.UINT32, NativeLayouts.POINTER, NativeLayouts.SIZE_T)
        ),
        function("kweb_engine_create", STATUS_FROM_POINTER.appendArgumentLayouts(NativeLayouts.POINTER)),
        function("kweb_engine_close", STATUS_FROM_HANDLE),
        function("kweb_live_engine_count", LIVE_COUNT),
        function("kweb_browser_create", STATUS_FROM_POINTER.appendArgumentLayouts(NativeLayouts.POINTER)),
        function(
            "kweb_browser_navigate",
            FunctionDescriptor.of(
                NativeLayouts.UINT32,
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.SIZE_T
            )
        ),
        function(
            "kweb_browser_resize",
            FunctionDescriptor.of(
                NativeLayouts.UINT32,
                NativeLayouts.UINT64,
                NativeLayouts.INT32,
                NativeLayouts.INT32
            )
        ),
        function("kweb_browser_close", STATUS_FROM_HANDLE),
        function("kweb_browser_open_devtools", STATUS_FROM_HANDLE),
        function("kweb_browser_close_devtools", STATUS_FROM_HANDLE),
        function(
            "kweb_browser_bridge_respond",
            FunctionDescriptor.of(
                NativeLayouts.UINT32,
                NativeLayouts.UINT64,
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.SIZE_T
            )
        ),
        function(
            "kweb_browser_bridge_fail",
            FunctionDescriptor.of(
                NativeLayouts.UINT32,
                NativeLayouts.UINT64,
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.SIZE_T
            )
        ),
        function("kweb_live_browser_count", LIVE_COUNT),
        function(
            "kweb_extension_start",
            FunctionDescriptor.of(
                NativeLayouts.UINT32,
                NativeLayouts.UINT64,
                NativeLayouts.POINTER,
                NativeLayouts.POINTER
            )
        ),
        function("kweb_extension_cancel", STATUS_FROM_HANDLE),
        function("kweb_live_extension_operation_count", LIVE_COUNT)
    );

    static void verifyAllBindings(Linker linker, SymbolLookup lookup) {
        if (FUNCTIONS.size() != 18) {
            throw new IllegalStateException("The frozen engine ABI must contain exactly 18 functions.");
        }
        for (FunctionSpec function : FUNCTIONS) {
            linker.downcallHandle(find(lookup, function.name()), function.descriptor());
        }
    }

    static MethodHandle downcall(Linker linker, SymbolLookup lookup, String name) {
        FunctionSpec function = FUNCTIONS.stream()
            .filter(candidate -> candidate.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown frozen engine symbol: " + name));
        return linker.downcallHandle(find(lookup, name), function.descriptor());
    }

    private static MemorySegment find(SymbolLookup lookup, String name) {
        return lookup.find(name)
            .orElseThrow(() -> new IllegalStateException("Required engine symbol is missing: " + name));
    }

    private static FunctionSpec function(String name, FunctionDescriptor descriptor) {
        return new FunctionSpec(name, descriptor);
    }

    record FunctionSpec(String name, FunctionDescriptor descriptor) {
    }

    private EngineAbi() {
    }
}
