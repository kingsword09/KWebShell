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
 * check <catalog-dir> <manifest.json> <runtime.json> <contracts.json>
 *       --repository-root <dir> [--report <out.json>]
 *   Validates the RFC catalog, the capability evidence manifest, the published
 *   service descriptors, and the capability matrix backing. Writes the joined
 *   status report (ready/blocked/stale/platform-specific per RFC) and exits 2 on
 *   any blocking finding.
 *
 * record <manifest.json> <output.json> --catalog <dir> --runtime <runtime.json>
 *        --contracts <contracts.json> --repository-root <dir>
 *        --rfc <id> --provider <id> --electron-major <n>
 *        [--service <id>] [--matrix-row <id>]... [--artifact <name=path>]...
 *        [--compatibility-status READY|BLOCKED] [--from-compatibility-report <path>]
 *   Upserts one evidence record derived from structured test output and rewrites
 *   the manifest canonically. Regeneration from the same inputs is byte-for-byte
 *   deterministic.
 *
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
        require(positional.size == 4) {
            "Usage: check <catalog-dir> <manifest.json> <runtime.json> <contracts.json> --repository-root <dir>"
        }
        val options = parseOptions(arguments.drop(positional.size))
        val reportPath = options["--report"]?.let { Path.of(it) }
        val repositoryRoot = Path.of(
            options["--repository-root"]
                ?: throw IllegalArgumentException("The check command requires --repository-root."),
        )

        val catalog = KWebRfcCatalog.load(Path.of(positional[0]))
        val runtime = KWebRfcRuntimeIdentity.load(Path.of(positional[2]))
        val contractBindings = KWebRfcContractBindings.load(Path.of(positional[3]))
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
        val report = KWebRfcGovernanceChecker(
            catalog,
            manifest,
            KWebElectronCapabilityMatrix.document(),
            runtime,
            KWebRfcContractDigestProvider.forRepository(contractBindings, repositoryRoot),
            KWebRfcArtifactDigestProvider.forRepository(repositoryRoot),
        ).check()
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
        val contractBindings = KWebRfcContractBindings.load(Path.of(requiredOption(options, "--contracts")))
        val repositoryRoot = Path.of(requiredOption(options, "--repository-root"))
        val manifest = KWebRfcEvidenceJson.decodeManifest(Files.readString(Path.of(positional[0])))
        val run = hostedRunFromEnvironment(System.getenv())

        val compatibilityReport = options["--from-compatibility-report"]?.singleOrNull()?.let { path ->
            val report = KWebElectronMigrationJson.format.decodeFromString(
                KWebElectronCompatibilityReport.serializer(),
                Files.readString(Path.of(path)),
            )
            report to Path.of(path)
        }
        val request = KWebRfcEvidenceRecordRequest(
            rfcId = requiredOption(options, "--rfc"),
            providerId = requiredOption(options, "--provider"),
            target = options["--target"]?.singleOrNull()?.also { target ->
                if (target !in HOSTED_TARGETS) {
                    throw KWebRfcGovernanceException(
                        code = "rfc.record.unsupported-runner",
                        details = mapOf("target" to target),
                        message = "Evidence records may only target the hosted verification triple.",
                    )
                }
            } ?: hostedTargetFromEnvironment(System.getenv()),
            run = run,
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
                        message = "Artifacts must be passed as name=path.",
                    )
                }
                KWebRfcEvidenceArtifactInput(
                    name = pair.substring(0, separator),
                    path = Path.of(pair.substring(separator + 1)),
                )
            },
            compatibilityReport = compatibilityReport?.first,
            compatibilityReportPath = compatibilityReport?.second,
        )
        val updated = KWebRfcEvidenceRecorder().record(
            request,
            manifest,
            catalog,
            runtime,
            contractBindings,
            repositoryRoot,
        )
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

    internal fun hostedRunFromEnvironment(environment: Map<String, String>): KWebRfcHostedRun {
        if (environment["GITHUB_ACTIONS"] != "true") {
            throw KWebRfcGovernanceException(
                code = "rfc.record.untrusted-environment",
                message = "Evidence records may only be produced by a GitHub Actions hosted run.",
            )
        }
        fun required(name: String): String = environment[name]?.takeIf(String::isNotBlank)
            ?: throw KWebRfcGovernanceException(
                code = "rfc.record.missing-provenance",
                details = mapOf("environment" to name),
                message = "The hosted run did not expose all required evidence provenance.",
            )
        return KWebRfcHostedRun(
            repository = required("GITHUB_REPOSITORY"),
            workflowRef = required("GITHUB_WORKFLOW_REF"),
            runId = required("GITHUB_RUN_ID"),
            runAttempt = required("GITHUB_RUN_ATTEMPT").toIntOrNull()
                ?: throw KWebRfcGovernanceException(
                    code = "rfc.record.missing-provenance",
                    details = mapOf("environment" to "GITHUB_RUN_ATTEMPT"),
                    message = "The hosted run attempt is not an integer.",
                ),
            sourceRevision = required("GITHUB_SHA"),
        )
    }

    private val HOSTED_TARGETS: Set<String> = setOf("macos-arm64", "windows-x64", "linux-x64")

    internal fun hostedTargetFromEnvironment(environment: Map<String, String>): String {
        val key = "${environment["RUNNER_OS"]}:${environment["RUNNER_ARCH"]}"
        return when (key) {
            "macOS:ARM64" -> "macos-arm64"
            "Windows:X64" -> "windows-x64"
            "Linux:X64" -> "linux-x64"
            else -> throw KWebRfcGovernanceException(
                code = "rfc.record.unsupported-runner",
                details = mapOf("runner" to key),
                message = "The hosted runner is not one of the RFC evidence targets.",
            )
        }
    }
}
