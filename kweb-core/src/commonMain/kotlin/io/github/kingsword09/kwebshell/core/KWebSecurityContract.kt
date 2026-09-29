package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow

public enum class KWebTlsError(public val id: String) {
    UNKNOWN("unknown"),
    COMMON_NAME_INVALID("common-name-invalid"),
    DATE_INVALID("date-invalid"),
    AUTHORITY_INVALID("authority-invalid"),
    REVOKED("revoked"),
    WEAK_SIGNATURE_ALGORITHM("weak-signature-algorithm"),
    WEAK_KEY("weak-key"),
    PINNED_KEY_MISSING("pinned-key-missing"),
    INVALID("invalid"),
    OTHER("other"),
}

public data class KWebCertificateSummary(
    public val sha256Fingerprint: String,
    public val subject: String,
    public val issuer: String,
    public val serialNumber: String,
    public val validStartEpochMillis: Long?,
    public val validExpiryEpochMillis: Long?,
) {
    init {
        requireSecurityFingerprint(sha256Fingerprint)
        requireSecurityText("subject", subject, 512, allowEmpty = true)
        requireSecurityText("issuer", issuer, 512, allowEmpty = true)
        requireSecurityText("serialNumber", serialNumber, 256)
        if (validStartEpochMillis != null && validExpiryEpochMillis != null &&
            validStartEpochMillis > validExpiryEpochMillis
        ) {
            throw securityContractError("validity", "start must not be after expiry")
        }
    }
}

public sealed interface KWebSecurityChallenge {
    public val requestId: Long
    public val profileId: String
    public val pageId: String?
    public val origin: String?
    public val deadlineEpochMillis: Long

    public data class Tls(
        override val requestId: Long,
        override val profileId: String,
        override val pageId: String?,
        override val origin: String?,
        override val deadlineEpochMillis: Long,
        public val requestUrl: String,
        public val error: KWebTlsError,
        public val certificate: KWebCertificateSummary,
    ) : KWebSecurityChallenge {
        init {
            validateChallengeIdentity(requestId, profileId, pageId, origin, deadlineEpochMillis)
            requireSecurityText("requestUrl", requestUrl, 8192)
        }
    }

    public data class ClientCertificate(
        override val requestId: Long,
        override val profileId: String,
        override val pageId: String?,
        override val origin: String?,
        override val deadlineEpochMillis: Long,
        public val host: String,
        public val port: Int,
        public val isProxy: Boolean,
        public val certificates: List<KWebCertificateSummary>,
    ) : KWebSecurityChallenge {
        init {
            validateChallengeIdentity(requestId, profileId, pageId, origin, deadlineEpochMillis)
            requireSecurityText("host", host, 512)
            if (port !in 1..65535) {
                throw securityContractError("port", "must be between 1 and 65535")
            }
            if (certificates.size > 64) {
                throw securityContractError("certificates", "must contain at most 64 entries")
            }
            if (certificates.map { it.sha256Fingerprint }.toSet().size != certificates.size) {
                throw securityContractError("certificates", "must not contain duplicate fingerprints")
            }
        }
    }
}

public sealed interface KWebSecurityDecision {
    public sealed interface Tls : KWebSecurityDecision {
        public data object DENY : Tls
        public data object ALLOW_ONCE : Tls
        public data class ALLOW_FOR_PROFILE_ORIGIN(
            public val expiresAtEpochMillis: Long,
        ) : Tls {
            init {
                if (expiresAtEpochMillis <= 0L) {
                    throw securityContractError("expiresAtEpochMillis", "must be positive")
                }
            }
        }
    }

    public sealed interface ClientCertificate : KWebSecurityDecision {
        public data object DENY : ClientCertificate
        public data class SELECT(
            public val sha256Fingerprint: String,
        ) : ClientCertificate {
            init {
                requireSecurityFingerprint(sha256Fingerprint)
            }
        }
    }
}

public enum class KWebSecurityChallengeOutcome(public val id: String) {
    ACCEPTED("accepted"),
    DENIED("denied"),
    TIMED_OUT("timed-out"),
    OWNER_CLOSED("owner-closed"),
    STALE("stale"),
    REJECTED("rejected"),
}

public data class KWebSecurityChallengeResult(
    public val requestId: Long,
    public val outcome: KWebSecurityChallengeOutcome,
)

private fun validateChallengeIdentity(
    requestId: Long,
    profileId: String,
    pageId: String?,
    origin: String?,
    deadlineEpochMillis: Long,
) {
    if (requestId <= 0L) {
        throw securityContractError("requestId", "must be positive")
    }
    requireSecurityText("profileId", profileId, 256)
    pageId?.let { requireSecurityText("pageId", it, 256) }
    origin?.let { requireSecurityText("origin", it, 2048) }
    if (deadlineEpochMillis <= 0L) {
        throw securityContractError("deadlineEpochMillis", "must be positive")
    }
}

private fun requireSecurityFingerprint(value: String) {
    if (!value.matches(Regex("[0-9a-f]{64}"))) {
        throw securityContractError("sha256Fingerprint", "must be 64 lowercase hexadecimal characters")
    }
}

private fun requireSecurityText(
    name: String,
    value: String,
    maximumBytes: Int,
    allowEmpty: Boolean = false,
) {
    if ((!allowEmpty && value.isEmpty()) || value.contains('\u0000') ||
        value.encodeToByteArray().size > maximumBytes
    ) {
        throw securityContractError(name, "is empty, contains NUL, or exceeds $maximumBytes UTF-8 bytes")
    }
}

private fun securityContractError(field: String, reason: String): KWebConfigurationException =
    KWebConfigurationException(
        code = "security.contract.invalid",
        details = mapOf("field" to field, "reason" to reason),
        message = "The security challenge contract field '$field' is invalid: $reason.",
    )
