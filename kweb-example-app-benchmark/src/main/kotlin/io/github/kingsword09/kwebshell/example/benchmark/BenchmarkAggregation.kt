package io.github.kingsword09.kwebshell.example.benchmark

internal object BenchmarkAggregator {
    fun aggregate(
        lock: BenchmarkWorkloadLock,
        samples: List<BenchmarkRawSample>,
        artifacts: List<BenchmarkRawArtifact>,
        screenshots: List<BenchmarkScreenshotArtifact>,
        warmupPairs: Int,
        measuredPairs: Int,
    ): BenchmarkReport {
        samples.forEach(BenchmarkValidator::validateRaw)
        if (warmupPairs != 1 || measuredPairs < 10 || samples.size != (warmupPairs + measuredPairs) * 2) {
            throw BenchmarkException("aggregate.sample-plan", "The benchmark sample plan is incomplete.")
        }
        if (artifacts.size != samples.size || artifacts.map { it.sampleId } != samples.map { it.sampleId }) {
            throw BenchmarkException("aggregate.raw-index", "The raw artifact index does not match the samples.")
        }
        val expectedPairs = (0 until warmupPairs + measuredPairs).toList()
        if (samples.groupBy { it.phase }.values.any { phaseSamples -> phaseSamples.map { it.pairIndex } != expectedPairs }) {
            throw BenchmarkException("aggregate.pair-order", "Cold and warm samples do not contain every pair in order.")
        }
        expectedPairs.forEach { pairIndex -> validatePair(samples.filter { it.pairIndex == pairIndex }, pairIndex) }
        val screenshotSamples = screenshots.map { screenshot ->
            samples.singleOrNull { it.sampleId == screenshot.sampleId }
                ?: throw BenchmarkException("aggregate.screenshot-sample", "Screenshot '${screenshot.file}' has no raw sample.")
        }
        if (screenshotSamples.size != 2 || screenshotSamples.any { !it.measured } ||
            screenshotSamples.map { it.pairIndex }.toSet() != setOf(warmupPairs) ||
            screenshotSamples.map { it.phase }.toSet() != setOf("cold", "warm")
        ) {
            throw BenchmarkException("aggregate.screenshot-selection", "Screenshots must represent the first measured cold/warm pair.")
        }
        val first = samples.first()
        val invariantSelectors: List<(BenchmarkRawSample) -> String> = listOf(
            { it.workloadAggregateSha256 }, { it.runtimeSha256 }, { it.chromiumProduct },
            { it.protocolVersion }, { it.revision }, { it.javaScriptVersion }, { it.platform },
            { it.gpuVendor }, { it.gpuRenderer }, { it.displayScale.toString() },
            { it.architecture }, { it.machineClass }, { it.benchmarkGitRevision },
        )
        invariantSelectors.forEach { selector ->
            if (samples.any { selector(it) != selector(first) }) {
                throw BenchmarkException("aggregate.metadata-drift", "Benchmark metadata changed between raw samples.")
            }
        }
        if (first.workloadAggregateSha256 != lock.aggregateSha256) {
            throw BenchmarkException("aggregate.workload-drift", "Raw samples do not match the verified workload lock.")
        }
        val phases = listOf("cold", "warm").map { phase ->
            summarizePhase(samples.filter { it.measured && it.phase == phase }, phase, measuredPairs)
        }
        return BenchmarkReport(
            schemaVersion = BenchmarkMetricCatalog.SCHEMA_VERSION,
            metadata = BenchmarkReportMetadata(
                workloadName = lock.name,
                workloadRevision = lock.revision,
                workloadAggregateSha256 = lock.aggregateSha256,
                runtimeSha256 = first.runtimeSha256,
                chromiumProduct = first.chromiumProduct,
                protocolVersion = first.protocolVersion,
                chromiumRevision = first.revision,
                javaScriptVersion = first.javaScriptVersion,
                gpuVendor = first.gpuVendor,
                gpuRenderer = first.gpuRenderer,
                displayScale = first.displayScale,
                platform = first.platform,
                architecture = first.architecture,
                machineClass = first.machineClass,
                benchmarkGitRevision = first.benchmarkGitRevision,
                warmupPairs = warmupPairs,
                measuredPairs = measuredPairs,
            ),
            phases = phases,
            rawArtifacts = artifacts,
            screenshots = screenshots,
        ).also(BenchmarkValidator::validateReport)
    }

