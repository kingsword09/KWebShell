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
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
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
    gestures: KWebUserGestureRegistry = KWebUserGestureRegistry(),
): KWebBridgeDispatcher {
    val engine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = gestures,
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
                dispatcher.dispatch("""{"version":1,"method":"requestClose","payload":{"enabled":true}}""")
            }
        }
        assertEquals("service.permission-denied", failure.code)
        assertEquals(0, service.requestCloseCalls)
    }

    @Test
    fun bridgeMapsTypedRequestsAndPreservesCancellation() {
        val service = RecordingWindowControls()
        val gestures = KWebUserGestureRegistry()
        val dispatcher = policyDispatcher(
            service,
            setOf(
                KWebServiceGrant(KWebWindowControls.DESCRIPTOR.id, "request-close"),
            ),
            gestures,
        )
        val binding = KWebGestureBinding("engine-bridge-test", "bridge-test", "page-1", "https://app.example")
        gestures.mint(binding)

        val response = runBlocking {
            dispatcher.dispatch("""{"version":1,"method":"requestClose","payload":{"enabled":true}}""")
        }
        assertEquals(1, service.requestCloseCalls)
        kotlin.test.assertTrue(response.contains("bridge-test-window"))

        service.cancelRequest = true
        gestures.mint(binding)
        assertFailsWith<CancellationException> {
            runBlocking {
                dispatcher.dispatch("""{"version":1,"method":"requestClose","payload":{"enabled":true}}""")
            }
        }
    }

    private class RecordingWindowControls : KWebWindowControls {
        override val descriptor = KWebWindowControls.DESCRIPTOR
        override val lifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
        override val registration = KWebWindowRegistration("bridge-test-window")
        override val state = MutableStateFlow(initialState())
        override val events = emptyFlow<KWebWindowEvent>()
        override val closeRequests = emptyFlow<KWebWindowCloseRequest>()
        var requestCloseCalls: Int = 0
        var cancelRequest: Boolean = false

        override suspend fun snapshot(): KWebWindowState {
            return state.value
        }

        override suspend fun setTitle(title: String): KWebWindowState {
            return state.value.copy(title = title).also { state.value = it }
        }

        override suspend fun requestClose(): KWebWindowCloseResult {
            if (cancelRequest) throw CancellationException("cancelled")
            requestCloseCalls += 1
            return KWebWindowCloseResult(1L, KWebWindowCloseOutcome.PENDING, state.value)
        }

        override suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState = update { copy(bounds = bounds) }
        override suspend fun setConstraints(constraints: KWebWindowConstraints): KWebWindowState = update { copy(constraints = constraints) }
        override suspend fun setVisible(visible: Boolean): KWebWindowState = update { copy(visible = visible) }
        override suspend fun focus(): KWebWindowState = update { copy(focused = true) }
        override suspend fun minimize(): KWebWindowState = update { copy(placement = KWebWindowPlacement.MINIMIZED) }
        override suspend fun restore(): KWebWindowState = update { copy(placement = KWebWindowPlacement.FLOATING) }
        override suspend fun setMaximized(maximized: Boolean): KWebWindowState =
            update { copy(placement = if (maximized) KWebWindowPlacement.MAXIMIZED else KWebWindowPlacement.FLOATING) }
        override suspend fun setFullscreen(mode: KWebWindowFullscreenMode): KWebWindowState = update { copy(fullscreen = mode) }
        override suspend fun setMovable(movable: Boolean): KWebWindowState = update { copy(movable = movable) }
        override suspend fun setMinimizable(minimizable: Boolean): KWebWindowState = update { copy(minimizable = minimizable) }
        override suspend fun setMaximizable(maximizable: Boolean): KWebWindowState = update { copy(maximizable = maximizable) }
        override suspend fun setClosable(closable: Boolean): KWebWindowState = update { copy(closable = closable) }
        override suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState = update { copy(alwaysOnTop = alwaysOnTop) }
        override suspend fun setResizable(resizable: Boolean): KWebWindowState = update { copy(resizable = resizable) }
        override suspend fun requestAttention(): KWebWindowState = update { copy(attention = KWebWindowAttention.REQUESTED) }
        override suspend fun clearAttention(): KWebWindowState = update { copy(attention = KWebWindowAttention.NONE) }
        override suspend fun respondToClose(requestId: Long, decision: KWebWindowCloseDecision): KWebWindowCloseResult =
            KWebWindowCloseResult(requestId, if (decision == KWebWindowCloseDecision.ALLOW) KWebWindowCloseOutcome.ALLOWED else KWebWindowCloseOutcome.DENIED, state.value)
        override suspend fun forceClose(reason: KWebWindowForceCloseReason): KWebWindowCloseResult =
            KWebWindowCloseResult(1, KWebWindowCloseOutcome.FORCED, state.value)
        override fun close() {
            lifecycle.value = KWebLifecycleState.CLOSED
        }

        private fun update(block: KWebWindowState.() -> KWebWindowState): KWebWindowState =
            state.value.block().also { state.value = it }

        companion object {
            fun initialState(): KWebWindowState = KWebWindowState(
                id = "bridge-test-window",
                parentId = null,
                modality = KWebWindowModality.NONE,
                title = "fixture",
                bounds = KWebWindowBounds(10, 20, 800, 600),
                restoredBounds = null,
                placement = KWebWindowPlacement.FLOATING,
                fullscreen = KWebWindowFullscreenMode.WINDOWED,
                visible = true,
                focused = false,
                movable = true,
                minimizable = true,
                maximizable = true,
                closable = true,
                alwaysOnTop = false,
                resizable = true,
                constraints = KWebWindowConstraints(),
                attention = KWebWindowAttention.NONE,
                displayId = null,
                displayScale = null,
            )
        }
    }
}
