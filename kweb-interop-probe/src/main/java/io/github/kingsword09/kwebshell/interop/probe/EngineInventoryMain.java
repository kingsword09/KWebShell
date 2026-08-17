package io.github.kingsword09.kwebshell.interop.probe;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.Linker;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

public final class EngineInventoryMain {
    private static final int INVALID_HANDLE = 6;
    private static final int EXTENSION_OPERATION_NOT_FOUND = 51;

    public static void main(String[] arguments) {
        if (arguments.length != 2) {
            throw new IllegalArgumentException(
                "Expected <absolute-cef-runtime-library> <absolute-engine-library>."
            );
        }
        Path cefRuntime = ExactLibrary.argument(arguments[0], "CEF runtime library");
        Path engineLibrary = ExactLibrary.argument(arguments[1], "engine library");
        Linker linker = Linker.nativeLinker();
        try (Arena arena = Arena.ofShared()) {
            SymbolLookup cefLookup = ExactLibrary.open(cefRuntime, arena);
            require(cefLookup.find("cef_initialize").isPresent(), "CEF runtime is missing cef_initialize.");
            SymbolLookup engine = ExactLibrary.open(engineLibrary, arena);
            EngineAbi.verifyAllBindings(linker, engine);

            require(invokeInt(EngineAbi.downcall(linker, engine, "kweb_engine_abi_version")) ==
                EngineAbi.ABI_VERSION, "Engine ABI version mismatch.");
            require(statusName(linker, engine, 0).equals("ok"), "Status 0 name mismatch.");
            require(statusName(linker, engine, INVALID_HANDLE).equals("invalid-handle"),
                "Invalid-handle status name mismatch.");
            require(statusName(linker, engine, EXTENSION_OPERATION_NOT_FOUND)
                    .equals("extension-operation-not-found"),
                "Missing extension-operation status name mismatch.");
            require(invokeLong(EngineAbi.downcall(linker, engine, "kweb_live_engine_count")) == 0,
                "Fresh engine library reports live engines.");
            require(invokeLong(EngineAbi.downcall(linker, engine, "kweb_live_browser_count")) == 0,
                "Fresh engine library reports live browsers.");
            require(invokeLong(EngineAbi.downcall(
                linker,
                engine,
                "kweb_live_extension_operation_count"
            )) == 0, "Fresh engine library reports live extension operations.");
            require(invokeStatus(
                EngineAbi.downcall(linker, engine, "kweb_engine_close"),
                0
            ) == INVALID_HANDLE, "Engine close accepted an invalid handle.");
            require(invokeStatus(
                EngineAbi.downcall(linker, engine, "kweb_browser_close"),
                0
            ) == INVALID_HANDLE, "Browser close accepted an invalid handle.");
            require(invokeStatus(
                EngineAbi.downcall(linker, engine, "kweb_extension_cancel"),
                0
            ) == EXTENSION_OPERATION_NOT_FOUND,
                "Extension cancel did not report a missing operation.");
        }
        System.out.println(
            "JDK 25 FFM resolved and bound all 18 frozen engine ABI symbols and exercised " +
                "safe pre-initialization calls."
        );
    }

    private static String statusName(Linker linker, SymbolLookup lookup, int status) {
        MethodHandle handle = EngineAbi.downcall(linker, lookup, "kweb_status_name");
        try {
            MemorySegment pointer = (MemorySegment) handle.invokeExact(status);
            return pointer.reinterpret(64).getString(0);
        } catch (Throwable error) {
            throw new IllegalStateException("Unable to read an engine status name.", error);
        }
    }

    private static int invokeInt(MethodHandle handle) {
        try {
            return (int) handle.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("Engine integer downcall failed.", error);
        }
    }

    private static long invokeLong(MethodHandle handle) {
        try {
            return (long) handle.invokeExact();
        } catch (Throwable error) {
            throw new IllegalStateException("Engine long downcall failed.", error);
        }
    }

    private static int invokeStatus(MethodHandle handle, long value) {
        try {
            return (int) handle.invokeExact(value);
        } catch (Throwable error) {
            throw new IllegalStateException("Engine status downcall failed.", error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private EngineInventoryMain() {
    }
}
