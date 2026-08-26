package io.github.kingsword09.kwebshell.example.html5

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.net.URI
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val ROOT_PROPERTY = "kweb.capability.lab.root"
private const val OUTPUT_PROPERTY = "kweb.capability.lab.output"
private const val PHASE_PROPERTY = "kweb.capability.lab.phase"
private const val ORIGIN_PROPERTY = "kweb.capability.lab.origin"
private const val PAGE_URL_PROPERTY = "kweb.capability.lab.page.url"
private const val SCREENSHOT_PROPERTY = "kweb.capability.lab.screenshot"
private const val HOST_EVIDENCE_PROPERTY = "kweb.capability.lab.host-evidence"
private const val CDP_PORT_PROPERTY = "kweb.capability.lab.cdp.port"
private const val NATIVE_LIBRARY_PROPERTY = "kweb.native.library.path"

public data class CapabilityLabConfiguration(
    public val cefRuntime: Path,
    public val browserSubprocess: Path,
    public val resources: Path,
    public val locales: Path,
    public val rootCache: Path,
    public val outputDirectory: Path,
    public val profileName: String = "capability-lab",
    public val width: Int = 1280,
    public val height: Int = 900,
    public val timeoutMs: Long = 60_000L,
) {
    init {
        require(width > 0 && height > 0) { "The capability lab window dimensions must be positive." }
        require(timeoutMs > 0L) { "The capability lab timeout must be positive." }
    }

    public companion object {
        public fun fromSystemProperties(): CapabilityLabConfiguration = CapabilityLabConfiguration(
            cefRuntime = requiredPath("kweb.engine.cef.runtime.path"),
            browserSubprocess = requiredPath("kweb.engine.subprocess.path"),
            resources = requiredPath("kweb.engine.resources.path"),
            locales = requiredPath("kweb.engine.locales.path"),
            rootCache = requiredPath(ROOT_PROPERTY),
            outputDirectory = requiredPath(OUTPUT_PROPERTY),
        )
    }
}

