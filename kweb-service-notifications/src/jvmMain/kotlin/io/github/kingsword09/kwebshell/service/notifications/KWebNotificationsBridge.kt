package io.github.kingsword09.kwebshell.service.notifications

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.service.notifications.generated.ActionRequest
import io.github.kingsword09.kwebshell.service.notifications.generated.CapabilitiesResponse
import io.github.kingsword09.kwebshell.service.notifications.generated.CloseRequest
import io.github.kingsword09.kwebshell.service.notifications.generated.CloseResponse
import io.github.kingsword09.kwebshell.service.notifications.generated.NotificationsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.notifications.generated.NotificationsBridgeHandler
import io.github.kingsword09.kwebshell.service.notifications.generated.PermissionResponse
import io.github.kingsword09.kwebshell.service.notifications.generated.ScopeRequest
import io.github.kingsword09.kwebshell.service.notifications.generated.ShowRequest
import io.github.kingsword09.kwebshell.service.notifications.generated.ShowResponse
import kotlinx.coroutines.CancellationException

public fun KWebNotifications.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = NotificationsBridgeDispatcher(
    object : NotificationsBridgeHandler {
        override suspend fun permission(request: ScopeRequest): PermissionResponse {
            requireApplicationScope(request)
            return dispatch("permission", policyEngine, subject) { permission().toBridge() }
        }

        override suspend fun requestPermission(request: ScopeRequest): PermissionResponse {
            requireApplicationScope(request)
            return dispatch("request-permission", policyEngine, subject) { requestPermission().toBridge() }
        }

        override suspend fun capabilities(request: ScopeRequest): CapabilitiesResponse {
            requireApplicationScope(request)
            return dispatch("capabilities", policyEngine, subject) { capabilities() }.let {
                CapabilitiesResponse(it.actions, it.replies, it.replacement, it.timeout, it.activation)
            }
        }

        override suspend fun show(request: ShowRequest): ShowResponse = dispatch("show", policyEngine, subject) {
            show(
                KWebNotificationRequest(
                    id = KWebNotificationId(request.id),
                    tag = request.tag,
                    title = request.title,
                    body = request.body,
                    icon = KWebNotificationIcon.valueOf(request.icon),
                    urgency = KWebNotificationUrgency.valueOf(request.urgency),
                    timeout = KWebNotificationTimeout.valueOf(request.timeout),
                    actions = request.actions.map { action ->
                        KWebNotificationAction(
                            id = action.id,
                            title = action.title,
                            kind = KWebNotificationActionKind.valueOf(action.kind),
                            replyPlaceholder = action.replyPlaceholder,
                        )
                    },
                ),
            ).let {
                ShowResponse(it.id.value, it.outcome.name, it.replacedId?.value, it.sequence.toString())
            }
        }

        override suspend fun close(request: CloseRequest): CloseResponse = dispatch("close", policyEngine, subject) {
            close(KWebNotificationId(request.id)).let { CloseResponse(it.id.value, it.sequence.toString()) }
        }

        private suspend fun <T> dispatch(
            operationId: String,
            policyEngine: KWebServicePolicyEngine,
            subject: KWebPolicySubject,
            block: suspend () -> T,
        ): T {
            authorize(policyEngine, subject, operationId)
            return try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: KWebException) {
            throw KWebBridgeException(error.code, error.message ?: "The notification operation failed.", error)
        } catch (error: Throwable) {
            throw KWebBridgeException("service.native-failed", error.message ?: "The notification operation failed.", error)
        }
        }
    },
)

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebNotifications.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebNotifications.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The KWebNotifications operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            KWebServicePolicyEngine.REASON_PROMPT,
            "The KWebNotifications operation requires consent.",
        )
    }
}

private fun requireApplicationScope(request: ScopeRequest) {
    if (request.scope != "application") {
        throw KWebBridgeException("service.request-invalid", "The notifications scope must be 'application'.")
    }
}

private fun KWebNotificationPermission.toBridge(): PermissionResponse = PermissionResponse(status.name, provider)
