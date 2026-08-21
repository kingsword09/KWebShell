package io.github.kingsword09.kwebshell.example.benchmark

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class BenchmarkValidationTest {
    @Test
    fun configurationRejectsLessThanTenMeasuredPairs() {
        assertFailsWith<IllegalArgumentException> {
            BenchmarkConfiguration(
                cefRuntime = java.nio.file.Path.of("cef"),
                browserSubprocess = java.nio.file.Path.of("subprocess"),
                resources = java.nio.file.Path.of("resources"),
                locales = java.nio.file.Path.of("locales"),
                rootCache = java.nio.file.Path.of("root"),
                outputDirectory = java.nio.file.Path.of("output"),
                workloadRoot = java.nio.file.Path.of("workload"),
                workloadLock = java.nio.file.Path.of("lock"),
                baselineCatalog = java.nio.file.Path.of("baselines"),
                measuredPairs = 9,
            )
        }
    }

    @Test
    fun metricCatalogDoesNotAcceptUnknownUnits() {
        val error = assertFailsWith<BenchmarkException> { BenchmarkMetricCatalog.unit("unknown.value") }
        assertEquals("metric.unit-unknown", error.code)
    }
}
