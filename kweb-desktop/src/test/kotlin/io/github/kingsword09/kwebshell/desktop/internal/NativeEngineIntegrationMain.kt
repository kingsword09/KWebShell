package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val INTEGRATION_ROOT_PROPERTY = "kweb.engine.integration.root"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val HOLDER_OPENED_MARKER = "KWEBSHELL_ENGINE_HOLDER_OPENED"
private const val STAGE_PREFIX = "KWEBSHELL_ENGINE_STAGE:"
private const val MACOS_BROWSER_POLICY_MARKER =
    "KWEBSHELL_NATIVE_ENGINE:macos_browser_policy_applied"
private const val MAIN_CLASS =
    "io.github.kingsword09.kwebshell.desktop.internal.NativeEngineIntegrationMainKt"
private const val FIRST_TITLE = "KWebShell profile 第一页🙂"
private const val SECOND_TITLE = "KWebShell navigation 路径🚀"

private enum class IntegrationMode(val argument: String) {
    COORDINATOR("coordinator"),
    SUCCESS("success"),
    CALLBACK_FAILURE("callback-failure"),
    LISTENER_FAILURE("listener-failure"),
    HOLDER("holder"),
    INITIALIZATION_FAILURE("initialization-failure"),
    ;

    companion object {
        fun parse(value: String): IntegrationMode = entries.singleOrNull { it.argument == value }
            ?: error("Unknown native engine integration mode: $value")
    }
}

fun main(arguments: Array<String>) {
    val mode = IntegrationMode.parse(arguments.singleOrNull() ?: IntegrationMode.COORDINATOR.argument)
    when (mode) {
        IntegrationMode.COORDINATOR -> runCoordinator()
        IntegrationMode.SUCCESS -> runSuccessfulLifecycle()
        IntegrationMode.CALLBACK_FAILURE -> runCallbackFailureLifecycle()
        IntegrationMode.LISTENER_FAILURE -> runListenerFailureLifecycle()
        IntegrationMode.HOLDER -> runHolderLifecycle()
        IntegrationMode.INITIALIZATION_FAILURE -> runInitializationFailureLifecycle()
    }
}

