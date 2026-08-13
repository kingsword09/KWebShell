package io.github.kingsword09.kwebshell.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public const val KWEB_BRIDGE_PROTOCOL_VERSION: Int = 1

public data class KWebBridgeRequest(
    public val method: String,
    public val payload: JsonElement,
)

public class KWebBridgeException(
    public val code: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public interface KWebBridgeDispatcher {
    public suspend fun dispatch(requestJson: String): String
}

public object KWebBridgeProtocol {
    public val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    public fun decodeRequest(requestJson: String): KWebBridgeRequest {
        val envelope = try {
            json.parseToJsonElement(requestJson).jsonObject
        } catch (error: Throwable) {
            throw KWebBridgeException(
                code = "bridge.request.invalid-json",
                message = "The bridge request is not valid JSON.",
                cause = error,
            )
        }
        val version = envelope.requiredInt("version")
        if (version != KWEB_BRIDGE_PROTOCOL_VERSION) {
            throw KWebBridgeException(
                code = "bridge.request.version-mismatch",
                message = "Bridge protocol version $version is not supported.",
            )
        }
        val allowedKeys = setOf("version", "method", "payload")
        val unknownKeys = envelope.keys - allowedKeys
        if (unknownKeys.isNotEmpty()) {
            throw KWebBridgeException(
                code = "bridge.request.unknown-field",
                message = "The bridge request contains unknown fields: ${unknownKeys.sorted().joinToString()}.",
            )
        }
        val method = envelope.requiredString("method")
        if (!METHOD_PATTERN.matches(method)) {
            throw KWebBridgeException(
                code = "bridge.request.method-invalid",
                message = "The bridge method name is invalid.",
            )
        }
        return KWebBridgeRequest(
            method = method,
            payload = envelope["payload"] ?: throw KWebBridgeException(
                code = "bridge.request.payload-missing",
                message = "The bridge request payload is required.",
            ),
        )
    }

    public fun encodeFailure(error: Throwable): String {
        val failure = when (error) {
            is KWebBridgeException -> error
            else -> KWebBridgeException(
                code = "bridge.handler.failed",
                message = "The bridge handler failed.",
                cause = error,
            )
        }
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                mapOf(
                    "code" to JsonPrimitive(failure.code),
                    "message" to JsonPrimitive(failure.message ?: "The bridge handler failed."),
                ),
            ),
        )
    }

    private fun JsonObject.requiredInt(name: String): Int =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }
            ?.content?.toIntOrNull() ?: throw KWebBridgeException(
            code = "bridge.request.$name-invalid",
            message = "The bridge request field '$name' must be an integer.",
        )

    private fun JsonObject.requiredString(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw KWebBridgeException(
            code = "bridge.request.$name-invalid",
            message = "The bridge request field '$name' must be a string.",
        )

    private val METHOD_PATTERN: Regex = Regex("[a-z][A-Za-z0-9]{0,63}")
}
