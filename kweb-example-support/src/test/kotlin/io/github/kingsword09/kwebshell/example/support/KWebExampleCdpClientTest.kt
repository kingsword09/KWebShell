package io.github.kingsword09.kwebshell.example.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebExampleCdpClientTest {
    @Test
    fun discoversTargetsAndReusesACommandSession() {
        FakeCdpServer().use { server ->
            val client = KWebExampleCdpClient(server.httpPort, 5_000)
            val version = client.awaitBrowserVersion()
            assertEquals("Chrome/151.0.0.0", version.product)
            assertEquals("1.3", version.protocolVersion)

            val target = client.awaitPage("${server.pageUrl}#/proof")
            assertEquals("page-1", target.id)
            client.openPageSession(server.pageUrl).use { session ->
                assertEquals(1, session.command("Runtime.enable")["ordinal"]?.jsonPrimitive?.contentOrNull?.toInt())
                assertEquals(2, session.command("Performance.enable")["ordinal"]?.jsonPrimitive?.contentOrNull?.toInt())
                val evaluation = session.evaluate("Promise.resolve({marker: 'ready'})")
                assertEquals("object", evaluation.type)
                assertEquals("ready", evaluation.value?.jsonObject?.get("marker")?.jsonPrimitive?.contentOrNull)
            }
            assertEquals(listOf(1L, 1L, 2L, 3L), server.commandIds.toList())
        }
    }

    @Test
    fun rejectsNonLoopbackDebuggerSockets() {
        FakeCdpServer(webSocketHost = "example.com").use { server ->
            val error = assertFailsWith<KWebExampleCdpException> {
                KWebExampleCdpClient(server.httpPort, 2_000).awaitBrowserVersion()
            }
            assertEquals("cdp.endpoint-not-loopback", error.code)
        }
    }
}

private class FakeCdpServer(
    private val webSocketHost: String = "127.0.0.1",
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool()
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val webSocket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val closed = AtomicBoolean(false)
    private val sockets = CopyOnWriteArrayList<Socket>()
    val commandIds = CopyOnWriteArrayList<Long>()
    val httpPort: Int get() = http.address.port
    val pageUrl: String get() = "http://127.0.0.1:$httpPort/workload"

    init {
        http.executor = executor
        http.createContext("/json/version") { exchange ->
            sendJson(
                exchange,
                """{"webSocketDebuggerUrl":"ws://$webSocketHost:${webSocket.localPort}/browser"}""",
            )
        }
        http.createContext("/json/list") { exchange ->
            sendJson(
                exchange,
                """[{"id":"page-1","type":"page","title":"fixture","url":"$pageUrl","webSocketDebuggerUrl":"ws://$webSocketHost:${webSocket.localPort}/page"}]""",
            )
        }
        http.start()
        executor.execute(::acceptSockets)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        http.stop(0)
        runCatching { webSocket.close() }
        sockets.forEach { runCatching { it.close() } }
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun sendJson(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun acceptSockets() {
        while (!closed.get()) {
            val socket = try {
                webSocket.accept()
            } catch (_: java.io.IOException) {
                return
            }
            sockets += socket
            executor.execute { handleSocket(socket) }
        }
    }

    private fun handleSocket(socket: Socket) {
        socket.use { active ->
            val input = BufferedInputStream(active.getInputStream())
            val output = BufferedOutputStream(active.getOutputStream())
            val headers = readHttpHeaders(input)
            val key = headers.entries.firstOrNull { it.key.equals("Sec-WebSocket-Key", ignoreCase = true) }?.value
                ?: error("WebSocket request omitted Sec-WebSocket-Key")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest("$key$WEB_SOCKET_GUID".toByteArray(StandardCharsets.US_ASCII)),
            )
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII),
            )
            output.flush()
            var ordinal = 0
            while (!closed.get()) {
                val frame = readFrame(input) ?: return
                if (frame.opcode == 8) return
                if (frame.opcode != 1) continue
                ordinal += 1
                val request = Json.parseToJsonElement(frame.payload.toString(StandardCharsets.UTF_8)).jsonObject
                val id = request["id"]?.jsonPrimitive?.contentOrNull?.toLong() ?: error("Missing command id")
                commandIds += id
                val method = request["method"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val result = when (method) {
                    "Browser.getVersion" -> buildJsonObject {
                        put("product", "Chrome/151.0.0.0")
                        put("protocolVersion", "1.3")
                        put("revision", "@fixture")
                        put("jsVersion", "15.1")
                        put("userAgent", "KWebShell test")
                    }
                    "Runtime.evaluate" -> buildJsonObject {
                        put("result", buildJsonObject {
                            put("type", "object")
                            put("value", buildJsonObject { put("marker", "ready") })
                        })
                    }
                    else -> buildJsonObject { put("ordinal", ordinal) }
                }
                val response = buildJsonObject {
                    put("id", id)
                    put("result", result)
                }.toString().toByteArray(StandardCharsets.UTF_8)
                writeTextFrame(output, response)
            }
        }
    }

    private data class Frame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: BufferedInputStream): Frame? {
        val first = input.read()
        if (first < 0) return null
        val second = input.read()
        if (second < 0) return null
        var length = second and 0x7f
        if (length == 126) {
            length = (readRequired(input) shl 8) or readRequired(input)
        } else if (length == 127) {
            var longLength = 0L
            repeat(8) { longLength = (longLength shl 8) or readRequired(input).toLong() }
            require(longLength <= Int.MAX_VALUE) { "Test WebSocket frame is too large." }
            length = longLength.toInt()
        }
        val masked = second and 0x80 != 0
        val mask = if (masked) ByteArray(4) { readRequired(input).toByte() } else ByteArray(0)
        val payload = input.readNBytes(length)
        require(payload.size == length) { "Incomplete test WebSocket frame." }
        if (masked) payload.indices.forEach { index ->
            payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        return Frame(first and 0x0f, payload)
    }

    private fun writeTextFrame(output: BufferedOutputStream, payload: ByteArray) {
        output.write(0x81)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 65_535 -> {
                output.write(126)
                output.write(payload.size ushr 8)
                output.write(payload.size and 0xff)
            }
            else -> error("Test WebSocket response is too large.")
        }
        output.write(payload)
        output.flush()
    }

    private fun readHttpHeaders(input: BufferedInputStream): Map<String, String> {
        val bytes = ArrayList<Byte>()
        var state = 0
        while (state < 4) {
            val value = input.read()
            require(value >= 0) { "Incomplete WebSocket handshake." }
            bytes += value.toByte()
            state = when {
                state == 0 && value == '\r'.code -> 1
                state == 1 && value == '\n'.code -> 2
                state == 2 && value == '\r'.code -> 3
                state == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
        }
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
            .split("\r\n")
            .drop(1)
            .filter { it.contains(':') }
            .associate { line -> line.substringBefore(':').trim() to line.substringAfter(':').trim() }
    }

    private fun readRequired(input: BufferedInputStream): Int =
        input.read().takeIf { it >= 0 } ?: error("Incomplete WebSocket frame.")

    private companion object {
        const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    }
}
