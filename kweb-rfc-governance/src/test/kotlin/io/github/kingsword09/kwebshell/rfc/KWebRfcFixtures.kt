package io.github.kingsword09.kwebshell.rfc

internal val FIXTURE_RUNTIME: KWebRfcRuntimeIdentity = KWebRfcRuntimeIdentity(
    cefVersion = "151.3.16+gbe1e15d+chromium-151.0.7922.109",
    chromiumVersion = "151.0.7922.109",
)

internal fun fixtureRecord(
    rfcId: String = "0001",
    target: String = "macos-arm64",
    providerId: String = "governance.hosted",
    serviceId: String? = "app-paths",
    serviceVersion: String? = "1.0.0",
    schemaVersion: Int = 1,
    matrixRowIds: List<String> = emptyList(),
    compatibilityStatus: String = "READY",
    cefVersion: String = FIXTURE_RUNTIME.cefVersion,
    chromiumVersion: String = FIXTURE_RUNTIME.chromiumVersion,
    testRunId: String = "run-2026-09-11-1",
    electronFixtureMajor: Int = 37,
    artifacts: List<KWebRfcEvidenceArtifact> = listOf(
        KWebRfcEvidenceArtifact("governance-report", "a".repeat(64)),
    ),
): KWebRfcEvidenceRecord = KWebRfcEvidenceRecord(
    rfcId = rfcId,
    rfcStatus = "Implemented",
    serviceId = serviceId,
    serviceVersion = serviceVersion,
    schemaVersion = schemaVersion,
    providerId = providerId,
    target = target,
    cefVersion = cefVersion,
    chromiumVersion = chromiumVersion,
    electronFixtureMajor = electronFixtureMajor,
    testRunId = testRunId,
    compatibilityStatus = compatibilityStatus,
    matrixRowIds = matrixRowIds,
    artifacts = artifacts,
)

internal fun fixtureManifest(records: List<KWebRfcEvidenceRecord> = emptyList()): KWebRfcEvidenceManifest =
    KWebRfcEvidenceManifest(
        schemaVersion = 1,
        recordsSha256 = KWebRfcEvidenceJson.recordsSha256(records),
        records = records,
    )

internal fun fixtureDocument(
    id: String = "0001",
    status: KWebRfcStatus = KWebRfcStatus.IMPLEMENTED,
    dependsOn: List<String> = listOf("existing Phase 11 prerequisites"),
    platformTargets: Set<String> = emptySet(),
): KWebRfcDocument = KWebRfcDocument(
    id = id,
    fileName = "$id-capability.md",
    title = "RFC $id: Capability",
    status = status,
    priority = "P0",
    owners = "repository governance",
    dependsOn = dependsOn,
    electronSurface = "inventory surface",
    targetMapping = "infrastructure",
    declaredPlatformTargets = platformTargets,
)

internal fun fixtureCatalogDocumentText(
    id: String = "0001",
    status: String = "Implemented",
    priority: String = "P0",
    dependsOn: String = "existing Phase 11 prerequisites",
    platformTargets: String? = null,
    extraLine: String? = null,
): String = buildString {
    appendLine("# RFC $id: Capability")
    appendLine()
    appendLine("- Status: $status")
    appendLine("- Priority: $priority")
    appendLine("- Owners: repository governance")
    appendLine("- Depends on: $dependsOn")
    appendLine("- Electron migration surface: inventory surface")
    appendLine("- Target mapping: infrastructure")
    platformTargets?.let { appendLine("- Platform targets: $it") }
    extraLine?.let { appendLine(it) }
    appendLine()
    appendLine("## Objective")
    appendLine()
    appendLine("Body.")
}

internal fun threeHostedTargets(
    transform: (KWebRfcEvidenceRecord) -> KWebRfcEvidenceRecord = { it },
): List<KWebRfcEvidenceRecord> = listOf("macos-arm64", "windows-x64", "linux-x64")
    .map { target -> transform(fixtureRecord(target = target)) }