private fun runCoordinator() {
    val root = requiredPathProperty(INTEGRATION_ROOT_PROPERTY)
    Files.createDirectories(root)
    runChildAndRequireSuccess(IntegrationMode.SUCCESS, root.resolve("success"))
    runChildAndRequireSuccess(IntegrationMode.CALLBACK_FAILURE, root.resolve("callback-failure"))
    runChildAndRequireSuccess(IntegrationMode.LISTENER_FAILURE, root.resolve("listener-failure"))

    val sharedRoot = root.resolve("initialization-failure")
    val holder = startChild(IntegrationMode.HOLDER, sharedRoot)
    require(holder.opened.await(60, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(holder.process)
        holder.process.destroyForcibly()
        "The holder engine did not reach OnContextInitialized.\n${holder.output()}\n$diagnostics"
    }
    try {
        runChildAndRequireSuccess(IntegrationMode.INITIALIZATION_FAILURE, sharedRoot)
    } finally {
        holder.input.newLine()
        holder.input.flush()
        require(holder.process.waitFor(60, TimeUnit.SECONDS)) {
            val diagnostics = collectTimeoutDiagnostics(holder.process)
            holder.process.destroyForcibly()
            "The holder engine did not shut down in time.\n${holder.output()}\n$diagnostics"
        }
        holder.reader.join(5000)
        require(holder.process.exitValue() == 0) {
            "The holder engine exited with ${holder.process.exitValue()}.\n${holder.output()}"
        }
    }
    println("KWebShell real CEF engine integration passed in isolated JVM processes.")
}

private fun runSuccessfulLifecycle() {
    val events = CopyOnWriteArrayList<NativeEngineEvent>()
    val callbackThreads = CopyOnWriteArrayList<String>()
    val configuration = runtimeConfiguration()
    reportStage("before_open")
    val engine = NativeEngine.open(configuration) { event ->
        events += event
        callbackThreads += Thread.currentThread().name
        if (event.type == NativeEngineEventType.CLOSED) {
            require(NativeEngine.liveNativeEngineCount() == 0L)
        }
    }
    reportStage("opened")
    require(engine.lifecycle.value == KWebLifecycleState.OPEN)
    val handle = events.single { it.type == NativeEngineEventType.OPENED }.handle

    val browserEvents = CopyOnWriteArrayList<NativeBrowserEvent>()
    val firstTitle = CountDownLatch(1)
    val secondTitle = CountDownLatch(1)
    val secondLoad = CountDownLatch(1)
    val resized = CountDownLatch(1)
    val profile = configuration.rootCache.resolve("integration-profile")
    BrowserOrigin().use { origin ->
        val surface = NativeEngine.onAwtEventDispatchThread { AwtBrowserSurface.create(800, 600) }
        try {
            requireCreateFailure(
                NativeEngine.onAwtEventDispatchThread {
                    rawBrowserCreate(
                        engine,
                        surface.component,
                        configuration.rootCache.resolve("nested/profile"),
                        origin.firstUrl,
                        800,
                        600,
                    )
                },
                NativeStatus.PROFILE_PATH_INVALID,
            )
            requireCreateFailure(
                NativeEngine.onAwtEventDispatchThread {
                    rawBrowserCreate(
                        engine,
                        surface.component,
                        configuration.rootCache.resolve("invalid-url-profile"),
                        "not-a-url",
                        800,
                        600,
                    )
                },
                NativeStatus.NAVIGATION_INVALID,
            )
            requireStatus(
                NativeEngine.onAwtEventDispatchThread {
                    NativeBindings.browserResize(0L, 0, 600)
                },
                NativeStatus.INVALID_DIMENSIONS,
                "invalid browser dimensions",
            )
            val browser = NativeBrowser.open(
                engine = engine,
                component = surface.component,
                profilePath = profile,
                initialUrl = origin.firstUrl,
                width = 800,
                height = 600,
            ) { event ->
                browserEvents += event
                when {
                    event.type == NativeBrowserEventType.TITLE_CHANGED && event.text == FIRST_TITLE -> {
                        firstTitle.countDown()
                    }
                    event.type == NativeBrowserEventType.TITLE_CHANGED && event.text == SECOND_TITLE -> {
                        secondTitle.countDown()
                    }
                    event.type == NativeBrowserEventType.LOAD_ENDED && event.text == origin.secondUrl -> {
                        secondLoad.countDown()
                    }
                    event.type == NativeBrowserEventType.RESIZED && event.width == 960 && event.height == 640 -> {
                        resized.countDown()
                    }
                }
            }
            require(firstTitle.await(30, TimeUnit.SECONDS)) {
                "The first real Chromium page did not publish its Unicode title: $browserEvents"
            }
            require(NativeBrowser.liveNativeBrowserCount() == 1L)
            val rejectedEngineClose = try {
                NativeEngine.onAwtEventDispatchThread { engine.close() }
                null
            } catch (error: KWebNativeException) {
                error
            }
            require(rejectedEngineClose?.code == "native.abi.engine-has-live-browsers") {
                "Engine close with a live browser returned $rejectedEngineClose."
            }
            requireEquals(KWebLifecycleState.OPEN, engine.lifecycle.value, "engine remains open after rejected close")
            requireEquals(handle, engine.requireLiveHandle("browser-test"), "engine handle remains owned")

            browser.navigate(origin.secondUrl)
            require(secondTitle.await(30, TimeUnit.SECONDS)) {
                "The second real Chromium page did not publish its Unicode title: $browserEvents"
            }
            require(secondLoad.await(30, TimeUnit.SECONDS)) {
                "The second real Chromium navigation did not complete: $browserEvents"
            }
            browser.resize(960, 640)
            require(resized.await(30, TimeUnit.SECONDS)) {
                "The native Chromium child did not confirm the requested size: $browserEvents"
            }

            browser.close()
            val staleBrowserHandle = browserEvents.first().browser
            val browserCallbackCountAfterClose = browserEvents.size
            Thread.sleep(100)
            browser.close()
            require(browserEvents.size == browserCallbackCountAfterClose)
            require(browser.lifecycle.value == KWebLifecycleState.CLOSED)
            require(NativeBrowser.liveNativeBrowserCount() == 0L)
            requireStatus(
                NativeEngine.onAwtEventDispatchThread { NativeBindings.browserClose(staleBrowserHandle) },
                NativeStatus.INVALID_HANDLE,
                "stale browser close",
            )
            require(browserEvents.first().type == NativeBrowserEventType.CREATED)
            require(browserEvents.last().type == NativeBrowserEventType.CLOSED)
            require(browserEvents.map { it.sequence } == (1L..browserEvents.size.toLong()).toList())
            require(browserEvents.any { it.type == NativeBrowserEventType.NAVIGATION_STARTED && it.text == origin.secondUrl })
            require(browserEvents.any { it.type == NativeBrowserEventType.ADDRESS_CHANGED && it.text == origin.secondUrl })
            require(browserEvents.none { it.type == NativeBrowserEventType.FATAL_ERROR })
        }
        finally {
            NativeEngine.onAwtEventDispatchThread(surface::close)
        }
    }

    val duplicateCreate = NativeEngine.onAwtEventDispatchThread {
        rawCreate(configuration, NativeEngineEventSink { _, _, _ -> })
    }
    requireCreateFailure(duplicateCreate, NativeStatus.ENGINE_ALREADY_EXISTS)

    val wrongThreadStatus = NativeBindings.engineClose(handle)
    requireStatus(wrongThreadStatus, NativeStatus.WRONG_THREAD, "wrong-thread close")
    require(NativeEngine.liveNativeEngineCount() == 1L)

    Thread.sleep(2_000)
    reportStage("before_close")
    NativeEngine.onAwtEventDispatchThread(engine::close)
    reportStage("closed")
    val callbackCountAfterClose = events.size
    Thread.sleep(100)
    engine.close()

    require(events.map { it.type } == listOf(NativeEngineEventType.OPENED, NativeEngineEventType.CLOSED))
    require(events.map { it.sequence } == listOf(1L, 2L))
    require(events.size == callbackCountAfterClose)
    require(callbackThreads.toSet().size == 1)
    require(callbackThreads.toSet().single().startsWith("KWebShell-engine-callback-"))
    require(engine.lifecycle.value == KWebLifecycleState.CLOSED)
    require(NativeEngine.liveNativeEngineCount() == 0L)
    requireProfileDiskState(profile)

    val staleClose = NativeEngine.onAwtEventDispatchThread { NativeBindings.engineClose(handle) }
    requireStatus(staleClose, NativeStatus.INVALID_HANDLE, "stale engine close")
    val restart = NativeEngine.onAwtEventDispatchThread {
        rawCreate(configuration, NativeEngineEventSink { _, _, _ -> })
    }
    requireCreateFailure(restart, NativeStatus.ENGINE_RESTART_FORBIDDEN)
    reportStage("main_complete")
    println("KWebShell engine success lifecycle passed.")
}

private class BrowserOrigin : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val port: Int

    val firstUrl: String
    val secondUrl: String

    init {
        server.createContext("/", ::serve)
        server.executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "KWebShell-browser-origin").also { it.isDaemon = true }
        }
        server.start()
        port = server.address.port
        firstUrl = "http://127.0.0.1:$port/first"
        secondUrl = URI("http", null, "127.0.0.1", port, "/路径", "q=🙂", null).toASCIIString()
    }

    private fun serve(exchange: HttpExchange) {
        val second = exchange.requestURI.rawPath != "/first"
        val title = if (second) SECOND_TITLE else FIRST_TITLE
        val script = if (second) {
            "document.title = ${javascriptString(title)};"
        } else {
            "localStorage.setItem('kwebshell-integration', 'persisted');" +
                "document.cookie = 'kwebshell-session=persisted; path=/';" +
                "document.title = ${javascriptString(title)};"
        }
        val body = "<!doctype html><meta charset=\"utf-8\"><script>$script</script>".toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() {
        server.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

private fun javascriptString(value: String): String = buildString {
    append('\'')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            else -> append(character)
        }
    }
    append('\'')
}

