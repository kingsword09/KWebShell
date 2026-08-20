package io.github.kingsword09.kwebshell.example.html5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

internal data class CapabilityCdpEvidence(
    val chromiumProduct: String,
    val protocolVersion: String,
    val revision: String,
    val javaScriptVersion: String,
    val accessibilityRole: String,
    val accessibilityName: String,
)

internal class CapabilityLabCdpClient(
    private val port: Int,
    private val timeoutMs: Long,
) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private var host: String = "127.0.0.1"

    fun inspect(pageUrl: String): CapabilityCdpEvidence {
        val versionDocument = awaitVersion()
        val browserSocketUrl = versionDocument.requiredString("webSocketDebuggerUrl")
        requireLoopbackWebSocket(browserSocketUrl)
        val browserVersion = CdpCommandSocket(browserSocketUrl, timeoutMs).use { socket ->
            socket.command("Browser.getVersion")
        }
        val product = browserVersion.requiredString("product")
        val protocolVersion = browserVersion.requiredString("protocolVersion")
        val revision = browserVersion.requiredString("revision")
        val javaScriptVersion = browserVersion.requiredString("jsVersion")
        val page = awaitPage(pageUrl)
        val pageSocketUrl = page.requiredString("webSocketDebuggerUrl")
        requireLoopbackWebSocket(pageSocketUrl)
        val accessibility = CdpCommandSocket(pageSocketUrl, timeoutMs).use { socket ->
            socket.command("Accessibility.getFullAXTree")
        }
        val nodes = accessibility["nodes"]?.jsonArray
            ?: throw CapabilityLabException("cdp-accessibility-missing", "CDP returned no accessibility nodes.")
        val marker = nodes.map { it.jsonObject }.firstOrNull { node ->
            node.axValue("name") == "KWebShell capability evidence"
        } ?: throw CapabilityLabException(
            "cdp-accessibility-marker-missing",
            "The page accessibility tree does not contain the capability evidence region.",
        )
        val role = marker.axValue("role")
            ?: throw CapabilityLabException("cdp-accessibility-role-missing", "The marker accessibility node has no role.")
        if (role != "region") {
            throw CapabilityLabException("cdp-accessibility-role-invalid", "Expected accessibility role 'region', got '$role'.")
        }
        return CapabilityCdpEvidence(
            chromiumProduct = product,
            protocolVersion = protocolVersion,
            revision = revision,
            javaScriptVersion = javaScriptVersion,
            accessibilityRole = role,
            accessibilityName = "KWebShell capability evidence",
        )
    }

    fun dispatchTrustedClick(pageUrl: String, x: Int, y: Int) {
        if (x <= 0 || y <= 0) {
            throw CapabilityLabException("cdp-input-coordinates-invalid", "CDP input coordinates must be positive.")
        }
        val pageSocketUrl = awaitPage(pageUrl).requiredString("webSocketDebuggerUrl")
        requireLoopbackWebSocket(pageSocketUrl)
        listOf("mouseMoved", "mousePressed", "mouseReleased").forEach { type ->
            val parameters = buildJsonObject {
                put("type", type)
                put("x", x)
                put("y", y)
                if (type != "mouseMoved") {
                    put("button", "left")
                    put("clickCount", 1)
                }
            }
            CdpCommandSocket(pageSocketUrl, timeoutMs).use { socket ->
                socket.command("Input.dispatchMouseEvent", parameters)
            }
        }
    }

    fun assertUnavailable() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var lastLiveResponse: String? = null
        while (System.nanoTime() < deadline) {
            val responses = listOf("127.0.0.1", "[::1]").mapNotNull { candidate ->
                try {
                    val response = http.send(
                        HttpRequest.newBuilder(URI("http://$candidate:$port/json/version"))
                            .timeout(Duration.ofSeconds(1))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )
                    "${response.statusCode()}:${response.body()}"
                } catch (_: IOException) {
                    null
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CapabilityLabException(
                        "cdp-unavailable-check-interrupted",
                        "Checking CDP shutdown was interrupted.",
                        error,
                    )
                }
            }
            if (responses.isEmpty()) return
            lastLiveResponse = responses.joinToString()
            Thread.sleep(100)
        }
        throw CapabilityLabException(
            "cdp-endpoint-still-live",
            "The explicitly configured CDP endpoint remained live after Engine close: $lastLiveResponse",
        )
    }

    private fun awaitVersion(): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                return getLoopback("/json/version")
            } catch (error: Throwable) {
                lastFailure = error
                Thread.sleep(100)
            }
        }
        throw CapabilityLabException("cdp-version-timeout", "CDP version discovery timed out.", lastFailure)
    }

    private fun awaitPage(pageUrl: String): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastTargets: JsonArray? = null
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val targets = getArray("/json/list")
                lastTargets = targets
                targets.map { it.jsonObject }.firstOrNull { target ->
                    target["type"]?.jsonPrimitive?.contentOrNull == "page" &&
                        target["url"]?.jsonPrimitive?.contentOrNull == pageUrl
                }?.let { return it }
            } catch (error: Throwable) {
                lastFailure = error
            }
            Thread.sleep(100)
        }
        throw CapabilityLabException(
            "cdp-page-timeout",
            "CDP did not expose page '$pageUrl'; last targets=$lastTargets.",
            lastFailure,
        )
    }

    private fun getLoopback(path: String): JsonObject {
        return try {
            host = "127.0.0.1"
            getObject(path)
        } catch (first: Throwable) {
            host = "[::1]"
            try {
                getObject(path)
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun getObject(path: String): JsonObject = getJson(path).jsonObject

    private fun getArray(path: String): JsonArray = getJson(path).jsonArray

    private fun getJson(path: String): JsonElement {
        val response = http.send(
            HttpRequest.newBuilder(URI("http://$host:$port$path"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        if (response.statusCode() != 200) {
            throw CapabilityLabException(
                "cdp-http-status",
                "CDP '$path' returned HTTP ${response.statusCode()}.",
            )
        }
        return try {
            Json.parseToJsonElement(response.body())
        } catch (error: Throwable) {
            throw CapabilityLabException("cdp-json-invalid", "CDP '$path' returned invalid JSON.", error)
        }
    }

    private fun requireLoopbackWebSocket(value: String) {
        if (!value.startsWith("ws://127.0.0.1:") && !value.startsWith("ws://[::1]:")) {
            throw CapabilityLabException("cdp-endpoint-not-loopback", "CDP exposed non-loopback WebSocket '$value'.")
        }
    }
}

private class CdpCommandSocket(
    url: String,
    private val timeoutMs: Long,
) : AutoCloseable {
    private val response = CompletableFuture<JsonObject>()
    private val fragment = StringBuilder()
    private val socket = HttpClient.newHttpClient().newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI(url), object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
                synchronized(fragment) {
                    fragment.append(data)
                    if (last) {
                        val message = fragment.toString()
                        fragment.setLength(0)
                        try {
                            val document = Json.parseToJsonElement(message).jsonObject
                            if (document["id"]?.jsonPrimitive?.contentOrNull == "1") {
                                response.complete(document)
                            }
                        } catch (error: Throwable) {
                            response.completeExceptionally(
                                CapabilityLabException("cdp-message-invalid", "CDP returned an invalid message.", error),
                            )
                        }
                    }
                }
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                response.completeExceptionally(error)
            }
        }).orTimeout(10, TimeUnit.SECONDS).join()

    fun command(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val request = buildJsonObject {
            put("id", 1)
            put("method", method)
            put("params", params)
        }
        socket.sendText(request.toString(), true).orTimeout(10, TimeUnit.SECONDS).join()
        val document = response.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join()
        document["error"]?.let { error ->
            throw CapabilityLabException("cdp-command-failed", "CDP '$method' failed: $error")
        }
        return document["result"]?.jsonObject
            ?: throw CapabilityLabException("cdp-result-missing", "CDP '$method' returned no result.")
    }

    override fun close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "complete")
            .orTimeout(10, TimeUnit.SECONDS)
            .join()
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw CapabilityLabException("cdp-field-missing", "CDP field '$name' is missing or empty.")

private fun JsonObject.axValue(name: String): String? =
    this[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
