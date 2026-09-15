package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.io.TempDir

class KWebRfcGovernanceCliTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val hostedEnvironment: Map<String, String> = mapOf(
        "GITHUB_ACTIONS" to "true",
        "GITHUB_REPOSITORY" to "kingsword09/KWebShell",
        "GITHUB_WORKFLOW_REF" to "kingsword09/KWebShell/.github/workflows/ci.yml@refs/heads/main",
        "GITHUB_RUN_ID" to "123456789",
        "GITHUB_RUN_ATTEMPT" to "2",
        "GITHUB_SHA" to "a".repeat(40),
        "RUNNER_OS" to "macOS",
        "RUNNER_ARCH" to "ARM64",
    )

    @Test
    fun hostedRunProvenanceAndTargetComeOnlyFromRunnerEnvironment() {
        assertEquals(
            KWebRfcHostedRun(
                repository = "kingsword09/KWebShell",
                workflowRef = "kingsword09/KWebShell/.github/workflows/ci.yml@refs/heads/main",
                runId = "123456789",
                runAttempt = 2,
                sourceRevision = "a".repeat(40),
            ),
            KWebRfcGovernanceCli.hostedRunFromEnvironment(hostedEnvironment),
        )
        assertEquals("macos-arm64", KWebRfcGovernanceCli.hostedTargetFromEnvironment(hostedEnvironment))
    }

    @Test
    fun localOrUnsupportedRunnerCannotPublishEvidence() {
        val local = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcGovernanceCli.hostedRunFromEnvironment(emptyMap())
        }
        assertEquals("rfc.record.untrusted-environment", local.code)

        val unsupported = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcGovernanceCli.hostedTargetFromEnvironment(
                hostedEnvironment + ("RUNNER_ARCH" to "X64"),
            )
        }
        assertEquals("rfc.record.unsupported-runner", unsupported.code)
    }

    @Test
    fun mergeUnionsPerTargetRecordsInCanonicalOrderWithARecomputedDigest() {
        val first = writeManifest(
            "first.json",
            fixtureRecord(target = "macos-arm64"),
        )
        val second = writeManifest(
            "second.json",
            fixtureRecord(rfcId = "0002", target = "windows-x64", serviceId = "dialogs"),
            fixtureRecord(target = "linux-x64"),
        )
        val output = temporaryDirectory.resolve("merged.json")

        KWebRfcGovernanceCli.mergeManifests(listOf(first, second), output)

        val merged = KWebRfcEvidenceJson.decodeManifest(Files.readString(output))
        val expected = listOf(
            fixtureRecord(target = "linux-x64"),
            fixtureRecord(target = "macos-arm64"),
            fixtureRecord(rfcId = "0002", target = "windows-x64", serviceId = "dialogs"),
        )
        assertEquals(expected, merged.records)
        assertEquals(KWebRfcEvidenceJson.recordsSha256(expected), merged.recordsSha256)
    }

    @Test
    fun mergeIsByteForByteDeterministicAcrossRuns() {
        val first = writeManifest("first.json", fixtureRecord(target = "macos-arm64"))
        val second = writeManifest("second.json", fixtureRecord(target = "windows-x64"))
        val firstOutput = temporaryDirectory.resolve("merged-first.json")
        val secondOutput = temporaryDirectory.resolve("merged-second.json")

        KWebRfcGovernanceCli.mergeManifests(listOf(first, second), firstOutput)
        KWebRfcGovernanceCli.mergeManifests(listOf(second, first), secondOutput)

        assertEquals(Files.readString(firstOutput), Files.readString(secondOutput))
    }

    @Test
    fun mergeRejectsDuplicateRecordIdentities() {
        val first = writeManifest("first.json", fixtureRecord(target = "macos-arm64"))
        val second = writeManifest("second.json", fixtureRecord(target = "macos-arm64"))

        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcGovernanceCli.mergeManifests(
                listOf(first, second),
                temporaryDirectory.resolve("merged.json"),
            )
        }
        assertEquals("rfc.merge.duplicate-record", error.code)
    }

    @Test
    fun mergeRequiresAtLeastTwoInputManifests() {
        val only = writeManifest("only.json", fixtureRecord(target = "macos-arm64"))

        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcGovernanceCli.mergeManifests(
                listOf(only),
                temporaryDirectory.resolve("merged.json"),
            )
        }
        assertEquals("rfc.merge.invalid-argument", error.code)
    }

    @Test
    fun mergeRejectsInputManifestsThatAreNotStrictSchemaJson() {
        val valid = writeManifest("valid.json", fixtureRecord(target = "macos-arm64"))
        val malformed = temporaryDirectory.resolve("malformed.json")
        Files.writeString(malformed, "{\"schemaVersion\": \"not-a-number\"}")

        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcGovernanceCli.mergeManifests(
                listOf(valid, malformed),
                temporaryDirectory.resolve("merged.json"),
            )
        }
        assertEquals(KWebRfcEvidenceErrorCode.INVALID_JSON, error.code)
    }

    private fun writeManifest(name: String, vararg records: KWebRfcEvidenceRecord): Path {
        val path = temporaryDirectory.resolve(name)
        Files.writeString(path, KWebRfcEvidenceJson.encodeManifest(fixtureManifest(records.toList())) + "\n")
        return path
    }
}
