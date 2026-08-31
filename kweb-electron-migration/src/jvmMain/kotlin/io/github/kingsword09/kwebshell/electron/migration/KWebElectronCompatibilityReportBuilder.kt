package io.github.kingsword09.kwebshell.electron.migration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

public data class KWebElectronRuntimeIdentity(
    public val cefVersion: String,
    public val chromiumVersion: String,
    public val target: String,
)

public class KWebElectronCompatibilityReportBuilder public constructor() {
    public fun build(
        manifestPath: Path,
        manifest: KWebElectronManifest,
        generatedOutput: Path,
        inventory: KWebElectronInventoryReport,
        runtime: KWebElectronRuntimeIdentity,
    ): KWebElectronCompatibilityReport {
        KWebElectronManifestValidator.validate(manifest)
        val normalizedManifest = manifestPath.toAbsolutePath().normalize()
        val manifestRoot = normalizedManifest.parent
            ?: throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                details = mapOf("manifest" to normalizedManifest.toString()),
                message = "The migration manifest must have a parent directory.",
            )
        if (inventory.schemaVersion != KWebElectronInventoryReport.CURRENT_SCHEMA_VERSION) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                details = mapOf("schemaVersion" to inventory.schemaVersion.toString()),
                message = "The Electron inventory report schema is unsupported.",
            )
        }
        if (Path.of(inventory.root).toAbsolutePath().normalize() != manifestRoot) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                details = mapOf(
                    "inventoryRoot" to inventory.root,
                    "manifestRoot" to manifestRoot.toString(),
                ),
                message = "The Electron inventory report is not bound to the manifest root.",
            )
        }
        val fileManifest = try {
            KWebElectronMigrationJson.decode(Files.readString(normalizedManifest))
        } catch (error: Throwable) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_INVALID,
                details = mapOf("manifest" to normalizedManifest.toString()),
                message = "The migration manifest file could not be decoded for digest binding.",
                cause = error,
            )
        }
        if (fileManifest != manifest) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_INVALID,
                details = mapOf("manifest" to normalizedManifest.toString()),
                message = "The migration manifest object does not match the manifest file being hashed.",
            )
        }
        val rendererDirectory = manifestRoot.resolve(manifest.rendererRoot).normalize()
        val rendererDigest = digestTree(rendererDirectory)
        val generatedDigest = digestTree(generatedOutput)
        val inventoryDigest = digestText(KWebElectronMigrationJson.format.encodeToString(inventory))
        val matrixDigest = digestText(
            KWebElectronMigrationJson.format.encodeToString(KWebElectronCapabilityMatrix.document()),
        )
        val manifestDigest = digestBytes(Files.readAllBytes(normalizedManifest))
        val blockedReasons = buildList {
            if (rendererDigest != manifest.rendererSha256) {
                add("renderer-digest-mismatch:expected=${manifest.rendererSha256}:actual=$rendererDigest")
            }
            if (!Files.isRegularFile(rendererDirectory.resolve(manifest.rendererEntry))) {
                add("renderer-entry-missing:${manifest.rendererEntry}")
            }
            if (generatedDigest == EMPTY_TREE_DIGEST) {
                add("generated-output-empty")
            }
            inventory.blockingFindings.forEach { finding ->
                add("inventory:${finding.path}:${finding.line}:${finding.expression}")
            }
            manifest.electronImports.filter { it.status == KWebElectronMappingStatus.REWRITE || it.status == KWebElectronMappingStatus.UNSUPPORTED }
                .forEach { add("manifest-import:${it.module}#${it.symbol}:${it.status.name}") }
            manifest.channels.filter { it.status == KWebElectronMappingStatus.REWRITE || it.status == KWebElectronMappingStatus.UNSUPPORTED }
                .forEach { add("manifest-channel:${it.name}:${it.status.name}") }
            manifest.preloadMethods.filter { it.status == KWebElectronMappingStatus.REWRITE || it.status == KWebElectronMappingStatus.UNSUPPORTED }
                .forEach { add("manifest-preload:${it.name}:${it.status.name}") }
            if (runtime.cefVersion.isBlank()) add("runtime-cef-version-empty")
            if (runtime.chromiumVersion.isBlank()) add("runtime-chromium-version-empty")
            if (!TARGET.matches(runtime.target)) add("runtime-target-invalid:${runtime.target}")
        }.distinct().sorted()
        val report = KWebElectronCompatibilityReport(
            schemaVersion = KWebElectronCompatibilityReport.CURRENT_SCHEMA_VERSION,
            migrationStatus = if (blockedReasons.isEmpty()) {
                KWebElectronCompatibilityReport.READY_STATUS
            } else {
                KWebElectronCompatibilityReport.BLOCKED_STATUS
            },
            blockedReasons = blockedReasons,
            rendererSha256 = rendererDigest,
            manifestSha256 = manifestDigest,
            generatedOutputSha256 = generatedDigest,
            inventorySha256 = inventoryDigest,
            capabilityMatrixVersion = KWebElectronCapabilityMatrix.schemaVersion,
            capabilityMatrixSha256 = matrixDigest,
            serviceContractVersions = manifest.requiredServices.associate { it.id to it.version }.toSortedMap(),
            cefVersion = runtime.cefVersion,
            chromiumVersion = runtime.chromiumVersion,
            target = runtime.target,
        )
        KWebElectronCompatibilityReportValidator.validate(report)
        return report
    }

    public fun write(report: KWebElectronCompatibilityReport, output: Path) {
        Files.createDirectories(output.toAbsolutePath().normalize().parent)
        Files.writeString(output, KWebElectronMigrationJson.format.encodeToString(report) + "\n")
    }

    private fun digestTree(root: Path): String {
        val normalized = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized)) return EMPTY_TREE_DIGEST
        val digest = MessageDigest.getInstance("SHA-256")
        val files = Files.walk(normalized).use { stream ->
            stream.filter(Files::isRegularFile).sorted().collect(Collectors.toList())
        }
        files.forEach { file ->
            val relative = normalized.relativize(file).toString().replace(file.fileSystem.getSeparator(), "/")
            digest.update(relative.toByteArray(StandardCharsets.UTF_8))
            digest.update(0)
            Files.newInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.update(0)
        }
        return digest.digest().toHex()
    }

    private fun digestText(value: String): String = digestBytes(value.toByteArray(StandardCharsets.UTF_8))

    private fun digestBytes(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private companion object {
        val TARGET = Regex("(windows|macos|linux)-(x64|arm64)")
        const val EMPTY_TREE_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}

private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
