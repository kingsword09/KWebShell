package io.github.kingsword09.kwebshell.example.benchmark

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeFrameProbeColorScannerTest {
    @Test
    fun detectsAProbeColorTransitionFromWindowPixels() {
        val cyan = image(0xFF008EAA.toInt())
        val magenta = image(0xFFBA356F.toInt())

        assertTrue(
            NativeFrameProbeColorScanner.hasTransition(
                NativeFrameProbeColorScanner.signature(cyan),
                NativeFrameProbeColorScanner.signature(magenta),
            ),
        )
    }

    @Test
    fun ignoresAnUnchangedWindowCapture() {
        val image = image(0xFF008EAA.toInt())
        val signature = NativeFrameProbeColorScanner.signature(image)

        assertFalse(NativeFrameProbeColorScanner.hasTransition(signature, signature.copyOf()))
    }

    @Test
    fun locatesProbeInWindowHeader() {
        val image = BufferedImage(240, 220, BufferedImage.TYPE_INT_ARGB)
        for (y in 82 until 100) {
            for (x in 214 until 232) image.setRGB(x, y, 0xFF008EAA.toInt())
        }

        val bounds = NativeFrameProbeColorScanner.locateCaptureBounds(image)

        assertTrue(bounds?.contains(223, 91) == true)
    }

    private fun image(color: Int): BufferedImage = BufferedImage(12, 12, BufferedImage.TYPE_INT_ARGB).also { image ->
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, color)
        }
    }
}
