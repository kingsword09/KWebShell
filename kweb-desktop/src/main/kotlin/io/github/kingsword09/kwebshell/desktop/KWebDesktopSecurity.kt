package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebCertificateSummary
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebSecurityChallenge
import io.github.kingsword09.kwebshell.core.KWebSecurityDecision
import io.github.kingsword09.kwebshell.core.KWebTlsError
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

internal class KWebDesktopSecurityChallengeStream {
    private val mutableEvents = MutableSharedFlow<KWebSecurityChallenge>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    val events: Flow<KWebSecurityChallenge> = mutableEvents.asSharedFlow()

    fun publish(challenge: KWebSecurityChallenge): Boolean {
        if (mutableEvents.subscriptionCount.value == 0) return false
        return mutableEvents.tryEmit(challenge)
    }
}

internal object KWebDesktopSecurityJson {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    fun parseChallenge(
        event: NativeBrowserEvent,
        profileId: String,
        pageId: String,
    ): KWebSecurityChallenge {
        val root = parseObject(event.details)
        val requestId = requiredLong(root, "requestId")
        if (requestId != event.requestId || requestId <= 0L) {
            invalid("requestId")
        }
        if (requiredInt(root, "version") != 1) invalid("version")
        val origin = nullableString(root, "origin")
        if (event.origin.isNotEmpty() && event.origin != origin) invalid("origin")
        val deadline = requiredLong(root, "deadlineEpochMillis")
        return when (requiredString(root, "kind")) {
            "tls" -> parseTls(root, event, profileId, pageId, origin, deadline)
            "clientCertificate" ->
                parseClientCertificate(root, event, profileId, pageId, origin, deadline)
            else -> invalid("kind")
        }
    }

    fun decisionPayload(
        challenge: KWebSecurityChallenge,
        decision: KWebSecurityDecision,
    ): String = when (challenge) {
        is KWebSecurityChallenge.Tls -> buildJsonObject {
            put("version", 1)
            put("kind", "tls")
            when (decision) {
                KWebSecurityDecision.Tls.DENY -> put("decision", "deny")
                KWebSecurityDecision.Tls.ALLOW_ONCE -> put("decision", "allow_once")
                is KWebSecurityDecision.Tls.ALLOW_FOR_PROFILE_ORIGIN -> {
                    if (challenge.origin == null) invalid("origin")
                    val now = System.currentTimeMillis()
                    if (decision.expiresAtEpochMillis <= now ||
                        decision.expiresAtEpochMillis > now + 24L * 60L * 60L * 1000L
                    ) {
                        throw KWebConfigurationException(
                            code = "security.tls.expiry-invalid",
                            details = mapOf("requestId" to challenge.requestId.toString()),
                            message = "A scoped TLS exception must expire within 24 hours.",
                        )
                    }
                    put("decision", "allow_for_profile_origin")
                    put("expiresAtEpochMillis", decision.expiresAtEpochMillis)
                }
                else -> invalidDecision(challenge, decision)
            }
        }.toString()
        is KWebSecurityChallenge.ClientCertificate -> buildJsonObject {
            put("version", 1)
            put("kind", "clientCertificate")
            when (decision) {
                KWebSecurityDecision.ClientCertificate.DENY -> put("decision", "deny")
                is KWebSecurityDecision.ClientCertificate.SELECT -> {
                    if (decision.sha256Fingerprint !in challenge.certificates.map { it.sha256Fingerprint }) {
                        throw KWebConfigurationException(
                            code = "security.client-certificate.not-offered",
                            details = mapOf("requestId" to challenge.requestId.toString()),
                            message = "The selected certificate was not offered by Chromium.",
                        )
                    }
                    put("decision", "select")
                    put("sha256Fingerprint", decision.sha256Fingerprint)
                }
                else -> invalidDecision(challenge, decision)
            }
        }.toString()
    }

    private fun parseTls(
        root: JsonObject,
        event: NativeBrowserEvent,
        profileId: String,
        pageId: String,
        origin: String?,
        deadline: Long,
    ): KWebSecurityChallenge.Tls {
        requireKeys(root, "version", "kind", "requestId", "requestUrl", "origin", "error",
            "deadlineEpochMillis", "certificate")
        val requestUrl = requiredString(root, "requestUrl")
        if (event.url != requestUrl) invalid("requestUrl")
        return KWebSecurityChallenge.Tls(
            requestId = event.requestId,
            profileId = profileId,
            pageId = pageId,
            origin = origin,
            deadlineEpochMillis = deadline,
            requestUrl = requestUrl,
            error = tlsError(requiredString(root, "error")),
            certificate = certificate(root["certificate"] ?: invalid("certificate")),
        )
    }

