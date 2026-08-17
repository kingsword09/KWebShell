package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

internal class KWebRuntimePayloadException(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

@Serializable
internal data class KWebRuntimePayloadManifest(
    val schemaVersion: Int,
    val product: String,
    val productVersion: String,
    val target: String,
    val cefVersion: String,
    val chromiumVersion: String,
    val sourceArtifact: KWebRuntimePayloadSourceArtifact,
    val treeSha256: String,
    val entries: List<KWebRuntimePayloadEntry>,
)

@Serializable
internal data class KWebRuntimePayloadSourceArtifact(
    val fileName: String,
    val size: Long,
    val checksumAlgorithm: String,
    val checksum: String,
)

@Serializable
internal data class KWebRuntimePayloadEntry(
    val path: String,
    val type: KWebRuntimePayloadEntryType,
    val mode: String,
    val size: Long,
    val sha256: String,
    val linkTarget: String? = null,
)

@Serializable
internal enum class KWebRuntimePayloadEntryType {
    @SerialName("directory")
    DIRECTORY,

    @SerialName("file")
    FILE,

    @SerialName("symlink")
    SYMLINK,
}

internal object KWebRuntimePayloadManifestCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
    }

    fun encode(manifest: KWebRuntimePayloadManifest): ByteArray =
        (json.encodeToString(KWebRuntimePayloadManifest.serializer(), manifest) + "\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): KWebRuntimePayloadManifest {
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.manifest-encoding-invalid",
                message = "The runtime payload manifest is not valid UTF-8.",
                cause = error,
            )
        }
        return try {
            json.decodeFromString(KWebRuntimePayloadManifest.serializer(), text)
        } catch (error: Exception) {
            payloadFailure(
                code = "runtime.payload.manifest-json-invalid",
                message = "The runtime payload manifest is not valid schema JSON.",
                cause = error,
            )
        }
    }

    fun treeSha256(entries: List<KWebRuntimePayloadEntry>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("KWebShell runtime payload tree v1\u0000".toByteArray(StandardCharsets.UTF_8))
        entries.forEach { entry ->
            val type = when (entry.type) {
                KWebRuntimePayloadEntryType.DIRECTORY -> "directory"
                KWebRuntimePayloadEntryType.FILE -> "file"
                KWebRuntimePayloadEntryType.SYMLINK -> "symlink"
            }
            listOf(
                entry.path,
                type,
                entry.mode,
                entry.size.toString(),
                entry.sha256,
                entry.linkTarget.orEmpty(),
            ).forEach { field ->
                digest.update(field.toByteArray(StandardCharsets.UTF_8))
                digest.update(0)
            }
        }
        return digest.digest().toHex()
    }
}

internal fun sha256(path: Path): String {
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
        payloadFailure(
            code = "runtime.payload.file-not-regular",
            details = mapOf("path" to path.toString()),
            message = "The runtime payload input is not a regular file: '$path'.",
        )
    }
    return try {
        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use(::sha256)
    } catch (error: KWebRuntimePayloadException) {
        throw error
    } catch (error: Exception) {
        payloadFailure(
            code = "runtime.payload.file-read-failed",
            details = mapOf("path" to path.toString()),
            message = "Unable to read runtime payload input '$path'.",
            cause = error,
        )
    }
}

internal fun sha256(input: InputStream): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        digest.update(buffer, 0, count)
    }
    return digest.digest().toHex()
}

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String = joinToString("") { byte ->
    byte.toUByte().toString(16).padStart(2, '0')
}

internal fun payloadFailure(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
): Nothing = throw KWebRuntimePayloadException(code, details, message, cause)

internal const val KWEB_RUNTIME_PAYLOAD_SCHEMA_VERSION: Int = 1
internal const val KWEB_RUNTIME_PAYLOAD_PRODUCT: String = "KWebShell"
internal const val KWEB_RUNTIME_PAYLOAD_MANIFEST_PATH: String = "manifest.json"
internal const val KWEB_RUNTIME_PAYLOAD_DIRECTORY_MODE: String = "0755"
internal const val KWEB_RUNTIME_PAYLOAD_EXECUTABLE_MODE: String = "0755"
internal const val KWEB_RUNTIME_PAYLOAD_FILE_MODE: String = "0644"
internal const val KWEB_RUNTIME_PAYLOAD_SYMLINK_MODE: String = "0777"
internal const val KWEB_RUNTIME_PAYLOAD_EMPTY_SHA256: String =
    "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
