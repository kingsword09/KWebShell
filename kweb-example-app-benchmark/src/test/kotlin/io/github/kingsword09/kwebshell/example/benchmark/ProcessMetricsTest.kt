package io.github.kingsword09.kwebshell.example.benchmark

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class ProcessMetricsTest {
    @Test
    fun samplesTheCurrentJvmProcessTreeOnTheHostPlatform() {
        val metrics = try {
            ProcessMetricsSampler.sample()
        } catch (error: BenchmarkException) {
            fail("${error.code}: ${error.message}", error)
        }
        assertTrue(metrics.residentBytes > 0.0)
        assertTrue(metrics.privateBytes > 0.0)
        assertTrue(metrics.cpuMs >= 0.0)
        assertTrue(metrics.threadCount > 0.0)
    }

    @Test
    fun rejectsAMissingRootProcess() {
        val error = assertFailsWith<BenchmarkException> { ProcessMetricsSampler.sample(Long.MAX_VALUE) }
        assertEquals("process.root-missing", error.code)
    }
}
