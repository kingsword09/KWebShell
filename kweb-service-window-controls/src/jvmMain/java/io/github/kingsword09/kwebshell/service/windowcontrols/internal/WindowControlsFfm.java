package io.github.kingsword09.kwebshell.service.windowcontrols.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class WindowControlsFfm {
    public static final int ABI_VERSION = 1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_INVALID_ARGUMENT = 1;
    public static final int STATUS_ABI_MISMATCH = 2;
    public static final int STATUS_NATIVE_UNAVAILABLE = 3;
    public static final int STATUS_STALE_HANDLE = 4;
    public static final int STATUS_UNSUPPORTED = 5;
    public static final int STATUS_NATIVE_FAILED = 6;
    public static final int STATUS_WRONG_THREAD = 7;
    public static final int STATUS_STATE_UNOBSERVED = 8;

    private static final ValueLayout.OfInt UINT32 = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong UINT64 = ValueLayout.JAVA_LONG;
    private static final AddressLayout POINTER = ValueLayout.ADDRESS;

    private final Path libraryPath;
    private final Arena arena;
    private final MethodHandle probe;
    private final MethodHandle attach;
    private final MethodHandle detach;
    private final MethodHandle setModalEnabled;
    private final MethodHandle setAlwaysOnTop;
    private final MethodHandle requestAttention;
    private final MethodHandle verifyParent;
    private final MethodHandle statusName;
    private final String providerId;

    private WindowControlsFfm(
        Path libraryPath,
        Arena arena,
        MethodHandle probe,
        MethodHandle attach,
        MethodHandle detach,
        MethodHandle setModalEnabled,
        MethodHandle setAlwaysOnTop,
        MethodHandle requestAttention,
        MethodHandle verifyParent,
        MethodHandle statusName,
        String providerId
    ) {
        this.libraryPath = libraryPath;
        this.arena = arena;
        this.probe = probe;
        this.attach = attach;
        this.detach = detach;
        this.setModalEnabled = setModalEnabled;
        this.setAlwaysOnTop = setAlwaysOnTop;
        this.requestAttention = requestAttention;
        this.verifyParent = verifyParent;
        this.statusName = statusName;
        this.providerId = providerId;
    }

    public static WindowControlsFfm open(Path requestedPath) {
        Objects.requireNonNull(requestedPath, "requestedPath");
        if (!requestedPath.isAbsolute() || !requestedPath.normalize().equals(requestedPath) ||
            !Files.isRegularFile(requestedPath)) {
            throw new IllegalArgumentException(
                "The KWebWindowControls native library must be an absolute regular file.");
        }
        final Path libraryPath;
        try {
            libraryPath = requestedPath.toRealPath();
        } catch (Exception error) {
            throw new IllegalArgumentException(
                "The KWebWindowControls native library could not be canonicalized.", error);
        }
        final Arena arena = Arena.ofShared();
        try {
            final SymbolLookup lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            final MethodHandle abi = downcall(lookup, "kweb_window_controls_abi_version",
                FunctionDescriptor.of(UINT32));
            final int version = (int) abi.invokeExact();
            if (version != ABI_VERSION) {
                throw new IllegalStateException(
                    "KWebWindowControls native ABI version mismatch: " + version);
            }
            final MethodHandle statusName = downcall(lookup,
                "kweb_window_controls_status_name",
                FunctionDescriptor.of(POINTER, UINT32));
            final MethodHandle provider = downcall(lookup,
                "kweb_window_controls_provider_id",
                FunctionDescriptor.of(POINTER));
            return new WindowControlsFfm(
                libraryPath,
                arena,
                downcall(lookup, "kweb_window_controls_probe",
                    FunctionDescriptor.of(UINT32, UINT64)),
                downcall(lookup, "kweb_window_controls_attach",
                    FunctionDescriptor.of(UINT32, UINT64, UINT64, UINT32)),
                downcall(lookup, "kweb_window_controls_detach",
                    FunctionDescriptor.of(UINT32, UINT64, UINT64, UINT32)),
                downcall(lookup, "kweb_window_controls_set_modal_enabled",
                    FunctionDescriptor.of(UINT32, UINT64, UINT32)),
                downcall(lookup, "kweb_window_controls_set_always_on_top",
                    FunctionDescriptor.of(UINT32, UINT64, UINT32)),
                downcall(lookup, "kweb_window_controls_request_attention",
                    FunctionDescriptor.of(UINT32, UINT64)),
                downcall(lookup, "kweb_window_controls_verify_parent",
                    FunctionDescriptor.of(UINT32, UINT64, UINT64)),
                statusName,
                readCString((MemorySegment) provider.invokeExact())
            );
        } catch (Throwable error) {
            arena.close();
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                "The KWebWindowControls native ABI could not be bound.", error);
        }
    }

    public Path libraryPath() {
        return libraryPath;
    }

    public String providerId() {
        return providerId;
    }

    public int probe(long handle) {
        return invokeProbe(handle);
    }

    public int attach(long child, long parent, int modality) {
        try {
            return (int) attach.invokeExact(child, parent, modality);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-attach-failed", error);
        }
    }

    public int detach(long child, long parent, int modality) {
        try {
            return (int) detach.invokeExact(child, parent, modality);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-detach-failed", error);
        }
    }

    public int setModalEnabled(long handle, boolean enabled) {
        try {
            return (int) setModalEnabled.invokeExact(handle, enabled ? 1 : 0);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-modal-failed", error);
        }
    }

    public int setAlwaysOnTop(long handle, boolean enabled) {
        try {
            return (int) setAlwaysOnTop.invokeExact(handle, enabled ? 1 : 0);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-always-on-top-failed", error);
        }
    }

    public int requestAttention(long handle) {
        try {
            return (int) requestAttention.invokeExact(handle);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-attention-failed", error);
        }
    }

    public int verifyParent(long child, long parent) {
        try {
            return (int) verifyParent.invokeExact(child, parent);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-parent-verification-failed", error);
        }
    }

    public String statusName(int status) {
        try {
            return readCString((MemorySegment) statusName.invokeExact(status));
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-status-name-failed", error);
        }
    }

    public void close() {
        arena.close();
    }

    private int invokeProbe(long handle) {
        try {
            return (int) probe.invokeExact(handle);
        } catch (Throwable error) {
            throw new NativeCallException(STATUS_NATIVE_FAILED, "native-probe-failed", error);
        }
    }

    private static MethodHandle downcall(SymbolLookup lookup, String symbol,
                                         FunctionDescriptor descriptor) {
        final MemorySegment address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException(
                "Missing KWebWindowControls ABI symbol: " + symbol));
        return java.lang.foreign.Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    @SuppressWarnings("restricted")
    private static String readCString(MemorySegment pointer) {
        if (pointer == null || pointer.address() == 0) {
            throw new IllegalStateException("The native ABI returned a null string pointer.");
        }
        final byte[] bytes = pointer.reinterpret(512).toArray(ValueLayout.JAVA_BYTE);
        int length = 0;
        while (length < bytes.length && bytes[length] != 0) {
            length++;
        }
        if (length == bytes.length) {
            throw new IllegalStateException("The native ABI returned an unterminated string.");
        }
        return new String(bytes, 0, length, StandardCharsets.UTF_8);
    }

    public static final class NativeCallException extends RuntimeException {
        private final int status;

        public NativeCallException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
