package io.github.kingsword09.kwebshell.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebBridgeDispatchersTest {
    @Test
    fun dispatchesOnlyToTheExactDeclaredOwner() {
        val alpha = RecordingDispatcher("alpha")
        val beta = RecordingDispatcher("beta")
        val dispatcher = KWebBridgeDispatchers.exact(
            KWebBridgeRoute(setOf("one", "two"), alpha),
            KWebBridgeRoute(setOf("three"), beta),
        )

        assertEquals("alpha", runBlocking { dispatcher.dispatch(request("two")) })
        assertEquals("beta", runBlocking { dispatcher.dispatch(request("three")) })
        assertEquals(listOf("two"), alpha.methods)
        assertEquals(listOf("three"), beta.methods)
    }

    @Test
    fun forwardsTheOriginalRequestJsonToTheSelectedOwner() {
        val delegate = CapturingDispatcher()
        val dispatcher = KWebBridgeDispatchers.exact(KWebBridgeRoute(setOf("probe"), delegate))
        val request = "{ \"payload\": {\"value\": \"值🙂\"}, \"method\": \"probe\", \"version\": 1 }"

        assertEquals("ok", runBlocking { dispatcher.dispatch(request) })
        assertEquals(request, delegate.requestJson)
    }

    @Test
    fun rejectsEmptyInvalidAndDuplicateRouteDefinitions() {
        val delegate = RecordingDispatcher("value")
        assertFailsWith<IllegalArgumentException> { KWebBridgeRoute(emptySet(), delegate) }
        assertFailsWith<IllegalArgumentException> { KWebBridgeRoute(setOf("bad-method"), delegate) }
        assertFailsWith<IllegalArgumentException> { KWebBridgeDispatchers.exact(emptyList()) }
        val duplicate = assertFailsWith<IllegalArgumentException> {
            KWebBridgeDispatchers.exact(
                KWebBridgeRoute(setOf("same"), delegate),
                KWebBridgeRoute(setOf("same"), RecordingDispatcher("other")),
            )
        }
        assertTrue(duplicate.message.orEmpty().contains("same"))
    }

    @Test
    fun copiesRouteMethodsAndKeepsMatchingCaseSensitive() {
        val declared = linkedSetOf("alpha")
        val delegate = RecordingDispatcher("value")
        val route = KWebBridgeRoute(declared, delegate)
        declared += "mutated"

        assertEquals(setOf("alpha"), route.methods)
        val dispatcher = KWebBridgeDispatchers.exact(route)
        assertEquals("value", runBlocking { dispatcher.dispatch(request("alpha")) })
        val failure = assertFailsWith<KWebBridgeException> {
            runBlocking { dispatcher.dispatch(request("alphA")) }
        }
        assertEquals("bridge.method.unknown", failure.code)
        assertEquals(listOf("alpha"), delegate.methods)
    }

    @Test
    fun unknownMethodsFailWithoutInvokingAnotherRoute() {
        val delegate = RecordingDispatcher("value")
        val dispatcher = KWebBridgeDispatchers.exact(KWebBridgeRoute(setOf("known"), delegate))

        val failure = assertFailsWith<KWebBridgeException> {
            runBlocking { dispatcher.dispatch(request("unknown")) }
        }
        assertEquals("bridge.method.unknown", failure.code)
        assertTrue(delegate.methods.isEmpty())
    }

    @Test
    fun cancellationFromTheSelectedDispatcherIsPreserved() {
        val dispatcher = KWebBridgeDispatchers.exact(
            KWebBridgeRoute(
                setOf("wait"),
                object : KWebBridgeDispatcher {
                    override suspend fun dispatch(requestJson: String): String {
                        throw CancellationException("cancelled")
                    }
                },
            ),
        )

        assertFailsWith<CancellationException> {
            runBlocking { dispatcher.dispatch(request("wait")) }
        }
    }

    private fun request(method: String): String =
        """{"version":1,"method":"$method","payload":{}}"""

    private class RecordingDispatcher(
        private val response: String,
    ) : KWebBridgeDispatcher {
        val methods = mutableListOf<String>()

        override suspend fun dispatch(requestJson: String): String {
            methods += KWebBridgeProtocol.decodeRequest(requestJson).method
            return response
        }
    }

    private class CapturingDispatcher : KWebBridgeDispatcher {
        var requestJson: String? = null

        override suspend fun dispatch(requestJson: String): String {
            this.requestJson = requestJson
            return "ok"
        }
    }
}
