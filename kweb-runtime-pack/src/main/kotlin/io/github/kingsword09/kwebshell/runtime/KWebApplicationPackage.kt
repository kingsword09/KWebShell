package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.ar.ArArchiveEntry
import org.apache.commons.compress.archivers.ar.ArArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.gzip.GzipParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.Signature
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.Locale
import javax.imageio.ImageIO

internal class KWebApplicationPackageException(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

internal fun applicationPackageFailure(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
): Nothing = throw KWebApplicationPackageException(code, details, message, cause)

internal fun applicationPackageRequire(
    condition: Boolean,
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
) {
    if (!condition) applicationPackageFailure(code, details, message)
}

@Serializable
internal enum class KWebApplicationSigningMode {
    TEST,
    RELEASE,
}

@Serializable
internal data class KWebApplicationPlatformSignature(
    val schemaVersion: Int,
    val target: String,
    val format: KWebApplicationPackageFormat,
    val mode: KWebApplicationSigningMode,
    val identity: String,
    val signer: String,
    val signatureStatus: String,
    val notarizationStatus: String,
    val registrationDigest: String,
)

@Serializable
internal data class KWebApplicationPackagedState(
    val schemaVersion: Int,
    val applicationId: String,
    val target: String,
    val productVersion: String,
    val manifestSha256: String,
    val isPackaged: Boolean,
)

@Serializable
internal data class KWebApplicationRegistration(
    val schemaVersion: Int,
    val target: String,
    val identity: String,
    val protocols: List<KWebApplicationProtocol>,
    val fileTypes: List<KWebApplicationFileType>,
)

@Serializable
internal data class KWebApplicationCapabilitiesReport(
    val schemaVersion: Int,
    val target: String,
    val capabilities: List<String>,
    val providerResources: List<KWebApplicationProviderResource>,
)

@Serializable
internal data class KWebApplicationSbom(
    val schemaVersion: Int,
    val product: String,
    val applicationId: String,
    val target: String,
    val productVersion: String,
    val runtimeReleaseSha256: String,
    val runtimeTreeSha256: String,
    val licenses: List<String>,
)

@Serializable
internal data class KWebApplicationPackageManifest(
    val schemaVersion: Int,
    val applicationId: String,
    val target: String,
    val productVersion: String,
    val format: KWebApplicationPackageFormat,
    val manifestSha256: String,
    val runtimeReleaseSha256: String,
    val platformSignatureSha256: String,
    val platformMetadataSha256: Map<String, String>,
)

@Serializable
internal data class KWebApplicationPackageSignatureStatement(
    val schemaVersion: Int,
    val applicationId: String,
    val target: String,
    val productVersion: String,
    val format: KWebApplicationPackageFormat,
    val entrySha256: Map<String, String>,
)

internal data class KWebApplicationPackageBuildRequest(
    val applicationManifest: Path,
    val applicationAssetRoot: Path,
    val runtimeRelease: Path,
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
    val trustedPublicKey: Path,
    val packageSigningPrivateKey: Path,
    val platformSignature: KWebApplicationPlatformSignature,
    val outputPackage: Path,
)

internal data class KWebApplicationPackageBuildResult(
    val packagePath: Path,
    val packageSha256: String,
    val manifestSha256: String,
    val runtimeReleaseSha256: String,
    val platformSignature: KWebApplicationPlatformSignature,
)

internal data class KWebApplicationPackageVerificationRequest(
    val applicationPackage: Path,
    val applicationManifest: Path,
    val applicationAssetRoot: Path,
    val catalog: CefRuntimeCatalog,
    val target: KWebTarget,
    val productVersion: String,
    val trustedPublicKey: Path,
)

internal data class KWebApplicationPackageVerificationResult(
    val packageSha256: String,
    val manifest: KWebApplicationManifest,
    val platformSignature: KWebApplicationPlatformSignature,
    val runtimeReleaseSha256: String,
)

internal object KWebApplicationPlatformSignatureCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    fun encode(signature: KWebApplicationPlatformSignature): ByteArray =
        (json.encodeToString(KWebApplicationPlatformSignature.serializer(), signature) + "\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): KWebApplicationPlatformSignature {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.platform-signature-encoding-invalid",
                message = "The platform signature record is not valid UTF-8.",
                cause = error,
            )
        }
        val signature = try {
            json.decodeFromString(KWebApplicationPlatformSignature.serializer(), text)
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.platform-signature-json-invalid",
                message = "The platform signature record is not strict schema JSON.",
                cause = error,
            )
        }
        applicationPackageRequire(
            bytes.contentEquals(encode(signature)),
            code = "application.package.platform-signature-non-canonical",
            message = "The platform signature record is not canonical JSON.",
        )
        return signature
    }
}

