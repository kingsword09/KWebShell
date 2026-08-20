package io.github.kingsword09.kwebshell.example.html5

public fun main(arguments: Array<String>) {
    val mode = arguments.singleOrNull() ?: "run"
    when (mode) {
        "run", "integration" -> {
            val report = CapabilityLabRunner(CapabilityLabConfiguration.fromSystemProperties()).run()
            println("KWebShell HTML5 capability lab report: ${report.toAbsolutePath()}")
        }
        "phase" -> CapabilityLabPhaseRunner(CapabilityLabConfiguration.fromSystemProperties())
            .runFromSystemProperties()
        else -> throw CapabilityLabException(
            "argument-invalid",
            "Expected exactly 'run', 'integration', or 'phase'.",
        )
    }
}