public class CapabilityLabRunner(
    private val configuration: CapabilityLabConfiguration,
) {
    public fun run(): Path {
        Files.createDirectories(configuration.rootCache)
        Files.createDirectories(configuration.outputDirectory)
        if (Files.exists(configuration.rootCache.resolve(configuration.profileName))) {
            throw CapabilityLabException(
                "profile-not-fresh",
                "The capability lab Profile already exists; use a new root cache for a deterministic run.",
            )
        }
        val staging = configuration.outputDirectory.resolve(".staging")
        if (Files.exists(staging)) {
            throw CapabilityLabException("staging-not-fresh", "The capability lab staging directory already exists.")
        }
        Files.createDirectory(staging)

        val server = CapabilityLabServer()
        var failure: Throwable? = null
        var reportPath: Path? = null
        try {
            val runs = mutableListOf<CapabilityLabRun>()
            val screenshots = linkedMapOf<String, Path>()
            val scales = mutableListOf<Double>()
            val hostEvidenceByPhase = mutableListOf<CapabilityPhaseHostEvidence>()
            listOf("cold", "warm").forEach { phase ->
                val screenshotName = "capability-lab-$phase.png"
                val screenshotPath = staging.resolve(screenshotName)
                val evidencePath = staging.resolve("host-$phase.json")
                runPhaseProcess(
                    phase = phase,
                    pageUrl = "${server.indexUrl}?phase=$phase",
                    origin = server.origin,
                    screenshotPath = screenshotPath,
                    evidencePath = evidencePath,
                    processLog = staging.resolve("process-$phase.log"),
                    cdpPort = findFreePort(),
                )
                val pageReport = server.awaitReport(phase, configuration.timeoutMs)
                val phaseEvidence = readHostEvidence(evidencePath, phase, screenshotName)
                runs += CapabilityLabRun(phase, pageReport, phaseEvidence)
                screenshots[screenshotName] = screenshotPath
                scales += phaseEvidence.displayScale
                hostEvidenceByPhase += phaseEvidence
            }
            if (scales.distinct().size != 1) {
                throw CapabilityLabException("display-scale-changed", "The display scale changed between cold and warm runs.")
            }
            val browserIdentity = hostEvidenceByPhase.first()
            if (hostEvidenceByPhase.any { evidence ->
                    evidence.chromiumProduct != browserIdentity.chromiumProduct ||
                        evidence.protocolVersion != browserIdentity.protocolVersion ||
                        evidence.revision != browserIdentity.revision ||
                        evidence.javaScriptVersion != browserIdentity.javaScriptVersion ||
                        evidence.accessibilityRole != "region" ||
                        evidence.accessibilityName != "KWebShell capability evidence"
                }
            ) {
                throw CapabilityLabException(
                    "cdp-evidence-inconsistent",
                    "Cold and warm runs did not produce identical CDP browser/accessibility evidence.",
                )
            }
            val bundle = CapabilityLabBundle(
                schemaVersion = CapabilityLabManifest.SCHEMA_VERSION,
                metadata = CapabilityLabMetadata(
                    runtimeSha256 = CapabilityLabDigest.sha256(configuration.cefRuntime),
                    chromiumProduct = browserIdentity.chromiumProduct,
                    protocolVersion = browserIdentity.protocolVersion,
                    revision = browserIdentity.revision,
                    javaScriptVersion = browserIdentity.javaScriptVersion,
                    platform = System.getProperty("os.name").orEmpty(),
                    architecture = System.getProperty("os.arch").orEmpty(),
                    displayScale = scales.first(),
                    hostPolicy = "compose-window-default-permission-policy",
                ),
                runs = runs,
            )
            reportPath = CapabilityLabArtifactWriter(configuration.outputDirectory).write(
                bundle = bundle,
                screenshots = screenshots,
                expectedOrigin = server.origin,
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            try {
                server.close()
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            try {
                deleteRecursively(staging)
            } catch (error: Throwable) {
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
        return reportPath ?: throw CapabilityLabException("report-missing", "The capability lab produced no report path.")
    }

    private fun runPhaseProcess(
        phase: String,
        pageUrl: String,
        origin: String,
        screenshotPath: Path,
        evidencePath: Path,
        processLog: Path,
        cdpPort: Int,
    ) {
        val javaExecutable = Path.of(
            System.getProperty("java.home"),
            "bin",
            if (isWindows()) "java.exe" else "java",
        )
        if (!Files.isRegularFile(javaExecutable)) {
            throw CapabilityLabException("java-executable-missing", "JDK executable '$javaExecutable' is missing.")
        }
        val classpath = System.getProperty("java.class.path")?.takeIf { it.isNotBlank() }
            ?: throw CapabilityLabException("classpath-missing", "The coordinator JVM classpath is empty.")
        val command = buildList {
            add(javaExecutable.toString())
            add("--enable-native-access=ALL-UNNAMED")
            add("-Djava.awt.headless=false")
            propagatedPropertyNames().forEach { name ->
                val value = System.getProperty(name)?.takeIf { it.isNotBlank() }
                    ?: throw CapabilityLabException("configuration-missing", "Missing required system property '$name'.")
                add("-D$name=$value")
            }
            add("-D$PHASE_PROPERTY=$phase")
            add("-D$ORIGIN_PROPERTY=$origin")
            add("-D$PAGE_URL_PROPERTY=$pageUrl")
            add("-D$SCREENSHOT_PROPERTY=${screenshotPath.toAbsolutePath()}")
            add("-D$HOST_EVIDENCE_PROPERTY=${evidencePath.toAbsolutePath()}")
            add("-D$CDP_PORT_PROPERTY=$cdpPort")
            add("-cp")
            add(classpath)
            add("io.github.kingsword09.kwebshell.example.html5.MainKt")
            add("phase")
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(processLog.toFile())
            .start()
        val completed = process.waitFor(configuration.timeoutMs * 2, TimeUnit.MILLISECONDS)
        if (!completed) {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    throw CapabilityLabException(
                        "phase-process-termination-timeout",
                        "Capability lab '$phase' process could not be terminated.\n${readProcessLog(processLog)}",
                    )
                }
            }
            throw CapabilityLabException(
                "phase-process-timeout",
                "Capability lab '$phase' process exceeded ${configuration.timeoutMs * 2} ms.\n${readProcessLog(processLog)}",
            )
        }
        if (process.exitValue() != 0) {
            throw CapabilityLabException(
                "phase-process-failed",
                "Capability lab '$phase' process exited with ${process.exitValue()}.\n${readProcessLog(processLog)}",
            )
        }
    }

    private fun readHostEvidence(path: Path, phase: String, screenshotName: String): CapabilityPhaseHostEvidence {
        if (!Files.isRegularFile(path)) {
            throw CapabilityLabException("host-evidence-missing", "Phase '$phase' did not write host evidence.")
        }
        val evidence = try {
            CapabilityLabJson.format.decodeFromString<CapabilityPhaseHostEvidence>(Files.readString(path))
        } catch (error: Throwable) {
            throw CapabilityLabException("host-evidence-invalid", "Phase '$phase' host evidence is invalid.", error)
        }
        if (evidence.schemaVersion != CapabilityLabManifest.SCHEMA_VERSION ||
            evidence.phase != phase || evidence.screenshotFile != screenshotName || evidence.displayScale <= 0.0 ||
            evidence.chromiumProduct.isBlank() || evidence.protocolVersion.isBlank() ||
            evidence.revision.isBlank() || evidence.javaScriptVersion.isBlank() ||
            evidence.accessibilityRole != "region" ||
            evidence.accessibilityName != "KWebShell capability evidence" ||
            evidence.eventSequences.isEmpty() || evidence.eventSequences.size != evidence.eventTypes.size ||
            evidence.eventSequences != (1L..evidence.eventSequences.last()).toList() ||
            evidence.eventTypes.first() != "created" || evidence.eventTypes.last() != "title-changed"
        ) {
            throw CapabilityLabException("host-evidence-invalid", "Phase '$phase' host evidence does not match the request.")
        }
        return evidence
    }

    private fun readProcessLog(path: Path): String =
        if (Files.isRegularFile(path)) Files.readString(path).takeLast(64 * 1024) else "<process log missing>"

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { entry ->
                if (!Files.deleteIfExists(entry) && Files.exists(entry)) {
                    throw CapabilityLabException("staging-delete-failed", "Could not delete staging path '$entry'.")
                }
            }
        }
    }

    private fun propagatedPropertyNames(): List<String> = listOf(
        NATIVE_LIBRARY_PROPERTY,
        "kweb.engine.cef.runtime.path",
        "kweb.engine.subprocess.path",
        "kweb.engine.resources.path",
        "kweb.engine.locales.path",
        ROOT_PROPERTY,
        OUTPUT_PROPERTY,
    )

    private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
        socket.localPort.takeIf { it in 1024..65535 }
            ?: throw CapabilityLabException("cdp-port-invalid", "The OS allocated an invalid CDP port.")
    }
}

