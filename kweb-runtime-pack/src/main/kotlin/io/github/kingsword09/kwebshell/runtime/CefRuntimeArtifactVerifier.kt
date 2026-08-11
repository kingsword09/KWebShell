package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

public class CefRuntimeVerificationException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public object CefRuntimeArtifactVerifier {
    public fun verify(path: Path, artifact: CefRuntimeArtifact) {
        if (!Files.isRegularFile(path)) {
            throw CefRuntimeVerificationException(
                code = "runtime.artifact.not-file",
                details = mapOf("path" to path.toAbsolutePath().toString()),
                message = "CEF runtime artifact '$path' is not a regular file.",
            )
        }

        val actualSize = Files.size(path)
        if (actualSize != artifact.size) {
            throw CefRuntimeVerificationException(
                code = "runtime.artifact.size-mismatch",
                details = mapOf(
                    "path" to path.toAbsolutePath().toString(),
                    "expected" to artifact.size.toString(),
                    "actual" to actualSize.toString(),
                ),
                message = "CEF runtime artifact '$path' has an unexpected size.",
            )
        }

        if (artifact.checksum.algorithm != SUPPORTED_CHECKSUM_ALGORITHM) {
            throw CefRuntimeVerificationException(
                code = "runtime.artifact.unsupported-checksum",
                details = mapOf(
                    "path" to path.toAbsolutePath().toString(),
                    "algorithm" to artifact.checksum.algorithm,
                    "supported" to SUPPORTED_CHECKSUM_ALGORITHM,
                ),
                message = "CEF runtime artifact '$path' uses an unsupported checksum algorithm.",
            )
        }

        val digest = MessageDigest.getInstance(SUPPORTED_CHECKSUM_ALGORITHM)
        try {
            Files.newInputStream(path).buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) {
                        break
                    }
                    digest.update(buffer, 0, count)
                }
            }
        } catch (exception: Exception) {
            throw CefRuntimeVerificationException(
                code = "runtime.artifact.read-failed",
                details = mapOf("path" to path.toAbsolutePath().toString()),
                message = "Unable to read CEF runtime artifact '$path'.",
                cause = exception,
            )
        }

        val actualChecksum = digest.digest().joinToString(separator = "") { byte ->
            byte.toUByte().toString(radix = 16).padStart(length = 2, padChar = '0')
        }
        if (actualChecksum != artifact.checksum.value) {
            throw CefRuntimeVerificationException(
                code = "runtime.artifact.checksum-mismatch",
                details = mapOf(
                    "path" to path.toAbsolutePath().toString(),
                    "algorithm" to artifact.checksum.algorithm,
                    "expected" to artifact.checksum.value,
                    "actual" to actualChecksum,
                ),
                message = "CEF runtime artifact '$path' failed checksum verification.",
            )
        }
    }

    private const val SUPPORTED_CHECKSUM_ALGORITHM: String = "SHA-1"
}
