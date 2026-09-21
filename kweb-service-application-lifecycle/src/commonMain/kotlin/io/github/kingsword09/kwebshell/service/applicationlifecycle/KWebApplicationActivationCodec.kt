package io.github.kingsword09.kwebshell.service.applicationlifecycle

import kotlinx.serialization.json.Json

public object KWebApplicationActivationCodec {
    private const val MAXIMUM_FRAME_BYTES: Int = 256 * 1024
    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = false
    }

    public fun encode(batch: KWebActivationBatch): ByteArray {
        val encoded = json.encodeToString(KWebActivationBatch.serializer(), batch).encodeToByteArray()
        if (encoded.size > MAXIMUM_FRAME_BYTES) {
            throw KWebApplicationLifecycleException(
                code = KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID,
                details = mapOf("bytes" to encoded.size.toString()),
                message = "The activation frame exceeds the 256 KiB transport limit.",
            )
        }
        return encoded
    }

    public fun decode(bytes: ByteArray): KWebActivationBatch = try {
        if (bytes.isEmpty() || bytes.size > MAXIMUM_FRAME_BYTES || !isStrictUtf8(bytes)) {
            throw IllegalArgumentException("The activation frame is not bounded strict UTF-8.")
        }
        json.decodeFromString(KWebActivationBatch.serializer(), bytes.decodeToString())
    } catch (error: Throwable) {
        throw KWebApplicationLifecycleException(
            code = KWebApplicationLifecycleErrorCode.ACTIVATION_INVALID,
            details = emptyMap(),
            message = "The native application activation payload is not valid contract JSON.",
            cause = error,
        )
    }

    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index].toInt() and 0xff
            when {
                first <= 0x7f -> index += 1
                first in 0xc2..0xdf -> {
                    if (!continuation(bytes, index + 1)) return false
                    index += 2
                }
                first in 0xe0..0xef -> {
                    if (index + 2 >= bytes.size) return false
                    val second = bytes[index + 1].toInt() and 0xff
                    val third = bytes[index + 2].toInt() and 0xff
                    if (!isContinuation(second) || !isContinuation(third) ||
                        (first == 0xe0 && second < 0xa0) ||
                        (first == 0xed && second >= 0xa0)
                    ) return false
                    index += 3
                }
                first in 0xf0..0xf4 -> {
                    if (index + 3 >= bytes.size) return false
                    val second = bytes[index + 1].toInt() and 0xff
                    val third = bytes[index + 2].toInt() and 0xff
                    val fourth = bytes[index + 3].toInt() and 0xff
                    if (!isContinuation(second) || !isContinuation(third) || !isContinuation(fourth) ||
                        (first == 0xf0 && second < 0x90) ||
                        (first == 0xf4 && second >= 0x90)
                    ) return false
                    index += 4
                }
                else -> return false
            }
        }
        return true
    }

    private fun continuation(bytes: ByteArray, index: Int): Boolean =
        index < bytes.size && isContinuation(bytes[index].toInt() and 0xff)

    private fun isContinuation(value: Int): Boolean = value in 0x80..0xbf
}
