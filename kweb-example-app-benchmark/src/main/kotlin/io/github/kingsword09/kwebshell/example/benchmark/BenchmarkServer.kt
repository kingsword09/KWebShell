package io.github.kingsword09.kwebshell.example.benchmark

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal class BenchmarkServer(
    private val workloadRoot: java.nio.file.Path,
) : AutoCloseable {
    private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val webSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "KWebShell-application-benchmark-server").apply { isDaemon = true }
    }
    private val reports = ConcurrentHashMap<String, CompletableFuture<BenchmarkPageObservation>>()
    private val expected = ConcurrentHashMap<String, ExpectedSample>()
    private val closed = AtomicBoolean(false)
    private val acceptor = Thread(::acceptWebSockets, "KWebShell-application-benchmark-websocket").apply {
        isDaemon = true
    }

    init {
        if (!java.nio.file.Files.isDirectory(workloadRoot)) {
            throw BenchmarkException("server.workload-root-missing", "Workload root '$workloadRoot' is missing.")
        }
        http.executor = executor
        http.createContext("/") { exchange -> handleHttp(exchange) }
        http.start()
        acceptor.start()
    }

    internal val origin: String get() = "http://127.0.0.1:${http.address.port}"
    internal val pageUrl: String get() = "$origin/index.html"

    fun registerSample(sampleId: String, phase: String, profileName: String): String {
        if (!SAMPLE_ID.matches(sampleId) || phase !in listOf("cold", "warm") || profileName.isBlank()) {
            throw BenchmarkException("server.sample-invalid", "Invalid benchmark sample registration.")
        }
        if (expected.putIfAbsent(sampleId, ExpectedSample(phase, profileName)) != null) {
            throw BenchmarkException("server.sample-duplicate", "Sample '$sampleId' was registered twice.")
        }
        reports[sampleId] = CompletableFuture()
        return "$pageUrl?phase=$phase&sampleId=$sampleId"
    }

    fun awaitObservation(sampleId: String, timeoutMs: Long): BenchmarkPageObservation {
        val future = reports[sampleId] ?: throw BenchmarkException("server.sample-unknown", "Sample '$sampleId' is not registered.")
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw BenchmarkException("server.observation-interrupted", "Waiting for sample '$sampleId' was interrupted.", error)
        } catch (error: TimeoutException) {
            throw BenchmarkException("server.observation-timeout", "Sample '$sampleId' did not publish an observation.", error)
        } catch (error: ExecutionException) {
            val cause = error.cause ?: error
            if (cause is BenchmarkException) throw cause
            throw BenchmarkException("server.observation-failed", "Sample '$sampleId' failed observation validation.", cause)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: Throwable? = null
        http.stop(0)
        try { webSocket.close() } catch (error: IOException) { failure = error }
        try {
            acceptor.join(5_000)
            if (acceptor.isAlive) failure = failure.append(BenchmarkException("server.acceptor-timeout", "The benchmark WebSocket acceptor did not stop."))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = failure.append(error)
        }
        executor.shutdownNow()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) failure = failure.append(BenchmarkException("server.executor-timeout", "The benchmark server executor did not stop."))
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            failure = failure.append(error)
        }
        failure?.let { throw it }
    }

    private fun handleHttp(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            when {
                exchange.requestMethod == "GET" && path == "/benchmark-config.json" -> serveConfig(exchange)
                exchange.requestMethod == "POST" && path == "/observation" -> receiveObservation(exchange)
                exchange.requestMethod == "GET" -> serveResource(exchange, path)
                else -> send(exchange, 405, "text/plain; charset=utf-8", "method not allowed")
            }
        } catch (error: Throwable) {
            val status = if (error is BenchmarkException) statusFor(error.code) else 500
            try {
                send(exchange, status, "text/plain; charset=utf-8", "${error::class.simpleName}: ${error.message}")
            } catch (sendFailure: Throwable) {
                error.addSuppressed(sendFailure)
            }
        } finally {
            exchange.close()
        }
    }

    private fun serveConfig(exchange: HttpExchange) {
        val query = parseQuery(exchange.requestURI)
        val sampleId = query["sampleId"] ?: throw BenchmarkException("server.config-sample-missing", "The benchmark config request has no sample id.")
        val sample = expected[sampleId] ?: throw BenchmarkException("server.config-sample-unknown", "Unknown benchmark sample '$sampleId'.")
        val config = """{"origin":"$origin","reportUrl":"$origin/observation?sampleId=$sampleId","webSocketUrl":"ws://127.0.0.1:${webSocket.localPort}/stream?sampleId=$sampleId","profileName":"${escapeJson(sample.profileName)}","phase":"${sample.phase}"}"""
        send(exchange, 200, "application/json; charset=utf-8", config)
    }

    private fun receiveObservation(exchange: HttpExchange) {
        val sampleId = parseQuery(exchange.requestURI)["sampleId"]
            ?: throw BenchmarkException("server.observation-sample-missing", "The observation request has no sample id.")
        val expectedSample = expected[sampleId] ?: throw BenchmarkException("server.observation-sample-unknown", "Unknown benchmark sample '$sampleId'.")
        val body = exchange.requestBody.use { input ->
            val bytes = input.readNBytes(2 * 1024 * 1024 + 1)
            if (bytes.size > 2 * 1024 * 1024) throw BenchmarkException("server.observation-too-large", "The page observation exceeds 2 MiB.")
            bytes.toString(StandardCharsets.UTF_8)
        }
        val future = reports[sampleId] ?: throw BenchmarkException("server.observation-sample-unknown", "Unknown benchmark sample '$sampleId'.")
        val observation = try {
            BenchmarkJson.format.decodeFromString<BenchmarkPageObservation>(body).also {
                BenchmarkValidator.validatePage(it, expectedSample.phase, sampleId)
            }
        } catch (error: Throwable) {
            val failure = if (error is BenchmarkException) error else BenchmarkException(
                "server.observation-json-invalid",
                "The page observation JSON is invalid.",
                error,
            )
            future.completeExceptionally(failure)
            throw failure
        }
        if (!future.complete(observation)) throw BenchmarkException("server.observation-duplicate", "Sample '$sampleId' published more than one observation.")
        send(exchange, 204, "text/plain; charset=utf-8", "")
    }

    private fun serveResource(exchange: HttpExchange, path: String) {
        val resourcePath = when (path) {
            "/", "/index.html" -> "index.html"
            "/app.js" -> "app.js"
            "/styles.css" -> "styles.css"
            "/worker.js" -> "worker.js"
            "/proof-sheet.svg" -> "proof-sheet.svg"
            "/proof-font.woff2" -> "proof-font.woff2"
            "/PROOF_FONT_NOTICE.txt" -> "PROOF_FONT_NOTICE.txt"
            else -> throw BenchmarkException("server.resource-not-found", "Workload resource '$path' is not locked.")
        }
        val file = workloadRoot.resolve(resourcePath).normalize()
        if (!file.startsWith(workloadRoot) || !java.nio.file.Files.isRegularFile(file)) {
            throw BenchmarkException("server.resource-missing", "Locked workload resource '$resourcePath' is missing.")
        }
        val type = when {
            resourcePath.endsWith(".html") -> "text/html; charset=utf-8"
            resourcePath.endsWith(".css") -> "text/css; charset=utf-8"
            resourcePath.endsWith(".svg") -> "image/svg+xml"
            resourcePath.endsWith(".woff2") -> "font/woff2"
            resourcePath.endsWith(".txt") -> "text/plain; charset=utf-8"
            else -> "text/javascript; charset=utf-8"
        }
        send(exchange, 200, type, java.nio.file.Files.readAllBytes(file), "public, max-age=31536000, immutable")
    }

    private fun send(exchange: HttpExchange, status: Int, contentType: String, body: String) =
        send(exchange, status, contentType, body.toByteArray(StandardCharsets.UTF_8), "no-store")

    private fun send(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: ByteArray,
        cacheControl: String = "no-store",
    ) {
        exchange.responseHeaders.set("Content-Type", contentType)
        exchange.responseHeaders.set("Cache-Control", cacheControl)
        exchange.responseHeaders.set("Cross-Origin-Opener-Policy", "same-origin")
        exchange.responseHeaders.set("Cross-Origin-Embedder-Policy", "require-corp")
        exchange.responseHeaders.set("Cross-Origin-Resource-Policy", "same-origin")
        if (status == 204) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun acceptWebSockets() {
        while (!closed.get()) {
            val socket = try { webSocket.accept() } catch (_: IOException) { return }
            executor.execute { handleWebSocket(socket) }
        }
    }

    private fun handleWebSocket(socket: Socket) {
        socket.use { active ->
            val input = BufferedInputStream(active.getInputStream())
            val output = BufferedOutputStream(active.getOutputStream())
            val headers = readHeaders(input)
            val key = headers.entries.firstOrNull { it.key.equals("Sec-WebSocket-Key", true) }?.value
                ?: throw BenchmarkException("server.websocket-key-missing", "The WebSocket handshake has no key.")
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest("$key$WEB_SOCKET_GUID".toByteArray(StandardCharsets.US_ASCII)))
            output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            val path = active.remoteSocketAddress.toString()
            val chunks = listOf(
                "The runtime opened one native child and kept its ownership explicit. ",
                "Routes, history, storage, workers, and decoders now leave observable marks. ",
                "The ledger keeps raw evidence beside every aggregate; unavailable remains unavailable.",
            )
            chunks.forEach { chunk -> writeTextFrame(output, chunk.toByteArray(StandardCharsets.UTF_8)); Thread.sleep(24) }
            writeCloseFrame(output)
        }
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val bytes = ArrayList<Byte>()
        var state = 0
        while (state < 4) {
            val value = input.read()
            if (value < 0) throw BenchmarkException("server.websocket-handshake-incomplete", "The WebSocket handshake ended early.")
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
        return bytes.toByteArray().toString(StandardCharsets.US_ASCII).split("\r\n").drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim() to it.substringAfter(':').trim() }
    }

    private fun writeTextFrame(output: BufferedOutputStream, payload: ByteArray) {
        output.write(0x81)
        when {
            payload.size < 126 -> output.write(payload.size)
            payload.size <= 65_535 -> { output.write(126); output.write(payload.size ushr 8); output.write(payload.size and 0xff) }
            else -> throw BenchmarkException("server.websocket-frame-too-large", "The workload WebSocket frame is too large.")
        }
        output.write(payload)
        output.flush()
    }

    private fun writeCloseFrame(output: BufferedOutputStream) {
        output.write(0x88); output.write(0); output.flush()
    }

    private fun parseQuery(uri: URI): Map<String, String> = uri.rawQuery.orEmpty().split('&').filter { it.contains('=') }
        .associate { java.net.URLDecoder.decode(it.substringBefore('='), StandardCharsets.UTF_8) to java.net.URLDecoder.decode(it.substringAfter('='), StandardCharsets.UTF_8) }

    private fun escapeJson(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun statusFor(code: String): Int = when {
        code.endsWith("-not-found") || code.endsWith("-missing") || code.endsWith("-unknown") -> 404
        code.endsWith("-too-large") -> 413
        code.endsWith("-duplicate") -> 409
        else -> 400
    }
    private fun Throwable?.append(next: Throwable): Throwable = this?.also { it.addSuppressed(next) } ?: next

    private data class ExpectedSample(val phase: String, val profileName: String)
    private companion object { val SAMPLE_ID = Regex("[0-9a-f-]{36}"); const val WEB_SOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11" }
}
