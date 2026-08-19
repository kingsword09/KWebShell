package io.github.kingsword09.kwebshell.core

public enum class KWebCapability(public val id: String) {
    NATIVE_CHILD("native-child"),
    PERSISTENT_PROFILE("persistent-profile"),
    NAVIGATION("navigation"),
    RESIZE("resize"),
    DEVTOOLS("devtools"),
    CDP("cdp"),
}
