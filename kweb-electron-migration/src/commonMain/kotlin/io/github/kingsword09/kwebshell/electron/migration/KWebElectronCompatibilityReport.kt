package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.Serializable

@Serializable
public data class KWebElectronPerformanceComparison(
    public val baseline: String,
    public val rendererSha256: String,
    public val machineClass: String,
)

@Serializable
public data class KWebElectronCompatibilityReport(
    public val schemaVersion: Int,
    public val migrationStatus: String,
    public val blockedReasons: List<String>,
    public val rendererSha256: String,
    public val manifestSha256: String,
    public val generatedOutputSha256: String,
    public val inventorySha256: String,
    public val capabilityMatrixVersion: Int,
    public val capabilityMatrixSha256: String,
    public val serviceContractVersions: Map<String, String>,
    public val cefVersion: String,
    public val chromiumVersion: String,
    public val target: String,
    public val performanceComparison: KWebElectronPerformanceComparison? = null,
) {
    public val migrationReady: Boolean
        get() = migrationStatus == READY_STATUS && blockedReasons.isEmpty()

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public const val READY_STATUS: String = "READY"
        public const val BLOCKED_STATUS: String = "BLOCKED"
    }
}

public object KWebElectronCompatibilityReportValidator {
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val TARGET = Regex("(windows|macos|linux)-(x64|arm64)")
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    public fun validate(report: KWebElectronCompatibilityReport) {
        require(report.schemaVersion == KWebElectronCompatibilityReport.CURRENT_SCHEMA_VERSION) {
            "Unsupported Electron compatibility report schema ${report.schemaVersion}."
        }
        require(report.migrationStatus == KWebElectronCompatibilityReport.READY_STATUS ||
            report.migrationStatus == KWebElectronCompatibilityReport.BLOCKED_STATUS
        ) {
            "The Electron compatibility report status is invalid."
        }
        require(DIGEST.matches(report.rendererSha256)) { "The report renderer digest is invalid." }
        require(DIGEST.matches(report.manifestSha256)) { "The report manifest digest is invalid." }
        require(DIGEST.matches(report.generatedOutputSha256)) { "The report generated-output digest is invalid." }
        require(DIGEST.matches(report.inventorySha256)) { "The report inventory digest is invalid." }
        require(report.capabilityMatrixVersion > 0 && DIGEST.matches(report.capabilityMatrixSha256)) {
            "The report capability-matrix provenance is invalid."
        }
        require(report.cefVersion.isNotBlank() && report.chromiumVersion.isNotBlank()) {
            "The report must contain CEF and Chromium identities."
        }
        require(TARGET.matches(report.target)) { "The report target is invalid." }
        require(report.serviceContractVersions.all { (id, version) ->
            id.isNotBlank() && VERSION.matches(version)
        }) {
            "The report contains an invalid service contract version."
        }
        if (report.migrationStatus == KWebElectronCompatibilityReport.BLOCKED_STATUS) {
            require(report.blockedReasons.isNotEmpty()) {
                "A blocked report must explain at least one blocking reason."
            }
            require(report.performanceComparison == null) {
                "A blocked migration report cannot contain a performance comparison."
            }
        } else {
            require(report.blockedReasons.isEmpty()) {
                "A ready report cannot contain blocking reasons."
            }
        }
    }
}
