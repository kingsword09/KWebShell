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
    fun pngDecodeNormalizesToDeterministicRgbaPng(): Unit = runBlocking {
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
    fun jpegDecodeAndUnsupportedFormatsFailExplicitly(): Unit = runBlocking {
        val decoded = codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, jpeg())))
        assertEquals(KWebImageAlphaMode.OPAQUE, decoded.alphaMode)
        assertFailsWith<KWebConfigurationException> {
            runBlocking {
                codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, "GIF89a".encodeToByteArray())))
            }
        }
    }

    @Test
    fun malformedAndTruncatedPayloadsFailBeforeNativeUse(): Unit = runBlocking {
        assertFailsWith<KWebConfigurationException> {
            codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, byteArrayOf(0x89.toByte(), 0x50))))
        }
    }

    @Test
    fun packageDigestIsCheckedBeforeDecode(): Unit = runBlocking {
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
    fun premultipliedAlphaCannotBeEncodedWithoutAnExplicitConversion(): Unit = runBlocking {
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

    @Test
    fun declaredDimensionsMustMatchThePng(): Unit = runBlocking {
        val source = png(2, 1, intArrayOf(Color.RED.rgb, Color.BLUE.rgb))
        val image = KWebImage(
            1,
            1,
            KWebImageAlphaMode.OPAQUE,
            KWebImageColorSpace.SRGB,
            KWebImageIntent.NORMAL,
            KWebImageEncoded(KWebImageFormat.PNG, source),
        )
        val error = assertFailsWith<KWebConfigurationException> { codec.encodePng(image) }
        assertEquals(KWebImageErrorCode.DIMENSIONS_INVALID, error.code)
    }

    @Test
    fun opaqueMetadataCannotDescribeTransparentPng(): Unit = runBlocking {
        val source = png(1, 1, intArrayOf(Color(20, 30, 40, 120).rgb))
        val image = KWebImage(
            1,
            1,
            KWebImageAlphaMode.OPAQUE,
            KWebImageColorSpace.SRGB,
            KWebImageIntent.NORMAL,
            KWebImageEncoded(KWebImageFormat.PNG, source),
        )
        val error = assertFailsWith<KWebConfigurationException> { codec.encodePng(image) }
        assertEquals(KWebImageErrorCode.PAYLOAD_INVALID, error.code)
    }

    @Test
    fun jpegExifOrientationIsParsedAndOnlyIdentityIsAccepted(): Unit = runBlocking {
        val base = jpeg()
        val identityLittleEndian = codec.decode(
            KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, jpegWithExifOrientation(base, 1, true))),
        )
        val identityBigEndian = codec.decode(
            KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, jpegWithExifOrientation(base, 1, false))),
        )
        assertEquals(2, identityLittleEndian.width)
        assertEquals(2, identityBigEndian.height)

        val rotated = assertFailsWith<KWebConfigurationException> {
            codec.decode(
                KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, jpegWithExifOrientation(base, 6, true))),
            )
        }
        assertEquals(KWebImageErrorCode.ORIENTATION_UNSUPPORTED, rotated.code)

        val lateExif = assertFailsWith<KWebConfigurationException> {
            codec.decode(
                KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, jpegWithExifAfterScan(base, 6))),
            )
        }
        assertEquals(KWebImageErrorCode.ORIENTATION_UNSUPPORTED, lateExif.code)
    }

    @Test
    fun malformedExifOffsetsAndOrientationEntriesAreRejected(): Unit = runBlocking {
        val malformedOffset = jpegWithExif(base = jpeg(), exifTiff = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 42, 0,
            0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
        ))
        val offsetError = assertFailsWith<KWebConfigurationException> {
            codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, malformedOffset)))
        }
        assertEquals(KWebImageErrorCode.PAYLOAD_INVALID, offsetError.code)

        val invalidType = jpegWithExif(base = jpeg(), exifTiff = exifTiff(orientation = 1, littleEndian = true, type = 4))
        val typeError = assertFailsWith<KWebConfigurationException> {
            codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, invalidType)))
        }
        assertEquals(KWebImageErrorCode.PAYLOAD_INVALID, typeError.code)
    }

    @Test
    fun pngCrcTruncationAnimationAndTrailingBytesAreRejected(): Unit = runBlocking {
        val valid = png(2, 1, intArrayOf(Color.RED.rgb, Color.BLUE.rgb))
        val corrupt = valid.copyOf().apply { this[29] = (this[29].toInt() xor 1).toByte() }
        for (bytes in listOf(corrupt, valid.copyOf(valid.size - 12), valid + byteArrayOf(0))) {
            val error = assertFailsWith<KWebConfigurationException> { decodePng(bytes) }
            assertEquals(KWebImageErrorCode.PAYLOAD_INVALID, error.code)
        }
        val animated = insertPngChunk(valid, "acTL", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0))
        assertEquals(KWebImageErrorCode.FORMAT_UNSUPPORTED, assertFailsWith<KWebConfigurationException> { decodePng(animated) }.code)
    }

    @Test
    fun pixelAndDimensionLimitsAreCheckedBeforePixelAllocation(): Unit = runBlocking {
        val valid = png(1, 1, intArrayOf(Color.BLACK.rgb))
        fun dimensions(width: Int, height: Int): ByteArray {
            val data = valid.copyOfRange(16, 29)
            java.nio.ByteBuffer.wrap(data).putInt(width).putInt(height)
            return valid.copyOfRange(0, 8) + pngChunk("IHDR", data) + valid.copyOfRange(33, valid.size)
        }
        assertEquals(KWebImageErrorCode.PIXEL_LIMIT_EXCEEDED,
            assertFailsWith<KWebConfigurationException> { decodePng(dimensions(3001, 2000)) }.code)
        assertEquals(KWebImageErrorCode.DIMENSIONS_INVALID,
            assertFailsWith<KWebConfigurationException> { decodePng(dimensions(16385, 1)) }.code)
        val boundary = decodePng(png(3000, 2000, IntArray(6_000_000) { Color.BLACK.rgb }))
        assertEquals(6_000_000L, boundary.width.toLong() * boundary.height)
        assertEquals(KWebImageAlphaMode.OPAQUE, boundary.alphaMode)
    }

    @Test
    fun embeddedLinearRgbIccIsConvertedToSrgbWithAlphaPreserved(): Unit = runBlocking {
        val valid = png(1, 1, intArrayOf(Color(128, 128, 128, 127).rgb))
        val profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_LINEAR_RGB)
        val decoded = decodePng(insertPngChunk(valid, "iCCP", iccChunk(profile.data)))
        val pixel = ImageIO.read(decoded.png.bytes.inputStream()).getRGB(0, 0)
        assertTrue(((pixel ushr 16) and 255) in 186..190, "Linear RGB must be converted, not relabeled as sRGB.")
        assertEquals(127, pixel ushr 24)
        assertEquals(KWebImageColorSpace.SRGB, decoded.colorSpace)
        assertContentEquals(decoded.png.bytes, codec.encodePng(decoded).bytes)
    }

    @Test
    fun jpegEmbeddedIccIsConvertedToSrgb(): Unit = runBlocking {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, 8, 8, IntArray(64) { Color(128, 128, 128).rgb }, 0, 8)
        val jpeg = ByteArrayOutputStream().also { check(ImageIO.write(image, "jpeg", it)) }.toByteArray()
        val profile = java.awt.color.ICC_Profile.getInstance(java.awt.color.ColorSpace.CS_LINEAR_RGB).data
        val payload = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII) + byteArrayOf(1, 1) + profile
        val size = payload.size + 2
        val tagged = jpeg.copyOfRange(0, 2) + byteArrayOf(0xff.toByte(), 0xe2.toByte(), (size ushr 8).toByte(), size.toByte()) +
            payload + jpeg.copyOfRange(2, jpeg.size)
        val decoded = codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, tagged)))
        val pixel = ImageIO.read(decoded.png.bytes.inputStream()).getRGB(0, 0)
        assertTrue(((pixel ushr 16) and 255) in 186..190, "The JPEG ICC profile must convert linear RGB to sRGB.")
        assertEquals(KWebImageAlphaMode.OPAQUE, decoded.alphaMode)
        assertEquals(KWebImageColorSpace.SRGB, decoded.colorSpace)
    }

    @Test
    fun malformedAndOversizedIccProfilesFailWithinTheirBound(): Unit = runBlocking {
        val valid = png(1, 1, intArrayOf(Color.WHITE.rgb))
        assertEquals(KWebImageErrorCode.PAYLOAD_INVALID, assertFailsWith<KWebConfigurationException> {
            decodePng(insertPngChunk(valid, "iCCP", iccChunk(byteArrayOf(1, 2, 3))))
        }.code)
        assertEquals(KWebImageErrorCode.DECODED_LIMIT_EXCEEDED, assertFailsWith<KWebConfigurationException> {
            decodePng(insertPngChunk(valid, "iCCP", iccChunk(ByteArray(1024 * 1024 + 1))))
        }.code)
    }

    @Test
    fun canonicalPngPreservesPixelsAndMaximumWidthRows(): Unit = runBlocking {
        val pixels = IntArray(16_384 * 2) { index ->
            (if (index % 2 == 0) 0xff000000.toInt() else 0x7f000000) or (index * 7919 and 0xffffff)
        }
        val decoded = decodePng(png(16_384, 2, pixels))
        val output = ImageIO.read(decoded.png.bytes.inputStream())
        assertContentEquals(pixels, output.getRGB(0, 0, 16_384, 2, null, 0, 16_384))
        assertContentEquals(decoded.png.bytes, codec.encodePng(decoded).bytes)
    }

    private suspend fun decodePng(bytes: ByteArray): KWebImage =
        codec.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, bytes)))

    private fun insertPngChunk(png: ByteArray, name: String, payload: ByteArray): ByteArray =
        png.copyOfRange(0, 33) + pngChunk(name, payload) + png.copyOfRange(33, png.size)

    private fun pngChunk(name: String, payload: ByteArray): ByteArray {
        val type = name.toByteArray(Charsets.US_ASCII)
        val output = ByteArrayOutputStream()
        val data = java.io.DataOutputStream(output)
        data.writeInt(payload.size)
        data.write(type)
        data.write(payload)
        val crc = java.util.zip.CRC32().apply { update(type); update(payload) }
        data.writeInt(crc.value.toInt())
        return output.toByteArray()
    }

    private fun iccChunk(profile: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(output).use { it.write(profile) }
        return "fixture".encodeToByteArray() + byteArrayOf(0, 0) + output.toByteArray()
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        return ByteArrayOutputStream().also { output -> check(ImageIO.write(image, "png", output)) }.toByteArray()
    }

    private fun jpeg(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
        val output = ByteArrayOutputStream()
        check(ImageIO.write(image, "jpeg", output))
        return output.toByteArray()
    }

    private fun jpegWithExifOrientation(base: ByteArray, orientation: Int, littleEndian: Boolean): ByteArray =
        jpegWithExif(base, exifTiff(orientation, littleEndian))

    private fun jpegWithExifAfterScan(base: ByteArray, orientation: Int): ByteArray {
        val withSegment = jpegWithExif(base, exifTiff(orientation, littleEndian = true))
        val segmentEnd = 6 + "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII).size + exifTiff(orientation, true).size
        val eoi = base.size - 2
        return base.copyOfRange(0, eoi) + withSegment.copyOfRange(2, segmentEnd) + base.copyOfRange(eoi, base.size)
    }

    private fun jpegWithExif(base: ByteArray, exifTiff: ByteArray): ByteArray {
        val payload = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + exifTiff
        val segmentLength = payload.size + 2
        return byteArrayOf(
            base[0], base[1],
            0xff.toByte(), 0xe1.toByte(),
            (segmentLength ushr 8).toByte(), segmentLength.toByte(),
        ) + payload + base.copyOfRange(2, base.size)
    }

    private fun exifTiff(orientation: Int, littleEndian: Boolean, type: Int = 3): ByteArray {
        val output = ByteArrayOutputStream()
        fun u16(value: Int) {
            if (littleEndian) {
                output.write(value and 0xff)
                output.write((value ushr 8) and 0xff)
            } else {
                output.write((value ushr 8) and 0xff)
                output.write(value and 0xff)
            }
        }
        fun u32(value: Int) {
            if (littleEndian) {
                repeat(4) { shift -> output.write((value ushr (shift * 8)) and 0xff) }
            } else {
                repeat(4) { shift -> output.write((value ushr ((3 - shift) * 8)) and 0xff) }
            }
        }
        output.write(if (littleEndian) 'I'.code else 'M'.code)
        output.write(if (littleEndian) 'I'.code else 'M'.code)
        u16(42)
        u32(8)
        u16(1)
        u16(0x0112)
        u16(type)
        u32(1)
        u16(orientation)
        u16(0)
        u32(0)
        return output.toByteArray()
    }
}
