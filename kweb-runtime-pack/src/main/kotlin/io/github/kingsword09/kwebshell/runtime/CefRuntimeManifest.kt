package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebTarget
import kotlinx.serialization.Serializable
import java.net.URI

@Serializable
public data class CefRuntimeManifest(
    public val schemaVersion: Int,
    public val cefVersion: String,
    public val chromiumVersion: String,
    public val channel: String,
    public val distributionType: String,
    public val baseUrl: String,
    public val sourceCatalog: String,
    public val artifacts: List<CefRuntimeArtifact>,
)

@Serializable
public data class CefRuntimeArtifact(
    public val target: String,
    public val cefPlatform: String,
    public val fileName: String,
    public val size: Long,
    public val checksum: CefRuntimeChecksum,
)

@Serializable
public data class CefRuntimeChecksum(
    public val algorithm: String,
    public val value: String,
)

public class CefRuntimeCatalog internal constructor(
    public val manifest: CefRuntimeManifest,
    artifactsByTarget: Map<KWebTarget, CefRuntimeArtifact>,
) {
    private val artifactsByTarget: Map<KWebTarget, CefRuntimeArtifact> = artifactsByTarget.toMap()

    public val supportedTargets: Set<KWebTarget> = this.artifactsByTarget.keys.toSet()

    public fun artifact(target: KWebTarget): CefRuntimeArtifact =
        artifactsByTarget.getValue(target)

    public fun downloadUri(target: KWebTarget): URI =
        URI("${manifest.baseUrl}/${artifact(target).fileName}")
}
