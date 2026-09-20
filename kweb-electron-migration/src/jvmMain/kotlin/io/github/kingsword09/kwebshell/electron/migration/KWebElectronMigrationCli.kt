package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

public object KWebElectronMigrationCli {
    @JvmStatic
    public fun main(arguments: Array<String>) {
        try {
            run(arguments)
        } catch (error: Throwable) {
            val migrationError = error as? KWebElectronMigrationException
            System.err.println(
                KWebElectronMigrationJson.format.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("code", migrationError?.code ?: "migration.command.failed")
                        put("message", migrationError?.message ?: error.message ?: "Electron migration command failed.")
                        put("details", buildJsonObject {
                            migrationError?.details?.forEach { (key, value) -> put(key, value) }
                        })
                    },
                ),
            )
            exitProcess(2)
        }
    }

    private fun run(arguments: Array<String>) {
        when (arguments.firstOrNull()) {
            "migrate" -> {
                require(arguments.size == 4) { "Usage: migrate <input-manifest.json> <output-manifest.json> <renderer-origin>" }
                val inputPath = Path.of(arguments[1]).toAbsolutePath().normalize()
                val outputPath = Path.of(arguments[2]).toAbsolutePath().normalize()
                val jsonText = Files.readString(inputPath)
                val migrated = KWebElectronManifestMigrator.migrate(jsonText, arguments[3])
                writeJson(migrated, outputPath)
                println("Electron migration manifest migrated to v2.")
            }
            "merge-reports" -> {
                require(arguments.size >= 4) { "Usage: merge-reports <output.json> <report1.json> <report2.json> [report3.json ...]" }
                val outputPath = Path.of(arguments[1]).toAbsolutePath().normalize()
                val inputReports = arguments.drop(2).map { readJson<KWebElectronCompatibilityReport>(Path.of(it)) }
                val merged = KWebElectronCompatibilityReportBuilder().merge(inputReports)
                writeJson(merged, outputPath)
                println("Electron migration reports merged: ${merged.migrationStatus}")
                if (!merged.migrationReady) throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, "One or more renderer reports are blocked.")
            }
            "manifest" -> {
                require(arguments.size == 2) { "Usage: manifest <manifest.json>" }
                loadManifest(Path.of(arguments[1]))
                println("Electron migration manifest is valid.")
            }
            "generate" -> {
                require(arguments.size == 3) { "Usage: generate <manifest.json> <output-directory>" }
                val manifestPath = Path.of(arguments[1]).toAbsolutePath().normalize()
                val manifest = loadManifest(manifestPath)
                KWebElectronPreloadGenerator().generate(manifest, Path.of(arguments[2]))
                    .forEach { println(it.toAbsolutePath().normalize()) }
            }
            "inventory" -> {
                require(arguments.size == 4) { "Usage: inventory <application-root> <manifest.json> <output.json>" }
                val manifestPath = Path.of(arguments[2]).toAbsolutePath().normalize()
                val manifest = loadManifest(manifestPath)
                val report = KWebElectronInventoryScanner().scan(Path.of(arguments[1]), manifest)
                writeJson(report, Path.of(arguments[3]))
                println("Electron migration inventory: ${report.findings.size} findings, ${report.blockingFindings.size} blocking.")
                if (!report.migrationReady) {
                    throw KWebElectronMigrationException(
                        code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                        details = mapOf("blocking" to report.blockingFindings.size.toString()),
                        message = "The Electron inventory contains blocking findings.",
                    )
                }
            }
            "report" -> {
                require(arguments.size == 5) { "Usage: report <manifest.json> <generated-directory> <inventory.json> <output.json>" }
                val manifestPath = Path.of(arguments[1]).toAbsolutePath().normalize()
                val manifest = loadManifest(manifestPath)
                val inventory = readJson<KWebElectronInventoryReport>(Path.of(arguments[3]))
                val report = KWebElectronCompatibilityReportBuilder().build(
                    manifestPath = manifestPath,
                    manifest = manifest,
                    generatedOutput = Path.of(arguments[2]),
                    inventory = inventory,
                    provenance = KWebElectronReportProvenance(
                        runtimeManifest = Path.of(requiredProperty("kweb.migration.runtime.manifest")),
                        rfcEvidenceManifest = Path.of(requiredProperty("kweb.migration.rfc.evidence")),
                        rfcCatalog = Path.of(requiredProperty("kweb.migration.rfc.catalog")),
                        target = requiredProperty("kweb.migration.target"),
                    ),
                )
                writeJson(report, Path.of(arguments[4]))
                println("Electron migration report: ${report.migrationStatus}")
                if (!report.migrationReady) {
                    throw KWebElectronMigrationException(
                        code = KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                        details = mapOf("reasons" to report.blockedReasons.size.toString()),
                        message = "The Electron migration is blocked; no performance comparison is valid.",
                    )
                }
            }
            else -> throw IllegalArgumentException(
                "Usage: migrate|manifest|generate|inventory|report|merge-reports ...",
            )
        }
    }

    private fun loadManifest(path: Path): KWebElectronManifest =
        KWebElectronMigrationJson.decode(Files.readString(path))

    private inline fun <reified T> readJson(path: Path): T =
        KWebElectronMigrationJson.format.decodeFromString(Files.readString(path))

    private inline fun <reified T> writeJson(value: T, output: Path) {
        val normalized = output.toAbsolutePath().normalize()
        normalized.parent?.let(Files::createDirectories)
        Files.writeString(normalized, KWebElectronMigrationJson.format.encodeToString(value) + "\n")
    }

    private fun requiredProperty(name: String): String =
        System.getProperty(name)?.takeIf(String::isNotBlank)
            ?: throw KWebElectronMigrationException(
                code = KWebElectronMigrationErrorCode.MANIFEST_INVALID,
                details = mapOf("property" to name),
                message = "The migration report requires system property '$name'.",
            )
}
