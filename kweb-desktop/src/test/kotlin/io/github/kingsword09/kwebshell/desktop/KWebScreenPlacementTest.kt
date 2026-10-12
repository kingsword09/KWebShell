package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.service.screen.KWebScreenCapabilities
import io.github.kingsword09.kwebshell.service.screen.KWebScreenConversion
import io.github.kingsword09.kwebshell.service.screen.KWebScreenCursor
import io.github.kingsword09.kwebshell.service.screen.KWebScreenDisplay
import io.github.kingsword09.kwebshell.service.screen.KWebScreenEvent
import io.github.kingsword09.kwebshell.service.screen.KWebScreenGeometry
import io.github.kingsword09.kwebshell.service.screen.KWebScreenId
import io.github.kingsword09.kwebshell.service.screen.KWebScreenPoint
import io.github.kingsword09.kwebshell.service.screen.KWebScreenSession
import io.github.kingsword09.kwebshell.service.screen.KWebScreenSpace
import io.github.kingsword09.kwebshell.service.screen.KWebScreenTopology
import io.github.kingsword09.kwebshell.service.screen.KWebScreens
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The placement consumer follows one topology read per placement, so a window is
 * never placed on a display that the topology no longer contains.
 */
class KWebScreenPlacementTest {
    private val primaryId = KWebScreenId("display.primary")
    private val secondaryId = KWebScreenId("display.secondary")

    private fun display(
        id: KWebScreenId,
        left: Int,
        width: Int,
        height: Int,
        workArea: KWebScreenGeometry? = null,
        primary: Boolean = false,
    ) = KWebScreenDisplay(
        id = id,
        geometry = KWebScreenGeometry(left, 0, width, height),
        nativeGeometry = KWebScreenGeometry(left, 0, width, height),
        workArea = workArea,
        primary = primary,
    )

    private class FakeScreens(
        private val ids: List<KWebScreenId>,
        private val displays: () -> List<KWebScreenDisplay>,
    ) : KWebScreens {
        override val descriptor: KWebServiceDescriptor = KWebScreens.DESCRIPTOR
        override val lifecycle: StateFlow<KWebLifecycleState> =
            MutableStateFlow(KWebLifecycleState.OPEN).asStateFlow()
        override val events: Flow<KWebScreenEvent> = emptyFlow()
        var topologyValue: KWebScreenTopology = KWebScreenTopology(
            providerId = "screen.test",
            session = KWebScreenSession.X11,
            displays = displays(),
        )

        override suspend fun capabilities(): KWebScreenCapabilities = KWebScreenCapabilities(
            providerId = "screen.test",
            session = KWebScreenSession.X11,
            capabilities = emptySet(),
            spaces = setOf(KWebScreenSpace.LOGICAL, KWebScreenSpace.NATIVE),
        )

        override suspend fun topology(): KWebScreenTopology = topologyValue

        override suspend fun cursor(): KWebScreenCursor =
            KWebScreenCursor(KWebScreenPoint(0, 0), KWebScreenSpace.NATIVE, null)

        override suspend fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId? =
            topologyValue.displayAt(point, space)?.id

        override suspend fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId =
            topologyValue.nearest(point, space).id

        override suspend fun convert(request: KWebScreenConversion): KWebScreenPoint = request.point

        override fun close() = Unit
    }

    private fun screens(vararg displays: KWebScreenDisplay): FakeScreens =
        FakeScreens(displays.map { it.id }) { displays.toList() }

    @Test
    fun aLiveDisplayIsCentredInsideItsWorkArea() = runBlocking {
        val primary = display(
            primaryId,
            left = 0,
            width = 1920,
            height = 1080,
            workArea = KWebScreenGeometry(0, 25, 1920, 1055),
            primary = true,
        )
        val placer = KWebScreenPlacer(screens(primary))
        val placement = placer.place(primaryId, 800, 600)
        assertEquals(primaryId, placement.displayId)
        assertEquals(560, placement.bounds.left)
        assertEquals(25 + (1055 - 600) / 2, placement.bounds.top)
        assertEquals(800, placement.bounds.width)
        assertNull(placement.movedFrom)
    }

    @Test
    fun aRemovedDisplayMovesTheWindowToTheNearestDisplayOfTheSeedAndReportsIt() = runBlocking {
        val screens = screens(
            display(primaryId, left = 0, width = 1920, height = 1080, primary = true),
            display(secondaryId, left = 1920, width = 1280, height = 1024),
        )
        val placer = KWebScreenPlacer(screens)
        val removed = KWebScreenId("display.unplugged")
        val placement = placer.place(removed, 400, 300, KWebScreenPoint(2000, 200))
        assertEquals(secondaryId, placement.displayId)
        assertEquals(removed, placement.movedFrom)
        // The window lies inside the display that still exists.
        assertEquals(secondaryId, screens.topologyValue.displayAt(
            KWebScreenPoint(placement.bounds.left, placement.bounds.top),
            KWebScreenSpace.LOGICAL,
        )?.id)

        // After the display is gone the placement never returns it again.
        val again = placer.place(removed, 400, 300)
        assertTrue(again.displayId != removed)
        assertEquals(removed, again.movedFrom)
    }

    @Test
    fun aWindowLargerThanTheWorkAreaIsClippedInsteadOfLeavingTheDesktop() = runBlocking {
        val primary = display(
            primaryId,
            left = 0,
            width = 1920,
            height = 1080,
            workArea = KWebScreenGeometry(0, 25, 1920, 1055),
            primary = true,
        )
        val placement = KWebScreenPlacer(screens(primary)).place(primaryId, 4000, 3000)
        assertEquals(1920, placement.bounds.width)
        assertEquals(1055, placement.bounds.height)
        assertTrue(placement.bounds.left >= 0 && placement.bounds.top >= 25)
        assertTrue(placement.bounds.right <= 1920 && placement.bounds.bottom <= 1080)
    }

    @Test
    fun aDisplayWithoutAPublishedWorkAreaUsesItsGeometryAndTheSizeIsBounded() = runBlocking {
        val primary = display(primaryId, left = -100, width = 800, height = 600, primary = true)
        val placement = KWebScreenPlacer(screens(primary)).place(null, 200, 100)
        assertEquals(primaryId, placement.displayId)
        assertNull(placement.movedFrom)
        assertTrue(placement.bounds.left >= -100 && placement.bounds.right <= 700)
        assertFailsWith<KWebConfigurationException> {
            KWebScreenPlacer(screens(primary)).place(null, 0, 100)
        }
    }
}
