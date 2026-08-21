package io.github.kingsword09.kwebshell.example.html5

import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class CapabilityCdpEvidence(
    val chromiumProduct: String,
    val protocolVersion: String,
    val revision: String,
    val javaScriptVersion: String,
    val accessibilityRole: String,
    val accessibilityName: String,
)

internal class CapabilityLabCdpClient(
    port: Int,
    timeoutMs: Long,
) {
    private val delegate = KWebExampleCdpClient(port, timeoutMs)

    fun inspect(pageUrl: String): CapabilityCdpEvidence = translateFailures {
        val browser = delegate.awaitBrowserVersion()
        delegate.openPageSession(pageUrl).use { session ->
            val accessibility = session.command("Accessibility.getFullAXTree")
            val nodes = accessibility["nodes"]?.jsonArray
                ?: throw CapabilityLabException(
                    "cdp-accessibility-missing",
                    "CDP returned no accessibility nodes.",
                )
            val marker = nodes.map { it.jsonObject }.firstOrNull { node ->
                node.axValue("name") == ACCESSIBILITY_NAME
            } ?: throw CapabilityLabException(
                "cdp-accessibility-marker-missing",
                "The page accessibility tree does not contain the capability evidence region.",
            )
            val role = marker.axValue("role")
                ?: throw CapabilityLabException(
                    "cdp-accessibility-role-missing",
                    "The marker accessibility node has no role.",
                )
            if (role != "region") {
                throw CapabilityLabException(
                    "cdp-accessibility-role-invalid",
                    "Expected accessibility role 'region', got '$role'.",
                )
            }
            CapabilityCdpEvidence(
                chromiumProduct = browser.product,
                protocolVersion = browser.protocolVersion,
                revision = browser.revision,
                javaScriptVersion = browser.javaScriptVersion,
                accessibilityRole = role,
                accessibilityName = ACCESSIBILITY_NAME,
            )
        }
    }

    fun dispatchTrustedClick(pageUrl: String, x: Int, y: Int) = translateFailures {
        if (x <= 0 || y <= 0) {
            throw CapabilityLabException("cdp-input-coordinates-invalid", "CDP input coordinates must be positive.")
        }
        delegate.openPageSession(pageUrl).use { session ->
            listOf("mouseMoved", "mousePressed", "mouseReleased").forEach { type ->
                session.command(
                    "Input.dispatchMouseEvent",
                    buildJsonObject {
                        put("type", type)
                        put("x", x)
                        put("y", y)
                        if (type != "mouseMoved") {
                            put("button", "left")
                            put("clickCount", 1)
                        }
                    },
                )
            }
        }
    }

    fun assertUnavailable() = translateFailures { delegate.assertUnavailable() }

    private inline fun <T> translateFailures(block: () -> T): T = try {
        block()
    } catch (error: KWebExampleCdpException) {
        throw CapabilityLabException(error.code, error.message.orEmpty(), error)
    }

    private companion object {
        const val ACCESSIBILITY_NAME = "KWebShell capability evidence"
    }
}

private fun kotlinx.serialization.json.JsonObject.axValue(name: String): String? =
    this[name]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull
