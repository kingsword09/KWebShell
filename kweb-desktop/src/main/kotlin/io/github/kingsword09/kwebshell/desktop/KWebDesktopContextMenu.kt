package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebContextMenuDecision
import io.github.kingsword09.kwebshell.core.KWebContextMenuItem
import io.github.kingsword09.kwebshell.core.KWebContextMenuItemKind
import io.github.kingsword09.kwebshell.core.KWebContextMenuRequest
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Decodes the native page context-menu payload into the published contract and
 * encodes one decision back. Unknown keys and malformed values are typed
 * failures; the decoder never guesses an item identity.
 */
internal object KWebDesktopContextMenuJson {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    private const val MAXIMUM_ITEMS = 512
    private const val MAXIMUM_DEPTH = 8

    fun parse(
        event: NativeBrowserEvent,
        profileId: String,
        pageId: String,
    ): KWebContextMenuRequest {
        val root = parseObject(event.details)
        if (requiredInt(root, "version") != 1) invalid("version")
        val requestId = requiredLong(root, "requestId")
        if (requestId != event.requestId || requestId <= 0L) invalid("requestId")
        val origin = nullableString(root, "origin") ?: event.origin.takeIf { it.isNotEmpty() }
        if (event.origin.isNotEmpty() && origin != null && event.origin != origin) invalid("origin")
        val url = requiredString(root, "url", allowEmpty = true)
        if (event.url.isNotEmpty() && event.url != url) invalid("url")
        val items = parseItems(root.requiredArray("items"), 1)
        return KWebContextMenuRequest(
            requestId = requestId,
            profileId = profileId,
            pageId = pageId,
            origin = origin,
            url = url,
            frameId = event.frameId,
            isMainFrame = event.frameScope == 1,
            x = requiredInt(root, "x"),
            y = requiredInt(root, "y"),
            editable = requiredBoolean(root, "editable"),
            selectionText = requiredString(root, "selection", allowEmpty = true),
            linkUrl = requiredString(root, "linkUrl", allowEmpty = true),
            items = items,
        )
    }

    fun decisionPayload(decision: KWebContextMenuDecision): String = when (decision) {
        is KWebContextMenuDecision.CONTINUE ->
            """{"decision":"continue","commandId":${quote(decision.command)}}"""
        KWebContextMenuDecision.DISMISS -> """{"decision":"dismiss"}"""
    }

    private fun parseItems(array: JsonArray, depth: Int): List<KWebContextMenuItem> {
        if (depth > MAXIMUM_DEPTH || array.size > MAXIMUM_ITEMS) invalid("items")
        val parsed = mutableListOf<KWebContextMenuItem>()
        for (element in array) {
            val item = element.jsonObject
            val kind = requiredString(item, "kind")
            if (kind == "separator") {
                parsed += KWebContextMenuItem.Separator
                continue
            }
            val children = item["items"]?.jsonArray?.let { parseItems(it, depth + 1) }.orEmpty()
            parsed += KWebContextMenuItem.Item(
                kind = when (kind) {
                    "command" -> KWebContextMenuItemKind.COMMAND
                    "checkbox" -> KWebContextMenuItemKind.CHECKBOX
                    "radio" -> KWebContextMenuItemKind.RADIO
                    "submenu" -> KWebContextMenuItemKind.SUBMENU
                    else -> invalid("items.kind")
                },
                command = requiredString(item, "command"),
                label = requiredString(item, "label", allowEmpty = true),
                enabled = requiredBoolean(item, "enabled"),
                checked = requiredBoolean(item, "checked"),
                items = children,
            )
        }
        return parsed
    }

    private fun quote(value: String): String = json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(value))

    private fun parseObject(payload: String): JsonObject {
        if (payload.isEmpty()) invalid("payload")
        return try {
            json.parseToJsonElement(payload).jsonObject
        } catch (error: Throwable) {
            throw invalid("payload", error)
        }
    }

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: invalid("missing.$name")

    private fun requiredString(
        source: JsonObject,
        name: String,
        allowEmpty: Boolean = false,
    ): String {
        val value = source.required(name).jsonPrimitive
        if (!value.isString) invalid(name)
        val text = value.content
        if (!allowEmpty && text.isEmpty()) invalid(name)
        return text
    }

    private fun nullableString(source: JsonObject, name: String): String? {
        val value = source[name] ?: return null
        if (value is kotlinx.serialization.json.JsonNull) return null
        val primitive = value.jsonPrimitive
        if (!primitive.isString) invalid(name)
        return primitive.content
    }

    private fun requiredInt(source: JsonObject, name: String): Int =
        try {
            source.required(name).jsonPrimitive.int
        } catch (error: Throwable) {
            throw invalid(name, error)
        }

    private fun requiredLong(source: JsonObject, name: String): Long =
        try {
            source.required(name).jsonPrimitive.long
        } catch (error: Throwable) {
            throw invalid(name, error)
        }

    private fun requiredBoolean(source: JsonObject, name: String): Boolean =
        try {
            source.required(name).jsonPrimitive.boolean
        } catch (error: Throwable) {
            throw invalid(name, error)
        }

    private fun JsonObject.requiredArray(name: String): JsonArray =
        try {
            required(name).jsonArray
        } catch (error: Throwable) {
            throw invalid(name, error)
        }

    private fun invalid(field: String, cause: Throwable? = null): Nothing = throw KWebNativeException(
        code = "page.context-menu.event-invalid",
        details = mapOf("field" to field),
        message = "The native page context-menu payload field '$field' is invalid.",
        cause = cause,
    )
}
