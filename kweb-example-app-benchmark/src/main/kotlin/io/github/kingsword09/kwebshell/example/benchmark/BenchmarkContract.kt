package io.github.kingsword09.kwebshell.example.benchmark

import kotlinx.serialization.Serializable

@Serializable
public data class BenchmarkWorkloadFile(
    public val path: String,
    public val size: Long,
    public val sha256: String,
)

@Serializable
public data class BenchmarkWorkloadLock(
    public val schemaVersion: Int,
    public val name: String,
    public val classification: String,
    public val source: String,
    public val revision: String,
    public val licenseNotice: String,
    public val entryPoint: String,
    public val aggregateSha256: String,
    public val files: List<BenchmarkWorkloadFile>,
)

@Serializable
public data class BenchmarkPageObservation(
    public val schemaVersion: Int,
    public val scenarioId: String,
    public val phase: String,
    public val startedAtMs: Double,
    public val endedAtMs: Double,
    public val metrics: Map<String, Double>,
    public val optionalMetrics: Map<String, Double>,
    public val unavailableMetrics: Map<String, String>,
    public val evidence: Map<String, String>,
)

@Serializable
public data class BenchmarkRawSample(
    public val schemaVersion: Int,
    public val sampleId: String,
    public val pairIndex: Int,
    public val measured: Boolean,
    public val phase: String,
    public val profileName: String,
    public val startedAtEpochMs: Long,
    public val endedAtEpochMs: Long,
    public val workloadAggregateSha256: String,
    public val runtimeSha256: String,
    public val chromiumProduct: String,
    public val protocolVersion: String,
    public val revision: String,
    public val javaScriptVersion: String,
    public val gpuVendor: String,
    public val gpuRenderer: String,
    public val displayScale: Double,
    public val platform: String,
    public val architecture: String,
    public val machineClass: String,
    public val benchmarkGitRevision: String,
    public val page: BenchmarkPageObservation,
    public val hostMetrics: Map<String, Double>,
    public val eventSequences: List<Long>,
    public val eventTypes: List<String>,
    public val evidence: Map<String, String>,
)

@Serializable
public data class BenchmarkMetricSummary(
    public val unit: String,
    public val sampleCount: Int,
    public val median: Double,
    public val p95: Double,
    public val worst: Double,
)

@Serializable
public data class BenchmarkPhaseSummary(
    public val phase: String,
    public val sampleCount: Int,
    public val metrics: Map<String, BenchmarkMetricSummary>,
    public val optionalMetrics: Map<String, BenchmarkMetricSummary>,
    public val unavailableMetrics: Map<String, List<String>>,
)

@Serializable
public data class BenchmarkReportMetadata(
    public val workloadName: String,
    public val workloadRevision: String,
    public val workloadAggregateSha256: String,
    public val runtimeSha256: String,
    public val chromiumProduct: String,
    public val protocolVersion: String,
    public val chromiumRevision: String,
    public val javaScriptVersion: String,
    public val gpuVendor: String,
    public val gpuRenderer: String,
    public val displayScale: Double,
    public val platform: String,
    public val architecture: String,
    public val machineClass: String,
    public val benchmarkGitRevision: String,
    public val warmupPairs: Int,
    public val measuredPairs: Int,
)

@Serializable
public data class BenchmarkRawArtifact(
    public val file: String,
    public val sha256: String,
    public val sampleId: String,
    public val measured: Boolean,
    public val phase: String,
)

@Serializable
public data class BenchmarkScreenshotArtifact(
    public val file: String,
    public val sha256: String,
    public val sampleId: String,
    public val pairIndex: Int,
    public val phase: String,
)

@Serializable
public data class BenchmarkReport(
    public val schemaVersion: Int,
    public val metadata: BenchmarkReportMetadata,
    public val phases: List<BenchmarkPhaseSummary>,
    public val rawArtifacts: List<BenchmarkRawArtifact>,
    public val screenshots: List<BenchmarkScreenshotArtifact>,
)

@Serializable
public data class BenchmarkBaselineThreshold(
    public val phase: String,
    public val metric: String,
    public val statistic: String,
    public val direction: String,
    public val limit: Double,
)

@Serializable
public data class BenchmarkBaseline(
    public val runtimeSha256: String,
    public val workloadAggregateSha256: String,
    public val platform: String,
    public val architecture: String,
    public val machineClass: String,
    public val thresholds: List<BenchmarkBaselineThreshold>,
)

@Serializable
public data class BenchmarkBaselineCatalog(
    public val schemaVersion: Int,
    public val baselines: List<BenchmarkBaseline>,
)

public class BenchmarkException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
