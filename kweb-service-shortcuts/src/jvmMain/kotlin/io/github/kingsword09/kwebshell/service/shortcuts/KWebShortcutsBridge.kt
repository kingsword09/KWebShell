package io.github.kingsword09.kwebshell.service.shortcuts

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.shortcuts.generated.DispatchAck
import io.github.kingsword09.kwebshell.service.shortcuts.generated.DispatchRequest
import io.github.kingsword09.kwebshell.service.shortcuts.generated.ReadRequest
import io.github.kingsword09.kwebshell.service.shortcuts.generated.ShortcutsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.shortcuts.generated.ShortcutsBridgeHandler
import io.github.kingsword09.kwebshell.service.shortcuts.generated.ShortcutsSummary
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

/**
 * The facts a renderer may learn: whether global registration is supported and how
 * many registrations are live. Accelerators, registration identifiers, and command
 * identifiers are never renderer-visible.
 */
private val RENDERER_FACTS: Set<String> = setOf("supported", "registration-count")

/**
 * The renderer-facing shortcut surface: a granted exact-origin main frame reads the
 * declared summary and may dispatch one application-declared command with a user
 * gesture and consent. No renderer path can register, read, or replace an
 * accelerator.
 */
public fun KWebShortcuts.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher {
    val service = this
    return ShortcutsBridgeDispatcher(
        object : ShortcutsBridgeHandler {
            override suspend fun read(request: ReadRequest): ShortcutsSummary =
                authorized("read", policyEngine, subject) { summaryOf(request.facts) }

            override suspend fun dispatch(request: DispatchRequest): DispatchAck =
                authorized("dispatch", policyEngine, subject) {
                    service.dispatch(KWebShortcutCommandId(request.commandId))
                    DispatchAck(accepted = true)
                }
        },
    )
}

/** Validates the requested facts and builds the bounded renderer summary. */
internal suspend fun KWebShortcuts.summaryOf(requested: List<String>): ShortcutsSummary {
    requested.forEach { fact ->
        if (fact !in RENDERER_FACTS) {
            throw KWebBridgeException(
                if (fact.matches(Regex("[a-z]+(?:-[a-z]+)*"))) {
                    KWebShortcutErrorCode.FACT_NOT_RENDERER_VISIBLE
                } else {
                    KWebShortcutErrorCode.FACT_UNKNOWN
                },
                "The renderer requested a shortcut fact that is not renderer-visible.",
            )
        }
    }
    val capabilities = capabilities()
    return ShortcutsSummary(
        supported = capabilities.supports(KWebShortcutCapability.GLOBAL_REGISTRATION),
        registrationCount = if (requested.contains("registration-count")) registrations().size else 0,
    )
}

private suspend fun <T> KWebShortcuts.authorized(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebShortcuts.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The shortcut operation failed.", error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The shortcut operation failed.", error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebShortcuts.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebShortcuts.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The shortcut operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            "service.policy.prompt-required",
            "The shortcut operation requires consent.",
        )
    }
}
