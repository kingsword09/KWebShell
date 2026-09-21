package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException

@Serializable
public data class KWebElectronPackagingManifest(
    public val schemaVersion: Int,
    public val builder: String,
    public val applicationId: String,
    public val productName: String,
    public val version: String,
    public val targets: List<String>,
    public val protocols: List<String> = emptyList(),
    public val fileExtensions: List<String> = emptyList(),
    public val hooks: List<String> = emptyList(),
)

@Serializable
public data class KWebElectronPackagingReport(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val productName: String,
    public val version: String,
    public val targets: List<String>,
    public val protocols: List<String>,
    public val fileExtensions: List<String>,
    public val blockingFindings: List<String>,
) {
    public val ready: Boolean
        get() = blockingFindings.isEmpty()
}

public object KWebElectronPackagingMapper {
    public const val CURRENT_SCHEMA_VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
    private val APPLICATION_ID = Regex(
        "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
    )
    private val VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")
    private val TARGETS = setOf("macos-arm64", "macos-x64", "windows-x64", "linux-x64")
    private val SUPPORTED_BUILDERS = setOf("electron-builder", "electron-forge")
    private val SUPPORTED_HOOKS = emptySet<String>()

    public fun decode(text: String): KWebElectronPackagingManifest {
        val manifest = try {
            json.decodeFromString(KWebElectronPackagingManifest.serializer(), text)
        } catch (error: SerializationException) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.PACKAGING_INVALID,
                message = "The Electron packaging metadata is not strict schema JSON.",
                cause = error,
            )
        }
        validate(manifest)
        if (json.encodeToString(KWebElectronPackagingManifest.serializer(), manifest) + "\n" != text) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.PACKAGING_NON_CANONICAL,
                message = "The Electron packaging metadata is not canonical JSON.",
            )
        }
        return manifest
    }

    public fun encode(manifest: KWebElectronPackagingManifest): String {
        validate(manifest)
        return json.encodeToString(KWebElectronPackagingManifest.serializer(), manifest) + "\n"
    }

    public fun map(manifest: KWebElectronPackagingManifest): KWebElectronPackagingReport {
        validate(manifest)
        val findings = buildList {
            manifest.hooks.filterNot(SUPPORTED_HOOKS::contains).distinct().sorted().forEach { hook ->
                add("unsupported-hook:$hook")
            }
        }
        return KWebElectronPackagingReport(
            schemaVersion = CURRENT_SCHEMA_VERSION,
            applicationId = manifest.applicationId,
            productName = manifest.productName,
            version = manifest.version,
            targets = manifest.targets.distinct().sorted(),
            protocols = manifest.protocols.distinct().sorted(),
            fileExtensions = manifest.fileExtensions.distinct().sorted(),
            blockingFindings = findings,
        )
    }

    private fun validate(manifest: KWebElectronPackagingManifest) {
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION ||
            manifest.builder !in SUPPORTED_BUILDERS ||
            !APPLICATION_ID.matches(manifest.applicationId) ||
            manifest.productName.isBlank() ||
            !VERSION.matches(manifest.version) ||
            manifest.targets.isEmpty() ||
            manifest.targets.any { it !in TARGETS } ||
            manifest.targets.size != manifest.targets.distinct().size ||
            manifest.protocols.any { !it.matches(Regex("[a-z][a-z0-9+.-]{1,31}")) } ||
            manifest.fileExtensions.any { !it.matches(Regex("\\.[a-z0-9][a-z0-9._-]{0,31}")) }
        ) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.PACKAGING_INVALID,
                message = "The Electron packaging metadata contains an invalid identity, target, association, or builder.",
            )
        }
    }
}
