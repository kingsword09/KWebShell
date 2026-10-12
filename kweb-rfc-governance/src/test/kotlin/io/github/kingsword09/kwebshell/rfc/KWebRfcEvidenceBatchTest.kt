package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir

class KWebRfcEvidenceBatchTest {
    @TempDir
    lateinit var root: Path

    private val catalog = listOf(fixtureDocument("0001", KWebRfcStatus.IMPLEMENTED))
    private val bindings = KWebRfcContractBindingsDocument(1, listOf(KWebRfcContractBinding("0001", listOf("contract.txt"))))
    private val entry = KWebRfcEvidenceBatchEntry(
        "0001", "governance.hosted", "linux-x64", 44,
        listOf(KWebRfcEvidenceBatchArtifact("proof", "downloaded inputs/raw.bin")),
    )
    private val bytes = byteArrayOf(0, 13, 10, -1, 65, 13, 10)
    private val json get() = KWebRfcEvidenceJson.canonical
    private val output get() = root.resolve("manifest.json")

    @BeforeEach
    fun initializeRepository() {
        Files.writeString(root.resolve("contract.txt"), "tracked contract\n")
        for (command in listOf(listOf("init", "-q"), listOf("add", "contract.txt"))) {
            val process = ProcessBuilder(listOf("git", "-C", root.toString()) + command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), text)
            assertEquals(0, process.exitValue(), text)
        }
        val source = root.resolve(entry.artifacts.single().path)
        Files.createDirectories(source.parent)
        Files.write(source, bytes)
        Files.writeString(output, KWebRfcEvidenceJson.encodeManifest(fixtureManifest()) + "\n")
    }

    private fun batch(entries: List<KWebRfcEvidenceBatchEntry> = listOf(entry)) = KWebRfcEvidenceBatch(1, entries)

    private fun record(
        batch: KWebRfcEvidenceBatch = batch(),
        manifest: KWebRfcEvidenceManifest = fixtureManifest(),
        documents: List<KWebRfcDocument> = catalog,
        destination: Path = output,
        run: KWebRfcHostedRun = FIXTURE_RUN,
    ) = KWebRfcEvidenceBatchRecorder.record(batch, manifest, documents, FIXTURE_RUNTIME, bindings, root, run, destination)

    private fun assertCode(code: String, action: () -> Unit) {
        val error = assertFailsWith<KWebRfcGovernanceException>(block = action)
        assertEquals(code, error.code, error.message)
        Files.list(root).use { paths -> assertFalse(paths.anyMatch { it.fileName.toString().startsWith(".rfc-batch-") }) }
    }

    @Test
    fun batchEqualsSequentialRecordingAcrossTargetsAndKeepsHistoricalRecords() {
        val entries = KWEB_RFC_HOSTED_TARGETS.sorted().map { entry.copy(target = it) }
        val history = fixtureRecord(rfcId = "0042")
        var sequential = fixtureManifest(listOf(history))
        for (item in entries) {
            sequential = KWebRfcEvidenceRecorder().record(
                KWebRfcEvidenceRecordRequest(
                    item.rfcId, item.providerId, item.target, FIXTURE_RUN, item.electronFixtureMajor,
                    artifacts = item.artifacts.map { KWebRfcEvidenceArtifactInput(it.name, root.resolve(it.path)) },
                ),
                sequential, catalog, FIXTURE_RUNTIME, bindings, root, retainedRoot = root.resolve("sequential"),
            )
        }
        val result = record(batch(entries), fixtureManifest(listOf(history)))
        assertEquals(KWebRfcEvidenceJson.encodeManifest(sequential), KWebRfcEvidenceJson.encodeManifest(result))
        assertEquals(history, result.records.single { it.rfcId == "0042" })
        val first = Files.readAllBytes(output)
        record(batch(entries.reversed()), result)
        assertContentEquals(first, Files.readAllBytes(output))
        result.records.filter { it.rfcId == "0001" }.forEach { record ->
            assertEquals(FIXTURE_RUN, record.run)
            assertContentEquals(bytes, Files.readAllBytes(root.resolve(record.artifacts.single().path)))
        }
    }

    @Test
    fun strictSchemaRejectsUnknownFieldsWrongTypesVersionsAndSizeBoundaries() {
        val valid = json.encodeToString(KWebRfcEvidenceBatch.serializer(), batch())
        val invalid = listOf(
            valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
            valid.replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\""),
            valid.replace("\"electronFixtureMajor\": 44", "\"electronFixtureMajor\": \"44\""),
            valid.replace("\"target\": \"linux-x64\"", "\"target\": 1"),
            valid.replace("\"schemaVersion\": 1", "\"unrecognized\": true, \"schemaVersion\": 1"),
            valid.replace("\"providerId\":", "\"run\": {}, \"providerId\":"),
            valid.replace("\"name\": \"proof\"", "\"name\": \"proof\", \"sha256\": \"caller-digest\""),
            "{\"schemaVersion\":1,\"records\":[]}",
            json.encodeToString(KWebRfcEvidenceBatch.serializer(), batch(List(257) { entry.copy(providerId = "provider.p$it") })),
        )
        invalid.forEach { text -> assertCode("rfc.record.batch-invalid") { KWebRfcEvidenceBatchRecorder.decode(text) } }
        val largest = batch(List(256) { entry.copy(providerId = "provider.p$it") })
        assertEquals(largest, KWebRfcEvidenceBatchRecorder.decode(json.encodeToString(KWebRfcEvidenceBatch.serializer(), largest)))
        assertCode("rfc.record.batch-duplicate") { record(batch(listOf(entry, entry))) }
    }

    @Test
    fun lastRecordFailureDoesNotPublishEarlierRecordsOrArtifacts() {
        val before = Files.readAllBytes(output)
        val invalid = entry.copy(target = "windows-x64", artifacts = listOf(KWebRfcEvidenceBatchArtifact("missing", "absent.bin")))
        assertCode(KWebRfcRecorderErrorCode.ARTIFACT_UNREADABLE) { record(batch(listOf(entry, invalid))) }
        assertContentEquals(before, Files.readAllBytes(output))
        assertFalse(Files.exists(root.resolve("docs/rfcs/evidence/artifacts")))
        assertCode(KWebRfcRecorderErrorCode.RFC_NOT_IMPLEMENTED) {
            record(documents = listOf(catalog.single().copy(status = KWebRfcStatus.ACCEPTED)))
        }
        assertContentEquals(before, Files.readAllBytes(output))
    }

    @Test
    fun existingArtifactsCannotBeOverwrittenAndIdenticalRerunIsSafe() {
        val result = record()
        val before = Files.readAllBytes(output)
        val artifact = root.resolve(result.records.single().artifacts.single().path)
        Files.write(root.resolve(entry.artifacts.single().path), byteArrayOf(42))
        assertCode("rfc.record.batch-collision") { record() }
        assertContentEquals(bytes, Files.readAllBytes(artifact))
        assertContentEquals(before, Files.readAllBytes(output))
    }

    @Test
    fun twoProvidersCannotPublishConflictingBytesAtTheSameRetainedPath() {
        Files.createDirectories(root.resolve("another"))
        Files.write(root.resolve("another/raw.bin"), byteArrayOf(42))
        val other = entry.copy(providerId = "other.hosted", artifacts = listOf(KWebRfcEvidenceBatchArtifact("proof", "another/raw.bin")))
        val before = Files.readAllBytes(output)
        assertCode("rfc.record.batch-collision") { record(batch(listOf(entry, other))) }
        assertContentEquals(before, Files.readAllBytes(output))
        assertFalse(Files.exists(root.resolve("docs/rfcs/evidence/artifacts")))
    }

    @Test
    fun batchRetainsCompatibilityBytesAndRejectsWrongTargetOrRuntime() {
        val report = fixtureCompatibilityReport().copy(target = entry.target, electronFixtureMajor = entry.electronFixtureMajor)
        val reportPath = root.resolve("compatibility.json")
        fun write(value: KWebElectronCompatibilityReport) = Files.writeString(
            reportPath, json.encodeToString(KWebElectronCompatibilityReport.serializer(), value) + "\r\n",
        )
        val request = entry.copy(serviceId = "app-paths", compatibilityReportPath = "compatibility.json", matrixRowIds = listOf("app-paths"))
        write(report)
        val result = record(batch(listOf(request)))
        val saved = result.records.single()
        assertEquals("app-paths", saved.serviceId)
        assertEquals("1.0.0", saved.serviceVersion)
        assertEquals(listOf("app-paths"), saved.matrixRowIds)
        val artifact = saved.artifacts.single { it.name == "compatibility-report" }
        val retainedBytes = Files.readAllBytes(root.resolve(artifact.path))
        assertContentEquals(Files.readAllBytes(reportPath), retainedBytes)
        val before = Files.readAllBytes(output)
        write(report.copy(target = "windows-x64"))
        assertCode(KWebRfcRecorderErrorCode.REPORT_TARGET_MISMATCH) { record(batch(listOf(request))) }
        write(report.copy(cefVersion = "150.0.0+old"))
        assertCode(KWebRfcRecorderErrorCode.REPORT_STALE_RUNTIME) { record(batch(listOf(request))) }
        assertContentEquals(before, Files.readAllBytes(output))
        assertContentEquals(retainedBytes, Files.readAllBytes(root.resolve(artifact.path)))
    }

    @Test
    fun unsafePathsAndInvalidSourceRevisionNeverEscapeStaging() {
        for (path in listOf("../escape.bin", "/absolute.bin", "C:\\absolute.bin", "dir/../raw.bin", "dir//raw.bin")) {
            assertCode("rfc.record.batch-path") {
                record(batch(listOf(entry.copy(artifacts = listOf(KWebRfcEvidenceBatchArtifact("proof", path))))))
            }
        }
        assertCode("rfc.record.missing-provenance") { record(run = FIXTURE_RUN.copy(sourceRevision = "../../../escape")) }
        assertCode("rfc.record.unsupported-runner") { record(batch(listOf(entry.copy(target = "macos-x64")))) }
    }

    @Test
    fun outputDirectoryFailsWithStructuredPublishErrorAndPreservesManifest() {
        val directory = Files.createDirectory(root.resolve("output-directory"))
        val before = Files.readAllBytes(output)
        assertCode("rfc.record.batch-publish") { record(destination = directory) }
        assertContentEquals(before, Files.readAllBytes(output))
        assertFalse(Files.exists(root.resolve("docs/rfcs/evidence/artifacts")))
    }

    @Test
    fun linkedSourceDirectoryIsRejectedOnEveryHost() {
        val link = root.resolve("linked-inputs")
        val source = root.resolve("downloaded inputs")
        if (System.getProperty("os.name").startsWith("Windows")) {
            // Junctions do not require Windows symbolic-link privileges, and are still redirects.
            val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), source.toString())
                .redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), text)
            assertEquals(0, process.exitValue(), text)
        } else {
            Files.createSymbolicLink(link, source)
        }
        try {
            assertCode("rfc.record.batch-path") {
                record(batch(listOf(entry.copy(artifacts = listOf(KWebRfcEvidenceBatchArtifact("proof", "linked-inputs/raw.bin"))))))
            }
        } finally {
            Files.delete(link)
        }
    }

    @Test
    fun packagedSchemaMatchesBatchFieldsAndBoundaries() {
        val resource = javaClass.getResourceAsStream("/io/github/kingsword09/kwebshell/rfc/rfc-evidence-batch.schema.json")
        requireNotNull(resource)
        val schema = resource.use { Json.parseToJsonElement(it.bufferedReader().readText()).jsonObject }
        val definitions = schema.getValue("\$defs").jsonObject
        val example = Json.parseToJsonElement(json.encodeToString(KWebRfcEvidenceBatch.serializer(), batch())).jsonObject
        assertEquals(example.keys, schema.getValue("properties").jsonObject.keys)
        val recordSchema = definitions.getValue("record").jsonObject
        assertEquals(example.getValue("records").jsonArray.single().jsonObject.keys, recordSchema.getValue("properties").jsonObject.keys)
        assertEquals(setOf("name", "path"), definitions.getValue("artifact").jsonObject.getValue("properties").jsonObject.keys)
        assertEquals("false", recordSchema.getValue("additionalProperties").jsonPrimitive.content)
        val records = schema.getValue("properties").jsonObject.getValue("records").jsonObject
        assertEquals("1", records.getValue("minItems").jsonPrimitive.content)
        assertEquals("256", records.getValue("maxItems").jsonPrimitive.content)
    }

    @Test
    fun actualCliRecordsFromHostedEnvironmentAndRejectsLocalAndInvalidOptions() {
        val batchFile = root.resolve("batch input.json")
        Files.writeString(batchFile, json.encodeToString(KWebRfcEvidenceBatch.serializer(), batch()))
        val catalogPath = Files.createDirectory(root.resolve("catalog"))
        Files.writeString(catalogPath.resolve("0001-capability.md"), fixtureCatalogDocumentText())
        val runtimeFile = root.resolve("runtime.json")
        Files.writeString(runtimeFile, json.encodeToString(KWebRfcRuntimeIdentity.serializer(), FIXTURE_RUNTIME))
        val bindingsFile = root.resolve("bindings.json")
        Files.writeString(bindingsFile, json.encodeToString(KWebRfcContractBindingsDocument.serializer(), bindings))
        val arguments = listOf(
            "recordBatch", output.toString(), output.toString(), "--catalog", catalogPath.toString(),
            "--runtime", runtimeFile.toString(), "--contracts", bindingsFile.toString(),
            "--repository-root", root.toString(), "--batch", batchFile.toString(),
        )
        fun runCli(hosted: Boolean, args: List<String> = arguments, missing: String? = null): Pair<Int, String> {
            val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val builder = ProcessBuilder(listOf(java.toString(), "-cp", System.getProperty("java.class.path"), KWebRfcGovernanceCli::class.java.name) + args)
                .redirectErrorStream(true)
            builder.environment().putAll(mapOf(
                "GITHUB_ACTIONS" to hosted.toString(), "GITHUB_REPOSITORY" to FIXTURE_RUN.repository,
                "GITHUB_WORKFLOW_REF" to FIXTURE_RUN.workflowRef, "GITHUB_RUN_ID" to FIXTURE_RUN.runId,
                "GITHUB_RUN_ATTEMPT" to FIXTURE_RUN.runAttempt.toString(), "GITHUB_SHA" to FIXTURE_RUN.sourceRevision,
            ))
            missing?.let { builder.environment().remove(it) }
            val log = root.resolve("cli-output.txt")
            val process = builder.redirectOutput(log.toFile()).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Batch CLI subprocess timed out")
            }
            return process.exitValue() to Files.readString(log)
        }
        val success = runCli(true)
        assertEquals(0, success.first, success.second)
        assertEquals(FIXTURE_RUN, KWebRfcEvidenceJson.decodeManifest(Files.readString(output)).records.single().run)
        for ((result, code) in listOf(
            runCli(false) to "rfc.record.untrusted-environment",
            runCli(true, missing = "GITHUB_SHA") to "rfc.record.missing-provenance",
            runCli(true, args = arguments + listOf("--unexpected", "value")) to "rfc.record.batch-invalid",
            runCli(true, args = arguments + listOf("--batch", batchFile.toString())) to "rfc.record.batch-invalid",
        )) {
            assertEquals(2, result.first, result.second)
            assertEquals(code, Json.parseToJsonElement(result.second).jsonObject.getValue("code").jsonPrimitive.content)
        }
    }
}
