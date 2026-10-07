package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.StateFlow

public enum class KWebImageFormat(public val mimeType: String) {
    PNG("image/png"),
    JPEG("image/jpeg"),
    ;

    public companion object {
        public fun fromMimeType(value: String): KWebImageFormat = entries.singleOrNull { it.mimeType == value }
            ?: invalid(KWebImageErrorCode.FORMAT_UNSUPPORTED, "Only PNG and JPEG are supported.")
    }
}

public enum class KWebImageAlphaMode { OPAQUE, STRAIGHT, PREMULTIPLIED }
public enum class KWebImageColorSpace { SRGB }
public enum class KWebImageIntent { NORMAL, TEMPLATE, MONOCHROME }

public class KWebImageEncoded(
    public val format: KWebImageFormat,
    bytes: ByteArray,
) {
    init {
        if (bytes.isEmpty()) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "Image bytes cannot be empty.")
        if (bytes.size > KWEB_IMAGE_MAX_ENCODED_BYTES) {
            invalid(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The encoded image exceeds the 16 MiB bound.")
        }
    }

    private val byteSnapshot = bytes.copyOf()
    public val bytes: ByteArray get() = byteSnapshot.copyOf()

    override fun equals(other: Any?): Boolean =
        other is KWebImageEncoded && format == other.format && byteSnapshot.contentEquals(other.byteSnapshot)

    override fun hashCode(): Int = 31 * format.hashCode() + byteSnapshot.contentHashCode()
}

public class KWebImage(
    public val width: Int,
    public val height: Int,
    public val alphaMode: KWebImageAlphaMode,
    public val colorSpace: KWebImageColorSpace,
    public val intent: KWebImageIntent,
    public val png: KWebImageEncoded,
) {
    init {
        if (width !in 1..KWEB_IMAGE_MAX_DIMENSION || height !in 1..KWEB_IMAGE_MAX_DIMENSION) {
            invalid(KWebImageErrorCode.DIMENSIONS_INVALID, "Image dimensions are outside the supported range.")
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > KWEB_IMAGE_MAX_PIXELS) {
            invalid(KWebImageErrorCode.PIXEL_LIMIT_EXCEEDED, "The image exceeds the pixel limit.")
        }
        if (png.format != KWebImageFormat.PNG) {
            invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The normalized image must be PNG.")
        }
        if (png.bytes.size > KWEB_IMAGE_MAX_ENCODED_BYTES) {
            invalid(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The normalized image exceeds the encoded limit.")
        }
    }
}

public sealed interface KWebImageSource {
    public class Encoded(public val value: KWebImageEncoded) : KWebImageSource

    public class PackageResource(
        public val id: KWebImageResourceId,
        public val sha256: String,
    ) : KWebImageSource {
        init {
            if (!SHA256_PATTERN.matches(sha256)) {
                invalid(KWebImageErrorCode.RESOURCE_DIGEST_MISMATCH, "The resource digest is not a SHA-256 value.")
            }
        }
    }
}

public class KWebImageResourceId(public val value: String) {
    private val components = value.split('/')
    init {
        if (value.isEmpty() || value.length > 128 || value.startsWith('/') || value.startsWith('\\') ||
            value.contains('\\') || components.any {
                it.isEmpty() || it == "." || it == ".." || it.endsWith('.') || it.endsWith(' ') ||
                    it.any { character -> character in "<>:\"|?*" }
            } ||
            value.any { it.code < 0x20 || it == '\u007f' }
        ) {
            invalid(KWebImageErrorCode.RESOURCE_ID_INVALID, "The image resource identifier is not portable.")
        }
    }

    override fun equals(other: Any?): Boolean = other is KWebImageResourceId && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value
}

public class KWebImageResource(
    public val id: KWebImageResourceId,
    public val sha256: String,
    public val image: KWebImageEncoded,
) {
    init {
        if (!SHA256_PATTERN.matches(sha256)) {
            invalid(KWebImageErrorCode.RESOURCE_DIGEST_MISMATCH, "The resource digest is not a SHA-256 value.")
        }
    }
}

public class KWebImageResourceStore(resources: Collection<KWebImageResource>) {
    private val entries = resources.associateBy { it.id }

    init {
        if (entries.size != resources.size) {
            invalid(KWebImageErrorCode.RESOURCE_ID_INVALID, "An image resource identifier is duplicated.")
        }
    }

    public fun resolve(source: KWebImageSource.PackageResource): KWebImageEncoded {
        val entry = entries[source.id]
            ?: invalid(KWebImageErrorCode.RESOURCE_NOT_FOUND, "The image resource is not in the package manifest.")
        if (entry.sha256 != source.sha256 || kWebImageSha256(entry.image.bytes) != source.sha256) {
            invalid(KWebImageErrorCode.RESOURCE_DIGEST_MISMATCH, "The image resource digest does not match the package.")
        }
        return entry.image
    }
}

public data class KWebImageVariant(
    public val scale: Int,
    public val image: KWebImage,
) {
    init {
        if (scale !in 1..8) invalid(KWebImageErrorCode.VARIANT_INVALID, "Image scale must be between 1 and 8.")
    }
}

public class KWebImageVariants(
    public val logicalWidth: Int,
    public val logicalHeight: Int,
    variants: Collection<KWebImageVariant>,
) {
    private val variantSnapshot = variants.toList()
    public val variants: List<KWebImageVariant> get() = variantSnapshot.toList()

    init {
        if (logicalWidth !in 1..KWEB_IMAGE_MAX_DIMENSION || logicalHeight !in 1..KWEB_IMAGE_MAX_DIMENSION) {
            invalid(KWebImageErrorCode.DIMENSIONS_INVALID, "Logical image dimensions are outside the supported range.")
        }
        if (variantSnapshot.isEmpty() || variantSnapshot.size > KWEB_IMAGE_MAX_VARIANTS ||
            variantSnapshot.map { it.scale }.toSet().size != variantSnapshot.size
        ) {
            invalid(KWebImageErrorCode.VARIANT_INVALID, "An image variant set must contain one through sixteen unique scales.")
        }
        variantSnapshot.forEach { variant ->
            if (variant.image.width.toLong() != logicalWidth.toLong() * variant.scale ||
                variant.image.height.toLong() != logicalHeight.toLong() * variant.scale
            ) {
                invalid(KWebImageErrorCode.VARIANT_INVALID, "Image pixel dimensions must match logical size multiplied by scale.")
            }
        }
    }
}

public interface KWebImageCodec {
    public suspend fun decode(source: KWebImageSource, intent: KWebImageIntent = KWebImageIntent.NORMAL): KWebImage
    public suspend fun encodePng(image: KWebImage): KWebImageEncoded
}

public interface KWebNativeImageHandle : AutoCloseable {
    public val providerId: String
    public val isClosed: Boolean
}

public interface KWebNativeImage : KWebNativeService, KWebImageCodec {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun createNativeHandle(image: KWebImage): KWebNativeImageHandle

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "native-image",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("decode"),
                operation("encode-png"),
                KWebServiceOperationDescriptor(
                    id = "create-native-handle",
                    schemaVersion = 1,
                    rendererPermission = null,
                    requiresUserGesture = false,
                ),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = setOf(
                KWebTarget(KWebOperatingSystem.WINDOWS, KWebArchitecture.X64),
                KWebTarget(KWebOperatingSystem.MACOS, KWebArchitecture.ARM64),
                KWebTarget(KWebOperatingSystem.LINUX, KWebArchitecture.X64),
            ),
        )

        public val Key: KWebServiceKey<KWebNativeImage> = object : KWebServiceKey<KWebNativeImage> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = "native.native-image.$id",
            requiresUserGesture = false,
        )
    }
}

