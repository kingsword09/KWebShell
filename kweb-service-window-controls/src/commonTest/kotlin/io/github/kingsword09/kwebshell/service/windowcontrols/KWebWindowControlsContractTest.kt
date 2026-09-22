package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebWindowControlsContractTest {
    @Test
    fun descriptorPublishesTheVersionTwoOperationSet() {
        assertEquals("window-controls", KWebWindowControls.DESCRIPTOR.id)
        assertEquals("2.0.0", KWebWindowControls.DESCRIPTOR.version.toString())
        assertEquals(
            setOf(
                "get-state", "set-title", "set-bounds", "set-constraints", "set-visible", "focus",
                "minimize", "restore", "set-maximized", "set-fullscreen", "set-movable",
                "set-minimizable", "set-maximizable", "set-closable", "set-always-on-top",
                "set-resizable", "request-attention", "clear-attention", "request-close",
            ),
            KWebWindowControls.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assertTrue(KWebWindowControls.DESCRIPTOR.operations.single { it.id == "request-close" }.requiresUserGesture)
    }

    @Test
    fun boundsAndPlacementAreStrict() {
        assertEquals(KWebWindowPlacement.MAXIMIZED, KWebWindowPlacement.fromId("maximized"))
        assertFailsWith<KWebConfigurationException> { KWebWindowPlacement.fromId("tiled") }
        assertFailsWith<KWebConfigurationException> { KWebWindowBounds(0, 0, 0, 100) }
        assertEquals(640, KWebWindowBounds(12, 24, 640, 480).width)
        assertFailsWith<KWebConfigurationException> { KWebWindowConstraints(800, 600, 799, null) }
        assertFailsWith<KWebConfigurationException> { KWebWindowRegistration("") }
        assertEquals(KWebWindowModality.WINDOW_MODAL, KWebWindowModality.fromId("window-modal"))
        assertEquals(KWebWindowFullscreenMode.KIOSK, KWebWindowFullscreenMode.fromId("kiosk"))
    }
}
