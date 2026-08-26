package io.github.kingsword09.kwebshell.example.html5

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class CapabilityLabServer : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val crossOrigin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val webSocket = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "KWebShell-capability-lab-server").apply { isDaemon = true }
    }
    private val reportFutures = ConcurrentHashMap<String, CompletableFuture<CapabilityPageReport>>().apply {
        put("cold", CompletableFuture())
        put("warm", CompletableFuture())
    }
    private val closed = AtomicBoolean(false)
    private val acceptor = Thread(::acceptWebSocket, "KWebShell-capability-lab-websocket").apply {
        isDaemon = true
    }

    init {
        http.executor = executor
        http.createContext("/") { exchange -> handleHttp(exchange) }
        crossOrigin.executor = executor
        crossOrigin.createContext("/cors") { exchange ->
            try {
                if (exchange.requestMethod != "GET") {
                    send(exchange, 405, "text/plain; charset=utf-8", "method not allowed", crossOriginResource = true)
                } else {
                    exchange.responseHeaders.set("Access-Control-Allow-Origin", origin)
                    send(
                        exchange,
                        200,
                        "application/json; charset=utf-8",
                        "{\"marker\":\"kwebshell-cors-v1\"}",
                        crossOriginResource = true,
                    )
                }
            } finally {
                exchange.close()
            }
        }
        http.start()
        crossOrigin.start()
        acceptor.start()
    }

    val origin: String
        get() = "http://127.0.0.1:${http.address.port}"

    val indexUrl: String
        get() = "$origin/index.html"

    private val webSocketUrl: String
        get() = "ws://127.0.0.1:${webSocket.localPort}/echo"

    private val crossOriginUrl: String
        get() = "http://127.0.0.1:${crossOrigin.address.port}/cors"

    fun awaitReport(phase: String, timeoutMs: Long): CapabilityPageReport {
        val future = reportFutures[phase]
            ?: throw CapabilityLabException("report-phase-invalid", "Unknown report phase '$phase'.")
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CapabilityLabException("report-interrupted", "Waiting for the capability report was interrupted.", error)
        } catch (error: TimeoutException) {
            throw CapabilityLabException("report-timeout", "The '$phase' capability page did not publish in time.", error)
        } catch (error: ExecutionException) {
            val cause = error.cause
            if (cause is CapabilityLabException) throw cause
            throw CapabilityLabException("report-failed", "The '$phase' capability report failed validation.", cause ?: error)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        fun record(error: Throwable) {
            val current = failure
            if (current == null) failure = error else current.addSuppressed(error)
        }
        http.stop(0)
        crossOrigin.stop(0)
        try {
            webSocket.close()
        } catch (error: IOException) {
            record(error)
        }
        try {
            acceptor.join(5_000)
            if (acceptor.isAlive) {
                record(CapabilityLabException("websocket-acceptor-timeout", "The local WebSocket acceptor did not terminate."))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            record(error)
        }
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                record(CapabilityLabException("server-executor-timeout", "The capability server executor did not terminate."))
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            record(error)
        }
        failure?.let { throw it }
    }

    private fun handleHttp(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path == "/manifest.json" -> {
                    send(exchange, 200, "application/json; charset=utf-8", manifestJson())
                }
                exchange.requestMethod == "GET" && path == "/config.json" -> {
                    send(
                        exchange,
                        200,
                        "application/json; charset=utf-8",
                        "{\"origin\":${quote(origin)},\"webSocketUrl\":${quote(webSocketUrl)}," +
                            "\"crossOriginUrl\":${quote(crossOriginUrl)}}",
                    )
                }
                exchange.requestMethod == "POST" && path == "/report" -> receiveReport(exchange)
                exchange.requestMethod == "GET" -> serveResource(exchange, path)
                else -> send(exchange, 405, "text/plain; charset=utf-8", "method not allowed")
            }
        } catch (error: Throwable) {
            reportFutures.values.filterNot { it.isDone }.forEach { it.completeExceptionally(error) }
            try {
                send(exchange, 500, "text/plain; charset=utf-8", "server error")
            } catch (sendError: Throwable) {
                error.addSuppressed(sendError)
            }
        } finally {
            exchange.close()
        }
    }

    private fun receiveReport(exchange: HttpExchange) {
        val body = exchange.requestBody.use { input ->
            val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
            if (bytes.size > 2 * 1024 * 1024) {
                throw CapabilityLabException("report-too-large", "The capability report exceeds the 2 MiB limit.")
            }
            bytes
        }
        val report = try {
            CapabilityLabJson.format.decodeFromString<CapabilityPageReport>(
                body.toString(StandardCharsets.UTF_8),
            )
        } catch (error: Throwable) {
            throw CapabilityLabException("report-json-invalid", "The capability report is not valid locked-schema JSON.", error)
        }
        val future = reportFutures[report.phase]
            ?: throw CapabilityLabException("report-phase-invalid", "Unknown report phase '${report.phase}'.")
        CapabilityLabValidator.validatePage(report, origin, report.phase)
        if (!future.complete(report)) {
            throw CapabilityLabException("duplicate-report", "The page published more than one '${report.phase}' report.")
        }
        send(exchange, 204, "text/plain; charset=utf-8", "")
    }

    private fun serveResource(exchange: HttpExchange, path: String) {
        val resource = when (path) {
            "/", "/index.html" -> "capability-lab/index.html"
            "/lab.js" -> "capability-lab/lab.js"
            "/dynamic-module.js" -> "capability-lab/dynamic-module.js"
            "/worker.js" -> "capability-lab/worker.js"
            "/shared-worker.js" -> "capability-lab/shared-worker.js"
            "/service-worker.js" -> "capability-lab/service-worker.js"
            "/style.css" -> "capability-lab/style.css"
            else -> null
        } ?: run {
            send(exchange, 404, "text/plain; charset=utf-8", "not found")
            return
        }
        val bytes = javaClass.classLoader.getResourceAsStream(resource)?.use { it.readBytes() }
            ?: throw CapabilityLabException("resource-missing", "Capability lab resource '$resource' is missing.")
        val contentType = when {
            resource.endsWith(".html") -> "text/html; charset=utf-8"
            resource.endsWith(".css") -> "text/css; charset=utf-8"
            else -> "text/javascript; charset=utf-8"
        }
        send(exchange, 200, contentType, bytes)
    }

    private fun manifestJson(): String = CapabilityLabJson.format.encodeToString(CapabilityLabManifest.definitions)

    private fun send(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
        crossOriginResource: Boolean = false,
    ) {
        send(exchange, status, contentType, body.toByteArray(StandardCharsets.UTF_8), crossOriginResource)
    }

    private fun send(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: ByteArray,
        crossOriginResource: Boolean = false,
    ) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.responseHeaders.set("Cross-Origin-Opener-Policy", "same-origin")
        exchange.responseHeaders.set("Cross-Origin-Embedder-Policy", "require-corp")
        exchange.responseHeaders.set(
            "Cross-Origin-Resource-Policy",
            if (crossOriginResource) "cross-origin" else "same-origin",
        )
        if (status == 204) {
            if (body.isNotEmpty()) {
                throw CapabilityLabException("response-body-invalid", "HTTP 204 responses cannot contain a body.")
            }
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun acceptWebSocket() {
        while (!closed.get()) {
            val socket = try {
                webSocket.accept()
            } catch (acceptError: IOException) {
                if (!closed.get()) {
                    val error = CapabilityLabException(
                        "websocket-accept-failed",
                        "The local WebSocket endpoint stopped accepting connections.",
                        acceptError,
                    )
                    reportFutures.values.filterNot { it.isDone }.forEach { it.completeExceptionally(error) }
                }
                return
            }
            executor.execute { handleWebSocket(socket) }
        }
    }

    private fun handleWebSocket(socket: Socket) {
        socket.use { connection ->
            connection.soTimeout = 30_000
            val input = BufferedInputStream(connection.getInputStream())
            val output = BufferedOutputStream(connection.getOutputStream())
            val request = readHttpHeaders(input)
            val key = request["sec-websocket-key"]
                ?: throw CapabilityLabException("websocket-handshake-invalid", "The page omitted Sec-WebSocket-Key.")
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII)),
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
            val payload = readTextFrame(input)
            writeTextFrame(output, "echo:$payload")
            output.flush()
        }
    }

    private fun readHttpHeaders(input: BufferedInputStream): Map<String, String> {
        val bytes = ArrayList<Byte>()
        while (bytes.size < 16 * 1024) {
            val value = input.read()
            if (value < 0) break
            bytes += value.toByte()
            if (bytes.takeLast(4).toByteArray().contentEquals(byteArrayOf(13, 10, 13, 10))) break
        }
        val text = bytes.toByteArray().toString(StandardCharsets.US_ASCII)
        if (!text.endsWith("\r\n\r\n")) {
            throw CapabilityLabException("websocket-handshake-invalid", "The WebSocket handshake headers were incomplete.")
        }
        return text.lineSequence()
            .drop(1)
            .takeWhile { it.isNotEmpty() }
            .mapNotNull { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) null else line.substring(0, separator).lowercase(Locale.ROOT) to
                    line.substring(separator + 1).trim()
            }.toMap()
    }

    private fun readTextFrame(input: BufferedInputStream): String {
        val first = input.read()
        val second = input.read()
        if (first < 0 || second < 0 || first and 0x0f != 1 || second and 0x80 == 0) {
            throw CapabilityLabException("websocket-frame-invalid", "Expected one masked client text frame.")
        }
        var length = second and 0x7f
        if (length == 126) {
            length = (input.read() shl 8) or input.read()
        } else if (length == 127) {
            throw CapabilityLabException("websocket-frame-too-large", "The capability WebSocket frame is too large.")
        }
        if (length <= 0 || length > 64 * 1024) {
            throw CapabilityLabException("websocket-frame-invalid", "The capability WebSocket frame length is invalid.")
        }
        val mask = ByteArray(4)
        input.readFully(mask)
        val payload = ByteArray(length)
        input.readFully(payload)
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor (mask[index % 4].toInt() and 0xff)).toByte()
        return payload.toString(StandardCharsets.UTF_8)
    }

    private fun writeTextFrame(output: BufferedOutputStream, value: String) {
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        require(payload.size <= 125) { "The echo frame must fit in one short WebSocket frame." }
        output.write(0x81)
        output.write(payload.size)
        output.write(payload)
    }

    private fun BufferedInputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read < 0) throw CapabilityLabException("websocket-frame-truncated", "The WebSocket frame ended early.")
            offset += read
        }
    }

    private fun quote(value: String): String = JsonPrimitiveEscaper.quote(value)
}

private object JsonPrimitiveEscaper {
    fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
}
