package io.github.kingsword09.kwebshell.example.benchmark

import java.util.UUID
import kotlin.math.ceil

public object BenchmarkMetricCatalog {
    public const val SCHEMA_VERSION: Int = 1

    public val requiredPageMetrics: Set<String> = linkedSetOf(
        "page.ready.ms",
        "navigation.fcp.ms",
        "navigation.lcp.ms",
        "layout.cls.ratio",
        "long-task.count",
        "long-task.duration.ms",
        "resource.count",
        "resource.transfer.bytes",
        "resource.decoded.bytes",
        "scenario.duration.ms",
        "route.transition.count",
        "history.back-forward.count",
        "markdown.node.count",
        "code.token.count",
        "virtual-list.total-row.count",
        "virtual-list.max-dom-row.count",
        "websocket.message.count",
        "websocket.payload.bytes",
        "worker.message.count",
        "indexeddb.preexisting.record.count",
        "indexeddb.record.count",
        "decode.image.ms",
        "decode.font.ms",
        "decode.audio.ms",
        "raf.frame.count",
        "raf.interval.median.ms",
        "raf.interval.p95.ms",
        "raf.interval.worst.ms",
    )

    public val optionalPageMetrics: Set<String> = setOf("interaction.inp.ms")

    public val requiredHostMetrics: Set<String> = linkedSetOf(
        "host.engine-startup.ms",
        "host.page-usable.ms",
        "host.cdp-command.median.ms",
        "host.cdp-command.p95.ms",
        "host.cdp-command.worst.ms",
        "host.devtools-open.ms",
        "host.devtools-close.ms",
        "host.public-event-address.ms",
        "host.process.resident.bytes",
        "host.process.private.bytes",
        "host.process.cpu.ms",
        "host.process.thread.count",
        "host.native-frame.count",
        "host.native-frame.interval.median.ms",
        "host.native-frame.interval.p95.ms",
        "host.native-frame.interval.worst.ms",
        "host.profile.before.bytes",
        "host.profile.after.bytes",
        "host.profile.delta.bytes",
        "host.shutdown.ms",
        "host.display.scale",
        "cdp.js-heap-used.bytes",
        "cdp.dom-node.count",
        "cdp.document.count",
        "cdp.layout.count",
        "cdp.recalc-style.count",
        "cdp.task-duration.ms",
    )

    public fun unit(metric: String): String = when {
        metric.endsWith(".ms") -> "ms"
        metric.endsWith(".bytes") -> "bytes"
        metric.endsWith(".count") -> "count"
        metric.endsWith(".ratio") -> "ratio"
        metric.endsWith(".scale") -> "scale"
        else -> throw BenchmarkException("metric.unit-unknown", "Metric '$metric' has no declared unit.")
    }
}

public object BenchmarkValidator {
    public fun validateWorkloadLock(lock: BenchmarkWorkloadLock) {
        if (lock.schemaVersion != BenchmarkMetricCatalog.SCHEMA_VERSION) invalid("workload.schema", "Invalid workload schema.")
        if (lock.name != "KWebShell LobeHub-class workload fixture" ||
            lock.classification != "repository-local-synthetic-workload" ||
            lock.source != "kweb-example-app-benchmark/src/main/resources/workload" ||
            lock.revision.isBlank() || lock.licenseNotice.isBlank() || lock.entryPoint != "index.html"
        ) {
            invalid("workload.identity", "The workload identity or provenance is incomplete.")
        }
        if (!SHA256.matches(lock.aggregateSha256)) invalid("workload.aggregate-digest", "Invalid aggregate SHA-256.")
        if (lock.files.isEmpty() || lock.files.map { it.path } != lock.files.map { it.path }.sorted()) {
            invalid("workload.file-order", "Workload files must be non-empty and sorted by path.")
        }
        val seen = mutableSetOf<String>()
        lock.files.forEach { file ->
            if (!seen.add(file.path) || !SAFE_PATH.matches(file.path) || file.path.contains("..")) {
                invalid("workload.file-path", "Unsafe or duplicate workload path '${file.path}'.")
            }
            if (file.size <= 0L || !SHA256.matches(file.sha256)) {
                invalid("workload.file-metadata", "Invalid metadata for workload file '${file.path}'.")
            }
        }
        if (lock.entryPoint !in seen) invalid("workload.entry-point", "The workload entry point is not locked.")
    }

