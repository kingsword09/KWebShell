package io.github.kingsword09.kwebshell.example.benchmark

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NativeCompositorFrameParserTest {
    @Test
    fun parsesAChromiumCompositorFrame() {
        val frame = NativeCompositorFrameParser.parse(frameEvent(timestamp = 123.456))

        assertEquals(42, frame.sessionId)
        assertEquals(123_456.0, frame.timestampMs)
    }

    @Test
    fun rejectsMissingFramePixels() {
        val event = buildJsonObject {
            put("sessionId", 42)
            put("metadata", buildJsonObject { put("timestamp", 123.456) })
        }

        val error = assertFailsWith<BenchmarkException> { NativeCompositorFrameParser.parse(event) }

        assertEquals("frame.data-missing", error.code)
    }

    @Test
    fun rejectsMalformedSessionAndTimestampEvidence() {
        val sessionError = assertFailsWith<BenchmarkException> {
            NativeCompositorFrameParser.parse(frameEvent(sessionId = -1))
        }
        val timestampError = assertFailsWith<BenchmarkException> {
            NativeCompositorFrameParser.parse(frameEvent(timestamp = 0.0))
        }

        assertEquals("frame.session-id-invalid", sessionError.code)
        assertEquals("frame.timestamp-invalid", timestampError.code)
    }

    @Test
    fun rejectsInvalidBase64FramePixels() {
        val event = frameEvent().toMutableMap().also { fields -> fields["data"] = JsonPrimitive("not-base64") }

        val error = assertFailsWith<BenchmarkException> {
            NativeCompositorFrameParser.parse(JsonObject(event))
        }

        assertEquals("frame.data-invalid", error.code)
    }

    @Test
    fun rejectsNonJpegFramePixels() {
        val error = assertFailsWith<BenchmarkException> {
            NativeCompositorFrameParser.parse(frameEvent(bytes = byteArrayOf(1, 2, 3)))
        }

        assertEquals("frame.jpeg-invalid", error.code)
    }

    private fun frameEvent(
        sessionId: Int = 42,
        timestamp: Double = 123.456,
        bytes: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
    ) = buildJsonObject {
        put("sessionId", sessionId)
        put("metadata", buildJsonObject { put("timestamp", timestamp) })
        put("data", Base64.getEncoder().encodeToString(bytes))
    }
}
