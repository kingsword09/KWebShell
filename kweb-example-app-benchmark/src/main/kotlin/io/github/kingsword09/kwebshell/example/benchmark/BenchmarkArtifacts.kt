package io.github.kingsword09.kwebshell.example.benchmark

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object BenchmarkJson {
    val format = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }
}

internal object BenchmarkDigest {
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
        return digest.digest().hex()
    }

    fun aggregate(files: List<BenchmarkWorkloadFile>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.sortedBy { it.path }.forEach { file ->
            digest.update("${file.path}\n${file.size}\n${file.sha256}\n".toByteArray(StandardCharsets.UTF_8))
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

public object BenchmarkWorkloadVerifier {
    public fun loadAndVerify(lockPath: Path, workloadRoot: Path): BenchmarkWorkloadLock {
        val normalizedLock = lockPath.toAbsolutePath().normalize()
        val normalizedRoot = workloadRoot.toAbsolutePath().normalize()
        if (!Files.isRegularFile(normalizedLock, LinkOption.NOFOLLOW_LINKS)) {
            throw BenchmarkException("workload.lock-missing", "Workload lock '$normalizedLock' is missing.")
        }
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw BenchmarkException("workload.root-missing", "Workload root '$normalizedRoot' is missing.")
        }
        val lock = try {
            BenchmarkJson.format.decodeFromString<BenchmarkWorkloadLock>(Files.readString(normalizedLock))
        } catch (error: Throwable) {
            throw BenchmarkException("workload.lock-invalid", "Workload lock '$normalizedLock' is invalid.", error)
        }
        BenchmarkValidator.validateWorkloadLock(lock)
        val actualPaths = Files.walk(normalizedRoot).use { paths ->
            paths.filter { it != normalizedRoot }.map { path ->
                if (Files.isSymbolicLink(path)) {
                    throw BenchmarkException("workload.symlink", "Workload path '$path' is a symbolic link.")
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw BenchmarkException("workload.non-file", "Workload path '$path' is not a regular file.")
                }
                normalizedRoot.relativize(path).joinToString("/") { it.toString() }
            }.toList().sorted()
        }
        val expectedPaths = lock.files.map { it.path }
        if (actualPaths != expectedPaths) {
            throw BenchmarkException(
                "workload.file-set-mismatch",
                "Locked workload paths $expectedPaths do not match actual paths $actualPaths.",
            )
        }
        lock.files.forEach { expected ->
            val file = normalizedRoot.resolve(expected.path).normalize()
            if (!file.startsWith(normalizedRoot) || Files.size(file) != expected.size ||
                BenchmarkDigest.sha256(file) != expected.sha256
            ) {
                throw BenchmarkException(
                    "workload.file-digest-mismatch",
                    "Workload file '${expected.path}' does not match its locked size and SHA-256.",
                )
            }
        }
        if (BenchmarkDigest.aggregate(lock.files) != lock.aggregateSha256) {
            throw BenchmarkException("workload.aggregate-mismatch", "The workload aggregate SHA-256 does not match.")
        }
        return lock
    }
}

internal class BenchmarkArtifactWriter(
    private val outputDirectory: Path,
) {
    fun writeRaw(sample: BenchmarkRawSample): BenchmarkRawArtifact {
        BenchmarkValidator.validateRaw(sample)
        val rawDirectory = outputDirectory.resolve("raw")
        Files.createDirectories(rawDirectory)
        val fileName = "pair-${sample.pairIndex.toString().padStart(2, '0')}-${sample.phase}-${sample.sampleId}.json"
        val path = rawDirectory.resolve(fileName)
        writeTextAtomically(path, BenchmarkJson.format.encodeToString(sample))
        return BenchmarkRawArtifact(
            file = "raw/$fileName",
            sha256 = BenchmarkDigest.sha256(path),
            sampleId = sample.sampleId,
            measured = sample.measured,
            phase = sample.phase,
        )
    }

    fun writeReport(report: BenchmarkReport): Path {
        BenchmarkValidator.validateReport(report)
        Files.createDirectories(outputDirectory)
        val jsonPath = outputDirectory.resolve("application-benchmark-report.json")
        writeTextAtomically(jsonPath, BenchmarkJson.format.encodeToString(report))
        writeTextAtomically(outputDirectory.resolve("application-benchmark-report.html"), renderHtml(report))
        return jsonPath
    }

    fun writeScreenshot(sample: BenchmarkRawSample, staging: Path): BenchmarkScreenshotArtifact {
        if (!Files.isRegularFile(staging)) {
            throw BenchmarkException("artifact.screenshot-missing", "Screenshot '$staging' is missing.")
        }
        val image = try { javax.imageio.ImageIO.read(staging.toFile()) } catch (error: Throwable) {
            throw BenchmarkException("artifact.screenshot-invalid", "Screenshot '$staging' could not be decoded.", error)
        }
        if (image == null || image.width <= 0 || image.height <= 0) {
            throw BenchmarkException("artifact.screenshot-invalid", "Screenshot '$staging' has no valid dimensions.")
        }
        val directory = outputDirectory.resolve("screenshots")
        Files.createDirectories(directory)
        val name = "pair-${sample.pairIndex.toString().padStart(2, '0')}-${sample.phase}.png"
        val destination = directory.resolve(name)
        try {
            Files.copy(staging, destination, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            throw BenchmarkException("artifact.screenshot-write-failed", "Could not retain screenshot '$destination'.", error)
        }
        return BenchmarkScreenshotArtifact(
            file = "screenshots/$name",
            sha256 = BenchmarkDigest.sha256(destination),
            sampleId = sample.sampleId,
            pairIndex = sample.pairIndex,
            phase = sample.phase,
        )
    }

    private fun writeTextAtomically(path: Path, content: String) {
        val temporary = path.resolveSibling(".${path.fileName}.tmp")
        try {
            Files.writeString(temporary, content, StandardCharsets.UTF_8)
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw BenchmarkException("artifact.write-failed", "Could not write benchmark artifact '$path'.", error)
        }
    }

    private fun renderHtml(report: BenchmarkReport): String {
        val rows = report.phases.flatMap { phase ->
            (phase.metrics + phase.optionalMetrics).map { (name, summary) ->
                "<tr><td>${escape(phase.phase)}</td><td><code>${escape(name)}</code></td>" +
                    "<td>${escape(summary.unit)}</td><td>${summary.sampleCount}</td>" +
                    "<td>${format(summary.median)}</td><td>${format(summary.p95)}</td>" +
                    "<td>${format(summary.worst)}</td></tr>"
            } + phase.unavailableMetrics.map { (name, reasons) ->
                "<tr><td>${escape(phase.phase)}</td><td><code>${escape(name)}</code></td>" +
                    "<td colspan=\"5\">Unavailable: ${escape(reasons.distinct().joinToString("; "))}</td></tr>"
            }
        }.joinToString("\n")
        val metadata = report.metadata
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>KWebShell application workload benchmark</title>
<style>body{font:14px system-ui,sans-serif;margin:2rem;color:#202124;background:#faf9f5}table{border-collapse:collapse;width:100%}th,td{border:1px solid #cbc9c0;padding:.5rem;text-align:right}th{background:#ece9df}th:nth-child(-n+3),td:nth-child(-n+3){text-align:left}code{overflow-wrap:anywhere}.meta{max-width:80ch;line-height:1.6}</style></head>
<body><h1>KWebShell application-scale workload benchmark</h1>
<p class="meta">This report measures the repository-local synthetic LobeHub-class workload fixture; it is not a LobeHub result or endorsement.<br>
Workload: <code>${escape(metadata.workloadRevision)}</code> / <code>${escape(metadata.workloadAggregateSha256)}</code><br>
Runtime: <code>${escape(metadata.runtimeSha256)}</code>; ${escape(metadata.chromiumProduct)}; CDP ${escape(metadata.protocolVersion)}<br>
Machine: ${escape(metadata.machineClass)}; ${escape(metadata.platform)} / ${escape(metadata.architecture)}; display scale ${format(metadata.displayScale)}<br>
GPU: ${escape(metadata.gpuVendor)} / ${escape(metadata.gpuRenderer)}<br>
Benchmark revision: <code>${escape(metadata.benchmarkGitRevision)}</code><br>
Plan: ${metadata.warmupPairs} warmup pair and ${metadata.measuredPairs} measured cold/warm pairs. Raw samples are retained beside this report. No composite score is calculated.</p>
<table><thead><tr><th>Phase</th><th>Metric</th><th>Unit</th><th>n</th><th>Median</th><th>p95</th><th>Worst</th></tr></thead><tbody>$rows</tbody></table>
</body></html>"""
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun format(value: Double): String = "%.3f".format(java.util.Locale.ROOT, value)
}
