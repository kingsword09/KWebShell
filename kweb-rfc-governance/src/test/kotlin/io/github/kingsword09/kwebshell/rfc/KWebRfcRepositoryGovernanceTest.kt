package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs the real governance join against the checked-in repository: the actual RFC
 * catalog, the checked-in evidence manifest, the pinned runtime identity, and the
 * packaged schema document. This is the same contract the `rfcGovernanceCheck`
 * Gradle task enforces in CI.
 */
class KWebRfcRepositoryGovernanceTest {
    private val repositoryRoot: java.nio.file.Path
        get() = java.nio.file.Path.of(
            requireNotNull(System.getProperty("kweb.rfc.repository.root")) {
                "The repository root must be provided as the kweb.rfc.repository.root system property."
            },
        )

    @Test
    fun checkedInCatalogAndManifestAreGoverned() {
        val catalog = KWebRfcCatalog.load(repositoryRoot.resolve("docs/rfcs"))
        assertTrue(catalog.size >= 42, "The RFC program defines 42 RFCs; found ${catalog.size}.")
        assertEquals("0001", catalog.first().id)

        val manifest = KWebRfcEvidenceJson.decodeManifest(
            Files.readString(repositoryRoot.resolve("docs/rfcs/evidence/manifest.json")),
        )
        val runtime = KWebRfcRuntimeIdentity.load(repositoryRoot.resolve("runtime/cef-runtime.json"))
        val contractBindings = KWebRfcContractBindings.load(
            repositoryRoot.resolve("docs/rfcs/evidence/contracts.json"),
        )
        assertTrue(runtime.chromiumVersion.startsWith("151."), "The pinned Chromium major must stay 151.")
        (1..4).forEach { number ->
            val rfcId = number.toString().padStart(4, '0')
            assertEquals(
                64,
                KWebRfcContractBindings.digest(contractBindings, repositoryRoot, rfcId).length,
            )
        }

        val report = KWebRfcGovernanceChecker(
            catalog,
            manifest,
            io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrix.document(),
            runtime,
            KWebRfcContractDigestProvider.forRepository(contractBindings, repositoryRoot),
            KWebRfcArtifactDigestProvider.forRepository(repositoryRoot),
        ).check()
        assertEquals(KWebRfcGovernanceReport.READY, report.governanceStatus)
        val hostedTargets = listOf("linux-x64", "macos-arm64", "windows-x64").sorted()
        val rfc0001 = report.rfcs.single { it.rfcId == "0001" }
        assertEquals(KWebRfcStatus.IMPLEMENTED.label, rfc0001.declaredStatus)
        assertEquals(KWebRfcGovernanceState.READY, rfc0001.state)
        assertEquals(hostedTargets, rfc0001.evidenceTargets)
        val rfc0002 = report.rfcs.single { it.rfcId == "0002" }
        assertEquals(KWebRfcStatus.IMPLEMENTED.label, rfc0002.declaredStatus)
        assertEquals(KWebRfcGovernanceState.READY, rfc0002.state)
        assertEquals(hostedTargets, rfc0002.evidenceTargets)
        val rfc0003 = report.rfcs.single { it.rfcId == "0003" }
        assertEquals(KWebRfcStatus.IMPLEMENTED.label, rfc0003.declaredStatus)
        assertEquals(KWebRfcGovernanceState.READY, rfc0003.state)
        assertEquals(hostedTargets, rfc0003.evidenceTargets)
        val rfc0004 = report.rfcs.single { it.rfcId == "0004" }
        assertEquals(KWebRfcStatus.ACCEPTED.label, rfc0004.declaredStatus)
        assertEquals(KWebRfcGovernanceState.READY, rfc0004.state)
        assertTrue(rfc0004.evidenceTargets.isEmpty())
    }

    @Test
    fun packagedSchemaMatchesTheValidatorModel() {
        val schemaText = KWebRfcRepositoryGovernanceTest::class.java.classLoader
            .getResourceAsStream("io/github/kingsword09/kwebshell/rfc/rfc-evidence-manifest.schema.json")!!
            .readBytes()
            .decodeToString()
        val schema = Json.parseToJsonElement(schemaText).jsonObject
        val manifestProperties = schema.getValue("properties").jsonObject
        assertEquals(
            2,
            manifestProperties.getValue("schemaVersion").jsonObject.getValue("const").jsonPrimitive.content.toInt(),
        )
        val recordsSpec = manifestProperties.getValue("records").jsonObject
        val recordProperties = recordsSpec.getValue("items").jsonObject.getValue("properties").jsonObject
        assertEquals(KWebRfcEvidenceValidator.RECORD_FIELDS.sorted(), recordProperties.keys.sorted())
        val recordRequired = recordsSpec.getValue("items").jsonObject.getValue("required").jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(KWebRfcEvidenceValidator.RECORD_FIELDS.sorted(), recordRequired.sorted())
        val artifactProperties = recordProperties
            .getValue("artifacts").jsonObject
            .getValue("items").jsonObject
            .getValue("properties").jsonObject
        assertEquals(KWebRfcEvidenceValidator.ARTIFACT_FIELDS.sorted(), artifactProperties.keys.sorted())
        val runProperties = recordProperties
            .getValue("run").jsonObject
            .getValue("properties").jsonObject
        assertEquals(KWebRfcEvidenceValidator.RUN_FIELDS.sorted(), runProperties.keys.sorted())
    }
}
