package io.github.kingsword09.kwebshell.electron.migration

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeProtocol
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import io.github.kingsword09.kwebshell.service.apppaths.JvmKWebAppPaths
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathKind
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathsConfiguration
import io.github.kingsword09.kwebshell.service.apppaths.bridgeDispatcher
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.EventQueue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val NATIVE_LIBRARY_PROPERTY = "kweb.native.library.path"
private const val SERVICES_LIBRARY_PROPERTY = "kweb.services.native.library.path"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val ROOT_PROPERTY = "kweb.migration.integration.root"
private const val APP_PATHS_BRIDGE_PROPERTY = "kweb.migration.app-paths.bridge.javascript"
private const val PRELOAD_PROPERTY = "kweb.migration.preload.javascript"
private const val REPORT_PROPERTY = "kweb.migration.report"

public fun main() {
    val root = requiredPath(ROOT_PROPERTY).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val report = KWebElectronMigrationJson.format.decodeFromString<KWebElectronCompatibilityReport>(
        Files.readString(requiredPath(REPORT_PROPERTY)),
    )
    KWebElectronCompatibilityReportValidator.validate(report)
    require(report.migrationReady) { "The migration fixture cannot run with a blocked compatibility report." }
    val appRoot = root.resolve("app-data")
    val sessionRoot = root.resolve("session-data")
    val profileRoot = root.resolve("profiles")
    Files.createDirectories(appRoot)
    Files.createDirectories(sessionRoot)
    Files.createDirectories(profileRoot)
    val port = findFreePort()
    val server = MigrationFixtureServer(
        appPathsBridge = Files.readString(requiredPath(APP_PATHS_BRIDGE_PROPERTY)),
        preload = Files.readString(requiredPath(PRELOAD_PROPERTY)),
    )
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell Electron Migration Fixture"
            setSize(960, 700)
            setLocationRelativeTo(null)
            isVisible = true
            require(isDisplayable && isShowing && windowHandle != 0L)
        }
    }
    val engine = KWebDesktop.openEngine(
        KWebDesktopEngineConfiguration(
            cefRuntime = requiredPath(CEF_RUNTIME_PROPERTY),
            browserSubprocess = requiredPath(SUBPROCESS_PROPERTY),
            resources = requiredPath(RESOURCES_PROPERTY),
            locales = requiredPath(LOCALES_PROPERTY),
            rootCache = profileRoot,
            log = profileRoot.resolve("cef.log"),
            remoteDebuggingPort = port,
        ),
    )
    val appPaths = JvmKWebAppPaths.open(
        requiredPath(SERVICES_LIBRARY_PROPERTY),
        KWebAppPathsConfiguration(
            applicationId = "io.github.kwebshell.migration.fixture",
            applicationDataRoot = appRoot.toString(),
            sessionDataRoot = sessionRoot.toString(),
        ),
    )
    engine.nativeServices.install(KWebAppPaths.Key, appPaths)
    val profile = runBlocking { engine.openProfile("electron-migration-fixture") }
    val cdp = KWebExampleCdpClient(port, 30_000)
    val allowPolicy = KWebServicePermissionPolicy.exact(
        setOf(KWebServiceGrant(KWebAppPaths.DESCRIPTOR.id, "resolve")),
    )
    val denyPolicy = KWebServicePermissionPolicy.exact(emptySet())
    val slowDispatcher = SlowAppPathsDispatcher(appPaths.bridgeDispatcher(allowPolicy))
    var activePage: KWebPage? = null
    var failure: Throwable? = null
    try {
        val directHome = runBlocking { appPaths.resolve(KWebAppPathKind.HOME) }.path
        val directDownloads = runBlocking { appPaths.resolve(KWebAppPathKind.DOWNLOADS) }.path
        val page = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(window, server.origin, slowDispatcher),
                server.indexUrl,
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = page
        cdp.awaitPage(server.indexUrl)
        cdp.openPageSession(server.indexUrl).use { session ->
            session.awaitExpression("typeof globalThis.desktop === 'object'")
            val paths = session.evaluateString(
                "(async()=>JSON.stringify(await Promise.all([window.desktop.getPath('home'), window.desktop.getPath('downloads')])))()",
            )
            val pathValues = Json.parseToJsonElement(paths).jsonArray.map { it.jsonPrimitive.content }
            require(pathValues == listOf(directHome, directDownloads)) {
                "Concurrent preload calls crossed responses: $pathValues"
            }
            require(session.evaluateString("typeof document.getElementById('fixture-frame').contentWindow.desktop") == "undefined")
            require(session.evaluateString("typeof document.getElementById('fixture-frame').contentWindow.__kwebBridgeQuery") == "undefined")
            require(
                session.evaluateString(
                    "(async()=>{try{await window.desktop.getPath('logs');return 'unexpected'}catch(e){return e.code}})()",
                ) == "migration.path-name.unsupported",
            )

            require(
                session.evaluateString(
                    """
                    (()=>{globalThis.__musicAbortController=new AbortController();globalThis.__musicCall=window.desktop.getPath('music',{signal:globalThis.__musicAbortController.signal});return 'started'})()
                    """.trimIndent(),
                ) == "started",
            )
            slowDispatcher.awaitStarted("music")
            val abortCode = session.evaluateString(
                """
                (async()=>{globalThis.__musicAbortController.abort();try{await globalThis.__musicCall;return 'unexpected'}catch(e){return e.code}finally{delete globalThis.__musicAbortController;delete globalThis.__musicCall}})()
                """.trimIndent(),
            )
            require(abortCode == "bridge.call.cancelled") { "AbortSignal returned '$abortCode'." }
            slowDispatcher.awaitCancelled("music")

            val timeoutCode = session.evaluateString(
                "(async()=>{try{await window.desktop.getPath('pictures',{timeoutMs:25});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(timeoutCode == "bridge.call.timeout") { "Timeout returned '$timeoutCode'." }
            slowDispatcher.awaitCancelled("pictures")

            require(session.evaluateString("void (globalThis.__navigationCall=window.desktop.getPath('videos'));'started'") == "started")
            slowDispatcher.awaitStarted("videos")
        }
        runBlocking { page.navigate(server.crossOriginUrl) }
        slowDispatcher.awaitCancelled("videos")
        cdp.awaitPage(server.crossOriginUrl)
        cdp.openPageSession(server.crossOriginUrl).use { session ->
            require(session.evaluateString("typeof globalThis.desktop") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }

        runBlocking { page.navigate(server.indexUrl) }
        cdp.awaitPage(server.indexUrl)
        val ownerSession = cdp.openPageSession(server.indexUrl)
        ownerSession.awaitExpression("typeof globalThis.desktop === 'object'")
        require(ownerSession.evaluateString("void (globalThis.__ownerCall=window.desktop.getPath('documents'));'started'") == "started")
        slowDispatcher.awaitStarted("documents")
        ownerSession.close()
        page.close()
        activePage = null
        slowDispatcher.awaitCancelled("documents")

        val deniedPage = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(window, server.origin, appPaths.bridgeDispatcher(denyPolicy)),
                "${server.indexUrl}?denied",
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = deniedPage
        cdp.awaitPage("${server.indexUrl}?denied")
        cdp.openPageSession("${server.indexUrl}?denied").use { session ->
            session.awaitExpression("typeof globalThis.desktop === 'object'")
            val code = session.evaluateString(
                "(async()=>{try{await window.desktop.getPath('home');return 'unexpected'}catch(e){return e.code}})()",
            )
            require(code == "service.permission-denied") { "Denied page returned '$code'." }
        }
        deniedPage.close()
        activePage = null

        val unconfiguredPage = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(window),
                "${server.indexUrl}?unconfigured",
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = unconfiguredPage
        cdp.awaitPage("${server.indexUrl}?unconfigured")
        cdp.openPageSession("${server.indexUrl}?unconfigured").use { session ->
            require(session.evaluateString("typeof globalThis.desktop") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        unconfiguredPage.close()
        activePage = null
        profile.close()
        engine.close()
        require(appPaths.lifecycle.value == KWebLifecycleState.CLOSED)
        require(engine.nativeServices.lifecycle.value == KWebLifecycleState.CLOSED)
    } catch (error: Throwable) {
        failure = error
    } finally {
        try {
            activePage?.let { page ->
                if (page.lifecycle.value != KWebLifecycleState.CLOSED) page.close()
            }
            activePage = null
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (profile.lifecycle.value != KWebLifecycleState.CLOSED) profile.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (engine.lifecycle.value != KWebLifecycleState.CLOSED) engine.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (appPaths.lifecycle.value != KWebLifecycleState.CLOSED) appPaths.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            onAwtThread { window.dispose() }
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            server.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
    }
    failure?.let { throw it }
    println("KWebShell typed Electron migration fixture passed against real CEF.")
}

private class SlowAppPathsDispatcher(
    private val delegate: KWebBridgeDispatcher,
) : KWebBridgeDispatcher {
    private val started = ConcurrentHashMap<String, CountDownLatch>()
    private val cancelled = ConcurrentHashMap<String, CountDownLatch>()

    override suspend fun dispatch(requestJson: String): String {
        val request = KWebBridgeProtocol.decodeRequest(requestJson)
        val kind = request.payload.jsonObject["kind"]?.jsonPrimitive?.content.orEmpty()
        if (kind in SLOW_KINDS) {
            started.computeIfAbsent(kind) { CountDownLatch(1) }.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.computeIfAbsent(kind) { CountDownLatch(1) }.countDown()
            }
        }
        return delegate.dispatch(requestJson)
    }

    fun awaitStarted(kind: String) {
        require(started.computeIfAbsent(kind) { CountDownLatch(1) }.await(30, TimeUnit.SECONDS)) {
            "The '$kind' bridge request did not start."
        }
    }

    fun awaitCancelled(kind: String) {
        require(cancelled.computeIfAbsent(kind) { CountDownLatch(1) }.await(30, TimeUnit.SECONDS)) {
            "The '$kind' bridge request was not cancelled."
        }
    }

    private companion object {
        val SLOW_KINDS = setOf("music", "pictures", "videos", "documents")
    }
}

private class MigrationFixtureServer(
    appPathsBridge: String,
    preload: String,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "KWebShell-migration-fixture-http").apply { isDaemon = true }
    }
    private val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cross = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val index = (
        "<!doctype html><meta charset=\"utf-8\"><title>KWebShell Migration Fixture</title>" +
            "<iframe id=\"fixture-frame\" src=\"/frame\"></iframe>" +
            "<script>$appPathsBridge</script><script>$preload</script>"
        ).toByteArray(StandardCharsets.UTF_8)

    init {
        allowed.executor = executor
        allowed.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/frame") {
                "<!doctype html><meta charset=\"utf-8\"><title>frame</title>".toByteArray(StandardCharsets.UTF_8)
            } else if (exchange.requestURI.query == "unconfigured") {
                "<!doctype html><meta charset=\"utf-8\"><title>unconfigured</title>".toByteArray(StandardCharsets.UTF_8)
            } else {
                index
            }
            send(exchange, body)
        }
        cross.executor = executor
        cross.createContext("/") { exchange ->
            send(exchange, "<!doctype html><meta charset=\"utf-8\"><title>cross</title>".toByteArray(StandardCharsets.UTF_8))
        }
        allowed.start()
        cross.start()
    }

    val origin: String
        get() = "http://127.0.0.1:${allowed.address.port}"
    val indexUrl: String
        get() = "$origin/index.html"
    val crossOriginUrl: String
        get() = "http://127.0.0.1:${cross.address.port}/cross"

    override fun close() {
        allowed.stop(0)
        cross.stop(0)
        executor.shutdownNow()
        require(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun send(exchange: HttpExchange, body: ByteArray) {
        try {
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            exchange.close()
        }
    }
}

private fun KWebExampleCdpSession.awaitExpression(expression: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline) {
        val value = runCatching { evaluateString(expression) }.getOrNull()
        if (value == "true") return
        Thread.sleep(50)
    }
    error("CDP expression did not become true: $expression")
}

private fun KWebExampleCdpSession.evaluateString(expression: String): String =
    evaluate(expression).value?.jsonPrimitive?.content
        ?: error("CDP expression returned no primitive value: $expression")

private fun requiredPath(name: String): Path =
    Path.of(System.getProperty(name) ?: error("Missing system property '$name'."))

private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
    socket.localPort.takeIf { it in 1024..65535 } ?: error("The OS allocated an invalid CDP port.")
}

private fun <T> onAwtThread(operation: () -> T): T {
    if (EventQueue.isDispatchThread()) return operation()
    val result = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { result.set(runCatching(operation)) }
    return result.get().getOrThrow()
}

private fun Throwable?.append(error: Throwable): Throwable = this?.also { current ->
    if (current !== error) current.addSuppressed(error)
} ?: error
