package io.github.kingsword09.kwebshell.service.preferences

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher
import io.github.kingsword09.kwebshell.core.KWebAppearanceSource
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.preferences.generated.ReadRequest
import io.github.kingsword09.kwebshell.service.preferences.generated.SetAppearanceRequest
import io.github.kingsword09.kwebshell.service.preferences.generated.SetAppearanceResponse
import io.github.kingsword09.kwebshell.service.preferences.generated.Snapshot
import io.github.kingsword09.kwebshell.service.preferences.generated.SystemPreferencesBridgeDispatcher
import io.github.kingsword09.kwebshell.service.preferences.generated.SystemPreferencesBridgeHandler
import io.github.kingsword09.kwebshell.service.preferences.generated.SystemPreferencesBridgeStreamDispatcher
import io.github.kingsword09.kwebshell.service.preferences.generated.SystemPreferencesBridgeStreamHandler
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** The facts a renderer may read: assistive-technology state is never among them. */
private val RENDERER_VISIBLE_FACTS: Set<KWebPreferenceFact> =
    KWebPreferenceFact.entries.toSet() - KWebPreferenceFact.SCREEN_READER

/**
 * The renderer-facing system-preferences surface. A page reads and subscribes to
 * the non-sensitive facts the host publishes, and only the declared appearance
 * override may be requested, from an exact-origin main frame with a user
 * gesture. A child or cross-origin frame is denied before anything happens.
 */
public fun KWebSystemPreferences.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher {
    val service = this
    return SystemPreferencesBridgeDispatcher(
        object : SystemPreferencesBridgeHandler {
            override suspend fun read(request: ReadRequest): Snapshot =
                service.dispatch("read", policyEngine, subject) {
                    snapshotFor(request.facts, snapshot())
                }

            override suspend fun setAppearance(request: SetAppearanceRequest): SetAppearanceResponse =
                service.dispatch("set-appearance", policyEngine, subject) {
                    val result = requestAppearance(appearanceSourceOf(request.source))
                    SetAppearanceResponse(result.effective.name, result.sequence.toString())
                }
        },
    )
}

/**
 * The renderer subscription. It follows the service change stream and ends with
 * the typed provider failure instead of leaving a silent gap in the sequence.
 */
public fun KWebSystemPreferences.bridgeStreamDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebStreamBridgeDispatcher = SystemPreferencesBridgeStreamDispatcher(
    object : SystemPreferencesBridgeStreamHandler {
        override fun changes(request: ReadRequest): Flow<Snapshot> =
            changesFlow(policyEngine, subject, request.facts)
    },
)

/**
 * The subscription itself: it authorizes the declared grant first, then follows
 * the service change stream and ends with the typed provider failure instead of
 * leaving a silent gap in the sequence.
 */
internal fun KWebSystemPreferences.changesFlow(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    requestedFacts: List<String>,
): Flow<Snapshot> = flow {
    // The renderer-facing stream method is `changes`; its declared policy
    // operation is `subscribe`.
    authorize(policyEngine, subject, "subscribe")
    events.collect { event ->
        when (event) {
            is KWebSystemPreferenceEvent.Changed -> emit(snapshotFor(requestedFacts, event.snapshot))
            is KWebSystemPreferenceEvent.Failed -> throw KWebBridgeException(
                event.code,
                "The system-preferences subscription ended with a provider failure.",
            )
        }
    }
}

private fun snapshotFor(
    requested: List<String>,
    snapshot: KWebSystemPreferenceSnapshot,
): Snapshot {
    val facts = requested.map { name ->
        val fact = KWebPreferenceFact.entries.singleOrNull { it.name == name }
            ?: throw KWebBridgeException(
                KWebSystemPreferenceErrorCode.FACT_UNKNOWN,
                "The renderer requested an unknown preference fact.",
            )
        if (fact !in RENDERER_VISIBLE_FACTS) {
            throw KWebBridgeException(
                KWebSystemPreferenceErrorCode.FACT_NOT_RENDERER_VISIBLE,
                "The renderer requested a fact that is not renderer-visible.",
            )
        }
        fact
    }.toSet()
    fun published(fact: KWebPreferenceFact): Boolean = fact in facts && fact in snapshot.facts
    return Snapshot(
        appearanceSource = snapshot.appearanceSource.name,
        colorScheme = if (published(KWebPreferenceFact.COLOR_SCHEME)) snapshot.colorScheme?.name.orEmpty() else "",
        contrast = if (published(KWebPreferenceFact.CONTRAST)) snapshot.contrast?.name.orEmpty() else "",
        reducedMotion = if (published(KWebPreferenceFact.REDUCED_MOTION)) {
            snapshot.reducedMotion?.toString().orEmpty()
        } else {
            ""
        },
        accentColor = if (published(KWebPreferenceFact.ACCENT_COLOR)) snapshot.accentColor?.hex.orEmpty() else "",
        textScalePercent = if (published(KWebPreferenceFact.TEXT_SCALE)) {
            snapshot.textScale?.percent ?: 0
        } else {
            0
        },
        sequence = snapshot.sequence.toString(),
    )
}

private fun appearanceSourceOf(value: String): KWebAppearanceSource =
    KWebAppearanceSource.entries.singleOrNull { it.name == value }
        ?: throw KWebBridgeException(
            KWebSystemPreferenceErrorCode.APPEARANCE_INVALID,
            "The renderer requested an unknown appearance source.",
        )

private suspend fun <T> KWebSystemPreferences.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebSystemPreferences.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The system-preferences operation failed.", error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The system-preferences operation failed.", error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebSystemPreferences.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebSystemPreferences.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The system-preferences operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            "service.policy.prompt-required",
            "The system-preferences operation requires consent.",
        )
    }
}
