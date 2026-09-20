package io.github.kingsword09.kwebshell.electron.migration

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.stream.Collectors

public class KWebElectronCompatibilityReportBuilder public constructor() {
    public fun build(
        manifestPath: Path,
        manifest: KWebElectronManifest,
        generatedOutput: Path,
        inventory: KWebElectronInventoryReport,
        provenance: KWebElectronReportProvenance,
    ): KWebElectronCompatibilityReport {
        KWebElectronManifestValidator.validate(manifest)
        val runtime = KWebElectronRuntimeIdentity.load(provenance)
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
        val sourcesDigest = KWebElectronFiles.digestEntries(KWebElectronFiles.sources(manifestRoot))
        val locksDigest = KWebElectronFiles.digestEntries(KWebElectronFiles.lockfiles(manifestRoot))
        val evidenceBytes = Files.readAllBytes(provenance.rfcEvidenceManifest)
        val catalogEntries = KWebElectronFiles.entries(provenance.rfcCatalog) { it.parent == provenance.rfcCatalog && it.fileName.toString().matches(Regex("[0-9]{4}-.*\\.md")) }
        if (catalogEntries.isEmpty()) throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, "The RFC catalog is missing.")
        val declarationBlockers = KWebElectronManifestValidator.blockingReasons(manifest)
        val expectedOutput = if (declarationBlockers.isEmpty()) KWebElectronPreloadGenerator().generate(manifest).let { sources ->
            mapOf("KWebElectronPreload.ts" to sources.typescript, "KWebElectronPreload.d.ts" to sources.declarations, "KWebElectronPreload.js" to sources.javascript, "KWebElectronHostChecklist.md" to sources.checklist)
        } else emptyMap()
        val blockedReasons = buildList {
            addAll(declarationBlockers)
            expectedOutput.forEach { (name, expected) ->
                val path = generatedOutput.resolve(name)
                if (!Files.isRegularFile(path) || Files.readString(path) != expected) add("generated-output-mismatch:$name")
            }
            if (sourcesDigest != inventory.sourceSha256) add("inventory-source-digest-mismatch")
            if (locksDigest != inventory.lockfileSha256) add("inventory-lockfile-digest-mismatch")
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
            if (runtime.cefVersion.isBlank()) add("runtime-cef-version-empty")
            if (runtime.chromiumVersion.isBlank()) add("runtime-chromium-version-empty")
            if (!TARGET.matches(runtime.target)) add("runtime-target-invalid:${runtime.target}")
        }.distinct().sorted()
        val report = KWebElectronCompatibilityReport(
            schemaVersion = KWebElectronCompatibilityReport.CURRENT_SCHEMA_VERSION,
            applicationId = manifest.applicationId,
            entryId = "${manifest.rendererProfile}:${manifest.rendererOrigin}:${manifest.rendererRoot}/${manifest.rendererEntry}",
            rendererOrigin = manifest.rendererOrigin,
            rendererProfile = manifest.rendererProfile,
            policies = (manifest.channels.mapNotNull { channel -> channel.policy?.let { "channel:${channel.name}" to it } } + manifest.streams.mapNotNull { stream -> stream.policy?.let { "stream:${stream.name}" to it } }).toMap().toSortedMap(),
            sourceSha256 = sourcesDigest,
            lockfileSha256 = locksDigest,
            rfcEvidenceSha256 = KWebElectronFiles.digest(evidenceBytes),
            rfcCatalogSha256 = KWebElectronFiles.digestEntries(catalogEntries),
            runtimeSha256 = runtime.runtimeSha256,
            runtimeArtifactSha256 = runtime.artifactSha256,
            electronFixtureMajor = manifest.electronFixtureMajor,
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

    public fun merge(reports: List<KWebElectronCompatibilityReport>): KWebElectronAggregateReport {
        if (reports.isEmpty()) conflict("At least one compatibility report is required.")
        reports.forEach(KWebElectronCompatibilityReportValidator::validate)
        val entries = reports.sortedBy { it.entryId }
        if (entries.map { it.entryId }.distinct().size != entries.size) conflict("Duplicate renderer entry identities cannot be merged.")
        val first = entries.first()
        reports.forEach { report ->
            if (report.applicationId != first.applicationId || report.target != first.target ||
                report.cefVersion != first.cefVersion || report.chromiumVersion != first.chromiumVersion ||
                report.runtimeSha256 != first.runtimeSha256 || report.runtimeArtifactSha256 != first.runtimeArtifactSha256 ||
                report.capabilityMatrixVersion != first.capabilityMatrixVersion || report.capabilityMatrixSha256 != first.capabilityMatrixSha256 ||
                report.rfcEvidenceSha256 != first.rfcEvidenceSha256 || report.rfcCatalogSha256 != first.rfcCatalogSha256 ||
                report.electronFixtureMajor != first.electronFixtureMajor
            ) conflict("Renderer reports disagree on application, target, runtime, Electron, RFC or matrix provenance.")
        }
        reports.flatMap { it.serviceContractVersions.entries }.groupBy { it.key }.forEach { (service, versions) ->
            if (versions.map { it.value }.distinct().size != 1) conflict("Conflicting service versions for $service.")
        }
        val reasons = entries.flatMap { report -> report.blockedReasons.map { "${report.entryId}:$it" } }.sorted()
        return KWebElectronAggregateReport(
            schemaVersion = 1,
            migrationStatus = if (reasons.isEmpty()) KWebElectronCompatibilityReport.READY_STATUS else KWebElectronCompatibilityReport.BLOCKED_STATUS,
            blockedReasons = reasons,
            entriesSha256 = digestText(KWebElectronMigrationJson.format.encodeToString(entries)),
            entries = entries,
        )
    }

    private fun conflict(message: String): Nothing = throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.REPORT_CONFLICT, message)

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