private fun requireProfileDiskState(profile: Path) {
    val cookieStore = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        profile.resolve("Network/Cookies")
    } else {
        profile.resolve("Cookies")
    }
    listOf(
        profile.resolve("Preferences"),
        cookieStore,
        profile.resolve("Local Storage/leveldb/CURRENT"),
    ).forEach { path ->
        require(Files.isRegularFile(path) && Files.size(path) > 0L) {
            "Chromium did not persist required Profile state at '$path'."
        }
    }
}

private fun runCallbackFailureLifecycle() {
    val configuration = NativeEngine.prepareNativeRuntime(runtimeConfiguration())
    val callbackObserved = CountDownLatch(1)
    val closedObserved = CountDownLatch(1)
    val result = NativeEngine.onAwtEventDispatchThread {
        rawCreate(
            configuration,
            NativeEngineEventSink { _, _, type ->
                if (type == NativeEngineEventType.CLOSED.value) {
                    closedObserved.countDown()
                } else {
                    callbackObserved.countDown()
                    error("deliberate JNI callback contract failure")
                }
            },
        )
    }
    require(result > 0L)
    require(callbackObserved.await(30, TimeUnit.SECONDS))
    val closeStatus = NativeEngine.onAwtEventDispatchThread { NativeBindings.engineClose(result) }
    requireStatus(closeStatus, NativeStatus.CALLBACK_FAILED, "JNI callback failure")
    require(closedObserved.await(30, TimeUnit.SECONDS))
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine JNI callback failure contract passed.")
}