public object KWebImageErrorCode {
    public const val FORMAT_UNSUPPORTED: String = "image.format-unsupported"
    public const val PAYLOAD_INVALID: String = "image.payload-invalid"
    public const val PAYLOAD_TOO_LARGE: String = "image.payload-too-large"
    public const val DIMENSIONS_INVALID: String = "image.dimensions-invalid"
    public const val PIXEL_LIMIT_EXCEEDED: String = "image.pixel-limit-exceeded"
    public const val DECODED_LIMIT_EXCEEDED: String = "image.decoded-limit-exceeded"
    public const val ORIENTATION_UNSUPPORTED: String = "image.orientation-unsupported"
    public const val RESOURCE_ID_INVALID: String = "image.resource-id-invalid"
    public const val RESOURCE_NOT_FOUND: String = "image.resource-not-found"
    public const val RESOURCE_DIGEST_MISMATCH: String = "image.resource-digest-mismatch"
    public const val INTENT_INVALID: String = "image.intent-invalid"
    public const val PLATFORM_UNAVAILABLE: String = "image.platform-unavailable"
    public const val NATIVE_FAILED: String = "image.native-failed"
    public const val OUTCOME_UNKNOWN: String = "image.outcome-unknown"
    public const val OWNER_CLOSED: String = "image.owner-closed"
    public const val ALPHA_MODE_UNSUPPORTED: String = "image.alpha-mode-unsupported"
    public const val VARIANT_INVALID: String = "image.variant-invalid"
    public const val HANDLE_LIMIT: String = "image.handle-limit"
}

public const val KWEB_IMAGE_MAX_BRIDGE_BYTES: Int = 512 * 1024
public const val KWEB_IMAGE_MAX_ENCODED_BYTES: Int = 16 * 1024 * 1024
public const val KWEB_IMAGE_MAX_DECODED_BYTES: Long = 256L * 1024L * 1024L
public const val KWEB_IMAGE_MAX_PIXELS: Long = 6_000_000L
public const val KWEB_IMAGE_MAX_DIMENSION: Int = 16_384
public const val KWEB_IMAGE_MAX_VARIANTS: Int = 16
public const val KWEB_IMAGE_MAX_NATIVE_HANDLES: Int = 256

internal val SHA256_PATTERN = Regex("[0-9a-f]{64}")

internal expect fun kWebImageSha256(bytes: ByteArray): String

internal fun invalid(code: String, message: String): Nothing = throw KWebConfigurationException(
    code = code,
    details = emptyMap(),
    message = message,
)
