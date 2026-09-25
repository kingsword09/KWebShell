package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public interface KWebPageHost

public enum class KWebPageFrameScope(public val id: String) {
    MAIN("main"),
    SUBFRAME("subframe"),
}

public enum class KWebPageEventType(public val id: String) {
    CREATED("created"),
    NAVIGATION_STARTED("navigation-started"),
    NAVIGATION_COMMITTED("navigation-committed"),
    SAME_DOCUMENT_NAVIGATION("same-document-navigation"),
    ADDRESS_CHANGED("address-changed"),
    LOADING_STATE_CHANGED("loading-state-changed"),
    LOAD_ENDED("load-ended"),
    LOAD_FAILED("load-failed"),
    RESIZED("resized"),
    FATAL_ERROR("fatal-error"),
    TITLE_CHANGED("title-changed"),
    FAVICON_CHANGED("favicon-changed"),
    BEFORE_UNLOAD_REQUESTED("before-unload-requested"),
    POPUP_REQUESTED("popup-requested"),
    RENDERER_UNRESPONSIVE("renderer-unresponsive"),
    RENDERER_RESPONSIVE("renderer-responsive"),
    RENDERER_TERMINATED("renderer-terminated"),
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

public enum class KWebPageEventReason(public val id: String) {
    NONE("none"),
    NAVIGATION_FAILED("navigation-failed"),
    NAVIGATION_ABORTED("navigation-aborted"),
    BEFORE_UNLOAD_TIMEOUT("before-unload-timeout"),
    POPUP_TIMEOUT("popup-timeout"),
    POPUP_POLICY_DENIED("popup-policy-denied"),
    RENDERER_CRASH("renderer-crash"),
    RENDERER_KILLED("renderer-killed"),
    RENDERER_OOM("renderer-oom"),
    RENDERER_UNKNOWN("renderer-unknown"),
    OWNER_CLOSED("owner-closed"),
    NATIVE_FAILURE("native-failure"),
}

public data class KWebPageEvent(
    public val type: KWebPageEventType,
    public val sequence: Long,
    public val statusCode: Int,
    public val bounds: KWebBounds?,
    public val flags: Set<KWebPageEventFlag>,
    public val pageId: String,
    public val profileId: String,
    public val frameId: String,
    public val frameScope: KWebPageFrameScope,
    public val origin: String? = null,
    public val url: String? = null,
    public val title: String? = null,
    public val faviconUrls: List<String> = emptyList(),
    public val reason: KWebPageEventReason,
    public val rendererFailureReason: KWebRendererFailureReason? = null,
    /** Present only for [KWebPageEventType.BEFORE_UNLOAD_REQUESTED]. */
    public val beforeUnloadRequest: KWebBeforeUnloadRequest? = null,
    /** Present only for [KWebPageEventType.POPUP_REQUESTED]. */
    public val popupRequest: KWebPopupRequest? = null,
)

public enum class KWebReloadMode(public val id: String) {
    NORMAL("normal"),
    IGNORE_CACHE("ignore-cache"),
}

public enum class KWebReloadOutcome(public val id: String) {
    STARTED("started"),
    REJECTED("rejected"),
    CANCELLED("cancelled"),
}

public data class KWebReloadResult(
    public val mode: KWebReloadMode,
    public val outcome: KWebReloadOutcome,
    public val reason: KWebPageEventReason = KWebPageEventReason.NONE,
)

public data class KWebBeforeUnloadRequest(
    public val requestId: Long,
    public val pageId: String,
    public val frameId: String,
    public val origin: String?,
    public val message: String?,
)

public enum class KWebBeforeUnloadDecision(public val id: String) {
    PROCEED("proceed"),
    CANCEL("cancel"),
}

public data class KWebBeforeUnloadResult(
    public val requestId: Long,
    public val decision: KWebBeforeUnloadDecision,
    public val timedOut: Boolean,
)

public data class KWebPopupFeatures(
    public val x: Int?,
    public val y: Int?,
    public val width: Int?,
    public val height: Int?,
    /** Chromium's request that this is a popup rather than a normal tab. */
    public val isPopup: Boolean,
)

public data class KWebPopupRequest(
    public val requestId: Long,
    public val pageId: String,
    public val profileId: String,
    public val frameId: String,
    public val origin: String?,
    public val targetUrl: String,
    public val frameName: String,
    public val userGesture: Boolean,
    public val features: KWebPopupFeatures,
)

public sealed interface KWebPopupDecision {
    public data object DENY : KWebPopupDecision

    public data class ALLOW(
        public val owner: KWebPageHost,
        public val bounds: KWebRect,
    ) : KWebPopupDecision
}

public enum class KWebPopupOutcome(public val id: String) {
    DENIED("denied"),
    ALLOWED("allowed"),
    TIMED_OUT("timed-out"),
}

public data class KWebPopupResult(
    public val requestId: Long,
    public val outcome: KWebPopupOutcome,
    public val page: KWebPage? = null,
)

public enum class KWebRendererFailureReason(public val id: String) {
    CRASH("crash"),
    KILLED("killed"),
    OOM("oom"),
    UNKNOWN("unknown"),
}

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
        bounds: KWebRect,
    ): KWebPage

    override fun close()
}

public interface KWebPage : AutoCloseable {
    /** Stable identity for policy binding (gesture tokens, audit subjects). */
    public val id: String

    public val lifecycle: StateFlow<KWebLifecycleState>
    public val events: Flow<KWebPageEvent>
    public val profile: KWebProfile

    public suspend fun navigate(url: String)

    public suspend fun reload(mode: KWebReloadMode = KWebReloadMode.NORMAL): KWebReloadResult

    public suspend fun respondToBeforeUnload(
        requestId: Long,
        decision: KWebBeforeUnloadDecision,
    ): KWebBeforeUnloadResult

    public suspend fun respondToPopup(
        requestId: Long,
        decision: KWebPopupDecision,
    ): KWebPopupResult

    public suspend fun setBounds(bounds: KWebRect)

    public suspend fun setSurfaceState(visible: Boolean, focused: Boolean)

    public suspend fun openDevTools()

    public suspend fun closeDevTools()

    override fun close()
}
