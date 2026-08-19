package io.github.kingsword09.kwebshell.desktop.internal.ffm;

public final class FfmStatus {
    public static final int OK = 0;
    public static final int INVALID_ARGUMENT = 1;
    public static final int ABI_MISMATCH = 2;
    public static final int ALLOCATION_FAILED = 3;
    public static final int INVALID_HANDLE = 6;
    public static final int INVALID_TEXT_ENCODING = 8;
    public static final int TEXT_TOO_LARGE = 9;
    public static final int CALLBACK_FAILED = 12;
    public static final int INTERNAL_ERROR = 13;
    public static final int ENGINE_LIBRARY_LOAD_FAILED = 14;
    public static final int ENGINE_SYMBOL_MISSING = 15;
    public static final int CEF_RUNTIME_LOAD_FAILED = 16;
    public static final int CEF_RUNTIME_MISMATCH = 17;
    public static final int PATH_NOT_ABSOLUTE = 19;
    public static final int PATH_NOT_FOUND = 20;
    public static final int PATH_TYPE_INVALID = 21;
    public static final int PATH_MISMATCH = 22;
    public static final int EXTENSION_OPERATION_NOT_FOUND = 51;
    public static final int EXTENSION_RESULT_INVALID = 52;

    private FfmStatus() {
    }
}
