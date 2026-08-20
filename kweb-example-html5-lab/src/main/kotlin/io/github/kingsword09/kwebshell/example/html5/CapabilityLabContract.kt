package io.github.kingsword09.kwebshell.example.html5

import io.github.kingsword09.kwebshell.core.KWebPageEventType
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
public enum class ProbeRequirement {
    REQUIRED,
    OPTIONAL,
    DIAGNOSTIC,
}

@Serializable
public enum class ProbeStatus {
    PASS,
    UNAVAILABLE,
    FAIL,
}

@Serializable
public enum class ProbeSource {
    ENGINE,
    HOST_POLICY,
}

@Serializable
public data class CapabilityProbeDefinition(
    public val id: String,
    public val category: String,
    public val requirement: ProbeRequirement,
    public val source: ProbeSource,
)

@Serializable
public data class CapabilityProbeResult(
    public val id: String,
    public val category: String,
    public val requirement: ProbeRequirement,
    public val source: ProbeSource,
    public val status: ProbeStatus,
    public val startedAtMs: Long,
    public val endedAtMs: Long,
    public val reason: String = "",
    public val evidence: Map<String, String> = emptyMap(),
)

@Serializable
public data class CapabilityPageReport(
    public val schemaVersion: Int,
    public val phase: String,
    public val reportId: String,
    public val origin: String,
    public val generatedAtMs: Long,
    public val userAgent: String,
    public val secureContext: Boolean,
    public val probes: List<CapabilityProbeResult>,
)

@Serializable
public data class CapabilityLabMetadata(
    public val runtimeSha256: String,
    public val chromiumProduct: String,
    public val protocolVersion: String,
    public val revision: String,
    public val javaScriptVersion: String,
    public val platform: String,
    public val architecture: String,
    public val displayScale: Double,
    public val hostPolicy: String,
)

@Serializable
public data class CapabilityLabRun(
    public val phase: String,
    public val page: CapabilityPageReport,
    public val host: CapabilityPhaseHostEvidence,
)

@Serializable
public data class CapabilityPhaseHostEvidence(
    public val schemaVersion: Int,
    public val phase: String,
    public val screenshotFile: String,
    public val displayScale: Double,
    public val chromiumProduct: String,
    public val protocolVersion: String,
    public val revision: String,
    public val javaScriptVersion: String,
    public val accessibilityRole: String,
    public val accessibilityName: String,
    public val eventSequences: List<Long>,
    public val eventTypes: List<String>,
)

@Serializable
public data class CapabilityLabBundle(
    public val schemaVersion: Int,
    public val metadata: CapabilityLabMetadata,
    public val runs: List<CapabilityLabRun>,
)

public class CapabilityLabException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public object CapabilityLabManifest {
    public const val SCHEMA_VERSION: Int = 1

    public val definitions: List<CapabilityProbeDefinition> = listOf(
        definition("language.es-modules", "language", ProbeRequirement.REQUIRED),
        definition("language.dynamic-import", "language", ProbeRequirement.REQUIRED),
        definition("language.top-level-await", "language", ProbeRequirement.REQUIRED),
        definition("language.webassembly", "language", ProbeRequirement.REQUIRED),
        definition("language.webassembly-simd", "language", ProbeRequirement.OPTIONAL),
        definition("language.webassembly-threads", "language", ProbeRequirement.OPTIONAL),
        definition("dom.css-layout", "dom", ProbeRequirement.REQUIRED),
        definition("dom.fonts", "dom", ProbeRequirement.REQUIRED),
        definition("dom.observers", "dom", ProbeRequirement.REQUIRED),
        definition("dom.shadow-dom", "dom", ProbeRequirement.REQUIRED),
        definition("dom.input-events", "dom", ProbeRequirement.REQUIRED),
        definition("graphics.canvas-2d", "graphics", ProbeRequirement.REQUIRED),
        definition("graphics.webgl2", "graphics", ProbeRequirement.OPTIONAL),
        definition("graphics.webgpu", "graphics", ProbeRequirement.OPTIONAL),
        definition("network.fetch-streams", "network", ProbeRequirement.REQUIRED),
        definition("network.webcrypto", "network", ProbeRequirement.REQUIRED),
        definition("network.websocket", "network", ProbeRequirement.REQUIRED),
        definition("network.blob-url", "network", ProbeRequirement.REQUIRED),
        definition("network.cors", "network", ProbeRequirement.REQUIRED),
        definition("network.compression-streams", "network", ProbeRequirement.OPTIONAL),
        definition("storage.local-storage", "storage", ProbeRequirement.REQUIRED),
        definition("storage.cookies", "storage", ProbeRequirement.REQUIRED),
        definition("storage.indexed-db", "storage", ProbeRequirement.REQUIRED),
        definition("storage.cache-storage", "storage", ProbeRequirement.OPTIONAL),
        definition("workers.dedicated", "workers", ProbeRequirement.REQUIRED),
        definition("workers.shared", "workers", ProbeRequirement.OPTIONAL),
        definition("workers.service-worker", "workers", ProbeRequirement.REQUIRED),
        definition("media.web-codecs", "media", ProbeRequirement.OPTIONAL),
        definition("media.media-source", "media", ProbeRequirement.OPTIONAL),
        definition("media.encrypted-media", "media", ProbeRequirement.OPTIONAL),
        definition("media.audio-element", "media", ProbeRequirement.OPTIONAL),
        definition("policy.permissions", "policy", ProbeRequirement.OPTIONAL, ProbeSource.HOST_POLICY),
        definition("policy.clipboard", "policy", ProbeRequirement.DIAGNOSTIC, ProbeSource.HOST_POLICY),
        definition("policy.fullscreen", "policy", ProbeRequirement.DIAGNOSTIC, ProbeSource.HOST_POLICY),
        definition("policy.drag-drop", "policy", ProbeRequirement.DIAGNOSTIC, ProbeSource.HOST_POLICY),
        definition("policy.file-selection", "policy", ProbeRequirement.DIAGNOSTIC, ProbeSource.HOST_POLICY),
        definition("lifecycle.visibility", "lifecycle", ProbeRequirement.REQUIRED),
    )

