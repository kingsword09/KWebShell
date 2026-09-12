package io.github.kingsword09.kwebshell.service.dialogs

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngine
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.desktop.KWebPageDispatcherFactory
import io.github.kingsword09.kwebshell.services.KWebCapabilityFact
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.consent.KWebFileConsentStore
import io.github.kingsword09.kwebshell.services.consent.LinuxPortalPermissionStoreConsentProvider
import io.github.kingsword09.kwebshell.services.consent.MacOsTccConsentProvider
import io.github.kingsword09.kwebshell.services.consent.WindowsCapabilityAccessConsentProvider
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Window
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

public fun main(): Unit = runBlocking {
    val evidence = requiredPath("kweb.dialogs.integration.root")
    Files.createDirectories(evidence)
    val root = Files.createTempDirectory(evidence, "run-")
    val fileRoot = Files.createTempDirectory("kweb-dialogs-")
    val input = Files.write(fileRoot.resolve("input.txt"), ByteArray(KWEB_DIALOGS_MAX_TRANSFER_BYTES) { 255.toByte() })
    val output = fileRoot.resolve("output.txt")
    val window = onDialogsAwtThread {
        ComposeWindow().apply {
            title = "KWebShell native dialogs integration"
            setBounds(140, 140, 900, 650)
            isVisible = true
        }
    }
    val visibleWindows = visibleWindows()
    val selector = NativeFileDialogSelector(
        window,
        requiredPath("kweb.dialogs.library"),
        fileRoot,
        mapOf(KWebFileDialogMode.OPEN to input.fileName.toString(), KWebFileDialogMode.SAVE to output.fileName.toString()),
    )
    val service = JvmKWebDialogs.openForTesting(window, selector)
    val server = DialogsServer(Files.readString(requiredPath("kweb.dialogs.bridge.javascript")))
    var engine: KWebDesktopEngine? = null
    var profile: KWebProfile? = null
    val pages = mutableListOf<KWebPage>()
    var failure: Throwable? = null
    try {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val gestures = RecordingGestureIssuer()
        val engineId = "engine-dialogs-fixture"
        val liveEngine = KWebDesktop.openEngine(KWebDesktopEngineConfiguration(
            cefRuntime = requiredPath("kweb.engine.cef.runtime.path"),
            browserSubprocess = requiredPath("kweb.engine.subprocess.path"),
            resources = requiredPath("kweb.engine.resources.path"),
            locales = requiredPath("kweb.engine.locales.path"),
            rootCache = Files.createDirectories(root.resolve("profiles")),
            log = root.resolve("profiles/cef.log"),
            remoteDebuggingPort = port,
            userGestureIssuer = gestures,
            engineId = engineId,
        ))
        engine = liveEngine
        liveEngine.nativeServices.install(KWebDialogs.Key, service)
        check(liveEngine.nativeServices.require(KWebDialogs.Key) === service)
        val liveProfile = liveEngine.openProfile("dialogs")
        profile = liveProfile
        val cdp = KWebExampleCdpClient(port, 30_000)
        val grants = KWebServicePermissionPolicy.exact(KWebDialogs.DESCRIPTOR.operations.map {
            KWebServiceGrant(KWebDialogs.DESCRIPTOR.id, it.id)
        }.toSet())
        val policyEngine = KWebServicePolicyEngine(
            rendererGrants = grants,
            gestures = gestures,
            consentStore = KWebInMemoryConsentStore("dialogs-fixture"),
            osConsent = null,
            audit = io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit(),
        )
        val allowed = liveProfile.openPage(
            KWebDesktop.composeWindowHost(
                window,
                server.origin,
                KWebPageDispatcherFactory { pageId ->
                    service.bridgeDispatcher(
                        policyEngine,
                        KWebPolicySubject(
                            engineId = engineId,
                            profileId = "dialogs",
                            pageId = pageId,
                            origin = server.origin,
                            scope = KWebServiceScope.APPLICATION,
                        ),
                    )
                },
            ),
            server.url, KWebRect(0, 0, 820, 550),
        )
        pages += allowed
        cdp.awaitPage(server.url)
        var readHandle: String? = null
        cdp.openPageSession(server.url).use { session ->
            session.awaitTrue("typeof DialogsBridge === 'object'")
            val opened = selectThroughRenderer(session, selector, KWebFileDialogMode.OPEN, input)
            check(opened["name"]!!.jsonPrimitive.content == "input.txt")
            check(opened["sizeBytes"]!!.jsonPrimitive.content == KWEB_DIALOGS_MAX_TRANSFER_BYTES.toString())
            check(opened.keys == setOf("selected", "handle", "name", "sizeBytes", "mode"))
            check(!opened.toString().contains(root.toString()))
            val handle = opened["handle"]!!.jsonPrimitive.content
            readHandle = handle
            val read = session.json("DialogsBridge.createClient().readFile({handle:${json(handle)},offset:'0',length:$KWEB_DIALOGS_MAX_TRANSFER_BYTES})")
            check(read["bytes"]!!.jsonArray.size == KWEB_DIALOGS_MAX_TRANSFER_BYTES)
            check(read["bytes"]!!.jsonArray.all { it.jsonPrimitive.content == "255" })
            check(read["eof"]!!.jsonPrimitive.content == "true")
            // The write-file operation is gated on a native-verified user gesture.
            val writeFileOnReadHandle = "DialogsBridge.createClient().writeFile({handle:${json(handle)},offset:'0',bytes:[1]})"
            check(session.failureCode(writeFileOnReadHandle) == "service.user-gesture-required") {
                "A renderer call without a gesture must not reach the service."
            }
            // A renderer-synthesized DOM event cannot mint a gesture.
            session.string(
                "document.dispatchEvent(new KeyboardEvent('keydown', {key:'a'})); 'dispatched'",
            )
            check(session.failureCode(writeFileOnReadHandle) == "service.user-gesture-required") {
                "A synthetic DOM event must never mint a user gesture."
            }
            // A browser-process key event follows the exact native input path a
            // real keystroke takes (a synthetic DOM event never leaves the
            // renderer) and mints one gesture.
            sendGestureKeystroke(session)
            var mintCount = awaitMint(gestures, 0)
            check(session.failureCode(writeFileOnReadHandle) == KWebDialogsErrorCode.HANDLE_MODE) {
                "The consumed gesture must carry the call to the service handler (handle-mode error)."
            }
            // The gesture was consumed once: a replay is denied.
            check(session.failureCode(writeFileOnReadHandle) == "service.user-gesture-required")

            // A main-frame navigation invalidates an outstanding gesture even
            // when no renderer call consumed it first.
            sendGestureKeystroke(session)
            awaitMint(gestures, mintCount)
            val gestureNavUrl = "${server.url}?gesture-nav"
            allowed.navigate(gestureNavUrl)
            cdp.awaitPage(gestureNavUrl)
            cdp.openPageSession(gestureNavUrl).use { navSession ->
                navSession.awaitTrue("typeof DialogsBridge === 'object'")
                check(navSession.failureCode(writeFileOnReadHandle) == "service.user-gesture-required") {
                    "An outstanding gesture must be invalidated by a main-frame navigation."
                }
            }

            val saved = selectThroughRenderer(session, selector, KWebFileDialogMode.SAVE, output)
            val saveHandle = saved["handle"]!!.jsonPrimitive.content
            check(saveHandle != handle)
            sendGestureKeystroke(session)
            awaitMint(gestures, mintCount)
            val written = session.json("DialogsBridge.createClient().writeFile({handle:${json(saveHandle)},offset:'0',bytes:Array($KWEB_DIALOGS_MAX_TRANSFER_BYTES).fill(255)})")
            check(written["written"]!!.jsonPrimitive.content == KWEB_DIALOGS_MAX_TRANSFER_BYTES.toString())
            check(session.json("DialogsBridge.createClient().truncateFile({handle:${json(saveHandle)},sizeBytes:'3'})")["sizeBytes"]!!.jsonPrimitive.content == "3")
            check(session.json("DialogsBridge.createClient().closeFile({handle:${json(saveHandle)}})")["closed"]!!.jsonPrimitive.content == "true")
            check(Files.readAllBytes(output).toList() == List(3) { 255.toByte() })
            check(session.failureCode("DialogsBridge.createClient().closeFile({handle:${json(saveHandle)}})") == KWebDialogsErrorCode.HANDLE_NOT_FOUND)

            val cancelled = async(Dispatchers.Default) {
                runCatching { session.json(selectionCall(KWebFileDialogMode.OPEN)) }
            }
            cancelNativePicker(selector, cancelled)
            val cancellation = cancelled.await().getOrThrow()
            check(cancellation["selected"]!!.jsonPrimitive.content == "false")
            check(cancellation.filterKeys { it != "selected" }.values.all { it.toString() == "null" })

            session.string("globalThis.abort = new AbortController(); globalThis.aborted = null; void DialogsBridge.createClient().selectFile({mode:'open',title:'Abort',defaultName:null,filters:[]},{signal:abort.signal}).catch(e => {globalThis.aborted=e.code}); 'started'")
            val observer = async(Dispatchers.Default) { session.awaitTrue("globalThis.aborted !== null") }
            awaitNativePicker(selector, observer)
            session.string("abort.abort(); 'aborted'")
            withTimeout(5_000) { observer.await(); while (selector.isVisible()) delay(10) }
            check(session.string("globalThis.aborted") == "bridge.call.cancelled")

            session.awaitTrue("typeof document.getElementById('child').contentWindow.DialogsBridge === 'object'")
            check(session.string("typeof document.getElementById('child').contentWindow.__kwebBridgeQuery") == "undefined")
            check(session.failureCode("document.getElementById('child').contentWindow.DialogsBridge.createClient().readFile({handle:${json(handle)},offset:'0',length:1})") == "bridge.transport.failed")
        }

        val deniedUrl = "${server.url}?denied"
        val denied = liveProfile.openPage(KWebDesktop.composeWindowHost(window, server.origin,
            service.bridgeDispatcher(KWebServicePermissionPolicy.exact(emptySet()))), deniedUrl, KWebRect(0, 0, 820, 550))
        pages += denied
        cdp.awaitPage(deniedUrl)
        cdp.openPageSession(deniedUrl).use { session ->
            session.awaitTrue("typeof DialogsBridge === 'object'")
            check(session.failureCode(selectionCall(KWebFileDialogMode.OPEN)) == "service.permission-denied")
            check(!selector.isVisible())
        }
        denied.close()
        pages.remove(denied)
        allowed.navigate(server.crossUrl)
        cdp.awaitPage(server.crossUrl)
        cdp.openPageSession(server.crossUrl).use { session ->
            check(session.string("typeof __kwebBridgeQuery") == "undefined")
        }
        allowed.close()
        pages.remove(allowed)
        val unconfiguredUrl = "${server.url}?unconfigured"
        val unconfigured = liveProfile.openPage(KWebDesktop.composeWindowHost(window), unconfiguredUrl, KWebRect(0, 0, 820, 550))
        pages += unconfigured
        cdp.awaitPage(unconfiguredUrl)
        cdp.openPageSession(unconfiguredUrl).use { session ->
            check(session.string("typeof __kwebBridgeQuery") == "undefined")
        }
        unconfigured.close()
        pages.remove(unconfigured)
        liveProfile.close()
        profile = null
        // Keep a native operation live while the Engine closes its installed provider.
        val pending = async(Dispatchers.Default) {
            runCatching { service.selectFile(KWebFileDialogRequest(KWebFileDialogMode.SAVE, "Engine close")) }
        }
        awaitNativePicker(selector, pending)
        liveEngine.close()
        engine = null
        val ownerFailure = pending.await().exceptionOrNull()
        check(ownerFailure is io.github.kingsword09.kwebshell.core.KWebNativeException && ownerFailure.code == "service.owner-closed")
        check(service.lifecycle.value == KWebLifecycleState.CLOSED)
        check(!selector.isVisible())
        val readAfterClose = runCatching { service.readFile(requireNotNull(readHandle), 0, 1) }.exceptionOrNull()
        check(readAfterClose is io.github.kingsword09.kwebshell.core.KWebNativeException && readAfterClose.code == "service.owner-closed")
        cdp.assertUnavailable()
        check(onDialogsAwtThread { window.isShowing && window.isDisplayable })
        check(visibleWindows() == visibleWindows)
        val osConsent = when (KWebTarget.parse(currentTargetId()).operatingSystem.id) {
            "windows" -> WindowsCapabilityAccessConsentProvider("webcam")
            "macos" -> MacOsTccConsentProvider("accessibility")
            else -> LinuxPortalPermissionStoreConsentProvider("kwebshell-test")
        }
        val consentStatus = runCatching {
            osConsent.status(
                io.github.kingsword09.kwebshell.services.policy.KWebConsentRequest(
                    KWebDialogs.DESCRIPTOR.id, "write-file", server.origin, osConsent.facility,
                ),
            )
        }
        Files.writeString(
            evidence.resolve("consent-status.json"),
            "{\"facility\": \"${'$'}{osConsent.facility}\", \"status\": \"${'$'}{consentStatus.getOrNull()}\", " +
                "\"queriedThrough\": \"integration\", \"note\": \"real OS consent state, retained as-is\"}\n",
        )
        Files.writeString(root.resolve("passed.txt"), "Native selection, bounded IO, renderer gestures, origins, permissions, and Engine shutdown passed.\n")
        println("KWebShell dialogs passed real native selection and exact-origin CEF integration: $root")
    } catch (error: Throwable) {
        failure = error
    } finally {
        val cleanup = listOf<() -> Unit>(
            { pages.asReversed().forEach { it.close() } },
            { profile?.close() }, { engine?.close() }, { service.close() },
            { onDialogsAwtThread { window.dispose() } }, { server.close() },
            { Files.deleteIfExists(output); Files.deleteIfExists(input); Files.deleteIfExists(fileRoot) },
        )
        cleanup.forEach { action ->
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
            }
        }
    }
    failure?.let { throw it }
}

