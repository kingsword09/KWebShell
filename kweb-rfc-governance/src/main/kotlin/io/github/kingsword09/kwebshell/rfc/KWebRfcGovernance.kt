package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrixDocument
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronMappingStatus
import kotlinx.serialization.Serializable

public enum class KWebRfcGovernanceState {
    READY,
    BLOCKED,
    STALE,
    PLATFORM_SPECIFIC,
}

public object KWebRfcFindingCode {
    public const val EVIDENCE_UNKNOWN_RFC: String = "evidence.unknown-rfc"
    public const val EVIDENCE_STATUS_MISMATCH: String = "evidence.status-mismatch"
    public const val EVIDENCE_STALE_RUNTIME: String = "evidence.stale-runtime"
    public const val EVIDENCE_STALE_SERVICE: String = "evidence.stale-service"
    public const val EVIDENCE_UNKNOWN_SERVICE: String = "evidence.unknown-service"
    public const val EVIDENCE_UNKNOWN_MATRIX_ROW: String = "evidence.unknown-matrix-row"
    public const val EVIDENCE_UNSUPPORTED_ROW_CLAIM: String = "evidence.unsupported-row-claim"
    public const val EVIDENCE_BLOCKED_RECORD: String = "evidence.blocked-record"
    public const val EVIDENCE_MISSING_TARGETS: String = "evidence.missing-targets"
    public const val MATRIX_UNBACKED_ROW: String = "matrix.unbacked-row"
    public const val MATRIX_DUPLICATE_BACKING: String = "matrix.duplicate-backing"
    public const val MATRIX_PREREQUISITE_INVALID: String = "matrix.prerequisite-invalid"
}

@Serializable
public data class KWebRfcGovernanceFinding(
    public val rfcId: String? = null,
    public val code: String,
    public val message: String,
)

@Serializable
public data class KWebRfcGovernanceRfcStatus(
    public val rfcId: String,
    public val title: String,
    public val declaredStatus: String,
    public val state: KWebRfcGovernanceState,
    public val declaredPlatformTargets: List<String>,
    public val requiredTargets: List<String>,
    public val evidenceTargets: List<String>,
    public val reasons: List<KWebRfcGovernanceFinding>,
)

@Serializable
public data class KWebRfcMatrixRowBacking(
    public val rowId: String,
    public val electron: String,
    public val mappingStatus: String,
    public val backing: String,
)

@Serializable
public data class KWebRfcGovernanceReport(
    public val schemaVersion: Int,
    public val governanceStatus: String,
    public val recordsSha256: String,
    public val runtime: KWebRfcRuntimeIdentity,
    public val matrixRows: List<KWebRfcMatrixRowBacking>,
    public val rfcs: List<KWebRfcGovernanceRfcStatus>,
    public val findings: List<KWebRfcGovernanceFinding>,
) {
    public companion object {
        public const val READY: String = "READY"
        public const val BLOCKED: String = "BLOCKED"
        public const val SCHEMA_VERSION: Int = 1

        /** A report for a manifest so broken that the join could not run. */
        public fun failure(findings: List<KWebRfcGovernanceFinding>): KWebRfcGovernanceReport =
            KWebRfcGovernanceReport(
                schemaVersion = SCHEMA_VERSION,
                governanceStatus = BLOCKED,
                recordsSha256 = "",
                runtime = KWebRfcRuntimeIdentity(cefVersion = "", chromiumVersion = ""),
                matrixRows = emptyList(),
                rfcs = emptyList(),
                findings = findings,
            )
    }
}

/**
 * Joins the RFC catalog, the capability evidence manifest, the published service
 * descriptors, and the conservative Electron capability matrix. The RFC catalog is
 * the planning source; a supported matrix row without Implemented-RFC evidence or a
 * delivered-prerequisite backing is rejected here, in CI, before packaging.
 */
