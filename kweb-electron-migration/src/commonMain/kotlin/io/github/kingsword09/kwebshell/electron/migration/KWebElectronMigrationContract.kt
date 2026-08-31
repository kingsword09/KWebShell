package io.github.kingsword09.kwebshell.electron.migration

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

public enum class KWebElectronMappingStatus {
    DIRECT,
    ADAPTER,
    REWRITE,
    UNSUPPORTED,
}

public enum class KWebElectronAdapterKind {
    APP_PATHS_GET_PATH,
}

@Serializable
public data class KWebElectronImport(
    public val module: String,
    public val symbol: String,
    public val status: KWebElectronMappingStatus,
    public val matrixId: String? = null,
)

@Serializable
public data class KWebElectronServiceRequirement(
    public val id: String,
    public val version: String,
)

@Serializable
public data class KWebElectronChannel(
    public val name: String,
    public val schemaVersion: Int,
    public val requestType: String,
    public val responseType: String,
    public val serviceId: String? = null,
    public val serviceVersion: String? = null,
    public val operationId: String? = null,
    public val status: KWebElectronMappingStatus,
    public val adapter: KWebElectronAdapterKind? = null,
)

@Serializable
public data class KWebElectronPreloadMethod(
    public val name: String,
    public val channel: String,
    public val parameterName: String,
    public val parameterType: String,
    public val returnType: String,
    public val status: KWebElectronMappingStatus,
    public val adapter: KWebElectronAdapterKind? = null,
)

@Serializable
public data class KWebElectronManifest(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val rendererGlobal: String,
    public val rendererRoot: String,
    public val rendererEntry: String,
    public val rendererSha256: String,
    @SerialName("electronImports")
    public val electronImports: List<KWebElectronImport>,
    public val channels: List<KWebElectronChannel>,
    public val preloadMethods: List<KWebElectronPreloadMethod>,
    public val requiredServices: List<KWebElectronServiceRequirement>,
)

public class KWebElectronMigrationException(
    code: String,
    message: String,
    details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public object KWebElectronMigrationErrorCode {
    public const val MANIFEST_INVALID_JSON: String = "migration.manifest.invalid-json"
    public const val MANIFEST_SCHEMA_UNSUPPORTED: String = "migration.manifest.schema-unsupported"
    public const val MANIFEST_INVALID: String = "migration.manifest.invalid"
    public const val CHANNEL_UNDECLARED: String = "migration.channel.undeclared"
    public const val SCHEMA_INCOMPATIBLE: String = "migration.schema.incompatible"
    public const val MAPPING_UNRESOLVED: String = "migration.mapping.unresolved"
    public const val INVENTORY_BLOCKED: String = "migration.inventory.blocked"
}

public object KWebElectronMigrationJson {
    public val format: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    public fun decode(text: String): KWebElectronManifest {
        val manifest = try {
            format.decodeFromString<KWebElectronManifest>(text)
        } catch (error: SerializationException) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_INVALID_JSON,
                message = "The Electron migration manifest is not strict schema JSON.",
                cause = error,
            )
        }
        KWebElectronManifestValidator.validate(manifest)
        return manifest
    }

    public fun encode(manifest: KWebElectronManifest): String {
        KWebElectronManifestValidator.validate(manifest)
        return format.encodeToString(manifest)
    }
}

