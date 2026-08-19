package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebEngine
import java.nio.file.Path

public object KWebDesktop {
    public fun openEngine(configuration: KWebDesktopEngineConfiguration): KWebEngine =
        KWebDesktopEngine.open(configuration)

    public fun composeWindowHost(window: ComposeWindow): KWebComposeWindowHost =
        KWebComposeWindowHost(window)
}

public data class KWebDesktopEngineConfiguration(
    public val cefRuntime: Path,
    public val browserSubprocess: Path,
    public val resources: Path,
    public val locales: Path,
    public val rootCache: Path,
    public val log: Path,
    public val remoteDebuggingPort: Int = 0,
)

public class KWebComposeWindowHost(
    public val window: ComposeWindow,
) : io.github.kingsword09.kwebshell.core.KWebPageHost