internal object KWebApplicationPackageAssembler {
    fun build(request: KWebApplicationPackageBuildRequest): KWebApplicationPackageBuildResult {
        val manifest = KWebApplicationManifestLoader.load(request.applicationManifest)
        KWebApplicationManifestContract.validate(manifest)
        applicationPackageRequire(
            manifest.productVersion == request.productVersion,
            code = "application.package.product-version-mismatch",
            message = "The application manifest version does not match the package request.",
        )
        val targetSpec = manifest.targets[request.target.id]
            ?: applicationPackageFailure(
                code = "application.package.target-missing",
                details = mapOf("target" to request.target.id),
                message = "The application manifest does not declare the requested target.",
            )
        applicationPackageRequire(
            request.target.operatingSystem.id != "windows" || request.target.id == "windows-x64",
            code = "application.package.windows-target-unsupported",
            details = mapOf("target" to request.target.id),
            message = "Windows MSIX packaging currently supports the pinned x64 JRE only; no Windows ARM64 fallback is provided.",
        )
        validateOutputPath(request.outputPackage, targetSpec.format)
        validatePlatformSignature(manifest, request.target, targetSpec, request.platformSignature)

        val release = KWebRuntimeReleaseVerifier.verify(
            KWebRuntimeReleaseVerificationRequest(
                pack = request.runtimeRelease,
                catalog = request.catalog,
                target = request.target,
                productVersion = request.productVersion,
                trustedPublicKey = request.trustedPublicKey,
            ),
        )
        val manifestBytes = KWebApplicationManifestCodec.encode(manifest)
        val manifestSha256 = sha256(manifestBytes)
        val runtimeReleaseDigest = KWebRuntimeReleaseFileIO.digest(request.runtimeRelease)
        val signatureBytes = KWebApplicationPlatformSignatureCodec.encode(request.platformSignature)
        validateApplicationAssetRoot(request.applicationAssetRoot)
        val assetSnapshot = applicationAssetSnapshot(manifest, request.target, request.applicationAssetRoot)
        val platformEntries = platformMetadataEntries(
            manifest,
            request.target,
            targetSpec,
            request.applicationAssetRoot,
        )
        requireApplicationAssetSnapshot(assetSnapshot)
        val platformMetadataSha256 = platformEntries.mapValues { (_, bytes) -> sha256(bytes) }
        val entries = linkedMapOf(
            APPLICATION_MANIFEST_PATH to manifestBytes,
            PACKAGED_STATE_PATH to canonicalBytes(
                KWebApplicationPackagedState(
                    schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                    applicationId = manifest.applicationId,
                    target = request.target.id,
                    productVersion = manifest.productVersion,
                    manifestSha256 = manifestSha256,
                    isPackaged = true,
                ),
                KWebApplicationPackagedState.serializer(),
            ),
            REGISTRATION_PATH to canonicalBytes(
                registration(manifest, request.target, targetSpec),
                KWebApplicationRegistration.serializer(),
            ),
            CAPABILITIES_PATH to canonicalBytes(
                KWebApplicationCapabilitiesReport(
                    schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                    target = request.target.id,
                    capabilities = manifest.capabilities.sortedWith(KWebApplicationManifestContract.utf8Comparator),
                    providerResources = manifest.providerResources.sortedWith(
                        compareBy(KWebApplicationProviderResource::id, KWebApplicationProviderResource::version, KWebApplicationProviderResource::path),
                    ),
                ),
                KWebApplicationCapabilitiesReport.serializer(),
            ),
            SBOM_PATH to canonicalBytes(
                KWebApplicationSbom(
                    schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                    product = APPLICATION_PRODUCT,
                    applicationId = manifest.applicationId,
                    target = request.target.id,
                    productVersion = manifest.productVersion,
                    runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
                    runtimeTreeSha256 = release.manifest.payload.treeSha256,
                    licenses = listOf("runtime/release.pack.zip"),
                ),
                KWebApplicationSbom.serializer(),
            ),
            PLATFORM_SIGNATURE_PATH to signatureBytes,
            PACKAGE_MANIFEST_PATH to canonicalBytes(
                KWebApplicationPackageManifest(
                    schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                    applicationId = manifest.applicationId,
                    target = request.target.id,
                    productVersion = manifest.productVersion,
                    format = targetSpec.format,
                    manifestSha256 = manifestSha256,
                    runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
                    platformSignatureSha256 = sha256(signatureBytes),
                    platformMetadataSha256 = platformMetadataSha256,
                ),
                KWebApplicationPackageManifest.serializer(),
            ),
        )
        entries.putAll(platformEntries)
        val signingPrivateKey = KWebRuntimeReleaseKeys.loadPrivateKey(request.packageSigningPrivateKey)
        val trustedPublicKey = KWebRuntimeReleaseKeys.loadPublicKey(request.trustedPublicKey)
        KWebRuntimeReleaseKeys.requireMatchingPair(signingPrivateKey, trustedPublicKey)
        val signatureStatement = KWebApplicationPackageSignatureStatement(
            schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
            applicationId = manifest.applicationId,
            target = request.target.id,
            productVersion = request.productVersion,
            format = targetSpec.format,
            entrySha256 = packageEntryDigests(entries, runtimeReleaseDigest),
        )
        val signatureStatementBytes = canonicalBytes(
            signatureStatement,
            KWebApplicationPackageSignatureStatement.serializer(),
        )
        entries[PACKAGE_SIGNATURE_STATEMENT_PATH] = signatureStatementBytes
        entries[PACKAGE_SIGNATURE_PATH] = signPackage(signingPrivateKey, signatureStatementBytes)
        val temporary = createSiblingTemporary(request.outputPackage)
        var primaryFailure: Throwable? = null
        try {
            writePackage(
                output = temporary,
                entries = entries,
                runtimeRelease = request.runtimeRelease,
                runtimeReleaseDigest = runtimeReleaseDigest,
                manifest = manifest,
                target = request.target,
                format = targetSpec.format,
            )
            val verificationRequest = KWebApplicationPackageVerificationRequest(
                    applicationPackage = temporary,
                    applicationManifest = request.applicationManifest,
                    applicationAssetRoot = request.applicationAssetRoot,
                    catalog = request.catalog,
                    target = request.target,
                    productVersion = request.productVersion,
                    trustedPublicKey = request.trustedPublicKey,
            )
            if (request.target.operatingSystem.id == "windows") {
                KWebApplicationPackageVerifier.verifyWindowsMetadataArchive(
                    verificationRequest,
                    atomicBuildTemporary = true,
                )
            } else {
                KWebApplicationPackageVerifier.verify(verificationRequest)
            }
            publishAtomically(temporary, request.outputPackage)
        } catch (error: Throwable) {
            primaryFailure = error
            throw when (error) {
                is KWebApplicationPackageException -> error
                else -> KWebApplicationPackageException(
                    code = "application.package.write-failed",
                    details = mapOf("path" to request.outputPackage.toString()),
                    message = "Unable to build and verify the application package.",
                    cause = error,
                )
            }
        } finally {
            if (primaryFailure != null) Files.deleteIfExists(temporary)
        }
        return KWebApplicationPackageBuildResult(
            packagePath = request.outputPackage,
            packageSha256 = sha256(request.outputPackage),
            manifestSha256 = manifestSha256,
            runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
            platformSignature = request.platformSignature,
        )
    }

    private fun registration(
        manifest: KWebApplicationManifest,
        target: KWebTarget,
        targetSpec: KWebApplicationTarget,
    ): KWebApplicationRegistration = applicationRegistrationRecord(manifest, target, targetSpec)
}

internal fun applicationRegistrationRecord(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    targetSpec: KWebApplicationTarget,
): KWebApplicationRegistration = KWebApplicationRegistration(
    schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
    target = target.id,
    identity = platformIdentity(manifest, targetSpec),
    protocols = manifest.protocols,
    fileTypes = manifest.fileTypes,
)

internal fun applicationRegistrationDigest(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
): String {
    val targetSpec = manifest.targets[target.id]
        ?: applicationPackageFailure(
            code = "application.package.target-missing",
            details = mapOf("target" to target.id),
            message = "The application manifest does not declare the requested target.",
        )
    return sha256(
        canonicalBytes(
            applicationRegistrationRecord(manifest, target, targetSpec),
            KWebApplicationRegistration.serializer(),
        ),
    )
}

internal object KWebApplicationPackageVerifier {
    fun verify(request: KWebApplicationPackageVerificationRequest): KWebApplicationPackageVerificationResult =
        verify(request, allowWindowsMetadataArchive = false)

    internal fun verifyWindowsMetadataArchive(
        request: KWebApplicationPackageVerificationRequest,
        atomicBuildTemporary: Boolean = false,
    ): KWebApplicationPackageVerificationResult {
        applicationPackageRequire(
            request.target.id == "windows-x64" &&
                (atomicBuildTemporary || request.applicationPackage.fileName.toString().endsWith(".zip", ignoreCase = true)),
            code = "application.package.windows-metadata-archive-invalid",
            details = mapOf("target" to request.target.id, "path" to request.applicationPackage.toString()),
            message = "Only the internal ZIP metadata archive for the Windows x64 provider can use this verifier.",
        )
        return verify(request, allowWindowsMetadataArchive = true)
    }

