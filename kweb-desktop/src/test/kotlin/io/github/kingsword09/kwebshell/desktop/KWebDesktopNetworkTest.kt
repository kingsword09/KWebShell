package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNetworkHeaderMutation
import io.github.kingsword09.kwebshell.core.KWebNetworkPolicy
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestPhase
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestEvent
import io.github.kingsword09.kwebshell.core.KWebNetworkCompletionStatus
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebNetworkResourceType
import io.github.kingsword09.kwebshell.core.KWebNetworkRule
import io.github.kingsword09.kwebshell.core.KWebNetworkRuleAction
import io.github.kingsword09.kwebshell.core.KWebProxyConfiguration
import io.github.kingsword09.kwebshell.core.KWebProxyMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KWebDesktopNetworkTest {
    @Test
    fun serializesBoundedPolicyAndParsesBodyFreeObservation() {
        val policy = KWebNetworkPolicy(
            rules = listOf(
                KWebNetworkRule(
                    id = "rewrite-api",
                    urlPattern = "https://api.example.test/*",
                    resourceTypes = setOf(KWebNetworkResourceType.XHR),
                    methods = setOf("GET"),
                    priority = 10,
                    action = KWebNetworkRuleAction.REDIRECT,
                    redirectUrl = "https://api.example.test/v2",
                    headerMutations = listOf(KWebNetworkHeaderMutation("X-KWeb", "policy-1")),
                ),
            ),
            proxy = KWebProxyConfiguration(
                mode = KWebProxyMode.PAC,
                pacUrl = "https://proxy.example.test/pac.js",
                pacMandatory = true,
            ),
            userAgent = "KWebShellTest/1",
            acceptLanguage = "zh-CN,zh;q=0.9",
        )

        val payload = KWebDesktopNetworkJson.policyPayload(policy)
        val root = Json.parseToJsonElement(payload).jsonObject
        assertEquals(1, root["version"]?.jsonPrimitive?.content?.toInt())
        assertEquals(
            "redirect",
            root["rules"]?.jsonArray?.single()?.jsonObject?.get("action")?.jsonPrimitive?.content,
        )
        assertEquals("pac", root["proxy"]?.jsonObject?.get("mode")?.jsonPrimitive?.content)

        val event = KWebDesktopNetworkJson.parseEvent(
            """
            {"requestId":"41","phase":"complete","url":"https://api.example.test/v2","method":"GET",
             "resourceType":"xhr","action":"allow","statusCode":200,
             "completionStatus":"success","policyVersion":1}
            """.trimIndent(),
        )
        assertEquals(41L, event.requestId)
        assertEquals(KWebNetworkRequestPhase.COMPLETE, event.phase)
        assertEquals(200, event.statusCode)
        assertEquals(KWebNetworkCompletionStatus.SUCCESS, event.completionStatus)
        assertTrue(event.redirectedUrl == null)
    }

    @Test
    fun rejectsInvalidPolicyBeforeNativeBoundary() {
        val invalidVersion = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(KWebNetworkPolicy(version = 2))
        }
        assertEquals("network.policy.invalid", invalidVersion.code)

        val forbiddenHeader = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    rules = listOf(
                        KWebNetworkRule(
                            id = "forbidden",
                            urlPattern = "https://example.test/*",
                            headerMutations = listOf(KWebNetworkHeaderMutation("Host", "evil.test")),
                        ),
                    ),
                ),
            )
        }
        assertEquals("network.header.forbidden", forbiddenHeader.code)

        val malformedPattern = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    rules = listOf(
                        KWebNetworkRule(
                            id = "malformed-pattern",
                            urlPattern = "https://user:secret@example.test/*",
                        ),
                    ),
                ),
            )
        }
        assertEquals("network.policy.invalid", malformedPattern.code)

        val injectedHeader = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    rules = listOf(
                        KWebNetworkRule(
                            id = "header-injection",
                            urlPattern = "https://example.test/*",
                            headerMutations = listOf(
                                KWebNetworkHeaderMutation("X-Test", "ok\r\nInjected: true"),
                            ),
                        ),
                    ),
                ),
            )
        }
        assertEquals("network.policy.invalid", injectedHeader.code)

        val proxyMismatch = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    proxy = KWebProxyConfiguration(
                        mode = KWebProxyMode.DIRECT,
                        pacMandatory = true,
                    ),
                ),
            )
        }
        assertEquals("network.proxy.invalid", proxyMismatch.code)

        val invalidUserAgent = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(KWebNetworkPolicy(userAgent = "KWeb\r\nInjected: true"))
        }
        assertEquals("network.policy.invalid", invalidUserAgent.code)

        val invalidAcceptLanguage = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(acceptLanguage = "en-US\nX-Injected: true"),
            )
        }
        assertEquals("network.policy.invalid", invalidAcceptLanguage.code)

        val oversizedUserAgent = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(userAgent = "u".repeat(1025)),
            )
        }
        assertEquals("network.policy.limit-exceeded", oversizedUserAgent.code)

        assertTrue(
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    proxy = KWebProxyConfiguration(
                        mode = KWebProxyMode.FIXED,
                        rules = "http=proxy.example.test:8080;https=secure.example.test:8443;socks=socks.example.test:1080",
                        bypassList = listOf("<local>", "*.example.test"),
                    ),
                ),
            ).contains("proxy.example.test"),
        )

        val invalidFixedProxy = assertFailsWith<KWebConfigurationException> {
            KWebDesktopNetworkJson.policyPayload(
                KWebNetworkPolicy(
                    proxy = KWebProxyConfiguration(
                        mode = KWebProxyMode.FIXED,
                        rules = "proxy.example.test:8080,not a proxy",
                    ),
                ),
            )
        }
        assertEquals("network.proxy.invalid", invalidFixedProxy.code)
    }

    @Test
    fun rejectsMalformedNativeObservation() {
        val error = assertFailsWith<IllegalStateException> {
            KWebDesktopNetworkJson.parseEvent(
                "{\"requestId\":\"1\",\"phase\":\"unknown\",\"url\":\"https://example.test/\"," +
                    "\"method\":\"GET\",\"resourceType\":\"xhr\",\"action\":\"allow\",\"policyVersion\":1}",
            )
        }
        assertTrue(error.message.orEmpty().contains("Unknown network event phase"))
    }

    @Test
    fun rejectsUnknownResourceTypesAndCredentialBearingUrls() {
        val base = "\"requestId\":\"1\",\"phase\":\"before-request\"," +
            "\"url\":\"https://example.test/\",\"method\":\"GET\"," +
            "\"action\":\"allow\",\"policyVersion\":1"
        assertFailsWith<IllegalStateException> {
            KWebDesktopNetworkJson.parseEvent("{" + base + ",\"resourceType\":\"future_type\"}")
        }
        assertFailsWith<IllegalStateException> {
            KWebDesktopNetworkJson.parseEvent(
                "{\"requestId\":\"1\",\"phase\":\"before-request\"," +
                    "\"url\":\"https://user:secret@example.test/\",\"method\":\"GET\"," +
                    "\"resourceType\":\"xhr\",\"action\":\"allow\",\"policyVersion\":1}",
            )
        }
    }

    @Test
    fun rejectsBodyOrHeaderValuesInNetworkObservationPayloads() {
        val error = assertFailsWith<IllegalStateException> {
            KWebDesktopNetworkJson.parseEvent(
                """{"requestId":"1","phase":"complete","url":"https://example.test/","method":"POST","resourceType":"xhr","action":"allow","policyVersion":1,"completionStatus":"success","responseBody":"private"}""",
            )
        }
        assertTrue(error.message.orEmpty().contains("unsupported field"))
    }

    @Test
    fun observationOverflowTerminatesAllAndFutureCollectors() = runBlocking {
        val stream = KWebDesktopNetworkEventStream(bufferCapacity = 1)
        val failures = List(2) { CompletableDeferred<String>() }
        val collectors = failures.map { result ->
            launch {
                try {
                    stream.events.collect { awaitCancellation() }
                } catch (error: KWebNativeException) {
                    result.complete(error.code)
                }
            }
        }
        yield()
        yield()
        val event = KWebNetworkRequestEvent(
            requestId = 1,
            phase = KWebNetworkRequestPhase.BEFORE_REQUEST,
            url = "https://example.test/",
            method = "GET",
            resourceType = KWebNetworkResourceType.XHR,
            action = KWebNetworkRuleAction.ALLOW,
            policyVersion = 1,
        )
        assertTrue(stream.publish(event))
        assertTrue(!stream.publish(event.copy(requestId = 2)))
        assertEquals("network.observation-backpressure", failures[0].await())
        assertEquals("network.observation-backpressure", failures[1].await())
        collectors.forEach { it.join() }
        val lateFailure = assertFailsWith<KWebNativeException> {
            stream.events.first()
        }
        assertEquals("network.observation-backpressure", lateFailure.code)
    }

    @Test
    fun profileCloseTerminatesActiveAndFutureCollectors() = runBlocking {
        val stream = KWebDesktopNetworkEventStream()
        val activeFailure = CompletableDeferred<String>()
        val collector = launch {
            try {
                stream.events.collect { awaitCancellation() }
            } catch (error: KWebNativeException) {
                activeFailure.complete(error.code)
            }
        }
        yield()
        stream.close()
        assertEquals("network.profile-closing", activeFailure.await())
        collector.join()
        val laterFailure = assertFailsWith<KWebNativeException> {
            stream.events.first()
        }
        assertEquals("network.profile-closing", laterFailure.code)
    }

    @Test
    fun networkObservationDoesNotReplayBeforeSubscription() = runBlocking {
        val stream = KWebDesktopNetworkEventStream()
        val event = KWebNetworkRequestEvent(
            requestId = 1,
            phase = KWebNetworkRequestPhase.BEFORE_REQUEST,
            url = "https://example.test/",
            method = "GET",
            resourceType = KWebNetworkResourceType.MAIN_FRAME,
            action = KWebNetworkRuleAction.ALLOW,
            policyVersion = 1,
        )
        assertTrue(stream.publish(event))
        assertNull(withTimeoutOrNull(50) { stream.events.first() })
        stream.close()
    }
}
