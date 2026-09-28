package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebNetworkPolicy
import io.github.kingsword09.kwebshell.core.KWebNetworkCompletionStatus
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestEvent
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestPhase
import io.github.kingsword09.kwebshell.core.KWebNetworkResourceType
import io.github.kingsword09.kwebshell.core.KWebNetworkRule
import io.github.kingsword09.kwebshell.core.KWebNetworkRuleAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URI
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow

internal class KWebNetworkObservationLimitException : IllegalArgumentException(
    "Chromium exceeded the network event payload limit.",
)

internal class KWebDesktopNetworkEventStream(
    bufferCapacity: Int = 256,
) {
    private val mutableEvents = MutableSharedFlow<KWebNetworkRequestEvent>(
        replay = 0,
        extraBufferCapacity = bufferCapacity.also { require(it > 0) },
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val terminalLock = Any()
    @Volatile
    private var terminalFailure: KWebNativeException? = null
    private val mutableFailure = MutableStateFlow<KWebNativeException?>(null)

    val events: Flow<KWebNetworkRequestEvent> = flow {
        terminalFailure?.let { throw it }
        coroutineScope {
            val failureWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
                mutableFailure.filterNotNull().collect { throw it }
            }
            try {
                mutableEvents.asSharedFlow().collect { emit(it) }
            } finally {
                failureWatcher.cancel()
            }
        }
    }

    fun publish(event: KWebNetworkRequestEvent): Boolean {
        synchronized(terminalLock) {
            if (terminalFailure != null) return false
            if (mutableEvents.tryEmit(event)) return true
            failLocked(
                KWebNativeException(
                    code = "network.observation-backpressure",
                    details = emptyMap(),
                    message = "The Profile network observation stream has no capacity.",
                ),
            )
            return false
        }
    }

    fun fail(error: KWebNativeException) {
        synchronized(terminalLock) { failLocked(error) }
    }

    fun close() {
        fail(
            KWebNativeException(
                code = "network.profile-closing",
                details = emptyMap(),
                message = "The Profile closed while network events were being observed.",
            ),
        )
    }

    private fun failLocked(error: KWebNativeException) {
        if (terminalFailure == null) {
            terminalFailure = error
            mutableFailure.value = error
        }
    }
}

internal object KWebDesktopNetworkOperation {
    const val SET_POLICY: Int = 9
}

