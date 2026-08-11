package io.github.kingsword09.kwebshell.runtime

import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CefRuntimeArtifactVerifierTest {
    @Test
    fun verifiesSizeAndSha1() {
        val path = createTempFile(prefix = "kweb-cef-", suffix = ".archive")
        try {
            Files.writeString(path, "runtime-data")
            CefRuntimeArtifactVerifier.verify(path, artifact(checksum = "e0e42ad869418cb9feca5900e7c7e1adba3a0b99"))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsSizeMismatchBeforeChecksum() {
        val path = createTempFile(prefix = "kweb-cef-", suffix = ".archive")
        try {
            Files.writeString(path, "runtime-data")
            val error = assertFailsWith<CefRuntimeVerificationException> {
                CefRuntimeArtifactVerifier.verify(path, artifact(size = 999))
            }
            assertEquals("runtime.artifact.size-mismatch", error.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsChecksumMismatch() {
        val path = createTempFile(prefix = "kweb-cef-", suffix = ".archive")
        try {
            Files.writeString(path, "runtime-data")
            val error = assertFailsWith<CefRuntimeVerificationException> {
                CefRuntimeArtifactVerifier.verify(path, artifact(checksum = "0000000000000000000000000000000000000000"))
            }
            assertEquals("runtime.artifact.checksum-mismatch", error.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsDirectories() {
        val path = Files.createTempDirectory("kweb-cef-directory-")
        try {
            val error = assertFailsWith<CefRuntimeVerificationException> {
                CefRuntimeArtifactVerifier.verify(path, artifact())
            }
            assertEquals("runtime.artifact.not-file", error.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun rejectsUnsupportedChecksumAlgorithmsWithTypedError() {
        val path = createTempFile(prefix = "kweb-cef-", suffix = ".archive")
        try {
            Files.writeString(path, "runtime-data")
            val error = assertFailsWith<CefRuntimeVerificationException> {
                CefRuntimeArtifactVerifier.verify(path, artifact(algorithm = "MD5"))
            }
            assertEquals("runtime.artifact.unsupported-checksum", error.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    private fun artifact(
        size: Long = 12,
        checksum: String = "e0e42ad869418cb9feca5900e7c7e1adba3a0b99",
        algorithm: String = "SHA-1",
    ): CefRuntimeArtifact = CefRuntimeArtifact(
        target = "macos-arm64",
        cefPlatform = "macosarm64",
        fileName = "runtime.tar.bz2",
        size = size,
        checksum = CefRuntimeChecksum(algorithm, checksum),
    )
}
