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
                schemaVersion = 2,
            sourceSha256 = "a".repeat(64),
            lockfileSha256 = "b".repeat(64),
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
                provenance = KWebElectronReportProvenance(java.nio.file.Path.of("runtime/cef-runtime.json"), java.nio.file.Path.of("docs/rfcs/evidence/manifest.json"), java.nio.file.Path.of("docs/rfcs"), "macos-arm64"),
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
    fun mergeReportsCombinesBlockedReasonsAndHashes() {
        val validDigest1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val validDigest2 = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val report1 = KWebElectronCompatibilityReport(
            schemaVersion = 2,
            applicationId = "io.github.kwebshell.fixture",
            entryId = "entry-1",
            rendererOrigin = "app://fixture",
            rendererProfile = "default",
            policies = mapOf("channel:app.getPath" to io.github.kingsword09.kwebshell.electron.migration.KWebElectronChannelPolicy("native.app-paths.resolve", false, false)),
            sourceSha256 = "a".repeat(64),
            lockfileSha256 = "b".repeat(64),
            rfcEvidenceSha256 = "c".repeat(64),
            rfcCatalogSha256 = "d".repeat(64),
            runtimeSha256 = "e".repeat(64),
            runtimeArtifactSha256 = "f".repeat(64),
            electronFixtureMajor = 44,
            migrationStatus = KWebElectronCompatibilityReport.READY_STATUS,
            blockedReasons = emptyList(),
            rendererSha256 = validDigest1,
            manifestSha256 = validDigest1,
            generatedOutputSha256 = validDigest1,
            inventorySha256 = validDigest1,
            capabilityMatrixVersion = 1,
            capabilityMatrixSha256 = validDigest1,
            serviceContractVersions = mapOf("app-paths" to "1.0.0"),
            cefVersion = "151.3.16",
            chromiumVersion = "151.0.7922.109",
            target = "macos-arm64",
        )
        val report2 = KWebElectronCompatibilityReport(
            schemaVersion = 2,
            applicationId = "io.github.kwebshell.fixture",
            entryId = "entry-2",
            rendererOrigin = "app://fixture",
            rendererProfile = "default",
            policies = mapOf("channel:app.getPath" to io.github.kingsword09.kwebshell.electron.migration.KWebElectronChannelPolicy("native.app-paths.resolve", false, false)),
            sourceSha256 = "a".repeat(64),
            lockfileSha256 = "b".repeat(64),
            rfcEvidenceSha256 = "c".repeat(64),
            rfcCatalogSha256 = "d".repeat(64),
            runtimeSha256 = "e".repeat(64),
            runtimeArtifactSha256 = "f".repeat(64),
            electronFixtureMajor = 44,
            migrationStatus = KWebElectronCompatibilityReport.BLOCKED_STATUS,
            blockedReasons = listOf("renderer-entry-missing:sub.html"),
            rendererSha256 = validDigest2,
            manifestSha256 = validDigest2,
            generatedOutputSha256 = validDigest2,
            inventorySha256 = validDigest2,
            capabilityMatrixVersion = 1,
            capabilityMatrixSha256 = validDigest1,
            serviceContractVersions = mapOf("app-paths" to "1.0.0"),
            cefVersion = "151.3.16",
            chromiumVersion = "151.0.7922.109",
            target = "macos-arm64",
        )
        assertFailsWith<KWebElectronMigrationException> {
            KWebElectronCompatibilityReportBuilder().merge(listOf(report1, report2.copy(target = "windows-x64")))
        }
        assertFailsWith<KWebElectronMigrationException> {
            KWebElectronCompatibilityReportBuilder().merge(listOf(report1, report2.copy(serviceContractVersions = mapOf("app-paths" to "2.0.0"))))
        }
        val merged = KWebElectronCompatibilityReportBuilder().merge(listOf(report1, report2))
        assertEquals(KWebElectronCompatibilityReport.BLOCKED_STATUS, merged.migrationStatus)
        assertEquals(listOf("entry-2:renderer-entry-missing:sub.html"), merged.blockedReasons)
        assertTrue(!merged.migrationReady)
    }

    @Test
    fun blockedReportCannotCarryPerformanceComparison() {
        val validDigest = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val report = KWebElectronCompatibilityReport(
            schemaVersion = 2,
            applicationId = "io.github.kwebshell.fixture",
            entryId = "entry-3",
            rendererOrigin = "app://fixture",
            rendererProfile = "default",
            policies = mapOf("channel:app.getPath" to io.github.kingsword09.kwebshell.electron.migration.KWebElectronChannelPolicy("native.app-paths.resolve", false, false)),
            sourceSha256 = "a".repeat(64),
            lockfileSha256 = "b".repeat(64),
            rfcEvidenceSha256 = "c".repeat(64),
            rfcCatalogSha256 = "d".repeat(64),
            runtimeSha256 = "e".repeat(64),
            runtimeArtifactSha256 = "f".repeat(64),
            electronFixtureMajor = 44,
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
          "schemaVersion":2,
          "rendererOrigin":"app://fixture",
          "rendererProfile":"default",
          "profiles":[{"id":"default","storagePath":"profiles/default","isPersistent":true}],
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
          "channels":[{"name":"app.getPath","schemaVersion":2,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
    """.trimIndent()
}
