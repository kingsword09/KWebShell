package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

@Serializable
internal data class KWebElectronAstFile(val path: String, val text: String)

@Serializable
internal data class KWebElectronAstInput(val root: String, val files: List<KWebElectronAstFile>)

@Serializable
internal data class KWebElectronAstFact(
    val path: String,
    val line: Int,
    val column: Int,
    val kind: KWebElectronInventoryFindingKind,
    val expression: String,
    val symbol: String? = null,
    val operation: String? = null,
    val reExport: Boolean = false,
    val exports: List<String> = emptyList(),
)

public class KWebElectronInventoryScanner public constructor(
    private val matrix: List<KWebElectronCapabilityMatrixEntry> = KWebElectronCapabilityMatrix.entries,
) {
    public fun scan(root: Path, manifest: KWebElectronManifest? = null): KWebElectronInventoryReport {
        val normalized = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalized)) fail("The inventory root must be a directory.")
        manifest?.let(KWebElectronManifestValidator::validate)
        val sources = KWebElectronFiles.sources(normalized)
        val parserFiles = sources.filter { (name, _) -> name.endsWith("package.json") || name.substringAfterLast('.') in PARSED_EXTENSIONS }
            .map { (name, _) -> KWebElectronAstFile(name, Files.readString(normalized.resolve(name))) }
        val findings = parse(KWebElectronAstInput(normalized.toString(), parserFiles)).map { fact -> classify(fact, manifest) }.toMutableList()
        sources.keys.filter { it.endsWith(".node") || it.endsWith("binding.gyp") || it.endsWith(".vue") || it.endsWith(".svelte") }.forEach { name ->
            findings += KWebElectronInventoryFinding(name, 1, kind = if (name.endsWith(".node") || name.endsWith("binding.gyp")) KWebElectronInventoryFindingKind.NATIVE_ADDON else KWebElectronInventoryFindingKind.UNCLASSIFIED, expression = name, blocking = true,
                detail = "This source format requires an explicit migration and cannot be classified as ready.")
        }
        return KWebElectronInventoryReport(
            schemaVersion = KWebElectronInventoryReport.CURRENT_SCHEMA_VERSION,
            root = normalized.toString(),
            filesScanned = sources.size,
            findings = findings.distinct().sortedWith(compareBy<KWebElectronInventoryFinding> { it.path }.thenBy { it.line }.thenBy { it.column }.thenBy { it.kind.name }.thenBy { it.expression }),
            sourceSha256 = KWebElectronFiles.digestEntries(sources),
            lockfileSha256 = KWebElectronFiles.digestEntries(KWebElectronFiles.lockfiles(normalized)),
        )
    }

    private fun parse(input: KWebElectronAstInput): List<KWebElectronAstFact> {
        val directory = Files.createTempDirectory("kweb-migration-parser")
        try {
            val script = directory.resolve("inventory.cjs")
            val resource = javaClass.getResourceAsStream("inventory.cjs") ?: fail("The packaged TypeScript inventory parser is missing.")
            resource.use { Files.copy(it, script) }
            val output = directory.resolve("output.json")
            val errors = directory.resolve("errors.txt")
            val typescript = Path.of(System.getProperty("kweb.migration.typescript", "node_modules/typescript-ast")).toAbsolutePath().normalize()
            if (!Files.isRegularFile(typescript.resolve("package.json"))) fail("Install pinned TypeScript 6.0.2 with npm ci and set -Dkweb.migration.typescript to its module directory.")
            val process = try {
                ProcessBuilder(System.getProperty("kweb.migration.node", "node"), script.toString(), typescript.toString())
                    .redirectOutput(output.toFile()).redirectError(errors.toFile()).start()
            } catch (error: java.io.IOException) {
                throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.PARSER_UNAVAILABLE, "Node.js is required for migration inventory; configure kweb.migration.node.", cause = error)
            }
            try {
                process.outputStream.bufferedWriter().use { it.write(KWebElectronMigrationJson.format.encodeToString(input)) }
                if (!process.waitFor(60, TimeUnit.SECONDS)) fail("The TypeScript parser exceeded its 60 second limit.")
                if (process.exitValue() != 0) fail("TypeScript inventory failed: ${Files.readString(errors).take(2048)}")
                return KWebElectronMigrationJson.format.decodeFromString(Files.readString(output))
            } finally {
                if (process.isAlive) process.destroyForcibly().waitFor()
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun classify(fact: KWebElectronAstFact, manifest: KWebElectronManifest?): KWebElectronInventoryFinding {
        val mapping = when (fact.kind) {
            KWebElectronInventoryFindingKind.ELECTRON_IMPORT -> mappingForElectron(fact.symbol)
            KWebElectronInventoryFindingKind.ELECTRON_CHANNEL -> matrix.singleOrNull { it.id == "ipc-request" }
            KWebElectronInventoryFindingKind.PRELOAD_GLOBAL -> matrix.singleOrNull { it.id == "context-bridge" }
            KWebElectronInventoryFindingKind.NODE_IMPORT, KWebElectronInventoryFindingKind.NATIVE_ADDON -> matrix.singleOrNull { it.id == "node-runtime" }
            else -> null
        }
        val declaredMethods = manifest?.preloadMethods.orEmpty().map { it.name }.toSet() + manifest?.streams.orEmpty().map { it.name }
        val unresolved = when (fact.kind) {
            KWebElectronInventoryFindingKind.ELECTRON_IMPORT -> fact.reExport || manifest?.electronImports?.none { it.symbol == fact.symbol && it.matrixId == mapping?.id } != false
            KWebElectronInventoryFindingKind.ELECTRON_CHANNEL -> fact.expression == "<dynamic>" ||
                fact.operation !in setOf("electron.ipcMain.handle", "electron.ipcRenderer.invoke") ||
                manifest?.channels?.none { it.name == fact.expression && it.status == KWebElectronMappingStatus.ADAPTER } != false
            KWebElectronInventoryFindingKind.PRELOAD_GLOBAL -> fact.expression != manifest?.rendererGlobal || fact.exports.any { it !in declaredMethods }
            else -> true
        }
        return KWebElectronInventoryFinding(
            path = fact.path, line = fact.line, column = fact.column, kind = fact.kind, expression = fact.expression,
            matrixId = mapping?.id, status = mapping?.status,
            blocking = unresolved || mapping == null || mapping.status in setOf(KWebElectronMappingStatus.REWRITE, KWebElectronMappingStatus.UNSUPPORTED),
            detail = fact.operation,
        )
    }

    private fun mappingForElectron(symbol: String?): KWebElectronCapabilityMatrixEntry? = matrix.singleOrNull { it.id == when (symbol) {
        "BrowserWindow" -> "browser-window"
        "webContents" -> "web-contents"
        "session" -> "session-partition"
        "protocol" -> "profile-protocol"
        "ipcMain", "ipcRenderer" -> "ipc-request"
        "contextBridge" -> "context-bridge"
        "dialog" -> "dialog"
        "clipboard" -> "clipboard"
        "shell" -> "shell"
        "nativeTheme" -> "native-theme"
        "screen" -> "screen"
        "globalShortcut" -> "global-shortcut"
        "Menu", "Tray" -> "menu-tray"
        "utilityProcess", "child_process" -> "process"
        "autoUpdater" -> "auto-updater"
        else -> null
    } }

    private fun fail(message: String): Nothing = throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.PARSER_UNAVAILABLE, message)

    private companion object {
        val PARSED_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts")
    }
}
