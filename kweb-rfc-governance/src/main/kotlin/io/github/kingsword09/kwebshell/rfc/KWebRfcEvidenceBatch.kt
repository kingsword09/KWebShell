package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationJson
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

@Serializable
internal data class KWebRfcEvidenceBatch(
    val schemaVersion: Int,
    val records: List<KWebRfcEvidenceBatchEntry>,
)

@Serializable
internal data class KWebRfcEvidenceBatchEntry(
    val rfcId: String,
    val providerId: String,
    val target: String,
    val electronFixtureMajor: Int,
    val artifacts: List<KWebRfcEvidenceBatchArtifact>,
    val serviceId: String? = null,
    val matrixRowIds: List<String> = emptyList(),
    val compatibilityStatus: String? = null,
    val compatibilityReportPath: String? = null,
)

@Serializable
internal data class KWebRfcEvidenceBatchArtifact(val name: String, val path: String)

/** Validates a complete batch in isolation, then publishes immutable artifacts and one manifest. */
internal object KWebRfcEvidenceBatchRecorder {
    fun decode(text: String): KWebRfcEvidenceBatch {
        val batch = try {
            val document = KWebRfcEvidenceJson.canonical.parseToJsonElement(text) as? JsonObject
                ?: throw failure("invalid", "The evidence batch must be a JSON object.")
            fun integer(value: kotlinx.serialization.json.JsonElement?) {
                if (value !is JsonPrimitive || value.isString || value.intOrNull == null) {
                    throw failure("invalid", "Batch versions and Electron majors must be JSON integers.")
                }
            }
            integer(document["schemaVersion"])
            val records = document["records"] as? JsonArray
                ?: throw failure("invalid", "The evidence batch records must be a JSON array.")
            records.forEach { record -> integer((record as? JsonObject)?.get("electronFixtureMajor")) }
            KWebRfcEvidenceJson.canonical.decodeFromJsonElement(KWebRfcEvidenceBatch.serializer(), document)
        } catch (error: SerializationException) {
            throw failure("invalid", "The evidence batch must match the strict versioned JSON schema.", error)
        } catch (error: IllegalArgumentException) {
            throw failure("invalid", "The evidence batch must match the strict versioned JSON schema.", error)
        }
        validate(batch)
        return batch
    }

    private fun validate(batch: KWebRfcEvidenceBatch) {
        if (batch.schemaVersion != 1 || batch.records.size !in 1..256) {
            throw failure("invalid", "Evidence batch version 1 requires between 1 and 256 records.")
        }
        val identities = batch.records.map { Triple(it.rfcId, it.target, it.providerId) }
        if (identities.distinct().size != identities.size) {
            throw failure("duplicate", "An evidence batch cannot repeat an RFC/target/provider identity.")
        }
        if (batch.records.any { it.target !in KWEB_RFC_HOSTED_TARGETS }) {
            throw KWebRfcGovernanceException(
                code = "rfc.record.unsupported-runner",
                message = "Evidence records may only target the hosted verification triple.",
            )
        }
    }

