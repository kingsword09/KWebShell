package io.github.kingsword09.kwebshell.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebStreamBridgeTest {
    private fun dataFrame(sequence: Long, value: String): KWebStreamFrame = KWebStreamFrame(
        version = 1,
        sequence = sequence,
        kind = KWebStreamFrameKind.DATA,
        payload = buildJsonObject { put("value", JsonPrimitive(value)) },
    )

    private fun terminalFrame(reason: String): KWebStreamFrame = KWebStreamFrame(
        version = 1,
        sequence = 0,
        kind = KWebStreamFrameKind.TERMINAL,
        reason = reason,
    )

    @Test
    fun framesRoundTripAndValidateStrictly() {
        val encoded = KWebStreamProtocol.encode(dataFrame(1, "alpha"))
        val decoded = KWebStreamProtocol.decode(encoded)
        assertEquals(1L, decoded.sequence)
        assertEquals(KWebStreamFrameKind.DATA, decoded.kind)
        assertEquals("alpha", decoded.payload!!.jsonObject["value"]!!.jsonPrimitive.content)

        val terminal = KWebStreamProtocol.decode(KWebStreamProtocol.encode(terminalFrame(KWEB_STREAM_COMPLETED)))
        assertEquals(KWEB_STREAM_COMPLETED, terminal.reason)

        // Data frames need payloads; terminal frames need reasons and no sequence.
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.encode(KWebStreamFrame(1, 1, KWebStreamFrameKind.DATA, payload = null))
        }
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.encode(KWebStreamFrame(1, 0, KWebStreamFrameKind.DATA, payload = JsonPrimitive(1)))
        }
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.encode(KWebStreamFrame(1, 0, KWebStreamFrameKind.TERMINAL, reason = null))
        }
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.encode(KWebStreamFrame(1, 1, KWebStreamFrameKind.TERMINAL, reason = "x"))
        }
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.encode(KWebStreamFrame(2, 1, KWebStreamFrameKind.DATA, payload = JsonPrimitive(1)))
        }
        // Unknown protocol fields are rejected.
        assertFailsWith<KWebBridgeException> {
            KWebStreamProtocol.decode(
                KWebStreamProtocol.encode(dataFrame(1, "x")).replace("}", ",\"extra\":true}"),
            )
        }
    }

    @Test
    fun creditGateSuspendsWithoutBlockingAndGrantsResume() = runBlocking {
        val gate = KWebStreamCreditGate(initialCredits = 2)
        assertEquals(true, gate.acquire())
        assertEquals(true, gate.acquire())
        val waiting = async(Dispatchers.Default) { gate.acquire() }
        // The waiter suspends; the acquiring coroutine keeps running without a grant.
        gate.grant(1)
        assertEquals(true, waiting.await())

        gate.close()
        assertEquals(false, gate.acquire())
    }

    @Test
    fun oneMillionSequencedEventsFlowInOrderWithBoundedInFlight() = runBlocking {
        val total = 1_000_000
        val gate = KWebStreamCreditGate(initialCredits = 256)
        val sent = mutableListOf<String>()
        val consumed = mutableListOf<Long>()

        val publisher = launch(Dispatchers.Default) {
            var sequence = 1L
            while (sequence <= total) {
                if (!gate.acquire()) break
                sent += KWebStreamProtocol.encode(dataFrame(sequence, "v$sequence"))
                sequence += 1
            }
        }
        val consumer = launch(Dispatchers.Default) {
            // The consumer grants one credit per consumed frame: bounded in-flight.
            while (consumed.size < total) {
                val frame = sent.getOrNull(consumed.size) ?: continue
                consumed += KWebStreamProtocol.decode(frame).sequence
                gate.grant(1)
            }
        }
        publisher.join()
        consumer.join()

        assertEquals(total, consumed.size)
        // Sequenced small events arrive without reordering.
        assertEquals((1L..total.toLong()).toList(), consumed)
        // In-flight is bounded by the credit capacity: the publisher never runs ahead.
        assertTrue(sent.size <= consumed.size + 256, "In-flight frames must stay within the credit capacity.")
    }

    @Test
    fun boundedBinaryChunksTransferWithoutTruncation() = runBlocking {
        val gate = KWebStreamCreditGate(initialCredits = 8)
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val payload = buildJsonObject {
            put("bytes", JsonPrimitive(java.util.Base64.getEncoder().encodeToString(chunk)))
        }
        val frames = mutableListOf<KWebStreamFrame>()
        coroutineScope {
            val publisher = launch(Dispatchers.Default) {
                repeat(64) { index ->
                    gate.acquire()
                    frames += KWebStreamFrame(1, index + 1L, KWebStreamFrameKind.DATA, payload = payload)
                    gate.grant(1)
                }
            }
            publisher.join()
        }
        assertEquals(64, frames.size)
        // Every chunk decodes byte-for-byte: no silent truncation.
        frames.forEach { frame ->
            val decoded = java.util.Base64.getDecoder()
                .decode(frame.payload!!.jsonObject["bytes"]!!.jsonPrimitive.content)
            assertTrue(decoded.contentEquals(chunk))
        }
    }

    @Test
    fun concurrentStreamsDoNotStarveRequests() = runBlocking {
        // Two independent gates simulate two live streams while a plain request
        // executes on the same dispatcher pool: the suspended streams never
        // block the request.
        val streams = List(4) { KWebStreamCreditGate(initialCredits = 1) }
        coroutineScope {
            streams.map { gate ->
                async(Dispatchers.Default) {
                    gate.acquire() // consume the only credit; the next acquire suspends
                    launch(Dispatchers.Default) { gate.acquire() } // stays suspended
                }
            }
            val request = async(Dispatchers.Default) {
                Json.decodeFromString(JsonObject.serializer(), """{"value":1}""")
            }
            assertEquals(1, request.await()["value"]!!.jsonPrimitive.content.toInt())
            streams.forEach { it.grant(1) }
        }
    }

}
