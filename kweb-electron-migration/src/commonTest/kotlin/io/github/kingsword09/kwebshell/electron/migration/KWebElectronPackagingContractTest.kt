package io.github.kingsword09.kwebshell.electron.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebElectronPackagingContractTest {
    @Test
    fun mapsClosedBuilderMetadataToTheApplicationPackagingReport() {
        val manifest = validManifest()
        val report = KWebElectronPackagingMapper.map(manifest)

        assertTrue(report.ready)
        assertEquals("io.github.kwebshell.fixture", report.applicationId)
        assertEquals(listOf("kweb"), report.protocols)
        assertEquals(listOf(".kweb"), report.fileExtensions)
    }

    @Test
    fun unsupportedHooksBecomeBlockingFindings() {
        val report = KWebElectronPackagingMapper.map(validManifest().copy(hooks = listOf("afterPack")))

        assertFalse(report.ready)
        assertEquals(listOf("unsupported-hook:afterPack"), report.blockingFindings)
    }

    @Test
    fun invalidTargetAndNonCanonicalMetadataAreRejected() {
        val invalid = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronPackagingMapper.map(validManifest().copy(targets = listOf("freebsd-x64")))
        }
        assertEquals(KWebElectronMigrationErrorCode.PACKAGING_INVALID, invalid.code)

        val nonCanonical = KWebElectronPackagingMapper.encode(validManifest()).replace("  ", "    ")
        val error = assertFailsWith<KWebElectronMigrationException> {
            KWebElectronPackagingMapper.decode(nonCanonical)
        }
        assertEquals(KWebElectronMigrationErrorCode.PACKAGING_NON_CANONICAL, error.code)
    }

    private fun validManifest(): KWebElectronPackagingManifest = KWebElectronPackagingManifest(
        schemaVersion = 1,
        builder = "electron-builder",
        applicationId = "io.github.kwebshell.fixture",
        productName = "KWebShell",
        version = "0.1.0",
        targets = listOf("macos-arm64", "windows-x64", "linux-x64"),
        protocols = listOf("kweb"),
        fileExtensions = listOf(".kweb"),
    )
}
