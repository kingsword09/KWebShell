package io.github.kingsword09.kwebshell.rfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebRfcGovernanceCliTest {
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
}
