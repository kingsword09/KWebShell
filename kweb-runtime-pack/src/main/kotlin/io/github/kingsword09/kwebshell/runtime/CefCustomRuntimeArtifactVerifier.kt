package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

public class CefCustomRuntimeVerificationException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

public object CefCustomRuntimeArtifactVerifier {
    public fun verify(path: Path, catalog: CefSourcePatchCatalog, target: KWebTarget) {
        val artifact = catalog.customRuntimeArtifact(target)
        val absolute = path.toAbsolutePath().normalize()
        if (!Files.isRegularFile(absolute) || absolute.fileName.toString() != artifact.fileName) {
            failure(
                code = "runtime.custom-runtime.archive-invalid",
                details = mapOf("path" to absolute.toString(), "expectedFileName" to artifact.fileName),
                message = "The custom CEF runtime archive is missing or has the wrong file name.",
            )
        }
        val size = Files.size(absolute)
        if (size != artifact.size) {
            failure(
                code = "runtime.custom-runtime.archive-size-mismatch",
                details = mapOf("expected" to artifact.size.toString(), "actual" to size.toString()),
                message = "The custom CEF runtime archive size does not match its manifest.",
            )
        }
        val archiveDigest = Files.newInputStream(absolute).use(::sha256)
        if (archiveDigest != artifact.sha256) {
            failure(
                code = "runtime.custom-runtime.archive-digest-mismatch",
                details = mapOf("expected" to artifact.sha256, "actual" to archiveDigest),
                message = "The custom CEF runtime archive failed SHA-256 verification.",
            )
        }

        val platform = CEF_PLATFORM_BY_TARGET[target] ?: failure(
            code = "runtime.custom-runtime.target-invalid",
            details = mapOf("target" to target.id),
            message = "The target is not part of the custom runtime publication contract.",
        )
        val root = "cef_binary_${catalog.manifest.cefVersion}_${platform}_minimal"
        val libraryEntryName = "$root/${LIBRARY_BY_TARGET.getValue(target)}"
        val headerEntryName = "$root/include/internal/cef_kweb_extension_abi.h"
        val headerDigest = catalog.manifest.patches.single().createdPostimages
            .single { it.path == "include/internal/cef_kweb_extension_abi.h" }
            .sha256

        try {
            ZipFile(absolute.toFile()).use { archive ->
                val entries = archive.entries().asSequence().toList()
                validateEntries(entries, root)
                val library = requireFileEntry(entries, libraryEntryName)
                val header = requireFileEntry(entries, headerEntryName)
                val needles = (catalog.manifest.exports + catalog.manifest.adapterAbiFingerprint)
                    .map { it.toByteArray(StandardCharsets.US_ASCII) }
                val libraryResult = archive.getInputStream(library).use { input ->
                    digestAndFind(input, needles)
                }
                if (libraryResult.digest != artifact.librarySha256) {
                    failure(
                        code = "runtime.custom-runtime.library-digest-mismatch",
                        details = mapOf(
                            "entry" to libraryEntryName,
                            "expected" to artifact.librarySha256,
                            "actual" to libraryResult.digest,
                        ),
                        message = "The custom libcef binary does not match its pinned digest.",
                    )
                }
                if (!libraryResult.found.all { it }) {
                    val missing = needles.indices.filterNot { libraryResult.found[it] }
                        .joinToString { index -> String(needles[index], StandardCharsets.US_ASCII) }
                    failure(
                        code = "runtime.custom-runtime.adapter-evidence-missing",
                        details = mapOf("entry" to libraryEntryName, "missing" to missing),
                        message = "The custom libcef binary lacks required adapter ABI evidence.",
                    )
                }
                val actualHeaderDigest = archive.getInputStream(header).use(::sha256)
                if (actualHeaderDigest != headerDigest) {
                    failure(
                        code = "runtime.custom-runtime.abi-header-digest-mismatch",
                        details = mapOf("expected" to headerDigest, "actual" to actualHeaderDigest),
                        message = "The packaged adapter ABI header differs from the source patch.",
                    )
                }
            }
        } catch (error: CefCustomRuntimeVerificationException) {
            throw error
        } catch (error: ZipException) {
            failure(
                code = "runtime.custom-runtime.archive-zip-invalid",
                details = mapOf("path" to absolute.toString()),
                message = "The custom CEF runtime is not a valid ZIP archive.",
                cause = error,
            )
        } catch (error: Exception) {
            failure(
                code = "runtime.custom-runtime.archive-read-failed",
                details = mapOf("path" to absolute.toString()),
                message = "The custom CEF runtime archive could not be verified.",
                cause = error,
            )
        }
    }

