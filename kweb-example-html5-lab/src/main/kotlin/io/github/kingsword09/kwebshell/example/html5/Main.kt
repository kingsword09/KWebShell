package io.github.kingsword09.kwebshell.example.html5

public fun main(arguments: Array<String>) {
    val mode = arguments.singleOrNull() ?: "run"
    when (mode) {
        "run", "html5test" -> {
            val report = Html5TestSiteRunner(CapabilityLabConfiguration.fromSystemProperties()).run()
            println("KWebShell html5test.com evidence report: ${report.toAbsolutePath()}")
        }
        "integration", "conformance" -> {
            val report = CapabilityLabRunner(CapabilityLabConfiguration.fromSystemProperties()).run()
            println("KWebShell deterministic capability probe report: ${report.toAbsolutePath()}")
        }
        "phase" -> CapabilityLabPhaseRunner(CapabilityLabConfiguration.fromSystemProperties())
            .runFromSystemProperties()
        else -> throw CapabilityLabException(
            "argument-invalid",
            "Expected exactly 'run', 'html5test', 'integration', 'conformance', or 'phase'.",
        )
    }
}
