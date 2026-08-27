package io.github.kingsword09.kwebshell.example.html5

import kotlinx.serialization.Serializable

@Serializable
public data class Html5TestSiteEventEvidence(
    public val sequence: Long,
    public val type: String,
    public val text: String,
    public val statusCode: Int,
)

@Serializable
public data class Html5TestSiteReport(
    public val schemaVersion: Int,
    public val requestedUrl: String,
    public val finalUrl: String,
    public val title: String,
    public val score: Int,
    public val maxScore: Int,
    public val scoreText: String,
    public val readyState: String,
    public val secureContext: Boolean,
    public val archivedTestNoticePresent: Boolean,
    public val collectedAtEpochMs: Long,
    public val runtimeSha256: String,
    public val chromiumProduct: String,
    public val protocolVersion: String,
    public val revision: String,
    public val javaScriptVersion: String,
    public val userAgent: String,
    public val platform: String,
    public val architecture: String,
    public val displayScale: Double,
    public val screenshotSource: String,
    public val screenshotFile: String,
    public val screenshotSha256: String,
    public val screenshotWidth: Int,
    public val screenshotHeight: Int,
    public val events: List<Html5TestSiteEventEvidence>,
)

public class Html5TestSiteException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public object Html5TestSiteContract {
    public const val SCHEMA_VERSION: Int = 1
    public const val URL: String = "https://html5test.com/"
    public const val TITLE: String = "HTML5test - How well does your browser support HTML5?"
    public const val SCREENSHOT_SOURCE: String = "cdp-page-target"
    public const val SCREENSHOT_FILE: String = "html5test.png"
    public const val REPORT_FILE: String = "html5test-report.json"
}

internal object Html5TestSiteValidator {
    private val digestPattern = Regex("[0-9a-f]{64}")
    private val scoreTextPattern = Regex(
        "^Your browser scores\\s+(\\d+)\\s+out of\\s+(\\d+)\\s+points$",
        RegexOption.IGNORE_CASE,
    )

    fun validate(report: Html5TestSiteReport) {
        if (report.schemaVersion != Html5TestSiteContract.SCHEMA_VERSION) invalid("schema-version")
        if (report.requestedUrl != Html5TestSiteContract.URL) invalid("requested-url")
        if (report.finalUrl != Html5TestSiteContract.URL) invalid("final-url")
        if (report.title != Html5TestSiteContract.TITLE) invalid("title")
        if (report.readyState != "complete") invalid("ready-state")
        if (!report.secureContext) invalid("secure-context")
        if (report.maxScore <= 0 || report.score !in 0..report.maxScore) invalid("score-range")
        val scoreMatch = scoreTextPattern.matchEntire(report.scoreText)
            ?: invalid("score-text")
        val parsedScore = scoreMatch.groupValues[1].toIntOrNull() ?: invalid("score-text-values")
        val parsedMaxScore = scoreMatch.groupValues[2].toIntOrNull() ?: invalid("score-text-values")
        if (parsedScore != report.score || parsedMaxScore != report.maxScore) {
            invalid("score-text-values")
        }
        if (report.collectedAtEpochMs <= 0L) invalid("collected-at")
        if (!digestPattern.matches(report.runtimeSha256)) invalid("runtime-digest")
        if (report.chromiumProduct.isBlank() || report.protocolVersion.isBlank() ||
            report.revision.isBlank() || report.javaScriptVersion.isBlank() || report.userAgent.isBlank()
        ) {
            invalid("browser-identity")
        }
        if (report.platform.isBlank() || report.architecture.isBlank()) invalid("host-identity")
        if (report.displayScale <= 0.0) invalid("display-scale")
        if (report.screenshotSource != Html5TestSiteContract.SCREENSHOT_SOURCE) invalid("screenshot-source")
        if (report.screenshotFile != Html5TestSiteContract.SCREENSHOT_FILE) invalid("screenshot-file")
        if (!digestPattern.matches(report.screenshotSha256)) invalid("screenshot-digest")
        if (report.screenshotWidth <= 0 || report.screenshotHeight <= 0) invalid("screenshot-size")
        validateEvents(report.events)
    }

    private fun validateEvents(events: List<Html5TestSiteEventEvidence>) {
        if (events.isEmpty() || events.first().type != "created") invalid("events-empty")
        if (events.map { it.sequence } != (1L..events.last().sequence).toList()) invalid("event-sequence")
        if (events.any { it.type == "load-failed" || it.type == "fatal-error" }) invalid("event-failure")
        if (events.none { it.type == "address-changed" && it.text == Html5TestSiteContract.URL }) {
            invalid("address-event")
        }
        if (events.none {
                it.type == "load-ended" && it.text == Html5TestSiteContract.URL && it.statusCode == 200
            }
        ) {
            invalid("load-event")
        }
        if (events.none { it.type == "title-changed" && it.text == Html5TestSiteContract.TITLE }) {
            invalid("title-event")
        }
    }

    private fun invalid(field: String): Nothing = throw Html5TestSiteException(
        "html5test.report-invalid",
        "The live HTML5test report has invalid '$field' evidence.",
    )
}
