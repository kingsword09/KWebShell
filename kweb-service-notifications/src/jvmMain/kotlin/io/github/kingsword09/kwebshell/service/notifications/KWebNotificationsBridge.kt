package io.github.kingsword09.kwebshell.service.notifications

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
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

public fun KWebNotifications.bridgeDispatcher(): KWebBridgeDispatcher = NotificationsBridgeDispatcher(
    object : NotificationsBridgeHandler {
        override suspend fun permission(request: ScopeRequest): PermissionResponse {
            requireApplicationScope(request)
            return permission().toBridge()
        }

        override suspend fun requestPermission(request: ScopeRequest): PermissionResponse {
            requireApplicationScope(request)
            return requestOperation { requestPermission().toBridge() }
        }

        override suspend fun capabilities(request: ScopeRequest): CapabilitiesResponse {
            requireApplicationScope(request)
            return capabilities().let {
                CapabilitiesResponse(it.actions, it.replies, it.replacement, it.timeout, it.activation)
            }
        }

        override suspend fun show(request: ShowRequest): ShowResponse = requestOperation {
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

        override suspend fun close(request: CloseRequest): CloseResponse = requestOperation {
            close(KWebNotificationId(request.id)).let { CloseResponse(it.id.value, it.sequence.toString()) }
        }

        private suspend fun <T> requestOperation(block: suspend () -> T): T = try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: KWebException) {
            throw KWebBridgeException(error.code, error.message ?: "The notification operation failed.", error)
        } catch (error: Throwable) {
            throw KWebBridgeException("service.native-failed", error.message ?: "The notification operation failed.", error)
        }
    },
)

private fun requireApplicationScope(request: ScopeRequest) {
    if (request.scope != "application") {
        throw KWebBridgeException("service.request-invalid", "The notifications scope must be 'application'.")
    }
}

private fun KWebNotificationPermission.toBridge(): PermissionResponse = PermissionResponse(status.name, provider)
