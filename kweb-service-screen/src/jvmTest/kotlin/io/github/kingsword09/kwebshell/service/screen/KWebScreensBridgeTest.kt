package io.github.kingsword09.kwebshell.service.screen

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The renderer surface of the screen service: a granted exact-origin main frame
 * reads and subscribes to the bounded summary only, and a page can never
 * enumerate the desktop, learn an identifier, or read the cursor.
 */
class KWebScreensBridgeTest {
    private val primaryId = KWebScreenId("display.primary")
    private val secondaryId = KWebScreenId("display.secondary")

    private inner class RecordingScreens : KWebScreens {
        override val descriptor: KWebServiceDescriptor = KWebScreens.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()
        val publishedEvents = MutableSharedFlow<KWebScreenEvent>(replay = 4)
        override val events: Flow<KWebScreenEvent> = publishedEvents

        var topologyValue: KWebScreenTopology = KWebScreenTopology(
            providerId = "screen.test",
            session = KWebScreenSession.X11,
            displays = listOf(
                KWebScreenDisplay(
                    id = KWebScreenId("display.primary"),
                    geometry = KWebScreenGeometry(0, 0, 1920, 1080),
                    nativeGeometry = KWebScreenGeometry(0, 0, 2880, 1620),
                    scale = KWebScreenScale(1500),
                    primary = true,
                ),
                KWebScreenDisplay(
                    id = KWebScreenId("display.secondary"),
                    geometry = KWebScreenGeometry(1920, 0, 1280, 1024),
                    nativeGeometry = KWebScreenGeometry(1920, 0, 1280, 1024),
                ),
            ),
        )

        override suspend fun capabilities(): KWebScreenCapabilities = KWebScreenCapabilities(
            providerId = "screen.test",
            session = KWebScreenSession.X11,
            capabilities = setOf(KWebScreenCapability.MULTI_DISPLAY),
            spaces = setOf(KWebScreenSpace.LOGICAL, KWebScreenSpace.NATIVE),
        )

        override suspend fun topology(): KWebScreenTopology = topologyValue

        override suspend fun cursor(): KWebScreenCursor =
            KWebScreenCursor(KWebScreenPoint(1, 2), KWebScreenSpace.NATIVE, KWebScreenId("display.primary"))

        override suspend fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId? = null

        override suspend fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId = primaryId

        override suspend fun convert(request: KWebScreenConversion): KWebScreenPoint = request.point

        override fun close() = Unit
    }

    private fun subject(mainFrame: Boolean = true) = KWebPolicySubject(
        engineId = "engine-1",
        profileId = "profile-1",
        pageId = "page-1",
        origin = "https://app.example",
        scope = KWebServiceScope.APPLICATION,
        isMainFrame = mainFrame,
        hostCall = false,
    )

    private fun engine(grants: Set<KWebServiceGrant>) = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(grants),
        gestures = KWebUserGestureRegistry(),
        consentStore = KWebInMemoryConsentStore("screen-test"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )

    private fun grant(operation: String) = KWebServiceGrant(KWebScreens.DESCRIPTOR.id, operation)

    @Test
    fun aGrantedRendererReadsOnlyTheSummaryItAskedFor() = runBlocking {
        val screens = RecordingScreens()
        val dispatcher = screens.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val response = dispatcher.dispatch(
            """{"version":1,"method":"read","payload":{"facts":["display-count","primary-geometry","primary-scale"]}}""",
        )
        assertTrue(response.contains("\"displayCount\":2"), response)
        assertTrue(response.contains("\"primaryWidth\":1920"), response)
        assertTrue(response.contains("\"primaryScalePermille\":1500"), response)
        // Identifiers, the work area, and the cursor are never part of the summary.
        assertTrue(!response.contains("display.primary"), response)
        assertTrue(!response.contains("secondary"), response)

        val partial = dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["display-count"]}}""")
        assertTrue(partial.contains("\"displayCount\":2"), partial)
        assertTrue(partial.contains("\"primaryWidth\":0"), partial)
    }

    @Test
    fun identifiersCursorAndUnknownFactsAreRejectedWithStableCodes() = runBlocking {
        val screens = RecordingScreens()
        val dispatcher = screens.bridgeDispatcher(engine(setOf(grant("read"))), subject())
        val notVisible = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["cursor"]}}""")
        }
        assertEquals(KWebScreenErrorCode.FACT_NOT_RENDERER_VISIBLE, notVisible.code)
        val unknown = assertFailsWith<KWebBridgeException> {
            dispatcher.dispatch("""{"version":1,"method":"read","payload":{"facts":["EVERY_DISPLAY"]}}""")
        }
        assertEquals(KWebScreenErrorCode.FACT_UNKNOWN, unknown.code)
    }

    @Test
    fun aMissingGrantOrAChildFrameIsDeniedBeforeAnythingHappens() = runBlocking {
        val screens = RecordingScreens()
        val missingGrant = assertFailsWith<KWebBridgeException> {
            screens.bridgeDispatcher(engine(emptySet()), subject())
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["display-count"]}}""")
        }
        assertEquals("service.permission-denied", missingGrant.code)
        val childFrame = assertFailsWith<KWebBridgeException> {
            screens.bridgeDispatcher(engine(setOf(grant("read"))), subject(mainFrame = false))
                .dispatch("""{"version":1,"method":"read","payload":{"facts":["display-count"]}}""")
        }
        assertEquals("service.policy.denied-frame", childFrame.code)
    }

    @Test
    fun theSubscriptionPublishesTheSummaryAndEndsOnAProviderFailure() = runBlocking {
        val screens = RecordingScreens()
        val chunks = mutableListOf<io.github.kingsword09.kwebshell.service.screen.generated.ScreenSummary>()
        val collector = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
            .launch(kotlinx.coroutines.Dispatchers.Default) {
                screens.changesFlow(engine(setOf(grant("subscribe"))), subject(), listOf("display-count"))
                    .collect { chunk -> chunks += chunk }
            }
        try {
            screens.publishedEvents.emit(
                KWebScreenEvent.TopologyChanged(
                    topology = screens.topologyValue.copy(
                        displays = screens.topologyValue.displays.take(1),
                        sequence = 2uL,
                    ),
                    added = emptySet(),
                    removed = setOf(secondaryId),
                    changed = emptySet(),
                    sequence = 2uL,
                ),
            )
            withTimeout(5_000) { while (chunks.isEmpty()) kotlinx.coroutines.delay(20) }
            assertEquals(1, chunks.single().displayCount)
            assertEquals(0, chunks.single().primaryWidth)
        } finally {
            collector.cancel()
        }

        val failure = assertFailsWith<KWebBridgeException> {
            kotlinx.coroutines.coroutineScope {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    screens.changesFlow(engine(setOf(grant("subscribe"))), subject(), listOf("display-count"))
                        .collect { }
                }
                kotlinx.coroutines.delay(50)
                screens.publishedEvents.emit(
                    KWebScreenEvent.Failed(code = KWebScreenErrorCode.NATIVE_FAILED, sequence = 3uL),
                )
                job.join()
            }
        }
        assertEquals(KWebScreenErrorCode.NATIVE_FAILED, failure.code)
        // The subscription is grant checked as well.
        val denied = assertFailsWith<KWebBridgeException> {
            screens.changesFlow(engine(emptySet()), subject(), listOf("display-count")).first()
        }
        assertEquals("service.permission-denied", denied.code)
    }
}
