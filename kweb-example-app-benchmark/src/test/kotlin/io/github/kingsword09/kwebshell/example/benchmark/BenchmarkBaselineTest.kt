package io.github.kingsword09.kwebshell.example.benchmark

import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchmarkBaselineTest {
    @Test
    fun selectsTheExactlyMatchingRuntimeWorkloadAndMachine() {
        val baseline = baseline()
        val path = writeCatalog(listOf(baseline))
        val selected = BenchmarkBaselineVerifier.load(
            path,
            baseline.runtimeSha256,
            baseline.workloadAggregateSha256,
            baseline.platform,
            baseline.architecture,
            baseline.machineClass,
        )
        assertEquals(baseline, selected)
    }

    @Test
    fun rejectsAnOldBaselineWhenTheRuntimeDigestChanges() {
        val baseline = baseline()
        val error = assertFailsWith<BenchmarkException> {
            BenchmarkBaselineVerifier.load(
                writeCatalog(listOf(baseline)),
                "f".repeat(64),
                baseline.workloadAggregateSha256,
                baseline.platform,
                baseline.architecture,
                baseline.machineClass,
            )
        }
        assertEquals("baseline.runtime-not-found", error.code)
    }

    @Test
    fun rejectsAReportThatCrossesARegressionThreshold() {
        val report = BenchmarkTestFixtures.report()
        val threshold = BenchmarkBaselineThreshold(
            phase = "cold",
            metric = "host.page-usable.ms",
            statistic = "worst",
            direction = "maximum",
            limit = 0.5,
        )
        val error = assertFailsWith<BenchmarkException> {
            BenchmarkBaselineVerifier.verify(report, baseline(threshold))
        }
        assertEquals("baseline.regression", error.code)
    }

    private fun baseline(
        threshold: BenchmarkBaselineThreshold = BenchmarkBaselineThreshold(
            phase = "cold",
            metric = "host.page-usable.ms",
            statistic = "worst",
            direction = "maximum",
            limit = 2.0,
        ),
    ): BenchmarkBaseline = BenchmarkBaseline(
        runtimeSha256 = BenchmarkTestFixtures.RUNTIME_SHA,
        workloadAggregateSha256 = BenchmarkTestFixtures.WORKLOAD_SHA,
        platform = "macos",
        architecture = "arm64",
        machineClass = "fixture-machine",
        thresholds = listOf(threshold),
    )

    private fun writeCatalog(baselines: List<BenchmarkBaseline>) = Files.createTempFile("kwebshell-baseline", ".json").also { path ->
        Files.writeString(
            path,
            BenchmarkJson.format.encodeToString(
                BenchmarkBaselineCatalog(BenchmarkMetricCatalog.SCHEMA_VERSION, baselines),
            ),
        )
        path.toFile().deleteOnExit()
    }
}
