package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.ValueLayout;
import java.util.Locale;

public final class FfmLayouts {
    public static final ValueLayout.OfInt UINT32 = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfInt INT32 = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfLong UINT64 = ValueLayout.JAVA_LONG;
    public static final AddressLayout POINTER = ValueLayout.ADDRESS;
    public static final ValueLayout.OfLong SIZE_T = nativeSizeT();

    public static final GroupLayout STRING_VIEW = MemoryLayout.structLayout(
        POINTER.withName("data"),
        SIZE_T.withName("size")
    ).withName("kweb_string_view");

    public static final GroupLayout ENGINE_EVENT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("type"),
        UINT32.withName("reserved"),
        UINT64.withName("engine"),
        UINT64.withName("sequence")
    ).withName("kweb_engine_event");

    public static final GroupLayout ENGINE_CONFIG = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        POINTER.withName("callback"),
        POINTER.withName("user_data"),
        STRING_VIEW.withName("cef_runtime_path"),
        STRING_VIEW.withName("browser_subprocess_path"),
        STRING_VIEW.withName("resources_path"),
        STRING_VIEW.withName("locales_path"),
        STRING_VIEW.withName("root_cache_path"),
        STRING_VIEW.withName("log_path"),
        INT32.withName("remote_debugging_port"),
        UINT32.withName("reserved")
    ).withName("kweb_engine_config");

    public static final GroupLayout BROWSER_EVENT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("type"),
        UINT32.withName("flags"),
        UINT64.withName("engine"),
        UINT64.withName("browser"),
        UINT64.withName("sequence"),
        STRING_VIEW.withName("text"),
        INT32.withName("status_code"),
        INT32.withName("width"),
        INT32.withName("height"),
        UINT32.withName("reserved")
    ).withName("kweb_browser_event");

    public static final GroupLayout BRIDGE_EVENT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("type"),
        UINT32.withName("reserved"),
        UINT64.withName("engine"),
        UINT64.withName("browser"),
        UINT64.withName("request_id"),
        STRING_VIEW.withName("payload")
    ).withName("kweb_bridge_event");

    public static final GroupLayout EXTENSION_RESULT = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT64.withName("operation_handle"),
        UINT32.withName("operation"),
        UINT32.withName("outcome"),
        UINT32.withName("state"),
        UINT32.withName("reserved"),
        UINT64.withName("engine"),
        UINT64.withName("browser"),
        STRING_VIEW.withName("extension_id"),
        STRING_VIEW.withName("version"),
        STRING_VIEW.withName("path"),
        STRING_VIEW.withName("error_code"),
        STRING_VIEW.withName("error_message")
    ).withName("kweb_extension_result");

    public static final GroupLayout EXTENSION_CONFIG = MemoryLayout.structLayout(
        UINT32.withName("struct_size"),
        UINT32.withName("abi_version"),
        UINT32.withName("operation"),
        UINT32.withName("reserved"),
        STRING_VIEW.withName("extension_id"),
        STRING_VIEW.withName("expected_version"),
        STRING_VIEW.withName("extension_path"),
        POINTER.withName("callback"),
        POINTER.withName("user_data")
    ).withName("kweb_extension_config");

    public static final GroupLayout BROWSER_CONFIG = MemoryLayout.structLayout(
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
        STRING_VIEW.withName("profile_path"),
        STRING_VIEW.withName("initial_url"),
        POINTER.withName("callback"),
        POINTER.withName("user_data"),
        STRING_VIEW.withName("bridge_origin"),
        POINTER.withName("bridge_callback"),
        POINTER.withName("bridge_user_data")
    ).withName("kweb_browser_config");

    static {
        requireSupportedDesktopAbi();
    }

    public static long offset(GroupLayout layout, String field) {
        return layout.byteOffset(MemoryLayout.PathElement.groupElement(field));
    }

    private static ValueLayout.OfLong nativeSizeT() {
        MemoryLayout layout = Linker.nativeLinker().canonicalLayouts().get("size_t");
        if (!(layout instanceof ValueLayout.OfLong sizeT)) {
            throw new ExceptionInInitializerError("KWebShell requires a 64-bit native size_t layout.");
        }
        return sizeT;
    }

    private static void requireSupportedDesktopAbi() {
        if (POINTER.byteSize() != Long.BYTES || SIZE_T.byteSize() != Long.BYTES) {
            throw new ExceptionInInitializerError("KWebShell supports only 64-bit desktop ABIs.");
        }
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean supportedOs = operatingSystem.startsWith("mac")
            || operatingSystem.startsWith("windows")
            || operatingSystem.startsWith("linux");
        boolean supportedArchitecture = architecture.equals("x86_64")
            || architecture.equals("amd64")
            || architecture.equals("aarch64")
            || architecture.equals("arm64");
        if (!supportedOs || !supportedArchitecture) {
            throw new ExceptionInInitializerError(
                "Unsupported KWebShell desktop ABI: " + operatingSystem + "/" + architecture
            );
        }
    }

    private FfmLayouts() {
    }
}
