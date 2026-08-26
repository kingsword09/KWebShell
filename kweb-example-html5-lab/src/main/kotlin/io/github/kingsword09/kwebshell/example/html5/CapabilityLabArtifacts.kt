package io.github.kingsword09.kwebshell.example.html5

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object CapabilityLabJson {
    val format: Json = Json {
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
        explicitNulls = true
        ignoreUnknownKeys = false
    }
}

internal object CapabilityLabDigest {
    fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

internal class CapabilityLabArtifactWriter(
    private val outputDirectory: Path,
) {
    fun write(
        bundle: CapabilityLabBundle,
        screenshots: Map<String, Path>,
        expectedOrigin: String,
    ): Path {
        CapabilityLabValidator.validateBundle(bundle, expectedOrigin)
        Files.createDirectories(outputDirectory)
        val expectedScreenshots = bundle.runs.map { it.host.screenshotFile }.toSet()
        if (screenshots.keys != expectedScreenshots) {
            throw CapabilityLabException(
                "artifact-screenshot-set-invalid",
                "Expected screenshots $expectedScreenshots, got ${screenshots.keys}.",
            )
        }
        screenshots.forEach { (fileName, source) ->
            if (fileName.isBlank() || fileName.contains('/') || fileName.contains('\\')) {
                throw CapabilityLabException("artifact-name-invalid", "Invalid screenshot artifact name '$fileName'.")
            }
            copyPngAtomically(source, outputDirectory.resolve(fileName))
        }
        val jsonPath = outputDirectory.resolve("capability-lab-report.json")
        writeTextAtomically(jsonPath, CapabilityLabJson.format.encodeToString(bundle))
        val htmlPath = outputDirectory.resolve("capability-lab-report.html")
        writeTextAtomically(htmlPath, renderHtml(bundle))
        return jsonPath
    }

    private fun copyPngAtomically(source: Path, path: Path) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            if (!Files.isRegularFile(source)) {
                throw CapabilityLabException("artifact-screenshot-missing", "Screenshot source '$source' is missing.")
            }
            val image = javax.imageio.ImageIO.read(source.toFile())
                ?: throw CapabilityLabException("artifact-screenshot-invalid", "Screenshot source '$source' is not PNG data.")
            if (image.width <= 0 || image.height <= 0) {
                throw CapabilityLabException("artifact-screenshot-invalid", "Screenshot source '$source' has invalid dimensions.")
            }
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING)
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw CapabilityLabException(
                "artifact-screenshot-write-failed",
                "Could not write screenshot artifact '${path.fileName}'.",
                error,
            )
        }
    }

    private fun writeTextAtomically(path: Path, text: String) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8)
            Files.move(
                temporary,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw CapabilityLabException(
                "artifact-report-write-failed",
                "Could not write report artifact '${path.fileName}'.",
                error,
            )
        }
    }

    private fun renderHtml(bundle: CapabilityLabBundle): String {
        fun escape(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

        val rows = bundle.runs.flatMap { run ->
            run.page.probes.map { probe ->
                "<tr><td>${escape(run.phase)}</td><td>${escape(probe.category)}</td>" +
                    "<td>${escape(probe.id)}</td><td>${escape(probe.requirement.name)}</td>" +
                    "<td class=\"${probe.status.name.lowercase()}\">${escape(probe.status.name)}</td>" +
                    "<td>${escape(probe.reason)}</td><td><code>${escape(probe.evidence.toString())}</code></td></tr>"
            }
        }.joinToString("\n")
        val hostRows = bundle.runs.joinToString("\n") { run ->
            val host = run.host
            "<tr><td>${escape(run.phase)}</td><td>${escape(host.screenshotFile)}</td>" +
                "<td>${escape(host.accessibilityRole)} / ${escape(host.accessibilityName)}</td>" +
                "<td><code>${escape(host.eventSequences.zip(host.eventTypes).toString())}</code></td></tr>"
        }
        val metadata = bundle.metadata
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><title>KWebShell HTML5 Capability Lab</title>
<style>body{font:14px system-ui,sans-serif;margin:2rem;color:#202124}table{border-collapse:collapse;width:100%}th,td{border:1px solid #d0d7de;padding:.45rem;text-align:left;vertical-align:top}th{background:#f6f8fa}.pass{color:#137333}.unavailable{color:#8a6100}.fail{color:#b3261e}code{white-space:pre-wrap}</style></head>
<body><h1>KWebShell HTML5 Capability Lab</h1>
<p>Runtime SHA-256: <code>${escape(metadata.runtimeSha256)}</code><br>
Chromium: <code>${escape(metadata.chromiumProduct)}</code><br>
CDP protocol: ${escape(metadata.protocolVersion)}; revision: <code>${escape(metadata.revision)}</code>; JavaScript: ${escape(metadata.javaScriptVersion)}<br>
Platform: ${escape(metadata.platform)} / ${escape(metadata.architecture)}<br>
Display scale: ${metadata.displayScale}<br>Host policy: ${escape(metadata.hostPolicy)}</p>
<h2>Host and CDP evidence</h2><table><thead><tr><th>Phase</th><th>Screenshot</th><th>Accessibility</th><th>Public events</th></tr></thead><tbody>$hostRows</tbody></table>
<h2>Page probes</h2>
<table><thead><tr><th>Phase</th><th>Category</th><th>Probe</th><th>Requirement</th><th>Status</th><th>Reason</th><th>Evidence</th></tr></thead><tbody>$rows</tbody></table>
</body></html>"""
    }
}
