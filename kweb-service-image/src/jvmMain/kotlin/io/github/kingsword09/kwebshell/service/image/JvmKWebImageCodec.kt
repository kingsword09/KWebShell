package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.AlphaComposite
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.imageio.ImageIO
import javax.imageio.ImageReader
import javax.imageio.stream.ImageInputStream

public class JvmKWebImageCodec(
    private val resources: KWebImageResourceStore? = null,
) : KWebImageCodec {
    override suspend fun decode(source: KWebImageSource, intent: KWebImageIntent): KWebImage = withContext(Dispatchers.IO) {
        val encoded = when (source) {
            is KWebImageSource.Encoded -> source.value
            is KWebImageSource.PackageResource -> resources?.resolve(source)
                ?: invalid(KWebImageErrorCode.RESOURCE_NOT_FOUND, "No package resource store is installed.")
        }
        decodeBytes(encoded, intent)
    }

    override suspend fun encodePng(image: KWebImage): KWebImageEncoded = withContext(Dispatchers.IO) {
        if (image.alphaMode == KWebImageAlphaMode.PREMULTIPLIED) {
            invalid(KWebImageErrorCode.ALPHA_MODE_UNSUPPORTED, "Premultiplied alpha requires an explicit conversion contract.")
        }
        val normalized = decodeBytes(image.png, image.intent)
        KWebImageEncoded(KWebImageFormat.PNG, normalized.png.bytes)
    }

    private fun decodeBytes(encoded: KWebImageEncoded, intent: KWebImageIntent): KWebImage {
        val bytes = encoded.bytes
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
        val image = readImage(bytes)
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
        val normalized = normalize(image)
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

    private fun readImage(bytes: ByteArray): BufferedImage {
        val input: ImageInputStream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
            ?: invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The image reader could not open the payload.")
        input.use { stream ->
            val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull()
                ?: invalid(KWebImageErrorCode.FORMAT_UNSUPPORTED, "No bounded PNG/JPEG reader is available.")
            return reader.useReader(stream)
        }
    }

    private fun ImageReader.useReader(stream: ImageInputStream): BufferedImage {
        try {
            input = stream
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

    private fun normalize(source: BufferedImage): BufferedImage {
        val targetType = if (source.colorModel.hasAlpha()) BufferedImage.TYPE_INT_ARGB else BufferedImage.TYPE_INT_RGB
        val target = BufferedImage(source.width, source.height, targetType)
        val graphics: Graphics2D = target.createGraphics()
        try {
            graphics.composite = AlphaComposite.Src
            graphics.color = java.awt.Color.BLACK
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun encodeCanonicalPng(width: Int, height: Int, argb: IntArray): ByteArray {
        val rowBytes = width * 4
        val raw = ByteArray((rowBytes + 1) * height)
        var cursor = 0
        var pixel = 0
        repeat(height) {
            raw[cursor++] = 0
            repeat(width) {
                val value = argb[pixel++]
                raw[cursor++] = (value ushr 16).toByte()
                raw[cursor++] = (value ushr 8).toByte()
                raw[cursor++] = value.toByte()
                raw[cursor++] = (value ushr 24).toByte()
            }
        }
        val deflater = Deflater(9, false)
        val compressed = ByteArrayOutputStream()
        try {
            deflater.setInput(raw)
            deflater.finish()
            val buffer = ByteArray(16 * 1024)
            while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        } finally {
            deflater.end()
        }
        val output = ByteArrayOutputStream()
        output.write(PNG_SIGNATURE)
        writeChunk(output, "IHDR", byteArrayOf(
            (width ushr 24).toByte(), (width ushr 16).toByte(), (width ushr 8).toByte(), width.toByte(),
            (height ushr 24).toByte(), (height ushr 16).toByte(), (height ushr 8).toByte(), height.toByte(),
            8, 6, 0, 0, 0,
        ))
        writeChunk(output, "IDAT", compressed.toByteArray())
        writeChunk(output, "IEND", ByteArray(0))
        val result = output.toByteArray()
        if (result.size > KWEB_IMAGE_MAX_ENCODED_BYTES) {
            invalid(KWebImageErrorCode.PAYLOAD_TOO_LARGE, "The canonical PNG exceeds the encoded limit.")
        }
        return result
    }

    private fun writeChunk(output: ByteArrayOutputStream, name: String, data: ByteArray) {
        val type = name.toByteArray(Charsets.US_ASCII)
        DataOutputStream(output).use { stream -> stream.writeInt(data.size); stream.write(type); stream.write(data) }
        val crc = CRC32()
        crc.update(type)
        crc.update(data)
        DataOutputStream(output).use { stream -> stream.writeInt(crc.value.toInt()) }
    }

    private fun jpegOrientation(bytes: ByteArray): Int {
        var offset = 2
        while (offset + 4 <= bytes.size) {
            if (bytes[offset].toInt() and 0xff != 0xff) return 1
            val marker = bytes[offset + 1].toInt() and 0xff
            offset += 2
            if (marker == 0xda || marker == 0xd9) return 1
            if (offset + 2 > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG marker is truncated.")
            val length = ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)
            if (length < 2 || offset + length > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The JPEG segment is truncated.")
            if (marker == 0xe1 && length >= 8 && bytes.copyOfRange(offset + 2, offset + 8).contentEquals(EXIF_HEADER)) {
                return parseExifOrientation(bytes, offset + 8, length - 8)
            }
            offset += length
        }
        return 1
    }

    private fun parseExifOrientation(bytes: ByteArray, start: Int, size: Int): Int {
        if (size < 8 || start + size > bytes.size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF block is truncated.")
        val little = when {
            bytes[start] == 'I'.code.toByte() && bytes[start + 1] == 'I'.code.toByte() -> true
            bytes[start] == 'M'.code.toByte() && bytes[start + 1] == 'M'.code.toByte() -> false
            else -> invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF byte order is invalid.")
        }
        fun u16(index: Int): Int {
            if (index + 2 > start + size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF value is truncated.")
            val a = bytes[index].toInt() and 0xff
            val b = bytes[index + 1].toInt() and 0xff
            return if (little) a or (b shl 8) else (a shl 8) or b
        }
        fun u32(index: Int): Int {
            if (index + 4 > start + size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF value is truncated.")
            val a = bytes[index].toInt() and 0xff
            val b = bytes[index + 1].toInt() and 0xff
            val c = bytes[index + 2].toInt() and 0xff
            val d = bytes[index + 3].toInt() and 0xff
            return if (little) a or (b shl 8) or (c shl 16) or (d shl 24)
            else (a shl 24) or (b shl 16) or (c shl 8) or d
        }
        if (u16(start + 2) != 42) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF TIFF marker is invalid.")
        val ifd = start + u32(start + 4)
        if (ifd < start || ifd + 2 > start + size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF directory is invalid.")
        val count = u16(ifd)
        for (entry in 0 until count) {
            val address = ifd + 2 + entry * 12
            if (address + 12 > start + size) invalid(KWebImageErrorCode.PAYLOAD_INVALID, "The EXIF directory is truncated.")
            if (u16(address) == 0x0112 && u16(address + 2) == 3) return u16(address + 8)
        }
        return 1
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = size >= prefix.size &&
        prefix.indices.all { this[it] == prefix[it] }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val EXIF_HEADER = "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII)
    }
}
