package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReportValidator

/** Inputs for upserting one deterministic evidence record into the manifest. */
public data class KWebRfcEvidenceRecordRequest(
    public val rfcId: String,
    public val providerId: String,
    public val target: String,
    public val testRunId: String,
    public val electronFixtureMajor: Int,
    public val serviceId: String? = null,
    public val matrixRowIds: List<String> = emptyList(),
    public val compatibilityStatus: String? = null,
    public val artifacts: List<KWebRfcEvidenceArtifact> = emptyList(),
    public val compatibilityReport: KWebElectronCompatibilityReport? = null,
    public val compatibilityReportSha256: String? = null,
)

/**
 * Builds capability evidence records from structured test output. Digests and
 * runtime identity are derived from the retained artifacts and the pinned runtime
 * manifest, never typed by hand, so regenerating the manifest from the same inputs
 * is byte-for-byte deterministic.
 */
public class KWebRfcEvidenceRecorder {
    public fun record(
        request: KWebRfcEvidenceRecordRequest,
        manifest: KWebRfcEvidenceManifest,
        catalog: List<KWebRfcDocument>,
        runtime: KWebRfcRuntimeIdentity,
    ): KWebRfcEvidenceManifest {
        val document = catalog.singleOrNull { it.id == request.rfcId }
            ?: throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.RFC_UNKNOWN,
                details = mapOf("rfc" to request.rfcId),
                message = "Evidence can only be recorded for an RFC in the catalog.",
            )
        if (document.status != KWebRfcStatus.IMPLEMENTED) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.RFC_NOT_IMPLEMENTED,
                details = mapOf("rfc" to request.rfcId, "status" to document.status.label),
                message = "Evidence is a support claim; move the RFC to Implemented in the same change.",
            )
        }

        var serviceId = request.serviceId
        var serviceVersion: String? = null
        var compatibilityStatus = request.compatibilityStatus
        val artifacts = request.artifacts.toMutableList()

        request.compatibilityReport?.let { report ->
            KWebElectronCompatibilityReportValidator.validate(report)
            if (report.target != request.target) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcRecorderErrorCode.REPORT_TARGET_MISMATCH,
                    details = mapOf("report" to report.target, "record" to request.target),
                    message = "The compatibility report was produced for a different target.",
                )
            }
            if (report.cefVersion != runtime.cefVersion || report.chromiumVersion != runtime.chromiumVersion) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcRecorderErrorCode.REPORT_STALE_RUNTIME,
                    details = mapOf(
                        "reportCef" to report.cefVersion,
                        "pinnedCef" to runtime.cefVersion,
                    ),
                    message = "The compatibility report was produced against a different pinned runtime.",
                )
            }
            when (serviceId) {
                null -> {
                    if (report.serviceContractVersions.size != 1) {
                        throw KWebRfcGovernanceException(
                            code = KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED,
                            details = mapOf("services" to report.serviceContractVersions.keys.sorted().joinToString()),
                            message = "The compatibility report binds several services; declare one explicitly.",
                        )
                    }
                    serviceId = report.serviceContractVersions.keys.single()
                }
                else -> if (report.serviceContractVersions[serviceId] == null) {
                    throw KWebRfcGovernanceException(
                        code = KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED,
                        details = mapOf("service" to serviceId),
                        message = "The compatibility report does not bind the declared service contract.",
                    )
                }
            }
            serviceVersion = report.serviceContractVersions.getValue(serviceId!!)
            compatibilityStatus = compatibilityStatus ?: report.migrationStatus
            artifacts += KWebRfcEvidenceArtifact("renderer", report.rendererSha256)
            artifacts += KWebRfcEvidenceArtifact("migration-manifest", report.manifestSha256)
            artifacts += KWebRfcEvidenceArtifact("generated-output", report.generatedOutputSha256)
            artifacts += KWebRfcEvidenceArtifact("inventory", report.inventorySha256)
            artifacts += KWebRfcEvidenceArtifact("capability-matrix", report.capabilityMatrixSha256)
            request.compatibilityReportSha256?.let {
                artifacts += KWebRfcEvidenceArtifact("compatibility-report", it)
            }
        }

        if (serviceId != null && serviceVersion == null) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED,
                details = mapOf("service" to serviceId!!),
                message = "A declared service requires a version from a compatibility report.",
            )
        }
        if (artifacts.isEmpty()) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.ARTIFACTS_EMPTY,
                details = mapOf("rfc" to request.rfcId),
                message = "An evidence record must retain at least one artifact digest.",
            )
        }

        val record = KWebRfcEvidenceRecord(
            rfcId = request.rfcId,
            rfcStatus = KWebRfcEvidenceValidator.IMPLEMENTED_STATUS,
            serviceId = serviceId,
            serviceVersion = serviceVersion,
            schemaVersion = KWebRfcEvidenceValidator.CURRENT_SCHEMA_VERSION,
            providerId = request.providerId,
            target = request.target,
            cefVersion = runtime.cefVersion,
            chromiumVersion = runtime.chromiumVersion,
            electronFixtureMajor = request.electronFixtureMajor,
            testRunId = request.testRunId,
            compatibilityStatus = compatibilityStatus ?: KWebRfcEvidenceValidator.READY_STATUS,
            matrixRowIds = request.matrixRowIds,
            artifacts = artifacts,
        )
        val retained = manifest.records
            .filterNot { it.rfcId == record.rfcId && it.target == record.target && it.providerId == record.providerId }
            .let { existing -> existing + record }
        val updated = manifest.copy(recordsSha256 = KWebRfcEvidenceJson.recordsSha256(retained), records = retained)
        KWebRfcEvidenceValidator.validate(updated)
        return updated
    }
}

public object KWebRfcRecorderErrorCode {
    public const val RFC_UNKNOWN: String = "rfc.record.rfc-unknown"
    public const val RFC_NOT_IMPLEMENTED: String = "rfc.record.rfc-not-implemented"
    public const val REPORT_TARGET_MISMATCH: String = "rfc.record.report-target-mismatch"
    public const val REPORT_STALE_RUNTIME: String = "rfc.record.report-stale-runtime"
    public const val SERVICE_UNRESOLVED: String = "rfc.record.service-unresolved"
    public const val ARTIFACTS_EMPTY: String = "rfc.record.artifacts-empty"
}
