package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface KWebPageHost

public enum class KWebPageEventType(public val id: String) {
    CREATED("created"),
    NAVIGATION_STARTED("navigation-started"),
    ADDRESS_CHANGED("address-changed"),
    LOADING_STATE_CHANGED("loading-state-changed"),
    LOAD_ENDED("load-ended"),
    LOAD_FAILED("load-failed"),
    RESIZED("resized"),
    FATAL_ERROR("fatal-error"),
    TITLE_CHANGED("title-changed"),
    CLOSED("closed"),
    DEVTOOLS_OPENED("devtools-opened"),
    DEVTOOLS_CLOSED("devtools-closed"),
    DEVTOOLS_FAILED("devtools-failed"),
}

public enum class KWebPageEventFlag(public val id: String) {
    LOADING("loading"),
    CAN_GO_BACK("can-go-back"),
    CAN_GO_FORWARD("can-go-forward"),
    USER_GESTURE("user-gesture"),
    REDIRECT("redirect"),
}

public data class KWebPageEvent(
    public val type: KWebPageEventType,
    public val sequence: Long,
    public val text: String,
    public val statusCode: Int,
    public val bounds: KWebBounds?,
    public val flags: Set<KWebPageEventFlag>,
)

public interface KWebEngine : AutoCloseable {
    public val lifecycle: StateFlow<KWebLifecycleState>
    public val capabilities: Set<KWebCapability>

    public suspend fun openProfile(name: String): KWebProfile

    override fun close()
}

public interface KWebProfile : AutoCloseable {
    public val name: String
    public val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun openPage(
        host: KWebPageHost,
        initialUrl: String,
        bounds: KWebBounds,
    ): KWebPage

    override fun close()
}

public interface KWebPage : AutoCloseable {
    public val lifecycle: StateFlow<KWebLifecycleState>
    public val events: Flow<KWebPageEvent>
    public val profile: KWebProfile

    public suspend fun navigate(url: String)

    public suspend fun resize(bounds: KWebBounds)

    public suspend fun openDevTools()

    public suspend fun closeDevTools()

    override fun close()
}
