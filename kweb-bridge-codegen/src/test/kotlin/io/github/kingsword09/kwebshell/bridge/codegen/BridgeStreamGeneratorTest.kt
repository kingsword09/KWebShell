package io.github.kingsword09.kwebshell.bridge.codegen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BridgeStreamGeneratorTest {
    private val base = """
        {
          "namespace": "StreamBridge",
          "kotlinPackage": "io.github.kwebshell.test.stream",
          "version": 1,
          "types": [
            {"name": "StartRequest", "fields": [{"name": "total", "type": "int32"}]},
            {"name": "ProgressChunk", "fields": [{"name": "done", "type": "int32"}]},
            {"name": "NoopResponse", "fields": [{"name": "ok", "type": "boolean"}]}
          ],
          "methods": [
            {"name": "noop", "request": "StartRequest", "response": "NoopResponse"}
          ],
          "streams": %STREAMS%
        }
    """.trimIndent()

    private fun schema(streamsJson: String): String = base.replace("%STREAMS%", streamsJson)

    private val eventsOnly = """
        [
          {"name": "streamProgress", "request": "StartRequest", "chunk": "ProgressChunk",
           "binary": false, "capacity": 32, "schemaVersion": 1}
        ]
    """.trimIndent()

    private val bytesOnly = """
        [
          {"name": "streamBytes", "request": "StartRequest", "binary": true,
           "capacity": 8, "schemaVersion": 1, "maxAggregateBytes": 1048576}
        ]
    """.trimIndent()

    @Test
    fun generatesEventsOnlyStreams() {
        val sources = BridgeGenerator().generate(schema(eventsOnly))
        // The Kotlin dispatcher implements the stream contract with a credit gate.
        assertTrue(sources.kotlin.contains("class StreamBridgeStreamDispatcher"))
        assertTrue(sources.kotlin.contains(": KWebStreamBridgeDispatcher"))
        assertTrue(sources.kotlin.contains("fun streamProgress(request: StartRequest): kotlinx.coroutines.flow.Flow<ProgressChunk>"))
        assertTrue(sources.kotlin.contains("gate.acquire()"))
        assertTrue(sources.kotlin.contains("\"streamProgressAck\""))
        // TypeScript exposes an AsyncIterable stream with close and explicit options.
        assertTrue(sources.typescript.contains("KWebBridgeStream<ProgressChunk>"))
        assertTrue(sources.typescript.contains("KWebBridgeStreamCallOptions"))
        assertTrue(sources.typescript.contains("openStream<ProgressChunk>(\"streamProgress\", \"streamProgressAck\", request, 32, false, null, options)"))
        // Browser JavaScript drives the persistent query with credit acknowledgements.
        assertTrue(sources.browserJavascript.contains("openStream(\"streamProgress\", \"streamProgressAck\", request, 32, false, null, options)"))
        assertTrue(sources.browserJavascript.contains("persistent: true"))
        assertTrue(sources.browserJavascript.contains("bridge.stream.sequence-invalid"))
    }

    @Test
    fun generatesBinaryChunkStreams() {
        val sources = BridgeGenerator().generate(schema(bytesOnly))
        assertTrue(sources.kotlin.contains("fun streamBytes(request: StartRequest): kotlinx.coroutines.flow.Flow<ByteArray>"))
        assertTrue(sources.kotlin.contains("java.util.Base64.getEncoder().encodeToString(chunk)"))
        assertTrue(sources.typescript.contains("KWebBridgeStream<{ bytes: string }>"))
        assertTrue(sources.typescript.contains("bridge.stream.aggregate-exceeded"))
        assertTrue(sources.browserJavascript.contains("bridge.stream.aggregate-exceeded"))
    }

    @Test
    fun streamsWithoutStreamsGenerateNoStreamSurface() {
        val sources = BridgeGenerator().generate(schema("[]"))
        assertTrue(!sources.kotlin.contains("StreamDispatcher"))
        assertTrue(!sources.typescript.contains("KWebBridgeStream<"))
        assertTrue(!sources.browserJavascript.contains("openStream"))
    }

    @Test
    fun streamGenerationIsByteForByteDeterministic() {
        val first = BridgeGenerator().generate(schema(eventsOnly))
        val second = BridgeGenerator().generate(schema(eventsOnly))
        assertEquals(first.kotlin, second.kotlin)
        assertEquals(first.typescript, second.typescript)
        assertEquals(first.browserJavascript, second.browserJavascript)
    }

    @Test
    fun invalidStreamSchemasFail() {
        val generator = BridgeGenerator()
        // Unknown chunk type.
        val unknownChunk = schema("""[{"name":"s","request":"StartRequest","chunk":"Missing","capacity":8,"schemaVersion":1}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(unknownChunk) }
        // Capacity out of range.
        val badCapacity = schema("""[{"name":"s","request":"StartRequest","chunk":"ProgressChunk","capacity":0,"schemaVersion":1}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(badCapacity) }
        // Unsupported stream schema version.
        val badVersion = schema("""[{"name":"s","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":2}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(badVersion) }
        // Stream name collides with a method.
        val collision = schema("""[{"name":"noop","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(collision) }
        // Non-binary stream with aggregate bound.
        val mixed = schema("""[{"name":"s","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1,"maxAggregateBytes":10}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(mixed) }
        // Unknown schema fields are rejected.
        val unknownField = schema("""[{"name":"s","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1,"extra":1}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(unknownField) }
    }

    @Test
    fun streamMethodAndAckNamesAreValidated() {
        val generator = BridgeGenerator()
        // Ack name exceeding the method name bound.
        val longName = schema("""[{"name":"${"s".repeat(62)}","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1}]""")
        assertFailsWith<IllegalArgumentException> { generator.generate(longName) }
        // Duplicate streams fail.
        val duplicated = schema(
            """
            [
              {"name":"streamOne","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1},
              {"name":"streamOne","request":"StartRequest","chunk":"ProgressChunk","capacity":8,"schemaVersion":1}
            ]
            """.trimIndent(),
        )
        assertFailsWith<IllegalArgumentException> { generator.generate(duplicated) }
    }
}
