package io.github.kingsword09.kwebshell.service.windowcontrols

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngine
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.EventQueue
import java.awt.Window
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val ROOT_PROPERTY = "kweb.window-controls.integration.root"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val BRIDGE_JAVASCRIPT_PROPERTY = "kweb.window-controls.bridge.javascript"

public fun main() {
    val root = requiredPath(ROOT_PROPERTY).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val profileRoot = root.resolve("profiles")
    Files.createDirectories(profileRoot)
    val server = WindowControlsServer(Files.readString(requiredPath(BRIDGE_JAVASCRIPT_PROPERTY)))
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell window controls"
            setBounds(120, 120, 900, 650)
            isVisible = true
            require(isDisplayable && isShowing && windowHandle != 0L)
        }
    }
    val visibleWindows = visibleWindows()
    val service = JvmKWebWindowControls.open(window)
    val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val events = CopyOnWriteArrayList<KWebWindowEvent>()
    val eventJob = eventScope.launch { service.events.collect(events::add) }
    var engine: KWebDesktopEngine? = null
    var profile: KWebProfile? = null
    val pages = mutableListOf<KWebPage>()
    var failure: Throwable? = null
    try {
        exerciseDirectControls(service, window)
        val cdpPort = findFreePort()
        val liveEngine = KWebDesktop.openEngine(
            KWebDesktopEngineConfiguration(
                cefRuntime = requiredPath(CEF_RUNTIME_PROPERTY),
                browserSubprocess = requiredPath(SUBPROCESS_PROPERTY),
                resources = requiredPath(RESOURCES_PROPERTY),
                locales = requiredPath(LOCALES_PROPERTY),
                rootCache = profileRoot,
                log = profileRoot.resolve("cef.log"),
                remoteDebuggingPort = cdpPort,
            ),
        )
        engine = liveEngine
        liveEngine.nativeServices.install(KWebWindowControls.Key, service)
        require(liveEngine.nativeServices.require(KWebWindowControls.Key) === service)
        val liveProfile = runBlocking { liveEngine.openProfile("window-controls") }
        profile = liveProfile
        val cdp = KWebExampleCdpClient(cdpPort, 30_000)
        val allowPolicy = KWebServicePermissionPolicy.exact(
            KWebWindowControls.DESCRIPTOR.operations.mapTo(mutableSetOf()) { operation ->
                KWebServiceGrant(KWebWindowControls.DESCRIPTOR.id, operation.id)
            },
        )
        val allowedPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(window, server.origin, service.bridgeDispatcher(allowPolicy)),
                server.indexUrl,
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += allowedPage
        cdp.awaitPage(server.indexUrl)
        cdp.openPageSession(server.indexUrl).use { session ->
            session.awaitTrue("typeof globalThis.WindowControlsBridge === 'object'")
            val title = "Renderer-controlled title🙂"
            val titleState = session.evaluateJson(
                "WindowControlsBridge.createClient().setTitle({title:${Json.encodeToString(title)}})",
            )
            require(titleState["title"]?.jsonPrimitive?.content == title)
            val floatingState = session.evaluateJson(
                "WindowControlsBridge.createClient().setMaximized({enabled:false})",
            )
            require(floatingState["placement"]?.jsonPrimitive?.content == "floating")
            val boundsState = session.evaluateJson(
                "WindowControlsBridge.createClient().setBounds({x:160,y:170,width:940,height:700})",
            )
            require(boundsState["width"]?.jsonPrimitive?.content == "940")
            require(boundsState["height"]?.jsonPrimitive?.content == "700")
            require(
                session.evaluateString(
                    "typeof document.getElementById('window-frame').contentWindow.WindowControlsBridge",
                ) == "object",
            )
            require(
                session.evaluateString(
                    "typeof document.getElementById('window-frame').contentWindow.__kwebBridgeQuery",
                ) == "undefined",
            )
            require(
                session.evaluateString(
                    "(async()=>{try{await document.getElementById('window-frame').contentWindow.WindowControlsBridge.createClient().getState({enabled:true});return 'unexpected'}catch(e){return e.code}})()",
                ) == "bridge.transport.failed",
            )
            val state = session.evaluateJson(
                "WindowControlsBridge.createClient().getState({enabled:true})",
            )
            require(state["placement"]?.jsonPrimitive?.content == "floating")
            require(state["resizable"]?.jsonPrimitive?.content == "true")
        }

        val deniedPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(
                    window,
                    server.origin,
                    service.bridgeDispatcher(KWebServicePermissionPolicy.exact(emptySet())),
                ),
                "${server.indexUrl}?denied",
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += deniedPage
        cdp.awaitPage("${server.indexUrl}?denied")
        cdp.openPageSession("${server.indexUrl}?denied").use { session ->
            session.awaitTrue("typeof globalThis.WindowControlsBridge === 'object'")
            require(
                session.evaluateString(
                    "(async()=>{try{await WindowControlsBridge.createClient().setTitle({title:'denied'});return 'unexpected'}catch(e){return e.code}})()",
                ) == KWebServiceErrorCode.PERMISSION_DENIED,
            )
        }
        deniedPage.close()
        pages.remove(deniedPage)

        runBlocking { allowedPage.navigate(server.crossOriginUrl) }
        cdp.awaitPage(server.crossOriginUrl)
        cdp.openPageSession(server.crossOriginUrl).use { session ->
            require(session.evaluateString("typeof globalThis.WindowControlsBridge") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        allowedPage.close()
        pages.remove(allowedPage)

        val unconfiguredPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(window),
                "${server.indexUrl}?unconfigured",
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += unconfiguredPage
        cdp.awaitPage("${server.indexUrl}?unconfigured")
        cdp.openPageSession("${server.indexUrl}?unconfigured").use { session ->
            require(session.evaluateString("typeof globalThis.WindowControlsBridge") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        unconfiguredPage.close()
        pages.remove(unconfiguredPage)

        liveProfile.close()
        profile = null
        liveEngine.close()
        engine = null
        cdp.assertUnavailable()
        require(service.lifecycle.value == KWebLifecycleState.CLOSED)
        require(onAwtThread { window.isDisplayable && window.isShowing }) {
            "Closing the Engine disposed the caller-owned ComposeWindow."
        }
        require(visibleWindows() == visibleWindows) {
            "Window controls or CEF created an unexpected visible top-level window."
        }
        require(events.isNotEmpty()) { "Window controls emitted no ordered state events." }
        require(events.map { it.sequence } == (1L..events.size.toLong()).toList()) {
            "Window control event sequence is not contiguous: $events"
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        pages.toList().asReversed().forEach { page ->
            try {
                if (page.lifecycle.value != KWebLifecycleState.CLOSED) page.close()
            } catch (error: Throwable) {
                failure = failure.append(error)
            }
        }
        try {
            profile?.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            engine?.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            service.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        runBlocking { eventJob.cancelAndJoin() }
        eventScope.cancel()
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
    verifyDisposedOwnerClosesService()
    println("KWebShell Compose window controls passed direct and exact-origin CEF integration.")
}

private fun exerciseDirectControls(service: KWebWindowControls, window: ComposeWindow) {
    runBlocking {
        require(service.snapshot().placement == KWebWindowPlacement.FLOATING)
        val invalidTitle = runCatching { service.setTitle("invalid\u0000title") }.exceptionOrNull()
        require(invalidTitle is io.github.kingsword09.kwebshell.core.KWebConfigurationException &&
            invalidTitle.code == KWebServiceErrorCode.REQUEST_INVALID
        )
        require(service.setTitle("KWebShell direct title").title == "KWebShell direct title")
        require(service.setBounds(KWebWindowBounds(140, 145, 920, 680)).bounds == KWebWindowBounds(140, 145, 920, 680))
        require(!service.setResizable(false).resizable)
        require(service.setResizable(true).resizable)
        require(!service.setVisible(false).visible)
        val hiddenFocus = runCatching { service.focus() }.exceptionOrNull()
        require(hiddenFocus is KWebNativeException && hiddenFocus.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE)
        require(service.setVisible(true).visible)
        service.focus()
        require(service.minimize().minimized)
        val restored = service.restore()
        require(!restored.minimized && restored.placement == KWebWindowPlacement.FLOATING)
        require(service.setMaximized(true).placement == KWebWindowPlacement.MAXIMIZED)
        val maximizedBounds = runCatching {
            service.setBounds(KWebWindowBounds(100, 100, 800, 600))
        }.exceptionOrNull()
        require(maximizedBounds is KWebNativeException &&
            maximizedBounds.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE
        )
        require(service.setMaximized(false).placement == KWebWindowPlacement.FLOATING)
        if (onAwtThread { window.isAlwaysOnTopSupported }) {
            require(service.setAlwaysOnTop(true).alwaysOnTop)
            require(!service.setAlwaysOnTop(false).alwaysOnTop)
        } else {
            val failure = runCatching { service.setAlwaysOnTop(true) }.exceptionOrNull()
            require(failure is KWebNativeException && failure.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE)
        }
    }
}

private fun verifyDisposedOwnerClosesService() {
    val window = onAwtThread {
        ComposeWindow().apply {
            setSize(320, 240)
            isVisible = true
        }
    }
    val service = JvmKWebWindowControls.open(window)
    onAwtThread { window.dispose() }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (service.lifecycle.value != KWebLifecycleState.CLOSED && System.nanoTime() < deadline) {
        Thread.sleep(25)
    }
    require(service.lifecycle.value == KWebLifecycleState.CLOSED)
    val failure = runCatching { runBlocking { service.snapshot() } }.exceptionOrNull()
    require(failure is KWebNativeException && failure.code == KWebServiceErrorCode.OWNER_CLOSED)
}

private class WindowControlsServer(bridgeJavascript: String) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "KWebShell-window-controls-http").apply { isDaemon = true }
    }
    private val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cross = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val index = (
        "<!doctype html><meta charset=\"utf-8\"><title>window controls</title>" +
            "<iframe id=\"window-frame\" src=\"/frame\"></iframe>" +
            "<script>$bridgeJavascript</script>"
        ).toByteArray(StandardCharsets.UTF_8)
    private val frame = (
        "<!doctype html><meta charset=\"utf-8\"><title>frame</title>" +
            "<script>$bridgeJavascript</script>"
        ).toByteArray(StandardCharsets.UTF_8)

    init {
        allowed.executor = executor
        allowed.createContext("/") { exchange ->
            val body = when {
                exchange.requestURI.path == "/frame" -> frame
                exchange.requestURI.query == "unconfigured" -> "<!doctype html><title>unconfigured</title>".toByteArray()
                else -> index
            }
            send(exchange, body)
        }
        cross.executor = executor
        cross.createContext("/") { exchange -> send(exchange, "<!doctype html><title>cross</title>".toByteArray()) }
        allowed.start()
        cross.start()
    }

    val origin: String get() = "http://127.0.0.1:${allowed.address.port}"
    val indexUrl: String get() = "$origin/index.html"
    val crossOriginUrl: String get() = "http://127.0.0.1:${cross.address.port}/cross"

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

private fun KWebExampleCdpSession.awaitTrue(expression: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline) {
        if (runCatching { evaluateString(expression) }.getOrNull() == "true") return
        Thread.sleep(50)
    }
    error("CDP expression did not become true: $expression")
}

private fun KWebExampleCdpSession.evaluateString(expression: String): String =
    evaluate(expression).value?.jsonPrimitive?.content
        ?: error("CDP expression returned no primitive: $expression")

private fun KWebExampleCdpSession.evaluateJson(call: String): kotlinx.serialization.json.JsonObject {
    val encoded = evaluateString("(async()=>JSON.stringify(await $call))()")
    return Json.parseToJsonElement(encoded).jsonObject
}

private fun visibleWindows(): Set<Window> = onAwtThread {
    Window.getWindows().filterTo(linkedSetOf()) { it.isShowing }
}

private fun requiredPath(name: String): Path =
    Path.of(System.getProperty(name) ?: error("Missing system property '$name'."))

private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
    socket.localPort.takeIf { it in 1024..65535 } ?: error("The OS allocated an invalid CDP port.")
}

private fun <T> onAwtThread(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val result = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { result.set(runCatching(action)) }
    return result.get().getOrThrow()
}

private fun Throwable?.append(error: Throwable): Throwable = this?.also { current ->
    if (current !== error) current.addSuppressed(error)
} ?: error
