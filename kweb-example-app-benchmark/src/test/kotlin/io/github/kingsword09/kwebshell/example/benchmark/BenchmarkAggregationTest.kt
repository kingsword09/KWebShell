package io.github.kingsword09.kwebshell.example.benchmark

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchmarkAggregationTest {
    @Test
    fun aggregatesOneWarmupAndTenMeasuredColdWarmPairs() {
        val report = BenchmarkTestFixtures.report()
        assertEquals(listOf("cold", "warm"), report.phases.map(BenchmarkPhaseSummary::phase))
        assertEquals(10, report.phases.single { it.phase == "cold" }.sampleCount)
        assertEquals(22, report.rawArtifacts.size)
        assertEquals(2, report.screenshots.size)
    }

    @Test
    fun rejectsProfileByteDiscontinuityBetweenColdAndWarm() {
        val samples = BenchmarkTestFixtures.samples().toMutableList()
        val index = samples.indexOfFirst { it.pairIndex == 4 && it.phase == "warm" }
        val warm = samples[index]
        samples[index] = warm.copy(
            hostMetrics = warm.hostMetrics + mapOf(
                "host.profile.before.bytes" to 999.0,
                "host.profile.delta.bytes" to warm.hostMetrics.getValue("host.profile.after.bytes") - 999.0,
            ),
        )
        val error = assertFailsWith<BenchmarkException> {
            BenchmarkAggregator.aggregate(
                BenchmarkTestFixtures.lock(),
                samples,
                BenchmarkTestFixtures.artifacts(samples),
                BenchmarkTestFixtures.screenshots(samples),
                1,
                10,
            )
        }
        assertEquals("aggregate.profile-continuity", error.code)
    }

    @Test
    fun rejectsWarmRunWithoutAResourceCacheReduction() {
        val samples = BenchmarkTestFixtures.samples().toMutableList()
        val index = samples.indexOfFirst { it.pairIndex == 6 && it.phase == "warm" }
        val warm = samples[index]
        samples[index] = warm.copy(page = warm.page.copy(metrics = warm.page.metrics + ("resource.transfer.bytes" to 100.0)))
        val error = assertFailsWith<BenchmarkException> {
            BenchmarkAggregator.aggregate(
                BenchmarkTestFixtures.lock(),
                samples,
                BenchmarkTestFixtures.artifacts(samples),
                BenchmarkTestFixtures.screenshots(samples),
                1,
                10,
            )
        }
        assertEquals("aggregate.cache-hit", error.code)
    }

    @Test
    fun rejectsAReportWithAnIncompleteRawMetricSet() {
        val sample = BenchmarkTestFixtures.sample(1, "cold")
        val error = assertFailsWith<BenchmarkException> {
            BenchmarkValidator.validateRaw(
                sample.copy(hostMetrics = sample.hostMetrics - "host.shutdown.ms"),
            )
        }
        assertEquals("host.metric-set", error.code)
    }
}
