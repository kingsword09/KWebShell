package io.github.kingsword09.kwebshell.example.support

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
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

public class KWebExampleCdpException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public data class KWebExampleCdpBrowserVersion(
    public val product: String,
    public val protocolVersion: String,
    public val revision: String,
    public val javaScriptVersion: String,
    public val userAgent: String,
)

public data class KWebExampleCdpTarget(
    public val id: String,
    public val type: String,
    public val title: String,
    public val url: String,
    public val webSocketDebuggerUrl: String,
)

public data class KWebExampleCdpEvaluation(
    public val value: JsonElement?,
    public val type: String,
    public val description: String?,
)

public class KWebExampleCdpClient(
    private val port: Int,
    private val timeoutMs: Long,
) {
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private var discoveryHost: String = "127.0.0.1"

    init {
        require(port in 1024..65535) { "The CDP port must be in 1024..65535." }
        require(timeoutMs > 0L) { "The CDP timeout must be positive." }
    }

    public fun awaitBrowserVersion(): KWebExampleCdpBrowserVersion {
        val document = awaitVersionDocument()
        return openSession(document.requiredString("webSocketDebuggerUrl")).use { session ->
            val result = session.command("Browser.getVersion")
            KWebExampleCdpBrowserVersion(
                product = result.requiredString("product"),
                protocolVersion = result.requiredString("protocolVersion"),
                revision = result.requiredString("revision"),
                javaScriptVersion = result.requiredString("jsVersion"),
                userAgent = result.requiredString("userAgent"),
            )
        }
    }

    public fun awaitPage(pageUrl: String): KWebExampleCdpTarget {
        if (pageUrl.isBlank()) {
            throw KWebExampleCdpException("cdp.page-url-empty", "The expected CDP page URL is empty.")
        }
        val expectedUrl = canonicalTargetUrl(pageUrl)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastTargets: JsonArray? = null
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val targets = getLoopbackArray("/json/list")
                lastTargets = targets
                targets.map { it.jsonObject }.firstOrNull { target ->
                    target["type"]?.jsonPrimitive?.contentOrNull == "page" &&
                        target["url"]?.jsonPrimitive?.contentOrNull?.let(::canonicalTargetUrl) == expectedUrl
                }?.let { return it.toTarget() }
            } catch (error: Throwable) {
                lastFailure = error
            }
            sleepPoll("cdp.page-discovery-interrupted")
        }
        throw KWebExampleCdpException(
            "cdp.page-timeout",
            "CDP did not expose page '$pageUrl'; last targets=$lastTargets.",
            lastFailure,
        )
    }

    public fun openBrowserSession(): KWebExampleCdpSession =
        openSession(awaitVersionDocument().requiredString("webSocketDebuggerUrl"))

    public fun openPageSession(pageUrl: String): KWebExampleCdpSession =
        openSession(awaitPage(pageUrl).webSocketDebuggerUrl)

    public fun assertUnavailable(shutdownTimeoutMs: Long = 5_000L) {
        require(shutdownTimeoutMs > 0L) { "The CDP shutdown timeout must be positive." }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(shutdownTimeoutMs)
        var lastLiveResponse: String? = null
        while (System.nanoTime() < deadline) {
            val responses = listOf("127.0.0.1", "[::1]").mapNotNull { host ->
                try {
                    val response = http.send(
                        HttpRequest.newBuilder(URI("http://$host:$port/json/version"))
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
                    throw KWebExampleCdpException(
                        "cdp.shutdown-check-interrupted",
                        "Checking CDP shutdown was interrupted.",
                        error,
                    )
                }
            }
            if (responses.isEmpty()) return
            lastLiveResponse = responses.joinToString()
            sleepPoll("cdp.shutdown-check-interrupted")
        }
        throw KWebExampleCdpException(
            "cdp.endpoint-still-live",
            "The configured CDP endpoint remained live after Engine close: $lastLiveResponse",
        )
    }

    private fun awaitVersionDocument(): JsonObject {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                return getLoopbackObject("/json/version")
            } catch (error: Throwable) {
                lastFailure = error
                sleepPoll("cdp.version-discovery-interrupted")
            }
        }
        throw KWebExampleCdpException(
            "cdp.version-timeout",
            "CDP version discovery timed out.",
            lastFailure,
        )
    }

    private fun getLoopbackObject(path: String): JsonObject {
        discoveryHost = "127.0.0.1"
        return try {
            getObject(path)
        } catch (ipv4Failure: Throwable) {
            discoveryHost = "[::1]"
            try {
                getObject(path)
            } catch (ipv6Failure: Throwable) {
                ipv6Failure.addSuppressed(ipv4Failure)
                throw ipv6Failure
            }
        }
    }

    private fun getLoopbackArray(path: String): JsonArray {
        discoveryHost = "127.0.0.1"
        return try {
            getArray(path)
        } catch (ipv4Failure: Throwable) {
            discoveryHost = "[::1]"
            try {
                getArray(path)
            } catch (ipv6Failure: Throwable) {
                ipv6Failure.addSuppressed(ipv4Failure)
                throw ipv6Failure
            }
        }
    }

    private fun getObject(path: String): JsonObject = getJson(path).jsonObject

    private fun getArray(path: String): JsonArray = getJson(path).jsonArray

    private fun getJson(path: String): JsonElement {
        val response = try {
            http.send(
                HttpRequest.newBuilder(URI("http://$discoveryHost:$port$path"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebExampleCdpException("cdp.http-interrupted", "CDP '$path' was interrupted.", error)
        } catch (error: IOException) {
            throw KWebExampleCdpException("cdp.http-failed", "CDP '$path' could not be read.", error)
        }
        if (response.statusCode() != 200) {
            throw KWebExampleCdpException(
                "cdp.http-status",
                "CDP '$path' returned HTTP ${response.statusCode()}.",
            )
        }
        return try {
            Json.parseToJsonElement(response.body())
        } catch (error: Throwable) {
            throw KWebExampleCdpException("cdp.json-invalid", "CDP '$path' returned invalid JSON.", error)
        }
    }

    private fun openSession(webSocketUrl: String): KWebExampleCdpSession {
        requireLoopbackWebSocket(webSocketUrl)
        return KWebExampleCdpSession(webSocketUrl, timeoutMs)
    }

    private fun sleepPoll(code: String) {
        try {
            Thread.sleep(100)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebExampleCdpException(code, "CDP polling was interrupted.", error)
        }
    }
}

public class KWebExampleCdpSession internal constructor(
    url: String,
    private val timeoutMs: Long,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val nextId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
    private val events = ConcurrentHashMap<String, LinkedBlockingQueue<JsonObject>>()
    private val fragments = StringBuilder()
    private val socket: WebSocket

    init {
        socket = try {
            HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI(url), Listener())
                .orTimeout(10, TimeUnit.SECONDS)
                .join()
        } catch (error: Throwable) {
            throw KWebExampleCdpException("cdp.websocket-open-failed", "Could not open CDP WebSocket '$url'.", error)
        }
    }

    public fun command(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        if (method.isBlank()) {
            throw KWebExampleCdpException("cdp.method-empty", "The CDP method is empty.")
        }
        if (closed.get()) {
            throw KWebExampleCdpException("cdp.session-closed", "The CDP session is closed.")
        }
        val id = nextId.getAndIncrement()
        val response = CompletableFuture<JsonObject>()
        if (pending.putIfAbsent(id, response) != null) {
            throw KWebExampleCdpException("cdp.command-id-collision", "CDP command id '$id' is already pending.")
        }
        val request = buildJsonObject {
            put("id", id)
            put("method", method)
            put("params", params)
        }
        try {
            socket.sendText(request.toString(), true).orTimeout(10, TimeUnit.SECONDS).join()
            val document = try {
                response.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join()
            } catch (error: Throwable) {
                throw KWebExampleCdpException(
                    "cdp.command-timeout",
                    "CDP '$method' with id $id did not complete within ${timeoutMs} ms.",
                    error,
                )
            }
            document["error"]?.let { error ->
                throw KWebExampleCdpException("cdp.command-failed", "CDP '$method' failed: $error")
            }
            return document["result"]?.jsonObject
                ?: throw KWebExampleCdpException("cdp.result-missing", "CDP '$method' returned no result.")
        } catch (error: KWebExampleCdpException) {
            throw error
        } catch (error: Throwable) {
            throw KWebExampleCdpException("cdp.command-transport-failed", "CDP '$method' did not complete.", error)
        } finally {
            pending.remove(id)
        }
    }

    public fun evaluate(
        expression: String,
        awaitPromise: Boolean = true,
        returnByValue: Boolean = true,
    ): KWebExampleCdpEvaluation {
        if (expression.isBlank()) {
            throw KWebExampleCdpException("cdp.expression-empty", "The Runtime.evaluate expression is empty.")
        }
        val result = command(
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", expression)
                put("awaitPromise", awaitPromise)
                put("returnByValue", returnByValue)
                put("replMode", false)
            },
        )
        result["exceptionDetails"]?.let { details ->
            throw KWebExampleCdpException(
                "cdp.evaluation-failed",
                "Runtime.evaluate failed for the supplied expression: $details",
            )
        }
        val remote = result["result"]?.jsonObject
            ?: throw KWebExampleCdpException("cdp.evaluation-result-missing", "Runtime.evaluate returned no result object.")
        val type = remote.requiredString("type")
        return KWebExampleCdpEvaluation(
            value = remote["value"],
            type = type,
            description = remote["description"]?.jsonPrimitive?.contentOrNull,
        )
    }

    public fun awaitEvent(method: String, eventTimeoutMs: Long = timeoutMs): JsonObject {
        return pollEvent(method, eventTimeoutMs) ?: throw KWebExampleCdpException(
            "cdp.event-timeout",
            "CDP event '$method' did not arrive within $eventTimeoutMs ms.",
        )
    }

    public fun pollEvent(method: String, eventTimeoutMs: Long): JsonObject? {
        if (method.isBlank()) {
            throw KWebExampleCdpException("cdp.event-method-empty", "The CDP event method is empty.")
        }
        require(eventTimeoutMs > 0L) { "The CDP event timeout must be positive." }
        if (closed.get()) {
            throw KWebExampleCdpException("cdp.session-closed", "The CDP session is closed.")
        }
        val event = try {
            events.computeIfAbsent(method) { LinkedBlockingQueue() }
                .poll(eventTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KWebExampleCdpException("cdp.event-interrupted", "Waiting for CDP event '$method' was interrupted.", error)
        } ?: return null
        return event["params"]?.jsonObject ?: buildJsonObject {}
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val failure = KWebExampleCdpException("cdp.session-closed", "The CDP session closed with a pending command.")
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        events.clear()
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "complete")
                .orTimeout(10, TimeUnit.SECONDS)
                .join()
        } catch (error: Throwable) {
            throw KWebExampleCdpException("cdp.websocket-close-failed", "Could not close the CDP WebSocket.", error)
        }
    }

    private inner class Listener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*> {
            synchronized(fragments) {
                fragments.append(data)
                if (last) {
                    val message = fragments.toString()
                    fragments.setLength(0)
                    acceptMessage(message)
                }
            }
            webSocket.request(1)
            return CompletableFuture.completedFuture(null)
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            val wrapped = KWebExampleCdpException("cdp.websocket-failed", "The CDP WebSocket failed.", error)
            pending.values.forEach { it.completeExceptionally(wrapped) }
        }

        private fun acceptMessage(message: String) {
            val document = try {
                Json.parseToJsonElement(message).jsonObject
            } catch (error: Throwable) {
                val wrapped = KWebExampleCdpException("cdp.message-invalid", "CDP returned an invalid message.", error)
                pending.values.forEach { it.completeExceptionally(wrapped) }
                return
            }
            val id = document["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            if (id == null) {
                document["method"]?.jsonPrimitive?.contentOrNull?.let { method ->
                    events.computeIfAbsent(method) { LinkedBlockingQueue() }.offer(document)
                }
            } else {
                pending[id]?.complete(document)
            }
        }
    }
}

