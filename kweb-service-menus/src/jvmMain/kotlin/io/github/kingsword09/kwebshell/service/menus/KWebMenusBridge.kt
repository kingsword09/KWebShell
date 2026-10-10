package io.github.kingsword09.kwebshell.service.menus

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.menus.generated.MenusBridgeDispatcher
import io.github.kingsword09.kwebshell.service.menus.generated.MenusBridgeHandler
import io.github.kingsword09.kwebshell.service.menus.generated.ShowDeclaredPopupRequest
import io.github.kingsword09.kwebshell.service.menus.generated.ShowDeclaredPopupResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

/**
 * The renderer-facing menus surface. A renderer may only present a menu the host
 * already declared for its exact page token, with a bounded page-space anchor;
 * it can never submit a menu template, a command, or a native handle.
 */
public fun KWebMenus.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    pageToken: KWebMenuPageToken,
): KWebBridgeDispatcher {
    val service = this
    return MenusBridgeDispatcher(
        object : MenusBridgeHandler {
            override suspend fun showDeclaredPopup(request: ShowDeclaredPopupRequest): ShowDeclaredPopupResponse =
                service.dispatch(
                    "show-declared-popup",
                    policyEngine,
                    subject,
                ) {
                    val menuId = KWebMenuId(request.menuId)
                    val popup = showPopup(
                        KWebMenuPopupRequest(
                            menuId = menuId,
                            owner = KWebMenuOwner.Page(pageToken),
                            position = KWebMenuPosition(
                                KWebMenuCoordinateSpace.PAGE,
                                request.x.toInt(),
                                request.y.toInt(),
                            ),
                            source = KWebMenuPopupSource.RENDERER,
                        ),
                    )
                    ShowDeclaredPopupResponse(popup.popupId.value, (popup.treeVersion).toInt())
                }
        },
    )
}

private suspend fun <T> KWebMenus.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    block: suspend KWebMenus.() -> T,
): T {
    val descriptor = KWebMenus.DESCRIPTOR.operations.first { it.id == operation }
    val verdict = policyEngine.authorize(subject, KWebMenus.DESCRIPTOR.id, descriptor)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode.ifEmpty { KWebMenuErrorCode.PERMISSION_DENIED },
            "The menus operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            verdict.reasonCode.ifEmpty { KWebMenuErrorCode.CONSENT_REQUIRED },
            "The menus operation requires consent.",
        )
    }
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The menus operation failed.", error)
    } catch (error: Throwable) {
        throw KWebBridgeException(KWebMenuErrorCode.NATIVE_FAILED, "The menus operation failed.", error)
    }
}
