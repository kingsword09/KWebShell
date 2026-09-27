package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebCookie
import io.github.kingsword09.kwebshell.core.KWebCookieFilter
import io.github.kingsword09.kwebshell.core.KWebCookiePriority
import io.github.kingsword09.kwebshell.core.KWebCookiePartitionKey
import io.github.kingsword09.kwebshell.core.KWebCookieSameSite
import io.github.kingsword09.kwebshell.core.KWebCookieSourceScheme
import io.github.kingsword09.kwebshell.core.KWebCookieSpec
import io.github.kingsword09.kwebshell.core.KWebProfileDataFilter
import io.github.kingsword09.kwebshell.core.KWebProfileDataKind
import io.github.kingsword09.kwebshell.core.KWebSpellcheckConfiguration
import io.github.kingsword09.kwebshell.core.KWebSpellcheckState
import io.github.kingsword09.kwebshell.core.KWebStorageUsage
import io.github.kingsword09.kwebshell.core.KWebStorageUsageEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URI

internal object KWebProfileDataOperation {
    const val GET_COOKIES: Int = 1
    const val SET_COOKIE: Int = 2
    const val DELETE_COOKIE: Int = 3
    const val CLEAR_ORIGIN: Int = 4
    const val CLEAR_HTTP_CACHE: Int = 5
    const val STORAGE_USAGE: Int = 6
    const val SET_SPELLCHECK: Int = 7
    const val FLUSH: Int = 8
}

internal object KWebDesktopProfileDataJson {
    private const val MAX_COOKIE_COUNT = 4096
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun cookieSetPayload(cookie: KWebCookieSpec): String = buildJsonObject {
        put("url", cookie.url)
        put("name", cookie.name)
        put("value", cookie.value)
        cookie.domain?.let { put("domain", it) }
        put("path", cookie.path)
        put("secure", cookie.secure)
        put("httpOnly", cookie.httpOnly)
        cookie.sameSite.cdpName()?.let { put("sameSite", it) }
        put("priority", cookie.priority.cdpName())
        cookie.expiresEpochMillis?.let { put("expires", it / 1000.0) }
        cookie.sourceScheme?.cdpName()?.let { put("sourceScheme", it) }
        cookie.sourcePort?.let { put("sourcePort", it) }
        cookie.partitionKey?.let { key ->
            putJsonObject("partitionKey") {
                put("topLevelSite", key.topLevelSite)
                put("hasCrossSiteAncestor", key.hasCrossSiteAncestor)
            }
        }
    }.toString()

    fun cookieDeletePayload(cookie: KWebCookie): String = buildJsonObject {
        put("name", cookie.name)
        put("domain", cookie.domain)
        put("path", cookie.path)
        cookie.partitionKey?.let { key ->
            putJsonObject("partitionKey") {
                put("topLevelSite", key.topLevelSite)
                put("hasCrossSiteAncestor", key.hasCrossSiteAncestor)
            }
        }
    }.toString()

    fun clearOriginPayload(origin: String, storageTypes: String): String =
        buildJsonObject {
            put("origin", origin)
            put("storageTypes", storageTypes)
        }.toString()

    fun usagePayload(origin: String): String = buildJsonObject {
        put("origin", origin)
    }.toString()

    fun spellcheckPayload(configuration: KWebSpellcheckConfiguration): String =
        buildJsonObject {
            put("enabled", configuration.enabled)
            putJsonArray("languages") {
                configuration.languages.forEach { add(JsonPrimitive(it)) }
            }
        }.toString()

    fun parseCookies(payload: String): List<KWebCookie> {
        val root = json.parseToJsonElement(payload).jsonObject
        val values = root["cookies"]?.jsonArray ?: JsonArray(emptyList())
        require(values.size <= MAX_COOKIE_COUNT) {
            "Chromium returned more than $MAX_COOKIE_COUNT cookies."
        }
        return values.map { parseCookie(it.jsonObject) }
    }

    fun parseSetCookieSuccess(payload: String): Boolean =
        json.parseToJsonElement(payload).jsonObject["success"]?.jsonPrimitive?.content == "true"