private class RecordingGestureIssuer(
    private val delegate: KWebUserGestureRegistry = KWebUserGestureRegistry(),
) : io.github.kingsword09.kwebshell.services.policy.KWebUserGestureIssuer {
    val minted = java.util.concurrent.atomic.AtomicInteger(0)

    override fun mint(binding: io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding) =
        delegate.mint(binding).also { minted.incrementAndGet() }

    override fun consumeLatest(binding: io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding) =
        delegate.consumeLatest(binding)

    override fun current(binding: io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding) =
        delegate.current(binding)

    override fun invalidateNavigation(pageId: String) = delegate.invalidateNavigation(pageId)

    override fun invalidatePage(pageId: String) = delegate.invalidatePage(pageId)
}

private fun awaitMint(issuer: RecordingGestureIssuer, previousCount: Int): Int {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        val observed = issuer.minted.get()
        if (observed > previousCount) return observed
        Thread.sleep(25)
    }
    error("The native input path never minted a user gesture")
}

private fun sendGestureKeystroke(session: KWebExampleCdpSession) {
    // Injected through the browser-process input pipeline: OnPreKeyEvent sees
    // this exactly like a physical keystroke, while a renderer-synthesized
    // DOM KeyboardEvent can never reach it.
    session.command(
        "Input.dispatchKeyEvent",
        buildJsonObject {
            put("type", "rawKeyDown")
            put("windowsVirtualKeyCode", 65)
            put("nativeVirtualKeyCode", 65)
            put("key", "a")
            put("code", "KeyA")
        },
    )
}

