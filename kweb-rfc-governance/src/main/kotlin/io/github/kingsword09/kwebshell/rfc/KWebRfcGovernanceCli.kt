package io.github.kingsword09.kwebshell.rfc

import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCapabilityMatrix
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronCompatibilityReport
import io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationJson
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Governance command line.
 *
 * check <catalog-dir> <manifest.json> <runtime.json> [--report <out.json>]
 *   Validates the RFC catalog, the capability evidence manifest, the published
 *   service descriptors, and the capability matrix backing. Writes the joined
 *   status report (ready/blocked/stale/platform-specific per RFC) and exits 2 on
 *   any blocking finding.
 *
 * record <manifest.json> <output.json> --catalog <dir> --runtime <runtime.json>
 *        --rfc <id> --provider <id> --target <t> --test-run <id> --electron-major <n>
 *        [--service <id>] [--matrix-row <id>]... [--artifact <name=sha256>]...
 *        [--compatibility-status READY|BLOCKED] [--from-compatibility-report <path>]
 *   Upserts one evidence record derived from structured test output and rewrites
 *   the manifest canonically. Regeneration from the same inputs is byte-for-byte
 *   deterministic.
 */
public object KWebRfcGovernanceCli {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        try {
            run(arguments)
        } catch (error: Throwable) {
            val governanceError = error as? KWebRfcGovernanceException
            System.err.println(
                KWebRfcEvidenceJson.canonical.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("code", governanceError?.code ?: "rfc.governance.failed")
                        put("message", governanceError?.message ?: error.message ?: "RFC governance check failed.")
                        put("details", buildJsonObject {
                            governanceError?.details?.forEach { (key, value) -> put(key, value) }
                        })
                    },
                ),
            )
            exitProcess(2)
        }
    }

    private fun run(arguments: Array<String>) {
        when (arguments.firstOrNull()) {
            "check" -> check(arguments.drop(1))
            "record" -> record(arguments.drop(1))
            else -> throw IllegalArgumentException("Usage: check|record ...")
        }
    }

    private fun check(arguments: List<String>) {
        val positional = arguments.takeWhile { !it.startsWith("--") }
        require(positional.size == 3) { "Usage: check <catalog-dir> <manifest.json> <runtime.json> [--report <out.json>]" }
        val options = parseOptions(arguments.drop(positional.size))
        val reportPath = options["--report"]?.let { Path.of(it) }

        val catalog = KWebRfcCatalog.load(Path.of(positional[0]))
        val runtime = KWebRfcRuntimeIdentity.load(Path.of(positional[2]))
        val manifest = try {
            KWebRfcEvidenceJson.decodeManifest(Files.readString(Path.of(positional[1])))
        } catch (error: KWebRfcGovernanceException) {
            // Retain a blocking diagnostic even when the manifest cannot be joined.
            if (reportPath != null) {
                writeReport(reportPath, KWebRfcGovernanceReport.failure(
                    listOf(KWebRfcGovernanceFinding(rfcId = null, code = error.code, message = error.message ?: "")),
                ))
            }
            throw error
        }
        val report = KWebRfcGovernanceChecker(catalog, manifest, KWebElectronCapabilityMatrix.document(), runtime)
            .check()
        reportPath?.let { writeReport(it, report) }

        report.rfcs.forEach { status ->
            println("${status.rfcId}-${status.title}: ${status.state}")
        }
        println(
            "RFC governance: ${report.governanceStatus} with ${report.findings.size} findings " +
                "across ${report.rfcs.size} RFCs; evidence digest ${report.recordsSha256}.",
        )
        if (report.governanceStatus == KWebRfcGovernanceReport.BLOCKED) {
            throw KWebRfcGovernanceException(
                code = "rfc.governance.blocked",
                details = mapOf("findings" to report.findings.size.toString()),
                message = "RFC governance is blocked; see the retained report for the structured reasons.",
            )
        }
    }

    private fun record(arguments: List<String>) {
        val positional = arguments.takeWhile { !it.startsWith("--") }
        require(positional.size == 2) { "Usage: record <manifest.json> <output.json> --catalog <dir> --runtime <runtime.json> --rfc <id> ..." }
        val options = parseMultiOptions(arguments.drop(positional.size))
        val catalog = KWebRfcCatalog.load(Path.of(requiredOption(options, "--catalog")))
        val runtime = KWebRfcRuntimeIdentity.load(Path.of(requiredOption(options, "--runtime")))
        val manifest = KWebRfcEvidenceJson.decodeManifest(Files.readString(Path.of(positional[0])))

        val compatibilityReport = options["--from-compatibility-report"]?.singleOrNull()?.let { path ->
            val report = KWebElectronMigrationJson.format.decodeFromString(
                KWebElectronCompatibilityReport.serializer(),
                Files.readString(Path.of(path)),
            )
            val reportDigest = KWebRfcEvidenceJson.sha256(Files.readAllBytes(Path.of(path)))
            report to reportDigest
        }
        val request = KWebRfcEvidenceRecordRequest(
            rfcId = requiredOption(options, "--rfc"),
            providerId = requiredOption(options, "--provider"),
            target = requiredOption(options, "--target"),
            testRunId = requiredOption(options, "--test-run"),
            electronFixtureMajor = requiredOption(options, "--electron-major").toIntOrNull()
                ?: throw KWebRfcGovernanceException(
                    code = "rfc.record.invalid-argument",
                    details = mapOf("option" to "--electron-major"),
                    message = "The Electron fixture major must be an integer.",
                ),
            serviceId = options["--service"]?.singleOrNull(),
            matrixRowIds = options["--matrix-row"].orEmpty(),
            compatibilityStatus = options["--compatibility-status"]?.singleOrNull(),
            artifacts = options["--artifact"].orEmpty().map { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) {
                    throw KWebRfcGovernanceException(
                        code = "rfc.record.invalid-argument",
                        details = mapOf("option" to "--artifact"),
                        message = "Artifacts must be passed as name=sha256.",
                    )
                }
                KWebRfcEvidenceArtifact(
                    name = pair.substring(0, separator),
                    sha256 = pair.substring(separator + 1),
                )
            },
            compatibilityReport = compatibilityReport?.first,
            compatibilityReportSha256 = compatibilityReport?.second,
        )
        val updated = KWebRfcEvidenceRecorder().record(request, manifest, catalog, runtime)
        val output = Path.of(positional[1]).toAbsolutePath().normalize()
        output.parent?.let(Files::createDirectories)
        Files.writeString(output, KWebRfcEvidenceJson.encodeManifest(updated) + "\n")
        println(
            "RFC evidence manifest updated: ${updated.records.size} records, " +
                "digest ${updated.recordsSha256}.",
        )
    }

    private fun writeReport(path: Path, report: KWebRfcGovernanceReport) {
        val json = KWebRfcEvidenceJson.canonical.encodeToString(KWebRfcGovernanceReport.serializer(), report)
        KWebRfcRedaction.scanText("report", json)
        val normalized = path.toAbsolutePath().normalize()
        normalized.parent?.let(Files::createDirectories)
        Files.writeString(normalized, json + "\n")
    }

    private fun parseOptions(arguments: List<String>): Map<String, String> {
        val options = mutableMapOf<String, String>()
        var index = 0
        while (index < arguments.size) {
            val name = arguments[index]
            if (!name.startsWith("--") || index + 1 >= arguments.size) {
                throw IllegalArgumentException("Invalid option '$name'.")
            }
            options[name] = arguments[index + 1]
            index += 2
        }
        return options
    }

    private fun parseMultiOptions(arguments: List<String>): Map<String, List<String>> {
        val options = linkedMapOf<String, MutableList<String>>()
        var index = 0
        while (index < arguments.size) {
            val name = arguments[index]
            if (!name.startsWith("--") || index + 1 >= arguments.size) {
                throw IllegalArgumentException("Invalid option '$name'.")
            }
            options.getOrPut(name) { mutableListOf() }.add(arguments[index + 1])
            index += 2
        }
        return options
    }

    private fun requiredOption(options: Map<String, List<String>>, name: String): String =
        options[name]?.singleOrNull()?.takeIf(String::isNotEmpty)
            ?: throw KWebRfcGovernanceException(
                code = "rfc.record.invalid-argument",
                details = mapOf("option" to name),
                message = "The record command requires exactly one '$name' option.",
            )
}
