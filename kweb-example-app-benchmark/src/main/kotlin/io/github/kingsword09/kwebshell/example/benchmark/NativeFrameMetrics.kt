package io.github.kingsword09.kwebshell.example.benchmark

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

internal data class NativeFrameMetrics(
    val frameCount: Double,
    val medianIntervalMs: Double,
    val p95IntervalMs: Double,
    val worstIntervalMs: Double,
)

internal data class NativeCompositorFrame(
    val sessionId: Int,
    val timestampMs: Double,
)

internal object NativeFrameMetricsSampler {
    fun sample(
        window: ComposeWindow,
        session: KWebExampleCdpSession,
        durationMs: Long = 450L,
    ): NativeFrameMetrics {
        require(durationMs >= 250L) { "Native frame sampling requires at least 250 ms." }
        requireVisibleNativeWindow(window)
        session.command(
            "Page.startScreencast",
            buildJsonObject {
                put("format", "jpeg")
                put("quality", 35)
                put("maxWidth", 256)
                put("maxHeight", 256)
                put("everyNthFrame", 1)
            },
        )
        var probeRunning = false
        var samplingFailure: Throwable? = null
        try {
            val probe = session.evaluate("window.kwebBenchmark.startPresentProbe()")
                .value?.jsonObject
                ?: throw BenchmarkException("frame.probe-result-missing", "The page returned no present-probe bounds.")
            probe.requiredPositive("width")
            probe.requiredPositive("height")
            probeRunning = true
            session.evaluate("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")

            val timestamps = mutableListOf<Double>()
            val deadline = System.nanoTime() + durationMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                val remainingMs = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1L)
                val event = session.pollEvent("Page.screencastFrame", minOf(remainingMs, EVENT_POLL_MS)) ?: continue
                val frame = NativeCompositorFrameParser.parse(event)
                session.command(
                    "Page.screencastFrameAck",
                    buildJsonObject { put("sessionId", frame.sessionId) },
                )
                if (timestamps.isNotEmpty() && frame.timestampMs <= timestamps.last()) {
                    throw BenchmarkException(
                        "frame.timestamp-not-monotonic",
                        "Chromium compositor frame timestamps are not strictly increasing.",
                    )
                }
                timestamps += frame.timestampMs
            }
            if (timestamps.size < MINIMUM_FRAME_COUNT) {
                throw BenchmarkException(
                    "frame.presented-count-low",
                    "The visible native child produced only ${timestamps.size} Chromium compositor frame events.",
                )
            }
            val intervals = timestamps.zipWithNext { first, second -> second - first }
            return NativeFrameMetrics(
                frameCount = timestamps.size.toDouble(),
                medianIntervalMs = percentile(intervals, 0.5),
                p95IntervalMs = percentile(intervals, 0.95),
                worstIntervalMs = intervals.max(),
            )
        } catch (error: Throwable) {
            samplingFailure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            if (probeRunning) runCleanup(cleanupFailures) { session.evaluate("window.kwebBenchmark.stopPresentProbe()") }
            runCleanup(cleanupFailures) { session.command("Page.stopScreencast") }
            if (samplingFailure != null) {
                cleanupFailures.forEach(samplingFailure::addSuppressed)
            } else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun requireVisibleNativeWindow(window: ComposeWindow) {
        val handle = AtomicLong(0L)
        SwingUtilities.invokeAndWait {
            if (!window.isShowing || !window.isDisplayable) {
                throw BenchmarkException("frame.window-not-visible", "The benchmark window is not visible for frame sampling.")
            }
            window.toFront()
            window.requestFocus()
            handle.set(window.windowHandle)
        }
        if (handle.get() == 0L) {
            throw BenchmarkException("frame.native-handle-missing", "The visible benchmark window has no native handle.")
        }
    }

    private fun JsonObject.requiredPositive(name: String): Double =
        get(name)?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0.0 }
            ?: throw BenchmarkException("frame.probe-bounds-invalid", "Present-probe bound '$name' is invalid.")

    private fun runCleanup(target: MutableList<Throwable>, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            target += error
        }
    }

    private const val EVENT_POLL_MS: Long = 100L
    private const val MINIMUM_FRAME_COUNT: Int = 8
}

internal object NativeCompositorFrameParser {
    fun parse(event: JsonObject): NativeCompositorFrame {
        val sessionId = (event["sessionId"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 }
            ?: throw BenchmarkException("frame.session-id-invalid", "Chromium compositor frame sessionId is invalid.")
        val timestampMs = ((event["metadata"] as? JsonObject)?.get("timestamp") as? JsonPrimitive)
            ?.doubleOrNull
            ?.takeIf { it.isFinite() && it > 0.0 }
            ?.times(1000.0)
            ?.takeIf(Double::isFinite)
            ?: throw BenchmarkException("frame.timestamp-invalid", "Chromium compositor frame timestamp is invalid.")
        val encoded = (event["data"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw BenchmarkException("frame.data-missing", "Chromium compositor frame data is missing.")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw BenchmarkException("frame.data-invalid", "Chromium compositor frame data is not valid Base64.", error)
        }
        if (bytes.size < 3 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte() || bytes[2] != 0xFF.toByte()) {
            throw BenchmarkException("frame.jpeg-invalid", "Chromium compositor frame data is not a JPEG image.")
        }
        return NativeCompositorFrame(sessionId, timestampMs)
    }
}
