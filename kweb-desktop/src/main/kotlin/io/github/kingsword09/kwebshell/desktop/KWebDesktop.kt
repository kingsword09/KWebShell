package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureIssuer
import java.nio.file.Path

public object KWebDesktop {
    public fun openEngine(configuration: KWebDesktopEngineConfiguration): KWebDesktopEngine =
        KWebDesktopEngine.open(configuration)

    public fun composeWindowHost(
        window: ComposeWindow,
        bridgeOrigin: String? = null,
        bridgeDispatcher: KWebBridgeDispatcher? = null,
    ): KWebComposeWindowHost = KWebComposeWindowHost(window, bridgeOrigin, bridgeDispatcher)

    public fun composeWindowHost(
        window: ComposeWindow,
        bridgeOrigin: String?,
        dispatcherFactory: KWebPageDispatcherFactory,
    ): KWebComposeWindowHost = KWebComposeWindowHost(window, bridgeOrigin, dispatcherFactory)
}

public data class KWebDesktopEngineConfiguration(
    public val cefRuntime: Path,
    public val browserSubprocess: Path,
    public val resources: Path,
    public val locales: Path,
    public val rootCache: Path,
    public val log: Path,
    public val remoteDebuggingPort: Int = 0,
    public val userGestureIssuer: KWebUserGestureIssuer? = null,
    public val engineId: String = "engine-" + java.util.UUID.randomUUID(),
)

/** Creates the page dispatcher once the generated page identity is known. */
public fun interface KWebPageDispatcherFactory {
    public fun create(pageId: String): KWebBridgeDispatcher
}

public class KWebComposeWindowHost internal constructor(
    public val window: ComposeWindow,
    public val bridgeOrigin: String? = null,
    internal val bridgeDispatcherForPage: KWebPageDispatcherFactory?,
) : io.github.kingsword09.kwebshell.core.KWebPageHost {
    public constructor(
        window: ComposeWindow,
        bridgeOrigin: String?,
        bridgeDispatcher: KWebBridgeDispatcher?,
    ) : this(window, bridgeOrigin, bridgeDispatcher?.let { dispatcher -> KWebPageDispatcherFactory { dispatcher } })
}
