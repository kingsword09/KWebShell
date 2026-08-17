package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.List;

final class NativeLayouts {
    static final ValueLayout.OfInt UINT32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfInt INT32 = ValueLayout.JAVA_INT;
    static final ValueLayout.OfLong UINT64 = ValueLayout.JAVA_LONG;
    static final AddressLayout POINTER = ValueLayout.ADDRESS;
    static final ValueLayout.OfLong SIZE_T = (ValueLayout.OfLong)
        Linker.nativeLinker().canonicalLayouts().get("size_t");

    static final LayoutSpec STRING_VIEW = new LayoutSpec(
        1,
        MemoryLayout.structLayout(
            POINTER.withName("data"),
            SIZE_T.withName("size")
        ).withName("kweb_string_view"),
        List.of("data", "size")
    );

    static final LayoutSpec ENGINE_EVENT = new LayoutSpec(
        2,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT32.withName("type"),
            UINT32.withName("reserved"),
            UINT64.withName("engine"),
            UINT64.withName("sequence")
        ).withName("kweb_engine_event"),
        List.of("struct_size", "abi_version", "type", "reserved", "engine", "sequence")
    );

    static final LayoutSpec ENGINE_CONFIG = new LayoutSpec(
        3,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            POINTER.withName("callback"),
            POINTER.withName("user_data"),
            STRING_VIEW.layout().withName("cef_runtime_path"),
            STRING_VIEW.layout().withName("browser_subprocess_path"),
            STRING_VIEW.layout().withName("resources_path"),
            STRING_VIEW.layout().withName("locales_path"),
            STRING_VIEW.layout().withName("root_cache_path"),
            STRING_VIEW.layout().withName("log_path"),
            INT32.withName("remote_debugging_port"),
            UINT32.withName("reserved")
        ).withName("kweb_engine_config"),
        List.of(
            "struct_size", "abi_version", "callback", "user_data",
            "cef_runtime_path", "browser_subprocess_path", "resources_path",
            "locales_path", "root_cache_path", "log_path",
            "remote_debugging_port", "reserved"
        )
    );

    static final LayoutSpec BROWSER_EVENT = new LayoutSpec(
        4,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT32.withName("type"),
            UINT32.withName("flags"),
            UINT64.withName("engine"),
            UINT64.withName("browser"),
            UINT64.withName("sequence"),
            STRING_VIEW.layout().withName("text"),
            INT32.withName("status_code"),
            INT32.withName("width"),
            INT32.withName("height"),
            UINT32.withName("reserved")
        ).withName("kweb_browser_event"),
        List.of(
            "struct_size", "abi_version", "type", "flags", "engine", "browser",
            "sequence", "text", "status_code", "width", "height", "reserved"
        )
    );

    static final LayoutSpec BRIDGE_EVENT = new LayoutSpec(
        5,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT32.withName("type"),
            UINT32.withName("reserved"),
            UINT64.withName("engine"),
            UINT64.withName("browser"),
            UINT64.withName("request_id"),
            STRING_VIEW.layout().withName("payload")
        ).withName("kweb_bridge_event"),
        List.of(
            "struct_size", "abi_version", "type", "reserved",
            "engine", "browser", "request_id", "payload"
        )
    );

    static final LayoutSpec EXTENSION_RESULT = new LayoutSpec(
        6,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT64.withName("operation_handle"),
            UINT32.withName("operation"),
            UINT32.withName("outcome"),
            UINT32.withName("state"),
            UINT32.withName("reserved"),
            UINT64.withName("engine"),
            UINT64.withName("browser"),
            STRING_VIEW.layout().withName("extension_id"),
            STRING_VIEW.layout().withName("version"),
            STRING_VIEW.layout().withName("path"),
            STRING_VIEW.layout().withName("error_code"),
            STRING_VIEW.layout().withName("error_message")
        ).withName("kweb_extension_result"),
        List.of(
            "struct_size", "abi_version", "operation_handle", "operation",
            "outcome", "state", "reserved", "engine", "browser",
            "extension_id", "version", "path", "error_code", "error_message"
        )
    );

    static final LayoutSpec EXTENSION_CONFIG = new LayoutSpec(
        7,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT32.withName("operation"),
            UINT32.withName("reserved"),
            STRING_VIEW.layout().withName("extension_id"),
            STRING_VIEW.layout().withName("expected_version"),
            STRING_VIEW.layout().withName("extension_path"),
            POINTER.withName("callback"),
            POINTER.withName("user_data")
        ).withName("kweb_extension_config"),
        List.of(
            "struct_size", "abi_version", "operation", "reserved",
            "extension_id", "expected_version", "extension_path", "callback", "user_data"
        )
    );

    static final LayoutSpec BROWSER_CONFIG = new LayoutSpec(
        8,
        MemoryLayout.structLayout(
            UINT32.withName("struct_size"),
            UINT32.withName("abi_version"),
            UINT64.withName("engine"),
            UINT32.withName("reserved"),
            MemoryLayout.paddingLayout(4),
            SIZE_T.withName("native_parent"),
            INT32.withName("x"),
            INT32.withName("y"),
            INT32.withName("width"),
            INT32.withName("height"),
            STRING_VIEW.layout().withName("profile_path"),
            STRING_VIEW.layout().withName("initial_url"),
            POINTER.withName("callback"),
            POINTER.withName("user_data"),
            STRING_VIEW.layout().withName("bridge_origin"),
            POINTER.withName("bridge_callback"),
            POINTER.withName("bridge_user_data")
        ).withName("kweb_browser_config"),
        List.of(
            "struct_size", "abi_version", "engine", "reserved", "native_parent",
            "x", "y", "width", "height", "profile_path", "initial_url",
            "callback", "user_data", "bridge_origin", "bridge_callback", "bridge_user_data"
        )
    );

    static final List<LayoutSpec> ALL = List.of(
        STRING_VIEW,
        ENGINE_EVENT,
        ENGINE_CONFIG,
        BROWSER_EVENT,
        BRIDGE_EVENT,
        EXTENSION_RESULT,
        EXTENSION_CONFIG,
        BROWSER_CONFIG
    );

    record LayoutSpec(int nativeId, GroupLayout layout, List<String> fields) {
        long offset(int index) {
            return layout.byteOffset(MemoryLayout.PathElement.groupElement(fields.get(index)));
        }
    }

    private NativeLayouts() {
    }
}
