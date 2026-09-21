package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

@Serializable
internal data class KWebApplicationManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val displayName: String,
    val productVersion: String,
    val publisher: String,
    val mainExecutable: String,
    val helperExecutables: List<String>,
    val icons: List<KWebApplicationIcon>,
    val protocols: List<KWebApplicationProtocol>,
    val fileTypes: List<KWebApplicationFileType>,
    val targets: Map<String, KWebApplicationTarget>,
    val capabilities: List<String>,
    val providerResources: List<KWebApplicationProviderResource>,
    val update: KWebApplicationUpdate,
)

@Serializable
internal data class KWebApplicationIcon(
    val path: String,
    val kind: String,
)

@Serializable
internal data class KWebApplicationProtocol(
    val scheme: String,
    val role: String,
)

@Serializable
internal data class KWebApplicationFileType(
    val extension: String,
    val mimeType: String,
    val description: String,
)

@Serializable
internal data class KWebApplicationTarget(
    val minimumOs: String,
    val format: KWebApplicationPackageFormat,
    val bundleId: String? = null,
    val aumid: String? = null,
    val desktopId: String? = null,
)

@Serializable
internal enum class KWebApplicationPackageFormat {
    @kotlinx.serialization.SerialName("macos-app-zip")
    MACOS_APP_ZIP,

    @kotlinx.serialization.SerialName("windows-msix")
    WINDOWS_MSIX,

    @kotlinx.serialization.SerialName("linux-deb")
    LINUX_DEB,
}

@Serializable
internal data class KWebApplicationProviderResource(
    val id: String,
    val version: String,
    val path: String,
)

@Serializable
internal data class KWebApplicationUpdate(
    val channel: String,
    val keyId: String,
)

internal object KWebApplicationManifestCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(manifest: KWebApplicationManifest): ByteArray =
        (json.encodeToString(KWebApplicationManifest.serializer(), canonicalize(manifest)) + "\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): KWebApplicationManifest {
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.manifest.encoding-invalid",
                message = "The application manifest is not valid UTF-8.",
                cause = error,
            )
        }
        val manifest = try {
            json.decodeFromString(KWebApplicationManifest.serializer(), text)
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.manifest.json-invalid",
                message = "The application manifest is not strict schema JSON.",
                cause = error,
            )
        }
        KWebApplicationManifestContract.validate(manifest)
        applicationPackageRequire(
            bytes.contentEquals(encode(manifest)),
            code = "application.manifest.non-canonical",
            details = mapOf(
                "actualSha256" to sha256(bytes),
                "expectedSha256" to sha256(encode(manifest)),
            ),
            message = "The application manifest is not the canonical UTF-8 encoding.",
        )
        return canonicalize(manifest)
    }

    private fun canonicalize(manifest: KWebApplicationManifest): KWebApplicationManifest =
        manifest.copy(
            helperExecutables = manifest.helperExecutables.sortedWith(KWebApplicationManifestContract.utf8Comparator),
            icons = manifest.icons.sortedWith(compareBy(KWebApplicationIcon::kind, KWebApplicationIcon::path)),
            protocols = manifest.protocols.sortedWith(compareBy(KWebApplicationProtocol::scheme, KWebApplicationProtocol::role)),
            fileTypes = manifest.fileTypes.sortedWith(compareBy(KWebApplicationFileType::extension, KWebApplicationFileType::mimeType)),
            targets = manifest.targets.toSortedMap(KWebApplicationManifestContract.utf8Comparator),
            capabilities = manifest.capabilities.sortedWith(KWebApplicationManifestContract.utf8Comparator),
            providerResources = manifest.providerResources.sortedWith(
                compareBy(KWebApplicationProviderResource::id, KWebApplicationProviderResource::version, KWebApplicationProviderResource::path),
            ),
        )
}

internal object KWebApplicationManifestLoader {
    fun load(path: Path): KWebApplicationManifest {
        applicationPackageRequire(
            path.isAbsolute && path == path.normalize(),
            code = "application.manifest.path-invalid",
            details = mapOf("path" to path.toString()),
            message = "The application manifest path must be absolute and normalized.",
        )
        applicationPackageRequire(
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
            code = "application.manifest.file-invalid",
            details = mapOf("path" to path.toString()),
            message = "The application manifest must be a regular non-symbolic-link file.",
        )
        return KWebApplicationManifestCodec.decode(
            try {
                Files.readAllBytes(path)
            } catch (error: Exception) {
                applicationPackageFailure(
                    code = "application.manifest.read-failed",
                    details = mapOf("path" to path.toString()),
                    message = "Unable to read the application manifest.",
                    cause = error,
                )
            },
        )
    }
}

