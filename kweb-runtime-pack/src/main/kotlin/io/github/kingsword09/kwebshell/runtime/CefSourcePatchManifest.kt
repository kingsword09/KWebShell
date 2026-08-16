package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import java.nio.file.Path
import kotlinx.serialization.Serializable

@Serializable
public data class CefSourcePatchManifest(
    public val schemaVersion: Int,
    public val cefVersion: String,
    public val cefCommit: String,
    public val chromiumVersion: String,
    public val chromiumCommit: String,
    public val depotToolsCommit: String,
    public val adapterAbiVersion: Int,
    public val adapterAbiFingerprint: String,
    public val sisoVersion: String,
    public val gnDefines: List<String>,
    public val exports: List<String>,
    public val patches: List<CefSourcePatch>,
    public val customRuntimeArtifacts: List<CefCustomRuntimeArtifact>,
)

@Serializable
public data class CefSourcePatch(
    public val file: String,
    public val sha256: String,
    public val modifiedPreimages: List<CefSourceFileDigest>,
    public val createdPostimages: List<CefSourceFileDigest>,
)

@Serializable
public data class CefSourceFileDigest(
    public val path: String,
    public val sha256: String,
)

@Serializable
public data class CefCustomRuntimeArtifact(
    public val target: String,
    public val fileName: String,
    public val downloadUrl: String,
    public val size: Long,
    public val sha256: String,
    public val librarySha256: String,
)

public class CefSourcePatchCatalog internal constructor(
    public val manifest: CefSourcePatchManifest,
    public val root: Path,
    customRuntimeArtifacts: Map<KWebTarget, CefCustomRuntimeArtifact> = emptyMap(),
) {
    private val customRuntimeArtifacts: Map<KWebTarget, CefCustomRuntimeArtifact> =
        customRuntimeArtifacts.toMap()

    public val certifiedRuntimeTargets: Set<KWebTarget> = this.customRuntimeArtifacts.keys.toSet()

    public val packageLifecyclePublicationReady: Boolean
        get() = certifiedRuntimeTargets == REQUIRED_CUSTOM_RUNTIME_TARGETS

    internal fun requirePackageLifecyclePublicationReady() {
        if (!packageLifecyclePublicationReady) {
            throw KWebConfigurationException(
                code = "runtime.custom-runtime.publication-incomplete",
                details = mapOf(
                    "required" to REQUIRED_CUSTOM_RUNTIME_TARGETS.joinToString { it.id },
                    "certified" to certifiedRuntimeTargets.joinToString { it.id },
                ),
                message = "Package lifecycle cannot be published before all custom runtimes are certified.",
            )
        }
    }

    public fun customRuntimeArtifact(target: KWebTarget): CefCustomRuntimeArtifact =
        customRuntimeArtifacts[target] ?: throw KWebConfigurationException(
            code = "runtime.custom-runtime.target-not-certified",
            details = mapOf("target" to target.id),
            message = "No checksum-pinned custom CEF runtime is certified for '${target.id}'.",
        )

    public companion object {
        public val REQUIRED_CUSTOM_RUNTIME_TARGETS: Set<KWebTarget> = linkedSetOf(
            KWebTarget.parse("macos-arm64"),
            KWebTarget.parse("windows-x64"),
            KWebTarget.parse("linux-x64"),
        )
    }
}
