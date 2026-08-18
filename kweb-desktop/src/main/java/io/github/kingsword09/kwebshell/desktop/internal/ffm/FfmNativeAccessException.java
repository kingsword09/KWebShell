package io.github.kingsword09.kwebshell.desktop.internal.ffm;

public final class FfmNativeAccessException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    private final String grantTarget;

    FfmNativeAccessException(String moduleName) {
        super(
            "KWebShell FFM requires --enable-native-access="
                + (moduleName == null ? "ALL-UNNAMED" : moduleName)
        );
        this.grantTarget = moduleName == null ? "ALL-UNNAMED" : moduleName;
    }

    public String grantTarget() {
        return grantTarget;
    }
}