internal object KWebApplicationManifestContract {
    const val SCHEMA_VERSION: Int = 1
    private val APPLICATION_ID = Regex(
        "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)+",
    )
    private val PRODUCT_VERSION = Regex("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")
    private val IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9._-]{0,127}")
    private val LOWER_IDENTIFIER = Regex("[a-z][a-z0-9._-]{0,127}")
    private val SCHEME = Regex("[a-z][a-z0-9+.-]{1,31}")
    private val EXTENSION = Regex("\\.[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
    private val MIME = Regex("[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val PORTABLE_PATH = Regex("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}")
    private val PACKAGE_FORMATS: Map<String, KWebApplicationPackageFormat> = mapOf(
        "macos" to KWebApplicationPackageFormat.MACOS_APP_ZIP,
        "windows" to KWebApplicationPackageFormat.WINDOWS_MSIX,
        "linux" to KWebApplicationPackageFormat.LINUX_DEB,
    )

    val utf8Comparator: Comparator<String> = Comparator { left, right ->
        val leftBytes = left.toByteArray(StandardCharsets.UTF_8)
        val rightBytes = right.toByteArray(StandardCharsets.UTF_8)
        val shared = minOf(leftBytes.size, rightBytes.size)
        repeat(shared) { index ->
            val difference = (leftBytes[index].toInt() and 0xff) - (rightBytes[index].toInt() and 0xff)
            if (difference != 0) return@Comparator difference
        }
        leftBytes.size - rightBytes.size
    }

    fun validate(manifest: KWebApplicationManifest) {
        applicationPackageRequire(
            manifest.schemaVersion == SCHEMA_VERSION,
            code = "application.manifest.schema-unsupported",
            details = mapOf("schemaVersion" to manifest.schemaVersion.toString()),
            message = "Only application manifest schema version $SCHEMA_VERSION is supported.",
        )
        applicationPackageRequire(
            APPLICATION_ID.matches(manifest.applicationId),
            code = "application.manifest.application-id-invalid",
            message = "The application id must be a safe reverse-DNS identifier.",
        )
        validateText(manifest.displayName, "displayName", 256)
        applicationPackageRequire(
            PRODUCT_VERSION.matches(manifest.productVersion),
            code = "application.manifest.product-version-invalid",
            message = "The product version is not a canonical package version.",
        )
        validateText(manifest.publisher, "publisher", 256)
        validateExecutable(manifest.mainExecutable, "mainExecutable")
        applicationPackageRequire(
            manifest.helperExecutables.distinct().size == manifest.helperExecutables.size,
            code = "application.manifest.helper-duplicate",
            message = "The application manifest contains duplicate helper executables.",
        )
        manifest.helperExecutables.forEach { validateExecutable(it, "helperExecutable") }
        manifest.icons.forEachIndexed { index, icon ->
            validatePortablePath(icon.path, "icons[$index].path")
            applicationPackageRequire(
                IDENTIFIER.matches(icon.kind),
                code = "application.manifest.icon-kind-invalid",
                details = mapOf("kind" to icon.kind),
                message = "An application icon kind is invalid.",
            )
        }
        applicationPackageRequire(
            manifest.protocols.map { it.scheme }.distinct().size == manifest.protocols.size,
            code = "application.manifest.protocol-duplicate",
            message = "The application manifest contains duplicate protocol schemes.",
        )
        manifest.protocols.forEach { protocol ->
            applicationPackageRequire(
                SCHEME.matches(protocol.scheme) && protocol.scheme == protocol.scheme.lowercase(),
                code = "application.manifest.protocol-invalid",
                details = mapOf("scheme" to protocol.scheme),
                message = "A protocol scheme is invalid or not lowercase.",
            )
            applicationPackageRequire(
                IDENTIFIER.matches(protocol.role),
                code = "application.manifest.protocol-role-invalid",
                message = "A protocol role is invalid.",
            )
        }
        applicationPackageRequire(
            manifest.fileTypes.map { it.extension }.distinct().size == manifest.fileTypes.size,
            code = "application.manifest.file-type-duplicate",
            message = "The application manifest contains duplicate file extensions.",
        )
        manifest.fileTypes.forEach { fileType ->
            applicationPackageRequire(
                EXTENSION.matches(fileType.extension) && fileType.extension == fileType.extension.lowercase(),
                code = "application.manifest.file-extension-invalid",
                details = mapOf("extension" to fileType.extension),
                message = "A file extension is invalid or not lowercase.",
            )
            applicationPackageRequire(
                MIME.matches(fileType.mimeType) && fileType.mimeType == fileType.mimeType.lowercase(),
                code = "application.manifest.mime-type-invalid",
                details = mapOf("mimeType" to fileType.mimeType),
                message = "A MIME type is invalid or not lowercase.",
            )
            validateText(fileType.description, "fileType.description", 256)
        }

        val expectedTargets = KWebTarget.supported.mapTo(sortedSetOf(utf8Comparator), KWebTarget::id)
        applicationPackageRequire(
            manifest.targets.keys == expectedTargets,
            code = "application.manifest.target-set-invalid",
            details = mapOf("expected" to expectedTargets.joinToString(), "actual" to manifest.targets.keys.joinToString()),
            message = "The application manifest must declare every supported target exactly once.",
        )
        manifest.targets.forEach { (targetId, targetSpec) ->
            val target = KWebTarget.parse(targetId)
            applicationPackageRequire(
                targetSpec.minimumOs.isNotBlank() && targetSpec.minimumOs.length <= 64 &&
                    targetSpec.minimumOs.all { it.isLetterOrDigit() || it in ".-_" },
                code = "application.manifest.minimum-os-invalid",
                details = mapOf("target" to targetId),
                message = "A target minimum OS value is invalid.",
            )
            applicationPackageRequire(
                targetSpec.format == PACKAGE_FORMATS.getValue(target.operatingSystem.id),
                code = "application.manifest.package-format-invalid",
                details = mapOf("target" to targetId, "format" to targetSpec.format.name),
                message = "A target package format does not match its operating system.",
            )
            when (target.operatingSystem.id) {
                "macos" -> requireIdentity(targetSpec.bundleId, manifest.applicationId, "bundleId", targetId)
                "windows" -> requireIdentity(targetSpec.aumid, manifest.applicationId, "aumid", targetId)
                "linux" -> requireIdentity(targetSpec.desktopId, "${manifest.applicationId}.desktop", "desktopId", targetId)
            }
        }
        validateIdentifiers(manifest.capabilities, "capability")
        applicationPackageRequire(
            manifest.capabilities.distinct().size == manifest.capabilities.size,
            code = "application.manifest.capability-duplicate",
            message = "The application manifest contains duplicate capabilities.",
        )
        manifest.providerResources.forEach { resource ->
            applicationPackageRequire(
                IDENTIFIER.matches(resource.id),
                code = "application.manifest.provider-id-invalid",
                details = mapOf("id" to resource.id),
                message = "A provider resource id is invalid.",
            )
            applicationPackageRequire(
                PRODUCT_VERSION.matches(resource.version),
                code = "application.manifest.provider-version-invalid",
                details = mapOf("id" to resource.id),
                message = "A provider resource version is invalid.",
            )
            validatePortablePath(resource.path, "providerResources.path")
        }
        applicationPackageRequire(
            manifest.providerResources.map { it.id }.distinct().size == manifest.providerResources.size,
            code = "application.manifest.provider-duplicate",
            message = "The application manifest contains duplicate provider resources.",
        )
        applicationPackageRequire(
            IDENTIFIER.matches(manifest.update.channel),
            code = "application.manifest.update-channel-invalid",
            message = "The update channel is invalid.",
        )
        applicationPackageRequire(
            manifest.update.keyId.isEmpty() || SHA256.matches(manifest.update.keyId),
            code = "application.manifest.update-key-invalid",
            message = "The update key id must be empty or a lowercase SHA-256 digest.",
        )
    }

    private fun validateIdentifiers(values: List<String>, field: String) {
        values.forEach { value ->
            applicationPackageRequire(
                IDENTIFIER.matches(value),
                code = "application.manifest.identifier-invalid",
                details = mapOf("field" to field, "value" to value),
                message = "An application manifest identifier is invalid.",
            )
        }
    }

    private fun validateExecutable(value: String, field: String) {
        applicationPackageRequire(
            value.isNotBlank() && value.length <= 128 &&
                value.none { it in "\\/:*?\"<>|" || it.code < 0x20 } &&
                value != "." && value != "..",
            code = "application.manifest.executable-invalid",
            details = mapOf("field" to field),
            message = "An executable name is invalid.",
        )
    }

    private fun validatePortablePath(value: String, field: String) {
        applicationPackageRequire(
            PORTABLE_PATH.matches(value) &&
                !value.startsWith('/') &&
                value.split('/').all { it != "." && it != ".." && it.isNotEmpty() },
            code = "application.manifest.path-invalid",
            details = mapOf("field" to field, "path" to value),
            message = "A manifest path must be a relative portable path.",
        )
    }

    private fun requireIdentity(value: String?, expected: String, field: String, target: String) {
        applicationPackageRequire(
            value == expected,
            code = "application.manifest.identity-mismatch",
            details = mapOf("target" to target, "field" to field, "expected" to expected, "actual" to (value ?: "")),
            message = "A target package identity does not match the application id.",
        )
    }

    private fun validateText(value: String, field: String, maximum: Int) {
        applicationPackageRequire(
            value.isNotBlank() && value.length <= maximum && value.none { it == '\u0000' || it.code < 0x20 && it !in "\t\n\r" },
            code = "application.manifest.text-invalid",
            details = mapOf("field" to field),
            message = "An application manifest text field is invalid.",
        )
    }
}