    fun parseStorageUsage(payload: String, requestedOrigin: String): KWebStorageUsage {
        val root = json.parseToJsonElement(payload).jsonObject
        root["origin"]?.jsonPrimitive?.content?.let { reportedOrigin ->
            require(reportedOrigin == requestedOrigin) {
                "Chromium returned a storage origin different from the requested origin."
            }
        }
        val usage = root["usage"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Chromium omitted storage usage.")
        val quota = root["quota"]?.jsonPrimitive?.longOrNull
            ?: throw IllegalArgumentException("Chromium omitted storage quota.")
        require(usage >= 0 && quota >= 0 && quota >= usage) {
            "Chromium returned invalid storage usage or quota values."
        }
        val breakdown = root["usageBreakdown"]?.jsonArray?.map { entry ->
            val objectValue = entry.jsonObject
            val type = objectValue["storageType"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException("Chromium omitted a storage type.")
            val bytes = objectValue["usage"]?.jsonPrimitive?.longOrNull
                ?: throw IllegalArgumentException("Chromium omitted storage bytes.")
            require(bytes >= 0) { "Chromium returned a negative storage byte count." }
            KWebStorageUsageEntry(type, bytes)
        } ?: emptyList()
        return KWebStorageUsage(requestedOrigin, usage, quota, breakdown)
    }

    fun parseSpellcheck(payload: String): KWebSpellcheckState {
        val root = json.parseToJsonElement(payload).jsonObject
        val enabled = root["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: throw IllegalArgumentException("Chromium omitted spellcheck enabled state.")
        val languages = root["languages"]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("Chromium omitted spellcheck languages.")
        return KWebSpellcheckState(enabled, languages)
    }

    fun storageTypes(kinds: Set<KWebProfileDataKind>): String =
        kinds.filter { it != KWebProfileDataKind.COOKIES && it != KWebProfileDataKind.HTTP_CACHE }
            .joinToString(",") { it.cdpStorageType() }

    private fun parseCookie(value: JsonObject): KWebCookie = KWebCookie(
        name = required(value, "name"),
        value = required(value, "value"),
        domain = required(value, "domain"),
        path = required(value, "path"),
        secure = value.boolean("secure"),
        httpOnly = value.boolean("httpOnly"),
        sameSite = value["sameSite"]?.jsonPrimitive?.content?.let(::sameSite) ?: KWebCookieSameSite.UNSPECIFIED,
        priority = value["priority"]?.jsonPrimitive?.content?.let(::priority) ?: KWebCookiePriority.MEDIUM,
        creationEpochMillis = value["creation"]?.jsonPrimitive?.doubleOrNull?.toEpochMillis(),
        lastAccessEpochMillis = value["lastAccess"]?.jsonPrimitive?.doubleOrNull?.toEpochMillis(),
        expiresEpochMillis = value["expires"]?.jsonPrimitive?.doubleOrNull?.toEpochMillis(),
        sourceScheme = value["sourceScheme"]?.jsonPrimitive?.content?.let(::sourceScheme),
        sourcePort = value["sourcePort"]?.jsonPrimitive?.longOrNull?.toInt(),
        partitionKey = value["partitionKey"]?.jsonObject?.let { partition ->
            KWebCookiePartitionKey(
                topLevelSite = partition["topLevelSite"]?.jsonPrimitive?.content,
                hasCrossSiteAncestor = partition["hasCrossSiteAncestor"]?.jsonPrimitive?.booleanOrNull
                    ?: throw IllegalArgumentException("Chromium omitted cookie partition ancestor state."),
                opaque = value["partitionKeyOpaque"]?.jsonPrimitive?.booleanOrNull == true,
            )
        } ?: if (value["partitionKeyOpaque"]?.jsonPrimitive?.booleanOrNull == true) {
            KWebCookiePartitionKey(topLevelSite = null, hasCrossSiteAncestor = false, opaque = true)
        } else {
            null
        },
    )

    private fun required(value: JsonObject, name: String): String =
        value[name]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Chromium omitted cookie field '$name'.")

    private fun JsonObject.boolean(name: String): Boolean =
        this[name]?.jsonPrimitive?.content == "true"

    private fun Double.toEpochMillis(): Long = (this * 1000.0).toLong()

    private fun sameSite(value: String): KWebCookieSameSite = when (value) {
        "None" -> KWebCookieSameSite.NONE
        "Lax" -> KWebCookieSameSite.LAX
        "Strict" -> KWebCookieSameSite.STRICT
        else -> KWebCookieSameSite.UNSPECIFIED
    }

    private fun priority(value: String): KWebCookiePriority = when (value) {
        "Low" -> KWebCookiePriority.LOW
        "High" -> KWebCookiePriority.HIGH
        else -> KWebCookiePriority.MEDIUM
    }

    private fun sourceScheme(value: String): KWebCookieSourceScheme = when (value) {
        "Secure" -> KWebCookieSourceScheme.HTTPS
        "NonSecure" -> KWebCookieSourceScheme.HTTP
        else -> KWebCookieSourceScheme.UNKNOWN
    }

    private fun KWebCookieSameSite.cdpName(): String? = when (this) {
        KWebCookieSameSite.UNSPECIFIED -> null
        KWebCookieSameSite.NONE -> "None"
        KWebCookieSameSite.LAX -> "Lax"
        KWebCookieSameSite.STRICT -> "Strict"
    }

    private fun KWebCookiePriority.cdpName(): String = when (this) {
        KWebCookiePriority.LOW -> "Low"
        KWebCookiePriority.MEDIUM -> "Medium"
        KWebCookiePriority.HIGH -> "High"
    }

    private fun KWebCookieSourceScheme.cdpName(): String? = when (this) {
        KWebCookieSourceScheme.HTTP -> "NonSecure"
        KWebCookieSourceScheme.HTTPS -> "Secure"
        KWebCookieSourceScheme.UNKNOWN -> null
    }

    private fun KWebProfileDataKind.cdpStorageType(): String = when (this) {
        KWebProfileDataKind.LOCAL_STORAGE -> "local_storage"
        KWebProfileDataKind.INDEXED_DB -> "indexeddb"
        KWebProfileDataKind.CACHE_STORAGE -> "cache_storage"
        KWebProfileDataKind.SERVICE_WORKERS -> "service_workers"
        KWebProfileDataKind.WEB_SQL -> "websql"
        KWebProfileDataKind.FILE_SYSTEMS -> "file_systems"
        KWebProfileDataKind.SHARED_STORAGE -> "shared_storage"
        KWebProfileDataKind.COOKIES,
        KWebProfileDataKind.HTTP_CACHE -> error("A non-origin storage kind was not expected.")
    }
}

internal fun KWebCookie.matches(filter: KWebCookieFilter): Boolean {
    if (!filter.includeHttpOnly && httpOnly) return false
    if (filter.name != null && name != filter.name) return false
    if (filter.domain != null && domain != filter.domain) return false
    if (filter.path != null && path != filter.path) return false
    if (filter.partitionKey != null && partitionKey != filter.partitionKey) return false
    filter.origin?.let { origin ->
        val uri = URI(origin)
        val host = uri.host ?: return false
        val normalizedHost = host.lowercase()
        val normalizedDomain = domain.removePrefix(".").lowercase()
        if (normalizedHost != normalizedDomain && !normalizedHost.endsWith(".$normalizedDomain")) {
            return false
        }
    }
    val range = filter.timeRange
    if (range.sinceEpochMillis != null || range.untilEpochMillis != null) {
        val activityTimes = listOfNotNull(creationEpochMillis, lastAccessEpochMillis)
        if (activityTimes.isEmpty() || activityTimes.none { it.inTimeRange(range) }) {
            return false
        }
    }
    return true
}

private fun Long.inTimeRange(range: io.github.kingsword09.kwebshell.core.KWebProfileTimeRange): Boolean =
    (range.sinceEpochMillis?.let { this >= it } ?: true) &&
        (range.untilEpochMillis?.let { this <= it } ?: true)