private suspend fun selectThroughRenderer(
    session: KWebExampleCdpSession,
    selector: NativeFileDialogSelector,
    mode: KWebFileDialogMode,
    path: Path,
): JsonObject = coroutineScope {
    val pending = async(Dispatchers.Default) { runCatching { session.json(selectionCall(mode, path.fileName.toString())) } }
    chooseNativeFile(selector, pending, path, mode)
    pending.await().getOrThrow().also { check(it["selected"]!!.jsonPrimitive.content == "true") }
}

private fun selectionCall(mode: KWebFileDialogMode, defaultName: String? = null): String =
    "DialogsBridge.createClient().selectFile({mode:${json(mode.id)},title:'KWebShell CEF selection',defaultName:${defaultName?.let(::json) ?: "null"},filters:[{description:'Text',extensions:['txt']}]})"

private fun KWebExampleCdpSession.awaitTrue(expression: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        if (string(expression) == "true") return
        Thread.sleep(20)
    }
    error("CDP condition did not become true: $expression")
}

private fun KWebExampleCdpSession.string(expression: String): String =
    requireNotNull(evaluate(expression).value).jsonPrimitive.content

private fun KWebExampleCdpSession.json(expression: String): JsonObject =
    Json.parseToJsonElement(string("(async()=>JSON.stringify(await ($expression)))()")).jsonObject

