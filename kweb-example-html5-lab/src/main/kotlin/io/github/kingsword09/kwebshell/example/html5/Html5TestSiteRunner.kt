package io.github.kingsword09.kwebshell.example.html5

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
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import javax.swing.SwingUtilities

private const val LIVE_PROFILE_NAME = "html5test-live"
private val LIVE_REQUIRED_CAPABILITIES = setOf(KWebCapability.NATIVE_CHILD, KWebCapability.CDP)

internal fun requireHtml5TestCapabilities(capabilities: Set<KWebCapability>) {
    val missing = LIVE_REQUIRED_CAPABILITIES.filterNot(capabilities::contains)
    if (missing.isNotEmpty()) {
        throw Html5TestSiteException(
            "html5test.capability-missing",
            "The live HTML5test runner requires explicit Engine capabilities " +
                "${LIVE_REQUIRED_CAPABILITIES.map(KWebCapability::id)}; missing ${missing.map(KWebCapability::id)}.",
        )
    }
}

internal data class Html5TestScreenshot(
    val bytes: ByteArray,
    val image: BufferedImage,
)

internal object Html5TestScreenshotParser {
    fun parse(result: JsonObject): Html5TestScreenshot {
        val encoded = (result["data"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw Html5TestSiteException(
                "html5test.screenshot-data-missing",
                "CDP returned no live HTML5test screenshot data.",
            )
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw Html5TestSiteException(
                "html5test.screenshot-data-invalid",
                "CDP returned invalid Base64 screenshot data.",
                error,
            )
        }
        if (!bytes.startsWithPngSignature()) {
            throw Html5TestSiteException(
                "html5test.screenshot-png-invalid",
                "CDP screenshot data is not PNG.",
            )
        }
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (error: Throwable) {
            throw Html5TestSiteException(
                "html5test.screenshot-png-invalid",
                "CDP screenshot PNG could not be decoded.",
                error,
            )
        } ?: throw Html5TestSiteException(
            "html5test.screenshot-png-invalid",
            "CDP screenshot PNG could not be decoded.",
        )
        if (image.width <= 0 || image.height <= 0 || distinctSampledColors(image) < MINIMUM_DISTINCT_COLORS) {
            throw Html5TestSiteException(
                "html5test.screenshot-content-invalid",
                "CDP screenshot has invalid dimensions or no visible page detail.",
            )
        }
        return Html5TestScreenshot(bytes, image)
    }

    private fun ByteArray.startsWithPngSignature(): Boolean =
        size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }

