package io.github.kingsword09.kwebshell.example.benchmark

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

internal data class NativeFrameMetrics(
    val frameCount: Double,
    val medianIntervalMs: Double,
    val p95IntervalMs: Double,
    val worstIntervalMs: Double,
)

internal object NativeFrameMetricsSampler {
    fun sample(
        window: ComposeWindow,
        session: KWebExampleCdpSession,
        durationMs: Long = 450L,
    ): NativeFrameMetrics {
        require(durationMs >= 250L) { "Native frame sampling requires at least 250 ms." }
        val probe = session.evaluate("window.kwebBenchmark.startPresentProbe()")
            .value?.jsonObject
            ?: throw BenchmarkException("frame.probe-result-missing", "The page returned no present-probe bounds.")
        val width = probe.requiredPositive("width")
        val height = probe.requiredPositive("height")
        val bounds = AtomicReference<Rectangle>()
        val contentOrigin = AtomicReference<Point>()
        val graphics = AtomicReference<GraphicsConfiguration>()
        SwingUtilities.invokeAndWait {
            window.toFront()
            window.requestFocus()
            if (!window.isShowing || !window.isDisplayable) {
                throw BenchmarkException("frame.window-not-visible", "The benchmark window is not visible for frame sampling.")
            }
            bounds.set(Rectangle(window.locationOnScreen, window.size))
            contentOrigin.set(window.contentPane.locationOnScreen)
            graphics.set(window.graphicsConfiguration)
        }
        if (width <= 0.0 || height <= 0.0) {
            throw BenchmarkException("frame.probe-bounds-invalid", "Present-probe bounds are not positive.")
        }
        val captureBounds = bounds.get()
            ?: throw BenchmarkException("frame.window-bounds-missing", "The benchmark window bounds are missing.")
        if (captureBounds.width <= 0 || captureBounds.height <= 0) {
            throw BenchmarkException("frame.window-bounds-invalid", "The benchmark window bounds are not positive.")
        }
        val device = graphics.get()?.device
            ?: throw BenchmarkException("frame.graphics-device-missing", "The benchmark graphics device is missing.")
        val robot = Robot(device)
        val initialCapture = robot.createScreenCapture(captureBounds)
        val content = contentOrigin.get()
            ?: throw BenchmarkException("frame.content-origin-missing", "The benchmark content origin is missing.")
        val expectedProbe = Rectangle(
            content.x - captureBounds.x + probe.requiredPositive("x").toInt(),
            content.y - captureBounds.y + probe.requiredPositive("y").toInt(),
            width.toInt().coerceAtLeast(1),
            height.toInt().coerceAtLeast(1),
        )
        val probeCaptureBounds = NativeFrameProbeColorScanner.locateCaptureBounds(
            initialCapture,
            expectedProbe,
            captureBounds.width.takeIf { it > 0 }?.let { initialCapture.width.toDouble() / it } ?: 1.0,
            captureBounds.height.takeIf { it > 0 }?.let { initialCapture.height.toDouble() / it } ?: 1.0,
        )?.let { local ->
            Rectangle(captureBounds.x + local.x, captureBounds.y + local.y, local.width, local.height)
        }
            ?: throw BenchmarkException(
                "frame.probe-not-visible",
                "The visible native child did not expose the present probe in the screen capture.",
            )
        val transitions = mutableListOf<Long>()
        var previous = NativeFrameProbeColorScanner.signature(robot.createScreenCapture(probeCaptureBounds))
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        try {
            while (System.nanoTime() < deadline) {
                val current = NativeFrameProbeColorScanner.signature(robot.createScreenCapture(probeCaptureBounds))
                if (NativeFrameProbeColorScanner.hasTransition(previous, current)) {
                    transitions += System.nanoTime()
                }
                previous = current
                Thread.sleep(2L)
            }
        } finally {
            session.evaluate("window.kwebBenchmark.stopPresentProbe()")
        }
        if (transitions.size < 8) {
            throw BenchmarkException(
                "frame.presented-count-low",
                "The visible native child produced only ${transitions.size} sampled probe-color transitions.",
            )
        }
        val intervals = transitions.zipWithNext { first, second -> (second - first) / 1_000_000.0 }
        return NativeFrameMetrics(
            frameCount = transitions.size.toDouble(),
            medianIntervalMs = percentile(intervals, 0.5),
            p95IntervalMs = percentile(intervals, 0.95),
            worstIntervalMs = intervals.max(),
        )
    }

