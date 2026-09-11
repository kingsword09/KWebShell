package io.github.kingsword09.kwebshell.services.policy

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The OS-reported consent state for one facility. The states are distinct on
 * purpose: an application may treat `temporarily-unavailable` differently from
 * `denied`, and `not-configured` is a remediation problem, never a fallback.
 */
public enum class KWebConsentStatus {
    GRANTED,
    DENIED,
    RESTRICTED,
    NOT_CONFIGURED,
    TEMPORARILY_UNAVAILABLE,
}

/** One OS consent query for one privileged operation from one exact origin. */
public data class KWebConsentRequest(
    public val serviceId: String,
    public val operationId: String,
    public val origin: String,
    public val facility: String,
) {
    init {
        if (serviceId.isBlank() || operationId.isBlank() || origin.isBlank() || facility.isBlank()) {
            throw KWebConfigurationException(
                code = "service.consent.request-invalid",
                details = mapOf("service" to serviceId, "operation" to operationId),
                message = "A consent request must identify the service, operation, origin, and facility.",
            )
        }
    }
}

/**
 * Reports real OS consent state. Implementations query the platform's own
 * permission machinery; they never prompt implicitly, never flip OS state, and
 * never report a synthetic result.
 */
public interface KWebOsConsentProvider {
    /** The OS facility this provider reports, e.g. `macos.tcc.accessibility`. */
    public val facility: String

    public suspend fun status(request: KWebConsentRequest): KWebConsentStatus
}

/** One persistent user decision for one (service, operation, origin) triple. */
public data class KWebConsentDecision(
    public val granted: Boolean,
    public val decidedBy: String,
)

/** One revocation of a previously recorded decision. */
public data class KWebConsentRevocation(
    public val serviceId: String,
    public val operationId: String,
    public val origin: String,
    public val decidedBy: String,
)

/**
 * Persistent user decisions with their persistence scope. Decisions are per
 * (scope owner, service, operation, origin); revocation is observable so hosts
 * can react immediately.
 */
public interface KWebConsentStore {
    /** The persistence scope owner, e.g. an application or Profile identity. */
    public val scopeOwnerId: String

    public fun decision(request: KWebConsentRequest): KWebConsentDecision?

    public fun record(request: KWebConsentRequest, granted: Boolean, decidedBy: String)

    /** Removes the persistent decision; the next attempt becomes PROMPT_REQUIRED. */
    public fun revoke(request: KWebConsentRequest, decidedBy: String)

    public val revocations: SharedFlow<KWebConsentRevocation>

    public fun close()
}

/** Thread-safe in-memory consent store; durable hosts install their own store. */
public class KWebInMemoryConsentStore(
    override val scopeOwnerId: String,
) : KWebConsentStore {
    private val lock = Any()
    private val decisions = mutableMapOf<String, KWebConsentDecision>()
    private val mutableRevocations = MutableSharedFlow<KWebConsentRevocation>(replay = 16, extraBufferCapacity = 64)
    private var closed = false

    override val revocations: SharedFlow<KWebConsentRevocation> = mutableRevocations.asSharedFlow()

    override fun decision(request: KWebConsentRequest): KWebConsentDecision? {
        synchronized(lock) {
            requireOpen()
            return decisions[key(request)]
        }
    }

    override fun record(request: KWebConsentRequest, granted: Boolean, decidedBy: String) {
        synchronized(lock) {
            requireOpen()
            decisions[key(request)] = KWebConsentDecision(granted, decidedBy)
        }
    }

    override fun revoke(request: KWebConsentRequest, decidedBy: String) {
        val removed = synchronized(lock) {
            requireOpen()
            decisions.remove(key(request))
        }
        if (removed != null) {
            val emitted = mutableRevocations.tryEmit(
                KWebConsentRevocation(request.serviceId, request.operationId, request.origin, decidedBy),
            )
            if (!emitted) {
                throw KWebConfigurationException(
                    code = "service.consent.revocation-dropped",
                    details = mapOf("service" to request.serviceId),
                    message = "The consent revocation event buffer overflowed.",
                )
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            decisions.clear()
        }
    }

    private fun requireOpen() {
        if (closed) {
            throw KWebConfigurationException(
                code = "service.owner-closed",
                details = mapOf("scope" to scopeOwnerId),
                message = "The consent store is closed.",
            )
        }
    }

    private fun key(request: KWebConsentRequest): String =
        "$scopeOwnerId|${request.serviceId}|${request.operationId}|${request.origin}"
}

