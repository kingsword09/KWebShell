package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReportValidator
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Inputs for upserting one deterministic evidence record into the manifest. */
internal data class KWebRfcEvidenceRecordRequest(
    val rfcId: String,
    val providerId: String,
    val target: String,
    val run: KWebRfcHostedRun,
    val electronFixtureMajor: Int,
    val serviceId: String? = null,
    val matrixRowIds: List<String> = emptyList(),
    val compatibilityStatus: String? = null,
    val artifacts: List<KWebRfcEvidenceArtifactInput> = emptyList(),
    val compatibilityReport: KWebElectronCompatibilityReport? = null,
    val compatibilityReportPath: Path? = null,
)

/** Local retained output whose exact bytes are hashed by the recorder. */
internal data class KWebRfcEvidenceArtifactInput(
    val name: String,
    val path: Path,
)

/**
 * Builds capability evidence records from structured test output. Digests and
 * runtime identity are derived from the retained artifacts and the pinned runtime
 * manifest, never typed by hand, so regenerating the manifest from the same inputs
 * is byte-for-byte deterministic.
 */
internal class KWebRfcEvidenceRecorder {
    fun record(
        request: KWebRfcEvidenceRecordRequest,
        manifest: KWebRfcEvidenceManifest,
        catalog: List<KWebRfcDocument>,
        runtime: KWebRfcRuntimeIdentity,
        contractBindings: KWebRfcContractBindingsDocument,
        repositoryRoot: Path,
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
        val artifactInputs = request.artifacts.toMutableList()

        request.compatibilityReport?.let { report ->
            KWebElectronCompatibilityReportValidator.validate(report)
            if (report.electronFixtureMajor != request.electronFixtureMajor) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcRecorderErrorCode.REPORT_ELECTRON_MISMATCH,
                    message = "The evidence Electron major must match the compatibility report.",
                )
            }
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
            serviceVersion = report.serviceContractVersions.getValue(serviceId)
            compatibilityStatus = compatibilityStatus ?: report.migrationStatus
            val reportPath = request.compatibilityReportPath
                ?: throw KWebRfcGovernanceException(
                    code = KWebRfcRecorderErrorCode.REPORT_ARTIFACT_MISSING,
                    message = "A compatibility report must retain and hash the exact report bytes.",
                )
            artifactInputs += KWebRfcEvidenceArtifactInput("compatibility-report", reportPath)
        }

        if (serviceId != null && serviceVersion == null) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED,
                details = mapOf("service" to serviceId),
                message = "A declared service requires a version from a compatibility report.",
            )
        }
        if (artifactInputs.isEmpty()) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.ARTIFACTS_EMPTY,
                details = mapOf("rfc" to request.rfcId),
                message = "An evidence record must retain at least one artifact digest.",
            )
        }
        val artifacts = artifactInputs.map { input -> retainArtifact(input, request, repositoryRoot) }

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
            run = request.run,
            contractSha256 = KWebRfcContractBindings.digest(contractBindings, repositoryRoot, request.rfcId),
            compatibilityStatus = compatibilityStatus ?: KWebRfcEvidenceValidator.READY_STATUS,
            matrixRowIds = request.matrixRowIds,
            artifacts = artifacts.sortedBy { it.name },
        )
        val retained = manifest.records
            .filterNot { it.rfcId == record.rfcId && it.target == record.target && it.providerId == record.providerId }
            .let { existing -> existing + record }
        val updated = manifest.copy(recordsSha256 = KWebRfcEvidenceJson.recordsSha256(retained), records = retained)
        KWebRfcEvidenceValidator.validate(updated)
        return updated
    }

    private fun retainArtifact(
        input: KWebRfcEvidenceArtifactInput,
        request: KWebRfcEvidenceRecordRequest,
        repositoryRoot: Path,
    ): KWebRfcEvidenceArtifact {
        if (!Files.isRegularFile(input.path) || Files.isSymbolicLink(input.path)) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.ARTIFACT_UNREADABLE,
                details = mapOf("artifact" to input.name),
                message = "Evidence artifacts must be readable regular files, not caller-provided digests.",
            )
        }
        if (!SAFE_ARTIFACT_NAME.matches(input.name)) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.ARTIFACT_UNREADABLE,
                details = mapOf("artifact" to input.name),
                message = "An evidence artifact name is unsafe for deterministic retention.",
            )
        }
        val fileName = input.path.fileName.toString()
        if (!SAFE_FILE_NAME.matches(fileName)) {
            throw KWebRfcGovernanceException(
                code = KWebRfcRecorderErrorCode.ARTIFACT_UNREADABLE,
                details = mapOf("artifact" to input.name),
                message = "An evidence artifact file name is unsafe for deterministic retention.",
            )
        }
        val relative = Path.of(
            "docs",
            "rfcs",
            "evidence",
            "artifacts",
            request.rfcId,
            request.run.sourceRevision,
            request.target,
            input.name,
            fileName,
        )
        val destination = repositoryRoot.toAbsolutePath().normalize().resolve(relative).normalize()
        Files.createDirectories(destination.parent)
        if (input.path.toAbsolutePath().normalize() != destination) {
            Files.copy(input.path, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        val digest = KWebRfcEvidenceJson.sha256(Files.readAllBytes(destination))
        return KWebRfcEvidenceArtifact(
            name = input.name,
            path = relative.toString().replace('\\', '/'),
            sha256 = digest,
        )
    }

    private companion object {
        val SAFE_ARTIFACT_NAME: Regex = Regex("[a-z0-9][a-z0-9.-]{0,63}")
        val SAFE_FILE_NAME: Regex = Regex("[A-Za-z0-9_.-]{1,128}")
    }
}

public object KWebRfcRecorderErrorCode {
    public const val RFC_UNKNOWN: String = "rfc.record.rfc-unknown"
    public const val RFC_NOT_IMPLEMENTED: String = "rfc.record.rfc-not-implemented"
    public const val REPORT_ELECTRON_MISMATCH: String = "rfc.record.report-electron-mismatch"
    public const val REPORT_TARGET_MISMATCH: String = "rfc.record.report-target-mismatch"
    public const val REPORT_STALE_RUNTIME: String = "rfc.record.report-stale-runtime"
    public const val REPORT_ARTIFACT_MISSING: String = "rfc.record.report-artifact-missing"
    public const val SERVICE_UNRESOLVED: String = "rfc.record.service-unresolved"
    public const val ARTIFACTS_EMPTY: String = "rfc.record.artifacts-empty"
    public const val ARTIFACT_UNREADABLE: String = "rfc.record.artifact-unreadable"
}
