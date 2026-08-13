package io.github.kingsword09.kwebshell.bridge

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebBridgeProtocolTest {
    @Test
    fun decodesTheClosedRequestEnvelope() {
        val request = KWebBridgeProtocol.decodeRequest(
            """{"version":1,"method":"probe","payload":"值🙂"}""",
        )

        assertEquals("probe", request.method)
        assertEquals(JsonPrimitive("值🙂"), request.payload)
    }

    @Test
    fun rejectsUnknownFieldsAndVersions() {
        val unknown = assertFailsWith<KWebBridgeException> {
            KWebBridgeProtocol.decodeRequest(
                """{"version":1,"method":"probe","payload":{},"extra":true}""",
            )
        }
        assertEquals("bridge.request.unknown-field", unknown.code)

        val version = assertFailsWith<KWebBridgeException> {
            KWebBridgeProtocol.decodeRequest(
                """{"version":2,"method":"probe","payload":{}}""",
            )
        }
        assertEquals("bridge.request.version-mismatch", version.code)

        val stringVersion = assertFailsWith<KWebBridgeException> {
            KWebBridgeProtocol.decodeRequest(
                """{"version":"1","method":"probe","payload":{}}""",
            )
        }
        assertEquals("bridge.request.version-invalid", stringVersion.code)
    }

    @Test
    fun encodesTypedAndUnexpectedFailures() {
        assertEquals(
            """{"code":"probe.denied","message":"Denied."}""",
            KWebBridgeProtocol.encodeFailure(KWebBridgeException("probe.denied", "Denied.")),
        )
        assertEquals(
            """{"code":"bridge.handler.failed","message":"The bridge handler failed."}""",
            KWebBridgeProtocol.encodeFailure(IllegalStateException("Broken.")),
        )
    }
}
