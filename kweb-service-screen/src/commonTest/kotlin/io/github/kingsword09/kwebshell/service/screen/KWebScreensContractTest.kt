package io.github.kingsword09.kwebshell.service.screen

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KWebScreensContractTest {
    private val primaryId = KWebScreenId("display.primary")
    private val secondaryId = KWebScreenId("display.secondary")

    private fun display(
        id: KWebScreenId,
        geometry: KWebScreenGeometry,
        native: KWebScreenGeometry = geometry,
        workArea: KWebScreenGeometry? = null,
        scale: KWebScreenScale? = null,
        primary: Boolean = false,
    ) = KWebScreenDisplay(
        id = id,
        geometry = geometry,
        nativeGeometry = native,
        workArea = workArea,
        scale = scale,
        primary = primary,
    )

    private fun topology(vararg displays: KWebScreenDisplay) = KWebScreenTopology(
        providerId = "screen.test",
        session = KWebScreenSession.X11,
        displays = displays.toList(),
    )

    private fun expectedError(code: String, block: () -> Unit) {
        val failure = assertFailsWith<KWebConfigurationException> { block() }
        assertEquals(code, failure.code)
    }

    @Test
    fun scalesStayExactAndBounded() {
        assertEquals(1.5, KWebScreenScale(1500).factor)
        assertEquals(2.0, KWebScreenScale(2000).factor)
        assertEquals(KWebScreenScale(1250), KWebScreenScale(1250))
        expectedError(KWebScreenErrorCode.SCALE_INVALID) {
            KWebScreenScale(KWEB_SCREEN_MIN_SCALE_PERMILLE - 1)
        }
        expectedError(KWebScreenErrorCode.SCALE_INVALID) {
            KWebScreenScale(KWEB_SCREEN_MAX_SCALE_PERMILLE + 1)
        }
    }

    @Test
    fun geometryHandlesNegativeOriginsAndHalfOpenEdges() {
        val geometry = KWebScreenGeometry(left = -1920, top = -200, width = 1920, height = 1080)
        assertEquals(0, geometry.right)
        assertEquals(880, geometry.bottom)
        assertTrue(geometry.contains(KWebScreenPoint(-1920, -200)))
        assertTrue(geometry.contains(KWebScreenPoint(-1, 879)))
        assertTrue(!geometry.contains(KWebScreenPoint(0, 100)))
        assertEquals(0L, geometry.distanceSquared(KWebScreenPoint(-10, 0)))
        assertEquals(1L, geometry.distanceSquared(KWebScreenPoint(0, 0)))
        assertEquals(1L, geometry.distanceSquared(KWebScreenPoint(-1921, 0)))
        expectedError(KWebScreenErrorCode.GEOMETRY_INVALID) {
            KWebScreenGeometry(0, 0, 0, 100)
        }
        expectedError(KWebScreenErrorCode.POINT_INVALID) {
            KWebScreenPoint(KWEB_SCREEN_MAX_COORDINATE + 1, 0)
        }
    }

    @Test
    fun displaysRejectWorkAreasOutsideTheirGeometryAndUnboundedFacts() {
        val geometry = KWebScreenGeometry(0, 0, 1600, 1200)
        display(primaryId, geometry, workArea = KWebScreenGeometry(0, 24, 1600, 1176))
        expectedError(KWebScreenErrorCode.GEOMETRY_INVALID) {
            display(primaryId, geometry, workArea = KWebScreenGeometry(0, 0, 1600, 1201))
        }
        expectedError(KWebScreenErrorCode.REFRESH_INVALID) {
            KWebScreenDisplay(
                id = primaryId,
                geometry = geometry,
                nativeGeometry = geometry,
                refreshHertz = 0,
            )
        }
        expectedError(KWebScreenErrorCode.COLOR_DEPTH_INVALID) {
            KWebScreenDisplay(
                id = primaryId,
                geometry = geometry,
                nativeGeometry = geometry,
                colorDepthBits = KWEB_SCREEN_MAX_COLOR_DEPTH + 1,
            )
        }
        expectedError(KWebScreenErrorCode.DISPLAY_NAME_INVALID) {
            KWebScreenDisplay(
                id = primaryId,
                name = "n".repeat(KWEB_SCREEN_MAX_NAME + 1),
                geometry = geometry,
                nativeGeometry = geometry,
            )
        }
        expectedError(KWebScreenErrorCode.DISPLAY_ID_INVALID) {
            KWebScreenId("1 starts with a digit")
        }
    }

    @Test
    fun topologyPublishesOnlyItsOwnDisplaysAndResolvesWithoutGuessing() {
        val primary = display(
            primaryId,
            KWebScreenGeometry(0, 0, 1600, 1200),
            native = KWebScreenGeometry(0, 0, 3200, 2400),
            scale = KWebScreenScale(2000),
            primary = true,
        )
        val secondary = display(secondaryId, KWebScreenGeometry(1600, 0, 1280, 1024))
        val snapshot = topology(primary, secondary)

        assertEquals(primaryId, snapshot.primary?.id)
        assertEquals(primaryId, snapshot.display(primaryId)?.id)
        assertNull(snapshot.display(KWebScreenId("display.absent")))
        assertEquals(primaryId, snapshot.displayAt(KWebScreenPoint(10, 10), KWebScreenSpace.LOGICAL)?.id)
        assertEquals(secondaryId, snapshot.displayAt(KWebScreenPoint(1700, 10), KWebScreenSpace.LOGICAL)?.id)
        // A point below both displays belongs to none of them, and the closest one wins.
        assertNull(snapshot.displayAt(KWebScreenPoint(1700, 1100), KWebScreenSpace.LOGICAL))
        assertEquals(secondaryId, snapshot.nearest(KWebScreenPoint(1700, 1100), KWebScreenSpace.LOGICAL).id)
        assertEquals(primaryId, snapshot.nearest(KWebScreenPoint(-50, -50), KWebScreenSpace.LOGICAL).id)

        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            topology(primary, display(primaryId, KWebScreenGeometry(0, 0, 100, 100)))
        }
        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            topology(primary, display(secondaryId, KWebScreenGeometry(1600, 0, 100, 100), primary = true))
        }
        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            KWebScreenTopology(
                providerId = "screen.test",
                session = KWebScreenSession.X11,
                displays = emptyList(),
            )
        }
        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            KWebScreenTopology(
                providerId = "screen.test",
                session = KWebScreenSession.X11,
                displays = listOf(primary),
                sequence = 0uL,
            )
        }
    }

    @Test
    fun topologyReportsTheFactsThatMoved() {
        val primary = display(primaryId, KWebScreenGeometry(0, 0, 1600, 1200), primary = true)
        val secondary = display(secondaryId, KWebScreenGeometry(1600, 0, 1280, 1024))
        val before = topology(primary, secondary)

        assertEquals(emptySet(), before.changedFacts(before.atSequence(2uL)))
        assertEquals(
            setOf(KWebScreenChangeFact.GEOMETRY),
            before.changedFacts(
                topology(
                    display(primaryId, KWebScreenGeometry(0, 0, 1600, 1200), primary = true),
                    display(secondaryId, KWebScreenGeometry(1600, 0, 1920, 1080)),
                ),
            ),
        )
        assertEquals(
            setOf(KWebScreenChangeFact.DISPLAYS),
            before.changedFacts(topology(primary)),
        )
        assertEquals(
            setOf(KWebScreenChangeFact.PRIMARY),
            before.changedFacts(
                topology(
                    display(primaryId, KWebScreenGeometry(0, 0, 1600, 1200)),
                    display(secondaryId, KWebScreenGeometry(1600, 0, 1280, 1024), primary = true),
                ),
            ),
        )
    }

    @Test
    fun topologyEventsRequireAReportedChangeAndKeepTheirSequence() {
        val snapshot = topology(display(primaryId, KWebScreenGeometry(0, 0, 800, 600), primary = true))
        val event = KWebScreenEvent.TopologyChanged(
            topology = snapshot.atSequence(4uL),
            added = setOf(secondaryId),
            removed = emptySet(),
            changed = emptySet(),
            sequence = 4uL,
        )
        assertEquals(4uL, event.sequence)
        assertEquals(setOf(secondaryId), event.added)
        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            KWebScreenEvent.TopologyChanged(
                topology = snapshot,
                added = emptySet(),
                removed = emptySet(),
                changed = emptySet(),
                sequence = 1uL,
            )
        }
        assertEquals(2uL, KWebScreenEvent.Failed(KWebScreenErrorCode.NATIVE_FAILED, 2uL).sequence)
    }

    @Test
    fun capabilitiesDeclareOnlySupportedFactsAndSpaces() {
        val capabilities = KWebScreenCapabilities(
            providerId = "screen.linux.x11-randr",
            session = KWebScreenSession.X11,
            capabilities = setOf(
                KWebScreenCapability.WORK_AREA,
                KWebScreenCapability.REFRESH_RATE,
                KWebScreenCapability.CURSOR,
                KWebScreenCapability.TOPOLOGY_EVENTS,
            ),
            spaces = setOf(KWebScreenSpace.LOGICAL, KWebScreenSpace.NATIVE),
        )
        assertTrue(capabilities.supports(KWebScreenCapability.CURSOR))
        assertTrue(!capabilities.supports(KWebScreenCapability.SCALE))
        assertTrue(capabilities.supports(KWebScreenSpace.NATIVE))
        assertTrue(!capabilities.supports(KWebScreenSpace.PHYSICAL))

        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            KWebScreenCapabilities(
                providerId = " ",
                session = KWebScreenSession.X11,
                capabilities = emptySet(),
                spaces = setOf(KWebScreenSpace.LOGICAL, KWebScreenSpace.NATIVE),
            )
        }
        // Every session describes each display in logical and native coordinates.
        expectedError(KWebScreenErrorCode.TOPOLOGY_INVALID) {
            KWebScreenCapabilities(
                providerId = "screen.linux.x11-randr",
                session = KWebScreenSession.X11,
                capabilities = emptySet(),
                spaces = setOf(KWebScreenSpace.LOGICAL),
            )
        }
    }

    @Test
    fun descriptorPublishesHostOperationsAndReadOnlyRendererOperations() {
        val descriptor = KWebScreens.DESCRIPTOR
        assertEquals("screen", descriptor.id)
        assertEquals("1.0.0", descriptor.version.toString())
        assertEquals(KWebServiceScope.APPLICATION, descriptor.scope)
        assertEquals(
            setOf(
                "capabilities",
                "convert",
                "cursor",
                "display-at",
                "events",
                "nearest",
                "read",
                "subscribe",
                "topology",
            ),
            descriptor.operations.map { it.id }.toSet(),
        )
        val rendererOperations = descriptor.operations.filter { it.rendererPermission != null }
        assertEquals(setOf("read", "subscribe"), rendererOperations.map { it.id }.toSet())
        rendererOperations.forEach { operation ->
            assertTrue(operation.rendererPermission!!.startsWith("native.screen."))
            // The renderer reads and subscribes; it can never mutate the desktop.
            assertTrue(!operation.requiresUserGesture)
            assertTrue(!operation.requiresOsConsent)
        }
        assertEquals(
            setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
            descriptor.supportedTargets,
        )
        assertEquals("screen", KWebScreens.Key.id)
    }

    @Test
    fun errorCodesAreStableScreenCodes() {
        val codes = listOf(
            KWebScreenErrorCode.PLATFORM_UNAVAILABLE,
            KWebScreenErrorCode.TOPOLOGY_UNAVAILABLE,
            KWebScreenErrorCode.TOPOLOGY_INVALID,
            KWebScreenErrorCode.DISPLAY_ID_INVALID,
            KWebScreenErrorCode.DISPLAY_NAME_INVALID,
            KWebScreenErrorCode.DISPLAY_UNKNOWN,
            KWebScreenErrorCode.GEOMETRY_INVALID,
            KWebScreenErrorCode.POINT_INVALID,
            KWebScreenErrorCode.SCALE_INVALID,
            KWebScreenErrorCode.REFRESH_INVALID,
            KWebScreenErrorCode.COLOR_DEPTH_INVALID,
            KWebScreenErrorCode.PHYSICAL_SIZE_INVALID,
            KWebScreenErrorCode.SPACE_UNSUPPORTED,
            KWebScreenErrorCode.CURSOR_UNAVAILABLE,
            KWebScreenErrorCode.SESSION_UNSUPPORTED,
            KWebScreenErrorCode.NATIVE_FAILED,
            KWebScreenErrorCode.OWNER_CLOSED,
        )
        assertEquals(codes.size, codes.toSet().size)
        codes.forEach { code ->
            assertTrue(code.startsWith("screen."), code)
            assertTrue(Regex("screen\\.[a-z]+(?:-[a-z]+)*").matches(code), code)
        }
        assertEquals(64, KWEB_SCREEN_EVENT_REPLAY)
    }
}
