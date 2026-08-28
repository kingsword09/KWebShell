package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class KWebAppPathsBridgeTest {
    @Test
    fun preservesCancellationFromTheServiceOperation() {
        val service = CancellingAppPaths()
        val dispatcher = service.bridgeDispatcher(
            KWebServicePermissionPolicy.exact(
                setOf(
                    KWebServiceGrant(
                        KWebAppPaths.DESCRIPTOR.id,
                        "resolve",
                    ),
                ),
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