    private fun distinctSampledColors(image: BufferedImage): Int {
        val colors = HashSet<Int>()
        val stepX = maxOf(1, image.width / 64)
        val stepY = maxOf(1, image.height / 64)
        var y = 0
        while (y < image.height && colors.size < MINIMUM_DISTINCT_COLORS) {
            var x = 0
            while (x < image.width && colors.size < MINIMUM_DISTINCT_COLORS) {
                colors += image.getRGB(x, y)
                x += stepX
            }
            y += stepY
        }
        return colors.size
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private const val MINIMUM_DISTINCT_COLORS: Int = 8
}

public class Html5TestSiteRunner(
    private val configuration: CapabilityLabConfiguration,
) {
    public fun run(): Path {
        val outputParent = configuration.outputDirectory.parent
            ?: throw Html5TestSiteException(
                "html5test.output-parent-missing",
                "The live HTML5test output directory has no parent.",
            )
        try {
            Files.createDirectories(configuration.rootCache)
            Files.createDirectories(outputParent)
        } catch (error: Throwable) {
            throw Html5TestSiteException(
                "html5test.directory-create-failed",
                "Could not create the live HTML5test root or output parent directory.",
                error,
            )
        }
        val profilePath = configuration.rootCache.resolve(LIVE_PROFILE_NAME)
        if (Files.exists(profilePath)) {
            throw Html5TestSiteException(
                "html5test.profile-not-fresh",
                "The live HTML5test Profile already exists; use a new root cache for a deterministic run.",
            )
        }
        val staging = configuration.outputDirectory.resolveSibling(".${configuration.outputDirectory.fileName}.staging")
        val reportPath = configuration.outputDirectory.resolve(Html5TestSiteContract.REPORT_FILE)
        if (Files.exists(staging)) {
            throw Html5TestSiteException(
                "html5test.staging-not-fresh",
                "The live HTML5test staging directory already exists.",
            )
        }
        if (Files.exists(configuration.outputDirectory) &&
            (!Files.isDirectory(configuration.outputDirectory) || configuration.outputDirectory.hasEntries())
        ) {
            throw Html5TestSiteException(
                "html5test.output-not-fresh",
                "The live HTML5test output path '${configuration.outputDirectory}' is not an empty directory.",
            )
        }
        val cdpPort = findFreePort()
        val cdp = KWebExampleCdpClient(cdpPort, configuration.timeoutMs)
        try {
            Files.createDirectory(staging)
        } catch (error: Throwable) {
            throw Html5TestSiteException(
                "html5test.staging-create-failed",
                "Could not create the live HTML5test staging directory '$staging'.",
                error,
            )
        }
        val screenshotPath = staging.resolve(Html5TestSiteContract.SCREENSHOT_FILE)
        val stagedReportPath = staging.resolve(Html5TestSiteContract.REPORT_FILE)
        val stagedHtmlPath = staging.resolve("html5test-report.html")
        var window: ComposeWindow? = null
        var engine: KWebEngine? = null
        var profile: KWebProfile? = null
        var page: KWebPage? = null
        var pageSession: KWebExampleCdpSession? = null
        var failure: Throwable? = null
        val cleanupFailures = mutableListOf<Throwable>()
        try {
            window = createWindow()
            val liveEngine = KWebDesktop.openEngine(
                KWebDesktopEngineConfiguration(
                    cefRuntime = configuration.cefRuntime,
                    browserSubprocess = configuration.browserSubprocess,
                    resources = configuration.resources,
                    locales = configuration.locales,
                    rootCache = configuration.rootCache,
                    log = configuration.rootCache.resolve("cef-html5test.log"),
                    remoteDebuggingPort = cdpPort,
                ),
            )
            engine = liveEngine
            requireHtml5TestCapabilities(liveEngine.capabilities)
            val liveProfile = runBlocking { liveEngine.openProfile(LIVE_PROFILE_NAME) }
            profile = liveProfile
            val livePage = runBlocking {
                liveProfile.openPage(
                    KWebDesktop.composeWindowHost(requireWindow(window)),
                    Html5TestSiteContract.URL,
                    KWebBounds(configuration.width, configuration.height),
                )
            }
            page = livePage
            val browser = cdp.awaitBrowserVersion()
            pageSession = cdp.openPageSession(Html5TestSiteContract.URL)
            pageSession.command("Page.enable")
            val site = readSiteEvidence(pageSession)
            val events = collectPageEvents(livePage)
            if (site.finalUrl != Html5TestSiteContract.URL) {
                throw Html5TestSiteException(
                    "html5test.final-url-invalid",
                    "The live page ended at '${site.finalUrl}', not '${Html5TestSiteContract.URL}'.",
                )
            }
            val displayScale = requireVisibleWindowScale(requireWindow(window))
            pageSession.evaluate("new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
            val screenshot = capturePageScreenshot(pageSession)
            writeScreenshot(screenshotPath, screenshot.bytes)
            val report = Html5TestSiteReport(
                schemaVersion = Html5TestSiteContract.SCHEMA_VERSION,
                requestedUrl = Html5TestSiteContract.URL,
                finalUrl = site.finalUrl,
                title = site.title,
                score = site.score,
                maxScore = site.maxScore,
                scoreText = site.scoreText,
                readyState = site.readyState,
                secureContext = site.secureContext,
                archivedTestNoticePresent = site.archivedTestNoticePresent,
                collectedAtEpochMs = System.currentTimeMillis(),
                runtimeSha256 = CapabilityLabDigest.sha256(configuration.cefRuntime),
                chromiumProduct = browser.product,
                protocolVersion = browser.protocolVersion,
                revision = browser.revision,
                javaScriptVersion = browser.javaScriptVersion,
                userAgent = browser.userAgent,
                platform = System.getProperty("os.name").orEmpty(),
                architecture = System.getProperty("os.arch").orEmpty(),
                displayScale = displayScale,
                screenshotSource = Html5TestSiteContract.SCREENSHOT_SOURCE,
                screenshotFile = screenshotPath.fileName.toString(),
                screenshotSha256 = CapabilityLabDigest.sha256(screenshotPath),
                screenshotWidth = screenshot.image.width,
                screenshotHeight = screenshot.image.height,
                events = events.map { it.toEvidence() },
            )
            Html5TestSiteValidator.validate(report)
            writeTextAtomically(stagedReportPath, CapabilityLabJson.format.encodeToString(report))
            writeTextAtomically(stagedHtmlPath, renderHtml(report))
        } catch (error: Throwable) {
            failure = error
        } finally {
            pageSession?.let { session ->
                try {
                    session.close()
                } catch (error: Throwable) {
                    cleanupFailures += error
                }
            }
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
        if (failure != null || cleanupFailures.isNotEmpty()) {
            try {
                deleteRecursively(staging)
            } catch (error: Throwable) {
                cleanupFailures += Html5TestSiteException(
                    "html5test.failed-staging-delete-failed",
                    "Could not remove failed-run staging directory '$staging'.",
                    error,
                )
            }
            if (failure != null) {
                cleanupFailures.forEach(failure::addSuppressed)
                throw failure
            }
            val first = cleanupFailures.first()
            cleanupFailures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        try {
            if (Files.exists(configuration.outputDirectory)) {
                if (!Files.isDirectory(configuration.outputDirectory) || configuration.outputDirectory.hasEntries()) {
                    throw Html5TestSiteException(
                        "html5test.output-publish-not-empty",
                        "The live HTML5test output directory changed before publication.",
                    )
                }
                Files.delete(configuration.outputDirectory)
            }
            Files.move(staging, configuration.outputDirectory, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Throwable) {
            try {
                deleteRecursively(staging)
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw Html5TestSiteException(
                "html5test.output-publish-failed",
                "Could not atomically publish the live HTML5test evidence directory.",
                error,
            )
        }
        return reportPath
    }

    private fun collectPageEvents(page: KWebPage): List<KWebPageEvent> = runBlocking {
        try {
            var titleSeen = false
            var addressSeen = false
            var successfulLoadSeen = false
            withTimeout(configuration.timeoutMs) {
                page.events.transformWhile { event ->
                    if (event.type == KWebPageEventType.LOAD_FAILED || event.type == KWebPageEventType.FATAL_ERROR) {
                        throw Html5TestSiteException(
                            "html5test.page-load-failed",
                            "The live HTML5test page reported '${event.type.id}': ${event.text}.",
                        )
                    }
                    emit(event)
                    titleSeen = titleSeen ||
                        (event.type == KWebPageEventType.TITLE_CHANGED && event.text == Html5TestSiteContract.TITLE)
                    addressSeen = addressSeen ||
                        (event.type == KWebPageEventType.ADDRESS_CHANGED && event.text == Html5TestSiteContract.URL)
                    successfulLoadSeen = successfulLoadSeen ||
                        (event.type == KWebPageEventType.LOAD_ENDED &&
                            event.text == Html5TestSiteContract.URL && event.statusCode == 200)
                    !(titleSeen && addressSeen && successfulLoadSeen)
                }.toList()
            }
        } catch (error: Html5TestSiteException) {
            throw error
        } catch (error: Throwable) {
            throw Html5TestSiteException(
                "html5test.page-events-timeout",
                "The live HTML5test page did not publish its canonical title event.",
                error,
            )
        }
    }

    private data class SiteEvidence(
        val finalUrl: String,
        val title: String,
        val score: Int,
        val maxScore: Int,
        val scoreText: String,
        val readyState: String,
        val secureContext: Boolean,
        val archivedTestNoticePresent: Boolean,
    )

    private fun readSiteEvidence(session: KWebExampleCdpSession): SiteEvidence {
        val expression = """
            (() => {
              const panel = document.querySelector('#score .pointsPanel h2');
              const scoreText = (panel?.textContent || '').replace(/\s+/g, ' ').trim();
              const match = scoreText.match(/^Your browser scores\s+(\d+)\s+out of\s+(\d+)\s+points$/i);
              const notice = [...document.querySelectorAll('h1,h2,h3,p')]
                .some(node => /HTML5TEST IS DEAD/i.test(node.textContent || ''));
              return {
                finalUrl: location.href,
                title: document.title,
                score: match ? Number(match[1]) : -1,
                maxScore: match ? Number(match[2]) : -1,
                scoreText,
                readyState: document.readyState,
                secureContext: window.isSecureContext === true,
                archivedTestNoticePresent: notice
              };
            })()
        """.trimIndent()
        val deadline = System.nanoTime() + configuration.timeoutMs * 1_000_000L
        var lastEvidence: SiteEvidence? = null
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val value = session.evaluate(expression).value?.jsonObject
                    ?: throw Html5TestSiteException(
                        "html5test.cdp-evidence-missing",
                        "CDP returned no live HTML5test evidence object.",
                    )
                val evidence = SiteEvidence(
                    finalUrl = value.requiredString("finalUrl"),
                    title = value.requiredString("title"),
                    score = value.requiredInt("score"),
                    maxScore = value.requiredInt("maxScore"),
                    scoreText = value.requiredString("scoreText"),
                    readyState = value.requiredString("readyState"),
                    secureContext = value.requiredBoolean("secureContext"),
                    archivedTestNoticePresent = value.requiredBoolean("archivedTestNoticePresent"),
                )
                lastEvidence = evidence
                if (evidence.finalUrl == Html5TestSiteContract.URL &&
                    evidence.title == Html5TestSiteContract.TITLE &&
                    evidence.score >= 0 && evidence.maxScore > 0 &&
                    evidence.readyState == "complete" && evidence.secureContext
                ) {
                    return evidence
                }
            } catch (error: Throwable) {
                lastFailure = error
            }
            try {
                Thread.sleep(100)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw Html5TestSiteException(
                    "html5test.cdp-poll-interrupted",
                    "Waiting for the live HTML5test score was interrupted.",
                    error,
                )
            }
        }
        throw Html5TestSiteException(
            "html5test.score-timeout",
            "The live HTML5test score DOM did not reach the required structure; last evidence=$lastEvidence.",
            lastFailure,
        )
    }

    private fun createWindow(): ComposeWindow {
        val reference = AtomicReference<ComposeWindow>()
        try {
            SwingUtilities.invokeAndWait {
                reference.set(ComposeWindow().apply {
                    title = "KWebShell HTML5test.com"
                    setSize(configuration.width, configuration.height)
                    setLocationRelativeTo(null)
                    isVisible = true
                })
            }
        } catch (error: Throwable) {
            throw Html5TestSiteException("html5test.window-create-failed", "Could not create the live HTML5test window.", error)
        }
        val window = reference.get()
            ?: throw Html5TestSiteException("html5test.window-create-failed", "The live HTML5test window was not returned.")
        if (!window.isDisplayable || !window.isShowing || window.windowHandle == 0L) {
            window.dispose()
            throw Html5TestSiteException("html5test.window-not-visible", "The live HTML5test window is not visible.")
        }
        return window
    }

    private fun requireVisibleWindowScale(window: ComposeWindow): Double {
        val scale = AtomicReference<Double>()
        try {
            SwingUtilities.invokeAndWait {
                if (!window.isShowing || !window.isDisplayable || window.windowHandle == 0L) {
                    throw Html5TestSiteException(
                        "html5test.screenshot-window-invalid",
                        "The live HTML5test window is not visible or has no native handle.",
                    )
                }
                scale.set(window.graphicsConfiguration?.defaultTransform?.scaleX)
            }
            return scale.get()?.takeIf { it.isFinite() && it > 0.0 }
                ?: throw Html5TestSiteException("html5test.screenshot-scale-invalid", "The live HTML5test display scale is invalid.")
        } catch (error: Html5TestSiteException) {
            throw error
        } catch (error: Throwable) {
            throw Html5TestSiteException("html5test.screenshot-window-failed", "The live HTML5test window could not be inspected.", error)
        }
    }

    private fun capturePageScreenshot(session: KWebExampleCdpSession): Html5TestScreenshot =
        Html5TestScreenshotParser.parse(
            session.command(
                "Page.captureScreenshot",
                buildJsonObject {
                    put("format", "png")
                    put("fromSurface", true)
                    put("captureBeyondViewport", false)
                },
            ),
        )

    private fun writeScreenshot(path: Path, bytes: ByteArray) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.write(temporary, bytes)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            if (error is Html5TestSiteException) throw error
            throw Html5TestSiteException("html5test.screenshot-write-failed", "Could not write the live HTML5test screenshot.", error)
        }
    }

    private fun writeTextAtomically(path: Path, text: String) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw Html5TestSiteException("html5test.report-write-failed", "Could not write '${path.fileName}'.", error)
        }
    }

