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
    NAMED_APPLICATION_STREAM,
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

/**
 * The declared policy of one privileged migration channel: renderer grant,
 * user-gesture requirement, and OS consent requirement must all be stated
 * explicitly before the migration facade can be generated or packaged.
 */
@Serializable
public data class KWebElectronChannelPolicy(
    public val rendererGrant: String?,
    public val requiresUserGesture: Boolean,
    public val requiresOsConsent: Boolean,
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
    public val policy: KWebElectronChannelPolicy? = null,
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
public data class KWebElectronStream(
    public val name: String,
    public val method: String,
    public val requestType: String,
    public val chunkType: String,
    public val capacity: Int,
    public val status: KWebElectronMappingStatus,
    public val adapter: KWebElectronAdapterKind? = null,
    public val policy: KWebElectronChannelPolicy? = null,
)

@Serializable
public data class KWebElectronWindowDefinition(
    public val id: String,
    public val title: String,
    public val isMainWindow: Boolean = false,
    public val profile: String = "default",
    public val isModal: Boolean = false,
    public val parentWindowId: String? = null,
)

@Serializable
public data class KWebElectronProfileDefinition(
    public val id: String,
    public val storagePath: String? = null,
    public val isPersistent: Boolean = true,
)

@Serializable
public data class KWebElectronLifecycleEvent(
    public val event: String,
    public val hostTarget: String,
    public val status: KWebElectronMappingStatus,
)

@Serializable
public enum class KWebElectronDependencyKind {
    @SerialName("builtin") BUILTIN,
    @SerialName("native-addon") NATIVE_ADDON,
    @SerialName("package") PACKAGE,
}

@Serializable
public data class KWebElectronNodeDependency(
    public val name: String,
    public val kind: KWebElectronDependencyKind,
    public val status: KWebElectronMappingStatus,
    public val replacementServiceId: String? = null,
)

@Serializable
public data class KWebElectronManifest(
    public val schemaVersion: Int,
    public val applicationId: String,
    public val rendererGlobal: String,
    public val rendererOrigin: String,
    public val rendererProfile: String,
    public val rendererRoot: String,
    public val rendererEntry: String,
    public val rendererSha256: String,
    /**
     * The pinned Electron major this fixture's API surface was surveyed against.
     * The value participates in capability evidence and must be repinned with a new
     * evidence revision when the fixture moves to a different Electron major.
     */
    public val electronFixtureMajor: Int,
    @SerialName("electronImports")
    public val electronImports: List<KWebElectronImport>,
    public val channels: List<KWebElectronChannel>,
    public val preloadMethods: List<KWebElectronPreloadMethod>,
    public val streams: List<KWebElectronStream> = emptyList(),
    public val requiredServices: List<KWebElectronServiceRequirement>,
    public val windows: List<KWebElectronWindowDefinition> = emptyList(),
    public val profiles: List<KWebElectronProfileDefinition> = emptyList(),
    public val lifecycleEvents: List<KWebElectronLifecycleEvent> = emptyList(),
    public val nodeDependencies: List<KWebElectronNodeDependency> = emptyList(),
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
    public const val REPORT_CONFLICT: String = "migration.report.conflict"
    public const val PARSER_UNAVAILABLE: String = "migration.parser.unavailable"
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

@Serializable
private data class KWebElectronManifestV1(
    val schemaVersion: Int,
    val applicationId: String,
    val rendererGlobal: String,
    val rendererRoot: String,
    val rendererEntry: String,
    val rendererSha256: String,
    val electronFixtureMajor: Int,
    val electronImports: List<KWebElectronImport>,
    val channels: List<KWebElectronChannel>,
    val preloadMethods: List<KWebElectronPreloadMethod>,
    val streams: List<KWebElectronStream> = emptyList(),
    val requiredServices: List<KWebElectronServiceRequirement>,
)

public object KWebElectronManifestMigrator {
    public fun migrate(v1Json: String, rendererOrigin: String): KWebElectronManifest {
        val v1 = try {
            KWebElectronMigrationJson.format.decodeFromString<KWebElectronManifestV1>(v1Json)
        } catch (error: SerializationException) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_INVALID_JSON,
                message = "The input manifest is not valid JSON.",
                cause = error,
            )
        }
        if (v1.schemaVersion != 1) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                details = mapOf("schemaVersion" to v1.schemaVersion.toString()),
                message = "Only migration manifest v1 can be migrated by this tool.",
            )
        }
        if (v1.channels.any { it.schemaVersion != 1 }) {
            throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE, "A v1 manifest must contain v1 channel contracts; unknown revisions cannot be migrated.")
        }
        val v2 = KWebElectronManifest(
            schemaVersion = 2,
            applicationId = v1.applicationId,
            rendererGlobal = v1.rendererGlobal,
            rendererOrigin = rendererOrigin,
            rendererProfile = "default",
            rendererRoot = v1.rendererRoot,
            rendererEntry = v1.rendererEntry,
            rendererSha256 = v1.rendererSha256,
            electronFixtureMajor = v1.electronFixtureMajor,
            electronImports = v1.electronImports,
            channels = v1.channels.map { it.copy(schemaVersion = 2) },
            preloadMethods = v1.preloadMethods,
            streams = v1.streams,
            requiredServices = v1.requiredServices,
            windows = listOf(
                KWebElectronWindowDefinition(
                    id = "main",
                    title = v1.applicationId,
                    isMainWindow = true,
                    profile = "default",
                ),
            ),
            profiles = listOf(
                KWebElectronProfileDefinition(
                    id = "default",
                    storagePath = "profiles/default",
                    isPersistent = true,
                ),
            ),
            lifecycleEvents = emptyList(),
            nodeDependencies = emptyList(),
        )
        KWebElectronManifestValidator.validate(v2)
        return v2
    }
}