private fun runListenerFailureLifecycle() {
    val error = try {
        NativeEngine.open(runtimeConfiguration()) { event ->
            if (event.type == NativeEngineEventType.OPENED) {
                throw IllegalStateException("deliberate listener failure")
            }
        }
        null
    } catch (failure: KWebNativeException) {
        failure
    }
    require(error?.code == "native.engine.listener-failed") {
        "Expected a typed listener failure, got $error"
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine listener failure contract passed.")
}

private fun runHolderLifecycle() {
    val engine = NativeEngine.open(runtimeConfiguration())
    println(HOLDER_OPENED_MARKER)
    System.out.flush()
    System.`in`.bufferedReader().readLine()
    engine.close()
    require(NativeEngine.liveNativeEngineCount() == 0L)
}

private fun runInitializationFailureLifecycle() {
    val failure = try {
        NativeEngine.open(runtimeConfiguration())
        null
    } catch (error: KWebNativeException) {
        error
    }
    require(failure?.code == "native.abi.cef-initialize-failed") {
        "Expected the real CEF process-singleton initialization failure, got $failure"
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine initialization failure contract passed.")
}

private fun rawCreate(configuration: NativeEngineConfiguration, sink: NativeEngineEventSink): Long =
    NativeBindings.engineCreate(
        sink,
        configuration.cefRuntime.toString(),
        configuration.browserSubprocess.toString(),
        configuration.resources.toString(),
        configuration.locales.toString(),
        configuration.rootCache.toString(),
        configuration.log.toString(),
    )

private fun rawBrowserCreate(
    engine: NativeEngine,
    component: java.awt.Component,
    profile: Path,
    initialUrl: String,
    width: Int,
    height: Int,
): Long = NativeBindings.browserCreate(
    engine.requireLiveHandle("browser-create-test"),
    NativeBrowserEventSink { _, _, _, _, _, _, _, _, _ -> },
    component,
    profile.toString(),
    initialUrl,
    0,
    0,
    width,
    height,
)

private fun runtimeConfiguration(): NativeEngineConfiguration {
    val root = requiredPathProperty(INTEGRATION_ROOT_PROPERTY)
    Files.createDirectories(root)
    return NativeEngineConfiguration(
        cefRuntime = requiredPathProperty(CEF_RUNTIME_PROPERTY),
        browserSubprocess = requiredPathProperty(SUBPROCESS_PROPERTY),
        resources = requiredPathProperty(RESOURCES_PROPERTY),
        locales = requiredPathProperty(LOCALES_PROPERTY),
        rootCache = root,
        log = root.resolve("cef.log"),
    )
}

private fun requiredPathProperty(name: String): Path {
    val value = System.getProperty(name) ?: error("Missing required system property '$name'.")
    return Path.of(value)
}

private fun requireStatus(actual: Int, expected: NativeStatus, operation: String) {
    require(actual == expected.value) {
        "$operation returned $actual instead of ${expected.value} (${expected.id})."
    }
}

private fun requireEquals(expected: Any, actual: Any, operation: String) {
    require(expected == actual) { "$operation was $actual instead of $expected." }
}

private fun requireCreateFailure(result: Long, expected: NativeStatus) {
    require(result == -expected.value.toLong()) {
        "Native create returned $result instead of -${expected.value} (${expected.id})."
    }
}

private fun reportStage(stage: String) {
    println("$STAGE_PREFIX$stage")
    System.out.flush()
}

private data class ChildProcess(
    val process: Process,
    val input: BufferedWriter,
    val lines: CopyOnWriteArrayList<String>,
    val opened: CountDownLatch,
    val reader: Thread,
) {
    fun output(): String = lines.joinToString(System.lineSeparator())
}

private fun runChildAndRequireSuccess(mode: IntegrationMode, root: Path) {
    val child = startChild(mode, root)
    require(child.process.waitFor(120, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(child.process)
        child.process.destroyForcibly()
        "Native engine child '${mode.argument}' timed out.\n${child.output()}\n$diagnostics"
    }
    child.reader.join(5000)
    require(child.process.exitValue() == 0) {
        "Native engine child '${mode.argument}' exited with ${child.process.exitValue()}.\n${child.output()}"
    }
    if (mode == IntegrationMode.SUCCESS && isMacOs()) {
        val policyIndexes = child.lines.mapIndexedNotNull { index, line ->
            index.takeIf { line == MACOS_BROWSER_POLICY_MARKER }
        }
        val openedIndex = child.lines.indexOf("${STAGE_PREFIX}opened")
        require(policyIndexes.size == 1 && openedIndex > policyIndexes.single()) {
            "The macOS browser policy was not applied exactly once before OnContextInitialized.\n${child.output()}"
        }
    }
}

private fun isMacOs(): Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

private fun collectTimeoutDiagnostics(process: Process): String = buildString {
    appendLine("JVM thread dump:")
    appendLine(
        runDiagnostic(
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "jcmd.exe" else "jcmd",
            ).toString(),
            process.pid().toString(),
            "Thread.print",
            "-l",
        ),
    )
    if (isMacOs()) {
        appendLine("macOS native sample:")
        appendLine(runDiagnostic("/usr/bin/sample", process.pid().toString(), "5"))
    }
}

private fun runDiagnostic(vararg command: String): String {
    val diagnostic = try {
        ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (error: Exception) {
        return "Could not start '${command.first()}': $error"
    }
    val output = StringBuilder()
    val reader = thread(name = "KWebShell-engine-timeout-diagnostic", isDaemon = true) {
        diagnostic.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
    }
    if (!diagnostic.waitFor(15, TimeUnit.SECONDS)) {
        diagnostic.destroyForcibly()
        reader.join(1_000)
        return "Diagnostic '${command.first()}' timed out.\n$output"
    }
    reader.join(1_000)
    return output.toString()
}

private fun startChild(mode: IntegrationMode, root: Path): ChildProcess {
    Files.createDirectories(root)
    val javaExecutable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe" else "java",
    )
    val inheritedProperties = listOf(
        NATIVE_LIBRARY_PATH_PROPERTY,
        CEF_RUNTIME_PROPERTY,
        SUBPROCESS_PROPERTY,
        RESOURCES_PROPERTY,
        LOCALES_PROPERTY,
    )
    val command = buildList {
        add(javaExecutable.toString())
        add("-Djava.awt.headless=false")
        inheritedProperties.forEach { name ->
            add("-D$name=${System.getProperty(name) ?: error("Missing '$name'.")}")
        }
        add("-D$INTEGRATION_ROOT_PROPERTY=$root")
        add("-cp")
        add(System.getProperty("java.class.path"))
        add(MAIN_CLASS)
        add(mode.argument)
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lines = CopyOnWriteArrayList<String>()
    val opened = CountDownLatch(1)
    val reader = thread(name = "KWebShell-engine-child-${mode.argument}", isDaemon = true) {
        process.inputStream.bufferedReader().useLines { outputLines ->
            outputLines.forEach { line ->
                lines += line
                if (line == HOLDER_OPENED_MARKER) {
                    opened.countDown()
                }
            }
        }
    }
    return ChildProcess(process, process.outputStream.bufferedWriter(), lines, opened, reader)
}
