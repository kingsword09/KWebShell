package io.github.kingsword09.kwebshell.example.benchmark

public fun main(arguments: Array<String>) {
    when (arguments.singleOrNull() ?: "run") {
        "run", "integration" -> println(
            "KWebShell application benchmark report: ${BenchmarkRunner(BenchmarkConfiguration.fromSystemProperties()).run().toAbsolutePath()}",
        )
        "phase" -> BenchmarkPhaseRunner(BenchmarkConfiguration.fromSystemProperties()).runFromSystemProperties()
        "preview" -> BenchmarkPreview.run()
        else -> throw BenchmarkException("main.argument-invalid", "Expected exactly 'run', 'integration', 'phase', or 'preview'.")
    }
}