public object KWebElectronManifestValidator {
    public const val CURRENT_SCHEMA_VERSION: Int = 2
    private const val APP_PATHS_SERVICE: String = "app-paths"
    private const val APP_PATHS_VERSION: String = "1.0.0"
    private const val APP_PATHS_OPERATION: String = "resolve"
    private const val APP_PATHS_CHANNEL: String = "app.getPath"
    private const val APP_PATHS_REQUEST: String = "ElectronPathName"
    private const val APP_PATHS_RESPONSE: String = "string"
    private const val APP_PATHS_PARAMETER_TYPE: String = "ElectronPathName"
    private const val APP_PATHS_RETURN_TYPE: String = "Promise<string>"
    // The published KWebAppPaths resolve operation policy; a jvmTest drift
    // check asserts these constants still match the live descriptor.
    private const val APP_PATHS_GRANT: String = "native.app-paths.resolve"
    private const val APP_PATHS_GESTURE: Boolean = false
    private const val APP_PATHS_CONSENT: Boolean = false

    private fun validateChannelPolicy(channel: KWebElectronChannel) {
        if (channel.status != KWebElectronMappingStatus.ADAPTER) {
            if (channel.policy != null) {
                invalid("channel", channel.name, message = "Only ADAPTER channels declare a migration policy.")
            }
            return
        }
        val policy = channel.policy
            ?: invalid("channel", channel.name, message = "A privileged ADAPTER channel must declare its policy.")
        policy.rendererGrant?.let { grant ->
            if (!SERVICE_ID.matches(grant)) {
                invalid("channel", channel.name, message = "A channel renderer grant must be a stable identifier.")
            }
        }
    }
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
        if (!Regex("(?:https?|app)://[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?(?::[1-9][0-9]{0,4})?").matches(manifest.rendererOrigin) ||
            manifest.rendererOrigin.substringAfterLast(':', "").toIntOrNull()?.let { it > 65535 } == true
        ) invalid("rendererOrigin", message = "Declare one exact canonical http, https or app origin without a path, wildcard or credentials.")
        validateRelativePath(manifest.rendererRoot, "rendererRoot")
        validateRelativePath(manifest.rendererEntry, "rendererEntry")
        if (!DIGEST.matches(manifest.rendererSha256)) {
            invalid("rendererSha256", manifest.rendererSha256, message = "The renderer digest must be lowercase SHA-256.")
        }
        if (manifest.electronFixtureMajor !in 1..999) {
            invalid(
                "electronFixtureMajor",
                manifest.electronFixtureMajor.toString(),
                message = "The Electron fixture major must be pinned between 1 and 999.",
            )
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
        requireUnique(manifest.streams.map { it.name }, "stream")
        requireUnique(manifest.streams.map { it.method }, "stream method")
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
            validateChannelPolicy(channel)
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
                val policy = channel.policy
                if (policy == null || policy.rendererGrant != APP_PATHS_GRANT ||
                    policy.requiresUserGesture != APP_PATHS_GESTURE ||
                    policy.requiresOsConsent != APP_PATHS_CONSENT
                ) {
                    invalid(
                        KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED,
                        "channel" to channel.name,
                        message = "The app-paths adapter must declare the published grant, gesture, and consent policy exactly.",
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

        manifest.streams.forEachIndexed { index, stream ->
            if (!LOWER_IDENTIFIER.matches(stream.name) || !LOWER_IDENTIFIER.matches(stream.method)) {
                invalid("streams[$index]", message = "Stream names and methods are invalid.")
            }
            if (!IDENTIFIER.matches(stream.requestType) || !IDENTIFIER.matches(stream.chunkType)) {
                invalid("streams[$index]", message = "A stream request and chunk type must be identifiers.")
            }
            if (stream.capacity !in 1..65536) {
                invalid("streams[$index].capacity", stream.capacity.toString(), message = "A stream capacity must be between 1 and 65536.")
            }
            if (stream.status != KWebElectronMappingStatus.ADAPTER ||
                stream.adapter != KWebElectronAdapterKind.NAMED_APPLICATION_STREAM
            ) {
                invalid("streams[$index]", message = "Only named application stream adapters are publishable.")
            }
            val policy = stream.policy
                ?: invalid("streams[$index]", message = "A stream adapter must declare its policy.")
            if (policy.rendererGrant == null || policy.requiresUserGesture || policy.requiresOsConsent) {
                invalid("streams[$index]", message = "A named application stream must declare a renderer grant and explicit native policy.")
            }
        }

        requireUnique(manifest.windows.map { it.id }, "window")
        requireUnique(manifest.profiles.map { it.id }, "profile")
        requireUnique(manifest.lifecycleEvents.map { it.event }, "lifecycle event")
        requireUnique(manifest.nodeDependencies.map { it.name }, "Node dependency")
        val profiles = manifest.profiles.associateBy { it.id }
        if (manifest.rendererProfile !in profiles) invalid("rendererProfile", message = "The renderer must reference an explicitly declared profile.")
        val windows = manifest.windows.associateBy { it.id }
        if (manifest.windows.isNotEmpty() && manifest.windows.count { it.isMainWindow } != 1) invalid("windows", message = "Declare exactly one main window.")
        manifest.windows.forEachIndexed { index, window ->
            if (!IDENTIFIER.matches(window.id) || window.title.isBlank()) invalid("windows[$index]", message = "Window id and title must be valid.")
            if (window.profile !in profiles) invalid("windows[$index].profile", message = "Window references an undeclared profile.")
            if (window.isModal && window.parentWindowId == null) invalid("windows[$index].parentWindowId", message = "A modal window must declare its parent.")
            if (window.isMainWindow && (window.parentWindowId != null || window.isModal)) invalid("windows[$index]", message = "The main window must be a non-modal root.")
            val visited = mutableSetOf(window.id)
            var parent = window.parentWindowId
            while (parent != null) {
                if (!visited.add(parent)) invalid("windows[$index].parentWindowId", message = "Window ownership must be acyclic.")
                val owner = windows[parent] ?: invalid("windows[$index].parentWindowId", message = "Window parent is undeclared.")
                parent = owner.parentWindowId
            }
        }
        manifest.profiles.forEachIndexed { index, profile ->
            if (!IDENTIFIER.matches(profile.id)) invalid("profiles[$index].id", message = "Profile id is invalid.")
            if (profile.isPersistent) validateRelativePath(profile.storagePath ?: invalid("profiles[$index].storagePath", message = "A persistent profile requires an explicit storage directory."), "profiles[$index].storagePath")
            else if (profile.storagePath != null) invalid("profiles[$index].storagePath", message = "An in-memory profile cannot declare persistent storage.")
        }
        val storage = manifest.profiles.mapNotNull { it.storagePath?.lowercase() }
        storage.forEachIndexed { index, path ->
            if (storage.drop(index + 1).any { it == path || it.startsWith("$path/") || path.startsWith("$it/") }) invalid("profiles", message = "Profile storage directories must be isolated on every target.")
        }
        manifest.lifecycleEvents.forEachIndexed { index, event ->
            if (!Regex("[a-z][a-z-]*").matches(event.event) || event.hostTarget.isBlank()) invalid("lifecycleEvents[$index]", message = "Lifecycle events require a stable name and an explicit host target.")
            if (event.status !in setOf(KWebElectronMappingStatus.REWRITE, KWebElectronMappingStatus.UNSUPPORTED)) invalid("lifecycleEvents[$index].status", message = "Application lifecycle adapters are not implemented; declare REWRITE or UNSUPPORTED.")
        }
        manifest.nodeDependencies.forEachIndexed { index, dep ->
            if (dep.name.isBlank()) invalid("nodeDependencies[$index].name", message = "Node dependency name is required.")
            if (dep.status !in setOf(KWebElectronMappingStatus.REWRITE, KWebElectronMappingStatus.UNSUPPORTED)) invalid("nodeDependencies[$index].status", message = "Node/native dependencies have no published automatic adapter.")
            if (dep.replacementServiceId != null && dep.replacementServiceId !in services) invalid("nodeDependencies[$index].replacementServiceId", message = "A proposed replacement must reference a declared versioned service.")
        }
    }

    public fun blockingReasons(manifest: KWebElectronManifest): List<String> {
        validate(manifest)
        return buildList {
            manifest.electronImports.filter { it.status in setOf(KWebElectronMappingStatus.REWRITE, KWebElectronMappingStatus.UNSUPPORTED) }.forEach { add("manifest-import:${it.module}#${it.symbol}:${it.status}") }
            manifest.channels.filter { it.status != KWebElectronMappingStatus.ADAPTER }.forEach { add("manifest-channel:${it.name}:${it.status}") }
            manifest.preloadMethods.filter { it.status != KWebElectronMappingStatus.ADAPTER }.forEach { add("manifest-preload:${it.name}:${it.status}") }
            manifest.lifecycleEvents.forEach { add("manifest-lifecycle:${it.event}:${it.status}") }
            manifest.nodeDependencies.forEach { add("manifest-dependency:${it.name}:${it.status}") }
            manifest.windows.filter { it.isModal || it.parentWindowId != null }.forEach { add("manifest-window-hierarchy:${it.id}:rfc-0007-unimplemented") }
            manifest.profiles.filter { !it.isPersistent }.forEach { add("manifest-profile:${it.id}:ephemeral-unimplemented") }
        }.sorted()
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