public object KWebElectronManifestValidator {
    private const val CURRENT_SCHEMA_VERSION: Int = 1
    private const val APP_PATHS_SERVICE: String = "app-paths"
    private const val APP_PATHS_VERSION: String = "1.0.0"
    private const val APP_PATHS_OPERATION: String = "resolve"
    private const val APP_PATHS_CHANNEL: String = "app.getPath"
    private const val APP_PATHS_REQUEST: String = "ElectronPathName"
    private const val APP_PATHS_RESPONSE: String = "string"
    private const val APP_PATHS_PARAMETER_TYPE: String = "ElectronPathName"
    private const val APP_PATHS_RETURN_TYPE: String = "Promise<string>"
    private val APPLICATION_ID = Regex(
        "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
    )
    private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,63}")
    private val LOWER_IDENTIFIER = Regex("[a-z][A-Za-z0-9_$]{0,63}")
    private val DIGEST = Regex("[0-9a-f]{64}")
    private val SERVICE_ID = Regex("[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)*")
    private val VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

    public fun validate(manifest: KWebElectronManifest) {
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            invalid(
                KWebElectronMigrationErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                "schemaVersion" to manifest.schemaVersion.toString(),
                message = "Only migration manifest schema version $CURRENT_SCHEMA_VERSION is supported.",
            )
        }
        if (!APPLICATION_ID.matches(manifest.applicationId)) {
            invalid("applicationId", manifest.applicationId, message = "The migration application id is invalid.")
        }
        if (!LOWER_IDENTIFIER.matches(manifest.rendererGlobal) || manifest.rendererGlobal in RESERVED_GLOBALS) {
            invalid("rendererGlobal", manifest.rendererGlobal, message = "The renderer global is not a safe identifier.")
        }
        validateRelativePath(manifest.rendererRoot, "rendererRoot")
        validateRelativePath(manifest.rendererEntry, "rendererEntry")
        if (!DIGEST.matches(manifest.rendererSha256)) {
            invalid("rendererSha256", manifest.rendererSha256, message = "The renderer digest must be lowercase SHA-256.")
        }
        if (manifest.channels.isEmpty() || manifest.preloadMethods.isEmpty()) {
            invalid("surface", message = "A migration manifest must declare at least one channel and preload method.")
        }
        requireUnique(
            manifest.electronImports.map { "${it.module}#${it.symbol}" },
            "Electron import",
        )
        requireUnique(manifest.channels.map { it.name }, "channel")
        requireUnique(manifest.preloadMethods.map { it.name }, "preload method")
        requireUnique(manifest.requiredServices.map { it.id }, "required service")

        manifest.electronImports.forEachIndexed { index, item ->
            if (item.module.isBlank() || item.symbol.isBlank()) {
                invalid("electronImports[$index]", message = "Electron import module and symbol are required.")
            }
            item.matrixId?.let { matrixId ->
                if (!LOWER_IDENTIFIER.matches(matrixId.replace('.', '_').replace('-', '_'))) {
                    invalid("electronImports[$index].matrixId", matrixId, message = "The import matrix id is invalid.")
                }
            }
            val matrixEntry = item.matrixId?.let(KWebElectronCapabilityMatrix::find)
                ?: invalid(
                    KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                    "import" to "${item.module}#${item.symbol}",
                    message = "Every Electron import must reference a published matrix row.",
                )
            if (matrixEntry.status != item.status) {
                invalid(
                    KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                    "import" to "${item.module}#${item.symbol}",
                    message = "The import status does not match its capability matrix row.",
                )
            }
            if (item.module != "electron") {
                invalid(
                    KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                    "import" to "${item.module}#${item.symbol}",
                    message = "A capability-matrix Electron import must use the electron module.",
                )
            }
        }

        val services = manifest.requiredServices.associateBy { it.id }
        manifest.requiredServices.forEachIndexed { index, requirement ->
            if (!SERVICE_ID.matches(requirement.id) || !VERSION.matches(requirement.version)) {
                invalid("requiredServices[$index]", message = "A required service must use a stable id and semantic version.")
            }
            try {
                val components = requirement.version.split('.').map(String::toInt)
                KWebServiceVersion(components[0], components[1], components[2])
            } catch (error: Throwable) {
                invalid("requiredServices[$index].version", requirement.version, message = "A required service version is invalid.", cause = error)
            }
        }

        manifest.channels.forEachIndexed { index, channel ->
            if (!LOWER_IDENTIFIER.matches(channel.name.replace('.', '_'))) {
                invalid("channels[$index].name", channel.name, message = "A channel name is invalid.")
            }
            if (channel.schemaVersion != CURRENT_SCHEMA_VERSION) {
                invalid(
                    KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE,
                    "channel" to channel.name,
                    "version" to channel.schemaVersion.toString(),
                    message = "The channel schema revision is incompatible with this migration kit.",
                )
            }
            if (!IDENTIFIER.matches(channel.requestType) || !IDENTIFIER.matches(channel.responseType)) {
                invalid("channels[$index]", message = "Channel request and response types must be identifiers.")
            }
            validateMapping(channel.status, channel.adapter, channel.serviceId, channel.serviceVersion, channel.operationId, services, channel.name)
            if (channel.adapter == KWebElectronAdapterKind.APP_PATHS_GET_PATH) {
                if (channel.name != APP_PATHS_CHANNEL || channel.serviceId != APP_PATHS_SERVICE ||
                    channel.serviceVersion != APP_PATHS_VERSION || channel.operationId != APP_PATHS_OPERATION ||
                    channel.requestType != APP_PATHS_REQUEST || channel.responseType != APP_PATHS_RESPONSE
                ) {
                    invalid(
                        KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                        "channel" to channel.name,
                        message = "The app-paths adapter must bind the published app.getPath contract exactly.",
                    )
                }
            }
        }

        manifest.preloadMethods.forEachIndexed { index, method ->
            if (!LOWER_IDENTIFIER.matches(method.name) || !LOWER_IDENTIFIER.matches(method.parameterName)) {
                invalid("preloadMethods[$index]", message = "Preload method and parameter names are invalid.")
            }
            val channel = manifest.channels.singleOrNull { it.name == method.channel }
                ?: throw KWebElectronMigrationException(
                    code = KWebElectronMigrationErrorCode.CHANNEL_UNDECLARED,
                    details = mapOf("method" to method.name, "channel" to method.channel),
                    message = "A preload method references an undeclared migration channel.",
                )
            if (method.status != channel.status || method.adapter != channel.adapter) {
                invalid(
                    KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                    "method" to method.name,
                    message = "A preload method mapping must exactly match its channel mapping.",
                )
            }
            if (method.adapter == KWebElectronAdapterKind.APP_PATHS_GET_PATH &&
                (method.parameterType != APP_PATHS_PARAMETER_TYPE || method.returnType != APP_PATHS_RETURN_TYPE)
            ) {
                invalid(
                    KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                    "method" to method.name,
                    message = "The app-paths preload method shape is incompatible with the generated adapter.",
                )
            }
        }
    }

    private fun validateMapping(
        status: KWebElectronMappingStatus,
        adapter: KWebElectronAdapterKind?,
        serviceId: String?,
        serviceVersion: String?,
        operationId: String?,
        services: Map<String, KWebElectronServiceRequirement>,
        channel: String,
    ) {
        when (status) {
            KWebElectronMappingStatus.ADAPTER -> {
                if (adapter == null || serviceId == null || serviceVersion == null || operationId == null) {
                    invalid("channel", channel, message = "An ADAPTER mapping must declare an adapter and exact service operation.")
                }
                val requirement = services[serviceId]
                    ?: invalid("channel", channel, message = "An adapter references a service that is not required by the manifest.")
                if (requirement.version != serviceVersion) {
                    invalid(
                        KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE,
                        "service" to serviceId,
                        "requested" to serviceVersion,
                        "declared" to requirement.version,
                        message = "The adapter service version does not match the required service contract.",
                    )
                }
            }
            KWebElectronMappingStatus.DIRECT,
            KWebElectronMappingStatus.REWRITE,
            KWebElectronMappingStatus.UNSUPPORTED,
            -> if (adapter != null || serviceId != null || serviceVersion != null || operationId != null) {
                invalid("channel", channel, message = "Only ADAPTER mappings may declare service or adapter fields.")
            }
        }
    }

    private fun validateRelativePath(value: String, field: String) {
        if (value.isBlank() || value.contains('\\') || value.startsWith('/') || value.contains(':')) {
            invalid(field, value, message = "The renderer path must be a non-empty relative POSIX path.")
        }
        val segments = value.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            invalid(field, value, message = "The renderer path contains an unsafe segment.")
        }
    }

    private fun requireUnique(values: List<String>, description: String) {
        if (values.size != values.toSet().size) {
            invalid("duplicates", message = "Duplicate $description declarations are not allowed.")
        }
    }

    private fun invalid(
        field: String,
        value: String? = null,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw KWebElectronMigrationException(
        code = KWebElectronMigrationErrorCode.MANIFEST_INVALID,
        details = buildMap {
            put("field", field)
            value?.let { put("value", it) }
        },
        message = message,
        cause = cause,
    )

    private fun invalid(
        code: String,
        vararg details: Pair<String, String>,
        message: String,
    ): Nothing = throw KWebElectronMigrationException(code, message, details.toMap())

    private val RESERVED_GLOBALS = setOf("window", "globalThis", "document", "location", "navigator")
}