internal object KWebDesktopNetworkJson {
    private val eventFields = setOf(
        "requestId",
        "phase",
        "url",
        "method",
        "resourceType",
        "action",
        "policyVersion",
        "statusCode",
        "completionStatus",
        "redirectedUrl",
        "errorId",
    )
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }

    fun policyPayload(policy: KWebNetworkPolicy): String {
        validate(policy)
        return buildJsonObject {
            put("version", policy.version)
            putJsonArray("rules") {
                policy.rules.forEach { rule -> add(ruleJson(rule)) }
            }
        }.toString().also { payload ->
            if (payload.encodeToByteArray().size > MAXIMUM_POLICY_BYTES) {
                invalid("network.policy.limit-exceeded", "policyBytes")
            }
        }
    }

    fun parseEvent(payload: String): KWebNetworkRequestEvent {
        if (payload.encodeToByteArray().size > MAXIMUM_EVENT_BYTES) {
            throw KWebNetworkObservationLimitException()
        }
        val root = parseObject(payload)
        if (root.keys.any { it !in eventFields }) {
            error("Chromium returned a network event with an unsupported field.")
        }
        val phase = when (requiredString(root, "phase")) {
            "before-request" -> KWebNetworkRequestPhase.BEFORE_REQUEST
            "complete" -> KWebNetworkRequestPhase.COMPLETE
            else -> error("Unknown network event phase.")
        }
        val action = when (requiredString(root, "action")) {
            "allow" -> KWebNetworkRuleAction.ALLOW
            "block" -> KWebNetworkRuleAction.BLOCK
            "redirect" -> KWebNetworkRuleAction.REDIRECT
            else -> error("Unknown network event action.")
        }
        val resourceType = resourceType(requiredString(root, "resourceType"))
        val statusCode = optionalInt(root, "statusCode")
        val requestIdValue = root["requestId"]?.jsonPrimitive?.content
        val requestId = requestIdValue?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?: error("Chromium omitted a valid network request identifier: '$requestIdValue'.")
        val policyVersion = optionalInt(root, "policyVersion")
            ?.takeIf { it == 1 }
            ?: error("Chromium returned an unsupported network policy version.")
        val url = requiredString(root, "url")
        rejectUrlCredentials(url)
        val redirectedUrl = root["redirectedUrl"]?.let { element ->
            element.jsonPrimitive.contentOrNull?.also(::rejectUrlCredentials)
                ?: error("Chromium returned an invalid redirected URL.")
        }
        val errorId = root["errorId"]?.let { element ->
            element.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank)
                ?: error("Chromium returned an invalid network error identifier.")
        }
        val completionStatus = root["completionStatus"]?.let { element ->
            when (element.jsonPrimitive.contentOrNull) {
                "unknown" -> KWebNetworkCompletionStatus.UNKNOWN
                "success" -> KWebNetworkCompletionStatus.SUCCESS
                "pending" -> KWebNetworkCompletionStatus.PENDING
                "canceled" -> KWebNetworkCompletionStatus.CANCELED
                "failed" -> KWebNetworkCompletionStatus.FAILED
                else -> error("Chromium returned an unknown network completion status.")
            }
        }
        if (phase == KWebNetworkRequestPhase.BEFORE_REQUEST && statusCode != null) {
            error("Chromium returned an HTTP status for a before-request event.")
        }
        if ((phase == KWebNetworkRequestPhase.COMPLETE) != (completionStatus != null)) {
            error("Chromium returned a completion status for the wrong network event phase.")
        }
        return KWebNetworkRequestEvent(
            requestId = requestId,
            phase = phase,
            url = url,
            method = requiredString(root, "method"),
            resourceType = resourceType,
            action = action,
            statusCode = statusCode,
            errorId = errorId,
            completionStatus = completionStatus,
            redirectedUrl = redirectedUrl,
            policyVersion = policyVersion,
        )
    }

    fun validate(policy: KWebNetworkPolicy) {
        if (policy.version != 1) invalid("network.policy.invalid", "version")
        if (policy.rules.size > MAXIMUM_RULES) invalid("network.policy.limit-exceeded", "rules")
        val ids = HashSet<String>()
        policy.rules.forEachIndexed { index, rule -> validateRule(index, rule, ids) }
    }

    private fun validateRule(index: Int, rule: KWebNetworkRule, ids: MutableSet<String>) {
        if (rule.id.isBlank() || rule.id.encodeToByteArray().size > MAXIMUM_RULE_ID_BYTES || !ids.add(rule.id)) {
            invalid("network.policy.invalid", "rules[$index].id")
        }
        if (rule.urlPattern.encodeToByteArray().size !in 1..MAXIMUM_PATTERN_BYTES ||
            !isHttpUrlPattern(rule.urlPattern)
        ) {
            invalid("network.policy.invalid", "rules[$index].urlPattern")
        }
        if (rule.methods.any { method -> !METHOD_PATTERN.matches(method) }) {
            invalid("network.policy.invalid", "rules[$index].methods")
        }
        if (rule.action == KWebNetworkRuleAction.REDIRECT) {
            val target = rule.redirectUrl ?: invalid("network.redirect.invalid", rule.id)
            try {
                validateUrl(target)
            } catch (error: KWebConfigurationException) {
                if (error.code == "network.policy.limit-exceeded") throw error
                invalid("network.redirect.invalid", rule.id, error)
            }
        } else if (rule.redirectUrl != null) {
            invalid("network.policy.invalid", "rules[$index].redirectUrl")
        }
        if (rule.headerMutations.size > MAXIMUM_HEADER_MUTATIONS) {
            invalid("network.policy.limit-exceeded", "rules[$index].headerMutations")
        }
        val names = HashSet<String>()
        rule.headerMutations.forEach { mutation ->
            val name = mutation.name.lowercase()
            if (!HEADER_PATTERN.matches(mutation.name) || !names.add(name)) {
                invalid("network.policy.invalid", "rules[$index].headerMutations")
            }
            if (name in FORBIDDEN_HEADERS) invalid("network.header.forbidden", mutation.name)
            val value = mutation.value
            if (value != null && value.encodeToByteArray().size > MAXIMUM_HEADER_VALUE_BYTES) {
                invalid("network.policy.limit-exceeded", "rules[$index].headerMutations")
            }
            if (value != null && value.any { character ->
                    character.code < 0x20 && character != '\t' || character.code == 0x7f
                }
            ) {
                invalid("network.policy.invalid", "rules[$index].headerMutations")
            }
        }
    }

    private fun ruleJson(rule: KWebNetworkRule): JsonObject = buildJsonObject {
        put("id", rule.id)
        put("urlPattern", rule.urlPattern)
        put("priority", rule.priority)
        put("action", rule.action.name.lowercase())
        putJsonArray("resourceTypes") { rule.resourceTypes.forEach { add(JsonPrimitive(it.name.lowercase())) } }
        putJsonArray("methods") { rule.methods.forEach { add(JsonPrimitive(it)) } }
        rule.redirectUrl?.let { put("redirectUrl", it) }
        putJsonArray("headerMutations") {
            rule.headerMutations.forEach { mutation ->
                add(buildJsonObject {
                    put("name", mutation.name)
                    mutation.value?.let { put("value", it) }
                })
            }
        }
    }

    private fun parseObject(payload: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (error: Throwable) {
        throw IllegalArgumentException("Native network payload is not valid JSON.", error)
    }

    private fun requiredString(root: JsonObject, key: String): String =
        root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: error("Native network payload omitted '$key'.")

    private fun validateUrl(value: String): String {
        if (value.encodeToByteArray().size > MAXIMUM_URL_BYTES) {
            invalid("network.policy.limit-exceeded", "url")
        }
        val uri = try { URI(value) } catch (error: Throwable) {
            invalid("network.policy.invalid", "url", error)
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() ||
            uri.userInfo != null
        ) invalid("network.policy.invalid", "url")
        return value
    }

    private fun isHttpUrlPattern(value: String): Boolean {
        val schemeEnd = value.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return false
        val candidate = try {
            URI(value.replace("*", "x"))
        } catch (_: Throwable) {
            return false
        }
        return candidate.scheme?.lowercase() == scheme && !candidate.host.isNullOrBlank() &&
            candidate.userInfo == null
    }

    private fun resourceType(value: String): KWebNetworkResourceType =
        KWebNetworkResourceType.entries.firstOrNull { it.name.equals(value.replace('-', '_'), ignoreCase = true) }
            ?: error("Chromium returned an unknown network resource type.")

    private fun optionalInt(root: JsonObject, key: String): Int? = root[key]?.let { element ->
        element.jsonPrimitive.intOrNull ?: error("Chromium returned an invalid '$key' value.")
    }

    private fun rejectUrlCredentials(value: String) {
        val uri = try {
            URI(value)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Chromium returned an invalid network URL.", error)
        }
        if (uri.userInfo != null) error("Chromium returned a credential-bearing network URL.")
    }

    private fun invalid(code: String, field: String, cause: Throwable? = null): Nothing =
        throw KWebConfigurationException(
            code = code,
            details = mapOf("field" to field),
            message = "The Profile network policy is invalid.",
            cause = cause,
        )

    private const val MAXIMUM_RULES = 256
    private const val MAXIMUM_RULE_ID_BYTES = 128
    private const val MAXIMUM_PATTERN_BYTES = 2048
    private const val MAXIMUM_POLICY_BYTES = 256 * 1024
    private const val MAXIMUM_EVENT_BYTES = 16 * 1024
    private const val MAXIMUM_HEADER_MUTATIONS = 32
    private const val MAXIMUM_HEADER_VALUE_BYTES = 8192
    private const val MAXIMUM_URL_BYTES = 8192
    private val METHOD_PATTERN = Regex("[A-Za-z]{1,16}")
    private val HEADER_PATTERN = Regex("[A-Za-z0-9-]{1,128}")
    private val FORBIDDEN_HEADERS = setOf(
        "host", "content-length", "cookie", "set-cookie", "authorization",
        "proxy-authorization", "origin", "referer",
    )
}
