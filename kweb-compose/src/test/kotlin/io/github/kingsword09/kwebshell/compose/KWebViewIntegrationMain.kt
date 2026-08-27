package io.github.kingsword09.kwebshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.window.WindowScope
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebComposeWindowHost
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.awt.Window
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities
import kotlin.math.max

private const val ROOT_PROPERTY = "kweb.compose.integration.root"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val TIMEOUT_MS = 30_000L
private const val WINDOW_WIDTH = 1_000
private const val WINDOW_HEIGHT = 700
private const val PADDING = 20
private const val GAP = 20

/**
 * Runs a real ComposeWindow/CEF/native-child lifecycle without replacing the
 * renderer with an off-screen or system-WebView implementation.
 */
public fun main(): Unit {
    runBlocking {
        runComposeIntegration()
    }
}

private suspend fun runComposeIntegration() {
    val configuration = runtimeConfiguration()
    val root = configuration.root
    Files.createDirectories(root)
    val server = IntegrationServer.start()
    var engine: io.github.kingsword09.kwebshell.core.KWebEngine? = null
    var profile: io.github.kingsword09.kwebshell.core.KWebProfile? = null
    var externalPage: KWebPage? = null
    var componentPage: KWebPage? = null
    var window: ComposeWindow? = null
    val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val cleanupFailures = mutableListOf<Throwable>()
    var failure: Throwable? = null

    try {
        window = onEventDispatchThread {
            ComposeWindow().apply {
                isUndecorated = true
                title = "KWebShell Compose native-child integration"
                setSize(WINDOW_WIDTH, WINDOW_HEIGHT)
                setLocation(120, 120)
                isVisible = true
            }
        }
        check(requireNotNull(window).isShowing) { "ComposeWindow did not become visible." }
        check(requireNotNull(window).windowHandle != 0L) { "ComposeWindow.windowHandle is zero." }
        val visibleAwtWindowsBefore = visibleAwtWindows()

        val liveEngine = KWebDesktop.openEngine(
            KWebDesktopEngineConfiguration(
                cefRuntime = configuration.cefRuntime,
                browserSubprocess = configuration.browserSubprocess,
                resources = configuration.resources,
                locales = configuration.locales,
                rootCache = root,
                log = root.resolve("cef-compose-integration.log"),
            ),
        )
        engine = liveEngine
        check(KWebCapability.NATIVE_CHILD in liveEngine.capabilities)
        check(KWebCapability.RESIZE in liveEngine.capabilities)

        val liveProfile = liveEngine.openProfile("compose-integration")
        profile = liveProfile
        val host: KWebComposeWindowHost = KWebDesktop.composeWindowHost(requireNotNull(window))
        val firstPage = liveProfile.openPage(
            host = host,
            initialUrl = server.url("/one"),
            bounds = KWebRect(0, 0, 100, 100),
        )
        externalPage = firstPage
        val secondPage = liveProfile.openPage(
            host = host,
            initialUrl = server.url("/two"),
            bounds = KWebRect(0, 0, 100, 100),
        )
        componentPage = secondPage

        val externalController = KWebViewController(firstPage, KWebViewPageOwnership.EXTERNAL)
        val componentController = KWebViewController(secondPage, KWebViewPageOwnership.COMPONENT)
        val state = IntegrationUiState()
        val eventLog = mapOf(
            firstPage to collectEvents(eventScope, firstPage),
            secondPage to collectEvents(eventScope, secondPage),
        )
        onEventDispatchThread {
            requireNotNull(window).setContent {
                IntegrationContent(
                    externalController = externalController,
                    componentController = componentController,
                    state = state,
                )
            }
        }

        awaitEvent(firstPage) { it.type == KWebPageEventType.CREATED }
        awaitEvent(secondPage) { it.type == KWebPageEventType.CREATED }
        val initialLayout = awaitLayout(state)
        check(initialLayout.childCount == 2) { "Compose did not lay out both KWebView children." }
        awaitResizeFor(firstPage, initialLayout)
        awaitResizeFor(secondPage, initialLayout)
        check(externalController.placementError.value == null) { externalController.placementError.value.toString() }
        check(componentController.placementError.value == null) { componentController.placementError.value.toString() }

        val firstCreated = awaitEventCount(eventLog.getValue(firstPage)) { it.type == KWebPageEventType.CREATED }
        val secondCreated = awaitEventCount(eventLog.getValue(secondPage)) { it.type == KWebPageEventType.CREATED }
        check(firstCreated == 1) { "Recomposition test started with $firstCreated external CREATED events." }
        check(secondCreated == 1) { "Recomposition test started with $secondCreated component CREATED events." }

        forceRecomposition(state)
        forceRecomposition(state)
        awaitEvent(firstPage) { it.type == KWebPageEventType.RESIZED && it.bounds == initialLayout.toBounds() }
        awaitEvent(secondPage) { it.type == KWebPageEventType.RESIZED && it.bounds == initialLayout.toBounds() }
        kotlinx.coroutines.delay(200)
        check(eventLog.getValue(firstPage).count { it.type == KWebPageEventType.CREATED } == 1) {
            "External page was recreated during ordinary recomposition."
        }
        check(eventLog.getValue(secondPage).count { it.type == KWebPageEventType.CREATED } == 1) {
            "Component page was recreated during ordinary recomposition."
        }

        val beforeResize = state.layout.get() ?: error("Compose layout disappeared before resize.")
        onEventDispatchThread {
            requireNotNull(window).setSize(1_200, 800)
            requireNotNull(window).validate()
        }
        val afterResize = awaitLayout(state) { it != beforeResize }
        awaitResizeFor(firstPage, afterResize)
        awaitResizeFor(secondPage, afterResize)

        onEventDispatchThread {
            val current = requireNotNull(window)
            current.setLocation(current.x + 24, current.y + 18)
            current.toFront()
            current.requestFocus()
        }
        externalController.requestFocus()
        componentController.requestFocus()
        kotlinx.coroutines.delay(150)
        externalController.clearFocus()
        componentController.clearFocus()

        onEventDispatchThread { requireNotNull(window).isMinimized = true }
        kotlinx.coroutines.delay(150)
        check(firstPage.lifecycle.value == KWebLifecycleState.OPEN)
        check(secondPage.lifecycle.value == KWebLifecycleState.OPEN)
        onEventDispatchThread {
            requireNotNull(window).isMinimized = false
            requireNotNull(window).toFront()
        }
        kotlinx.coroutines.delay(250)
        check(firstPage.lifecycle.value == KWebLifecycleState.OPEN)
        check(secondPage.lifecycle.value == KWebLifecycleState.OPEN)
        check(externalController.placementError.value == null) { externalController.placementError.value.toString() }
        check(componentController.placementError.value == null) { componentController.placementError.value.toString() }
        val unexpectedAwtWindows = visibleAwtWindows() - visibleAwtWindowsBefore
        check(unexpectedAwtWindows.isEmpty()) {
            "CEF created unexpected visible AWT top-level windows: $unexpectedAwtWindows"
        }

        val beforeComponentRemoval = state.layout.get() ?: error("Compose layout disappeared before component removal.")
        onEventDispatchThread {
            state.showComponent.value = false
        }
        val afterComponentRemoval = awaitLayout(state) { it != beforeComponentRemoval && it.childCount == 1 }
        awaitState(secondPage, KWebLifecycleState.CLOSED)
        check(afterComponentRemoval.childCount == 1)
        check(firstPage.lifecycle.value == KWebLifecycleState.OPEN) {
            "Removing a COMPONENT page changed the EXTERNAL page lifecycle."
        }

        val beforeExternalRemoval = state.layout.get() ?: error("Compose layout disappeared before external removal.")
        onEventDispatchThread {
            state.showExternal.value = false
        }
        val afterExternalRemoval = awaitLayout(state) { it != beforeExternalRemoval && it.childCount == 0 }
        kotlinx.coroutines.delay(200)
        check(afterExternalRemoval.childCount == 0)
        check(firstPage.lifecycle.value == KWebLifecycleState.OPEN) {
            "Removing an EXTERNAL page closed its page unexpectedly."
        }
        check(externalController.placementError.value == null) { externalController.placementError.value.toString() }

        firstPage.close()
        awaitState(firstPage, KWebLifecycleState.CLOSED)
        println(
            "KWebShell Compose native-child integration passed: " +
                "two pages, recomposition, resize, move, focus, minimize, and ownership teardown.",
        )
    } catch (error: Throwable) {
        failure = error
    } finally {
        eventScope.cancel()
        componentPage?.let { page ->
            if (page.lifecycle.value != KWebLifecycleState.CLOSED) {
                runCatching { page.close() }.onFailure { cleanupFailures += it }
            }
        }
        externalPage?.let { page ->
            if (page.lifecycle.value != KWebLifecycleState.CLOSED) {
                runCatching { page.close() }.onFailure { cleanupFailures += it }
            }
        }
        profile?.let { resource ->
            if (resource.lifecycle.value != KWebLifecycleState.CLOSED) {
                runCatching { resource.close() }.onFailure { cleanupFailures += it }
            }
        }
        engine?.let { resource ->
            if (resource.lifecycle.value != KWebLifecycleState.CLOSED) {
                runCatching { resource.close() }.onFailure { cleanupFailures += it }
            }
        }
        window?.let { resource ->
            runCatching {
                onEventDispatchThread {
                    resource.dispose()
                    check(!resource.isDisplayable) { "ComposeWindow remained displayable after disposal." }
                }
            }.onFailure { cleanupFailures += it }
        }
        runCatching { server.close() }.onFailure { cleanupFailures += it }
    }

    if (failure != null) {
        val primary = failure
        cleanupFailures.forEach(primary::addSuppressed)
        throw primary
    }
    if (cleanupFailures.isNotEmpty()) {
        val first = cleanupFailures.first()
        cleanupFailures.drop(1).forEach(first::addSuppressed)
        throw first
    }
}

