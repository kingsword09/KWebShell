package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import java.nio.file.Path

public object KWebDesktop {
    public fun openEngine(configuration: KWebDesktopEngineConfiguration): KWebDesktopEngine =
        KWebDesktopEngine.open(configuration)

    public fun composeWindowHost(
        window: ComposeWindow,
        bridgeOrigin: String? = null,
        bridgeDispatcher: KWebBridgeDispatcher? = null,
    ): KWebComposeWindowHost = KWebComposeWindowHost(window, bridgeOrigin, bridgeDispatcher)
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
    public val bridgeOrigin: String? = null,
    public val bridgeDispatcher: KWebBridgeDispatcher? = null,
) : io.github.kingsword09.kwebshell.core.KWebPageHost