    fun record(
        batch: KWebRfcEvidenceBatch,
        manifest: KWebRfcEvidenceManifest,
        catalog: List<KWebRfcDocument>,
        runtime: KWebRfcRuntimeIdentity,
        bindings: KWebRfcContractBindingsDocument,
        repositoryRoot: Path,
        run: KWebRfcHostedRun,
        output: Path,
    ): KWebRfcEvidenceManifest {
        validate(batch)
        if (!Regex("[0-9a-f]{40}").matches(run.sourceRevision)) {
            throw KWebRfcGovernanceException(
                code = "rfc.record.missing-provenance",
                message = "The hosted source revision must be a full lowercase Git commit id.",
            )
        }
        val root = repositoryRoot.toAbsolutePath().normalize()
        val destination = output.toAbsolutePath().normalize()
        val retained = root.resolve("docs/rfcs/evidence/artifacts")
        if (destination.startsWith(retained) || Files.isSymbolicLink(destination) ||
            (Files.exists(destination) && !Files.isRegularFile(destination))) {
            throw failure("publish", "The output must be a regular manifest outside the retained artifact tree.")
        }
        try {
            Files.createDirectories(destination.parent)
            val staging = Files.createTempDirectory(destination.parent, ".rfc-batch-")
            try {
                var updated = manifest
                val recorder = KWebRfcEvidenceRecorder()
                batch.records.forEach { entry ->
                    val reportPath = entry.compatibilityReportPath?.let { sourcePath(root, it) }
                    val report = reportPath?.let {
                        KWebElectronMigrationJson.format.decodeFromString(
                            KWebElectronCompatibilityReport.serializer(), Files.readString(it),
                        )
                    }
                    updated = recorder.record(
                        KWebRfcEvidenceRecordRequest(
                            rfcId = entry.rfcId,
                            providerId = entry.providerId,
                            target = entry.target,
                            run = run,
                            electronFixtureMajor = entry.electronFixtureMajor,
                            serviceId = entry.serviceId,
                            matrixRowIds = entry.matrixRowIds,
                            compatibilityStatus = entry.compatibilityStatus,
                            artifacts = entry.artifacts.map {
                                KWebRfcEvidenceArtifactInput(it.name, sourcePath(root, it.path))
                            },
                            compatibilityReport = report,
                            compatibilityReportPath = reportPath,
                        ),
                        updated, catalog, runtime, bindings, root,
                        retainedRoot = staging,
                        immutableArtifacts = true,
                    )
                }

                val artifactRoot = staging.resolve("docs/rfcs/evidence/artifacts")
                val stagedFiles = Files.walk(artifactRoot).use { paths ->
                    paths.filter { Files.isRegularFile(it) }.sorted().toList()
                }
                // Check every destination before publishing any bytes. Existing evidence is immutable.
                val destinations = stagedFiles.map { source ->
                    val relative = staging.relativize(source)
                    val target = root.resolve(relative)
                    checkAncestors(root, relative)
                    if (Files.exists(target) && (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1L)) {
                        throw failure("collision", "Existing retained evidence differs from the batch; inspect its source revision.")
                    }
                    source to target
                }
                val stagedManifest = staging.resolve("manifest.json")
                Files.writeString(stagedManifest, KWebRfcEvidenceJson.encodeManifest(updated) + "\n")
                destinations.forEach { (source, target) ->
                    if (!Files.exists(target)) {
                        Files.createDirectories(target.parent)
                        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
                    }
                }
                Files.move(stagedManifest, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                return updated
            } finally {
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        } catch (error: IOException) {
            throw failure("publish", "Batch I/O failed; the manifest was not partially written. Inspect unreferenced artifacts before retrying.", error)
        }
    }

    private fun sourcePath(root: Path, value: String): Path {
        if (value.isEmpty() || value.contains('\\') || value.contains('\u0000') || value.contains(':') ||
            value.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw failure("path", "Batch artifact paths must be safe repository-relative paths.")
        }
        val relative = Path.of(value)
        if (relative.isAbsolute) throw failure("path", "Batch artifact paths must be repository-relative.")
        checkAncestors(root, relative)
        return root.resolve(relative)
    }

    private fun checkAncestors(root: Path, relative: Path) {
        var path = root
        val realRoot = root.toRealPath()
        relative.forEach { component ->
            path = path.resolve(component)
            if (Files.isSymbolicLink(path) ||
                (Files.exists(path) && path.toRealPath() != realRoot.resolve(root.relativize(path)))) {
                throw failure("path", "Batch artifact paths must not traverse symlinks.")
            }
        }
    }

    internal fun failure(kind: String, message: String, cause: Throwable? = null): KWebRfcGovernanceException =
        KWebRfcGovernanceException("rfc.record.batch-$kind", message, cause = cause)
}
