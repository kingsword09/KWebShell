package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.StateRequest
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowControlsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowControlsBridgeHandler
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowStateResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

/**
 * The one bridge entry for KWebWindowControls. Renderer access is limited to
 * the generated operation set; hierarchy ownership and forced close remain
 * host-only operations.
 */
public fun KWebWindowControls.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = bridgeDispatcher { operationId ->
    authorizeOperation(policyEngine, subject, operationId)
}

private suspend fun authorizeOperation(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebWindowControls.DESCRIPTOR.operations
        .first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebWindowControls.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            code = verdict.reasonCode,
            message = "The policy engine denied '$operationId' on KWebWindowControls.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            code = KWebServicePolicyEngine.REASON_PROMPT,
            message = "The policy engine requires explicit consent for '$operationId' on KWebWindowControls.",
        )
    }
}

private fun KWebWindowControls.bridgeDispatcher(
    policyCheck: suspend (String) -> Unit,
): KWebBridgeDispatcher {
    val service = this
    return WindowControlsBridgeDispatcher(
    object : WindowControlsBridgeHandler {
        override suspend fun requestClose(request: StateRequest): WindowStateResponse =
            dispatch("request-close") {
                when (service) {
                    is ComposeKWebWindowControls -> service.requestRendererClose().state.toResponse()
                    else -> service.requestClose().state.toResponse()
                }
            }

        private suspend fun dispatch(
            operationId: String,
            action: suspend () -> WindowStateResponse,
        ): WindowStateResponse {
            policyCheck(operationId)
            return try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: KWebException) {
                throw KWebBridgeException(
                    code = error.code,
                    message = error.message ?: "The KWebWindowControls operation failed.",
                    cause = error,
                )
            } catch (error: Throwable) {
                throw KWebBridgeException(
                    code = KWebServiceErrorCode.NATIVE_FAILED,
                    message = error.message ?: "The KWebWindowControls operation failed.",
                    cause = error,
                )
            }
        }
    },
    )
}

private fun KWebWindowState.toResponse(): WindowStateResponse = WindowStateResponse(
    id = id,
    parentId = parentId,
    modality = modality.id,
    title = title,
    x = bounds.x,
    y = bounds.y,
    width = bounds.width,
    height = bounds.height,
    restoredX = restoredBounds?.x,
    restoredY = restoredBounds?.y,
    restoredWidth = restoredBounds?.width,
    restoredHeight = restoredBounds?.height,
    placement = placement.id,
    fullscreen = fullscreen.id,
    visible = visible,
    focused = focused,
    movable = movable,
    minimizable = minimizable,
    maximizable = maximizable,
    closable = closable,
    resizable = resizable,
    alwaysOnTop = alwaysOnTop,
    minimumWidth = constraints.minimumWidth,
    minimumHeight = constraints.minimumHeight,
    maximumWidth = constraints.maximumWidth,
    maximumHeight = constraints.maximumHeight,
    attention = attention.id,
    displayId = displayId,
    displayScale = displayScale?.toString(),
)
