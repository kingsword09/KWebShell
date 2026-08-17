package io.github.kingsword09.kwebshell.runtime

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.CRC32

internal object KWebRuntimeReleaseFileIO {
    fun digest(path: Path): KWebRuntimeReleaseContentDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    size += count
                }
            }
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.payload-read-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to read the unsigned runtime payload.",
                cause = error,
            )
        }
        return KWebRuntimeReleaseContentDigest(size, digest.digest().toHex(), crc.value)
    }

    fun copyVerified(path: Path, output: OutputStream, expected: KWebRuntimeReleaseContentDigest) {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        var size = 0L
        try {
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    releaseRequire(
                        size <= expected.size,
                        code = "runtime.release.payload-mutated",
                        details = { mapOf("path" to path.toString()) },
                        message = "The unsigned runtime payload grew while it was being copied.",
                    )
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    crc.update(buffer, 0, count)
                }
            }
        } catch (error: KWebRuntimeReleaseException) {
            throw error
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.payload-copy-failed",
                details = mapOf("path" to path.toString()),
                message = "Unable to copy the unsigned runtime payload into the release pack.",
                cause = error,
            )
        }
        releaseRequire(
            size == expected.size && digest.digest().toHex() == expected.sha256 && crc.value == expected.crc32,
            code = "runtime.release.payload-mutated",
            details = { mapOf("path" to path.toString()) },
            message = "The unsigned runtime payload changed while the release pack was being written.",
        )
    }
}
