package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWebElectronInventoryContractTest {
    @Test
    fun inventoryReportExposesBlockingFindings() {
        val report = KWebElectronInventoryReport(
            schemaVersion = 1,
            root = "/tmp/fixture",
            filesScanned = 2,
            findings = listOf(
                KWebElectronInventoryFinding(
                    path = "main.ts",
                    line = 1,
                    kind = KWebElectronInventoryFindingKind.ELECTRON_IMPORT,
                    expression = "BrowserWindow",
                    matrixId = "browser-window",
                    status = KWebElectronMappingStatus.ADAPTER,
                    blocking = false,
                ),
                KWebElectronInventoryFinding(
                    path = "renderer.ts",
                    line = 4,
                    kind = KWebElectronInventoryFindingKind.NODE_IMPORT,
                    expression = "node:fs",
                    matrixId = "node-runtime",
                    status = KWebElectronMappingStatus.REWRITE,
                    blocking = true,
                ),
            ),
        )
        assertEquals(1, report.blockingFindings.size)
        assertTrue(!report.migrationReady)
    }

    @Test
    fun scannerFindsDestructuredElectronNodeAndChannelUsage() {
        val root = Files.createTempDirectory("kweb-electron-inventory")
        try {
            root.resolve("src").createDirectories()
            root.resolve("src/main.ts").writeText(
                """
                import { contextBridge, ipcRenderer } from "electron";
                import fs from "node:fs";
                import path from "path";
                contextBridge.exposeInMainWorld("desktop", {});
                ipcRenderer.invoke("app.getPath", "home");
                ipcRenderer.invoke("secret-channel", {});
                ipcRenderer.invoke(channelName, {});
                """.trimIndent(),
            )
            root.resolve("package.json").writeText("""{"dependencies":{"electron":"^40.0.0"}}""")
            val report = KWebElectronInventoryScanner().scan(root, manifest())
            assertEquals(2, report.filesScanned)
            assertTrue(report.findings.any { it.expression == "electron.contextBridge" && !it.blocking }, report.findings.toString())
            assertTrue(report.findings.any { it.expression == "electron.ipcRenderer" && !it.blocking })
            assertTrue(report.findings.any { it.expression == "node:fs" && it.blocking })
            assertTrue(report.findings.any { it.expression == "path" && it.blocking })
            assertTrue(report.findings.any { it.kind == KWebElectronInventoryFindingKind.PRELOAD_GLOBAL && !it.blocking })
            assertTrue(report.findings.any { it.expression == "app.getPath" && !it.blocking })
            assertTrue(report.findings.any { it.expression == "secret-channel" && it.blocking })
            assertTrue(report.findings.any { it.expression == "<dynamic>" && it.blocking })
            assertTrue(report.findings.any { it.kind == KWebElectronInventoryFindingKind.PACKAGE_DEPENDENCY && it.blocking })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun manifest(): KWebElectronManifest = KWebElectronMigrationJson.decode(
        """
        {
          "schemaVersion":1,
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[
            {"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"},
            {"module":"electron","symbol":"ipcRenderer","status":"ADAPTER","matrixId":"ipc-request"}
          ],
          "channels":[{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
        """.trimIndent(),
    )
}