private data class IntegrationConfiguration(
    val root: Path,
    val cefRuntime: Path,
    val browserSubprocess: Path,
    val resources: Path,
    val locales: Path,
)

private fun runtimeConfiguration(): IntegrationConfiguration {
    fun required(name: String): Path = Path.of(System.getProperty(name) ?: error("Missing system property $name"))
    val root = required(ROOT_PROPERTY).toAbsolutePath().normalize()
    return IntegrationConfiguration(
        root = root,
        cefRuntime = required(CEF_RUNTIME_PROPERTY),
        browserSubprocess = required(SUBPROCESS_PROPERTY),
        resources = required(RESOURCES_PROPERTY),
        locales = required(LOCALES_PROPERTY),
    )
}

private class IntegrationUiState {
    val showExternal: MutableState<Boolean> = mutableStateOf(true)
    val showComponent: MutableState<Boolean> = mutableStateOf(true)
    val recompositionTick: MutableState<Int> = mutableStateOf(0)
    val layout = AtomicReference<LayoutMetrics?>(null)
}

private data class LayoutMetrics(
    val width: Int,
    val height: Int,
    val childWidth: Int,
    val childHeight: Int,
    val childCount: Int,
) {
    fun toBounds(): KWebBounds = KWebBounds(childWidth, childHeight)
}

