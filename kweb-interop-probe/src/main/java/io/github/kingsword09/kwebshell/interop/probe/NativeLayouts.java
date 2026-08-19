package io.github.kingsword09.kwebshell.interop.probe;

import io.github.kingsword09.kwebshell.desktop.internal.ffm.FfmLayouts;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.ValueLayout;
import java.util.List;

final class NativeLayouts {
    static final ValueLayout.OfInt UINT32 = FfmLayouts.UINT32;
    static final ValueLayout.OfInt INT32 = FfmLayouts.INT32;
    static final ValueLayout.OfLong UINT64 = FfmLayouts.UINT64;
    static final AddressLayout POINTER = FfmLayouts.POINTER;
    static final ValueLayout.OfLong SIZE_T = FfmLayouts.SIZE_T;

    static final LayoutSpec STRING_VIEW = spec(
        1,
        FfmLayouts.STRING_VIEW,
        "data", "size"
    );
    static final LayoutSpec ENGINE_EVENT = spec(
        2,
        FfmLayouts.ENGINE_EVENT,
        "struct_size", "abi_version", "type", "reserved", "engine", "sequence"
    );
    static final LayoutSpec ENGINE_CONFIG = spec(
        3,
        FfmLayouts.ENGINE_CONFIG,
        "struct_size", "abi_version", "callback", "user_data",
        "cef_runtime_path", "browser_subprocess_path", "resources_path",
        "locales_path", "root_cache_path", "log_path", "remote_debugging_port", "reserved"
    );
    static final LayoutSpec BROWSER_EVENT = spec(
        4,
        FfmLayouts.BROWSER_EVENT,
        "struct_size", "abi_version", "type", "flags", "engine", "browser",
        "sequence", "text", "status_code", "width", "height", "reserved"
    );
    static final LayoutSpec BRIDGE_EVENT = spec(
        5,
        FfmLayouts.BRIDGE_EVENT,
        "struct_size", "abi_version", "type", "reserved",
        "engine", "browser", "request_id", "payload"
    );
    static final LayoutSpec EXTENSION_RESULT = spec(
        6,
        FfmLayouts.EXTENSION_RESULT,
        "struct_size", "abi_version", "operation_handle", "operation",
        "outcome", "state", "reserved", "engine", "browser",
        "extension_id", "version", "path", "error_code", "error_message"
    );
    static final LayoutSpec EXTENSION_CONFIG = spec(
        7,
        FfmLayouts.EXTENSION_CONFIG,
        "struct_size", "abi_version", "operation", "reserved",
        "extension_id", "expected_version", "extension_path", "callback", "user_data"
    );
    static final LayoutSpec BROWSER_CONFIG = spec(
        8,
        FfmLayouts.BROWSER_CONFIG,
        "struct_size", "abi_version", "engine", "reserved", "native_parent",
        "x", "y", "width", "height", "profile_path", "initial_url",
        "callback", "user_data", "bridge_origin", "bridge_callback", "bridge_user_data"
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

    private static LayoutSpec spec(int nativeId, GroupLayout layout, String... fields) {
        return new LayoutSpec(nativeId, layout, List.of(fields));
    }

    record LayoutSpec(int nativeId, GroupLayout layout, List<String> fields) {
        long offset(int index) {
            return FfmLayouts.offset(layout, fields.get(index));
        }
    }

    private NativeLayouts() {
    }
}
