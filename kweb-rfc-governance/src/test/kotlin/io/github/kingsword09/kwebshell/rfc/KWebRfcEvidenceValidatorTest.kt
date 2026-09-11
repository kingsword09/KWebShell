package io.github.kingsword09.kwebshell.rfc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebRfcEvidenceValidatorTest {
    @Test
    fun validManifestPasses() {
        val manifest = fixtureManifest(threeHostedTargets())
        KWebRfcEvidenceValidator.validate(manifest)
        assertEquals(3, manifest.records.size)
    }

    @Test
    fun emptyManifestPasses() {
        KWebRfcEvidenceValidator.validate(KWebRfcEvidenceJson.emptyManifest())
    }

    @Test
    fun unknownTopLevelFieldFails() {
        val text = KWebRfcEvidenceJson.encodeManifest(fixtureManifest()) + "\n"
        val edited = text.replace(
            "\"schemaVersion\": 1,",
            "\"schemaVersion\": 1,\n  \"extra\": true,",
        )
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceJson.decodeManifest(edited)
        }
        assertEquals(KWebRfcEvidenceErrorCode.INVALID_JSON, error.code)
    }

    @Test
    fun unknownRecordFieldFails() {
        val records = listOf(fixtureRecord(rfcId = "0001"))
        val text = KWebRfcEvidenceJson.encodeManifest(fixtureManifest(records))
        val edited = text.replace(
            "\"rfcId\": \"0001\",",
            "\"rfcId\": \"0001\",\n    \"handEdited\": 1,",
        )
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceJson.decodeManifest(edited)
        }
        assertEquals(KWebRfcEvidenceErrorCode.INVALID_JSON, error.code)
    }

    @Test
    fun digestChangeWithoutRegenerationFails() {
        val records = threeHostedTargets()
        val manifest = fixtureManifest(records)
        val handEdited = manifest.copy(
            records = manifest.records + fixtureRecord(rfcId = "0001", target = "linux-x64", providerId = "other.hosted"),
        )
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(handEdited)
        }
        assertEquals(KWebRfcEvidenceErrorCode.DIGEST_MISMATCH, error.code)
    }

    @Test
    fun unsupportedSchemaVersionFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(fixtureManifest().copy(schemaVersion = 2))
        }
        assertEquals(KWebRfcEvidenceErrorCode.SCHEMA_UNSUPPORTED, error.code)
    }

    @Test
    fun duplicateRecordIdentityFails() {
        val records = threeHostedTargets() + fixtureRecord(target = "macos-arm64", providerId = "governance.hosted")
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(fixtureManifest(records))
        }
        assertEquals(KWebRfcEvidenceErrorCode.INVALID, error.code)
        assertTrue(error.message!!.contains("Duplicate evidence record identity"))
    }

    @Test
    fun nonHostedTargetFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(fixtureManifest(listOf(fixtureRecord(target = "macos-x64"))))
        }
        assertTrue(error.message!!.contains("hosted verification targets"))
    }

    @Test
    fun nonImplementedRecordStatusFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord().copy(rfcStatus = "Accepted"))),
            )
        }
        assertTrue(error.message!!.contains("Implemented RFC status"))
    }

    @Test
    fun matrixRowClaimWithoutServiceFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(
                    listOf(fixtureRecord(serviceId = null, serviceVersion = null, matrixRowIds = listOf("menu-tray"))),
                ),
            )
        }
        assertTrue(error.message!!.contains("must bind a service contract"))
    }

    @Test
    fun serviceWithoutVersionFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(serviceVersion = null))),
            )
        }
        assertTrue(error.message!!.contains("declare service id and version together"))
    }

    @Test
    fun recordSchemaVersionMismatchFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(schemaVersion = 2))),
            )
        }
        assertTrue(error.message!!.contains("schema version"))
    }

    @Test
    fun invalidElectronFixtureMajorFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(electronFixtureMajor = 0))),
            )
        }
        assertTrue(error.message!!.contains("Electron fixture major"))
    }

    @Test
    fun emptyArtifactsFail() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(artifacts = emptyList()))),
            )
        }
        assertTrue(error.message!!.contains("1..32 artifact digests"))
    }

    @Test
    fun invalidTestRunIdFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(testRunId = "run id with spaces"))),
            )
        }
        assertTrue(error.message!!.contains("test run id"))
    }

    @Test
    fun runnerTokenIsRejected() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(
                    listOf(fixtureRecord(testRunId = "run-ghp_Abcdefghijklmnopqrst")),
                ),
            )
        }
        assertEquals(KWebRfcEvidenceErrorCode.REDACTION, error.code)
        assertEquals("runner-token", error.details["pattern"])
    }

    @Test
    fun privatePathIsRejected() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcRedaction.scanText("title", "Runs under /Users/alice/build")
        }
        assertEquals(KWebRfcEvidenceErrorCode.REDACTION, error.code)
        assertEquals("private-path", error.details["pattern"])
        // The detected value itself is never echoed back into the error.
        assertTrue(!error.message!!.contains("/Users/alice/build"))
    }

    @Test
    fun nativePointerIsRejected() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcEvidenceValidator.validate(
                fixtureManifest(listOf(fixtureRecord(testRunId = "ptr-0xdeadbeefcafe"))),
            )
        }
        assertEquals("native-pointer", error.details["pattern"])
    }

    @Test
    fun canonicalOrderingIsDeterministic() {
        val first = fixtureRecord(target = "linux-x64")
        val second = fixtureRecord(target = "macos-arm64", providerId = "other.hosted")
        val third = fixtureRecord(target = "macos-arm64", providerId = "governance.hosted")
        val digestOne = KWebRfcEvidenceJson.recordsSha256(listOf(first, second, third))
        val digestTwo = KWebRfcEvidenceJson.recordsSha256(listOf(third, first, second))
        assertEquals(digestOne, digestTwo)
        assertEquals(
            KWebRfcEvidenceJson.encodeManifest(fixtureManifest(listOf(first, second, third))),
            KWebRfcEvidenceJson.encodeManifest(fixtureManifest(listOf(third, first, second))),
        )
    }

    @Test
    fun emptyRecordsDigestMatchesSeedManifest() {
        assertEquals(
            "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945",
            KWebRfcEvidenceJson.recordsSha256(emptyList()),
        )
    }
}