    private fun renderHtml(report: Html5TestSiteReport): String {
        fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>KWebShell HTML5test.com evidence</title>
<style>body{font:14px system-ui,sans-serif;margin:2rem;color:#202124}code{font-family:ui-monospace,monospace}table{border-collapse:collapse}th,td{border:1px solid #d0d7de;padding:.45rem;text-align:left}</style></head>
<body><h1>HTML5test.com evidence</h1><p><strong>${report.score} / ${report.maxScore}</strong> points<br>
<a href="${escape(report.finalUrl)}">${escape(report.finalUrl)}</a><br>
${escape(report.title)}<br>Chromium: <code>${escape(report.chromiumProduct)}</code><br>
Runtime SHA-256: <code>${escape(report.runtimeSha256)}</code></p>
<table><tr><th>Evidence</th><th>Value</th></tr>
<tr><td>Score text</td><td>${escape(report.scoreText)}</td></tr>
<tr><td>Secure context</td><td>${report.secureContext}</td></tr>
<tr><td>Site notice</td><td>${report.archivedTestNoticePresent}</td></tr>
<tr><td>Screenshot source</td><td>${escape(report.screenshotSource)}</td></tr>
<tr><td>Screenshot</td><td>${escape(report.screenshotFile)} (${report.screenshotWidth}x${report.screenshotHeight})</td></tr>
</table><h2>Public page events</h2><pre>${escape(report.events.joinToString("\n") { "${it.sequence} ${it.type} ${it.statusCode} ${it.text}" })}</pre></body></html>"""
    }

    private fun disposeWindow(window: ComposeWindow) {
        SwingUtilities.invokeAndWait {
            window.dispose()
            if (window.isDisplayable) throw Html5TestSiteException("html5test.window-dispose-failed", "The live HTML5test window remained displayable.")
        }
    }

    private fun requireWindow(window: ComposeWindow?): ComposeWindow = window
        ?: throw Html5TestSiteException("html5test.window-missing", "The live HTML5test window was not created.")

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { entries ->
            entries.sorted(Comparator.reverseOrder()).forEach { entry ->
                if (!Files.deleteIfExists(entry) && Files.exists(entry)) {
                    throw Html5TestSiteException("html5test.staging-delete-failed", "Could not delete '$entry'.")
                }
            }
        }
    }

    private fun Path.hasEntries(): Boolean = Files.list(this).use { entries -> entries.findAny().isPresent }

    private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
        socket.localPort.takeIf { it in 1024..65535 }
            ?: throw Html5TestSiteException("html5test.cdp-port-invalid", "The OS allocated an invalid CDP port.")
    }
}

private fun KWebPageEvent.toEvidence(): Html5TestSiteEventEvidence = Html5TestSiteEventEvidence(
    sequence = sequence,
    type = type.id,
    text = text,
    statusCode = statusCode,
)

private fun JsonObject.requiredString(name: String): String = this[name]?.jsonPrimitive?.contentOrNull
    ?.takeIf { it.isNotBlank() }
    ?: throw Html5TestSiteException("html5test.cdp-field-missing", "CDP field '$name' is missing or empty.")

private fun JsonObject.requiredInt(name: String): Int = this[name]?.jsonPrimitive?.intOrNull
    ?: throw Html5TestSiteException("html5test.cdp-field-invalid", "CDP field '$name' is not an integer.")

private fun JsonObject.requiredBoolean(name: String): Boolean = this[name]?.jsonPrimitive?.booleanOrNull
    ?: throw Html5TestSiteException("html5test.cdp-field-invalid", "CDP field '$name' is not a boolean.")
