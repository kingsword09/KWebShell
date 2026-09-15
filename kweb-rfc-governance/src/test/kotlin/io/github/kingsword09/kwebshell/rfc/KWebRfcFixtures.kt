package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrixDocument

internal val FIXTURE_RUNTIME: KWebRfcRuntimeIdentity = KWebRfcRuntimeIdentity(
    cefVersion = "151.3.16+gbe1e15d+chromium-151.0.7922.109",
    chromiumVersion = "151.0.7922.109",
)

internal val FIXTURE_RUN: KWebRfcHostedRun = KWebRfcHostedRun(
    repository = "kingsword09/KWebShell",
    workflowRef = "kingsword09/KWebShell/.github/workflows/ci.yml@refs/heads/main",
    runId = "123456789",
    runAttempt = 1,
    sourceRevision = "1".repeat(40),
)

internal val FIXTURE_CONTRACT_DIGESTS: KWebRfcContractDigestProvider =
    KWebRfcContractDigestProvider { "c".repeat(64) }

internal fun KWebRfcGovernanceChecker(
    catalog: List<KWebRfcDocument>,
    manifest: KWebRfcEvidenceManifest,
    matrix: KWebElectronCapabilityMatrixDocument,
    runtime: KWebRfcRuntimeIdentity,
): KWebRfcGovernanceChecker = KWebRfcGovernanceChecker(
    catalog,
    manifest,
    matrix,
    runtime,
    FIXTURE_CONTRACT_DIGESTS,
    KWebRfcArtifactDigestProvider { "a".repeat(64) },
)

internal fun fixtureRecord(
    rfcId: String = "0001",
    target: String = "macos-arm64",
    providerId: String = "governance.hosted",
    serviceId: String? = "app-paths",
    serviceVersion: String? = "1.0.0",
    schemaVersion: Int = 2,
    matrixRowIds: List<String> = emptyList(),
    compatibilityStatus: String = "READY",
    cefVersion: String = FIXTURE_RUNTIME.cefVersion,
    chromiumVersion: String = FIXTURE_RUNTIME.chromiumVersion,
    run: KWebRfcHostedRun = FIXTURE_RUN,
    contractSha256: String = "c".repeat(64),
    electronFixtureMajor: Int = 37,
    artifacts: List<KWebRfcEvidenceArtifact> = listOf(
        KWebRfcEvidenceArtifact(
            "governance-report",
            "docs/rfcs/evidence/artifacts/fixture.json",
            "a".repeat(64),
        ),
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
    run = run,
    contractSha256 = contractSha256,
    compatibilityStatus = compatibilityStatus,
    matrixRowIds = matrixRowIds,
    artifacts = artifacts,
)

internal fun fixtureManifest(records: List<KWebRfcEvidenceRecord> = emptyList()): KWebRfcEvidenceManifest =
    KWebRfcEvidenceManifest(
        schemaVersion = 2,
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
