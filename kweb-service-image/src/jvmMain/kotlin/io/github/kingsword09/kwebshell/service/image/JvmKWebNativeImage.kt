package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.image.internal.NativeImageFfm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.imageio.ImageIO

public class JvmKWebNativeImage private constructor(
    private val codec: JvmKWebImageCodec,
    private val native: NativeImageFfm,
) : KWebNativeImage {
    private val closed = AtomicBoolean(false)
    private val lifecycleState = MutableStateFlow(KWebLifecycleState.OPEN)
    private val handles = linkedSetOf<JvmKWebNativeImageHandle>()

    override val descriptor: KWebServiceDescriptor = KWebNativeImage.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = lifecycleState.asStateFlow()

    override suspend fun decode(source: KWebImageSource, intent: KWebImageIntent): KWebImage {
        requireOpen()
        return codec.decode(source, intent)
    }

    override suspend fun encodePng(image: KWebImage): KWebImageEncoded {
        requireOpen()
        return codec.encodePng(image)
    }

    override suspend fun createNativeHandle(image: KWebImage): KWebNativeImageHandle = withContext(Dispatchers.IO) {
        requireOpen()
        val buffered = ImageIO.read(ByteArrayInputStream(image.png.bytes))
            ?: throw KWebNativeException(
                code = KWebImageErrorCode.PAYLOAD_INVALID,
                details = mapOf("service" to descriptor.id, "operation" to "create-native-handle"),
                message = "The normalized image could not be decoded for the native provider.",
            )
        val pixels = rgba(buffered)
        val handle = JvmKWebNativeImageHandle(native, native.create(pixels, image.width, image.height))
        synchronized(handles) { handles += handle }
        handle
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val snapshot = synchronized(handles) { handles.toList() }
        var failure: Throwable? = null
        snapshot.forEach { handle ->
            runCatching { handle.close() }.exceptionOrNull()?.let { error -> if (failure == null) failure = error }
        }
        runCatching { native.close() }.exceptionOrNull()?.let { error -> if (failure == null) failure = error }
        if (failure != null) {
            lifecycleState.value = KWebLifecycleState.FAILED
            throw KWebNativeException(
                code = KWebImageErrorCode.OUTCOME_UNKNOWN,
                details = mapOf("service" to descriptor.id, "operation" to "close"),
                message = "The native image provider could not release every handle.",
                cause = failure,
            )
        }
        lifecycleState.value = KWebLifecycleState.CLOSED
    }

    private fun requireOpen() {
        if (closed.get()) {
            throw KWebNativeException(
                code = KWebImageErrorCode.OWNER_CLOSED,
                details = mapOf("service" to KWebNativeImage.DESCRIPTOR.id),
                message = "The native image service is closed.",
            )
        }
    }

    public companion object {
        public fun open(libraryPath: Path, resources: KWebImageResourceStore? = null): JvmKWebNativeImage =
            JvmKWebNativeImage(JvmKWebImageCodec(resources), NativeImageFfm.open(libraryPath))
    }

    private fun rgba(image: BufferedImage): ByteArray {
        val argb = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, argb, 0, image.width)
        val bytes = ByteArray(argb.size * 4)
        argb.forEachIndexed { index, value ->
            bytes[index * 4] = (value ushr 16).toByte()
            bytes[index * 4 + 1] = (value ushr 8).toByte()
            bytes[index * 4 + 2] = value.toByte()
            bytes[index * 4 + 3] = (value ushr 24).toByte()
        }
        return bytes
    }
}

private class JvmKWebNativeImageHandle(
    private val owner: NativeImageFfm,
    private val handle: NativeImageFfm.NativeHandle,
) : KWebNativeImageHandle {
    override val providerId: String get() = owner.providerId()
    override val isClosed: Boolean get() = handle.isClosed()
    override fun close() { handle.close() }
}
