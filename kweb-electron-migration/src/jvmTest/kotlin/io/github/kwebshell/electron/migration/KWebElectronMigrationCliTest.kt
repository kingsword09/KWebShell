package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KWebElectronMigrationCliTest {
    @Test
    fun blockingInventoryExitsWithStructuredJsonError() {
        val root = Files.createTempDirectory("kweb-electron-cli")
        try {
            root.resolve("renderer").createDirectories()
            root.resolve("renderer/renderer.ts").writeText("export const value = 1;\n")
            root.resolve("fixture.ts").writeText("ipcRenderer.invoke(channelName, {});\n")
            val manifestPath = root.resolve("migration-manifest.json")
            manifestPath.writeText(manifestJson())
            val output = root.resolve("inventory.json")
            val java = java.nio.file.Path.of(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
            val process = ProcessBuilder(
                java.toString(),
                "-cp", System.getProperty("java.class.path"),
                "io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli",
                "inventory", root.toString(), manifestPath.toString(), output.toString(),
            ).redirectErrorStream(false).start()
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            assertEquals(2, process.waitFor(), "stdout=$stdout stderr=$stderr")
            assertTrue(stdout.contains("blocking"), "stdout=$stdout stderr=$stderr")
            val error = Json.parseToJsonElement(stderr.trim()).jsonObject
            assertEquals(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, error["code"]?.jsonPrimitive?.content)
            assertTrue(Files.isRegularFile(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun manifestJson(): String = """
        {
          "schemaVersion":1,
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"renderer.ts",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"ipcRenderer","status":"ADAPTER","matrixId":"ipc-request"}],
          "channels":[{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH""adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
    """.trimIndent()
}