    private fun verify(
        request: KWebApplicationPackageVerificationRequest,
        allowWindowsMetadataArchive: Boolean,
    ): KWebApplicationPackageVerificationResult {
        validatePackagePath(request.applicationPackage)
        val manifest = KWebApplicationManifestLoader.load(request.applicationManifest)
        KWebApplicationManifestContract.validate(manifest)
        applicationPackageRequire(
            manifest.productVersion == request.productVersion,
            code = "application.package.product-version-mismatch",
            message = "The application manifest version does not match the package request.",
        )
        val targetSpec = manifest.targets[request.target.id]
            ?: applicationPackageFailure(
                code = "application.package.target-missing",
                details = mapOf("target" to request.target.id),
                message = "The application manifest does not declare the requested target.",
            )
        applicationPackageRequire(
            targetSpec.format != KWebApplicationPackageFormat.WINDOWS_MSIX || allowWindowsMetadataArchive,
            code = "application.package.windows-msix-sdk-verification-required",
            details = mapOf("target" to request.target.id),
            message = "Windows MSIX packages must be verified with the Windows SDK and installed-package checks; the internal metadata archive is not a distributable package.",
        )
        val entries = readEntries(request.applicationPackage, targetSpec.format)
        validateApplicationAssetRoot(request.applicationAssetRoot)
        val assetSnapshot = applicationAssetSnapshot(manifest, request.target, request.applicationAssetRoot)
        val platformEntries = platformMetadataEntries(
            manifest,
            request.target,
            targetSpec,
            request.applicationAssetRoot,
        )
        requireApplicationAssetSnapshot(assetSnapshot)
        val expectedNames = listOf(
            APPLICATION_MANIFEST_PATH,
            PACKAGED_STATE_PATH,
            REGISTRATION_PATH,
            CAPABILITIES_PATH,
            SBOM_PATH,
            PLATFORM_SIGNATURE_PATH,
            PACKAGE_MANIFEST_PATH,
            RUNTIME_RELEASE_PATH,
            PACKAGE_SIGNATURE_STATEMENT_PATH,
            PACKAGE_SIGNATURE_PATH,
        ).plus(platformEntries.keys).sortedWith(KWebApplicationManifestContract.utf8Comparator)
        applicationPackageRequire(
            entries.keys.toList() == expectedNames,
            code = "application.package.entries-invalid",
            message = "The application package does not contain the exact canonical entry set.",
        )
        val runtimeReleaseDigest = KWebRuntimeReleaseFileIO.digestFromBytes(entries.getValue(RUNTIME_RELEASE_PATH))
        val signatureStatement = decodeCanonical(
            entries.getValue(PACKAGE_SIGNATURE_STATEMENT_PATH),
            KWebApplicationPackageSignatureStatement.serializer(),
        )
        applicationPackageRequire(
            signatureStatement == KWebApplicationPackageSignatureStatement(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                applicationId = manifest.applicationId,
                target = request.target.id,
                productVersion = request.productVersion,
                format = targetSpec.format,
                entrySha256 = packageEntryDigests(entries, runtimeReleaseDigest),
            ),
            code = "application.package.signature-statement-invalid",
            message = "The application package signature statement does not match its entries.",
        )
        applicationPackageRequire(
            verifyPackageSignature(
                KWebRuntimeReleaseKeys.loadPublicKey(request.trustedPublicKey),
                entries.getValue(PACKAGE_SIGNATURE_STATEMENT_PATH),
                entries.getValue(PACKAGE_SIGNATURE_PATH),
            ),
            code = "application.package.signature-invalid",
            message = "The application package signature does not verify with the trusted key.",
        )
        val manifestBytes = entries.getValue(APPLICATION_MANIFEST_PATH)
        applicationPackageRequire(
            manifestBytes.contentEquals(KWebApplicationManifestCodec.encode(manifest)),
            code = "application.package.manifest-mismatch",
            message = "The package application manifest differs from the trusted source manifest.",
        )
        val manifestSha256 = sha256(manifestBytes)
        val packagedState = decodeCanonical(
            entries.getValue(PACKAGED_STATE_PATH),
            KWebApplicationPackagedState.serializer(),
        )
        applicationPackageRequire(
            packagedState == KWebApplicationPackagedState(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                applicationId = manifest.applicationId,
                target = request.target.id,
                productVersion = request.productVersion,
                manifestSha256 = manifestSha256,
                isPackaged = true,
            ),
            code = "application.package.packaged-state-invalid",
            message = "The immutable packaged-state fact does not match the package inputs.",
        )
        val registration = decodeCanonical(
            entries.getValue(REGISTRATION_PATH),
            KWebApplicationRegistration.serializer(),
        )
        applicationPackageRequire(
            registration == KWebApplicationRegistration(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                target = request.target.id,
                identity = platformIdentity(manifest, targetSpec),
                protocols = manifest.protocols,
                fileTypes = manifest.fileTypes,
            ),
            code = "application.package.registration-invalid",
            message = "The package registration metadata does not match the application manifest.",
        )
        platformEntries.forEach { (path, expected) ->
            applicationPackageRequire(
                entries[path]?.contentEquals(expected) == true,
                code = "application.package.platform-metadata-invalid",
                details = mapOf("path" to path),
                message = "A target-specific platform registration record does not match the manifest.",
            )
        }
        val capabilities = decodeCanonical(
            entries.getValue(CAPABILITIES_PATH),
            KWebApplicationCapabilitiesReport.serializer(),
        )
        applicationPackageRequire(
            capabilities == KWebApplicationCapabilitiesReport(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                target = request.target.id,
                capabilities = manifest.capabilities.sortedWith(KWebApplicationManifestContract.utf8Comparator),
                providerResources = manifest.providerResources.sortedWith(
                    compareBy(KWebApplicationProviderResource::id, KWebApplicationProviderResource::version, KWebApplicationProviderResource::path),
                ),
            ),
            code = "application.package.capabilities-invalid",
            message = "The package capability audit does not match the application manifest.",
        )
        val nestedRelease = verifyNestedRuntimeRelease(entries.getValue(RUNTIME_RELEASE_PATH), request)
        val sbom = decodeCanonical(entries.getValue(SBOM_PATH), KWebApplicationSbom.serializer())
        applicationPackageRequire(
            sbom == KWebApplicationSbom(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                product = APPLICATION_PRODUCT,
                applicationId = manifest.applicationId,
                target = request.target.id,
                productVersion = request.productVersion,
                runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
                runtimeTreeSha256 = nestedRelease.manifest.payload.treeSha256,
                licenses = listOf("runtime/release.pack.zip"),
            ) && sbom.runtimeReleaseSha256 == runtimeReleaseDigest.sha256,
            code = "application.package.sbom-invalid",
            message = "The package SBOM does not match the nested runtime release.",
        )
        val platformSignature = decodeCanonical(
            entries.getValue(PLATFORM_SIGNATURE_PATH),
            KWebApplicationPlatformSignature.serializer(),
        )
        validatePlatformSignature(manifest, request.target, targetSpec, platformSignature)
        val packageManifest = decodeCanonical(
            entries.getValue(PACKAGE_MANIFEST_PATH),
            KWebApplicationPackageManifest.serializer(),
        )
        applicationPackageRequire(
            packageManifest == KWebApplicationPackageManifest(
                schemaVersion = APPLICATION_PACKAGE_SCHEMA_VERSION,
                applicationId = manifest.applicationId,
                target = request.target.id,
                productVersion = request.productVersion,
                format = targetSpec.format,
                manifestSha256 = manifestSha256,
                runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
                platformSignatureSha256 = sha256(entries.getValue(PLATFORM_SIGNATURE_PATH)),
                platformMetadataSha256 = platformEntries.mapValues { (path, _) -> sha256(entries.getValue(path)) },
            ),
            code = "application.package.manifest-record-invalid",
            message = "The package record does not match the canonical package entries.",
        )

        return KWebApplicationPackageVerificationResult(
            packageSha256 = sha256(request.applicationPackage),
            manifest = manifest,
            platformSignature = platformSignature,
            runtimeReleaseSha256 = runtimeReleaseDigest.sha256,
        )
    }

    private fun verifyNestedRuntimeRelease(
        bytes: ByteArray,
        request: KWebApplicationPackageVerificationRequest,
    ): KWebRuntimeReleaseVerificationResult {
        val temporaryRelease = Files.createTempFile("kweb-application-release-", ".zip")
        var primaryFailure: Throwable? = null
        try {
            Files.write(temporaryRelease, bytes)
            return KWebRuntimeReleaseVerifier.verify(
                KWebRuntimeReleaseVerificationRequest(
                    pack = temporaryRelease,
                    catalog = request.catalog,
                    target = request.target,
                    productVersion = request.productVersion,
                    trustedPublicKey = request.trustedPublicKey,
                ),
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                Files.deleteIfExists(temporaryRelease)
            } catch (cleanupError: Exception) {
                if (primaryFailure != null) primaryFailure.addSuppressed(cleanupError)
                else throw cleanupError
            }
        }
    }

    private fun readEntries(path: Path, format: KWebApplicationPackageFormat): Map<String, ByteArray> =
        if (format == KWebApplicationPackageFormat.LINUX_DEB) {
            readDebEntries(path)
        } else {
            readZipEntries(path)
        }

