package io.github.kingsword09.kwebshell.example.benchmark

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.GraphicsConfiguration
import java.awt.Point
import java.awt.Robot
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
        val x = probe.requiredPositive("x")
        val y = probe.requiredPositive("y")
        val width = probe.requiredPositive("width")
        val height = probe.requiredPositive("height")
        val origin = AtomicReference<Point>()
        val graphics = AtomicReference<GraphicsConfiguration>()
        SwingUtilities.invokeAndWait {
            window.toFront()
            origin.set(window.contentPane.locationOnScreen)
            graphics.set(window.graphicsConfiguration)
        }
        val contentOrigin = origin.get()
            ?: throw BenchmarkException("frame.window-origin-missing", "The benchmark content origin is missing.")
        val device = graphics.get()?.device
            ?: throw BenchmarkException("frame.graphics-device-missing", "The benchmark graphics device is missing.")
        val sampleX = contentOrigin.x + x + width / 2
        val sampleY = contentOrigin.y + y + height / 2
        val transitions = mutableListOf<Long>()
        val robot = Robot(device)
        var previous = robot.getPixelColor(sampleX.toInt(), sampleY.toInt()).rgb
        val deadline = System.nanoTime() + durationMs * 1_000_000L
        try {
            while (System.nanoTime() < deadline) {
                val color = robot.getPixelColor(sampleX.toInt(), sampleY.toInt()).rgb
                if (color != previous) {
                    transitions += System.nanoTime()
                    previous = color
                }
                Thread.sleep(2L)
            }
        } finally {
            session.evaluate("window.kwebBenchmark.stopPresentProbe()")
        }
        if (transitions.size < 8) {
            throw BenchmarkException(
                "frame.presented-count-low",
                "The visible native child produced only ${transitions.size} sampled frame transitions.",
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
