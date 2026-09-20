package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public data class KWebElectronReportProvenance(
    public val runtimeManifest: Path,
    public val rfcEvidenceManifest: Path,
    public val rfcCatalog: Path,
    public val target: String,
)

internal data class KWebElectronRuntimeIdentity(
    val cefVersion: String,
    val chromiumVersion: String,
    val target: String,
    val runtimeSha256: String,
    val artifactSha256: String,
) {
    companion object {
        fun load(input: KWebElectronReportProvenance): KWebElectronRuntimeIdentity {
            val bytes = Files.readAllBytes(input.runtimeManifest)
            val runtime = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val artifact = runtime.getValue("artifacts").jsonArray.singleOrNull { it.jsonObject.getValue("target").jsonPrimitive.content == input.target }
                ?: throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, "The runtime manifest has no unique artifact for ${input.target}.")
            val checksum = artifact.jsonObject.getValue("checksum").jsonObject
            if (checksum.getValue("algorithm").jsonPrimitive.content != "SHA-1" || !Regex("[0-9a-f]{40}").matches(checksum.getValue("value").jsonPrimitive.content)) {
                throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, "The pinned CEF artifact checksum is invalid.")
            }
            return KWebElectronRuntimeIdentity(
                runtime.getValue("cefVersion").jsonPrimitive.content,
                runtime.getValue("chromiumVersion").jsonPrimitive.content,
                input.target, KWebElectronFiles.digest(bytes), KWebElectronFiles.text(artifact.toString()),
            )
        }
    }
}
