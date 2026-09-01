package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebWindowControlsContractTest {
    @Test
    fun descriptorPublishesOnlyTheClosedOperationSet() {
        assertEquals("window-controls", KWebWindowControls.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebWindowControls.DESCRIPTOR.version.toString())
        assertEquals(
            setOf(
                "get-state", "set-title", "set-bounds", "set-visible", "focus",
                "minimize", "restore", "set-maximized", "set-always-on-top", "set-resizable",
            ),
            KWebWindowControls.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assertTrue(KWebWindowControls.DESCRIPTOR.operations.none { it.requiresUserGesture })
    }

    @Test
    fun boundsAndPlacementAreStrict() {
        assertEquals(KWebWindowPlacement.MAXIMIZED, KWebWindowPlacement.fromId("maximized"))
        assertFailsWith<KWebConfigurationException> { KWebWindowPlacement.fromId("tiled") }
        assertFailsWith<KWebConfigurationException> { KWebWindowBounds(0, 0, 0, 100) }
        assertEquals(640, KWebWindowBounds(12, 24, 640, 480).width)
    }
}