    private fun readZipEntries(path: Path): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        try {
            ZipFile.builder()
                .setPath(path)
                .setCharset(StandardCharsets.UTF_8)
                .setUseUnicodeExtraFields(false)
                .setIgnoreLocalFileHeader(false)
                .get()
                .use { zip ->
                    applicationPackageRequire(
                        zip.firstLocalFileHeaderOffset == 0L,
                        code = "application.package.prefix-invalid",
                        message = "The application package contains a preamble.",
                    )
                    val iterator = zip.entries.asSequence().toList()
                    iterator.forEach { entry ->
                        applicationPackageRequire(
                            !entry.isDirectory && entry.name !in entries,
                            code = "application.package.entry-invalid",
                            message = "The application package contains a duplicate or directory entry.",
                        )
                        applicationPackageRequire(
                            entry.unixMode == (UnixStat.FILE_FLAG or APPLICATION_PACKAGE_FILE_MODE) &&
                                entry.method == ZipArchiveOutputStream.STORED &&
                                entry.timeLocal == FIXED_TIMESTAMP,
                            code = "application.package.entry-non-canonical",
                            details = mapOf("name" to entry.name),
                            message = "An application package entry is not canonical.",
                        )
                        val bytes = zip.getInputStream(entry).use { input -> input.readBytes() }
                        applicationPackageRequire(
                            bytes.size.toLong() == entry.size && crc32(bytes) == entry.crc,
                            code = "application.package.entry-integrity-invalid",
                            details = mapOf("name" to entry.name),
                            message = "An application package entry failed its size or CRC check.",
                        )
                        entries[entry.name] = bytes
                    }
                    applicationPackageRequire(
                        entries.keys.toList() == entries.keys.sortedWith(KWebApplicationManifestContract.utf8Comparator),
                        code = "application.package.entry-order-invalid",
                        message = "Application package entries are not in UTF-8 lexical order.",
                    )
                }
        } catch (error: KWebApplicationPackageException) {
            throw error
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to read the application package.",
                cause = error,
            )
        }
        return entries
    }

    private fun readDebEntries(path: Path): Map<String, ByteArray> {
        val arEntries = linkedMapOf<String, ByteArray>()
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                ArArchiveInputStream(input).use { archive ->
                    while (true) {
                        val entry = archive.getNextArEntry() ?: break
                        applicationPackageRequire(
                            entry.name !in arEntries,
                            code = "application.package.deb-member-duplicate",
                            details = mapOf("name" to entry.name),
                            message = "The Debian package contains a duplicate member.",
                        )
                        arEntries[entry.name] = archive.readBytes()
                    }
                }
            }
            applicationPackageRequire(
                arEntries.keys == setOf("debian-binary", "control.tar.gz", "data.tar.gz") &&
                    arEntries.getValue("debian-binary").contentEquals("2.0\n".toByteArray(StandardCharsets.US_ASCII)),
                code = "application.package.deb-members-invalid",
                message = "The Debian package does not contain the canonical members.",
            )
            val entries = linkedMapOf<String, ByteArray>()
            GzipCompressorInputStream(ByteArrayInputStream(arEntries.getValue("data.tar.gz"))).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    while (true) {
                        val entry = tar.getNextTarEntry() ?: break
                        applicationPackageRequire(
                            !entry.isDirectory && entry.name.startsWith(DEB_DATA_ROOT),
                            code = "application.package.deb-data-entry-invalid",
                            details = mapOf("name" to entry.name),
                            message = "The Debian data archive contains an unsafe or non-regular entry.",
                        )
                        val name = entry.name.removePrefix(DEB_DATA_ROOT)
                        applicationPackageRequire(
                            name.isNotEmpty() && name.split('/').none { it.isEmpty() || it == "." || it == ".." },
                            code = "application.package.deb-data-path-invalid",
                            details = mapOf("name" to entry.name),
                            message = "The Debian data archive contains an unsafe path.",
                        )
                        applicationPackageRequire(
                            name !in entries,
                            code = "application.package.deb-data-duplicate",
                            details = mapOf("name" to name),
                            message = "The Debian data archive contains a duplicate application entry.",
                        )
                        entries[name] = tar.readBytes()
                    }
                }
            }
            applicationPackageRequire(
                entries.keys.toList() == entries.keys.sortedWith(KWebApplicationManifestContract.utf8Comparator),
                code = "application.package.entry-order-invalid",
                message = "Debian application entries are not in UTF-8 lexical order.",
            )
            return entries
        } catch (error: KWebApplicationPackageException) {
            throw error
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to read the Debian application package.",
                cause = error,
            )
        }
    }
}

private val canonicalJson = Json {
    encodeDefaults = true
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

private fun <T> canonicalBytes(value: T, serializer: kotlinx.serialization.KSerializer<T>): ByteArray =
    (canonicalJson.encodeToString(serializer, value) + "\n").toByteArray(StandardCharsets.UTF_8)

private fun <T> decodeCanonical(bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>): T {
    val text = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        applicationPackageFailure(
            code = "application.package.record-encoding-invalid",
            message = "An application package record is not valid UTF-8.",
            cause = error,
        )
    }
    val value = try {
        canonicalJson.decodeFromString(serializer, text)
    } catch (error: Exception) {
        applicationPackageFailure(
            code = "application.package.record-json-invalid",
            message = "An application package record is not strict schema JSON.",
            cause = error,
        )
    }
    applicationPackageRequire(
        bytes.contentEquals(canonicalBytes(value, serializer)),
        code = "application.package.record-non-canonical",
        message = "An application package record is not canonical JSON.",
    )
    return value
}

private fun validatePlatformSignature(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    targetSpec: KWebApplicationTarget,
    signature: KWebApplicationPlatformSignature,
) {
    applicationPackageRequire(
        signature.schemaVersion == APPLICATION_PACKAGE_SCHEMA_VERSION &&
            signature.target == target.id &&
            signature.format == targetSpec.format &&
            signature.identity == platformIdentity(manifest, targetSpec) &&
            signature.signer.isNotBlank() &&
            signature.signatureStatus == "VERIFIED" &&
            signature.registrationDigest == applicationRegistrationDigest(manifest, target),
        code = "application.package.platform-signature-invalid",
        details = mapOf("target" to target.id),
        message = "The platform signature facts are missing, stale, or do not match the application identity.",
    )
    val expectedNotarization = if (target.operatingSystem.id == "macos") {
        setOf("VERIFIED", "NOT_APPLICABLE")
    } else {
        setOf("NOT_APPLICABLE")
    }
    applicationPackageRequire(
        signature.notarizationStatus in expectedNotarization,
        code = "application.package.notarization-invalid",
        details = mapOf("target" to target.id),
        message = "The platform notarization fact is not valid for the selected target.",
    )
}

private fun platformIdentity(manifest: KWebApplicationManifest, targetSpec: KWebApplicationTarget): String =
    targetSpec.bundleId ?: targetSpec.packageIdentityName ?: targetSpec.desktopId
        ?: applicationPackageFailure(
            code = "application.package.identity-missing",
            message = "The target package identity is missing.",
        )

private fun platformMetadataEntries(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    targetSpec: KWebApplicationTarget,
    applicationAssetRoot: Path,
): Map<String, ByteArray> = when (target.operatingSystem.id) {
    "macos" -> mapOf(
        MACOS_INFO_PLIST_PATH to macosInfoPlist(manifest, targetSpec).toByteArray(StandardCharsets.UTF_8),
    )

    "windows" -> windowsMsixMetadataEntries(manifest, target, applicationAssetRoot)

    "linux" -> mapOf(
        LINUX_DESKTOP_PATH to linuxDesktopEntry(manifest, targetSpec).toByteArray(StandardCharsets.UTF_8),
        LINUX_MIME_PATH to linuxMimeInfo(manifest).toByteArray(StandardCharsets.UTF_8),
        LINUX_METAINFO_PATH to linuxMetainfo(manifest, targetSpec).toByteArray(StandardCharsets.UTF_8),
    )

    else -> applicationPackageFailure(
        code = "application.package.target-unsupported",
        details = mapOf("target" to target.id),
        message = "The application package has no provider for the requested target.",
    )
}

