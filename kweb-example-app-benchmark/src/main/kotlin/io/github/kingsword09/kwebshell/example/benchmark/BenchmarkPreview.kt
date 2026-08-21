package io.github.kingsword09.kwebshell.example.benchmark

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch

internal object BenchmarkPreview {
    fun run() {
        val workloadRoot = System.getProperty("kweb.benchmark.workload.root")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: throw BenchmarkException("preview.workload-root-missing", "Preview workload root is not configured.")
        val workloadLock = System.getProperty("kweb.benchmark.workload.lock")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()
            ?: throw BenchmarkException("preview.workload-lock-missing", "Preview workload lock is not configured.")
        BenchmarkWorkloadVerifier.loadAndVerify(workloadLock, workloadRoot)
        val server = BenchmarkServer(workloadRoot)
        val stopped = CountDownLatch(1)
        val shutdown = Thread({
            server.close()
            stopped.countDown()
        }, "KWebShell-application-benchmark-preview-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdown)
        try {
            val sampleId = UUID.randomUUID().toString()
            println("KWebShell application benchmark preview: ${server.registerSample(sampleId, "cold", "preview")}")
            stopped.await()
        } finally {
            server.close()
        }
    }
}
