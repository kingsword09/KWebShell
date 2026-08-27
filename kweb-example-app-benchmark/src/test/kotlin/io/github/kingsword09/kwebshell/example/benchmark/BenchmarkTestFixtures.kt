package io.github.kingsword09.kwebshell.example.benchmark

import java.nio.charset.StandardCharsets
import java.util.UUID

internal object BenchmarkTestFixtures {
    const val WORKLOAD_SHA: String = "b0b0260ecfcd9fc395389e89576fe01696f119cece13c442e3260662bdad91ed"
    const val RUNTIME_SHA: String = "1d67f3d8a4c84d2f86899d5c1de086ff481cd81274bd03f5a30ea21a82e20f97"

    fun sample(pairIndex: Int, phase: String): BenchmarkRawSample {
        val cold = phase == "cold"
        val profileBefore = if (cold) 0.0 else 1_000.0 + pairIndex
        val profileAfter = if (cold) 1_000.0 + pairIndex else 1_010.0 + pairIndex
        val pageMetrics = BenchmarkMetricCatalog.requiredPageMetrics.associateWith { 1.0 }.toMutableMap().apply {
            this["indexeddb.preexisting.record.count"] = if (cold) 0.0 else 160.0
            this["indexeddb.record.count"] = 160.0
            this["resource.transfer.bytes"] = if (cold) 100.0 else 0.0
        }
        val hostMetrics = BenchmarkMetricCatalog.requiredHostMetrics.associateWith { 1.0 }.toMutableMap().apply {
            this["host.profile.before.bytes"] = profileBefore
            this["host.profile.after.bytes"] = profileAfter
            this["host.profile.delta.bytes"] = profileAfter - profileBefore
            this["host.display.scale"] = 1.0
        }
        val sampleId = UUID.nameUUIDFromBytes("$pairIndex/$phase".toByteArray(StandardCharsets.UTF_8)).toString()
        val gpuVendor = "Fixture GPU Vendor"
        val gpuRenderer = "Fixture GPU Renderer"
        return BenchmarkRawSample(
            schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION,
            sampleId = sampleId,
            pairIndex = pairIndex,
            measured = pairIndex > 0,
            phase = phase,
            profileName = "profile-$pairIndex",
            startedAtEpochMs = 1_000L,
            endedAtEpochMs = 2_000L,
            workloadAggregateSha256 = WORKLOAD_SHA,
            runtimeSha256 = RUNTIME_SHA,
            chromiumProduct = "Chrome/151.0.7922.109",
            protocolVersion = "1.3",
            revision = "@fixture",
            javaScriptVersion = "15.1",
            gpuVendor = gpuVendor,
            gpuRenderer = gpuRenderer,
            displayScale = 1.0,
            platform = "macos",
            architecture = "arm64",
            machineClass = "fixture-machine",
            benchmarkGitRevision = "fixture-revision",
            page = BenchmarkPageObservation(
                schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION,
                scenarioId = sampleId,
                phase = phase,
                startedAtMs = 1.0,
                endedAtMs = 2.0,
                metrics = pageMetrics,
                optionalMetrics = emptyMap(),
                unavailableMetrics = mapOf("interaction.inp.ms" to "No deterministic interaction entry."),
                evidence = mapOf(
                    "gpuRenderer" to gpuRenderer,
                    "gpuVendor" to gpuVendor,
                    "webSocketProtocol" to "RFC 6455",
                    "workerType" to "DedicatedWorker",
                    "indexedDbDatabase" to "kwebshell-benchmark",
                    "routeFinal" to "#/proof",
                    "fontFamily" to "Proof Sans",
                    "imageNaturalSize" to "176x112",
                    "audioSampleRate" to "8000",
                    "phasePersistence" to if (cold) "cold:0->160" else "warm:160->160",
                ),
            ),
            hostMetrics = hostMetrics,
            eventSequences = listOf(1L, 2L),
            eventTypes = listOf("created", "closed"),
            evidence = mapOf(
                "lifecycle" to "created->closed",
                "devTools" to "opened->closed",
                "cdpEndpoint" to "loopback",
                "profilePath" to "/fixture/profile-$pairIndex",
            ),
        )
    }

    fun samples(): List<BenchmarkRawSample> = (0..10).flatMap { pair ->
        listOf(sample(pair, "cold"), sample(pair, "warm"))
    }

    fun artifacts(samples: List<BenchmarkRawSample>): List<BenchmarkRawArtifact> = samples.map { sample ->
        BenchmarkRawArtifact(
            file = "raw/${sample.sampleId}.json",
            sha256 = "a".repeat(64),
            sampleId = sample.sampleId,
            measured = sample.measured,
            phase = sample.phase,
        )
    }

    fun screenshots(samples: List<BenchmarkRawSample>): List<BenchmarkScreenshotArtifact> =
        samples.filter { it.pairIndex == 1 }.map { sample ->
            BenchmarkScreenshotArtifact(
                file = "screenshots/${sample.phase}.png",
                sha256 = "b".repeat(64),
                sampleId = sample.sampleId,
                pairIndex = sample.pairIndex,
                phase = sample.phase,
            )
        }

    fun lock(): BenchmarkWorkloadLock = BenchmarkWorkloadLock(
        schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION,
        name = "KWebShell LobeHub-class workload fixture",
        classification = "repository-local-synthetic-workload",
        source = "kweb-example-app-benchmark/src/main/resources/workload",
        revision = "fixture-r01",
        licenseNotice = "Fixture license notice.",
        entryPoint = "index.html",
        aggregateSha256 = WORKLOAD_SHA,
        files = listOf(BenchmarkWorkloadFile("index.html", 1L, "c".repeat(64))),
    )

    fun report(): BenchmarkReport {
        val samples = samples()
        return BenchmarkAggregator.aggregate(lock(), samples, artifacts(samples), screenshots(samples), 1, 10)
    }
}
