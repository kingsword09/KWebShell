/**
 * RFC 0046 hosted probe: compare the service snapshot with the host's own print
 * subsystem. The OS tool is the independent observation; the service must agree
 * with it for names, the default printer, and the empty case.
 */
package io.github.kingsword09.kwebshell.service.printers

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private data class OsPrinters(
    val names: Set<String>,
    val defaultName: String?,
    val tool: String,
    val empty: Boolean,
)

public fun main(arguments: Array<String>) {
    require(arguments.size == 1) { "The printer integration probe requires one output directory argument." }
    val root = Path.of(arguments.single())
    Files.createDirectories(root)
    var failure: String = ""
    var serviceProvider = ""
    var serviceCount = -1
    var osCount = -1
    var matchesOs = false
    var defaultMatches = false
    var hostPrinters = emptyList<String>()
    var mediaFacts = 0
    var colorFacts = 0
    var duplexFacts = 0
    var tool = ""
    try {
        val os = observeHost()
        tool = os.tool
        osCount = os.names.size
        val printers = JvmKWebPrinters.open()
        try {
            val snapshot = runBlocking { printers.list() }
            serviceProvider = snapshot.provider
            serviceCount = snapshot.printers.size
            hostPrinters = snapshot.printers.map { it.name.value }
            mediaFacts = snapshot.printers.count { it.facts.mediaSizes.isNotEmpty() }
            colorFacts = snapshot.printers.count { it.facts.colorSupported != null }
            duplexFacts = snapshot.printers.count { it.facts.duplexSupported != null }
            matchesOs = snapshot.printers.map { it.name.value }.toSet() == os.names
            defaultMatches = snapshot.defaultPrinter?.name?.value == os.defaultName
            require(matchesOs) {
                "The service reported ${snapshot.printers.map { it.name.value }.sorted()} but the host tool " +
                    "'${os.tool}' reported ${os.names.sorted()}."
            }
            require(defaultMatches) {
                "The service reported default '${snapshot.defaultPrinter?.name?.value}' but the host tool " +
                    "reported '${os.defaultName}'."
            }
            require(os.empty == (serviceCount == 0)) {
                "The empty-observation rule failed: host empty=${os.empty}, service count=$serviceCount."
            }
        } finally {
            printers.close()
        }
        println("KWEBSHELL_PRINTERS_PROBE:${os.tool}:${osCount}")
    } catch (error: Throwable) {
        failure = error::class.simpleName ?: "unknown"
        error.printStackTrace(System.err)
        writeEvidence(
            root, serviceProvider, serviceCount, osCount, matchesOs, defaultMatches,
            hostPrinters, mediaFacts, colorFacts, duplexFacts, tool, failure,
        )
        kotlin.system.exitProcess(1)
    }
    writeEvidence(
        root, serviceProvider, serviceCount, osCount, matchesOs, defaultMatches,
        hostPrinters, mediaFacts, colorFacts, duplexFacts, tool, failure,
    )
    println("KWebShell RFC0046 printer discovery passed: $serviceCount printer(s).")
}

private fun writeEvidence(
    root: Path,
    provider: String,
    serviceCount: Int,
    osCount: Int,
    matchesOs: Boolean,
    defaultMatches: Boolean,
    printers: List<String>,
    mediaFacts: Int,
    colorFacts: Int,
    duplexFacts: Int,
    tool: String,
    failure: String,
) {
    val evidence = buildJsonObject {
        put("schemaVersion", 1)
        put("target", currentTarget())
        put("provider", provider)
        put("serviceCount", serviceCount)
        put("osCount", osCount)
        put("osTool", tool)
        put("matchesOs", matchesOs)
        put("defaultMatches", defaultMatches)
        put("printersWithMediaFacts", mediaFacts)
        put("printersWithColorFacts", colorFacts)
        put("printersWithDuplexFacts", duplexFacts)
        putJsonArray("printers") { printers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        put("failure", failure)
    }
    Files.writeString(root.resolve("printers-integration.json"), evidence.toString() + "\n", StandardCharsets.UTF_8)
}

private fun currentTarget(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    return when {
        os.startsWith("mac") && arch in setOf("arm64", "aarch64") -> "macos-arm64"
        os.startsWith("windows") && arch in setOf("amd64", "x86_64") -> "windows-x64"
        os.startsWith("linux") && arch in setOf("amd64", "x86_64") -> "linux-x64"
        else -> error("Unsupported printer-discovery target: $os/$arch")
    }
}

/** Reads the host's own printer list with the platform's print tooling. */
private fun observeHost(): OsPrinters = if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) {
    observeWindows()
} else {
    observeCups()
}

private fun observeCups(): OsPrinters {
    val printers = runCups("lpstat", "-p")
    // CUPS localizes its prose but keeps its own "lpstat:" error prefix and the
    // queue name as the second token of a printer line, so neither the empty
    // case nor a named queue depends on the host locale.
    val names = printers.output.lineSequence()
        .filterNot { it.contains("lpstat:") }
        .mapNotNull { line ->
            val tokens = line.trim().split(WHITESPACE)
            if (tokens.size >= 3) tokens[1].takeIf { it.isNotEmpty() } else null
        }
        .toSet()
    val defaults = runCups("lpstat", "-d")
    val defaultName = defaults.output.lineSequence()
        .firstOrNull { it.contains(':') }
        ?.substringAfterLast(':')?.trim()
        ?.takeIf { it.isNotEmpty() }
    require(names.isNotEmpty() || printers.exitCode != 0) {
        "lpstat exited 0 but published no printer line: ${printers.output}"
    }
    return OsPrinters(
        names = names,
        defaultName = defaultName,
        tool = "lpstat",
        empty = names.isEmpty(),
    )
}

private data class CommandResult(val output: String, val exitCode: Int)

private fun runCups(vararg command: String): CommandResult {
    val process = ProcessBuilder(*command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    require(process.waitFor(30, TimeUnit.SECONDS)) { "${command.first()} did not finish in time." }
    return CommandResult(output, process.exitValue())
}

private val WHITESPACE = Regex("\\s+")

private fun observeWindows(): OsPrinters {
    val script = "Get-Printer | ForEach-Object { \$_.Name }; '--default--'; " +
        "(Get-CimInstance Win32_Printer -Filter 'Default = TRUE').Name"
    val process = ProcessBuilder(
        "powershell.exe",
        "-NoLogo",
        "-NoProfile",
        "-NonInteractive",
        "-Command",
        script,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    require(process.waitFor(60, TimeUnit.SECONDS)) { "Get-Printer did not finish in time." }
    val lines = output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    val separator = lines.indexOf("--default--")
    val names = (if (separator >= 0) lines.take(separator) else lines).toSet()
    val defaultName = if (separator >= 0) lines.drop(separator + 1).firstOrNull() else null
    return OsPrinters(
        names = names,
        defaultName = defaultName,
        tool = "Get-Printer",
        empty = names.isEmpty(),
    )
}