public class KWebRfcGovernanceChecker(
    private val catalog: List<KWebRfcDocument>,
    private val manifest: KWebRfcEvidenceManifest,
    private val matrix: KWebElectronCapabilityMatrixDocument,
    private val runtime: KWebRfcRuntimeIdentity,
) {
    /**
     * Matrix rows that are backed by delivered Phase 11 prerequisites instead of an
     * Implemented RFC. Mirrors the "Existing prerequisites" table in docs/rfcs/README.md;
     * `node-runtime` is the delivered typed-rewrite direction of the migration kit.
     */
    private val prerequisiteRows: Set<String> = setOf(
        "browser-window",
        "web-contents",
        "session-partition",
        "profile-protocol",
        "ipc-request",
        "context-bridge",
        "dialog",
        "node-runtime",
    )

    public fun check(): KWebRfcGovernanceReport {
        try {
            KWebRfcEvidenceValidator.validate(manifest)
        } catch (error: KWebRfcGovernanceException) {
            return KWebRfcGovernanceReport.failure(
                listOf(
                    KWebRfcGovernanceFinding(
                        rfcId = null,
                        code = error.code,
                        message = error.message ?: "The capability evidence manifest is invalid.",
                    ),
                ),
            )
        }

        val findings = mutableListOf<KWebRfcGovernanceFinding>()
        val documents = catalog.associateBy { it.id }
        val recordStates = mutableMapOf<Int, Boolean>()

        manifest.records.forEachIndexed { index, record ->
            recordStates[index] = isRecordCurrent(record, findings)
            val document = documents[record.rfcId]
            if (document == null) {
                findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_UNKNOWN_RFC) {
                    "Evidence references RFC ${record.rfcId}, which is not in the catalog."
                }
                return@forEachIndexed
            }
            if (document.status != KWebRfcStatus.IMPLEMENTED) {
                findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_STATUS_MISMATCH) {
                    "The catalog declares RFC ${record.rfcId} as ${document.status.label}; " +
                        "evidence records are only valid for Implemented RFCs."
                }
            }
            record.matrixRowIds.forEach { rowId ->
                val entry = matrix.entries.singleOrNull { it.id == rowId }
                if (entry == null) {
                    findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_UNKNOWN_MATRIX_ROW) {
                        "Evidence claims unknown capability matrix row '$rowId'."
                    }
                } else if (entry.status == KWebElectronMappingStatus.UNSUPPORTED) {
                    findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_UNSUPPORTED_ROW_CLAIM) {
                        "Evidence claims matrix row '$rowId' that the runtime matrix still marks UNSUPPORTED; " +
                            "promote the row in the same change as the evidence."
                    }
                }
            }
        }

        // Evidence requirements for Implemented RFCs, plus the per-RFC state.
        val evidenceTargetsByRfc = mutableMapOf<String, Set<String>>()
        documents.values.forEach { document ->
            if (document.status != KWebRfcStatus.IMPLEMENTED) return@forEach
            val records = manifest.records.filter { it.rfcId == document.id }
            evidenceTargetsByRfc[document.id] = records.map { it.target }.toSet()
            records.filter { it.compatibilityStatus == KWebRfcEvidenceValidator.BLOCKED_STATUS }
                .forEach { record ->
                    findings += finding(document.id, KWebRfcFindingCode.EVIDENCE_BLOCKED_RECORD) {
                        "Evidence for ${record.target} reports a blocked compatibility status."
                    }
                }
            // Targets with no record at all are missing; targets whose record is
            // expired already carry their stale-runtime or stale-service finding.
            val missing = document.requiredHostedTargets - records.map { it.target }.toSet()
            if (missing.isNotEmpty()) {
                findings += finding(document.id, KWebRfcFindingCode.EVIDENCE_MISSING_TARGETS) {
                    "Implemented RFC ${document.id} lacks current hosted evidence for ${missing.sorted()}."
                }
            }
        }

        // The matrix is conservative runtime code. Every supported row must be backed
        // by a delivered prerequisite or by current Implemented-RFC evidence.
        val backingByRow = mutableMapOf<String, MutableSet<String>>()
        manifest.records.forEachIndexed { index, record ->
            if (recordStates[index] != true || record.compatibilityStatus != KWebRfcEvidenceValidator.READY_STATUS) return@forEachIndexed
            record.matrixRowIds.forEach { rowId ->
                backingByRow.getOrPut(rowId) { mutableSetOf() }.add(record.rfcId)
            }
        }
        val matrixRows = matrix.entries.map { entry ->
            when {
                entry.status == KWebElectronMappingStatus.UNSUPPORTED -> {
                    // Records claiming an unsupported row were already flagged above;
                    // the runtime matrix row itself stays conservative and unsupported.
                    KWebRfcMatrixRowBacking(entry.id, entry.electron, entry.status.name, "unsupported")
                }
                entry.id in prerequisiteRows -> {
                    val contract = entry.kweb.ifBlank { entry.electron }
                    KWebRfcMatrixRowBacking(entry.id, entry.electron, entry.status.name, "prerequisite:$contract")
                }
                else -> {
                    val claimants = backingByRow[entry.id].orEmpty()
                    when {
                        claimants.isEmpty() -> {
                            findings += finding(null, KWebRfcFindingCode.MATRIX_UNBACKED_ROW) {
                                "Supported matrix row '${entry.id}' has no Implemented-RFC evidence and no " +
                                    "delivered-prerequisite backing."
                            }
                            KWebRfcMatrixRowBacking(entry.id, entry.electron, entry.status.name, "unbacked")
                        }
                        claimants.size > 1 -> {
                            findings += finding(null, KWebRfcFindingCode.MATRIX_DUPLICATE_BACKING) {
                                "Matrix row '${entry.id}' is claimed by multiple RFCs: ${claimants.sorted()}."
                            }
                            KWebRfcMatrixRowBacking(entry.id, entry.electron, entry.status.name, "rfc:ambiguous")
                        }
                        else -> KWebRfcMatrixRowBacking(entry.id, entry.electron, entry.status.name, "rfc:${claimants.single()}")
                    }
                }
            }
        }
        prerequisiteRows.forEach { rowId ->
            val entry = matrix.entries.singleOrNull { it.id == rowId }
            if (entry == null || entry.status == KWebElectronMappingStatus.UNSUPPORTED) {
                findings += finding(null, KWebRfcFindingCode.MATRIX_PREREQUISITE_INVALID) {
                    "Prerequisite row '$rowId' must exist as a supported matrix row."
                }
            }
        }

        val rfcStatuses = catalog.map { document ->
            val reasons = findings.filter { it.rfcId == document.id }
            val state = when {
                reasons.any { it.code.startsWith("evidence.stale") } -> KWebRfcGovernanceState.STALE
                reasons.isNotEmpty() -> KWebRfcGovernanceState.BLOCKED
                document.isPlatformSpecific -> KWebRfcGovernanceState.PLATFORM_SPECIFIC
                else -> KWebRfcGovernanceState.READY
            }
            KWebRfcGovernanceRfcStatus(
                rfcId = document.id,
                title = document.title,
                declaredStatus = document.status.label,
                state = state,
                declaredPlatformTargets = document.declaredPlatformTargets.sorted(),
                requiredTargets = document.requiredHostedTargets.sorted(),
                evidenceTargets = (evidenceTargetsByRfc[document.id] ?: emptySet()).sorted(),
                reasons = reasons,
            )
        }

        return KWebRfcGovernanceReport(
            schemaVersion = KWebRfcGovernanceReport.SCHEMA_VERSION,
            governanceStatus = if (findings.isEmpty()) {
                KWebRfcGovernanceReport.READY
            } else {
                KWebRfcGovernanceReport.BLOCKED
            },
            recordsSha256 = manifest.recordsSha256,
            runtime = runtime,
            matrixRows = matrixRows,
            rfcs = rfcStatuses,
            findings = findings,
        )
    }

    /**
     * Returns whether the record is still current: bound to the pinned runtime
     * identity and to live service descriptor versions. Expired records are stale
     * evidence, never support.
     */
    private fun isRecordCurrent(record: KWebRfcEvidenceRecord, findings: MutableList<KWebRfcGovernanceFinding>): Boolean {
        var current = true
        if (record.cefVersion != runtime.cefVersion || record.chromiumVersion != runtime.chromiumVersion) {
            findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_STALE_RUNTIME) {
                "Evidence for ${record.target} is bound to CEF ${record.cefVersion} / Chromium " +
                    "${record.chromiumVersion}; the pinned runtime is CEF ${runtime.cefVersion} / " +
                    "Chromium ${runtime.chromiumVersion}."
            }
            current = false
        }
        record.serviceId?.let { serviceId ->
            val contract = KWebRfcServiceCatalog.find(serviceId)
            if (contract == null) {
                findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_UNKNOWN_SERVICE) {
                    "Evidence binds unknown service contract '$serviceId'."
                }
                current = false
            } else if (contract.version != record.serviceVersion) {
                findings += finding(record.rfcId, KWebRfcFindingCode.EVIDENCE_STALE_SERVICE) {
                    "Evidence binds service '$serviceId' at ${record.serviceVersion}; the published " +
                        "descriptor is ${contract.version}."
                }
                current = false
            }
        }
        return current
    }

    private fun finding(
        rfcId: String?,
        code: String,
        message: () -> String,
    ): KWebRfcGovernanceFinding = KWebRfcGovernanceFinding(rfcId = rfcId, code = code, message = message())
}