internal class CapabilityLabPhaseRunner(
    private val configuration: CapabilityLabConfiguration,
) {
    fun runFromSystemProperties() {
        val phase = requiredValue(PHASE_PROPERTY)
        if (phase != "cold" && phase != "warm") {
            throw CapabilityLabException("phase-invalid", "Unknown phase '$phase'.")
        }
        val origin = requiredValue(ORIGIN_PROPERTY)
        val pageUrl = requiredValue(PAGE_URL_PROPERTY)
        val screenshotPath = requiredPath(SCREENSHOT_PROPERTY)
        val evidencePath = requiredPath(HOST_EVIDENCE_PROPERTY)
        val cdpPort = requiredValue(CDP_PORT_PROPERTY).toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: throw CapabilityLabException("cdp-port-invalid", "The phase CDP port is invalid.")
        val pageUri = try {
            URI(pageUrl)
        } catch (error: Throwable) {
            throw CapabilityLabException("page-url-invalid", "The phase page URL is invalid.", error)
        }
        if (pageUri.scheme != "http" || "${pageUri.scheme}://${pageUri.authority}" != origin) {
            throw CapabilityLabException("page-origin-invalid", "The phase page URL does not belong to '$origin'.")
        }
        runPhase(phase, pageUrl, screenshotPath, evidencePath, cdpPort)
    }

    private fun runPhase(
        phase: String,
        pageUrl: String,
        screenshotPath: Path,
        evidencePath: Path,
        cdpPort: Int,
    ) {
        var window: ComposeWindow? = null
        var engine: KWebEngine? = null
        var profile: KWebProfile? = null
        var page: KWebPage? = null
        val cdp = CapabilityLabCdpClient(cdpPort, configuration.timeoutMs)
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            reportStage(phase, "started")
            window = createWindow(phase)
            reportStage(phase, "window-created")
            engine = KWebDesktop.openEngine(
                KWebDesktopEngineConfiguration(
                    cefRuntime = configuration.cefRuntime,
                    browserSubprocess = configuration.browserSubprocess,
                    resources = configuration.resources,
                    locales = configuration.locales,
                    rootCache = configuration.rootCache,
                    log = configuration.rootCache.resolve("cef.log"),
                    remoteDebuggingPort = cdpPort,
                ),
            )
            if (KWebCapability.CDP !in engine.capabilities) {
                throw CapabilityLabException("cdp-capability-missing", "The explicitly configured Engine omitted CDP capability.")
            }
            reportStage(phase, "engine-open")
            profile = runBlocking { engine.openProfile(configuration.profileName) }
            page = runBlocking {
                profile.openPage(
                    KWebDesktop.composeWindowHost(window),
                    pageUrl,
                    KWebBounds(configuration.width, configuration.height),
                )
            }
            reportStage(phase, "page-open")
            val expectedTitle = "KWEB_CAPABILITY_LAB_${phase.uppercase(Locale.ROOT)}_PASS"
            val inputReadyTitle = "KWEB_CAPABILITY_LAB_${phase.uppercase(Locale.ROOT)}_INPUT_READY"
            runBlocking {
                withTimeout(configuration.timeoutMs) {
                    page.events.first { event ->
                        event.type == KWebPageEventType.TITLE_CHANGED && event.text == inputReadyTitle
                    }
                }
            }
            reportStage(phase, "input-ready")
            cdp.dispatchTrustedClick(pageUrl, 120, 48)
            reportStage(phase, "input-dispatched")
            val publicEvents = runBlocking {
                withTimeout(configuration.timeoutMs) {
                    page.events.transformWhile { event ->
                        emit(event)
                        event.type != KWebPageEventType.TITLE_CHANGED || event.text != expectedTitle
                    }.toList()
                }
            }
            reportStage(phase, "report-published")
            val cdpEvidence = cdp.inspect(pageUrl)
            reportStage(phase, "cdp-inspected")
            val screenshot = captureWindow(window)
            writeScreenshot(screenshotPath, screenshot.image)
            writeHostEvidence(
                evidencePath,
                CapabilityPhaseHostEvidence(
                    schemaVersion = CapabilityLabManifest.SCHEMA_VERSION,
                    phase = phase,
                    screenshotFile = screenshotPath.fileName.toString(),
                    displayScale = screenshot.scale,
                    chromiumProduct = cdpEvidence.chromiumProduct,
                    protocolVersion = cdpEvidence.protocolVersion,
                    revision = cdpEvidence.revision,
                    javaScriptVersion = cdpEvidence.javaScriptVersion,
                    accessibilityRole = cdpEvidence.accessibilityRole,
                    accessibilityName = cdpEvidence.accessibilityName,
                    eventSequences = publicEvents.map { it.sequence },
                    eventTypes = publicEvents.map { it.type.id },
                ),
            )
            reportStage(phase, "artifacts-written")
        } catch (error: Throwable) {
            failure = error
        } finally {
            page?.let { resource ->
                try {
                    if (resource.lifecycle.value != KWebLifecycleState.CLOSED) resource.close()
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            profile?.let { resource ->
                try {
                    if (resource.lifecycle.value != KWebLifecycleState.CLOSED) resource.close()
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            engine?.let { resource ->
                try {
                    if (resource.lifecycle.value != KWebLifecycleState.CLOSED) resource.close()
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            window?.let { resource ->
                try {
                    disposeWindow(resource)
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
            try {
                cdp.assertUnavailable()
            } catch (error: Throwable) {
                cleanupFailures += error
            }
        }
        if (failure != null) {
            cleanupFailures.forEach(failure::addSuppressed)
            throw failure
        }
        cleanupFailures.firstOrNull()?.let { throw it }
        reportStage(phase, "closed")
    }

    private fun createWindow(phase: String): ComposeWindow {
        val reference = AtomicReference<ComposeWindow>()
        try {
            SwingUtilities.invokeAndWait {
                reference.set(ComposeWindow().apply {
                    title = "KWebShell HTML5 Capability Lab ($phase)"
                    setSize(configuration.width, configuration.height)
                    setLocationRelativeTo(null)
                    isVisible = true
                })
            }
        } catch (error: Throwable) {
            throw CapabilityLabException("window-create-failed", "Could not create the visible ComposeWindow parent.", error)
        }
        val window = reference.get()
            ?: throw CapabilityLabException("window-create-failed", "The ComposeWindow creation returned no window.")
        if (!window.isDisplayable || !window.isShowing || window.windowHandle == 0L) {
            window.dispose()
            throw CapabilityLabException("window-not-visible", "The ComposeWindow parent has no valid visible native handle.")
        }
        return window
    }

    private data class CapturedScreenshot(
        val image: BufferedImage,
        val scale: Double,
    )

    private fun captureWindow(window: ComposeWindow): CapturedScreenshot {
        val bounds = AtomicReference<Rectangle>()
        val graphics = AtomicReference<GraphicsConfiguration>()
        try {
            SwingUtilities.invokeAndWait {
                if (!window.isShowing || !window.isDisplayable) {
                    throw CapabilityLabException("screenshot-window-invalid", "The ComposeWindow was not visible for capture.")
                }
                bounds.set(Rectangle(window.locationOnScreen, window.size))
                graphics.set(window.graphicsConfiguration)
            }
            val rectangle = bounds.get()
                ?: throw CapabilityLabException("screenshot-bounds-missing", "The visible window had no screen bounds.")
            if (rectangle.width <= 0 || rectangle.height <= 0) {
                throw CapabilityLabException("screenshot-bounds-invalid", "The visible window had non-positive screen bounds.")
            }
            val graphicsConfiguration = graphics.get()
                ?: throw CapabilityLabException("screenshot-device-missing", "The window had no graphics configuration.")
            val image = Robot(graphicsConfiguration.device).createScreenCapture(rectangle)
            if (image.width != rectangle.width || image.height != rectangle.height) {
                throw CapabilityLabException("screenshot-size-mismatch", "The screenshot dimensions did not match the ComposeWindow.")
            }
            val scale = graphicsConfiguration.defaultTransform.scaleX
            if (scale <= 0.0) {
                throw CapabilityLabException("screenshot-scale-invalid", "The display scale was not positive.")
            }
            return CapturedScreenshot(image, scale)
        } catch (error: CapabilityLabException) {
            throw error
        } catch (error: Throwable) {
            throw CapabilityLabException("screenshot-capture-failed", "The native screen capture failed.", error)
        }
    }

    private fun writeScreenshot(path: Path, image: BufferedImage) {
        Files.createDirectories(path.parent)
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            if (!javax.imageio.ImageIO.write(image, "png", temporary.toFile())) {
                throw CapabilityLabException("screenshot-writer-missing", "No PNG writer is available.")
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            if (error is CapabilityLabException) throw error
            throw CapabilityLabException("screenshot-write-failed", "Could not write screenshot '$path'.", error)
        }
    }

    private fun writeHostEvidence(path: Path, evidence: CapabilityPhaseHostEvidence) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.writeString(
                temporary,
                CapabilityLabJson.format.encodeToString(evidence),
                StandardCharsets.UTF_8,
            )
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw CapabilityLabException("host-evidence-write-failed", "Could not write host evidence '$path'.", error)
        }
    }

    private fun disposeWindow(window: ComposeWindow) {
        SwingUtilities.invokeAndWait {
            window.dispose()
            if (window.isDisplayable) {
                throw CapabilityLabException("window-dispose-failed", "The ComposeWindow remained displayable after disposal.")
            }
        }
    }

    private fun reportStage(phase: String, stage: String) {
        println("KWEBSHELL_CAPABILITY_LAB_STAGE:$phase:$stage")
        System.out.flush()
    }
}

private fun requiredValue(name: String): String =
    System.getProperty(name)?.takeIf { it.isNotBlank() }
        ?: throw CapabilityLabException("configuration-missing", "Missing required system property '$name'.")

private fun requiredPath(name: String): Path = Path.of(requiredValue(name)).toAbsolutePath().normalize()

private fun isWindows(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")
