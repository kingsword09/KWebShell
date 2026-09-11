package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.apppaths.generated.KWebAppPathsBridgeHandler
import io.github.kingsword09.kwebshell.service.apppaths.generated.ResolveRequest
import io.github.kingsword09.kwebshell.service.apppaths.generated.ResolveResponse
import io.github.kingsword09.kwebshell.service.apppaths.generated.KWebAppPathsBridgeDispatcher
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

public fun KWebAppPaths.bridgeDispatcher(
    policy: KWebServicePermissionPolicy,
): KWebBridgeDispatcher = bridgeDispatcher { _ ->
    if (!policy.allows(KWebAppPaths.DESCRIPTOR.id, "resolve")) {
        throw KWebBridgeException(
            code = KWebServiceErrorCode.PERMISSION_DENIED,
            message = "The page is not granted the KWebAppPaths resolve operation.",
        )
    }
}

public fun KWebAppPaths.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = bridgeDispatcher {
    val operation = KWebAppPaths.DESCRIPTOR.operations.first { it.id == "resolve" }
    val verdict = policyEngine.authorize(subject, KWebAppPaths.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            code = verdict.reasonCode,
            message = "The policy engine denied the KWebAppPaths resolve operation.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            code = KWebServicePolicyEngine.REASON_PROMPT,
            message = "The policy engine requires explicit consent for the KWebAppPaths resolve operation.",
        )
    }
}

private fun KWebAppPaths.bridgeDispatcher(
    policyCheck: suspend (String) -> Unit,
): KWebBridgeDispatcher = KWebAppPathsBridgeDispatcher(
    object : KWebAppPathsBridgeHandler {
        override suspend fun resolve(request: ResolveRequest): ResolveResponse {
            policyCheck("resolve")
            val kind = try {
                KWebAppPathKind.fromId(request.kind)
            } catch (error: Throwable) {
                throw KWebBridgeException(
                    code = KWebServiceErrorCode.REQUEST_INVALID,
                    message = "The requested application path kind is not published.",
                    cause = error,
                )
            }
            try {
                val result = resolve(kind)
                return ResolveResponse(
                    kind = result.kind.id,
                    path = result.path,
                    source = result.source,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: KWebException) {
                throw KWebBridgeException(
                    code = error.code,
                    message = error.message ?: "The KWebAppPaths operation failed.",
                    cause = error,
                )
            } catch (error: Throwable) {
                throw KWebBridgeException(
                    code = "service.native-failed",
                    message = error.message ?: "The KWebAppPaths operation failed.",
                    cause = error,
                )
            }
        }
    },
)
