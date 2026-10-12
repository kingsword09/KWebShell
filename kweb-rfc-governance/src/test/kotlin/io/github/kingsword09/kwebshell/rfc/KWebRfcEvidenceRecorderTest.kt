package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class KWebRfcEvidenceRecorderTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private var workTreeInitialized = false

    private val catalog = listOf(
        fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED),
        fixtureDocument(id = "0002", status = KWebRfcStatus.PROPOSED, dependsOn = listOf("0001")),
    )

    private val compatibilityReport = fixtureCompatibilityReport()

    private fun request(
        report: KWebElectronCompatibilityReport? = compatibilityReport,
        rfcId: String = "0001",
        target: String = "macos-arm64",
    ) = KWebRfcEvidenceRecordRequest(
        rfcId = rfcId,
        providerId = "governance.hosted",
        target = target,
        run = FIXTURE_RUN,
        electronFixtureMajor = 37,
        compatibilityReport = report,
        compatibilityReportPath = report?.let { retainedFile("compatibility-report.json", "retained report") },
    )

    /**
     * Creates one retained fixture file. The contract digest binds the repository
     * revision, so the fixture directory is a real work tree and the file is staged.
     */
    private fun retainedFile(name: String, contents: String): Path {
        val path = temporaryDirectory.resolve(name)
        Files.writeString(path, contents)
        if (!workTreeInitialized) {
            workTreeInitialized = true
            git("init", "-q")
        }
        git("add", "--", name)
        return path
    }

    private fun git(vararg arguments: String) {
        val command = listOf("git", "-C", temporaryDirectory.toString()) + arguments
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        check(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) && process.exitValue() == 0) {
            "git ${arguments.joinToString(" ")} failed: $output"
        }
    }

    private fun record(
        request: KWebRfcEvidenceRecordRequest,
        manifest: KWebRfcEvidenceManifest = fixtureManifest(),
        recordCatalog: List<KWebRfcDocument> = catalog,
        runtime: KWebRfcRuntimeIdentity = FIXTURE_RUNTIME,
    ): KWebRfcEvidenceManifest {
        val contract = retainedFile("contract.txt", "contract")
        val bindings = KWebRfcContractBindingsDocument(
            schemaVersion = 1,
            bindings = listOf(KWebRfcContractBinding(request.rfcId, listOf(contract.fileName.toString()))),
        )
        return KWebRfcEvidenceRecorder().record(
            request,
            manifest,
            recordCatalog,
            runtime,
            bindings,
            temporaryDirectory,
        )
    }

    @Test
    fun evidenceCannotOverrideTheReportElectronMajor() {
        val failure = assertFailsWith<KWebRfcGovernanceException> {
            record(request(report = compatibilityReport.copy(electronFixtureMajor = 99)))
        }
        assertEquals(KWebRfcRecorderErrorCode.REPORT_ELECTRON_MISMATCH, failure.code)
    }

    @Test
    fun recordDerivesDigestsAndServiceVersionFromCompatibilityReport() {
        val updated = record(request())
        val record = updated.records.single()
        assertEquals("app-paths", record.serviceId)
        assertEquals("1.0.0", record.serviceVersion)
        assertEquals(FIXTURE_RUNTIME.cefVersion, record.cefVersion)
        assertEquals("READY", record.compatibilityStatus)
        assertEquals(
            listOf(
                KWebRfcEvidenceArtifact(
                    "compatibility-report",
                    "docs/rfcs/evidence/artifacts/0001/${FIXTURE_RUN.sourceRevision}/macos-arm64/" +
                        "compatibility-report/compatibility-report.json",
                    KWebRfcEvidenceJson.sha256("retained report".encodeToByteArray()),
                ),
            ),
            record.artifacts,
        )
    }

    @Test
    fun regenerationIsByteForByteDeterministic() {
        val recorder = KWebRfcEvidenceRecorder()
        val first = KWebRfcEvidenceJson.encodeManifest(
            record(request()),
        )
        val second = KWebRfcEvidenceJson.encodeManifest(
            record(request()),
        )
        assertEquals(first, second)
        assertTrue(first.contains("\"recordsSha256\""))
    }

    @Test
    fun upsertReplacesSameIdentityAndKeepsOtherTargets() {
        val recorder = KWebRfcEvidenceRecorder()
        val manifest = fixtureManifest(threeHostedTargets())
        val updated = record(
            request(target = "macos-arm64"),
            manifest,
        )
        assertEquals(3, updated.records.size)
        val macosRecords = updated.records.filter { it.target == "macos-arm64" }
        assertEquals(1, macosRecords.size)
        assertEquals(FIXTURE_RUN, macosRecords.single().run)
    }

    @Test
    fun reportForDifferentTargetFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                request(target = "windows-x64"),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.REPORT_TARGET_MISMATCH, error.code)
    }

    @Test
    fun reportAgainstDifferentRuntimeFails() {
        val staleReport = compatibilityReport.copy(cefVersion = "150.0.0+old")
        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                request(report = staleReport),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.REPORT_STALE_RUNTIME, error.code)
    }

    @Test
    fun recordingForProposedRfcFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                request(rfcId = "0002"),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.RFC_NOT_IMPLEMENTED, error.code)
    }

    @Test
    fun recordingForUnknownRfcFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                request(rfcId = "0042"),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.RFC_UNKNOWN, error.code)
    }

    @Test
    fun declaredArtifactsRequireNoReportButServiceBindingRequiresReport() {
        val updated = record(
            KWebRfcEvidenceRecordRequest(
                rfcId = "0001",
                providerId = "governance.hosted",
                target = "linux-x64",
                run = FIXTURE_RUN,
                electronFixtureMajor = 37,
                artifacts = listOf(
                    KWebRfcEvidenceArtifactInput(
                        "governance-report",
                        retainedFile("governance-report.json", "governance"),
                    ),
                ),
            ),
        )
        assertEquals(1, updated.records.size)
        assertEquals(null, updated.records.single().serviceId)

        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                KWebRfcEvidenceRecordRequest(
                    rfcId = "0001",
                    providerId = "governance.hosted",
                    target = "linux-x64",
                    run = FIXTURE_RUN,
                    electronFixtureMajor = 37,
                    serviceId = "app-paths",
                    artifacts = listOf(
                        KWebRfcEvidenceArtifactInput(
                            "governance-report",
                            retainedFile("governance-report-2.json", "governance"),
                        ),
                    ),
                ),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.SERVICE_UNRESOLVED, error.code)
    }

    @Test
    fun missingArtifactFileFailsInsteadOfTrustingACallerDigest() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            record(
                KWebRfcEvidenceRecordRequest(
                    rfcId = "0001",
                    providerId = "governance.hosted",
                    target = "linux-x64",
                    run = FIXTURE_RUN,
                    electronFixtureMajor = 37,
                    artifacts = listOf(
                        KWebRfcEvidenceArtifactInput(
                            "governance-report",
                            temporaryDirectory.resolve("does-not-exist.json"),
                        ),
                    ),
                ),
            )
        }
        assertEquals(KWebRfcRecorderErrorCode.ARTIFACT_UNREADABLE, error.code)
    }
}