@Composable
private fun WindowScope.IntegrationContent(
    externalController: KWebViewController,
    componentController: KWebViewController,
    state: IntegrationUiState,
) {
    val showExternal = state.showExternal.value
    val showComponent = state.showComponent.value
    state.recompositionTick.value
    Layout(
        content = {
            if (showExternal) KWebView(externalController, Modifier)
            if (showComponent) KWebView(componentController, Modifier)
        },
        measurePolicy = { measurables, constraints ->
            val width = constraints.maxWidth.takeUnless { it == Constraints.Infinity } ?: WINDOW_WIDTH
            val height = constraints.maxHeight.takeUnless { it == Constraints.Infinity } ?: WINDOW_HEIGHT
            val childCount = measurables.size
            val usableWidth = max(1, width - (PADDING * 2) - (GAP * max(0, childCount - 1)))
            val childWidth = max(1, usableWidth / max(1, childCount))
            val childHeight = max(1, height - (PADDING * 2))
            val placeables = measurables.map {
                it.measure(Constraints.fixed(childWidth, childHeight))
            }
            state.layout.set(LayoutMetrics(width, height, childWidth, childHeight, childCount))
            layout(width, height) {
                placeables.forEachIndexed { index, placeable ->
                    placeable.place(PADDING + index * (childWidth + GAP), PADDING)
                }
            }
        },
    )
}

