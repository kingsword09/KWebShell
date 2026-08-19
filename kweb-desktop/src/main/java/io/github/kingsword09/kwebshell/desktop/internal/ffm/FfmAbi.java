package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.FunctionDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FfmAbi {
    public static final int VERSION = 6;

    public static final FunctionDescriptor ENGINE_CALLBACK = FunctionDescriptor.ofVoid(
        FfmLayouts.POINTER,
        FfmLayouts.POINTER
    );
    public static final FunctionDescriptor BROWSER_CALLBACK = FunctionDescriptor.ofVoid(
        FfmLayouts.POINTER,
        FfmLayouts.POINTER
    );
    public static final FunctionDescriptor BRIDGE_CALLBACK = FunctionDescriptor.ofVoid(
        FfmLayouts.POINTER,
        FfmLayouts.POINTER
    );
    public static final FunctionDescriptor EXTENSION_CALLBACK = FunctionDescriptor.ofVoid(
        FfmLayouts.POINTER,
        FfmLayouts.POINTER
    );

    private static final FunctionDescriptor STATUS_FROM_HANDLE = FunctionDescriptor.of(
        FfmLayouts.UINT32,
        FfmLayouts.UINT64
    );
    private static final FunctionDescriptor LIVE_COUNT = FunctionDescriptor.of(FfmLayouts.UINT64);

    public static final List<FunctionSpec> FUNCTIONS = List.of(
        function("kweb_engine_abi_version", FunctionDescriptor.of(FfmLayouts.UINT32)),
        function(
            "kweb_status_name",
            FunctionDescriptor.of(FfmLayouts.POINTER, FfmLayouts.UINT32)
        ),
        function(
            "kweb_engine_platform_startup",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.POINTER,
                FfmLayouts.SIZE_T
            )
        ),
        function(
            "kweb_engine_create",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.POINTER,
                FfmLayouts.POINTER
            )
        ),
        function("kweb_engine_close", STATUS_FROM_HANDLE),
        function("kweb_live_engine_count", LIVE_COUNT),
        function(
            "kweb_browser_create",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.POINTER,
                FfmLayouts.POINTER
            )
        ),
        function(
            "kweb_browser_navigate",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.UINT64,
                FfmLayouts.POINTER,
                FfmLayouts.SIZE_T
            )
        ),
        function(
            "kweb_browser_resize",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.UINT64,
                FfmLayouts.INT32,
                FfmLayouts.INT32
            )
        ),
        function("kweb_browser_close", STATUS_FROM_HANDLE),
        function("kweb_browser_open_devtools", STATUS_FROM_HANDLE),
        function("kweb_browser_close_devtools", STATUS_FROM_HANDLE),
        function(
            "kweb_browser_bridge_respond",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.UINT64,
                FfmLayouts.UINT64,
                FfmLayouts.POINTER,
                FfmLayouts.SIZE_T
            )
        ),
        function(
            "kweb_browser_bridge_fail",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.UINT64,
                FfmLayouts.UINT64,
                FfmLayouts.POINTER,
                FfmLayouts.SIZE_T
            )
        ),
        function("kweb_live_browser_count", LIVE_COUNT),
        function(
            "kweb_extension_start",
            FunctionDescriptor.of(
                FfmLayouts.UINT32,
                FfmLayouts.UINT64,
                FfmLayouts.POINTER,
                FfmLayouts.POINTER
            )
        ),
        function("kweb_extension_cancel", STATUS_FROM_HANDLE),
        function("kweb_live_extension_operation_count", LIVE_COUNT)
    );

    private static final Map<String, FunctionDescriptor> DESCRIPTORS = descriptorsByName();

    static {
        if (FUNCTIONS.size() != 18 || DESCRIPTORS.size() != 18) {
            throw new ExceptionInInitializerError("The KWebShell ABI must contain exactly 18 exports.");
        }
    }

    public static FunctionDescriptor descriptor(String symbol) {
        FunctionDescriptor descriptor = DESCRIPTORS.get(symbol);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown KWebShell ABI symbol: " + symbol);
        }
        return descriptor;
    }

    private static FunctionSpec function(String name, FunctionDescriptor descriptor) {
        return new FunctionSpec(name, descriptor);
    }

    private static Map<String, FunctionDescriptor> descriptorsByName() {
        Map<String, FunctionDescriptor> descriptors = new LinkedHashMap<>();
        for (FunctionSpec function : FUNCTIONS) {
            if (descriptors.put(function.name(), function.descriptor()) != null) {
                throw new ExceptionInInitializerError("Duplicate KWebShell ABI symbol: " + function.name());
            }
        }
        return Map.copyOf(descriptors);
    }

    public record FunctionSpec(String name, FunctionDescriptor descriptor) {
        public FunctionSpec {
            if (name == null || name.isBlank() || descriptor == null) {
                throw new IllegalArgumentException("FFM function specifications must be complete.");
            }
        }
    }

    private FfmAbi() {
    }
}
