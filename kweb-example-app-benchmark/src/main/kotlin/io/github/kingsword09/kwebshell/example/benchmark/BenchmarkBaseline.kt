package io.github.kingsword09.kwebshell.example.benchmark

import kotlinx.serialization.decodeFromString
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal object BenchmarkBaselineVerifier {
    fun load(
        path: Path,
        runtimeSha256: String,
        workloadAggregateSha256: String,
        platform: String,
        architecture: String,
        machineClass: String,
    ): BenchmarkBaseline {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw BenchmarkException("baseline.catalog-missing", "Benchmark baseline catalog '$normalized' is missing.")
        }
        val catalog = try {
            BenchmarkJson.format.decodeFromString<BenchmarkBaselineCatalog>(Files.readString(normalized))
        } catch (error: Throwable) {
            throw BenchmarkException("baseline.catalog-invalid", "Benchmark baseline catalog '$normalized' is invalid.", error)
        }
        if (catalog.schemaVersion != BenchmarkMetricCatalog.SCHEMA_VERSION || catalog.baselines.isEmpty()) {
            throw BenchmarkException("baseline.catalog-schema", "Benchmark baseline catalog has an invalid schema or no entries.")
        }
        val matchingMachine = catalog.baselines.filter {
            it.platform == platform && it.architecture == architecture && it.machineClass == machineClass
        }
        if (matchingMachine.isEmpty()) {
            throw BenchmarkException(
                "baseline.machine-not-found",
                "No baseline exists for $platform/$architecture machine class '$machineClass'.",
            )
        }
        val matchingWorkload = matchingMachine.filter { it.workloadAggregateSha256 == workloadAggregateSha256 }
        if (matchingWorkload.isEmpty()) {
            throw BenchmarkException(
                "baseline.workload-not-found",
                "No baseline for $platform/$architecture '$machineClass' matches workload '$workloadAggregateSha256'.",
            )
        }
        val matchingRuntime = matchingWorkload.filter { it.runtimeSha256 == runtimeSha256 }
        if (matchingRuntime.size != 1) {
            throw BenchmarkException(
                if (matchingRuntime.isEmpty()) "baseline.runtime-not-found" else "baseline.identity-duplicate",
                "Expected one baseline for runtime '$runtimeSha256', found ${matchingRuntime.size}.",
            )
        }
        return matchingRuntime.single().also(::validate)
    }

    fun verify(report: BenchmarkReport, baseline: BenchmarkBaseline) {
        val metadata = report.metadata
        if (metadata.runtimeSha256 != baseline.runtimeSha256 ||
            metadata.workloadAggregateSha256 != baseline.workloadAggregateSha256 ||
            metadata.platform != baseline.platform || metadata.architecture != baseline.architecture ||
            metadata.machineClass != baseline.machineClass
        ) {
            throw BenchmarkException("baseline.report-identity", "The report identity does not match the selected baseline.")
        }
        val phases = report.phases.associateBy(BenchmarkPhaseSummary::phase)
        baseline.thresholds.forEach { threshold ->
            val phase = phases[threshold.phase]
                ?: throw BenchmarkException("baseline.phase-missing", "Report phase '${threshold.phase}' is missing.")
            val summary = phase.metrics[threshold.metric] ?: phase.optionalMetrics[threshold.metric]
                ?: throw BenchmarkException("baseline.metric-missing", "Report metric '${threshold.metric}' is missing.")
            val value = when (threshold.statistic) {
                "median" -> summary.median
                "p95" -> summary.p95
                "worst" -> summary.worst
                else -> throw BenchmarkException("baseline.statistic-invalid", "Unknown baseline statistic '${threshold.statistic}'.")
            }
            val passed = when (threshold.direction) {
                "maximum" -> value <= threshold.limit
                "minimum" -> value >= threshold.limit
                else -> throw BenchmarkException("baseline.direction-invalid", "Unknown baseline direction '${threshold.direction}'.")
            }
            if (!passed) {
                throw BenchmarkException(
                    "baseline.regression",
                    "${threshold.phase}/${threshold.metric}/${threshold.statistic} was $value; " +
                        "${threshold.direction} is ${threshold.limit}.",
                )
            }
        }
    }

    private fun validate(baseline: BenchmarkBaseline) {
        val identities = listOf(
            baseline.runtimeSha256,
            baseline.workloadAggregateSha256,
            baseline.platform,
            baseline.architecture,
            baseline.machineClass,
        )
        if (identities.any(String::isBlank) || baseline.thresholds.isEmpty()) {
            throw BenchmarkException("baseline.entry-invalid", "Benchmark baseline identity or thresholds are incomplete.")
        }
        val keys = mutableSetOf<String>()
        baseline.thresholds.forEach { threshold ->
            val key = "${threshold.phase}/${threshold.metric}/${threshold.statistic}"
            if (!keys.add(key) || threshold.phase !in setOf("cold", "warm") ||
                threshold.metric !in BenchmarkMetricCatalog.requiredPageMetrics + BenchmarkMetricCatalog.requiredHostMetrics ||
                threshold.statistic !in setOf("median", "p95", "worst") ||
                threshold.direction !in setOf("maximum", "minimum") ||
                !threshold.limit.isFinite() || threshold.limit < 0.0
            ) {
                throw BenchmarkException("baseline.threshold-invalid", "Benchmark threshold '$key' is invalid or duplicated.")
            }
        }
    }
}
