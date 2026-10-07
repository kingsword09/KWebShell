package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.image.generated.DecodeRequest
import io.github.kingsword09.kwebshell.service.image.generated.EncodeRequest
import io.github.kingsword09.kwebshell.service.image.generated.EncodedResponse
import io.github.kingsword09.kwebshell.service.image.generated.ImageBridgeDispatcher
import io.github.kingsword09.kwebshell.service.image.generated.ImageBridgeHandler
import io.github.kingsword09.kwebshell.service.image.generated.ImageResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException
import java.util.Base64

public fun KWebNativeImage.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher {
    val service = this
    return ImageBridgeDispatcher(
        object : ImageBridgeHandler {
        override suspend fun decode(request: DecodeRequest): ImageResponse = service.dispatch("decode", policyEngine, subject) {
            val source = when (request.sourceKind) {
                "encoded" -> KWebImageSource.Encoded(
                    KWebImageEncoded(KWebImageFormat.fromMimeType(request.format), decodeBase64(request.payload)),
                )
                "package" -> KWebImageSource.PackageResource(
                    KWebImageResourceId(request.resourceId ?: throw invalidBridge("resourceId is required")),
                    request.sha256 ?: throw invalidBridge("sha256 is required"),
                )
                else -> throw invalidBridge("sourceKind is not published")
            }
            decode(source, parseIntent(request.intent)).toResponse()
        }

        override suspend fun encodePng(request: EncodeRequest): EncodedResponse = service.dispatch("encode-png", policyEngine, subject) {
            val png = KWebImageEncoded(KWebImageFormat.PNG, decodeBase64(request.pngBase64))
            encodePng(
                KWebImage(
                    request.width,
                    request.height,
                    parseAlpha(request.alphaMode),
                    parseColorSpace(request.colorSpace),
                    parseIntent(request.intent),
                    png,
                ),
            ).let { EncodedResponse(it.format.mimeType, encodeBase64(it.bytes)) }
        }
        },
    )
}

private suspend fun <T> KWebNativeImage.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    block: suspend KWebNativeImage.() -> T,
): T {
    val descriptor = KWebNativeImage.DESCRIPTOR.operations.first { it.id == operation }
    when (val verdict = policyEngine.authorize(subject, KWebNativeImage.DESCRIPTOR.id, descriptor).decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException("service.permission-denied", "The image operation was denied.")
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException("service.consent-required", "The image operation requires consent.")
    }
    return try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The image operation failed.", error)
    } catch (error: Throwable) {
        throw KWebBridgeException(KWebImageErrorCode.NATIVE_FAILED, "The image operation failed.", error)
    }
}

private fun decodeBase64(payload: String): ByteArray = try {
    if (payload.length > ((KWEB_IMAGE_MAX_BRIDGE_BYTES + 2) / 3) * 4) {
        throw KWebBridgeException(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The image payload exceeds the 512 KiB bridge bound.")
    }
    Base64.getDecoder().decode(payload).also { requireBridgeBound(it) }
} catch (_: IllegalArgumentException) {
    throw invalidBridge("The image payload is not valid base64.")
}

private fun parseIntent(value: String): KWebImageIntent = runCatching { KWebImageIntent.valueOf(value) }
    .getOrElse { throw invalidBridge("The image intent is not published.") }

private fun parseAlpha(value: String): KWebImageAlphaMode = runCatching { KWebImageAlphaMode.valueOf(value) }
    .getOrElse { throw invalidBridge("The image alpha mode is not published.") }

private fun parseColorSpace(value: String): KWebImageColorSpace = runCatching { KWebImageColorSpace.valueOf(value) }
    .getOrElse { throw invalidBridge("The image color space is not published.") }

private fun KWebImage.toResponse(): ImageResponse = ImageResponse(
    width,
    height,
    alphaMode.name,
    colorSpace.name,
    intent.name,
    encodeBase64(png.bytes),
)

private fun invalidBridge(message: String): KWebBridgeException = KWebBridgeException(
    KWebImageErrorCode.PAYLOAD_INVALID,
    message,
)

private fun requireBridgeBound(bytes: ByteArray) {
    if (bytes.size > KWEB_IMAGE_MAX_BRIDGE_BYTES) {
        throw KWebBridgeException(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The image payload exceeds the 512 KiB bridge bound.")
    }
}

private fun encodeBase64(bytes: ByteArray): String {
    requireBridgeBound(bytes)
    return Base64.getEncoder().encodeToString(bytes)
}
