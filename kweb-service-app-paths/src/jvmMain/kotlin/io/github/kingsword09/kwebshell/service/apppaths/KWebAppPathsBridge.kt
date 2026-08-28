package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.apppaths.generated.KWebAppPathsBridgeHandler
import io.github.kingsword09.kwebshell.service.apppaths.generated.ResolveRequest
import io.github.kingsword09.kwebshell.service.apppaths.generated.ResolveResponse
import io.github.kingsword09.kwebshell.service.apppaths.generated.KWebAppPathsBridgeDispatcher
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import kotlinx.coroutines.CancellationException

public fun KWebAppPaths.bridgeDispatcher(
    policy: KWebServicePermissionPolicy,
): KWebBridgeDispatcher = KWebAppPathsBridgeDispatcher(
    object : KWebAppPathsBridgeHandler {
        override suspend fun resolve(request: ResolveRequest): ResolveResponse {
            val grant = KWebServiceGrant(KWebAppPaths.DESCRIPTOR.id, "resolve")
            if (!policy.allows(grant.serviceId, grant.operationId)) {
                throw KWebBridgeException(
                    code = KWebServiceErrorCode.PERMISSION_DENIED,
                    message = "The page is not granted the KWebAppPaths resolve operation.",
                )
            }
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
