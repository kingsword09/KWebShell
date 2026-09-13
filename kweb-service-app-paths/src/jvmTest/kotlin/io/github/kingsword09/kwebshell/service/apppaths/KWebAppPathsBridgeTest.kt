package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KWebAppPathsBridgeTest {
    @Test
    fun preservesCancellationFromTheServiceOperation() {
        val service = CancellingAppPaths()
        val engine = KWebServicePolicyEngine(
            rendererGrants = KWebServicePermissionPolicy.exact(
                setOf(
                    KWebServiceGrant(
                        KWebAppPaths.DESCRIPTOR.id,
                        "resolve",
                    ),
                ),
            ),
            gestures = KWebUserGestureRegistry(),
            consentStore = KWebInMemoryConsentStore("bridge-test"),
            osConsent = null,
            audit = KWebPolicyAudit(),
        )
        val dispatcher: KWebBridgeDispatcher = service.bridgeDispatcher(
            engine,
            KWebPolicySubject(
                engineId = "engine-bridge-test",
                profileId = "bridge-test",
                pageId = "page-1",
                origin = "https://app.example",
                scope = KWebServiceScope.APPLICATION,
            ),
        )

        assertFailsWith<CancellationException> {
            runBlocking {
                dispatcher.dispatch(
                    """{"version":1,"method":"resolve","payload":{"kind":"home"}}""",
                )
            }
        }
    }

    private class CancellingAppPaths : KWebAppPaths {
        override val descriptor = KWebAppPaths.DESCRIPTOR
        override val lifecycle = MutableStateFlow(KWebLifecycleState.OPEN)

        override suspend fun resolve(kind: KWebAppPathKind): KWebResolvedPath {
            throw CancellationException("test cancellation")
        }

        override fun close() {
            lifecycle.value = KWebLifecycleState.CLOSED
        }
    }
}
