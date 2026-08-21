package io.github.kingsword09.kwebshell.example.benchmark

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Robot
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val PRESENTATION_PREFLIGHT_MS: Long = 750L
private const val PRESENTATION_MINIMUM_FRAMES: Double = 3.0

internal class BenchmarkPhaseRunner(
    private val configuration: BenchmarkConfiguration,
) {
    fun runFromSystemProperties() {
        val phase = requiredValue("kweb.benchmark.phase").takeIf { it == "cold" || it == "warm" }
            ?: throw BenchmarkException("phase.invalid", "Benchmark phase must be cold or warm.")
        val sampleId = requiredValue("kweb.benchmark.sample.id")
        val profileName = requiredValue("kweb.benchmark.profile")
        val measured = requiredValue("kweb.benchmark.measured").toBooleanStrictOrNull()
            ?: throw BenchmarkException("phase.measured-invalid", "Benchmark measured flag is invalid.")
        runPhase(
            pageUrl = requiredValue("kweb.benchmark.page.url"),
            phase = phase,
            sampleId = sampleId,
            profileName = profileName,
            measured = measured,
            screenshotPath = requiredPath("kweb.benchmark.screenshot"),
            hostPath = requiredPath("kweb.benchmark.host"),
            cdpPort = requiredValue("kweb.benchmark.cdp.port").toIntOrNull()?.takeIf { it in 1024..65535 }
                ?: throw BenchmarkException("phase.cdp-port-invalid", "Benchmark CDP port is invalid."),
        )
    }

    private fun runPhase(
        pageUrl: String,
        phase: String,
        sampleId: String,
        profileName: String,
        measured: Boolean,
        screenshotPath: Path,
        hostPath: Path,
        cdpPort: Int,
    ) {
        var window: ComposeWindow? = null
        var engine: KWebEngine? = null
        var profile: KWebProfile? = null
        var page: KWebPage? = null
        var devToolsOpen = false
        var pageSession: KWebExampleCdpSession? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        val cdp = KWebExampleCdpClient(cdpPort, configuration.timeoutMs)
        val phaseStartedEpochMs = System.currentTimeMillis()
        var engineStartupMs: Double? = null
        var pageUsableMs: Double? = null
        var addressEventMs: Double? = null
        var devToolsOpenMs: Double? = null
        var devToolsCloseMs: Double? = null
        var preProfileBytes = 0.0
        var browserVersion: io.github.kingsword09.kwebshell.example.support.KWebExampleCdpBrowserVersion? = null
        var liveHostMetrics: Map<String, Double>? = null
        var displayScale: Double? = null
        var shutdownStartedNs = 0L
        var eventDeferred: kotlinx.coroutines.Deferred<List<KWebPageEvent>>? = null
        val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            window = createWindow(phase, sampleId)
            val profilePath = configuration.rootCache.resolve(profileName)
            preProfileBytes = directoryBytes(profilePath)
            val engineStartedNs = System.nanoTime()
            engine = KWebDesktop.openEngine(
                KWebDesktopEngineConfiguration(
                    cefRuntime = configuration.cefRuntime,
                    browserSubprocess = configuration.browserSubprocess,
                    resources = configuration.resources,
                    locales = configuration.locales,
                    rootCache = configuration.rootCache,
                    log = configuration.rootCache.resolve("cef-$sampleId.log"),
                    remoteDebuggingPort = cdpPort,
                ),
            )
            engineStartupMs = elapsedMs(engineStartedNs)
            if (KWebCapability.CDP !in engine.capabilities) {
                throw BenchmarkException("phase.cdp-capability-missing", "The benchmark requires the explicit CDP capability.")
            }
            profile = runBlocking { engine.openProfile(profileName) }
            val pageOpenedNs = System.nanoTime()
            page = runBlocking {
                profile.openPage(
                    KWebDesktop.composeWindowHost(window),
                    pageUrl,
                    KWebBounds(configuration.width, configuration.height),
                )
            }
            val observedPage = page
            eventDeferred = eventScope.async {
                observedPage.events.transformWhile { event ->
                    emit(event)
                    event.type != KWebPageEventType.CLOSED
                }.toList()
            }
            val addressStartedNs = System.nanoTime()
            runBlocking {
                withTimeout(configuration.timeoutMs) {
                    page.events.first { it.type == KWebPageEventType.ADDRESS_CHANGED }
                }
            }
            addressEventMs = elapsedMs(addressStartedNs)
            val readyEvent = runBlocking {
                withTimeout(configuration.timeoutMs) {
                    page.events.first { event ->
                        event.type == KWebPageEventType.TITLE_CHANGED &&
                            (event.text.endsWith("_READY") || event.text.endsWith("_FAIL"))
                    }
                }
            }
            if (readyEvent.text.endsWith("_FAIL")) {
                val failureSession = cdp.openPageSession(pageUrl)
                try {
                    throwPageFailure(failureSession, "startup")
                } finally {
                    failureSession.close()
                }
            }
            pageUsableMs = elapsedMs(pageOpenedNs)
            browserVersion = cdp.awaitBrowserVersion()

            val livePageSession = cdp.openPageSession(pageUrl)
            pageSession = livePageSession
            livePageSession.command("Runtime.enable")
            livePageSession.command("Performance.enable")
            livePageSession.command("Network.enable")
            livePageSession.command("Page.enable")
            prepareScenarioPresentation(requireWindow(), livePageSession)
            livePageSession.command(
                "Runtime.evaluate",
                buildJsonObject {
                    put("expression", "window.kwebBenchmark.ready.then(() => window.kwebBenchmark.run())")
                    put("awaitPromise", true)
                    put("returnByValue", true)
                },
            )
            val scenarioEvent = runBlocking {
                withTimeout(configuration.timeoutMs) {
                    page.events.first { event ->
                        event.type == KWebPageEventType.TITLE_CHANGED &&
                            (event.text.endsWith("_PASS") || event.text.endsWith("_FAIL"))
                    }
                }
            }
            if (scenarioEvent.text.endsWith("_FAIL")) {
                val failureSession = cdp.openPageSession(pageUrl)
                try {
                    throwPageFailure(failureSession, "scenario")
                } finally {
                    failureSession.close()
                }
            }

            val openStartedNs = System.nanoTime()
            runBlocking { page.openDevTools() }
            devToolsOpenMs = elapsedMs(openStartedNs)
            devToolsOpen = true
            val evidence = livePageSession.evaluate(
                "document.querySelector('[role=region]')?.getAttribute('aria-label') || ''",
            )
            if (evidence.value?.jsonPrimitive?.contentOrNull != "KWebShell benchmark evidence") {
                throw BenchmarkException("phase.accessibility-evidence-missing", "The benchmark accessibility evidence marker is missing.")
            }
            liveHostMetrics = collectLiveHostMetrics(
                window = requireWindow(),
                session = livePageSession,
                engineStartupMs = requireMetric(engineStartupMs, "phase.engine-startup-missing", "Engine startup timing is missing."),
                pageUsableMs = requireMetric(pageUsableMs, "phase.page-usable-missing", "Page usable timing is missing."),
                addressEventMs = requireMetric(addressEventMs, "phase.address-event-missing", "Address event timing is missing."),
                devToolsOpenMs = requireMetric(devToolsOpenMs, "phase.devtools-open-missing", "DevTools open timing is missing."),
            )
            val closeStartedNs = System.nanoTime()
            runBlocking { page.closeDevTools() }
            devToolsCloseMs = elapsedMs(closeStartedNs)
            devToolsOpen = false
            livePageSession.close()
            pageSession = null
            displayScale = captureWindow(requireWindow(), screenshotPath).scale
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (devToolsOpen && page != null) {
                runCleanup(cleanupFailures) { runBlocking { page.closeDevTools() } }
            }
            pageSession?.let { session -> runCleanup(cleanupFailures) { session.close() } }
            shutdownStartedNs = System.nanoTime()
            if (page != null) runCleanup(cleanupFailures) { if (page.lifecycle.value != KWebLifecycleState.CLOSED) page.close() }
            if (profile != null) runCleanup(cleanupFailures) { if (profile.lifecycle.value != KWebLifecycleState.CLOSED) profile.close() }
            if (engine != null) runCleanup(cleanupFailures) { if (engine.lifecycle.value != KWebLifecycleState.CLOSED) engine.close() }
            if (shutdownStartedNs <= 0L) cleanupFailures += BenchmarkException("phase.shutdown-timing-missing", "Shutdown timing did not start.")
            val shutdownMs = if (shutdownStartedNs > 0L) elapsedMs(shutdownStartedNs) else 0.0
            if (window != null) runCleanup(cleanupFailures) { disposeWindow(window) }
            runCleanup(cleanupFailures) { cdp.assertUnavailable() }

            val events = if (eventDeferred != null) {
                try {
                    runBlocking { withTimeout(configuration.timeoutMs) { eventDeferred.await() } }
                } catch (error: Throwable) {
                    cleanupFailures += BenchmarkException("phase.events-missing", "The page event stream did not reach CLOSED.", error)
                    emptyList()
                }
            } else {
                emptyList()
            }
            eventScope.cancel()

            if (failure == null && cleanupFailures.isEmpty()) {
                val host = liveHostMetrics
                val version = browserVersion
                val measuredDisplayScale = displayScale
                if (host == null || version == null || measuredDisplayScale == null) {
                    cleanupFailures += BenchmarkException("phase.host-evidence-missing", "Host measurements were not produced.")
                } else {
                    val finalProfileBytes = directoryBytes(configuration.rootCache.resolve(profileName))
                    val completeHost = host + mapOf(
                        "host.devtools-close.ms" to requireMetric(devToolsCloseMs, "phase.devtools-close-missing", "DevTools close timing is missing."),
                        "host.profile.before.bytes" to preProfileBytes,
                        "host.profile.after.bytes" to finalProfileBytes,
                        "host.profile.delta.bytes" to finalProfileBytes - preProfileBytes,
                        "host.shutdown.ms" to shutdownMs,
                        "host.display.scale" to measuredDisplayScale,
                    )
                    try {
                        writeHostEvidence(
                            hostPath,
                            HostEvidence(
                                schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION,
                                phase = phase,
                                sampleId = sampleId,
                                startedAtEpochMs = phaseStartedEpochMs,
                                endedAtEpochMs = System.currentTimeMillis(),
                                chromiumProduct = version.product,
                                protocolVersion = version.protocolVersion,
                                revision = version.revision,
                                javaScriptVersion = version.javaScriptVersion,
                                metrics = completeHost,
                                eventSequences = events.map(KWebPageEvent::sequence),
                                eventTypes = events.map { it.type.id },
                                evidence = mapOf(
                                    "lifecycle" to "created->closed",
                                    "devTools" to "opened->closed",
                                    "cdpEndpoint" to "loopback",
                                    "profilePath" to configuration.rootCache.resolve(profileName).toString(),
                                ),
                            ),
                        )
                    } catch (error: Throwable) {
                        cleanupFailures += error
                    }
                }
            }
        }
        if (failure != null) {
            cleanupFailures.forEach(failure::addSuppressed)
            throw failure
        }
        cleanupFailures.firstOrNull()?.let { throw it }
    }

    private fun collectLiveHostMetrics(
        window: ComposeWindow,
        session: KWebExampleCdpSession,
        engineStartupMs: Double,
        pageUsableMs: Double,
        addressEventMs: Double,
        devToolsOpenMs: Double,
    ): Map<String, Double> {
        val cdpDurations = mutableListOf<Double>()
        val pageValues = mutableMapOf<String, Double>()
        session.command("DOM.enable")
        listOf("Runtime.evaluate", "Performance.getMetrics", "DOM.getDocument").forEach { method ->
            val startedNs = System.nanoTime()
            val result = if (method == "Runtime.evaluate") {
                session.command(method, buildJsonObject { put("expression", "performance.now()") })
            } else {
                session.command(method)
            }
            cdpDurations += elapsedMs(startedNs)
            if (method == "Performance.getMetrics") {
                result["metrics"]?.jsonArray?.forEach { element ->
                    val item = element.jsonObject
                    val name = item["name"]?.jsonPrimitive?.contentOrNull
                    val value = item["value"]?.jsonPrimitive?.doubleOrNull
                    if (name != null && value != null) pageValues[name] = value
                }
            }
        }
        val process = ProcessMetricsSampler.sample()
        val nativeFrames = NativeFrameMetricsSampler.sample(window, session)
        val cdpMedian = percentile(cdpDurations, 0.5)
        val cdpP95 = percentile(cdpDurations, 0.95)
        val cdpWorst = cdpDurations.max()
        fun required(name: String): Double = pageValues[name]
            ?: throw BenchmarkException("phase.cdp-metric-missing", "CDP did not expose '$name'.")
        return linkedMapOf(
            "host.engine-startup.ms" to engineStartupMs,
            "host.page-usable.ms" to pageUsableMs,
            "host.cdp-command.median.ms" to cdpMedian,
            "host.cdp-command.p95.ms" to cdpP95,
            "host.cdp-command.worst.ms" to cdpWorst,
            "host.devtools-open.ms" to devToolsOpenMs,
            "host.public-event-address.ms" to addressEventMs,
            "host.process.resident.bytes" to process.residentBytes,
            "host.process.private.bytes" to process.privateBytes,
            "host.process.cpu.ms" to process.cpuMs,
            "host.process.thread.count" to process.threadCount,
            "host.native-frame.count" to nativeFrames.frameCount,
            "host.native-frame.interval.median.ms" to nativeFrames.medianIntervalMs,
            "host.native-frame.interval.p95.ms" to nativeFrames.p95IntervalMs,
            "host.native-frame.interval.worst.ms" to nativeFrames.worstIntervalMs,
            "cdp.js-heap-used.bytes" to required("JSHeapUsedSize"),
            "cdp.dom-node.count" to required("Nodes"),
            "cdp.document.count" to required("Documents"),
            "cdp.layout.count" to required("LayoutCount"),
            "cdp.recalc-style.count" to required("RecalcStyleCount"),
            "cdp.task-duration.ms" to required("TaskDuration") * 1000.0,
        )
    }

    private fun prepareScenarioPresentation(window: ComposeWindow, session: KWebExampleCdpSession) {
        try {
            SwingUtilities.invokeAndWait {
                if (!window.isShowing || !window.isDisplayable) {
                    throw BenchmarkException(
                        "phase.presentation-window-invalid",
                        "The benchmark window is not visible before the scenario starts.",
                    )
                }
                window.toFront()
                window.requestFocus()
            }
            session.evaluate("window.kwebBenchmark.startPresentProbe()")
            var stopped = false
            val frameCount = try {
                Thread.sleep(PRESENTATION_PREFLIGHT_MS)
                session.evaluate("window.kwebBenchmark.stopPresentProbe()").also { stopped = true }
                    .value?.jsonPrimitive?.doubleOrNull
                    ?: throw BenchmarkException(
                        "phase.presentation-evidence-missing",
                        "The renderer returned no presentation preflight frame count.",
                    )
            } catch (error: Throwable) {
                if (!stopped) {
                    try {
                        session.evaluate("window.kwebBenchmark.stopPresentProbe()")
                    } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                    }
                }
                throw error
            }
            if (frameCount < PRESENTATION_MINIMUM_FRAMES) {
                throw BenchmarkException(
                    "phase.presentation-evidence-invalid",
                    "The visible renderer produced only $frameCount frame(s) during the " +
                        "$PRESENTATION_PREFLIGHT_MS ms presentation preflight.",
                )
            }
        } catch (error: BenchmarkException) {
            throw error
        } catch (error: Throwable) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            throw BenchmarkException(
                "phase.presentation-preflight-failed",
                "The renderer presentation preflight did not complete.",
                error,
            )
        }
    }

    private fun throwPageFailure(session: KWebExampleCdpSession, stage: String): Nothing {
        val message = try {
            session.evaluate("String(window.__kwebBenchmarkFailure || document.querySelector('#conformance-copy')?.textContent || 'unknown page failure')")
                .value?.jsonPrimitive?.contentOrNull
        } catch (error: Throwable) {
            "Could not read page failure: ${error.message}"
        }
        throw BenchmarkException("phase.page-$stage-failed", "The workload page failed during $stage: $message")
    }

    private var activeWindow: ComposeWindow? = null

    private fun requireWindow(): ComposeWindow = activeWindow
        ?: throw BenchmarkException("phase.window-missing", "The benchmark window is missing during host sampling.")

    private data class Capture(val scale: Double)

    private fun captureWindow(window: ComposeWindow, path: Path): Capture {
        val bounds = AtomicReference<Rectangle>()
        val graphics = AtomicReference<GraphicsConfiguration>()
        try {
            SwingUtilities.invokeAndWait {
                if (!window.isShowing || !window.isDisplayable) {
                    throw BenchmarkException("phase.capture-window-invalid", "The benchmark window is not visible for capture.")
                }
                bounds.set(Rectangle(window.locationOnScreen, window.size))
                graphics.set(window.graphicsConfiguration)
            }
            val rectangle = bounds.get() ?: throw BenchmarkException("phase.capture-bounds-missing", "Benchmark window bounds are missing.")
            val device = graphics.get()?.device ?: throw BenchmarkException("phase.capture-device-missing", "Benchmark graphics device is missing.")
            val image = Robot(device).createScreenCapture(rectangle)
            Files.createDirectories(path.parent)
            if (!javax.imageio.ImageIO.write(image, "png", path.toFile())) {
                throw BenchmarkException("phase.capture-writer-missing", "The platform has no PNG writer.")
            }
            val scale = graphics.get()?.defaultTransform?.scaleX
                ?: throw BenchmarkException("phase.capture-scale-missing", "Benchmark display scale is missing.")
            if (scale <= 0.0) throw BenchmarkException("phase.capture-scale-invalid", "Benchmark display scale is not positive.")
            return Capture(scale)
        } catch (error: BenchmarkException) {
            throw error
        } catch (error: Throwable) {
            throw BenchmarkException("phase.capture-failed", "The benchmark screenshot could not be captured.", error)
        }
    }

    private fun createWindow(phase: String, sampleId: String): ComposeWindow {
        val reference = AtomicReference<ComposeWindow>()
        try {
            SwingUtilities.invokeAndWait {
                reference.set(
                    ComposeWindow().apply {
                        title = "KWebShell application benchmark ($phase/$sampleId)"
                        setSize(configuration.width, configuration.height)
                        setLocationRelativeTo(null)
                        isVisible = true
                    },
                )
            }
        } catch (error: Throwable) {
            throw BenchmarkException("phase.window-create-failed", "The benchmark ComposeWindow could not be created.", error)
        }
        val window = reference.get() ?: throw BenchmarkException("phase.window-missing", "ComposeWindow creation returned no window.")
        if (!window.isShowing || !window.isDisplayable || window.windowHandle == 0L) {
            window.dispose()
            throw BenchmarkException("phase.window-invalid", "Benchmark ComposeWindow has no visible native handle.")
        }
        activeWindow = window
        return window
    }

    private fun disposeWindow(window: ComposeWindow) {
        SwingUtilities.invokeAndWait {
            window.dispose()
            if (window.isDisplayable) throw BenchmarkException("phase.window-dispose-failed", "The benchmark window remained displayable.")
        }
        activeWindow = null
    }

    private fun writeHostEvidence(path: Path, evidence: HostEvidence) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.createDirectories(path.parent)
            Files.writeString(temporary, BenchmarkJson.format.encodeToString(evidence))
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw BenchmarkException("phase.host-evidence-write-failed", "Could not write host evidence '$path'.", error)
        }
    }

    private fun directoryBytes(path: Path): Double {
        if (!Files.exists(path)) return 0.0
        if (!Files.isDirectory(path)) throw BenchmarkException("phase.profile-not-directory", "Profile path '$path' is not a directory.")
        return Files.walk(path).use { paths ->
            paths.filter { Files.isRegularFile(it) }.mapToLong { Files.size(it) }.sum().toDouble()
        }
    }

    private fun runCleanup(target: MutableList<Throwable>, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            target += error
        }
    }

    private fun elapsedMs(startedNs: Long): Double = (System.nanoTime() - startedNs) / 1_000_000.0
    private fun requireMetric(value: Double?, code: String, message: String): Double = value ?: throw BenchmarkException(code, message)
    private fun percentile(values: List<Double>, p: Double): Double = io.github.kingsword09.kwebshell.example.benchmark.percentile(values, p)
    private fun requiredValue(name: String): String = System.getProperty(name)?.takeIf(String::isNotBlank)
        ?: throw BenchmarkException("phase.property-missing", "Missing required property '$name'.")
    private fun requiredPath(name: String): Path = Path.of(requiredValue(name)).toAbsolutePath().normalize()
}
