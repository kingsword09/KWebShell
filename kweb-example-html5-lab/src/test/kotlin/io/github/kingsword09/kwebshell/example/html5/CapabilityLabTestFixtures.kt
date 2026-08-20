package io.github.kingsword09.kwebshell.example.html5

internal fun sampleCapabilityPageReport(phase: String = "cold"): CapabilityPageReport {
    val probes = CapabilityLabManifest.definitions.mapIndexed { index, definition ->
        val evidence = linkedMapOf("value" to "observed")
        when (definition.id) {
            "storage.local-storage",
            "storage.indexed-db",
            "storage.cookies",
            -> evidence["previousValue"] = "<absent>"
            else -> evidence["extra"] = "observed"
        }
        if (definition.id == "workers.service-worker") {
            evidence["registration"] = "active"
        } else {
            evidence["extra2"] = "observed"
        }
        CapabilityProbeResult(
            id = definition.id,
            category = definition.category,
            requirement = definition.requirement,
            source = definition.source,
            status = ProbeStatus.PASS,
            startedAtMs = index.toLong() + 1L,
            endedAtMs = index.toLong() + 2L,
            evidence = evidence,
        )
    }
    return CapabilityPageReport(
        schemaVersion = 1,
        phase = phase,
        reportId = if (phase == "cold") {
            "00000000-0000-0000-0000-000000000001"
        } else {
            "00000000-0000-0000-0000-000000000002"
        },
        origin = "http://127.0.0.1:1",
        generatedAtMs = 1_000L,
        userAgent = "Mozilla/5.0 Chrome/151.0.0.0 Safari/537.36",
        secureContext = true,
        probes = probes,
    )
}

internal fun sampleCapabilityHostEvidence(phase: String): CapabilityPhaseHostEvidence =
    CapabilityPhaseHostEvidence(
        schemaVersion = 1,
        phase = phase,
        screenshotFile = "$phase.png",
        displayScale = 1.0,
        chromiumProduct = "Chrome/151.0.0.0",
        protocolVersion = "1.3",
        revision = "@revision",
        javaScriptVersion = "15.1",
        accessibilityRole = "region",
        accessibilityName = "KWebShell capability evidence",
        eventSequences = listOf(1L, 2L, 3L, 4L, 5L),
        eventTypes = listOf(
            "created",
            "navigation-started",
            "address-changed",
            "load-ended",
            "title-changed",
        ),
    )

internal fun sampleCapabilityBundle(): CapabilityLabBundle {
    val cold = sampleCapabilityPageReport("cold")
    val warm = sampleCapabilityPageReport("warm").copy(
        probes = sampleCapabilityPageReport("warm").probes.map { probe ->
            when (probe.id) {
                "storage.local-storage",
                "storage.indexed-db",
                -> probe.copy(evidence = probe.evidence + ("previousValue" to "kwebshell-capability-lab-v1"))
                "storage.cookies" -> probe.copy(evidence = probe.evidence + ("previousValue" to "present"))
                else -> probe
            }
        },
    )
    return CapabilityLabBundle(
        schemaVersion = 1,
        metadata = CapabilityLabMetadata(
            runtimeSha256 = "a".repeat(64),
            chromiumProduct = "Chrome/151.0.0.0",
            protocolVersion = "1.3",
            revision = "@revision",
            javaScriptVersion = "15.1",
            platform = "test",
            architecture = "test",
            displayScale = 1.0,
            hostPolicy = "test-policy",
        ),
        runs = listOf(
            CapabilityLabRun("cold", cold, sampleCapabilityHostEvidence("cold")),
            CapabilityLabRun("warm", warm, sampleCapabilityHostEvidence("warm")),
        ),
    )
}
