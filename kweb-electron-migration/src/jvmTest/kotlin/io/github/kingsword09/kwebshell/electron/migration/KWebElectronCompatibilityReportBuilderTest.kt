package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebElectronCompatibilityReportBuilderTest {
    @Test
    fun reportIsBlockedByDigestAndInventoryEvidence() {
        val root = Files.createTempDirectory("kweb-electron-report")
        try {
            val renderer = root.resolve("renderer").also { it.createDirectories() }
            renderer.resolve("index.js").writeText("console.log('fixture');")
            val manifestPath = root.resolve("migration.json")
            val manifest = KWebElectronMigrationJson.decode(manifestJson())
            manifestPath.writeText(KWebElectronMigrationJson.encode(manifest))
            val generated = root.resolve("generated").also { it.createDirectories() }
            generated.resolve("KWebElectronPreload.js").writeText("generated")
            val inventory = KWebElectronInventoryReport(
                schemaVersion = 1,
                root = root.toString(),
                filesScanned = 1,
                findings = listOf(
                    KWebElectronInventoryFinding(
                        path = "main.ts",
                        line = 3,
                        kind = KWebElectronInventoryFindingKind.ELECTRON_CHANNEL,
                        expression = "secret",
                        matrixId = "ipc-request",
                        status = KWebElectronMappingStatus.ADAPTER,
                        blocking = true,
                    ),
                ),
            )
            val report = KWebElectronCompatibilityReportBuilder().build(
                manifestPath = manifestPath,
                manifest = manifest,
                generatedOutput = generated,
                inventory = inventory,
                runtime = KWebElectronRuntimeIdentity("151.3.16", "151.0.7922.109", "macos-arm64"),
            )
            assertEquals(KWebElectronCompatibilityReport.BLOCKED_STATUS, report.migrationStatus)
            assertTrue(report.blockedReasons.any { it.startsWith("renderer-digest-mismatch") })
            assertTrue(report.blockedReasons.any { it.startsWith("inventory:") })
            assertTrue(!report.migrationReady)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun blockedReportCannotCarryPerformanceComparison() {
        val validDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val report = KWebElectronCompatibilityReport(
            schemaVersion = 1,
            migrationStatus = KWebElectronCompatibilityReport.BLOCKED_STATUS,
            blockedReasons = listOf("unresolved"),
            rendererSha256 = validDigest,
            manifestSha256 = validDigest,
            generatedOutputSha256 = validDigest,
            inventorySha256 = validDigest,
            capabilityMatrixVersion = 1,
            capabilityMatrixSha256 = validDigest,
            serviceContractVersions = mapOf("app-paths" to "1.0.0"),
            cefVersion = "151.3.16",
            chromiumVersion = "151.0.7922.109",
            target = "macos-arm64",
            performanceComparison = KWebElectronPerformanceComparison("electron", validDigest, "local"),
        )
        assertFailsWith<IllegalArgumentException> {
            KWebElectronCompatibilityReportValidator.validate(report)
        }
    }

    private fun manifestJson(): String = """
        {
          "schemaVersion":1,
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronImports":[{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
          "channels":[{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
    """.trimIndent()
}
