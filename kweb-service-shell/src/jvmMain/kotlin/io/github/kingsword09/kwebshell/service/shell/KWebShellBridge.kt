package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.shell.generated.ActionResponse
import io.github.kingsword09.kwebshell.service.shell.generated.ExternalUriRequest
import io.github.kingsword09.kwebshell.service.shell.generated.ResourceRequest
import io.github.kingsword09.kwebshell.service.shell.generated.ShellBridgeDispatcher
import io.github.kingsword09.kwebshell.service.shell.generated.ShellBridgeHandler
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

public fun KWebShell.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = bridgeDispatcher { operationId ->
    val operation = KWebShell.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebShell.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            code = verdict.reasonCode,
            message = "The policy engine denied '$operationId' on KWebShell.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            code = KWebServicePolicyEngine.REASON_PROMPT,
            message = "The policy engine requires explicit consent for '$operationId' on KWebShell.",
        )
    }
}

private fun KWebShell.bridgeDispatcher(
    policyCheck: suspend (String) -> Unit,
): KWebBridgeDispatcher = ShellBridgeDispatcher(
    object : ShellBridgeHandler {
        override suspend fun openExternal(request: ExternalUriRequest): ActionResponse =
            dispatch("open-external") {
                openExternal(KWebShellExternalUriRequest(request.uri)).toResponse()
            }

        override suspend fun openResource(request: ResourceRequest): ActionResponse =
            dispatch("open-resource") {
                openResource(KWebShellResourceHandle.fromBridge(request.handle)).toResponse()
            }

        override suspend fun revealResource(request: ResourceRequest): ActionResponse =
            dispatch("reveal-resource") {
                revealResource(KWebShellResourceHandle.fromBridge(request.handle)).toResponse()
            }

        override suspend fun trashResource(request: ResourceRequest): ActionResponse =
            dispatch("trash-resource") {
                trashResource(KWebShellResourceHandle.fromBridge(request.handle)).toResponse()
            }

        private suspend fun dispatch(
            operation: String,
            action: suspend () -> ActionResponse,
        ): ActionResponse {
            policyCheck(operation)
            return try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: KWebException) {
                throw KWebBridgeException(
                    code = error.code,
                    message = error.message ?: "The KWebShell operation failed.",
                    cause = error,
                )
            } catch (error: Throwable) {
                throw KWebBridgeException(
                    code = KWebServiceErrorCode.NATIVE_FAILED,
                    message = error.message ?: "The KWebShell operation failed.",
                    cause = error,
                )
            }
        }
    },
)

private fun KWebShellActionResult.toResponse(): ActionResponse = ActionResponse(
    action = action.id,
    outcome = outcome.id,
    resourceKind = resourceKind?.id,
)

private val KWebShellAction.id: String
    get() = when (this) {
        KWebShellAction.OPEN_EXTERNAL -> "open-external"
        KWebShellAction.OPEN_RESOURCE -> "open-resource"
        KWebShellAction.REVEAL_RESOURCE -> "reveal-resource"
        KWebShellAction.TRASH_RESOURCE -> "trash-resource"
    }

private val KWebShellActionOutcome.id: String
    get() = when (this) {
        KWebShellActionOutcome.HANDLER_ACCEPTED -> "handler-accepted"
        KWebShellActionOutcome.MOVED_TO_TRASH -> "moved-to-trash"
    }

private val KWebShellResourceKind.id: String
    get() = when (this) {
        KWebShellResourceKind.FILE -> "file"
        KWebShellResourceKind.DIRECTORY -> "directory"
    }
