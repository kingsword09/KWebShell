package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlinx.coroutines.runBlocking
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmKWebImageCodecTest {
    private val codec = JvmKWebImageCodec()

    @Test
    fun pngDecodeNormalizesToDeterministicRgbaPng() = runBlocking {
        val source = png(
            2,
            1,
            intArrayOf(Color.RED.rgb, Color(0, 128, 255, 127).rgb),
        )
        val first = codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, source)))
        val second = codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, source)))
        assertEquals(2, first.width)
        assertEquals(1, first.height)
        assertEquals(KWebImageAlphaMode.STRAIGHT, first.alphaMode)
        assertContentEquals(first.png.bytes, second.png.bytes)
        assertTrue(first.png.bytes.size < KWEB_IMAGE_MAX_ENCODED_BYTES)
    }

    @Test
    fun jpegDecodeAndUnsupportedFormatsFailExplicitly() = runBlocking {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpeg", output))
        val decoded = codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, output.toByteArray())))
        assertEquals(KWebImageAlphaMode.OPAQUE, decoded.alphaMode)
        assertFailsWith<KWebConfigurationException> {
            runBlocking {
                codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, "GIF89a".encodeToByteArray())))
            }
        }
    }

    @Test
    fun malformedAndTruncatedPayloadsFailBeforeNativeUse() = runBlocking {
        assertFailsWith<KWebConfigurationException> {
            codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, byteArrayOf(0x89.toByte(), 0x50))))
        }
    }

    @Test
    fun packageDigestIsCheckedBeforeDecode() = runBlocking {
        val source = png(1, 1, intArrayOf(Color.BLUE.rgb))
        val encoded = KWebImageEncoded(KWebImageFormat.PNG, source)
        val digest = kWebImageSha256(source)
        val store = KWebImageResourceStore(listOf(KWebImageResource(KWebImageResourceId("icon.png"), digest, encoded)))
        val image = JvmKWebImageCodec(store).decode(KWebImageSource.PackageResource(KWebImageResourceId("icon.png"), digest))
        assertEquals(1, image.width)
        assertFailsWith<KWebConfigurationException> {
            JvmKWebImageCodec(store).decode(KWebImageSource.PackageResource(KWebImageResourceId("icon.png"), "0".repeat(64)))
        }
    }

    @Test
    fun premultipliedAlphaCannotBeEncodedWithoutAnExplicitConversion() = runBlocking {
        val source = png(1, 1, intArrayOf(Color.WHITE.rgb))
        val image = KWebImage(
            1,
            1,
            KWebImageAlphaMode.PREMULTIPLIED,
            KWebImageColorSpace.SRGB,
            KWebImageIntent.NORMAL,
            KWebImageEncoded(KWebImageFormat.PNG, source),
        )
        assertFailsWith<KWebConfigurationException> { codec.encodePng(image) }
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().also { output -> check(ImageIO.write(image, "png", output)) }.toByteArray()
    }
}
