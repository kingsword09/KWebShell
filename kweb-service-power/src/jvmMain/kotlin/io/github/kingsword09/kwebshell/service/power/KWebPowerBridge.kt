package io.github.kingsword09.kwebshell.service.power

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.power.generated.AcquireAck
import io.github.kingsword09.kwebshell.service.power.generated.AcquireRequest
import io.github.kingsword09.kwebshell.service.power.generated.PowerBridgeDispatcher
import io.github.kingsword09.kwebshell.service.power.generated.PowerBridgeHandler
import io.github.kingsword09.kwebshell.service.power.generated.PowerSummary
import io.github.kingsword09.kwebshell.service.power.generated.ReadRequest
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

/**
 * The facts a renderer may learn: whether sleep blocking is supported, whether the
 * session is on battery, and how many leases are live. Lease identifiers, reasons,
 * asserted types, and native assertion details are never renderer-visible.
 */
private val RENDERER_FACTS: Set<String> = setOf("supported", "on-battery", "lease-count")

/**
 * The renderer-facing power surface: a granted exact-origin main frame reads the
 * bounded state summary and may acquire one application-declared lease profile with
 * a user gesture and consent. An observation never creates a lease, and a page can
 * never name a blocker type or an arbitrary duration.
 */
public fun KWebPower.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher {
    val service = this
    return PowerBridgeDispatcher(
        object : PowerBridgeHandler {
            override suspend fun read(request: ReadRequest): PowerSummary =
                authorized("read", policyEngine, subject) { summaryOf(request.facts) }

            override suspend fun acquireProfile(request: AcquireRequest): AcquireAck =
                authorized("acquire-profile", policyEngine, subject) {
                    service.acquireProfile(KWebPowerLeaseProfileId(request.profileId))
                    AcquireAck(accepted = true)
                }
        },
    )
}

/** Validates the requested facts and builds the bounded renderer summary. */
internal suspend fun KWebPower.summaryOf(requested: List<String>): PowerSummary {
    requested.forEach { fact ->
        if (fact !in RENDERER_FACTS) {
            throw KWebBridgeException(
                if (fact.matches(Regex("[a-z]+(?:-[a-z]+)*"))) {
                    KWebPowerErrorCode.FACT_NOT_RENDERER_VISIBLE
                } else {
                    KWebPowerErrorCode.FACT_UNKNOWN
                },
                "The renderer requested a power fact that is not renderer-visible.",
            )
        }
    }
    val capabilities = capabilities()
    return PowerSummary(
        supported = capabilities.supports(KWebPowerCapability.SYSTEM_SLEEP_BLOCKER),
        onBattery = if (requested.contains("on-battery")) state().onBattery ?: false else false,
        leaseCount = if (requested.contains("lease-count")) leases().size else 0,
    )
}

private suspend fun <T> KWebPower.authorized(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebPower.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The power operation failed.", error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The power operation failed.", error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebPower.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebPower.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The power operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            "service.policy.prompt-required",
            "The power operation requires consent.",
        )
    }
}
