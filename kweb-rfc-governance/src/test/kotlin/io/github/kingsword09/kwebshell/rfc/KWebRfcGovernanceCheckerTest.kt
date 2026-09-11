package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrix
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrixDocument
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrixEntry
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronMappingStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KWebRfcGovernanceCheckerTest {
    private val matrix: KWebElectronCapabilityMatrixDocument = KWebElectronCapabilityMatrix.document()

    private fun implementedCatalog(): List<KWebRfcDocument> = listOf(
        fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED),
        fixtureDocument(id = "0002", status = KWebRfcStatus.PROPOSED, dependsOn = listOf("0001")),
    )

    @Test
    fun implementedRfcWithoutEvidenceIsBlocked() {
        val report = KWebRfcGovernanceChecker(implementedCatalog(), fixtureManifest(), matrix, FIXTURE_RUNTIME).check()
        assertEquals(KWebRfcGovernanceReport.BLOCKED, report.governanceStatus)
        val status = report.rfcs.single { it.rfcId == "0001" }
        assertEquals(KWebRfcGovernanceState.BLOCKED, status.state)
        assertTrue(
            status.reasons.single().message.contains("[linux-x64, macos-arm64, windows-x64]"),
            "The missing-targets reason must name every required hosted target.",
        )
    }

    @Test
    fun implementedRfcWithAllHostedEvidenceIsReady() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets()),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertEquals(KWebRfcGovernanceReport.READY, report.governanceStatus)
        assertEquals(KWebRfcGovernanceState.READY, report.rfcs.single { it.rfcId == "0001" }.state)
        assertEquals(KWebRfcGovernanceState.READY, report.rfcs.single { it.rfcId == "0002" }.state)
        assertEquals(3, report.rfcs.single { it.rfcId == "0001" }.evidenceTargets.size)
    }

    @Test
    fun partiallyCoveredTargetsAreBlocked() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(
                listOf(fixtureRecord(target = "macos-arm64"), fixtureRecord(target = "windows-x64")),
            ),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        val status = report.rfcs.single { it.rfcId == "0001" }
        assertEquals(KWebRfcGovernanceState.BLOCKED, status.state)
        assertTrue(status.reasons.single().message.contains("[linux-x64]"))
    }

    @Test
    fun staleRuntimeIdentityMakesEvidenceStale() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(
                threeHostedTargets { record -> if (record.target == "macos-arm64") record.copy(cefVersion = "151.3.15+older") else record },
            ),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertEquals(KWebRfcGovernanceReport.BLOCKED, report.governanceStatus)
        assertEquals(KWebRfcGovernanceState.STALE, report.rfcs.single { it.rfcId == "0001" }.state)
        val staleFinding = report.findings.single { it.code == KWebRfcFindingCode.EVIDENCE_STALE_RUNTIME }
        assertTrue(staleFinding.message.contains("pinned runtime"))
    }

    @Test
    fun staleServiceVersionMakesEvidenceStale() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(
                threeHostedTargets { record -> if (record.target == "linux-x64") record.copy(serviceVersion = "0.9.0") else record },
            ),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertEquals(KWebRfcGovernanceState.STALE, report.rfcs.single { it.rfcId == "0001" }.state)
    }

    @Test
    fun unknownServiceContractFails() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(serviceId = "not-a-service") }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_UNKNOWN_SERVICE })
    }

    @Test
    fun evidenceForProposedRfcIsRejected() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(rfcId = "0002") }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_STATUS_MISMATCH })
        assertEquals(KWebRfcGovernanceState.BLOCKED, report.rfcs.single { it.rfcId == "0002" }.state)
    }

    @Test
    fun evidenceForUnknownRfcIsRejected() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(rfcId = "0042") }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_UNKNOWN_RFC })
    }

    @Test
    fun blockedCompatibilityRecordIsRejected() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(compatibilityStatus = "BLOCKED") }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_BLOCKED_RECORD })
    }

    @Test
    fun unsupportedToSupportedPromotionWithoutEvidenceFails() {
        val report = KWebRfcGovernanceChecker(implementedCatalog(), fixtureManifest(), promotedClipboardMatrix(), FIXTURE_RUNTIME).check()
        assertEquals(KWebRfcGovernanceReport.BLOCKED, report.governanceStatus)
        val unbacked = report.findings.filter { it.code == KWebRfcFindingCode.MATRIX_UNBACKED_ROW }
        assertTrue(unbacked.single().message.contains("'clipboard'"))
    }

    @Test
    fun recordClaimingUnsupportedRowFails() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(matrixRowIds = listOf("clipboard")) }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_UNSUPPORTED_ROW_CLAIM })
    }

    @Test
    fun recordClaimingUnknownRowFails() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets { it.copy(matrixRowIds = listOf("not-a-row")) }),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.EVIDENCE_UNKNOWN_MATRIX_ROW })
    }

    /** The real matrix has no RFC-backed supported row yet; this fixture promotes one. */
    private fun promotedClipboardMatrix(): KWebElectronCapabilityMatrixDocument = matrix.copy(
        entries = matrix.entries.map { entry ->
            if (entry.id == "clipboard") {
                KWebElectronCapabilityMatrixEntry(
                    id = entry.id,
                    electron = entry.electron,
                    direction = entry.direction,
                    status = KWebElectronMappingStatus.DIRECT,
                    kweb = "KWebClipboard",
                    notes = entry.notes,
                )
            } else {
                entry
            }
        },
    )

    @Test
    fun implementedEvidenceBacksSupportedMatrixRow() {
        val catalog = listOf(fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED))
        val report = KWebRfcGovernanceChecker(
            catalog,
            fixtureManifest(threeHostedTargets { it.copy(matrixRowIds = listOf("clipboard")) }),
            promotedClipboardMatrix(),
            FIXTURE_RUNTIME,
        ).check()
        assertEquals(KWebRfcGovernanceReport.READY, report.governanceStatus)
        assertEquals("rfc:0001", report.matrixRows.single { it.rowId == "clipboard" }.backing)
    }

    @Test
    fun duplicateMatrixBackingIsRejected() {
        val catalog = listOf(
            fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED),
            fixtureDocument(id = "0009", status = KWebRfcStatus.IMPLEMENTED),
        )
        val manifest = fixtureManifest(
            threeHostedTargets { it.copy(matrixRowIds = listOf("clipboard")) } +
                threeHostedTargets { it.copy(rfcId = "0009", providerId = "second.hosted", matrixRowIds = listOf("clipboard")) },
        )
        val report = KWebRfcGovernanceChecker(catalog, manifest, promotedClipboardMatrix(), FIXTURE_RUNTIME).check()
        assertTrue(report.findings.any { it.code == KWebRfcFindingCode.MATRIX_DUPLICATE_BACKING })
    }

    @Test
    fun platformSpecificRfcRequiresOnlyDeclaredTarget() {
        val catalog = listOf(
            fixtureDocument(
                id = "0033",
                status = KWebRfcStatus.IMPLEMENTED,
                platformTargets = setOf("macos"),
            ),
        )
        val satisfied = KWebRfcGovernanceChecker(
            catalog,
            fixtureManifest(listOf(fixtureRecord(rfcId = "0033", target = "macos-arm64"))),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        assertEquals(KWebRfcGovernanceReport.READY, satisfied.governanceStatus)
        assertEquals(KWebRfcGovernanceState.PLATFORM_SPECIFIC, satisfied.rfcs.single().state)
        assertEquals(listOf("macos-arm64"), satisfied.rfcs.single().requiredTargets)

        val missing = KWebRfcGovernanceChecker(catalog, fixtureManifest(), matrix, FIXTURE_RUNTIME).check()
        assertEquals(KWebRfcGovernanceState.BLOCKED, missing.rfcs.single().state)
        assertTrue(missing.rfcs.single().reasons.single().message.contains("[macos-arm64]"))
    }

    @Test
    fun reportContainsNoPrivatePaths() {
        val report = KWebRfcGovernanceChecker(
            implementedCatalog(),
            fixtureManifest(threeHostedTargets()),
            matrix,
            FIXTURE_RUNTIME,
        ).check()
        val serialized = KWebRfcEvidenceJson.canonical.encodeToString(
            KWebRfcGovernanceReport.serializer(),
            report,
        )
        assertFalse(serialized.contains("/Users/"))
        assertFalse(serialized.contains("/home/"))
        assertFalse(serialized.contains("C:\\\\Users\\\\"))
        assertFalse(serialized.contains("/tmp/"))
        KWebRfcRedaction.scanText("report", serialized)
    }

    @Test
    fun prerequisiteRowsAreReportedWithTheirContract() {
        val report = KWebRfcGovernanceChecker(implementedCatalog(), fixtureManifest(), matrix, FIXTURE_RUNTIME).check()
        // The matrix join still runs even when RFC evidence is missing: the prerequisite
        // rows keep their backing and the unsupported rows stay unsupported.
        assertEquals("prerequisite:KWebDialogs + DialogsBridge", report.matrixRows.single { it.rowId == "dialog" }.backing)
        assertEquals("unsupported", report.matrixRows.single { it.rowId == "clipboard" }.backing)
        assertEquals("unsupported", report.matrixRows.single { it.rowId == "menu-tray" }.backing)
    }

    @Test
    fun brokenManifestProducesFailureReport() {
        val broken = fixtureManifest(threeHostedTargets()).copy(recordsSha256 = "0".repeat(64))
        val report = KWebRfcGovernanceChecker(implementedCatalog(), broken, matrix, FIXTURE_RUNTIME).check()
        assertEquals(KWebRfcGovernanceReport.BLOCKED, report.governanceStatus)
        assertEquals(KWebRfcEvidenceErrorCode.DIGEST_MISMATCH, report.findings.single().code)
    }
}
