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
            val result = runCli("inventory", root.toString(), manifestPath.toString(), output.toString())
            assertEquals(2, result.exitCode, "stdout=${result.stdout} stderr=${result.stderr}")
            assertTrue(result.stdout.contains("blocking"), "stdout=${result.stdout} stderr=${result.stderr}")
            assertEquals(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, errorCode(result))
            assertTrue(Files.isRegularFile(output))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrateCommandWritesDeterministicV2WithoutChangingInput() {
        val root = Files.createTempDirectory("kweb-electron-migrate-cli")
        try {
            val input = root.resolve("migration-v1.json")
            input.writeText(v1ManifestJson())
            val inputBefore = Files.readAllBytes(input).toList()
            val firstOutput = root.resolve("first/output/v2.json")

            val first = runCli("migrate", input.toString(), firstOutput.toString(), "app://fixture")
            assertEquals(0, first.exitCode, "stdout=${first.stdout} stderr=${first.stderr}")
            assertEquals("Electron migration manifest migrated to v2.\n", first.stdout)
            assertEquals("", first.stderr)
            assertTrue(Files.isRegularFile(firstOutput))

            val migrated = KWebElectronMigrationJson.decode(Files.readString(firstOutput))
            assertEquals(2, migrated.schemaVersion)
            assertEquals("app://fixture", migrated.rendererOrigin)
            assertEquals(2, migrated.channels.single().schemaVersion)
            assertEquals("app.getPath", migrated.channels.single().name)
            assertEquals("contextBridge", migrated.electronImports.single().symbol)
            assertEquals("getPath", migrated.preloadMethods.single().name)
            assertEquals("app-paths", migrated.requiredServices.single().id)
            assertEquals("default", migrated.rendererProfile)
            assertEquals("default", migrated.profiles.single().id)
            assertEquals("profiles/default", migrated.profiles.single().storagePath)
            assertEquals("main", migrated.windows.single().id)
            assertEquals(inputBefore, Files.readAllBytes(input).toList())

            val secondOutput = root.resolve("second/output/v2.json")
            val second = runCli("migrate", input.toString(), secondOutput.toString(), "app://fixture")
            assertEquals(0, second.exitCode, "stdout=${second.stdout} stderr=${second.stderr}")
            assertEquals(Files.readAllBytes(firstOutput).toList(), Files.readAllBytes(secondOutput).toList())
            assertEquals(
                KWebElectronMigrationJson.encode(migrated) + "\n",
                Files.readString(firstOutput),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrateCommandReturnsTypedErrorsBeforeChangingDestination() {
        val root = Files.createTempDirectory("kweb-electron-migrate-errors")
        try {
            val missingOutput = root.resolve("missing.json")
            val badArguments = runCli("migrate", "input.json", missingOutput.toString())
            assertEquals(2, badArguments.exitCode)
            assertEquals("", badArguments.stdout)
            assertEquals(KWebElectronMigrationErrorCode.COMMAND_INVALID_ARGUMENTS, errorCode(badArguments))
            assertTrue(!Files.exists(missingOutput))

            val extraArguments = runCli(
                "migrate", "input.json", missingOutput.toString(), "app://fixture", "extra",
            )
            assertEquals(2, extraArguments.exitCode)
            assertEquals(KWebElectronMigrationErrorCode.COMMAND_INVALID_ARGUMENTS, errorCode(extraArguments))
            assertTrue(!Files.exists(missingOutput))

            val invalidCases = listOf(
                Triple(
                    "malformed-source",
                    "{\"schemaVersion\":1",
                    KWebElectronMigrationErrorCode.MANIFEST_INVALID_JSON,
                ),
                Triple(
                    "unknown-manifest-version",
                    v1ManifestJson().replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":99"),
                    KWebElectronMigrationErrorCode.MANIFEST_SCHEMA_UNSUPPORTED,
                ),
                Triple(
                    "unknown-channel-version",
                    v1ManifestJson().replace("\"name\":\"app.getPath\",\"schemaVersion\":1", "\"name\":\"app.getPath\",\"schemaVersion\":9"),
                    KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE,
                ),
                Triple(
                    "invalid-origin",
                    v1ManifestJson(),
                    KWebElectronMigrationErrorCode.MANIFEST_INVALID,
                ),
            )
            invalidCases.forEach { (name, source, expectedCode) ->
                val input = root.resolve("$name.json")
                val output = root.resolve("$name-output.json")
                val sentinel = "keep existing output: $name\n"
                input.writeText(source)
                output.writeText(sentinel)
                val origin = if (name == "invalid-origin") "https://example.com/path" else "app://fixture"

                val result = runCli("migrate", input.toString(), output.toString(), origin)
                assertEquals(2, result.exitCode, "case=$name stdout=${result.stdout} stderr=${result.stderr}")
                assertEquals("", result.stdout)
                assertEquals(expectedCode, errorCode(result), "case=$name stderr=${result.stderr}")
                assertEquals(sentinel, Files.readString(output), name)
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun migrateCommandRejectsInputOutputCollisionWithoutModifyingInput() {
        val root = Files.createTempDirectory("kweb-electron-migrate-collision")
        try {
            val input = root.resolve("migration-v1.json")
            input.writeText(v1ManifestJson())
            val inputBefore = Files.readAllBytes(input).toList()
            val outputAlias = input.parent.toString() + java.io.File.separator + "." +
                java.io.File.separator + input.fileName
            assertTrue(outputAlias != input.toString())
            val result = runCli("migrate", input.toString(), outputAlias, "app://fixture")

            assertEquals(2, result.exitCode)
            assertEquals("", result.stdout)
            assertEquals(KWebElectronMigrationErrorCode.MANIFEST_INPUT_OUTPUT_CONFLICT, errorCode(result))
            assertEquals(inputBefore, Files.readAllBytes(input).toList())
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun runCli(vararg arguments: String): CliResult {
        val java = java.nio.file.Path.of(
            System.getProperty("java.home"),
            "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
        )
        val process = ProcessBuilder(
            java.toString(),
            "-Dkweb.migration.typescript=${System.getProperty("kweb.migration.typescript")}",
            "-cp", System.getProperty("java.class.path"),
            "io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli",
            *arguments,
        ).redirectErrorStream(false).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        return CliResult(process.waitFor(), stdout, stderr)
    }

    private fun errorCode(result: CliResult): String {
        val error = Json.parseToJsonElement(result.stderr.trim()).jsonObject
        assertEquals(setOf("code", "message", "details"), error.keys)
        assertTrue(error["message"]!!.jsonPrimitive.content.isNotBlank())
        assertTrue(error["details"] is kotlinx.serialization.json.JsonObject)
        return error["code"]!!.jsonPrimitive.content
    }

    private data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun v1ManifestJson(): String = """
        {
          "schemaVersion":1,
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"index.html",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"contextBridge","status":"ADAPTER","matrixId":"context-bridge"}],
          "channels":[{"name":"app.getPath","schemaVersion":1,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
    """.trimIndent()

    private fun manifestJson(): String = """
        {
          "schemaVersion":2,
          "rendererOrigin":"app://fixture",
          "rendererProfile":"default",
          "profiles":[{"id":"default","storagePath":"profiles/default","isPersistent":true}],
          "applicationId":"io.github.kwebshell.fixture",
          "rendererGlobal":"desktop",
          "rendererRoot":"renderer",
          "rendererEntry":"renderer.ts",
          "rendererSha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "electronFixtureMajor":44,
          "electronImports":[{"module":"electron","symbol":"ipcRenderer","status":"ADAPTER","matrixId":"ipc-request"}],
          "channels":[{"name":"app.getPath","schemaVersion":2,"requestType":"ElectronPathName","responseType":"string","serviceId":"app-paths","serviceVersion":"1.0.0","operationId":"resolve","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH","policy":{"rendererGrant":"native.app-paths.resolve","requiresUserGesture":false,"requiresOsConsent":false}}],
          "preloadMethods":[{"name":"getPath","channel":"app.getPath","parameterName":"name","parameterType":"ElectronPathName","returnType":"Promise<string>","status":"ADAPTER","adapter":"APP_PATHS_GET_PATH"}],
          "requiredServices":[{"id":"app-paths","version":"1.0.0"}]
        }
    """.trimIndent()
}