    private fun definition(
        id: String,
        category: String,
        requirement: ProbeRequirement,
        source: ProbeSource = ProbeSource.ENGINE,
    ): CapabilityProbeDefinition = CapabilityProbeDefinition(id, category, requirement, source)
}

public object CapabilityLabValidator {
    public fun validatePage(
        report: CapabilityPageReport,
        expectedOrigin: String,
        expectedPhase: String? = null,
    ) {
        if (report.schemaVersion != CapabilityLabManifest.SCHEMA_VERSION) {
            invalid("schema-version", "Unsupported report schema ${report.schemaVersion}.")
        }
        if (report.phase.isBlank() || (expectedPhase != null && report.phase != expectedPhase)) {
            invalid("phase", "Unexpected report phase '${report.phase}'.")
        }
        val normalizedReportId = try {
            UUID.fromString(report.reportId).toString()
        } catch (error: IllegalArgumentException) {
            invalid("report-id", "The page report id is not a canonical UUID: ${error.message}.")
        }
        if (normalizedReportId != report.reportId) {
            invalid("report-id", "The page report id is not in canonical UUID form.")
        }
        if (report.origin != expectedOrigin) {
            invalid("origin", "Expected origin '$expectedOrigin', got '${report.origin}'.")
        }
        if (report.generatedAtMs <= 0L || report.userAgent.isBlank() || !report.secureContext) {
            invalid("page-metadata", "The page report lacks valid timestamp, user-agent, or secure-context evidence.")
        }

        val lockedDefinitions = CapabilityLabManifest.definitions
        val definitions = lockedDefinitions.associateBy { it.id }
        if (report.probes.size != definitions.size) {
            invalid("probe-count", "Expected ${definitions.size} probes, got ${report.probes.size}.")
        }
        if (report.probes.map { it.id } != lockedDefinitions.map { it.id }) {
            invalid("probe-order", "The report probe order does not match the locked manifest.")
        }
        val seen = mutableSetOf<String>()
        report.probes.forEach { result ->
            if (!seen.add(result.id)) {
                invalid("probe-duplicate", "Probe '${result.id}' appears more than once.")
            }
            val definition = definitions[result.id]
                ?: invalid("probe-unknown", "Probe '${result.id}' is not in the locked manifest.")
            if (result.category != definition.category ||
                result.requirement != definition.requirement ||
                result.source != definition.source
            ) {
                invalid("probe-definition", "Probe '${result.id}' does not match the locked manifest.")
            }
            if (result.startedAtMs <= 0L || result.endedAtMs < result.startedAtMs) {
                invalid("probe-timing", "Probe '${result.id}' has invalid timestamps.")
            }
            if (result.endedAtMs > report.generatedAtMs) {
                invalid("probe-timing", "Probe '${result.id}' ended after the report generation timestamp.")
            }
            if (result.evidence.isEmpty() || result.evidence.any { (key, value) -> key.isBlank() || value.isBlank() }) {
                invalid("probe-evidence", "Probe '${result.id}' has empty evidence.")
            }
            when (result.status) {
                ProbeStatus.PASS -> if (result.reason.isNotEmpty()) {
                    invalid("probe-reason", "Passing probe '${result.id}' has a failure reason.")
                }
                ProbeStatus.UNAVAILABLE,
                ProbeStatus.FAIL,
                -> if (result.reason.isBlank()) {
                    invalid("probe-reason", "Probe '${result.id}' has no reason for ${result.status}.")
                }
            }
            if (result.status == ProbeStatus.FAIL) {
                invalid("probe-failed", "Probe '${result.id}' failed: ${result.reason}.")
            }
            if (result.requirement == ProbeRequirement.REQUIRED && result.status != ProbeStatus.PASS) {
                invalid("required-probe-failed", "Required probe '${result.id}' is ${result.status}.")
            }
        }
        if (seen != definitions.keys) {
            invalid("probe-set", "The report does not contain exactly the locked probe set.")
        }
    }

