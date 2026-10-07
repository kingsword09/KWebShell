package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.color.ICC_ColorSpace
import java.awt.color.ICC_Profile
import java.awt.image.ColorConvertOp
import java.awt.image.ComponentColorModel
import java.awt.image.DirectColorModel
import java.awt.image.IndexColorModel
import java.util.zip.InflaterInputStream
import javax.imageio.stream.MemoryCacheImageInputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.Locale
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

public class JvmKWebImageCodec(
    private val resources: KWebImageResourceStore? = null,
) : KWebImageCodec {
    override suspend fun decode(source: KWebImageSource, intent: KWebImageIntent): KWebImage = withContext(Dispatchers.IO) {
        decodeBlocking(source, intent)
    }

    internal fun decodeBlocking(source: KWebImageSource, intent: KWebImageIntent): KWebImage {
        val encoded = when (source) {
            is KWebImageSource.Encoded -> source.value
            is KWebImageSource.PackageResource -> resources?.resolve(source)
                ?: invalid(KWebImageErrorCode.RESOURCE_NOT_FOUND, "No package resource store is installed.")
        }
        return decodeBytes(encoded, intent)
    }

    override suspend fun encodePng(image: KWebImage): KWebImageEncoded = withContext(Dispatchers.IO) {
        encodePngBlocking(image)
    }

    internal fun encodePngBlocking(image: KWebImage): KWebImageEncoded {
        if (image.alphaMode == KWebImageAlphaMode.PREMULTIPLIED) {
            invalid(KWebImageErrorCode.ALPHA_MODE_UNSUPPORTED, "Premultiplied alpha requires an explicit conversion contract.")
        }
        val normalized = decodeBytes(image.png, image.intent)
        if (normalized.width != image.width || normalized.height != image.height) {
            invalid(KWebImageErrorCode.DIMENSIONS_INVALID, "Declared image dimensions do not match the PNG payload.")
        }
        if (image.alphaMode == KWebImageAlphaMode.OPAQUE && normalized.alphaMode != KWebImageAlphaMode.OPAQUE) {
            invalid(KWebImageErrorCode.PAYLOAD_INVALID, "Declared opaque alpha does not match the PNG payload.")
        }
        return KWebImageEncoded(KWebImageFormat.PNG, normalized.png.bytes)
    }

    private fun decodeBytes(encoded: KWebImageEncoded, intent: KWebImageIntent): KWebImage {
        val bytes = encoded.bytes
        val profile = if (encoded.format == KWebImageFormat.PNG) validatePng(bytes) else null
        when (encoded.format) {
            KWebImageFormat.PNG -> {
                if (!bytes.startsWith(PNG_SIGNATURE)) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG signature is invalid.")
            }
            KWebImageFormat.JPEG -> {
                if (bytes.size < 2 || bytes[0] != 0xff.toByte() || bytes[1] != 0xd8.toByte()) {
                    invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG signature is invalid.")
                }
                if (jpegOrientation(bytes) != 1) {
                    invalid(KWebImageErrorCode.ORIENTATION_UNSUPPORTED, "Only identity JPEG EXIF orientation is supported.")
                }
            }
        }
        val image = readImage(bytes, encoded.format)
        val pixels = image.width.toLong() * image.height.toLong()
        if (image.width !in 1..KWEB_IMAGE_MAX_DIMENSION || image.height !in 1..KWEB_IMAGE_MAX_DIMENSION) {
            invalid(KWebImageErrorCode.DIMENSIONS_INVALID, "The decoded image dimensions are outside the supported range.")
        }
        if (pixels > KWEB_IMAGE_MAX_PIXELS) {
            invalid(KWebImageErrorCode.PIXEL_LIMIT_EXCEEDED, "The decoded image exceeds the pixel limit.")
        }
        val decodedBytes = pixels * 4L
        if (decodedBytes > KWEB_IMAGE_MAX_DECODED_BYTES) {
            invalid(KWebImageErrorCode.DECODED_LIMIT_EXCEEDED, "The decoded image exceeds the 256 MiB limit.")
        }
        val normalized = normalize(image, profile)
        val argb = IntArray(normalized.width * normalized.height)
        normalized.getRGB(0, 0, normalized.width, normalized.height, argb, 0, normalized.width)
        val alpha = if (argb.all { (it ushr 24) == 0xff }) KWebImageAlphaMode.OPAQUE else KWebImageAlphaMode.STRAIGHT
        val png = KWebImageEncoded(KWebImageFormat.PNG, encodeCanonicalPng(normalized.width, normalized.height, argb))
        return KWebImage(
            width = normalized.width,
            height = normalized.height,
            alphaMode = alpha,
            colorSpace = KWebImageColorSpace.SRGB,
            intent = intent,
            png = png,
        )
    }

    internal fun readNativePixels(png: ByteArray): BufferedImage = readImage(png, KWebImageFormat.PNG)

    private fun readImage(bytes: ByteArray, format: KWebImageFormat): BufferedImage {
        val input: ImageInputStream = MemoryCacheImageInputStream(ByteArrayInputStream(bytes))
        input.use { stream ->
            val expected = if (format == KWebImageFormat.PNG) "png" else "jpeg"
            val expectedReaderClass = if (format == KWebImageFormat.PNG) {
                "com.sun.imageio.plugins.png.PNGImageReader"
            } else {
                "com.sun.imageio.plugins.jpeg.JPEGImageReader"
            }
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull { candidate ->
                candidate.javaClass.name == expectedReaderClass && candidate.formatName.lowercase(Locale.ROOT).let { name ->
                    if (expected == "jpeg") name == "jpeg" || name == "jpg" else name == expected
                }
            } ?: invalid(KWebImageErrorCode.FORMAT_UNSUPPORTED, "The JDK ${expected.uppercase(Locale.ROOT)} reader is unavailable.")
            return reader.useReader(stream)
        }
    }

    private fun ImageReader.useReader(stream: ImageInputStream): BufferedImage {
        try {
            setInput(stream, false, true)
            addIIOReadWarningListener { _, _ -> invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The image reader rejected damaged image data.") }
            val width = getWidth(0)
            val height = getHeight(0)
            if (width !in 1..KWEB_IMAGE_MAX_DIMENSION || height !in 1..KWEB_IMAGE_MAX_DIMENSION) {
                invalid(KWebImageErrorCode.DIMENSIONS_INVALID, "The encoded image dimensions are outside the supported range.")
            }
            if (width.toLong() * height.toLong() > KWEB_IMAGE_MAX_PIXELS) {
                invalid(KWebImageErrorCode.PIXEL_LIMIT_EXCEEDED, "The encoded image exceeds the pixel limit.")
            }
            return read(0)
        } catch (error: KWebConfigurationException) {
            throw error
        } catch (_: Throwable) {
            invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The image payload is malformed or truncated.")
        } finally {
            dispose()
        }
    }

    private fun normalize(source: BufferedImage, profile: ICC_Profile?): BufferedImage {
        val targetType = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(source.width, source.height, targetType)
        if (profile != null) {
            try {
                val expanded = if (source.colorModel is IndexColorModel) {
                    BufferedImage(source.width, source.height, BufferedImage.TYPE_4BYTE_ABGR).also { image ->
                        val graphics = image.createGraphics()
                        try { graphics.composite = AlphaComposite.Src; graphics.drawImage(source, 0, 0, null) }
                        finally { graphics.dispose() }
                    }
                } else source
                val model = expanded.colorModel
                if (profile.numComponents != model.numColorComponents) {
                    invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The ICC profile does not match the image channels.")
                }
                val space = ICC_ColorSpace(profile)
                val taggedModel = if (model is DirectColorModel) {
                    DirectColorModel(space, model.pixelSize, model.redMask, model.greenMask, model.blueMask,
                        model.alphaMask, model.isAlphaPremultiplied, model.transferType)
                } else {
                    ComponentColorModel(space, model.componentSize, model.hasAlpha(), model.isAlphaPremultiplied,
                        model.transparency, model.transferType)
                }
                val tagged = BufferedImage(taggedModel, expanded.raster, model.isAlphaPremultiplied, null)
                ColorConvertOp(null).filter(tagged, target)
            } catch (error: KWebConfigurationException) {
                throw error
            } catch (_: Exception) {
                invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The image ICC profile cannot be normalized to sRGB.")
            }
            return target
        }
        val graphics: Graphics2D = target.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeCanonicalPng(width: Int, height: Int, argb: IntArray): ByteArray {
        val output = ByteArrayOutputStream(64 * 1024)
        output.write(PNG_SIGNATURE)
        writeChunk(output, "IHDR", byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        ))
        writeChunk(output, "sRGB", byteArrayOf(0))
        val deflater = Deflater(9, false)
        val row = ByteArray(width * 4 + 1)
        val compressed = ByteArray(64 * 1024)
        try {
            var pixel = 0
            repeat(height) {
                var cursor = 1
                repeat(width) {
                    val value = argb[pixel++]
                    row[cursor++] = (value ushr 16).toByte()
                    row[cursor++] = (value ushr 8).toByte()
                    row[cursor++] = value.toByte()
                    row[cursor++] = (value ushr 24).toByte()
                }
                deflater.setInput(row)
                while (!deflater.needsInput()) {
                    val count = deflater.deflate(compressed)
                    if (count > 0) writeChunk(output, "IDAT", compressed, count)
                }
            }
            deflater.finish()
            while (!deflater.finished()) {
                val count = deflater.deflate(compressed)
                if (count > 0) writeChunk(output, "IDAT", compressed, count)
            }
        } finally {
            deflater.end()
        }
        writeChunk(output, "IEND", ByteArray(0))
        return output.toByteArray()
    }

    private fun writeChunk(output: ByteArrayOutputStream, name: String, data: ByteArray, length: Int = data.size) {
        if (output.size().toLong() + 12 + length > KWEB_IMAGE_MAX_ENCODED_BYTES) {
            invalid(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The canonical PNG exceeds the encoded limit.")
        }
        val type = name.toByteArray(Charsets.US_ASCII)
        val stream = DataOutputStream(output)
        stream.writeInt(length)
        stream.write(type)
        stream.write(data, 0, length)
        val crc = CRC32()
        crc.update(type)
        crc.update(data, 0, length)
        stream.writeInt(crc.value.toInt())
    }

    private fun validatePng(bytes: ByteArray): ICC_Profile? {
        if (!bytes.startsWith(PNG_SIGNATURE)) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG signature is invalid.")
        fun u32(offset: Int): Long = (0..3).fold(0L) { value, index -> (value shl 8) or (bytes[offset + index].toLong() and 255) }
        var offset = PNG_SIGNATURE.size
        var chunks = 0
        var sawData = false
        var dataEnded = false
        var profile: ICC_Profile? = null
        var srgb = false
        while (offset < bytes.size) {
            if (++chunks > 4096 || bytes.size - offset < 12) {
                invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG chunk structure exceeds its bound or is truncated.")
            }
            val length = u32(offset)
            if (length > bytes.size.toLong() - offset - 12) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG chunk is truncated.")
            val size = length.toInt()
            val name = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            if (name.any { it !in 'A'..'Z' && it !in 'a'..'z' } || name[2] !in 'A'..'Z') {
                invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG chunk type is invalid.")
            }
            if (name[0] in 'A'..'Z' && name !in setOf("IHDR", "PLTE", "IDAT", "IEND")) {
                invalid(KWebImageErrorCode.FORMAT_UNSUPPORTED, "The PNG requires an unsupported critical chunk.")
            }
            val crc = CRC32().apply { update(bytes, offset + 4, size + 4) }
            if (crc.value != u32(offset + 8 + size)) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG checksum is invalid.")
            if (chunks == 1 && (name != "IHDR" || size != 13) || chunks > 1 && name == "IHDR") {
                invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG header is missing or duplicated.")
            }
            if (sawData && name != "IDAT" && name != "IEND") dataEnded = true
            when (name) {
                "acTL", "fcTL", "fdAT" -> invalid(KWebImageErrorCode.FORMAT_UNSUPPORTED, "Animated PNG is not supported.")
                "IDAT" -> {
                    if (dataEnded) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG image data is not contiguous.")
                    sawData = true
                }
                "sRGB" -> {
                    if (srgb || profile != null || sawData || size != 1 || bytes[offset + 8].toInt() !in 0..3) {
                        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG sRGB declaration is invalid.")
                    }
                    srgb = true
                }
                "iCCP" -> {
                    if (profile != null || srgb || sawData) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG ICC profile is misplaced or duplicated.")
                    val start = offset + 8
                    val end = start + size
                    val separator = (start until minOf(end, start + 80)).firstOrNull { bytes[it] == 0.toByte() }
                        ?: invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG ICC profile name is invalid.")
                    if (separator == start || separator + 2 >= end || bytes[separator + 1] != 0.toByte()) {
                        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG ICC compression header is invalid.")
                    }
                    try {
                        val inflated = InflaterInputStream(ByteArrayInputStream(bytes, separator + 2, end - separator - 2)).use {
                            it.readNBytes(1024 * 1024 + 1)
                        }
                        if (inflated.size > 1024 * 1024) invalid(KWebImageErrorCode.DECODED_LIMIT_EXCEEDED, "The PNG ICC profile exceeds 1 MiB.")
                        profile = ICC_Profile.getInstance(inflated)
                    } catch (error: KWebConfigurationException) {
                        throw error
                    } catch (_: Exception) {
                        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG ICC profile is malformed.")
                    }
                }
                "IEND" -> {
                    if (size != 0 || !sawData || offset + 12 != bytes.size) {
                        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG end marker is invalid.")
                    }
                    return profile
                }
            }
            offset += size + 12
        }
        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The PNG end marker is missing.")
    }

    private fun jpegOrientation(bytes: ByteArray): Int {
        var offset = 2
        var orientation: Int? = null
        var scanningEntropy = false
        while (offset < bytes.size) {
            var marker = -1
            if (scanningEntropy) {
                var foundMarker = false
                while (offset < bytes.size) {
                    if ((bytes[offset].toInt() and 0xff) != 0xff) {
                        offset++
                        continue
                    }
                    offset++
                    while (offset < bytes.size && (bytes[offset].toInt() and 0xff) == 0xff) offset++
                    if (offset >= bytes.size) break
                    val candidate = bytes[offset++].toInt() and 0xff
                    if (candidate == 0x00 || candidate in 0xd0..0xd7) continue
                    marker = candidate
                    scanningEntropy = false
                    foundMarker = true
                    break
                }
                if (!foundMarker) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG ended before its end marker.")
            } else {
                if ((bytes[offset].toInt() and 0xff) != 0xff) {
                    invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG marker prefix is invalid.")
                }
                while (offset < bytes.size && (bytes[offset].toInt() and 0xff) == 0xff) offset++
                if (offset >= bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG marker is truncated.")
                marker = bytes[offset++].toInt() and 0xff
            }
            if (marker == 0x00) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG marker is invalid.")
            if (marker == 0xd9) return orientation ?: 1
            if (marker == 0x01 || marker in 0xd0..0xd7) continue
            if (marker == 0xd8) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG contains a nested start marker.")
            if (offset + 2 > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG marker is truncated.")
            val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
            if (length < 2 || offset + length > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG segment is truncated.")
            val payload = offset + 2
            val payloadSize = length - 2
            if (marker == 0xe1 && bytes.regionStartsWith(payload, payloadSize, EXIF_PREFIX)) {
                if (!bytes.regionStartsWith(payload, payloadSize, EXIF_HEADER)) {
                    invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF identifier is invalid or truncated.")
                }
                val found = parseExifOrientation(bytes, payload + EXIF_HEADER.size, payloadSize - EXIF_HEADER.size)
                if (found != null) {
                    if (orientation != null) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG has duplicate EXIF orientation tags.")
                    orientation = found
                }
            }
            offset += length
            if (marker == 0xda) scanningEntropy = true
        }
        invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG ended before image data.")
    }

    private fun parseExifOrientation(bytes: ByteArray, start: Int, size: Int): Int? {
        val end = start.toLong() + size.toLong()
        if (size < 8 || end > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF block is truncated.")
        val little = when {
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> true
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> false
            else -> invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF byte order is invalid.")
        }
        fun u16(index: Int): Int {
            if (index < start || index.toLong() + 2 > end) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF value is truncated.")
            val a = bytes[index].toInt() and 0xff
            val b = bytes[index + 1].toInt() and 0xff
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(index: Int): Long {
            if (index < start || index.toLong() + 4 > end) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF value is truncated.")
            val a = bytes[index].toInt() and 0xff
            val b = bytes[index + 1].toInt() and 0xff
            val c = bytes[index + 2].toInt() and 0xff
            val d = bytes[index + 3].toInt() and 0xff
            return if (little) {
                (a.toLong() or (b.toLong() shl 8) or (c.toLong() shl 16) or (d.toLong() shl 24))
            } else {
                ((a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong())
            }
        }
        if (u16(start + 2) != 42) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF TIFF marker is invalid.")
        val ifdOffset = u32(start + 4)
        if (ifdOffset < 8 || ifdOffset > size - 2L) {
            invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF directory is invalid.")
        }
        val ifd = (start.toLong() + ifdOffset).toInt()
        val count = u16(ifd)
        if (ifd.toLong() + 2 + count * 12L + 4 > end) {
            invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF directory is truncated.")
        }
        var orientation: Int? = null
        for (entry in 0 until count) {
            val address = (ifd.toLong() + 2 + entry * 12L).toInt()
            if (u16(address) == 0x0112) {
                if (orientation != null) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF directory has duplicate orientation tags.")
                if (u16(address + 2) != 3 || u32(address + 4) != 1L) {
                    invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF orientation entry has an invalid type or count.")
                }
                val value = u16(address + 8)
                if (value !in 1..8) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF orientation value is invalid.")
                orientation = value
            }
        }
        return orientation
    }

    private fun ByteArray.regionStartsWith(offset: Int, length: Int, prefix: ByteArray): Boolean =
        length >= prefix.size && offset >= 0 && offset.toLong() + prefix.size <= size &&
            prefix.indices.all { this[offset + it] == prefix[it] }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
        val EXIF_PREFIX = "Exif".toByteArray(Charsets.US_ASCII)
    }
}