internal fun windowsMsixMetadataEntries(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    applicationAssetRoot: Path,
): Map<String, ByteArray> {
    applicationPackageRequire(
        target.id == "windows-x64",
        code = "application.package.windows-target-unsupported",
        details = mapOf("target" to target.id),
        message = "The pinned Temurin launcher currently supports Windows x64 MSIX only; Windows ARM64 has no matching JRE.",
    )
    val targetSpec = manifest.targets[target.id]
        ?: applicationPackageFailure(
            code = "application.package.target-missing",
            details = mapOf("target" to target.id),
            message = "The application manifest does not declare the requested target.",
        )
    return buildMap {
        put(
            WINDOWS_APPX_MANIFEST_PATH,
            windowsAppxManifest(manifest, targetSpec).toByteArray(StandardCharsets.UTF_8),
        )
        putAll(windowsIconEntries(manifest, applicationAssetRoot))
    }
}

private fun validateApplicationAssetRoot(root: Path) {
    applicationPackageRequire(
        root.isAbsolute && root == root.normalize() &&
            Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(root),
        code = "application.package.asset-root-invalid",
        details = mapOf("path" to root.toString()),
        message = "The application asset root must be an absolute, normalized, non-symbolic-link directory.",
    )
}

private fun applicationAssetSnapshot(
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    root: Path,
): Map<Path, ApplicationAssetSnapshot> = if (target.operatingSystem.id != "windows") {
    emptyMap()
} else {
    manifest.icons.associate { icon ->
        val path = safeApplicationAssetPath(root, icon.path)
        try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            applicationPackageRequire(
                attributes.size() in 24L..MAX_WINDOWS_ASSET_BYTES,
                code = "application.package.windows-icon-invalid",
                details = mapOf("path" to icon.path, "size" to attributes.size().toString()),
                message = "A Windows package PNG must be between 24 bytes and the configured asset-size limit.",
            )
            path to ApplicationAssetSnapshot(attributes = attributes, sha256 = sha256(path))
        } catch (error: KWebApplicationPackageException) {
            throw error
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.asset-file-invalid",
                details = mapOf("path" to path.toString()),
                message = "A declared application asset is missing or cannot be inspected.",
                cause = error,
            )
        }
    }
}

private fun requireApplicationAssetSnapshot(snapshot: Map<Path, ApplicationAssetSnapshot>) {
    snapshot.forEach { (path, before) ->
        val after = try {
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            if (!attributes.isRegularFile || attributes.size() !in 24L..MAX_WINDOWS_ASSET_BYTES) {
                applicationPackageFailure(
                    code = "application.package.asset-changed-during-build",
                    details = mapOf("path" to path.toString()),
                    message = "A declared Windows icon changed to an invalid file while its package was being verified.",
                )
            }
            ApplicationAssetSnapshot(attributes = attributes, sha256 = sha256(path))
        } catch (error: KWebApplicationPackageException) {
            throw error
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.asset-changed-during-build",
                details = mapOf("path" to path.toString()),
                message = "A declared application asset disappeared while its package was being verified.",
                cause = error,
            )
        }
        applicationPackageRequire(
            before.attributes.isRegularFile && after.attributes.isRegularFile &&
                (before.attributes.fileKey() == null || after.attributes.fileKey() == before.attributes.fileKey()) &&
                before.attributes.size() == after.attributes.size() &&
                before.attributes.lastModifiedTime() == after.attributes.lastModifiedTime() &&
                before.sha256 == after.sha256,
            code = "application.package.asset-changed-during-build",
            details = mapOf("path" to path.toString()),
            message = "A declared application asset changed during package construction or verification.",
        )
    }
}

private data class ApplicationAssetSnapshot(
    val attributes: BasicFileAttributes,
    val sha256: String,
)

private fun safeApplicationAssetPath(root: Path, relativePath: String): Path {
    val relative = Path.of(relativePath)
    val source = root.resolve(relative).normalize()
    applicationPackageRequire(
        !relative.isAbsolute && source.startsWith(root) && source != root,
        code = "application.package.asset-path-invalid",
        details = mapOf("path" to relativePath),
        message = "An application asset path escapes its declared root.",
    )
    val components = root.relativize(source)
    var cursor = root
    components.forEachIndexed { index, component ->
        cursor = cursor.resolve(component)
        applicationPackageRequire(
            !Files.isSymbolicLink(cursor) &&
                if (index == components.nameCount - 1) {
                    Files.isRegularFile(cursor, LinkOption.NOFOLLOW_LINKS)
                } else {
                    Files.isDirectory(cursor, LinkOption.NOFOLLOW_LINKS)
                },
            code = "application.package.asset-file-invalid",
            details = mapOf("path" to relativePath),
            message = "A declared application asset is missing, non-regular, or traverses a symbolic link.",
        )
    }
    return source
}

private fun macosInfoPlist(
    manifest: KWebApplicationManifest,
    targetSpec: KWebApplicationTarget,
): String = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    appendLine("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">")
    appendLine("<plist version=\"1.0\">")
    appendLine("<dict>")
    appendLine("  <key>CFBundleDisplayName</key><string>${xml(manifest.displayName)}</string>")
    appendLine("  <key>CFBundleExecutable</key><string>${xml(manifest.mainExecutable)}</string>")
    appendLine("  <key>CFBundleIdentifier</key><string>${xml(checkNotNull(targetSpec.bundleId))}</string>")
    appendLine("  <key>CFBundleName</key><string>${xml(manifest.displayName)}</string>")
    appendLine("  <key>CFBundlePackageType</key><string>APPL</string>")
    appendLine("  <key>CFBundleShortVersionString</key><string>${xml(manifest.productVersion)}</string>")
    appendLine("  <key>CFBundleVersion</key><string>${xml(manifest.productVersion)}</string>")
    appendLine("  <key>CFBundleURLTypes</key><array><dict>")
    appendLine("    <key>CFBundleURLName</key><string>${xml(manifest.applicationId)}</string>")
    appendLine("    <key>CFBundleURLSchemes</key><array>")
    manifest.protocols.forEach { protocol ->
        appendLine("      <string>${xml(protocol.scheme)}</string>")
    }
    appendLine("    </array>")
    appendLine("  </dict></array>")
    appendLine("  <key>CFBundleDocumentTypes</key><array>")
    manifest.fileTypes.forEach { fileType ->
        appendLine("    <dict><key>CFBundleTypeExtensions</key><array><string>${xml(fileType.extension.removePrefix("."))}</string></array>")
        appendLine("      <key>CFBundleTypeName</key><string>${xml(fileType.description)}</string>")
        appendLine("      <key>CFBundleTypeRole</key><string>Viewer</string></dict>")
    }
    appendLine("  </array>")
    appendLine("</dict>")
    appendLine("</plist>")
}