    private fun parseClientCertificate(
        root: JsonObject,
        event: NativeBrowserEvent,
        profileId: String,
        pageId: String,
        origin: String?,
        deadline: Long,
    ): KWebSecurityChallenge.ClientCertificate {
        requireKeys(root, "version", "kind", "requestId", "origin", "host", "port", "isProxy",
            "certificates", "deadlineEpochMillis")
        val certificates = root["certificates"]?.jsonArray?.map(::certificate)
            ?: invalid("certificates")
        return KWebSecurityChallenge.ClientCertificate(
            requestId = event.requestId,
            profileId = profileId,
            pageId = pageId,
            origin = origin,
            deadlineEpochMillis = deadline,
            host = requiredString(root, "host"),
            port = requiredInt(root, "port"),
            isProxy = requiredBoolean(root, "isProxy"),
            certificates = certificates,
        )
    }

    private fun certificate(element: JsonElement): KWebCertificateSummary {
        val root = try { element.jsonObject } catch (error: Throwable) { invalid("certificate", error) }
        requireKeys(root, "sha256Fingerprint", "subject", "issuer", "serialNumber",
            "validStartEpochMillis", "validExpiryEpochMillis")
        return KWebCertificateSummary(
            sha256Fingerprint = requiredString(root, "sha256Fingerprint"),
            subject = requiredString(root, "subject"),
            issuer = requiredString(root, "issuer"),
            serialNumber = requiredString(root, "serialNumber"),
            validStartEpochMillis = nullableLong(root, "validStartEpochMillis"),
            validExpiryEpochMillis = nullableLong(root, "validExpiryEpochMillis"),
        )
    }

    private fun tlsError(value: String): KWebTlsError = when (value) {
        "unknown" -> KWebTlsError.UNKNOWN
        "commonNameInvalid" -> KWebTlsError.COMMON_NAME_INVALID
        "dateInvalid" -> KWebTlsError.DATE_INVALID
        "authorityInvalid" -> KWebTlsError.AUTHORITY_INVALID
        "revoked" -> KWebTlsError.REVOKED
        "weakSignatureAlgorithm" -> KWebTlsError.WEAK_SIGNATURE_ALGORITHM
        "weakKey" -> KWebTlsError.WEAK_KEY
        "pinnedKeyMissing" -> KWebTlsError.PINNED_KEY_MISSING
        "invalid" -> KWebTlsError.INVALID
        "other" -> KWebTlsError.OTHER
        else -> invalid("error")
    }

    private fun parseObject(payload: String): JsonObject = try {
        json.parseToJsonElement(payload).jsonObject
    } catch (error: Throwable) {
        invalid("details", error)
    }

    private fun requireKeys(root: JsonObject, vararg keys: String) {
        if (root.keys != keys.toSet()) invalid("fields")
    }

    private fun requiredString(root: JsonObject, key: String): String =
        root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
            ?: invalid(key)

    private fun nullableString(root: JsonObject, key: String): String? {
        val element = root[key] ?: invalid(key)
        return if (element is JsonNull) null else element.jsonPrimitive.contentOrNull ?: invalid(key)
    }

    private fun requiredLong(root: JsonObject, key: String): Long {
        val primitive = root[key]?.jsonPrimitive ?: invalid(key)
        return primitive.longOrNull ?: primitive.doubleOrNull?.let { value ->
            if (!value.isFinite() || value < Long.MIN_VALUE || value > Long.MAX_VALUE ||
                value != value.toLong().toDouble()
            ) invalid(key)
            value.toLong()
        } ?: invalid(key)
    }

    private fun nullableLong(root: JsonObject, key: String): Long? {
        val element = root[key] ?: invalid(key)
        if (element is JsonNull) return null
        return requiredLong(root, key)
    }

    private fun requiredInt(root: JsonObject, key: String): Int =
        root[key]?.jsonPrimitive?.intOrNull ?: invalid(key)

    private fun requiredBoolean(root: JsonObject, key: String): Boolean =
        root[key]?.jsonPrimitive?.booleanOrNull ?: invalid(key)

    private fun invalid(field: String, cause: Throwable? = null): Nothing =
        throw KWebConfigurationException(
            code = "security.challenge.callback-failed",
            details = mapOf("field" to field),
            message = "Chromium returned an invalid security challenge.",
            cause = cause,
        )

    private fun invalidDecision(
        challenge: KWebSecurityChallenge,
        decision: KWebSecurityDecision,
    ): Nothing = throw KWebConfigurationException(
        code = "security.challenge.decision-invalid",
        details = mapOf("requestId" to challenge.requestId.toString()),
        message = "The security decision does not match the challenge type.",
    )
}

internal fun securityDenyDecision(
    challenge: KWebSecurityChallenge,
): KWebSecurityDecision = when (challenge) {
    is KWebSecurityChallenge.Tls -> KWebSecurityDecision.Tls.DENY
    is KWebSecurityChallenge.ClientCertificate -> KWebSecurityDecision.ClientCertificate.DENY
}

internal fun KWebSecurityDecision.isSecurityDeny(): Boolean = when (this) {
    KWebSecurityDecision.Tls.DENY,
    KWebSecurityDecision.ClientCertificate.DENY -> true
    else -> false
}
