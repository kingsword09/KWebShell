package io.github.kingsword09.kwebshell.example.html5

import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CapabilityLabContractTest {
    @Test
    fun manifestIsUniqueAndEveryProbeHasOneDefinition() {
        val definitions = CapabilityLabManifest.definitions
        assertTrue(definitions.size >= 20)
        assertEquals(definitions.size, definitions.map { it.id }.toSet().size)
        assertTrue(definitions.all { it.id.isNotBlank() && it.category.isNotBlank() })
    }

    @Test
    fun validatorRejectsMissingRequiredEvidence() {
        val report = sampleCapabilityPageReport().copy(
            probes = sampleCapabilityPageReport().probes.drop(1),
        )
        val error = assertFailsWith<CapabilityLabException> {
            CapabilityLabValidator.validatePage(report, "http://127.0.0.1:1", "cold")
        }
        assertEquals("probe-count", error.code)
    }

    @Test
    fun validatorRejectsFabricatedEmptyEvidence() {
        val report = sampleCapabilityPageReport().copy(
            probes = sampleCapabilityPageReport().probes.map { probe ->
                if (probe.id == "language.es-modules") probe.copy(evidence = emptyMap()) else probe
            },
        )
        val error = assertFailsWith<CapabilityLabException> {
            CapabilityLabValidator.validatePage(report, "http://127.0.0.1:1", "cold")
        }
        assertEquals("probe-evidence", error.code)
    }

    @Test
    fun validatorRejectsOptionalProbeExecutionFailure() {
        val report = sampleCapabilityPageReport().copy(
            probes = sampleCapabilityPageReport().probes.map { probe ->
                if (probe.requirement == ProbeRequirement.OPTIONAL) {
                    probe.copy(status = ProbeStatus.FAIL, reason = "execution-failed")
                } else {
                    probe
                }
            },
        )
        val error = assertFailsWith<CapabilityLabException> {
            CapabilityLabValidator.validatePage(report, "http://127.0.0.1:1", "cold")
        }
        assertEquals("probe-failed", error.code)
    }

    @Test
    fun reportJsonIsStrictAndRoundTrips() {
        val report = sampleCapabilityPageReport()
        val encoded = CapabilityLabJson.format.encodeToString(report)
        val decoded = CapabilityLabJson.format.decodeFromString<CapabilityPageReport>(encoded)
        assertEquals(report, decoded)
        assertFailsWith<Exception> {
            CapabilityLabJson.format.decodeFromString<CapabilityPageReport>("{\"schemaVersion\":1}")
        }
    }

    @Test
    fun bundleRequiresColdAndWarmPersistenceEvidence() {
        val bundle = sampleCapabilityBundle()
        CapabilityLabValidator.validateBundle(bundle, "http://127.0.0.1:1")
    }
}