private fun KWebExampleCdpSession.failureCode(expression: String): String =
    string("(async()=>{try{await ($expression);return 'unexpected-success'}catch(e){return e.code}})()")

private fun currentTargetId(): String {
    val operatingSystem = System.getProperty("os.name").lowercase().let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "macos"
            else -> "linux"
        }
    }
    val architecture = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x64"
        else -> "arm64"
    }
    return "$operatingSystem-$architecture"
}

private fun json(value: String): String = Json.encodeToString(value)
private fun requiredPath(name: String): Path = Path.of(requireNotNull(System.getProperty(name)) { "Missing $name" })
private fun visibleWindows(): Set<Window> = onDialogsAwtThread { Window.getWindows().filter { it.isShowing }.toSet() }

private class DialogsServer(bridge: String) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task -> Thread(task, "dialogs-http").apply { isDaemon = true } }
    private val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cross = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    val origin: String get() = "http://127.0.0.1:${allowed.address.port}"
    val url: String get() = "$origin/index.html"
    val crossUrl: String get() = "http://127.0.0.1:${cross.address.port}/cross"

    init {
        allowed.executor = executor
        allowed.createContext("/") { exchange ->
            val child = if (exchange.requestURI.path == "/frame") "" else "<iframe id='child' src='/frame'></iframe>"
            send(exchange, "<!doctype html><meta charset='utf-8'><title>Dialogs</title>$child<script>$bridge</script>")
        }
        cross.executor = executor
        cross.createContext("/") { exchange -> send(exchange, "<!doctype html><title>Cross origin</title><script>$bridge</script>") }
        allowed.start()
        cross.start()
    }

    private fun send(exchange: HttpExchange, html: String) {
        try {
            val bytes = html.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } finally { exchange.close() }
    }

    override fun close() {
        allowed.stop(0)
        cross.stop(0)
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}
