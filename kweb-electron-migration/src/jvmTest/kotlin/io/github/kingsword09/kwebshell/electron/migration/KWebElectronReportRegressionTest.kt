package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KWebElectronReportRegressionTest {
    private fun fixture(test: (Path, Path, KWebElectronManifest, KWebElectronInventoryReport) -> Unit) {
        val temporary = Files.createTempDirectory("migration-provenance")
        try {
            val root = temporary.resolve("application")
            Path.of("kweb-electron-migration/src/jvmTest/resources/migration-fixture").toFile().copyRecursively(root.toFile())
            val manifest = KWebElectronMigrationJson.decode(Files.readString(root.resolve("migration-manifest.json")))
            val generated = temporary.resolve("generated")
            KWebElectronPreloadGenerator().generate(manifest, generated)
            test(root, generated, manifest, KWebElectronInventoryScanner().scan(root, manifest))
        } finally {
            temporary.toFile().deleteRecursively()
        }
    }

    private fun build(root: Path, generated: Path, manifest: KWebElectronManifest, inventory: KWebElectronInventoryReport): KWebElectronCompatibilityReport =
        KWebElectronCompatibilityReportBuilder().build(
            root.resolve("migration-manifest.json"), manifest, generated, inventory,
            KWebElectronReportProvenance(Path.of("runtime/cef-runtime.json"), Path.of("docs/rfcs/evidence/manifest.json"), Path.of("docs/rfcs"), "macos-arm64"),
        )

    @Test fun lockfileAndNonRendererSourceChangesInvalidateInventory() = fixture { root, generated, manifest, inventory ->
        val before = build(root, generated, manifest, inventory)
        assertTrue(before.migrationReady)
        root.resolve("package-lock.json").writeText("{\"lockfileVersion\":3,\"packages\":{}}")
        val changedLock = build(root, generated, manifest, inventory)
        assertFalse(changedLock.migrationReady)
        assertNotEquals(before.lockfileSha256, changedLock.lockfileSha256)
        root.resolve("preload.ts").writeText("// changed source outside renderer\n")
        val changedSource = build(root, generated, manifest, inventory)
        assertNotEquals(before.sourceSha256, changedSource.sourceSha256)
        assertTrue("inventory-source-digest-mismatch" in changedSource.blockedReasons)
    }

    @Test fun unsupportedDeclarationsBlockReportsEvenWithPreviouslyGeneratedFacade() = fixture { root, generated, manifest, inventory ->
        val blocked = manifest.copy(nodeDependencies = listOf(KWebElectronNodeDependency("better-sqlite3", KWebElectronDependencyKind.NATIVE_ADDON, KWebElectronMappingStatus.UNSUPPORTED)))
        root.resolve("migration-manifest.json").writeText(KWebElectronMigrationJson.encode(blocked))
        val report = build(root, generated, blocked, inventory)
        assertFalse(report.migrationReady)
        assertTrue(report.blockedReasons.any { it.startsWith("manifest-dependency:") })
    }

    @Test fun aggregatePreservesEntryPoliciesAndIsOrderIndependent() = fixture { root, generated, manifest, inventory ->
        val first = build(root, generated, manifest, inventory)
        val second = first.copy(entryId = "second", rendererOrigin = "app://restricted", policies = mapOf("channel:restricted" to KWebElectronChannelPolicy("native.restricted", true, true)))
        val builder = KWebElectronCompatibilityReportBuilder()
        val merged = builder.merge(listOf(first, second))
        assertEquals(merged, builder.merge(listOf(second, first)))
        assertEquals(second, merged.entries.single { it.entryId == "second" })
        val swappedPolicies = builder.merge(listOf(first.copy(policies = second.policies), second.copy(policies = first.policies)))
        assertNotEquals(merged.entriesSha256, swappedPolicies.entriesSha256)
    }

    @Test fun blockedMergeWritesReportAndExitsTwo() = fixture { root, generated, manifest, inventory ->
        val first = build(root, generated, manifest, inventory)
        val second = first.copy(entryId = "second", migrationStatus = "BLOCKED", blockedReasons = listOf("permission-denied"))
        val a = root.resolve("a.json").also { it.writeText(KWebElectronMigrationJson.format.encodeToString(first)) }
        val b = root.resolve("b.json").also { it.writeText(KWebElectronMigrationJson.format.encodeToString(second)) }
        val output = root.resolve("merged.json")
        val java = Path.of(System.getProperty("java.home"), "bin", if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), KWebElectronMigrationCli::class.java.name, "merge-reports", output.toString(), a.toString(), b.toString()).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertEquals(2, process.waitFor(), text)
        val aggregate = KWebElectronMigrationJson.format.decodeFromString<KWebElectronAggregateReport>(Files.readString(output))
        assertFalse(aggregate.migrationReady)
        assertTrue(text.contains(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED))
    }
}
