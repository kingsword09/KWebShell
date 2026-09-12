package io.github.kingsword09.kwebshell.services.policy

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceException
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy

public enum class KWebPolicyDecision {
    ALLOW,
    DENY,
    PROMPT_REQUIRED,
}

/** The outcome of one policy decision: the decision plus its stable reason code. */
public data class KWebPolicyVerdict(
    public val decision: KWebPolicyDecision,
    public val reasonCode: String,
    public val consentStatus: KWebConsentStatus? = null,
    public val gestureResult: KWebGestureConsumeResult? = null,
)

/**
 * One ordered audit record. The field set is closed and contains no request or
 * response payload data; payload material cannot enter the audit trail.
 */
public data class KWebPolicyAuditRecord(
    public val sequence: Long,
    public val serviceId: String,
    public val operationId: String,
    public val origin: String,
    public val hostCall: Boolean,
    public val decision: KWebPolicyDecision,
    public val reasonCode: String,
    public val consentStatus: KWebConsentStatus? = null,
    public val gestureResult: KWebGestureConsumeResult? = null,
)

/**
 * Bounded, ordered audit trail. Records are append-only with contiguous
 * sequence numbers; the oldest record is dropped once the bound is reached.
 */
public class KWebPolicyAudit(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    private val lock = Any()
    private val records = ArrayDeque<KWebPolicyAuditRecord>()
    private var nextSequence = 1L
    private var closed = false

    init {
        if (capacity < 1) {
            throw KWebConfigurationException(
                code = "service.policy.audit-capacity-invalid",
                details = mapOf("capacity" to capacity.toString()),
                message = "The policy audit capacity must be at least one record.",
            )
        }
    }

    public fun record(entry: KWebPolicyAuditRecord) {
        synchronized(lock) {
            if (closed) return
            val sequenced = entry.copy(sequence = nextSequence)
            nextSequence += 1
            records.addLast(sequenced)
            while (records.size > capacity) {
                records.removeFirst()
            }
        }
    }

    public fun snapshot(): List<KWebPolicyAuditRecord> {
        synchronized(lock) {
            return records.toList()
        }
    }

    public fun close() {
        synchronized(lock) {
            closed = true
            records.clear()
        }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 256
    }
}

/**
 * The single policy engine for privileged browser and native-service
 * operations. It evaluates, in order: main-frame restriction, exact renderer
 * grants, native-verified user gestures, persistent user decisions, and OS
 * consent. Every outcome is audited. There is no implicit allow and no
 * fallback prompt.
 *
 * Kotlin-host calls (applications invoking services directly) bypass only the
 * renderer grant and main-frame restriction; gesture and OS consent rules
 * always apply.
 */
