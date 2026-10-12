package io.github.kingsword09.kwebshell.service.screen

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.screen.generated.ReadRequest
import io.github.kingsword09.kwebshell.service.screen.generated.ScreenBridgeDispatcher
import io.github.kingsword09.kwebshell.service.screen.generated.ScreenBridgeHandler
import io.github.kingsword09.kwebshell.service.screen.generated.ScreenBridgeStreamDispatcher
import io.github.kingsword09.kwebshell.service.screen.generated.ScreenBridgeStreamHandler
import io.github.kingsword09.kwebshell.service.screen.generated.ScreenSummary
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The facts a renderer may learn: the display count and the primary display's
 * summary, which a page already observes through `window.screen` and
 * `devicePixelRatio`. Display identifiers, other displays, the work area, and the
 * cursor are never renderer-visible.
 */
private val RENDERER_FACTS: Set<String> = setOf("display-count", "primary-geometry", "primary-scale")

/**
 * The renderer-facing screen surface: a granted exact-origin main frame reads the
 * declared summary and subscribes to topology change notifications that carry the
 * summary only, so a page can follow the desktop without enumerating it.
 */
public fun KWebScreens.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher {
    val service = this
    return ScreenBridgeDispatcher(
        object : ScreenBridgeHandler {
            override suspend fun read(request: ReadRequest): ScreenSummary =
                service.dispatch("read", policyEngine, subject) {
                    summaryOf(request.facts, topology())
                }
        },
    )
}

/** The renderer subscription: the same summary, published on every topology change. */
public fun KWebScreens.bridgeStreamDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebStreamBridgeDispatcher = ScreenBridgeStreamDispatcher(
    object : ScreenBridgeStreamHandler {
        override fun changes(request: ReadRequest): Flow<ScreenSummary> =
            changesFlow(policyEngine, subject, request.facts)
    },
)

/**
 * The subscription itself: it authorizes the declared grant first, then publishes
 * the bounded summary on every topology change and ends with the typed provider
 * failure instead of leaving a silent gap.
 */
internal fun KWebScreens.changesFlow(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    requestedFacts: List<String>,
): Flow<ScreenSummary> = flow {
    authorize(policyEngine, subject, "subscribe")
    events.collect { event ->
        when (event) {
            is KWebScreenEvent.TopologyChanged -> emit(summaryOf(requestedFacts, event.topology))
            is KWebScreenEvent.Failed -> throw KWebBridgeException(
                event.code,
                "The screen subscription ended with a provider failure.",
            )
        }
    }
}

/** Validates the requested facts and builds the bounded renderer summary. */
internal fun summaryOf(requested: List<String>, topology: KWebScreenTopology): ScreenSummary {
    requested.forEach { fact ->
        if (fact !in RENDERER_FACTS) {
            throw KWebBridgeException(
                if (fact.matches(Regex("[a-z]+(?:-[a-z]+)*"))) {
                    KWebScreenErrorCode.FACT_NOT_RENDERER_VISIBLE
                } else {
                    KWebScreenErrorCode.FACT_UNKNOWN
                },
                "The renderer requested a screen fact that is not renderer-visible.",
            )
        }
    }
    val primary = topology.primary ?: topology.displays.first()
    return ScreenSummary(
        displayCount = if (requested.contains("display-count")) topology.displays.size else 0,
        primaryWidth = if (requested.contains("primary-geometry")) primary.geometry.width else 0,
        primaryHeight = if (requested.contains("primary-geometry")) primary.geometry.height else 0,
        primaryScalePermille = if (requested.contains("primary-scale")) primary.scale?.permille ?: 0 else 0,
    )
}

private suspend fun <T> KWebScreens.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebScreens.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The screen operation failed.", error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The screen operation failed.", error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebScreens.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebScreens.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The screen operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            "service.policy.prompt-required",
            "The screen operation requires consent.",
        )
    }
}
