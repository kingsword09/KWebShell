package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

public class KWebElectronInventoryScanner public constructor(
    private val matrix: List<KWebElectronCapabilityMatrixEntry> = KWebElectronCapabilityMatrix.entries,
) {
    public fun scan(root: Path, manifest: KWebElectronManifest? = null): KWebElectronInventoryReport {
        val normalizedRoot = root.toAbsolutePath().normalize()
        if (!Files.isDirectory(normalizedRoot)) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                details = mapOf("root" to normalizedRoot.toString()),
                message = "The Electron inventory root is not a directory.",
            )
        }
        manifest?.let(KWebElectronManifestValidator::validate)
        val files = Files.walk(normalizedRoot).use { stream ->
            stream.filter(Files::isRegularFile)
                .filter { path -> isScannedSource(path) || path.fileName.toString() == "package.json" }
                .sorted()
                .collect(Collectors.toList())
        }
        val findings = files.flatMap { path ->
            if (path.fileName.toString() == "package.json") {
                scanPackageJson(normalizedRoot, path)
            } else {
                scanSource(normalizedRoot, path, manifest)
            }
        }.sortedWith(
            compareBy<KWebElectronInventoryFinding> { it.path }
                .thenBy { it.line }
                .thenBy { it.kind.name }
                .thenBy { it.expression },
        )
        return KWebElectronInventoryReport(
            schemaVersion = KWebElectronInventoryReport.CURRENT_SCHEMA_VERSION,
            root = normalizedRoot.toString(),
            filesScanned = files.size,
            findings = findings,
        )
    }

    private fun scanSource(
        root: Path,
        path: Path,
        manifest: KWebElectronManifest?,
    ): List<KWebElectronInventoryFinding> {
        val relative = root.relativize(path).toString().replace(path.fileSystem.getSeparator(), "/")
        val source = Files.readString(path)
        val findings = mutableListOf<KWebElectronInventoryFinding>()

        val destructuredImports = ELECTRON_DESTRUCTURED_IMPORT.findAll(source).toList()
        destructuredImports.forEach { match ->
            listOf(match.groupValues[1], match.groupValues.getOrElse(2) { "" })
                .asSequence()
                .flatMap { it.split(',').asSequence() }
                .map { it.trim().substringBefore(" as ").trim() }
                .filter(String::isNotBlank)
                .distinct()
                .forEach { symbol ->
                    val mapping = mappingForElectron(symbol)
                    val declared = manifest?.electronImports?.firstOrNull {
                        it.module == "electron" && it.symbol == symbol
                    }
                    findings += finding(
                        path = relative,
                        line = lineNumber(source, match.range.first),
                        kind = KWebElectronInventoryFindingKind.ELECTRON_IMPORT,
                        expression = "electron.$symbol",
                        mapping = mapping,
                        forceBlocking = manifest != null &&
                            (declared == null || declared.status != mapping?.status),
                    )
                }
        }
        ELECTRON_MODULE_IMPORT.findAll(source)
            .filter { moduleMatch -> destructuredImports.none { rangesOverlap(it.range, moduleMatch.range) } }
            .forEach { match ->
                findings += finding(
                    path = relative,
                    line = lineNumber(source, match.range.first),
                    kind = KWebElectronInventoryFindingKind.ELECTRON_IMPORT,
                    expression = match.value,
                    mapping = null,
                )
            }
        NODE_IMPORT.findAll(source).forEach { match ->
            val module = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
            findings += finding(
                path = relative,
                line = lineNumber(source, match.range.first),
                kind = KWebElectronInventoryFindingKind.NODE_IMPORT,
                expression = module,
                mapping = matrix.singleOrNull { it.id == "node-runtime" },
            )
        }
        NODE_CORE_IMPORT.findAll(source).forEach { match ->
            val module = match.groupValues.drop(1).firstOrNull(String::isNotBlank).orEmpty()
            findings += finding(
                path = relative,
                line = lineNumber(source, match.range.first),
                kind = KWebElectronInventoryFindingKind.NODE_IMPORT,
                expression = module,
                mapping = matrix.singleOrNull { it.id == "node-runtime" },
            )
        }

        val staticChannels = ELECTRON_CHANNEL.findAll(source).toList()
        staticChannels.forEach { match ->
            val channel = match.groupValues[1]
            val declaredChannel = manifest?.channels?.singleOrNull { it.name == channel }
            findings += finding(
                path = relative,
                line = lineNumber(source, match.range.first),
                kind = KWebElectronInventoryFindingKind.ELECTRON_CHANNEL,
                expression = channel,
                mapping = matrix.singleOrNull { it.id == "ipc-request" },
                forceBlocking = declaredChannel == null ||
                    declaredChannel.status == KWebElectronMappingStatus.REWRITE ||
                    declaredChannel.status == KWebElectronMappingStatus.UNSUPPORTED,
            )
        }
        ELECTRON_DYNAMIC_CHANNEL.findAll(source)
            .filter { dynamic -> staticChannels.none { rangesOverlap(it.range, dynamic.range) } }
            .forEach { match ->
                findings += finding(
                    path = relative,
                    line = lineNumber(source, match.range.first),
                    kind = KWebElectronInventoryFindingKind.ELECTRON_CHANNEL,
                    expression = "<dynamic>",
                    mapping = matrix.singleOrNull { it.id == "ipc-request" },
                    forceBlocking = true,
                )
            }
        CONTEXT_BRIDGE_GLOBAL.findAll(source).forEach { match ->
            val global = match.groupValues[1]
            findings += finding(
                path = relative,
                line = lineNumber(source, match.range.first),
                kind = KWebElectronInventoryFindingKind.PRELOAD_GLOBAL,
                expression = global,
                mapping = matrix.singleOrNull { it.id == "context-bridge" },
                forceBlocking = manifest?.rendererGlobal?.let { it != global } ?: true,
            )
        }
        return findings
    }

    private fun lineNumber(source: String, offset: Int): Int =
        source.asSequence().take(offset).count { it == '\n' } + 1

    private fun rangesOverlap(left: IntRange, right: IntRange): Boolean =
        left.first <= right.last && right.first <= left.last

    private fun scanPackageJson(root: Path, path: Path): List<KWebElectronInventoryFinding> {
        val relative = root.relativize(path).toString().replace(path.fileSystem.getSeparator(), "/")
        val document = try {
            Json.parseToJsonElement(Files.readString(path)).jsonObject
        } catch (error: Throwable) {
            throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                details = mapOf("path" to relative),
                message = "The package.json file is not valid strict JSON.",
                cause = error,
            )
        }
        return DEPENDENCY_SECTIONS.flatMap { section ->
            document[section]?.jsonObject?.entries.orEmpty().mapNotNull { (name, version) ->
                val mapping = when {
                    name == "electron" || name.startsWith("@electron/") -> matrix.singleOrNull { it.id == "browser-window" }
                    name.startsWith("node-") || name.startsWith("@node/") -> matrix.singleOrNull { it.id == "node-runtime" }
                    else -> null
                }
                mapping?.let {
                    finding(
                        path = relative,
                        line = 1,
                        kind = KWebElectronInventoryFindingKind.PACKAGE_DEPENDENCY,
                        expression = "$section.$name=${version.jsonPrimitive.content}",
                        mapping = it,
                        forceBlocking = name == "electron" || name.startsWith("@electron/"),
                    )
                }
            }
        }
    }

    private fun finding(
        path: String,
        line: Int,
        kind: KWebElectronInventoryFindingKind,
        expression: String,
        mapping: KWebElectronCapabilityMatrixEntry?,
        forceBlocking: Boolean = false,
    ): KWebElectronInventoryFinding {
        val status = mapping?.status
        val blocking = forceBlocking || mapping == null ||
            status == KWebElectronMappingStatus.REWRITE ||
            status == KWebElectronMappingStatus.UNSUPPORTED
        return KWebElectronInventoryFinding(
            path = path,
            line = line,
            kind = kind,
            expression = expression,
            matrixId = mapping?.id,
            status = status,
            blocking = blocking,
        )
    }

    private fun mappingForElectron(symbol: String): KWebElectronCapabilityMatrixEntry? = when (symbol) {
        "BrowserWindow" -> matrix.singleOrNull { it.id == "browser-window" }
        "webContents" -> matrix.singleOrNull { it.id == "web-contents" }
        "session" -> matrix.singleOrNull { it.id == "session-partition" }
        "protocol" -> matrix.singleOrNull { it.id == "profile-protocol" }
        "ipcMain", "ipcRenderer" -> matrix.singleOrNull { it.id == "ipc-request" }
        "contextBridge" -> matrix.singleOrNull { it.id == "context-bridge" }
        "dialog" -> matrix.singleOrNull { it.id == "dialog" }
        "clipboard" -> matrix.singleOrNull { it.id == "clipboard" }
        "shell" -> matrix.singleOrNull { it.id == "shell" }
        "nativeTheme" -> matrix.singleOrNull { it.id == "native-theme" }
        "screen" -> matrix.singleOrNull { it.id == "screen" }
        "globalShortcut" -> matrix.singleOrNull { it.id == "global-shortcut" }
        "Menu", "Tray" -> matrix.singleOrNull { it.id == "menu-tray" }
        "utilityProcess", "child_process" -> matrix.singleOrNull { it.id == "process" }
        "autoUpdater" -> matrix.singleOrNull { it.id == "auto-updater" }
        else -> null
    }

    private fun isScannedSource(path: Path): Boolean =
        path.fileName.toString().substringAfterLast('.', "") in SOURCE_EXTENSIONS

    private companion object {
        val SOURCE_EXTENSIONS = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "vue", "svelte")
        val DEPENDENCY_SECTIONS = listOf("dependencies", "devDependencies", "optionalDependencies", "peerDependencies")
        val ELECTRON_DESTRUCTURED_IMPORT = Regex(
            """(?:import\s*\{([^}]+)}\s*from\s*[\"']electron[\"']|(?:const|let|var)\s*\{([^}]+)}\s*=\s*require\s*\(\s*[\"']electron[\"']\s*\))""",
        )
        val ELECTRON_MODULE_IMPORT = Regex("""(?:from\s*[\"']electron[\"']|require\s*\(\s*[\"']electron[\"']\s*\))""")
        val NODE_IMPORT = Regex(
            """(?:from\s*[\"'](node:[^\"']+|[^\"']*node[^\"']*)[\"']|require\s*\(\s*[\"'](node:[^\"']+|[^\"']*node[^\"']*)[\"']\s*\))""",
        )
        val NODE_CORE_IMPORT = Regex(
            """(?:from\s*[\"'](assert|buffer|child_process|cluster|console|constants|crypto|dgram|diagnostics_channel|dns|domain|events|fs|http|http2|https|module|net|os|path|perf_hooks|process|punycode|querystring|readline|repl|stream|string_decoder|sys|timers|tls|trace_events|tty|url|util|v8|vm|wasi|worker_threads|zlib)[\"']|require\s*\(\s*[\"'](assert|buffer|child_process|cluster|console|constants|crypto|dgram|diagnostics_channel|dns|domain|events|fs|http|http2|https|module|net|os|path|perf_hooks|process|punycode|querystring|readline|repl|stream|string_decoder|sys|timers|tls|trace_events|tty|url|util|v8|vm|wasi|worker_threads|zlib)[\"']\s*\))""",
        )
        val ELECTRON_CHANNEL = Regex(
            """ipc(?:Main|Renderer)\.(?:handle|invoke|send|on|once)\s*\(\s*[\"']([^\"']+)[\"']""",
        )
        val ELECTRON_DYNAMIC_CHANNEL = Regex(
            """ipc(?:Main|Renderer)\.(?:handle|invoke|send|on|once)\s*\(\s*(?![\"'])[^\n)]*\)""",
        )
        val CONTEXT_BRIDGE_GLOBAL = Regex(
            """contextBridge\.exposeInMainWorld\s*\(\s*[\"']([^\"']+)[\"']""",
        )
    }
}
