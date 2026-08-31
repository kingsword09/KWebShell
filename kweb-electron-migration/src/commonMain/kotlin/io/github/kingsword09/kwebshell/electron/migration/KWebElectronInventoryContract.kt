package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.Serializable

public enum class KWebElectronInventoryFindingKind {
    ELECTRON_IMPORT,
    NODE_IMPORT,
    ELECTRON_CHANNEL,
    PRELOAD_GLOBAL,
    PACKAGE_DEPENDENCY,
}

@Serializable
public data class KWebElectronInventoryFinding(
    public val path: String,
    public val line: Int,
    public val kind: KWebElectronInventoryFindingKind,
    public val expression: String,
    public val matrixId: String?,
    public val status: KWebElectronMappingStatus?,
    public val blocking: Boolean,
)

@Serializable
public data class KWebElectronInventoryReport(
    public val schemaVersion: Int,
    public val root: String,
    public val filesScanned: Int,
    public val findings: List<KWebElectronInventoryFinding>,
) {
    public val blockingFindings: List<KWebElectronInventoryFinding>
        get() = findings.filter { it.blocking }

    public val migrationReady: Boolean
        get() = blockingFindings.isEmpty()

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
