package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private fun policyDispatcher(
    service: KWebWindowControls,
    grants: Set<KWebServiceGrant>,
): KWebBridgeDispatcher {
    val engine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = KWebUserGestureRegistry(),
        consentStore = KWebInMemoryConsentStore("bridge-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    return service.bridgeDispatcher(
        engine,
        KWebPolicySubject(
            engineId = "engine-bridge-test",
            profileId = "bridge-test",
            pageId = "page-1",
            origin = "https://app.example",
            scope = KWebServiceScope.APPLICATION,
        ),
    )
}

class KWebWindowControlsBridgeTest {
    @Test
    fun bridgeRequiresExactOperationGrant() {
        val service = RecordingWindowControls()
        val dispatcher = policyDispatcher(service, emptySet())

        val failure = assertFailsWith<KWebBridgeException> {
            runBlocking {
                dispatcher.dispatch("""{"version":1,"method":"setTitle","payload":{"title":"denied"}}""")
            }
        }
        assertEquals("service.permission-denied", failure.code)
        assertEquals(0, service.setTitleCalls)
    }

    @Test
    fun bridgeMapsTypedRequestsAndPreservesCancellation() {
        val service = RecordingWindowControls()
        val dispatcher = policyDispatcher(
            service,
            setOf(
                KWebServiceGrant(KWebWindowControls.DESCRIPTOR.id, "set-title"),
                KWebServiceGrant(KWebWindowControls.DESCRIPTOR.id, "get-state"),
            ),
        )

        val response = runBlocking {
            dispatcher.dispatch("""{"version":1,"method":"setTitle","payload":{"title":"KWebShell"}}""")
        }
        assertEquals(1, service.setTitleCalls)
        kotlin.test.assertTrue(response.contains("KWebShell"))

        service.cancelSnapshot = true
        assertFailsWith<CancellationException> {
            runBlocking {
                dispatcher.dispatch("""{"version":1,"method":"getState","payload":{"enabled":true}}""")
            }
        }
    }

    private class RecordingWindowControls : KWebWindowControls {
        override val descriptor = KWebWindowControls.DESCRIPTOR
        override val lifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        override val state = MutableStateFlow(initialState())
        override val events = emptyFlow<KWebWindowEvent>()
        var setTitleCalls: Int = 0
        var cancelSnapshot: Boolean = false

        override suspend fun snapshot(): KWebWindowState {
            if (cancelSnapshot) throw CancellationException("cancelled")
            return state.value
        }

        override suspend fun setTitle(title: String): KWebWindowState {
            setTitleCalls += 1
            return state.value.copy(title = title).also { state.value = it }
        }

        override suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState = update { copy(bounds = bounds) }
        override suspend fun setVisible(visible: Boolean): KWebWindowState = update { copy(visible = visible) }
        override suspend fun focus(): KWebWindowState = update { copy(focused = true) }
        override suspend fun minimize(): KWebWindowState = update { copy(minimized = true) }
        override suspend fun restore(): KWebWindowState = update { copy(minimized = false) }
        override suspend fun setMaximized(maximized: Boolean): KWebWindowState =
            update { copy(placement = if (maximized) KWebWindowPlacement.MAXIMIZED else KWebWindowPlacement.FLOATING) }
        override suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState = update { copy(alwaysOnTop = alwaysOnTop) }
        override suspend fun setResizable(resizable: Boolean): KWebWindowState = update { copy(resizable = resizable) }
        override fun close() {
            lifecycle.value = KWebLifecycleState.CLOSED
        }

        private fun update(block: KWebWindowState.() -> KWebWindowState): KWebWindowState =
            state.value.block().also { state.value = it }

        companion object {
            fun initialState(): KWebWindowState = KWebWindowState(
                title = "fixture",
                bounds = KWebWindowBounds(10, 20, 800, 600),
                visible = true,
                focused = false,
                minimized = false,
                placement = KWebWindowPlacement.FLOATING,
                alwaysOnTop = false,
                resizable = true,
            )
        }
    }
}