    private fun validateEntries(entries: List<ZipEntry>, expectedRoot: String) {
        if (entries.isEmpty() || entries.size > MAXIMUM_ENTRY_COUNT) {
            failure(
                code = "runtime.custom-runtime.archive-entry-count-invalid",
                details = mapOf("count" to entries.size.toString()),
                message = "The custom CEF runtime archive has an invalid entry count.",
            )
        }
        val names = mutableSetOf<String>()
        entries.forEach { entry ->
            val name = entry.name
            val components = name.removeSuffix("/").split('/')
            if (name.startsWith('/') || '\\' in name || components.any { it.isEmpty() || it == "." || it == ".." } ||
                components.firstOrNull() != expectedRoot || !names.add(name)
            ) {
                failure(
                    code = "runtime.custom-runtime.archive-entry-invalid",
                    details = mapOf("entry" to name),
                    message = "The custom CEF runtime archive contains an unsafe or duplicate entry.",
                )
            }
        }
    }

    private fun requireFileEntry(entries: List<ZipEntry>, name: String): ZipEntry {
        val matches = entries.filter { it.name == name && !it.isDirectory }
        if (matches.size != 1) {
            failure(
                code = "runtime.custom-runtime.required-entry-missing",
                details = mapOf("entry" to name, "matches" to matches.size.toString()),
                message = "The custom CEF runtime archive lacks an exact required file.",
            )
        }
        return matches.single()
    }

    private fun digestAndFind(input: InputStream, needles: List<ByteArray>): DigestEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        val found = BooleanArray(needles.size)
        val maximumNeedleSize = needles.maxOf(ByteArray::size)
        var tail = ByteArray(0)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            val searchable = ByteArray(tail.size + count)
            tail.copyInto(searchable)
            buffer.copyInto(searchable, tail.size, 0, count)
            needles.forEachIndexed { index, needle ->
                if (!found[index] && searchable.contains(needle)) found[index] = true
            }
            val retained = minOf(searchable.size, maximumNeedleSize - 1)
            tail = searchable.copyOfRange(searchable.size - retained, searchable.size)
        }
        return DigestEvidence(digest.digest().hex(), found)
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().hex()
    }

    private fun ByteArray.hex(): String = joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

    private fun failure(
        code: String,
        details: Map<String, String>,
        message: String,
        cause: Throwable? = null,
    ): Nothing = throw CefCustomRuntimeVerificationException(code, details, message, cause)

    private data class DigestEvidence(val digest: String, val found: BooleanArray)

    private const val MAXIMUM_ENTRY_COUNT: Int = 20_000
    private val CEF_PLATFORM_BY_TARGET: Map<KWebTarget, String> = mapOf(
        KWebTarget.parse("macos-arm64") to "macosarm64",
        KWebTarget.parse("windows-x64") to "windows64",
        KWebTarget.parse("linux-x64") to "linux64",
    )
    private val LIBRARY_BY_TARGET: Map<KWebTarget, String> = mapOf(
        KWebTarget.parse("macos-arm64") to
            "Release/Chromium Embedded Framework.framework/Chromium Embedded Framework",
        KWebTarget.parse("windows-x64") to "Release/libcef.dll",
        KWebTarget.parse("linux-x64") to "Release/libcef.so",
    )
}
