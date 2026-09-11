package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebRfcEvidenceRecorderTest {
    private val catalog = listOf(
        fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED),
        fixtureDocument(id = "0002", status = KWebRfcStatus.PROPOSED, dependsOn = listOf("0001")),
    )

    private val compatibilityReport = KWebElectronCompatibilityReport(
        schemaVersion = 1,
        migrationStatus = "READY",
        blockedReasons = emptyList(),
        rendererSha256 = "b".repeat(64),
        manifestSha256 = "c".repeat(64),
        generatedOutputSha256 = "d".repeat(64),
        inventorySha256 = "e".repeat(64),
        capabilityMatrixVersion = 1,
        capabilityMatrixSha256 = "f".repeat(64),
        serviceContractVersions = mapOf("app-paths" to "1.0.0"),
        cefVersion = FIXTURE_RUNTIME.cefVersion,
        chromiumVersion = FIXTURE_RUNTIME.chromiumVersion,
        target = "macos-arm64",
    )

    private fun request(
        report: KWebElectronCompatibilityReport? = compatibilityReport,
        reportDigest: String? = "9".repeat(64),
        rfcId: String = "0001",
        target: String = "macos-arm64",
    ) = KWebRfcEvidenceRecordRequest(
        rfcId = rfcId,
        providerId = "governance.hosted",
        target = target,
        testRunId = "run-2026-09-11-1",
        electronFixtureMajor = 37,
        compatibilityReport = report,
        compatibilityReportSha256 = reportDigest,
    )

    @Test
    fun recordDerivesDigestsAndServiceVersionFromCompatibilityReport() {
        val updated = KWebRfcEvidenceRecorder().record(request(), fixtureManifest(), catalog, FIXTURE_RUNTIME)
        val record = updated.records.single()
        assertEquals("app-paths", record.serviceId)
        assertEquals("1.0.0", record.serviceVersion)
        assertEquals(FIXTURE_RUNTIME.cefVersion, record.cefVersion)
        assertEquals("READY", record.compatibilityStatus)
        assertEquals(
            listOf(
                KWebRfcEvidenceArtifact("renderer", "b".repeat(64)),
                KWebRfcEvidenceArtifact("migration-manifest", "c".repeat(64)),
                KWebRfcEvidenceArtifact("generated-output", "d".repeat(64)),
                KWebRfcEvidenceArtifact("inventory", "e".repeat(64)),
                KWebRfcEvidenceArtifact("capability-matrix", "f".repeat(64)),
                KWebRfcEvidenceArtifact("compatibility-report", "9".repeat(64)),
            ),
            record.artifacts,
        )
    }

    @Test
    fun regenerationIsByteForByteDeterministic() {
        val recorder = KWebRfcEvidenceRecorder()
        val first = KWebRfcEvidenceJson.encodeManifest(
            recorder.record(request(), fixtureManifest(), catalog, FIXTURE_RUNTIME),
        )
        val second = KWebRfcEvidenceJson.encodeManifest(
            recorder.record(request(), fixtureManifest(), catalog, FIXTURE_RUNTIME),
        )
        assertEquals(first, second)
        assertTrue(first.contains("\"recordsSha256\""))
    }

    @Test
    fun upsertReplacesSameIdentityAndKeepsOtherTargets() {
        val recorder = KWebRfcEvidenceRecorder()
        val manifest = fixtureManifest(threeHostedTargets())
        val updated = recorder.record(
            request(target = "macos-arm64"),
            manifest,
            catalog,
            FIXTURE_RUNTIME,
        )
        assertEquals(3, updated.records.size)
        val macosRecords = updated.records.filter { it.target == "macos-arm64" }
        assertEquals(1, macosRecords.size)
        assertEquals("run-2026-09-11-1", macosRecords.single().testRunId)
    }

    @Test
    fun reportForDifferentTargetFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceRecorder().record(
                request(target = "windows-x64"),
                fixtureManifest(),
                catalog,
                FIXTURE_RUNTIME,
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.REPORT_TARGET_MISMATCH, error.code)
    }

    @Test
    fun reportAgainstDifferentRuntimeFails() {
        val staleReport = compatibilityReport.copy(cefVersion = "150.0.0+old")
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceRecorder().record(
                request(report = staleReport),
                fixtureManifest(),
                catalog,
                FIXTURE_RUNTIME,
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.REPORT_STALE_RUNTIME, error.code)
    }

    @Test
    fun recordingForProposedRfcFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceRecorder().record(
                request(rfcId = "0002"),
                fixtureManifest(),
                catalog,
                FIXTURE_RUNTIME,
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.RFC_NOT_IMPLEMENTED, error.code)
    }

    @Test
    fun recordingForUnknownRfcFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceRecorder().record(
                request(rfcId = "0042"),
                fixtureManifest(),
                catalog,
                FIXTURE_RUNTIME,
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.RFC_UNKNOWN, error.code)
    }

    @Test
    fun declaredArtifactsRequireNoReportButServiceBindingRequiresReport() {
        val updated = KWebRfcEvidenceRecorder().record(
            KWebRfcEvidenceRecordRequest(
                rfcId = "0001",
                providerId = "governance.hosted",
                target = "linux-x64",
                testRunId = "run-2026-09-11-2",
                electronFixtureMajor = 37,
                artifacts = listOf(KWebRfcEvidenceArtifact("governance-report", "a".repeat(64))),
            ),
            fixtureManifest(),
            catalog,
            FIXTURE_RUNTIME,
        )
        assertEquals(1, updated.records.size)
        assertEquals(null, updated.records.single().serviceId)

        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceRecorder().record(
                KWebRfcEvidenceRecordRequest(
                    rfcId = "0001",
                    providerId = "governance.hosted",
                    target = "linux-x64",
                    testRunId = "run-2026-09-11-2",
                    electronFixtureMajor = 37,
                    serviceId = "app-paths",
                    artifacts = listOf(KWebRfcEvidenceArtifact("governance-report", "a".repeat(64))),
                ),
                fixtureManifest(),
                catalog,
                FIXTURE_RUNTIME,
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED, error.code)
    }
}