    public fun validatePage(observation: BenchmarkPageObservation, phase: String, scenarioId: String) {
        if (observation.schemaVersion != BenchmarkMetricCatalog.SCHEMA_VERSION ||
            observation.phase != phase || observation.scenarioId != scenarioId ||
            observation.startedAtMs <= 0.0 || observation.endedAtMs < observation.startedAtMs
        ) {
            invalid("page.identity", "The page observation identity or timing is invalid.")
        }
        validateExactMetrics("page", observation.metrics, BenchmarkMetricCatalog.requiredPageMetrics)
        val optionalNames = observation.optionalMetrics.keys
        val unavailableNames = observation.unavailableMetrics.keys
        if (optionalNames intersect unavailableNames != emptySet<String>() ||
            optionalNames + unavailableNames != BenchmarkMetricCatalog.optionalPageMetrics
        ) {
            invalid("page.optional-metrics", "Every optional metric must be measured or explicitly unavailable.")
        }
        validateValues("page.optional", observation.optionalMetrics)
        if (observation.unavailableMetrics.any { (name, reason) -> name.isBlank() || reason.isBlank() }) {
            invalid("page.unavailable-reason", "Unavailable page metrics require non-empty reasons.")
        }
        val requiredEvidence = setOf(
            "gpuRenderer", "gpuVendor", "webSocketProtocol", "workerType", "indexedDbDatabase",
            "routeFinal", "fontFamily", "imageNaturalSize", "audioSampleRate", "phasePersistence",
        )
        if (!observation.evidence.keys.containsAll(requiredEvidence) ||
            observation.evidence.any { (key, value) -> key.isBlank() || value.isBlank() }
        ) {
            invalid("page.evidence", "The page observation lacks required evidence.")
        }
    }

    public fun validateRaw(sample: BenchmarkRawSample) {
        if (sample.schemaVersion != BenchmarkMetricCatalog.SCHEMA_VERSION ||
            sample.pairIndex < 0 || sample.phase !in PHASES || sample.profileName.isBlank() ||
            sample.startedAtEpochMs <= 0L || sample.endedAtEpochMs < sample.startedAtEpochMs
        ) {
            invalid("sample.identity", "The raw sample identity or timing is invalid.")
        }
        try {
            if (UUID.fromString(sample.sampleId).toString() != sample.sampleId) {
                invalid("sample.id", "The raw sample id is not a canonical UUID.")
            }
        } catch (error: IllegalArgumentException) {
            invalid("sample.id", "The raw sample id is not a UUID: ${error.message}.")
        }
        listOf(
            sample.workloadAggregateSha256,
            sample.runtimeSha256,
        ).forEach { digest ->
            if (!SHA256.matches(digest)) invalid("sample.digest", "The raw sample contains an invalid SHA-256.")
        }
        if (sample.chromiumProduct.isBlank() || sample.protocolVersion.isBlank() || sample.revision.isBlank() ||
            sample.javaScriptVersion.isBlank() || sample.gpuVendor.isBlank() || sample.gpuRenderer.isBlank() ||
            sample.displayScale <= 0.0 || sample.platform.isBlank() || sample.architecture.isBlank() ||
            sample.machineClass.isBlank() || sample.benchmarkGitRevision.isBlank()
        ) {
            invalid("sample.metadata", "The raw sample metadata is incomplete.")
        }
        validatePage(sample.page, sample.phase, sample.sampleId)
        validateExactMetrics("host", sample.hostMetrics, BenchmarkMetricCatalog.requiredHostMetrics)
        if (sample.gpuVendor != sample.page.evidence["gpuVendor"] ||
            sample.gpuRenderer != sample.page.evidence["gpuRenderer"] ||
            sample.displayScale != sample.hostMetrics["host.display.scale"]
        ) {
            invalid("sample.display-metadata", "GPU identity or display scale does not match the raw observations.")
        }
        val before = sample.hostMetrics.getValue("host.profile.before.bytes")
        val after = sample.hostMetrics.getValue("host.profile.after.bytes")
        val delta = sample.hostMetrics.getValue("host.profile.delta.bytes")
        if (before < 0.0 || after < 0.0 || delta != after - before) {
            invalid("sample.profile-size", "Profile size metrics are inconsistent.")
        }
        if (sample.hostMetrics.filterKeys { it != "host.profile.delta.bytes" }.any { it.value < 0.0 }) {
            invalid("sample.host-negative", "A non-delta host metric is negative.")
        }
        if (sample.eventSequences.isEmpty() || sample.eventSequences.size != sample.eventTypes.size ||
            sample.eventSequences != (1L..sample.eventSequences.last()).toList() ||
            sample.eventTypes.first() != "created" || sample.eventTypes.last() != "closed"
        ) {
            invalid("sample.events", "Public page events are missing, non-contiguous, or not terminal.")
        }
        val requiredEvidence = setOf("lifecycle", "devTools", "cdpEndpoint", "profilePath")
        if (!sample.evidence.keys.containsAll(requiredEvidence) ||
            sample.evidence.any { (key, value) -> key.isBlank() || value.isBlank() }
        ) {
            invalid("sample.evidence", "The raw sample host evidence is incomplete.")
        }
    }