private fun JsonObject.requiredString(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw KWebExampleCdpException("cdp.field-missing", "CDP field '$name' is missing or empty.")

private fun JsonObject.toTarget(): KWebExampleCdpTarget {
    val webSocketUrl = requiredString("webSocketDebuggerUrl")
    requireLoopbackWebSocket(webSocketUrl)
    return KWebExampleCdpTarget(
        id = requiredString("id"),
        type = requiredString("type"),
        title = requiredString("title"),
        url = requiredString("url"),
        webSocketDebuggerUrl = webSocketUrl,
    )
}

private fun requireLoopbackWebSocket(value: String) {
    val uri = try {
        URI(value)
    } catch (error: Throwable) {
        throw KWebExampleCdpException("cdp.endpoint-invalid", "CDP exposed invalid WebSocket '$value'.", error)
    }
    val loopbackHost = uri.host == "127.0.0.1" || uri.host == "::1" || uri.host == "[::1]"
    if (uri.scheme != "ws" || !loopbackHost || uri.port !in 1024..65535 || uri.rawUserInfo != null) {
        throw KWebExampleCdpException(
            "cdp.endpoint-not-loopback",
            "CDP exposed non-loopback or invalid WebSocket '$value'.",
        )
    }
}

private fun canonicalTargetUrl(value: String): String {
    val uri = try {
        URI(value)
    } catch (error: Throwable) {
        throw KWebExampleCdpException("cdp.page-url-invalid", "The CDP page URL '$value' is invalid.", error)
    }
    return try {
        URI(uri.scheme, uri.rawUserInfo, uri.host, uri.port, uri.rawPath, uri.rawQuery, null).toString()
    } catch (error: Throwable) {
        throw KWebExampleCdpException("cdp.page-url-invalid", "The CDP page URL '$value' cannot be canonicalized.", error)
    }
}