    private fun summarizePhase(
        samples: List<BenchmarkRawSample>,
        phase: String,
        expectedCount: Int,
    ): BenchmarkPhaseSummary {
        if (samples.size != expectedCount) {
            throw BenchmarkException("aggregate.phase-count", "Phase '$phase' has ${samples.size} measured samples.")
        }
        val metricNames = BenchmarkMetricCatalog.requiredPageMetrics + BenchmarkMetricCatalog.requiredHostMetrics
        val summaries = metricNames.associateWith { name ->
            summarize(name, samples.map { it.page.metrics[name] ?: it.hostMetrics.getValue(name) })
        }
        val optional = linkedMapOf<String, BenchmarkMetricSummary>()
        val unavailable = linkedMapOf<String, List<String>>()
        BenchmarkMetricCatalog.optionalPageMetrics.forEach { name ->
            val values = samples.mapNotNull { it.page.optionalMetrics[name] }
            val reasons = samples.mapNotNull { it.page.unavailableMetrics[name] }
            when {
                values.size == samples.size -> optional[name] = summarize(name, values)
                reasons.size == samples.size -> unavailable[name] = reasons
                else -> throw BenchmarkException(
                    "aggregate.optional-inconsistent",
                    "Optional metric '$name' is inconsistently measured in phase '$phase'.",
                )
            }
        }
        return BenchmarkPhaseSummary(
            phase = phase,
            sampleCount = samples.size,
            metrics = summaries,
            optionalMetrics = optional,
            unavailableMetrics = unavailable,
        )
    }

    private fun summarize(name: String, values: List<Double>): BenchmarkMetricSummary = BenchmarkMetricSummary(
        unit = BenchmarkMetricCatalog.unit(name),
        sampleCount = values.size,
        median = percentile(values, 0.5),
        p95 = percentile(values, 0.95),
        worst = values.max(),
    )

    private fun validatePair(samples: List<BenchmarkRawSample>, pairIndex: Int) {
        if (samples.map { it.phase } != listOf("cold", "warm")) {
            throw BenchmarkException("aggregate.pair-phases", "Pair $pairIndex is not ordered cold then warm.")
        }
        val cold = samples[0]
        val warm = samples[1]
        if (cold.profileName != warm.profileName || cold.measured != warm.measured) {
            throw BenchmarkException("aggregate.pair-identity", "Pair $pairIndex does not share one Profile and measured state.")
        }
        if (cold.hostMetrics.getValue("host.profile.before.bytes") != 0.0 ||
            warm.hostMetrics.getValue("host.profile.before.bytes") != cold.hostMetrics.getValue("host.profile.after.bytes")
        ) {
            throw BenchmarkException("aggregate.profile-continuity", "Pair $pairIndex does not preserve cold-to-warm Profile bytes.")
        }
        if (cold.page.metrics.getValue("indexeddb.preexisting.record.count") != 0.0 ||
            cold.page.metrics.getValue("indexeddb.record.count") != 160.0 ||
            warm.page.metrics.getValue("indexeddb.preexisting.record.count") != 160.0 ||
            warm.page.metrics.getValue("indexeddb.record.count") != 160.0 ||
            cold.page.evidence["phasePersistence"] != "cold:0->160" ||
            warm.page.evidence["phasePersistence"] != "warm:160->160"
        ) {
            throw BenchmarkException("aggregate.profile-persistence", "Pair $pairIndex failed the cold/warm IndexedDB contract.")
        }
        if (warm.page.metrics.getValue("resource.transfer.bytes") >= cold.page.metrics.getValue("resource.transfer.bytes")) {
            throw BenchmarkException("aggregate.cache-hit", "Pair $pairIndex did not reduce transferred resource bytes on warm restart.")
        }
    }
}
