package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class KWebDesktopDownloadUpdate(
    val id: Long,
    val status: String,
    val originalUrl: String,
    val url: String,
    val suggestedFileName: String,
    val contentDisposition: String,
    val mimeType: String,
    val receivedBytes: Long,
    val totalBytes: Long?,
    val currentSpeedBytesPerSecond: Long,
    val interruptReason: Int,
    val stagingPath: String?,
)

internal object KWebDesktopDownloadJson {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    private val fields = setOf(
        "version", "downloadId", "status", "originalUrl", "url",
        "suggestedFileName", "contentDisposition", "mimeType", "receivedBytes",
        "totalBytes", "currentSpeedBytesPerSecond", "interruptReason", "stagingPath",
    )

    fun parse(payload: String): KWebDesktopDownloadUpdate {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (error: Throwable) {
            throw invalid("The native download event is not valid JSON.", error)
        }
        if (root.keys != fields) invalid("The native download event fields are not exact.")
        if (root.requiredLong("version") != 1L) invalid("The native download event version is unsupported.")
        val status = root.requiredString("status")
        if (status !in setOf("starting", "in-progress", "paused", "complete", "canceled", "interrupted", "denied")) {
            invalid("The native download status is unsupported.")
        }
        val interruptReason = root.requiredLong("interruptReason").toInt()
        if (interruptReason < 0) invalid("The native download interrupt reason is negative.")
        return KWebDesktopDownloadUpdate(
            id = root.requiredLong("downloadId").also { if (it <= 0) invalid("The download ID is invalid.") },
            status = status,
            originalUrl = root.requiredString("originalUrl"),
            url = root.requiredString("url"),
            suggestedFileName = root.requiredString("suggestedFileName"),
            contentDisposition = root.optionalString("contentDisposition").orEmpty(),
            mimeType = root.optionalString("mimeType").orEmpty(),
            receivedBytes = root.requiredLong("receivedBytes").also { if (it < 0) invalid("Received bytes are negative.") },
            totalBytes = root.optionalLong("totalBytes")?.also { if (it < 0) invalid("Total bytes are negative.") },
            currentSpeedBytesPerSecond = root.requiredLong("currentSpeedBytesPerSecond")
                .also { if (it < 0) invalid("Download speed is negative.") },
            interruptReason = interruptReason,
            stagingPath = root.optionalString("stagingPath"),
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: invalid("The native download field '$name' is missing.")

    private fun JsonObject.requiredLong(name: String): Long =
        this[name]?.jsonPrimitive?.longOrNull
            ?: invalid("The native download field '$name' is not a decimal integer.")

    private fun JsonObject.optionalLong(name: String): Long? = when (val value: JsonElement? = this[name]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.longOrNull ?: invalid("The native download field '$name' is invalid.")
    }

    private fun JsonObject.optionalString(name: String): String? = when (val value: JsonElement? = this[name]) {
        null, JsonNull -> null
        else -> value.jsonPrimitive.content.takeIf { it.isNotBlank() }
    }

    private fun invalid(message: String, cause: Throwable? = null): Nothing = throw KWebNativeException(
        code = "download.event-invalid",
        details = emptyMap(),
        message = message,
        cause = cause,
    )
}