private fun windowsAppxManifest(
    manifest: KWebApplicationManifest,
    targetSpec: KWebApplicationTarget,
): String = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
    appendLine("<Package xmlns=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10\" xmlns:uap=\"http://schemas.microsoft.com/appx/manifest/uap/windows10\" xmlns:rescap=\"http://schemas.microsoft.com/appx/manifest/foundation/windows10/restrictedcapabilities\" IgnorableNamespaces=\"uap rescap\">")
    appendLine("  <Identity Name=\"${xml(checkNotNull(targetSpec.packageIdentityName))}\" Publisher=\"CN=${xml(manifest.publisher)}\" Version=\"${xml(appxVersion(manifest.productVersion))}\" ProcessorArchitecture=\"x64\" />")
    appendLine("  <Properties><DisplayName>${xml(manifest.displayName)}</DisplayName><PublisherDisplayName>${xml(manifest.publisher)}</PublisherDisplayName><Description>${xml(manifest.displayName)}</Description><Logo>Assets/StoreLogo.png</Logo></Properties>")
    appendLine("  <Dependencies><TargetDeviceFamily Name=\"Windows.Desktop\" MinVersion=\"${xml(appxMinimumOsVersion(targetSpec.minimumOs))}\" MaxVersionTested=\"10.0.26100.0\" /></Dependencies>")
    appendLine("  <Resources><Resource Language=\"en-us\" /></Resources>")
    appendLine("  <Applications><Application Id=\"${xml(manifest.mainExecutable)}\" Executable=\"${xml(manifest.mainExecutable)}.exe\" EntryPoint=\"Windows.FullTrustApplication\">")
    appendLine("    <uap:VisualElements DisplayName=\"${xml(manifest.displayName)}\" Description=\"${xml(manifest.displayName)}\" BackgroundColor=\"#071A3B\" Square44x44Logo=\"Assets/Square44x44Logo.png\" Square150x150Logo=\"Assets/Square150x150Logo.png\">")
    appendLine("      <uap:DefaultTile Square310x310Logo=\"Assets/Square310x310Logo.png\" Wide310x150Logo=\"Assets/Wide310x150Logo.png\" />")
    appendLine("    </uap:VisualElements>")
    appendLine("    <Extensions>")
    manifest.protocols.forEach { protocol ->
        appendLine("      <uap:Extension Category=\"windows.protocol\"><uap:Protocol Name=\"${xml(protocol.scheme)}\"><uap:DisplayName>${xml(manifest.displayName)}</uap:DisplayName></uap:Protocol></uap:Extension>")
    }
    manifest.fileTypes.forEach { fileType ->
        val associationName = xml(fileType.extension.removePrefix("."))
        appendLine("      <uap:Extension Category=\"windows.fileTypeAssociation\"><uap:FileTypeAssociation Name=\"$associationName\"><uap:DisplayName>${xml(fileType.description)}</uap:DisplayName><uap:SupportedFileTypes><uap:FileType>${xml(fileType.extension)}</uap:FileType></uap:SupportedFileTypes></uap:FileTypeAssociation></uap:Extension>")
    }
    appendLine("    </Extensions>")
    appendLine("  </Application></Applications>")
    appendLine("  <Capabilities><rescap:Capability Name=\"runFullTrust\" /></Capabilities>")
    appendLine("</Package>")
}

private fun windowsIconEntries(
    manifest: KWebApplicationManifest,
    applicationAssetRoot: Path,
): Map<String, ByteArray> {
    validateApplicationAssetRoot(applicationAssetRoot)
    val expected = listOf(
        WindowsIconAsset("windows-msix-square44", "Assets/Square44x44Logo.png", 44, 44),
        WindowsIconAsset("windows-msix-square150", "Assets/Square150x150Logo.png", 150, 150),
        WindowsIconAsset("windows-msix-square310", "Assets/Square310x310Logo.png", 310, 310),
        WindowsIconAsset("windows-msix-wide310", "Assets/Wide310x150Logo.png", 310, 150),
        WindowsIconAsset("windows-msix-store", "Assets/StoreLogo.png", 50, 50),
    )
    return expected.associate { asset ->
        val icon = manifest.icons.singleOrNull { it.kind == asset.kind }
            ?: applicationPackageFailure(
                code = "application.package.windows-icon-missing",
                details = mapOf("kind" to asset.kind),
                message = "The Windows package requires exactly one declared asset for each required icon kind.",
            )
        val source = safeApplicationAssetPath(applicationAssetRoot, icon.path)
        val sourceSize = try {
            Files.size(source)
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.asset-read-failed",
                details = mapOf("path" to icon.path),
                message = "Unable to inspect a declared Windows package icon.",
                cause = error,
            )
        }
        applicationPackageRequire(
            sourceSize in 24L..MAX_WINDOWS_ASSET_BYTES,
            code = "application.package.windows-icon-invalid",
            details = mapOf("kind" to asset.kind, "path" to icon.path),
            message = "A Windows package icon exceeds the configured asset-size limit.",
        )
        val bytes = try {
            Files.readAllBytes(source)
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.asset-read-failed",
                details = mapOf("path" to icon.path),
                message = "Unable to read a declared application asset.",
                cause = error,
            )
        }
        val pngSize = if (bytes.size >= 24) {
            bytes.pngDimensions()
        } else {
            null
        }
        applicationPackageRequire(
            bytes.copyOfRange(0, minOf(bytes.size, PNG_SIGNATURE.size)).contentEquals(PNG_SIGNATURE) &&
                pngSize == (asset.width to asset.height),
            code = "application.package.windows-icon-invalid",
            details = mapOf("kind" to asset.kind, "path" to icon.path),
            message = "A Windows package icon must have the PNG signature and its declared fixed dimensions.",
        )
        val image = try {
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (error: Exception) {
            applicationPackageFailure(
                code = "application.package.windows-icon-invalid",
                details = mapOf("kind" to asset.kind, "path" to icon.path),
                message = "A Windows package icon is not a decodable PNG image.",
                cause = error,
            )
        }
        applicationPackageRequire(
            image != null && image.width == asset.width && image.height == asset.height,
            code = "application.package.windows-icon-invalid",
            details = mapOf("kind" to asset.kind, "path" to icon.path),
            message = "A Windows package icon must be a PNG with the dimensions required by its manifest kind.",
        )
        asset.packagePath to bytes
    }
}

private fun ByteArray.pngDimensions(): Pair<Int, Int> = readPngDimension(16) to readPngDimension(20)

private fun ByteArray.readPngDimension(offset: Int): Int =
    (((this[offset].toLong() and 0xffL) shl 24) or
        ((this[offset + 1].toLong() and 0xffL) shl 16) or
        ((this[offset + 2].toLong() and 0xffL) shl 8) or
        (this[offset + 3].toLong() and 0xffL)).toInt()

private data class WindowsIconAsset(
    val kind: String,
    val packagePath: String,
    val width: Int,
    val height: Int,
)

private fun linuxDesktopEntry(
    manifest: KWebApplicationManifest,
    targetSpec: KWebApplicationTarget,
): String = buildString {
    appendLine("[Desktop Entry]")
    appendLine("Type=Application")
    appendLine("Name=${desktop(manifest.displayName)}")
    appendLine("Exec=${desktop(manifest.mainExecutable)} %U")
    appendLine("Terminal=false")
    appendLine("Categories=Network;")
    appendLine("MimeType=${manifest.fileTypes.joinToString(";") { desktop(it.mimeType) }};")
    appendLine("X-KDE-Protocols=${manifest.protocols.joinToString(",") { desktop(it.scheme) }}")
    appendLine("X-KWebShell-DesktopId=${desktop(checkNotNull(targetSpec.desktopId))}")
}

private fun linuxMimeInfo(manifest: KWebApplicationManifest): String = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    appendLine("<mime-info xmlns=\"http://www.freedesktop.org/standards/shared-mime-info\">")
    manifest.fileTypes.forEach { fileType ->
        appendLine("  <mime-type type=\"${xml(fileType.mimeType)}\"><comment>${xml(fileType.description)}</comment><glob pattern=\"*${xml(fileType.extension)}\" /></mime-type>")
    }
    appendLine("</mime-info>")
}

private fun linuxMetainfo(
    manifest: KWebApplicationManifest,
    targetSpec: KWebApplicationTarget,
): String = buildString {
    appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
    appendLine("<component type=\"desktop-application\">")
    appendLine("  <id>${xml(checkNotNull(targetSpec.desktopId))}</id>")
    appendLine("  <name>${xml(manifest.displayName)}</name>")
    appendLine("  <summary>${xml(manifest.displayName)}</summary>")
    appendLine("  <launchable type=\"desktop-id\">${xml(checkNotNull(targetSpec.desktopId))}</launchable>")
    appendLine("  <provides><binary>${xml(manifest.mainExecutable)}</binary></provides>")
    appendLine("  <releases><release version=\"${xml(manifest.productVersion)}\" /></releases>")
    appendLine("</component>")
}

private fun appxVersion(version: String): String {
    val digits = Regex("\\d+").findAll(version).map { it.value.toInt() }.toList()
    return listOf(digits.getOrElse(0) { 0 }, digits.getOrElse(1) { 0 }, digits.getOrElse(2) { 0 }, 0).joinToString(".")
}

