package io.github.kingsword09.kwebshell.core

public enum class KWebNetworkRuleAction {
    ALLOW,
    BLOCK,
    REDIRECT,
}

public enum class KWebNetworkRequestPhase {
    BEFORE_REQUEST,
    COMPLETE,
}

public enum class KWebNetworkCompletionStatus {
    UNKNOWN,
    SUCCESS,
    PENDING,
    CANCELED,
    FAILED,
}

public enum class KWebNetworkResourceType {
    MAIN_FRAME,
    SUB_FRAME,
    STYLESHEET,
    SCRIPT,
    IMAGE,
    FONT,
    OBJECT,
    MEDIA,
    WORKER,
    XHR,
    PING,
    CSP_REPORT,
    OTHER,
}

public data class KWebNetworkHeaderMutation(
    public val name: String,
    public val value: String?,
)

public data class KWebNetworkRule(
    public val id: String,
    public val urlPattern: String,
    public val resourceTypes: Set<KWebNetworkResourceType> = emptySet(),
    public val methods: Set<String> = emptySet(),
    public val priority: Int = 0,
    public val action: KWebNetworkRuleAction = KWebNetworkRuleAction.ALLOW,
    public val redirectUrl: String? = null,
    public val headerMutations: List<KWebNetworkHeaderMutation> = emptyList(),
)

public enum class KWebProxyMode {
    DIRECT,
    FIXED,
    PAC,
}

public data class KWebProxyConfiguration(
    public val mode: KWebProxyMode = KWebProxyMode.DIRECT,
    public val rules: String = "",
    public val pacUrl: String? = null,
    public val pacMandatory: Boolean = false,
    public val bypassList: List<String> = emptyList(),
)

public data class KWebNetworkPolicy(
    public val version: Int = 1,
    public val rules: List<KWebNetworkRule> = emptyList(),
    public val proxy: KWebProxyConfiguration = KWebProxyConfiguration(),
    public val userAgent: String? = null,
    public val acceptLanguage: String? = null,
)

public data class KWebNetworkRequestEvent(
    public val requestId: Long,
    public val phase: KWebNetworkRequestPhase,
    public val url: String,
    public val method: String,
    public val resourceType: KWebNetworkResourceType,
    public val action: KWebNetworkRuleAction,
    public val statusCode: Int? = null,
    public val errorId: String? = null,
    public val completionStatus: KWebNetworkCompletionStatus? = null,
    public val redirectedUrl: String? = null,
    public val policyVersion: Int,
)

public data class KWebProxyResolution(
    public val url: String,
    public val result: String,
)
