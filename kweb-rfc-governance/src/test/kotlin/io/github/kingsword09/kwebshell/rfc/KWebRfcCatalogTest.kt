package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebRfcCatalogTest {
    @Test
    fun validDocumentParses() {
        val document = KWebRfcCatalog.parse("0001-program-governance.md", fixtureCatalogDocumentText())
        assertEquals("0001", document.id)
        assertEquals("Capability", document.title)
        assertEquals(KWebRfcStatus.IMPLEMENTED, document.status)
        assertEquals("P0", document.priority)
        assertEquals(listOf("existing Phase 11 prerequisites"), document.dependsOn)
        assertEquals(emptySet(), document.declaredPlatformTargets)
        assertEquals(KWEB_RFC_HOSTED_TARGETS, document.requiredHostedTargets)
    }

    @Test
    fun platformSpecificDeclarationNarrowsRequiredTargets() {
        val document = KWebRfcCatalog.parse(
            "0033-macos-special-surfaces.md",
            fixtureCatalogDocumentText(id = "0033", platformTargets = "macOS"),
        )
        assertEquals(setOf("macos"), document.declaredPlatformTargets)
        assertEquals(setOf("macos-arm64"), document.requiredHostedTargets)
    }

    @Test
    fun titleIdMustMatchFileId() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse("0001-program-governance.md", fixtureCatalogDocumentText(id = "0002"))
        }
        assertTrue(error.message!!.contains("title id does not match"))
    }

    @Test
    fun unknownFrontMatterKeyFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse(
                "0001-program-governance.md",
                fixtureCatalogDocumentText(extraLine = "- Statuz: Proposed"),
            )
        }
        assertTrue(error.message!!.contains("not part of the RFC schema"))
    }

    @Test
    fun missingRequiredKeyFails() {
        val text = fixtureCatalogDocumentText().replace("- Owners: repository governance\n", "")
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse("0001-program-governance.md", text)
        }
        assertTrue(error.message!!.contains("missing required front matter"))
    }

    @Test
    fun invalidStatusFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse(
                "0001-program-governance.md",
                fixtureCatalogDocumentText(status = "AlmostDone"),
            )
        }
        assertTrue(error.message!!.contains("status must be one of"))
    }

    @Test
    fun invalidPriorityFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse(
                "0001-program-governance.md",
                fixtureCatalogDocumentText(priority = "P9"),
            )
        }
        assertTrue(error.message!!.contains("priority must be one of"))
    }

    @Test
    fun unknownDependencyTokenFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse(
                "0001-program-governance.md",
                fixtureCatalogDocumentText(dependsOn = "the window host"),
            )
        }
        assertTrue(error.message!!.contains("four-digit RFC id"))
    }

    @Test
    fun dependencyOnUnknownRfcFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.validate(
                listOf(
                    fixtureDocument(id = "0009", dependsOn = listOf("0008")),
                ),
            )
        }
        assertTrue(error.message!!.contains("unknown RFC id"))
    }

    @Test
    fun duplicateRfcIdsFail() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.validate(
                listOf(
                    fixtureDocument(id = "0001"),
                    fixtureDocument(id = "0001"),
                ),
            )
        }
        assertEquals(KWebRfcCatalogErrorCode.DUPLICATE_ID, error.code)
    }

    @Test
    fun implementingRfcWithUnimplementedDependencyFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.validate(
                listOf(
                    fixtureDocument(id = "0001", status = KWebRfcStatus.PROPOSED),
                    fixtureDocument(id = "0002", status = KWebRfcStatus.IMPLEMENTING, dependsOn = listOf("0001")),
                ),
            )
        }
        assertTrue(error.message!!.contains("dependencies to be Implemented"))
    }

    @Test
    fun implementedRfcWithImplementedDependencyPasses() {
        KWebRfcCatalog.validate(
            listOf(
                fixtureDocument(id = "0001", status = KWebRfcStatus.IMPLEMENTED),
                fixtureDocument(id = "0002", status = KWebRfcStatus.IMPLEMENTED, dependsOn = listOf("0001")),
            ),
        )
    }

    @Test
    fun invalidPlatformTargetFails() {
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.parse(
                "0033-macos-special-surfaces.md",
                fixtureCatalogDocumentText(id = "0033", platformTargets = "iOS"),
            )
        }
        assertTrue(error.message!!.contains("platform target must be one of"))
    }

    @Test
    fun loadReadsCatalogDirectoryAndRejectsDuplicateIds() {
        val directory = Files.createTempDirectory("rfc-catalog")
        Files.writeString(directory.resolve("0001-program-governance.md"), fixtureCatalogDocumentText(id = "0001"))
        Files.writeString(directory.resolve("0002-second.md"), fixtureCatalogDocumentText(id = "0002"))
        Files.writeString(directory.resolve("README.md"), "# Catalog\n")
        val loaded = KWebRfcCatalog.load(directory)
        assertEquals(listOf("0001", "0002"), loaded.map { it.id })

        Files.writeString(directory.resolve("0003-third.md"), fixtureCatalogDocumentText(id = "0003"))
        Files.writeString(directory.resolve("0003-other.md"), fixtureCatalogDocumentText(id = "0003"))
        val error = assertFailsWith<KWebRfcGovernanceException> {
            KWebRfcCatalog.load(directory)
        }
        assertEquals(KWebRfcCatalogErrorCode.DUPLICATE_ID, error.code)
    }
}