private fun appxMinimumOsVersion(version: String): String {
    val components = version.split('.')
    applicationPackageRequire(
        components.size in 3..4 && components.all { component ->
            component.matches(Regex("(?:0|[1-9][0-9]{0,4})")) &&
                component.toIntOrNull()?.let { it <= 65_535 } == true
        },
        code = "application.package.windows-minimum-os-invalid",
        details = mapOf("minimumOs" to version),
        message = "The Windows MSIX minimum OS must contain three or four valid numeric version components.",
    )
    return if (components.size == 3) "$version.0" else version
}

private fun xml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun desktop(value: String): String = value.replace("\\", "\\\\").replace("\n", "\\n")

private fun validateOutputPath(path: Path, format: KWebApplicationPackageFormat) {
    applicationPackageRequire(
        path.isAbsolute && path == path.normalize(),
        code = "application.package.output-path-invalid",
        message = "The application package output path must be absolute and normalized.",
    )
    applicationPackageRequire(
        path.parent != null && Files.isDirectory(path.parent, LinkOption.NOFOLLOW_LINKS),
        code = "application.package.output-directory-invalid",
        message = "The application package output directory must already exist.",
    )
    applicationPackageRequire(
        !Files.isSymbolicLink(path) && (!Files.exists(path) || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)),
        code = "application.package.output-file-invalid",
        message = "The application package output must be absent or a regular file.",
    )
    val extension = path.fileName.toString().substringAfterLast('.', "").lowercase()
    val expected = when (format) {
        KWebApplicationPackageFormat.MACOS_APP_ZIP -> "zip"
        KWebApplicationPackageFormat.WINDOWS_MSIX -> "zip"
        KWebApplicationPackageFormat.LINUX_DEB -> "deb"
    }
    applicationPackageRequire(
        extension == expected,
        code = "application.package.extension-invalid",
        details = mapOf("expected" to expected, "actual" to extension),
        message = if (format == KWebApplicationPackageFormat.WINDOWS_MSIX) {
            "The Windows Kotlin assembler emits only signed metadata ZIPs; a real .msix must be created and verified by the Windows SDK provider."
        } else {
            "The application package extension does not match its declared format."
        },
    )
}

private fun validatePackagePath(path: Path) {
    applicationPackageRequire(
        path.isAbsolute && path == path.normalize() &&
            !Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS),
        code = "application.package.file-invalid",
        details = mapOf("path" to path.toString()),
        message = "The application package must be a regular non-symbolic-link file.",
    )
}

private fun writePackage(
    output: Path,
    entries: Map<String, ByteArray>,
    runtimeRelease: Path,
    runtimeReleaseDigest: KWebRuntimeReleaseContentDigest,
    manifest: KWebApplicationManifest,
    target: KWebTarget,
    format: KWebApplicationPackageFormat,
) {
    if (format == KWebApplicationPackageFormat.LINUX_DEB) {
        writeDebPackage(output, entries, runtimeRelease, runtimeReleaseDigest, manifest, target)
        return
    }
    val allEntries = entries.keys + RUNTIME_RELEASE_PATH
    val sortedNames = allEntries.sortedWith(KWebApplicationManifestContract.utf8Comparator)
    try {
        ZipArchiveOutputStream(
            output,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { archive ->
            archive.setEncoding(StandardCharsets.UTF_8.name())
            archive.setUseLanguageEncodingFlag(true)
            archive.setFallbackToUTF8(false)
            archive.setCreateUnicodeExtraFields(ZipArchiveOutputStream.UnicodeExtraFieldPolicy.NEVER)
            archive.setUseZip64(Zip64Mode.Never)
            sortedNames.forEach { name ->
                if (name == RUNTIME_RELEASE_PATH) {
                    val entry = regularEntry(name, runtimeReleaseDigest.size, runtimeReleaseDigest.crc32)
                    archive.putArchiveEntry(entry)
                    KWebRuntimeReleaseFileIO.copyVerified(runtimeRelease, archive, runtimeReleaseDigest)
                    archive.closeArchiveEntry()
                } else {
                    val bytes = entries.getValue(name)
                    val entry = regularEntry(name, bytes.size.toLong(), crc32(bytes))
                    archive.putArchiveEntry(entry)
                    archive.write(bytes)
                    archive.closeArchiveEntry()
                }
            }
            archive.finish()
        }
    } catch (error: KWebApplicationPackageException) {
        throw error
    } catch (error: Exception) {
        applicationPackageFailure(
            code = "application.package.write-failed",
            details = mapOf("path" to output.toString()),
            message = "Unable to write the deterministic application package.",
            cause = error,
        )
    }
}

private fun writeDebPackage(
    output: Path,
    entries: Map<String, ByteArray>,
    runtimeRelease: Path,
    runtimeReleaseDigest: KWebRuntimeReleaseContentDigest,
    manifest: KWebApplicationManifest,
    target: KWebTarget,
) {
    val controlTar = Files.createTempFile(output.parent, ".kweb-control-", ".tar.gz")
    val dataTar = Files.createTempFile(output.parent, ".kweb-data-", ".tar.gz")
    var primaryFailure: Throwable? = null
    try {
        writeDebControl(controlTar, manifest, target)
        writeDebData(dataTar, entries, runtimeRelease, runtimeReleaseDigest)
        Files.newOutputStream(
            output,
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        ).use { archive ->
            archive.write(AR_MAGIC)
            writeArMember(archive, "debian-binary", "2.0\n".toByteArray(StandardCharsets.US_ASCII))
            writeArMember(archive, "control.tar.gz", controlTar)
            writeArMember(archive, "data.tar.gz", dataTar)
        }
    } catch (error: Throwable) {
        primaryFailure = error
        throw when (error) {
            is KWebApplicationPackageException -> error
            else -> KWebApplicationPackageException(
                code = "application.package.write-failed",
                details = mapOf("path" to output.toString()),
                message = "Unable to write the deterministic Debian application package.",
                cause = error,
            )
        }
    } finally {
        try {
            Files.deleteIfExists(controlTar)
            Files.deleteIfExists(dataTar)
        } catch (cleanupError: Exception) {
            if (primaryFailure != null) primaryFailure.addSuppressed(cleanupError)
            else throw cleanupError
        }
    }
}

private fun writeDebControl(path: Path, manifest: KWebApplicationManifest, target: KWebTarget) {
    val architecture = if (target.id.endsWith("arm64")) "arm64" else "amd64"
    val control = buildString {
        appendLine("Package: ${manifest.applicationId}")
        appendLine("Version: ${manifest.productVersion}")
        appendLine("Section: net")
        appendLine("Priority: optional")
        appendLine("Architecture: $architecture")
        appendLine("Maintainer: ${manifest.publisher}")
        appendLine("Description: ${manifest.displayName}")
        appendLine(" ${manifest.displayName} desktop browser shell")
    }.toByteArray(StandardCharsets.UTF_8)
    writeTarGzip(path) { tar ->
        putTarFile(tar, "control", control)
    }
}

private fun writeDebData(
    path: Path,
    entries: Map<String, ByteArray>,
    runtimeRelease: Path,
    runtimeReleaseDigest: KWebRuntimeReleaseContentDigest,
) {
    writeTarGzip(path) { tar ->
        val allEntries = entries.keys + RUNTIME_RELEASE_PATH
        allEntries.sortedWith(KWebApplicationManifestContract.utf8Comparator).forEach { name ->
            val dataPath = "$DEB_DATA_ROOT$name"
            if (name == RUNTIME_RELEASE_PATH) {
                val entry = regularTarEntry(dataPath, runtimeReleaseDigest.size)
                tar.putArchiveEntry(entry)
                KWebRuntimeReleaseFileIO.copyVerified(runtimeRelease, tar, runtimeReleaseDigest)
                tar.closeArchiveEntry()
            } else {
                putTarFile(tar, dataPath, entries.getValue(name))
            }
        }
    }
}

private fun writeTarGzip(path: Path, write: (TarArchiveOutputStream) -> Unit) {
    val parameters = GzipParameters().apply {
        modificationTime = 0
        operatingSystem = 3
    }
    Files.newOutputStream(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { file ->
        GzipCompressorOutputStream(file, parameters).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_ERROR)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_ERROR)
                write(tar)
                tar.finish()
            }
        }
    }
}

