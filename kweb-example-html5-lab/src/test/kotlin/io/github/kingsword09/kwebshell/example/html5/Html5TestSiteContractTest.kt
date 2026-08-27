package io.github.kingsword09.kwebshell.example.html5

import io.github.kingsword09.kwebshell.core.KWebCapability
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Html5TestSiteContractTest {
    @Test
    fun locksTheUserFacingExampleToTheRealHtml5TestPage() {
        assertEquals("https://html5test.com/", Html5TestSiteContract.URL)
        assertEquals("cdp-page-target", Html5TestSiteContract.SCREENSHOT_SOURCE)
    }

    @Test
    fun acceptsStrictLiveSiteEvidence() {
        Html5TestSiteValidator.validate(sampleReport())
    }

    @Test
    fun doesNotRequireTheArchivedSiteNotice() {
        Html5TestSiteValidator.validate(sampleReport().copy(archivedTestNoticePresent = false))
    }

    @Test
    fun reportJsonRoundTripsThroughTheStrictArtifactFormat() {
        val report = sampleReport()
        val encoded = CapabilityLabJson.format.encodeToString(report)
        assertEquals(report, CapabilityLabJson.format.decodeFromString<Html5TestSiteReport>(encoded))
    }

    @Test
    fun rejectsAChangedScoreSelectorResult() {
        val error = assertFailsWith<Html5TestSiteException> {
            Html5TestSiteValidator.validate(sampleReport().copy(scoreText = "HTML5 score unavailable"))
        }
        assertEquals("html5test.report-invalid", error.code)
    }

    @Test
    fun rejectsAnUnverifiedScreenshotSource() {
        val error = assertFailsWith<Html5TestSiteException> {
            Html5TestSiteValidator.validate(sampleReport().copy(screenshotSource = "awt-desktop"))
        }

        assertEquals("html5test.report-invalid", error.code)
    }

    @Test
    fun rejectsAResponseThatDidNotLoadTheCanonicalHttpsPage() {
        val error = assertFailsWith<Html5TestSiteException> {
            Html5TestSiteValidator.validate(sampleReport().copy(finalUrl = "https://html5test.com/index.html"))
        }
        assertEquals("html5test.report-invalid", error.code)
    }

    @Test
    fun rejectsAHiddenNetworkFailure() {
        val failure = Html5TestSiteEventEvidence(6, "load-failed", Html5TestSiteContract.URL, -105)
        val error = assertFailsWith<Html5TestSiteException> {
            Html5TestSiteValidator.validate(sampleReport().copy(events = sampleReport().events + failure))
        }
        assertEquals("html5test.report-invalid", error.code)
    }

    @Test
    fun requiresNativeChildAndCdpCapabilities() {
        requireHtml5TestCapabilities(setOf(KWebCapability.NATIVE_CHILD, KWebCapability.CDP))

        val error = assertFailsWith<Html5TestSiteException> {
            requireHtml5TestCapabilities(setOf(KWebCapability.CDP))
        }

        assertEquals("html5test.capability-missing", error.code)
    }

    @Test
    fun parsesARealPngScreenshotPayload() {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                image.setRGB(x, y, (0xFF shl 24) or (x * 16 shl 16) or (y * 16 shl 8) or (x xor y))
            }
        }
        val bytes = pngBytes(image)
        val parsed = Html5TestScreenshotParser.parse(
            buildJsonObject { put("data", Base64.getEncoder().encodeToString(bytes)) },
        )

        assertEquals(image.width, parsed.image.width)
        assertEquals(image.height, parsed.image.height)
        assertEquals(bytes.toList(), parsed.bytes.toList())
    }

    @Test
    fun rejectsInvalidScreenshotPayloads() {
        val invalidBase64 = assertFailsWith<Html5TestSiteException> {
            Html5TestScreenshotParser.parse(buildJsonObject { put("data", "not-base64") })
        }
        val invalidPng = assertFailsWith<Html5TestSiteException> {
            Html5TestScreenshotParser.parse(
                buildJsonObject { put("data", Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))) },
            )
        }
        val blankPng = assertFailsWith<Html5TestSiteException> {
            val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
            Html5TestScreenshotParser.parse(
                buildJsonObject { put("data", Base64.getEncoder().encodeToString(pngBytes(image))) },
            )
        }

        assertEquals("html5test.screenshot-data-invalid", invalidBase64.code)
        assertEquals("html5test.screenshot-png-invalid", invalidPng.code)
        assertEquals("html5test.screenshot-content-invalid", blankPng.code)
    }

    private fun sampleReport(): Html5TestSiteReport = Html5TestSiteReport(
        schemaVersion = 1,
        requestedUrl = Html5TestSiteContract.URL,
        finalUrl = Html5TestSiteContract.URL,
        title = Html5TestSiteContract.TITLE,
        score = 523,
        maxScore = 555,
        scoreText = "Your browser scores 523 out of 555 points",
        readyState = "complete",
        secureContext = true,
        archivedTestNoticePresent = true,
        collectedAtEpochMs = 1L,
        runtimeSha256 = "a".repeat(64),
        chromiumProduct = "Chrome/151.0.0.0",
        protocolVersion = "1.3",
        revision = "@revision",
        javaScriptVersion = "15.1",
        userAgent = "Mozilla/5.0 Chrome/151.0.0.0 Safari/537.36",
        platform = "macOS",
        architecture = "arm64",
        displayScale = 2.0,
        screenshotSource = "cdp-page-target",
        screenshotFile = "html5test.png",
        screenshotSha256 = "b".repeat(64),
        screenshotWidth = 1280,
        screenshotHeight = 900,
        events = listOf(
            Html5TestSiteEventEvidence(1, "created", "", 0),
            Html5TestSiteEventEvidence(2, "navigation-started", Html5TestSiteContract.URL, 0),
            Html5TestSiteEventEvidence(3, "address-changed", Html5TestSiteContract.URL, 0),
            Html5TestSiteEventEvidence(4, "load-ended", Html5TestSiteContract.URL, 200),
            Html5TestSiteEventEvidence(5, "title-changed", Html5TestSiteContract.TITLE, 0),
        ),
    )

    private fun pngBytes(image: BufferedImage): ByteArray = ByteArrayOutputStream().use { output ->
        check(ImageIO.write(image, "png", output))
        output.toByteArray()
    }
}
