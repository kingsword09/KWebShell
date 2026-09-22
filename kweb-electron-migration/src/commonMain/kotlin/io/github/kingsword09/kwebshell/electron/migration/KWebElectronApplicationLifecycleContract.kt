package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.Serializable

@Serializable
public data class KWebElectronApplicationLifecycleManifest(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val targets: List<String>,
    public val declarations: List<String>,
    public val dynamicListeners: List<String> = emptyList(),
    public val readsRawArguments: Boolean = false,
    public val rendererRequestsQuit: Boolean = false,
    public val arbitraryRelaunchExecutable: String? = null,
)

@Serializable
public data class KWebElectronApplicationLifecycleReport(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val targets: List<String>,
    public val mappedDeclarations: List<String>,
    public val blockingFindings: List<String>,
) {
    public val ready: Boolean
        get() = blockingFindings.isEmpty()
}

public object KWebElectronApplicationLifecycleMapper {
    public const val CURRENT_SCHEMA_VERSION: Int = 1
    private val APPLICATION_ID = Regex(
        "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
    )
    private val TARGETS = setOf("macos-arm64", "windows-x64", "linux-x64")
    private val DECLARATIONS = setOf(
        "whenReady",
        "requestSingleInstanceLock",
        "second-instance",
        "open-url",
        "open-file",
        "quit",
        "relaunch",
    )

    public fun map(manifest: KWebElectronApplicationLifecycleManifest): KWebElectronApplicationLifecycleReport {
        validate(manifest)
        val findings = buildList {
            manifest.declarations.filterNot(DECLARATIONS::contains).distinct().sorted().forEach {
                add("unsupported-declaration:$it")
            }
            manifest.dynamicListeners.distinct().sorted().forEach { add("dynamic-listener:$it") }
            if (manifest.readsRawArguments) add("raw-process-arguments")
            if (manifest.rendererRequestsQuit) add("renderer-controlled-quit")
            if (manifest.arbitraryRelaunchExecutable != null) add("arbitrary-relaunch-executable")
        }
        return KWebElectronApplicationLifecycleReport(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            applicationId = manifest.applicationId,
            targets = manifest.targets.distinct().sorted(),
            mappedDeclarations = manifest.declarations.filter(DECLARATIONS::contains).distinct().sorted(),
            blockingFindings = findings,
        )
    }

    private fun validate(manifest: KWebElectronApplicationLifecycleManifest) {
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION ||
            !APPLICATION_ID.matches(manifest.applicationId) ||
            manifest.targets.isEmpty() ||
            manifest.targets.any { it !in TARGETS } ||
            manifest.targets.size != manifest.targets.distinct().size ||
            manifest.declarations.isEmpty() ||
            manifest.declarations.size != manifest.declarations.distinct().size
        ) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.LIFECYCLE_INVALID,
                message = "The Electron application lifecycle declaration is not closed or target-complete.",
            )
        }
    }
}
