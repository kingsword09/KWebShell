package io.github.kingsword09.kwebshell.core

public enum class KWebCookieSameSite {
    UNSPECIFIED,
    NONE,
    LAX,
    STRICT,
}

public enum class KWebCookiePriority {
    LOW,
    MEDIUM,
    HIGH,
}

public enum class KWebCookieSourceScheme {
    HTTP,
    HTTPS,
    UNKNOWN,
}

public data class KWebCookiePartitionKey(
    public val topLevelSite: String?,
    public val hasCrossSiteAncestor: Boolean,
    public val opaque: Boolean = false,
)

public data class KWebProfileTimeRange(
    public val sinceEpochMillis: Long? = null,
    public val untilEpochMillis: Long? = null,
)

public data class KWebCookieFilter(
    public val origin: String? = null,
    public val name: String? = null,
    public val domain: String? = null,
    public val path: String? = null,
    public val partitionKey: KWebCookiePartitionKey? = null,
    public val includeHttpOnly: Boolean = true,
    public val timeRange: KWebProfileTimeRange = KWebProfileTimeRange(),
)

public data class KWebCookieSpec(
    public val url: String,
    public val name: String,
    public val value: String,
    public val domain: String? = null,
    public val path: String = "/",
    public val secure: Boolean = false,
    public val httpOnly: Boolean = false,
    public val sameSite: KWebCookieSameSite = KWebCookieSameSite.UNSPECIFIED,
    public val priority: KWebCookiePriority = KWebCookiePriority.MEDIUM,
    public val expiresEpochMillis: Long? = null,
    public val sourceScheme: KWebCookieSourceScheme? = null,
    public val sourcePort: Int? = null,
    public val partitionKey: KWebCookiePartitionKey? = null,
)

public data class KWebCookie(
    public val name: String,
    public val value: String,
    public val domain: String,
    public val path: String,
    public val secure: Boolean,
    public val httpOnly: Boolean,
    public val sameSite: KWebCookieSameSite,
    public val priority: KWebCookiePriority,
    public val creationEpochMillis: Long?,
    public val lastAccessEpochMillis: Long?,
    public val expiresEpochMillis: Long?,
    public val sourceScheme: KWebCookieSourceScheme?,
    public val sourcePort: Int?,
    public val partitionKey: KWebCookiePartitionKey?,
)

public data class KWebCookieMutationResult(
    public val affected: Int,
)

public enum class KWebProfileDataKind {
    COOKIES,
    HTTP_CACHE,
    LOCAL_STORAGE,
    INDEXED_DB,
    CACHE_STORAGE,
    SERVICE_WORKERS,
    WEB_SQL,
    FILE_SYSTEMS,
    SHARED_STORAGE,
}

public data class KWebProfileDataFilter(
    public val kinds: Set<KWebProfileDataKind>,
    public val origin: String? = null,
    public val timeRange: KWebProfileTimeRange = KWebProfileTimeRange(),
)

public data class KWebProfileDataClearResult(
    public val requestedKinds: Set<KWebProfileDataKind>,
    public val clearedKinds: Set<KWebProfileDataKind>,
    public val origin: String?,
    public val removedCookies: Int,
    public val startedEpochMillis: Long,
    public val completedEpochMillis: Long,
)

public data class KWebStorageUsageEntry(
    public val storageType: String,
    public val bytes: Long,
)

public data class KWebStorageUsage(
    public val origin: String,
    public val usageBytes: Long,
    public val quotaBytes: Long,
    public val breakdown: List<KWebStorageUsageEntry>,
)

public data class KWebSpellcheckConfiguration(
    public val enabled: Boolean,
    public val languages: List<String>,
)

public data class KWebSpellcheckState(
    public val enabled: Boolean,
    public val languages: List<String>,
)

public data class KWebProfileFlushResult(
    public val completedEpochMillis: Long,
)