private fun putTarFile(tar: TarArchiveOutputStream, name: String, bytes: ByteArray) {
    tar.putArchiveEntry(regularTarEntry(name, bytes.size.toLong()))
    tar.write(bytes)
    tar.closeArchiveEntry()
}

private fun regularTarEntry(name: String, size: Long): TarArchiveEntry =
    TarArchiveEntry(name).also { entry ->
        entry.size = size
        entry.mode = APPLICATION_PACKAGE_FILE_MODE
        entry.modTime = java.util.Date(0)
        entry.userId = 0
        entry.groupId = 0
        entry.userName = ""
        entry.groupName = ""
    }

private fun writeArMember(output: java.io.OutputStream, name: String, bytes: ByteArray) {
    writeArHeader(output, name, bytes.size.toLong())
    output.write(bytes)
    if (bytes.size % 2 != 0) output.write('\n'.code)
}

private fun writeArMember(output: java.io.OutputStream, name: String, path: Path) {
    val size = Files.size(path)
    writeArHeader(output, name, size)
    Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input -> input.copyTo(output) }
    if (size % 2L != 0L) output.write('\n'.code)
}

private fun writeArHeader(output: java.io.OutputStream, name: String, size: Long) {
    applicationPackageRequire(
        name.length <= 15 && name.none { it == ' ' || it == '/' },
        code = "application.package.ar-member-name-invalid",
        message = "A Debian package member name is invalid.",
    )
    val header = String.format(
        Locale.ROOT,
        "%-16s%-12s%-6s%-6s%-8s%-10d`\n",
        "$name/",
        "0",
        "0",
        "0",
        "100644",
        size,
    )
    output.write(header.toByteArray(StandardCharsets.US_ASCII))
}

private fun regularEntry(name: String, size: Long, crc: Long): ZipArchiveEntry =
    ZipArchiveEntry(name).also { entry ->
        entry.setTimeLocal(FIXED_TIMESTAMP)
        entry.setUnixMode(UnixStat.FILE_FLAG or APPLICATION_PACKAGE_FILE_MODE)
        entry.size = size
        entry.crc = crc
        entry.method = ZipArchiveOutputStream.STORED
    }

private fun createSiblingTemporary(output: Path): Path = try {
    Files.createTempFile(output.parent, ".${output.fileName}.", ".tmp")
} catch (error: Exception) {
    applicationPackageFailure(
        code = "application.package.temporary-create-failed",
        details = mapOf("output" to output.toString()),
        message = "Unable to create a sibling temporary application package.",
        cause = error,
    )
}

private fun publishAtomically(temporary: Path, output: Path) {
    try {
        Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: AtomicMoveNotSupportedException) {
        applicationPackageFailure(
            code = "application.package.atomic-move-unsupported",
            details = mapOf("output" to output.toString()),
            message = "The application package filesystem does not support atomic publication.",
            cause = error,
        )
    } catch (error: Exception) {
        applicationPackageFailure(
            code = "application.package.publish-failed",
            details = mapOf("output" to output.toString()),
            message = "Unable to publish the application package atomically.",
            cause = error,
        )
    }
}

private fun KWebRuntimeReleaseFileIO.digestFromBytes(bytes: ByteArray): KWebRuntimeReleaseContentDigest {
    val crc = CRC32().apply { update(bytes) }
    return KWebRuntimeReleaseContentDigest(bytes.size.toLong(), sha256(bytes), crc.value)
}

private fun crc32(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

private fun packageEntryDigests(
    entries: Map<String, ByteArray>,
    runtimeReleaseDigest: KWebRuntimeReleaseContentDigest,
): Map<String, String> = buildMap {
    entries.keys
        .filterNot { it == PACKAGE_SIGNATURE_STATEMENT_PATH || it == PACKAGE_SIGNATURE_PATH }
        .sortedWith(KWebApplicationManifestContract.utf8Comparator)
        .forEach { path ->
            put(path, if (path == RUNTIME_RELEASE_PATH) runtimeReleaseDigest.sha256 else sha256(entries.getValue(path)))
        }
    if (RUNTIME_RELEASE_PATH !in entries) put(RUNTIME_RELEASE_PATH, runtimeReleaseDigest.sha256)
}.toSortedMap(KWebApplicationManifestContract.utf8Comparator)

private fun signPackage(privateKey: java.security.PrivateKey, statement: ByteArray): ByteArray = try {
    Signature.getInstance(KWEB_APPLICATION_PACKAGE_SIGNATURE_ALGORITHM).run {
        initSign(privateKey)
        update(KWEB_APPLICATION_PACKAGE_SIGNATURE_DOMAIN)
        update(statement)
        sign()
    }
} catch (error: Exception) {
    applicationPackageFailure(
        code = "application.package.sign-failed",
        message = "Unable to sign the application package statement.",
        cause = error,
    )
}

private fun verifyPackageSignature(
    publicKey: java.security.PublicKey,
    statement: ByteArray,
    signature: ByteArray,
): Boolean = try {
    Signature.getInstance(KWEB_APPLICATION_PACKAGE_SIGNATURE_ALGORITHM).run {
        initVerify(publicKey)
        update(KWEB_APPLICATION_PACKAGE_SIGNATURE_DOMAIN)
        update(statement)
        verify(signature)
    }
} catch (_: Exception) {
    false
}

private const val APPLICATION_PRODUCT: String = "KWebShell"
private const val APPLICATION_PACKAGE_SCHEMA_VERSION: Int = 1
private const val APPLICATION_PACKAGE_FILE_MODE: Int = 0b110100100
private const val APPLICATION_MANIFEST_PATH: String = "application/manifest.json"
private const val PACKAGE_MANIFEST_PATH: String = "application/package.json"
private const val PACKAGED_STATE_PATH: String = "application/packaged-state.json"
private const val REGISTRATION_PATH: String = "application/registration.json"
private const val CAPABILITIES_PATH: String = "application/capabilities.json"
private const val SBOM_PATH: String = "application/sbom.json"
private const val RUNTIME_RELEASE_PATH: String = "runtime/release.pack.zip"
private const val PLATFORM_SIGNATURE_PATH: String = "signatures/platform.json"
private const val PACKAGE_SIGNATURE_STATEMENT_PATH: String = "signatures/package.json"
private const val PACKAGE_SIGNATURE_PATH: String = "signatures/package.ed25519"
private const val MACOS_INFO_PLIST_PATH: String = "platform/macos/Info.plist"
private const val WINDOWS_APPX_MANIFEST_PATH: String = "AppxManifest.xml"
private const val MAX_WINDOWS_ASSET_BYTES: Long = 16L * 1024L * 1024L
private const val LINUX_DESKTOP_PATH: String = "platform/linux/io.github.kwebshell.desktop"
private const val LINUX_MIME_PATH: String = "platform/linux/io.github.kwebshell.mime.xml"
private const val LINUX_METAINFO_PATH: String = "platform/linux/io.github.kwebshell.metainfo.xml"
private const val DEB_DATA_ROOT: String = "opt/kwebshell/"
private val AR_MAGIC: ByteArray = "!<arch>\n".toByteArray(StandardCharsets.US_ASCII)
private const val KWEB_APPLICATION_PACKAGE_SIGNATURE_ALGORITHM: String = "Ed25519"
private val KWEB_APPLICATION_PACKAGE_SIGNATURE_DOMAIN: ByteArray =
    "KWebShell application package v1\u0000".toByteArray(StandardCharsets.US_ASCII)
private val PNG_SIGNATURE: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
private val FIXED_TIMESTAMP: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0, 0)
