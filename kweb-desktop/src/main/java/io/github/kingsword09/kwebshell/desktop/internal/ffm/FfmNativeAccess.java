package io.github.kingsword09.kwebshell.desktop.internal.ffm;

public final class FfmNativeAccess {
    public static void requireEnabled() {
        Module module = FfmNativeAccess.class.getModule();
        if (!module.isNativeAccessEnabled()) {
            throw new FfmNativeAccessException(module.getName());
        }
    }

    public static String grantTarget() {
        String moduleName = FfmNativeAccess.class.getModule().getName();
        return moduleName == null ? "ALL-UNNAMED" : moduleName;
    }

    private FfmNativeAccess() {
    }
}