private fun forceRecomposition(state: IntegrationUiState) {
    onEventDispatchThread { state.recompositionTick.value += 1 }
}

private suspend fun awaitLayout(
    state: IntegrationUiState,
    predicate: (LayoutMetrics) -> Boolean = { true },
): LayoutMetrics = withTimeout(TIMEOUT_MS) {
    var result: LayoutMetrics? = null
    while (result == null) {
        result = state.layout.get()?.takeIf(predicate)
        if (result == null) kotlinx.coroutines.delay(10)
    }
    result
}

private suspend fun awaitResizeFor(page: KWebPage, layout: LayoutMetrics) {
    awaitEvent(page) { event ->
        event.type == KWebPageEventType.RESIZED &&
            event.bounds?.let { bounds ->
                bounds.width == layout.childWidth && bounds.height == layout.childHeight
            } == true
    }
}

private suspend fun awaitEvent(page: KWebPage, predicate: (KWebPageEvent) -> Boolean): KWebPageEvent =
    withTimeout(TIMEOUT_MS) { page.events.first(predicate) }

private suspend fun awaitEventCount(
    events: List<KWebPageEvent>,
    predicate: (KWebPageEvent) -> Boolean,
): Int = withTimeout(TIMEOUT_MS) {
    var count = 0
    while (count == 0) {
        count = events.count(predicate)
        if (count == 0) kotlinx.coroutines.delay(10)
    }
    count
}

private suspend fun awaitState(page: KWebPage, state: KWebLifecycleState) {
    withTimeout(TIMEOUT_MS) { page.lifecycle.first { it == state } }
}

private fun collectEvents(scope: CoroutineScope, page: KWebPage): MutableList<KWebPageEvent> {
    val events = java.util.concurrent.CopyOnWriteArrayList<KWebPageEvent>()
    scope.launch { page.events.collect { events += it } }
    return events
}

private fun visibleAwtWindows(): Set<Window> = onEventDispatchThread {
    Window.getWindows().filterTo(linkedSetOf()) { it.isShowing }
}

private fun <T> onEventDispatchThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val result = AtomicReference<T>()
    val failure = AtomicReference<Throwable>()
    SwingUtilities.invokeAndWait {
        try {
            result.set(block())
        } catch (error: Throwable) {
            failure.set(error)
        }
    }
    failure.get()?.let { throw it }
    return result.get()
}

private class IntegrationServer private constructor(
    private val server: HttpServer,
    private val executor: ExecutorService,
) : AutoCloseable {
    fun url(path: String): String = "http://127.0.0.1:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        fun start(): IntegrationServer {
            val executor = Executors.newCachedThreadPool { runnable ->
                Thread(runnable, "kweb-compose-integration-http").apply { isDaemon = true }
            }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.executor = executor
            server.createContext("/one") { exchange -> respond(exchange, "one") }
            server.createContext("/two") { exchange -> respond(exchange, "two") }
            server.start()
            return IntegrationServer(server, executor)
        }

        private fun respond(exchange: HttpExchange, marker: String) {
            val body = """
                <!doctype html><html><head><meta charset="utf-8"><title>KWebShell $marker</title></head>
                <body><main id="marker">KWebShell Compose native child $marker</main></body></html>
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { output -> output.write(body) }
        }
    }
}
