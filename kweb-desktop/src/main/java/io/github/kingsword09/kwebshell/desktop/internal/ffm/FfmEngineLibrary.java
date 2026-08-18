package io.github.kingsword09.kwebshell.desktop.internal.ffm;

import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class FfmEngineLibrary {
    private static final Object LOAD_LOCK = new Object();

    private static volatile FfmEngineLibrary loaded;
    private static volatile Throwable lastLoadFailure;

    private final Path enginePath;
    private final Path runtimePath;
    private final Arena arena;
    private final Linker linker;
    private final SymbolLookup engineLookup;
    private final SymbolLookup runtimeLookup;
    private final Map<String, MethodHandle> handles;

    private FfmEngineLibrary(
        Path enginePath,
        Path runtimePath,
        Arena arena,
        Linker linker,
        SymbolLookup engineLookup,
        SymbolLookup runtimeLookup,
        Map<String, MethodHandle> handles
    ) {
        this.enginePath = enginePath;
        this.runtimePath = runtimePath;
        this.arena = arena;
        this.linker = linker;
        this.engineLookup = engineLookup;
        this.runtimeLookup = runtimeLookup;
        this.handles = handles;
    }

    public static int load(String engineValue, String runtimeValue) {
        FfmNativeAccess.requireEnabled();
        lastLoadFailure = null;
        final Path enginePath;
        final Path runtimePath;
        try {
            enginePath = exactLibraryPath(engineValue);
            runtimePath = exactLibraryPath(runtimeValue);
        } catch (FfmLoadException error) {
            lastLoadFailure = error.getCause();
            return error.status();
        }

        synchronized (LOAD_LOCK) {
            FfmEngineLibrary existing = loaded;
            if (existing != null) {
                if (!existing.enginePath.equals(enginePath)) {
                    return FfmStatus.ENGINE_LIBRARY_LOAD_FAILED;
                }
                return existing.runtimePath.equals(runtimePath)
                    ? FfmStatus.OK
                    : FfmStatus.CEF_RUNTIME_MISMATCH;
            }
            try {
                FfmEngineLibrary candidate = open(enginePath, runtimePath);
                loaded = candidate;
                return FfmStatus.OK;
            } catch (FfmLoadException error) {
                lastLoadFailure = error.getCause();
                return error.status();
            }
        }
    }

    public static Throwable lastLoadFailure() {
        return lastLoadFailure;
    }

    public static FfmEngineLibrary requireLoaded() {
        FfmEngineLibrary result = loaded;
        if (result == null) {
            throw new IllegalStateException("The KWebShell FFM engine library is not loaded.");
        }
        return result;
    }

    public MethodHandle handle(String symbol) {
        MethodHandle handle = handles.get(symbol);
        if (handle == null) {
            throw new IllegalArgumentException("Unknown bound KWebShell ABI symbol: " + symbol);
        }
        return handle;
    }

    public Linker linker() {
        return linker;
    }

    public Path enginePath() {
        return enginePath;
    }

    public Path runtimePath() {
        return runtimePath;
    }

    @SuppressWarnings("restricted")
    private static FfmEngineLibrary open(Path enginePath, Path runtimePath) {
        Arena candidateArena = Arena.ofShared();
        try {
            Linker linker = Linker.nativeLinker();
            final SymbolLookup runtimeLookup;
            final SymbolLookup engineLookup;
            if (isWindows()) {
                final FfmWindowsLibraryBootstrap bootstrap;
                try {
                    bootstrap = FfmWindowsLibraryBootstrap.open(linker);
                } catch (Throwable error) {
                    throw new FfmLoadException(FfmStatus.CEF_RUNTIME_LOAD_FAILED, error);
                }
                try (bootstrap) {
                    try {
                        bootstrap.preload(runtimePath);
                    } catch (Throwable error) {
                        throw new FfmLoadException(FfmStatus.CEF_RUNTIME_LOAD_FAILED, error);
                    }
                    runtimeLookup = libraryLookup(
                        runtimePath,
                        candidateArena,
                        FfmStatus.CEF_RUNTIME_LOAD_FAILED
                    );
                    requireCefRuntime(runtimeLookup);
                    try {
                        bootstrap.preload(enginePath);
                    } catch (Throwable error) {
                        throw new FfmLoadException(FfmStatus.ENGINE_LIBRARY_LOAD_FAILED, error);
                    }
                    engineLookup = libraryLookup(
                        enginePath,
                        candidateArena,
                        FfmStatus.ENGINE_LIBRARY_LOAD_FAILED
                    );
                }
            } else {
                runtimeLookup = isMacOs()
                    ? null
                    : libraryLookup(
                        runtimePath,
                        candidateArena,
                        FfmStatus.CEF_RUNTIME_LOAD_FAILED
                    );
                if (runtimeLookup != null) {
                    requireCefRuntime(runtimeLookup);
                }
                engineLookup = libraryLookup(
                    enginePath,
                    candidateArena,
                    FfmStatus.ENGINE_LIBRARY_LOAD_FAILED
                );
            }
            Map<String, MethodHandle> handles = bindAll(linker, engineLookup);
            int version = invokeInt(handles.get("kweb_engine_abi_version"));
            if (version != FfmAbi.VERSION) {
                throw new FfmLoadException(FfmStatus.ABI_MISMATCH);
            }
            int startup = invokePlatformStartup(
                handles.get("kweb_engine_platform_startup"),
                runtimePath,
                candidateArena
            );
            if (startup != FfmStatus.OK) {
                throw new FfmLoadException(startup);
            }
            return new FfmEngineLibrary(
                enginePath,
                runtimePath,
                candidateArena,
                linker,
                engineLookup,
                runtimeLookup,
                Map.copyOf(handles)
            );
        } catch (FfmLoadException error) {
            candidateArena.close();
            throw error;
        } catch (Throwable error) {
            candidateArena.close();
            throw new FfmLoadException(FfmStatus.INTERNAL_ERROR, error);
        }
    }

    @SuppressWarnings("restricted")
    private static SymbolLookup libraryLookup(Path path, Arena arena, int failureStatus) {
        try {
            return SymbolLookup.libraryLookup(path, arena);
        } catch (Throwable error) {
            throw new FfmLoadException(failureStatus, error);
        }
    }

    private static void requireCefRuntime(SymbolLookup lookup) {
        if (lookup.find("cef_initialize").isEmpty()) {
            throw new FfmLoadException(FfmStatus.CEF_RUNTIME_MISMATCH);
        }
    }

    @SuppressWarnings("restricted")
    private static Map<String, MethodHandle> bindAll(Linker linker, SymbolLookup lookup) {
        Map<String, MethodHandle> result = new LinkedHashMap<>();
        for (FfmAbi.FunctionSpec function : FfmAbi.FUNCTIONS) {
            MemorySegment symbol = lookup.find(function.name())
                .orElseThrow(() -> new FfmLoadException(FfmStatus.ENGINE_SYMBOL_MISSING));
            result.put(function.name(), linker.downcallHandle(symbol, function.descriptor()));
        }
        return result;
    }

    private static int invokePlatformStartup(MethodHandle handle, Path runtimePath, Arena arena) {
        try {
            FfmMemory.EncodedUtf8 runtime = FfmMemory.encode(
                runtimePath.toString(),
                arena,
                FfmMemory.MAXIMUM_PATH_SIZE
            );
            return (int) handle.invokeExact(runtime.segment(), runtime.size());
        } catch (FfmTextException error) {
            return error.status();
        } catch (Throwable error) {
            throw new FfmLoadException(FfmStatus.INTERNAL_ERROR, error);
        }
    }

    private static int invokeInt(MethodHandle handle) {
        try {
            return (int) handle.invokeExact();
        } catch (Throwable error) {
            throw new FfmLoadException(FfmStatus.INTERNAL_ERROR, error);
        }
    }

    private static Path exactLibraryPath(String value) {
        final Path path;
        try {
            path = Path.of(value);
        } catch (InvalidPathException | NullPointerException error) {
            throw new FfmLoadException(FfmStatus.PATH_TYPE_INVALID, error);
        }
        if (!path.isAbsolute()) {
            throw new FfmLoadException(FfmStatus.PATH_NOT_ABSOLUTE);
        }
        Path normalized = path.normalize();
        if (!Files.isRegularFile(normalized)) {
            throw new FfmLoadException(FfmStatus.PATH_NOT_FOUND);
        }
        try {
            return normalized.toRealPath();
        } catch (Exception error) {
            throw new FfmLoadException(FfmStatus.PATH_NOT_FOUND, error);
        }
    }

    private static boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }

    private static final class FfmLoadException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final int status;

        FfmLoadException(int status) {
            this(status, null);
        }

        FfmLoadException(int status, Throwable cause) {
            super("Unable to load the KWebShell FFM engine with status " + status + '.', cause);
            this.status = status;
        }

        int status() {
            return status;
        }
    }
}