    private fun kotlinx.serialization.json.JsonObject.requiredPositive(name: String): Double =
        get(name)?.jsonPrimitive?.doubleOrNull?.takeIf { it >= 0.0 }
            ?: throw BenchmarkException("frame.probe-bounds-invalid", "Present-probe bound '$name' is invalid.")
}

internal object NativeFrameProbeColorScanner {
    private val probeColors = intArrayOf(0xFF008EAA.toInt(), 0xFFBA356F.toInt(), 0xFFD5A900.toInt(), 0xFF20231F.toInt())

    fun signature(image: java.awt.image.BufferedImage): IntArray {
        val counts = IntArray(probeColors.size)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                when (image.getRGB(x, y)) {
                    probeColors[0] -> counts[0] += 1
                    probeColors[1] -> counts[1] += 1
                    probeColors[2] -> counts[2] += 1
                    probeColors[3] -> counts[3] += 1
                }
            }
        }
        return counts
    }

    fun locateCaptureBounds(
        image: BufferedImage,
        expected: Rectangle? = null,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
    ): Rectangle? {
        require(scaleX > 0.0 && scaleY > 0.0)
        val centerX = expected?.let { ((it.x + it.width / 2) * scaleX).toInt() }
        val centerY = expected?.let { ((it.y + it.height / 2) * scaleY).toInt() }
        val searchLeft = (centerX?.minus(80) ?: (image.width - 180)).coerceAtLeast(0)
        val searchTop = (centerY?.minus(80) ?: 0).coerceAtLeast(0)
        val searchRight = (centerX?.plus(80) ?: image.width).coerceAtMost(image.width)
        val searchBottom = (centerY?.plus(80) ?: image.height.coerceAtMost(190)).coerceAtMost(image.height)
        var minX = image.width
        var minY = image.height
        var maxX = -1
        var maxY = -1
        for (y in searchTop until searchBottom) {
            for (x in searchLeft until searchRight) {
                if (isProbeColor(image.getRGB(x, y))) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val left = (minX - 2).coerceAtLeast(0)
        val top = (minY - 2).coerceAtLeast(0)
        val right = (maxX + 3).coerceAtMost(image.width)
        val bottom = (maxY + 3).coerceAtMost(image.height)
        return Rectangle(left, top, right - left, bottom - top)
    }

    fun hasTransition(previous: IntArray, current: IntArray): Boolean {
        require(previous.size == probeColors.size && current.size == probeColors.size)
        return previous.indices.sumOf { index -> kotlin.math.abs(previous[index] - current[index]) } >= MINIMUM_COLOR_DELTA
    }

    private fun isProbeColor(pixel: Int): Boolean = probeColors.any { expected ->
        val red = (pixel ushr 16) and 0xff
        val green = (pixel ushr 8) and 0xff
        val blue = pixel and 0xff
        val expectedRed = (expected ushr 16) and 0xff
        val expectedGreen = (expected ushr 8) and 0xff
        val expectedBlue = expected and 0xff
        kotlin.math.abs(red - expectedRed) <= COLOR_TOLERANCE &&
            kotlin.math.abs(green - expectedGreen) <= COLOR_TOLERANCE &&
            kotlin.math.abs(blue - expectedBlue) <= COLOR_TOLERANCE
    }

    private const val MINIMUM_COLOR_DELTA: Int = 64
    private const val COLOR_TOLERANCE: Int = 24
}
