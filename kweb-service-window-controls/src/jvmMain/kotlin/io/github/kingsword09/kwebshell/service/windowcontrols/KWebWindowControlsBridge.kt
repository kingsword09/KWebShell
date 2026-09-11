package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.BoundsRequest
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.FlagRequest
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.StateRequest
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.TitleRequest
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowControlsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowControlsBridgeHandler
import io.github.kingsword09.kwebshell.service.windowcontrols.generated.WindowStateResponse
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

public fun KWebWindowControls.bridgeDispatcher(
    policy: KWebServicePermissionPolicy,
): KWebBridgeDispatcher = bridgeDispatcher { operationId ->
    if (!policy.allows(KWebWindowControls.DESCRIPTOR.id, operationId)) {
        throw KWebBridgeException(
            code = KWebServiceErrorCode.PERMISSION_DENIED,
            message = "The page is not granted '$operationId' on KWebWindowControls.",
        )
    }
}

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
): KWebBridgeDispatcher = WindowControlsBridgeDispatcher(
    object : WindowControlsBridgeHandler {
        override suspend fun getState(request: StateRequest): WindowStateResponse =
            dispatch("get-state") { snapshot().toResponse() }

        override suspend fun setTitle(request: TitleRequest): WindowStateResponse =
            dispatch("set-title") { setTitle(request.title).toResponse() }

        override suspend fun setBounds(request: BoundsRequest): WindowStateResponse =
            dispatch("set-bounds") {
                setBounds(KWebWindowBounds(request.x, request.y, request.width, request.height)).toResponse()
            }

        override suspend fun setVisible(request: FlagRequest): WindowStateResponse =
            dispatch("set-visible") { setVisible(request.enabled).toResponse() }

        override suspend fun focus(request: FlagRequest): WindowStateResponse =
            dispatch("focus") { focus().toResponse() }

        override suspend fun minimize(request: FlagRequest): WindowStateResponse =
            dispatch("minimize") { minimize().toResponse() }

        override suspend fun restore(request: FlagRequest): WindowStateResponse =
            dispatch("restore") { restore().toResponse() }

        override suspend fun setMaximized(request: FlagRequest): WindowStateResponse =
            dispatch("set-maximized") { setMaximized(request.enabled).toResponse() }

        override suspend fun setAlwaysOnTop(request: FlagRequest): WindowStateResponse =
            dispatch("set-always-on-top") { setAlwaysOnTop(request.enabled).toResponse() }

        override suspend fun setResizable(request: FlagRequest): WindowStateResponse =
            dispatch("set-resizable") { setResizable(request.enabled).toResponse() }

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

private fun KWebWindowState.toResponse(): WindowStateResponse = WindowStateResponse(
    title = title,
    x = bounds.x,
    y = bounds.y,
    width = bounds.width,
    height = bounds.height,
    visible = visible,
    focused = focused,
    minimized = minimized,
    placement = placement.id,
    alwaysOnTop = alwaysOnTop,
    resizable = resizable,
)
