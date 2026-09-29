package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebDownloadCollisionPolicy
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

public data class KWebDesktopDownloadPolicy(
    public val directory: Path,
    public val collision: KWebDownloadCollisionPolicy =
        KWebDownloadCollisionPolicy.RENAME_UNIQUE,
    public val computeSha256: Boolean = true,
    public val expectedSha256ByUrl: Map<String, String> = emptyMap(),
) {
    internal fun validated(): KWebDesktopDownloadPolicy {
        val normalized = directory.toAbsolutePath().normalize()
        if (!directory.isAbsolute || Files.isSymbolicLink(directory) ||
            !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) || !Files.isWritable(normalized)
        ) {
            throw KWebConfigurationException(
                code = "download.destination.invalid",
                details = mapOf("directory" to directory.toString()),
                message = "The download directory must be an absolute, writable, non-symlink directory.",
            )
        }
        if (expectedSha256ByUrl.size > 64) {
            throw KWebConfigurationException(
                code = "download.policy.invalid",
                details = mapOf("field" to "expectedSha256ByUrl"),
                message = "The download expected-hash policy is too large.",
            )
        }
        val normalizedExpected = expectedSha256ByUrl.map { (url, hash) ->
            val parsed = runCatching { URI(url) }.getOrNull()
            if (parsed == null || !parsed.isAbsolute || parsed.userInfo != null ||
                hash.length != 64 || hash.any { it !in "0123456789abcdef" }
            ) {
                throw KWebConfigurationException(
                    code = "download.policy.invalid",
                    details = mapOf("url" to url),
                    message = "The expected download hash policy contains an invalid URL or hash.",
                )
            }
            url to hash
        }.toMap()
        if (normalizedExpected.isNotEmpty() && !computeSha256) {
            throw KWebConfigurationException(
                code = "download.policy.invalid",
                details = mapOf("field" to "computeSha256"),
                message = "Expected download hashes require SHA-256 computation.",
            )
        }
        return copy(directory = normalized, expectedSha256ByUrl = normalizedExpected)
    }
}