    public fun validateReport(report: BenchmarkReport) {
        if (report.schemaVersion != BenchmarkMetricCatalog.SCHEMA_VERSION ||
            report.metadata.warmupPairs != 1 || report.metadata.measuredPairs < 10 ||
            report.metadata.gpuVendor.isBlank() || report.metadata.gpuRenderer.isBlank() ||
            report.metadata.displayScale <= 0.0
        ) {
            invalid("report.identity", "The benchmark report does not describe the required sample plan.")
        }
        if (report.phases.map { it.phase } != PHASES || report.phases.any { it.sampleCount != report.metadata.measuredPairs }) {
            invalid("report.phases", "The report must contain complete cold and warm measured phases.")
        }
        val expectedMetrics = BenchmarkMetricCatalog.requiredPageMetrics + BenchmarkMetricCatalog.requiredHostMetrics
        report.phases.forEach { phase ->
            if (phase.metrics.keys != expectedMetrics || phase.metrics.values.any { summary ->
                    summary.sampleCount != phase.sampleCount || summary.unit.isBlank() ||
                        !summary.median.isFinite() || !summary.p95.isFinite() || !summary.worst.isFinite()
                }
            ) {
                invalid("report.metrics", "Phase '${phase.phase}' has incomplete metric aggregates.")
            }
            if (phase.optionalMetrics.keys + phase.unavailableMetrics.keys != BenchmarkMetricCatalog.optionalPageMetrics) {
                invalid("report.optional-metrics", "Phase '${phase.phase}' lost optional metric evidence.")
            }
            if (phase.unavailableMetrics.any { (_, reasons) -> reasons.size != phase.sampleCount || reasons.any(String::isBlank) }) {
                invalid("report.unavailable-metrics", "Unavailable metric reasons are incomplete.")
            }
        }
        val expectedRawCount = (report.metadata.warmupPairs + report.metadata.measuredPairs) * PHASES.size
        if (report.rawArtifacts.size != expectedRawCount || report.rawArtifacts.map { it.file }.toSet().size != expectedRawCount ||
            report.rawArtifacts.any { it.file.isBlank() || !SHA256.matches(it.sha256) }
        ) {
            invalid("report.raw-artifacts", "The raw artifact index is incomplete or invalid.")
        }
        if (report.screenshots.size != PHASES.size || report.screenshots.map { it.phase }.toSet() != PHASES.toSet() ||
            report.screenshots.any { it.file.isBlank() || !SHA256.matches(it.sha256) || it.sampleId.isBlank() || it.pairIndex < 0 }
        ) {
            invalid("report.screenshots", "The report must retain one valid screenshot for each phase.")
        }
    }

    private fun validateExactMetrics(scope: String, metrics: Map<String, Double>, expected: Set<String>) {
        if (metrics.keys != expected) invalid("$scope.metric-set", "The $scope metric set does not match the schema.")
        validateValues(scope, metrics)
    }

    private fun validateValues(scope: String, metrics: Map<String, Double>) {
        metrics.forEach { (name, value) ->
            if (name.isBlank() || !value.isFinite()) invalid("$scope.metric-value", "Metric '$name' is not finite.")
        }
    }

    private fun invalid(code: String, message: String): Nothing = throw BenchmarkException(code, message)

    private val PHASES = listOf("cold", "warm")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SAFE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]*")
}

internal fun percentile(values: List<Double>, percentile: Double): Double {
    require(values.isNotEmpty()) { "Percentiles require at least one value." }
    require(percentile in 0.0..1.0) { "The percentile must be between zero and one." }
    val sorted = values.sorted()
    val index = (ceil(percentile * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
    return sorted[index]
}