public class KWebServicePolicyEngine(
    private val rendererGrants: KWebServicePermissionPolicy,
    private val gestures: KWebUserGestureIssuer,
    private val consentStore: KWebConsentStore,
    private val osConsent: KWebOsConsentProvider?,
    private val audit: KWebPolicyAudit,
) {
    init {
        if (osConsent != null && osConsent.facility.isBlank()) {
            throw KWebConfigurationException(
                code = "service.consent.facility-invalid",
                details = mapOf(),
                message = "An OS consent provider must name its facility.",
            )
        }
    }

    public suspend fun authorize(
        subject: KWebPolicySubject,
        serviceId: String,
        operation: KWebServiceOperationDescriptor,
        facility: String = osConsent?.facility ?: serviceId,
    ): KWebPolicyVerdict {
        val verdict = evaluate(subject, serviceId, operation, facility)
        audit.record(
            KWebPolicyAuditRecord(
                sequence = 0,
                serviceId = serviceId,
                operationId = operation.id,
                origin = subject.origin,
                hostCall = subject.hostCall,
                decision = verdict.decision,
                reasonCode = verdict.reasonCode,
                consentStatus = verdict.consentStatus,
                gestureResult = verdict.gestureResult,
            ),
        )
        return verdict
    }

    private suspend fun evaluate(
        subject: KWebPolicySubject,
        serviceId: String,
        operation: KWebServiceOperationDescriptor,
        facility: String,
    ): KWebPolicyVerdict {
        // 1. Privileged renderer calls are main-frame only; host calls bypass
        //    the renderer surface but never the owner checks.
        if (!subject.hostCall && !subject.isMainFrame) {
            return KWebPolicyVerdict(KWebPolicyDecision.DENY, REASON_FRAME)
        }

        // 2. Exact renderer grants. Host calls bypass only this and the frame
        //    restriction; everything below still applies.
        if (!subject.hostCall) {
            val granted = operation.rendererPermission == null ||
                rendererGrants.allows(serviceId, operation.id)
            if (!granted) {
                return KWebPolicyVerdict(
                    KWebPolicyDecision.DENY,
                    KWebServiceErrorCode.PERMISSION_DENIED,
                )
            }
        }

        // 3. Native-verified user gesture, consumed once. The gesture is spent
        //    on the attempt even when a later rule denies it.
        var gestureResult: KWebGestureConsumeResult? = null
        if (operation.requiresUserGesture) {
            gestureResult = gestures.consumeLatest(subject.toGestureBinding())
            if (gestureResult != KWebGestureConsumeResult.CONSUMED) {
                return KWebPolicyVerdict(
                    KWebPolicyDecision.DENY,
                    KWebServiceErrorCode.USER_GESTURE_REQUIRED,
                    gestureResult = gestureResult,
                )
            }
        }

        // 4. OS consent, when the operation requires it. The OS verdict is
        //    never overridden and never bypassed.
        var consentStatus: KWebConsentStatus? = null
        if (operation.requiresOsConsent) {
            val provider = osConsent
            val request = KWebConsentRequest(serviceId, operation.id, subject.origin, facility)
            consentStatus = provider?.status(request) ?: KWebConsentStatus.NOT_CONFIGURED
            when (consentStatus) {
                KWebConsentStatus.GRANTED -> Unit
                KWebConsentStatus.DENIED,
                KWebConsentStatus.RESTRICTED,
                KWebConsentStatus.NOT_CONFIGURED,
                KWebConsentStatus.TEMPORARILY_UNAVAILABLE,
                -> return KWebPolicyVerdict(
                    KWebPolicyDecision.DENY,
                    REASON_CONSENT,
                    consentStatus = consentStatus,
                    gestureResult = gestureResult,
                )
            }

            // 5. Persistent user decision. Without one the engine demands an
            //    explicit prompt; there is no fallback prompt and no implicit
            //    allow.
            val decision = consentStore.decision(request)
            if (decision == null) {
                return KWebPolicyVerdict(
                    KWebPolicyDecision.PROMPT_REQUIRED,
                    REASON_PROMPT,
                    consentStatus = consentStatus,
                    gestureResult = gestureResult,
                )
            }
            if (!decision.granted) {
                return KWebPolicyVerdict(
                    KWebPolicyDecision.DENY,
                    REASON_REVOKED,
                    consentStatus = consentStatus,
                    gestureResult = gestureResult,
                )
            }
        }

        return KWebPolicyVerdict(
            KWebPolicyDecision.ALLOW,
            REASON_ALLOWED,
            consentStatus = consentStatus,
            gestureResult = gestureResult,
        )
    }

    public companion object {
        public const val REASON_ALLOWED: String = "service.policy.allowed"
        public const val REASON_FRAME: String = "service.policy.denied-frame"
        public const val REASON_CONSENT: String = "service.policy.denied-consent"
        public const val REASON_REVOKED: String = "service.policy.denied-revoked"
        public const val REASON_PROMPT: String = "service.policy.prompt-required"
    }
}

private fun KWebPolicySubject.toGestureBinding(): KWebGestureBinding = KWebGestureBinding(
    engineId = engineId,
    profileId = profileId,
    pageId = pageId,
    origin = origin,
)
