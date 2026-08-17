package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal class KWebRuntimeReleaseException(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

@Serializable
internal data class KWebRuntimeReleaseManifest(
    val schemaVersion: Int,
    val product: String,
    val productVersion: String,
    val target: String,
    val cefVersion: String,
    val chromiumVersion: String,
    val payload: KWebRuntimeReleasePayload,
    val signatureAlgorithm: String,
    val keyId: String,
)

@Serializable
internal data class KWebRuntimeReleasePayload(
    val fileName: String,
    val size: Long,
    val sha256: String,
    val treeSha256: String,
)

internal object KWebRuntimeReleaseManifestCodec {
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
    }

    fun encode(manifest: KWebRuntimeReleaseManifest): ByteArray =
        (json.encodeToString(KWebRuntimeReleaseManifest.serializer(), manifest) + "\n")
            .toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): KWebRuntimeReleaseManifest {
        val text = try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.metadata-encoding-invalid",
                message = "The signed runtime release metadata is not valid UTF-8.",
                cause = error,
            )
        }
        return try {
            json.decodeFromString(KWebRuntimeReleaseManifest.serializer(), text)
        } catch (error: Exception) {
            releaseFailure(
                code = "runtime.release.metadata-json-invalid",
                message = "The signed runtime release metadata is not valid schema JSON.",
                cause = error,
            )
        }
    }
}

internal const val KWEB_RUNTIME_RELEASE_SCHEMA_VERSION: Int = 1
internal const val KWEB_RUNTIME_RELEASE_PRODUCT: String = "KWebShell"
internal const val KWEB_RUNTIME_RELEASE_SIGNATURE_ALGORITHM: String = "Ed25519"
internal const val KWEB_RUNTIME_RELEASE_METADATA_PATH: String = "metadata.json"
internal const val KWEB_RUNTIME_RELEASE_PAYLOAD_PATH: String = "payload.zip"
internal const val KWEB_RUNTIME_RELEASE_SIGNATURE_PATH: String = "signature.ed25519"
internal const val KWEB_RUNTIME_RELEASE_PAYLOAD_FILE_NAME: String = "payload.zip"
internal const val KWEB_RUNTIME_RELEASE_SIGNATURE_SIZE: Int = 64
internal const val KWEB_RUNTIME_RELEASE_MAX_METADATA_BYTES: Long = 1L * 1024L * 1024L
internal const val KWEB_RUNTIME_RELEASE_MAX_KEY_BYTES: Long = 16L * 1024L

internal val KWEB_RUNTIME_RELEASE_SIGNATURE_DOMAIN: ByteArray =
    "KWebShell signed runtime release v1\u0000".toByteArray(StandardCharsets.US_ASCII)

internal val KWEB_RUNTIME_RELEASE_PACK_ENTRY_NAMES: List<String> = listOf(
    KWEB_RUNTIME_RELEASE_METADATA_PATH,
    KWEB_RUNTIME_RELEASE_PAYLOAD_PATH,
    KWEB_RUNTIME_RELEASE_SIGNATURE_PATH,
)

internal val KWEB_RUNTIME_RELEASE_KEY_ID_PATTERN: Regex = Regex("[0-9a-f]{64}")
internal val KWEB_RUNTIME_RELEASE_SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")

internal fun releaseFailure(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
): Nothing = throw KWebRuntimeReleaseException(code, details, message, cause)

internal inline fun releaseRequire(
    condition: Boolean,
    code: String,
    details: () -> Map<String, String> = { emptyMap() },
    message: String,
) {
    if (!condition) releaseFailure(code, details(), message)
}