    public fun validateBundle(bundle: CapabilityLabBundle, expectedOrigin: String) {
        if (bundle.schemaVersion != CapabilityLabManifest.SCHEMA_VERSION) {
            invalid("bundle-schema-version", "Unsupported bundle schema ${bundle.schemaVersion}.")
        }
        if (bundle.runs.size != 2 || bundle.runs.map { it.phase }.toSet() != setOf("cold", "warm")) {
            invalid("bundle-phases", "The bundle must contain exactly cold and warm runs.")
        }
        if (bundle.metadata.runtimeSha256.length != 64 ||
            bundle.metadata.runtimeSha256.any { it !in "0123456789abcdef" }
        ) {
            invalid("runtime-digest", "The bundle runtime digest is not a lowercase SHA-256 value.")
        }
        if (bundle.metadata.platform.isBlank() || bundle.metadata.architecture.isBlank() ||
            bundle.metadata.displayScale <= 0.0 || bundle.metadata.hostPolicy.isBlank() ||
            bundle.metadata.chromiumProduct.isBlank() || bundle.metadata.protocolVersion.isBlank() ||
            bundle.metadata.revision.isBlank() || bundle.metadata.javaScriptVersion.isBlank()
        ) {
            invalid("host-metadata", "The bundle host metadata is incomplete.")
        }
        bundle.runs.forEach { run ->
            val host = run.host
            if (host.schemaVersion != CapabilityLabManifest.SCHEMA_VERSION || host.phase != run.phase) {
                invalid("host-evidence-phase", "Run '${run.phase}' has mismatched host evidence.")
            }
            if (host.screenshotFile.isBlank()) {
                invalid("screenshot-file", "Run '${run.phase}' has no screenshot artifact name.")
            }
            if (host.chromiumProduct != bundle.metadata.chromiumProduct ||
                host.protocolVersion != bundle.metadata.protocolVersion ||
                host.revision != bundle.metadata.revision ||
                host.javaScriptVersion != bundle.metadata.javaScriptVersion ||
                host.accessibilityRole != "region" ||
                host.accessibilityName != "KWebShell capability evidence"
            ) {
                invalid("host-cdp-evidence", "Run '${run.phase}' has invalid CDP/accessibility evidence.")
            }
            if (host.eventSequences.isEmpty() || host.eventTypes.size != host.eventSequences.size ||
                host.eventSequences != (1L..host.eventSequences.last()).toList() ||
                host.eventTypes.first() != "created" || host.eventTypes.last() != "title-changed"
            ) {
                invalid("host-event-evidence", "Run '${run.phase}' has invalid public event-flow evidence.")
            }
            val requiredEventTypes = setOf(
                KWebPageEventType.CREATED.id,
                KWebPageEventType.NAVIGATION_STARTED.id,
                KWebPageEventType.ADDRESS_CHANGED.id,
                KWebPageEventType.LOAD_ENDED.id,
                KWebPageEventType.TITLE_CHANGED.id,
            )
            if (!host.eventTypes.containsAll(requiredEventTypes) ||
                host.eventTypes.any { type -> type !in KWebPageEventType.entries.map { it.id } }
            ) {
                invalid("host-event-evidence", "Run '${run.phase}' omitted or invented public event types.")
            }
            val productMajor = bundle.metadata.chromiumProduct
                .removePrefix("Chrome/")
                .substringBefore('.')
            if (productMajor.isBlank() || !run.page.userAgent.contains("Chrome/$productMajor.")) {
                invalid("browser-identity", "Run '${run.phase}' page and CDP Chromium identities disagree.")
            }
            validatePage(run.page, expectedOrigin, run.phase)
        }
        val cold = bundle.runs.single { it.phase == "cold" }.page
        val warm = bundle.runs.single { it.phase == "warm" }.page
        val coldPersistence = cold.probes.single { it.id == "storage.local-storage" }.evidence
        val warmPersistence = warm.probes.single { it.id == "storage.local-storage" }.evidence
        if (coldPersistence["previousValue"] != "<absent>") {
            invalid("cold-persistence", "Cold localStorage did not start absent.")
        }
        if (warmPersistence["previousValue"] != "kwebshell-capability-lab-v1") {
            invalid("warm-persistence", "Warm localStorage did not observe the cold-run marker.")
        }
        val warmIndexedDb = warm.probes.single { it.id == "storage.indexed-db" }.evidence
        if (warmIndexedDb["previousValue"] != "kwebshell-capability-lab-v1") {
            invalid("warm-indexed-db", "Warm IndexedDB did not observe the cold-run marker.")
        }
        val warmCookie = warm.probes.single { it.id == "storage.cookies" }.evidence
        if (warmCookie["previousValue"] != "present") {
            invalid("warm-cookie", "Warm run did not observe the cold-run persistent cookie.")
        }
        val warmServiceWorker = warm.probes.single { it.id == "workers.service-worker" }.evidence
        if (warmServiceWorker["registration"] != "active") {
            invalid("warm-service-worker", "Warm run did not observe an active Service Worker registration.")
        }
    }

    private fun invalid(code: String, message: String): Nothing =
        throw CapabilityLabException(code, message)
}
