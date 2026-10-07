package io.github.kingsword09.kwebshell.service.image

import java.awt.image.BufferedImage
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.image.internal.NativeImageFfm
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

public class JvmKWebNativeImage private constructor(
    private val codec: JvmKWebImageCodec,
    private val native: NativeImageFfm,
) : KWebNativeImage {
    private val closed = AtomicBoolean(false)
    private val lifecycleState = MutableStateFlow(KWebLifecycleState.OPEN)
    private val handles = linkedSetOf<JvmKWebNativeImageHandle>()
    private val operationLock = ReentrantLock()
    private var closeFailure: KWebNativeException? = null

    override val descriptor: KWebServiceDescriptor = KWebNativeImage.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = lifecycleState.asStateFlow()

    override suspend fun decode(source: KWebImageSource, intent: KWebImageIntent): KWebImage = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        operationLock.withLock {
            requireOpen()
            context.ensureActive()
            codec.decodeBlocking(source, intent).also { requireOpen(); context.ensureActive() }
        }
    }

    override suspend fun encodePng(image: KWebImage): KWebImageEncoded = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        operationLock.withLock {
            requireOpen()
            context.ensureActive()
            codec.encodePngBlocking(image).also { requireOpen(); context.ensureActive() }
        }
    }

    override suspend fun createNativeHandle(image: KWebImage): KWebNativeImageHandle {
        var created: KWebNativeImageHandle? = null
        try {
            return withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                operationLock.withLock {
                    requireOpen()
                    context.ensureActive()
                    if (handles.size >= KWEB_IMAGE_MAX_NATIVE_HANDLES) throw handleLimitFailure()
                    val boundedPng = codec.encodePngBlocking(image).bytes
                    val buffered = codec.readNativePixels(boundedPng)
                    val pixels = rgba(buffered)
                    requireOpen()
                    context.ensureActive()
                    val nativeHandle = try {
                        native.create(pixels, image.width, image.height)
                    } catch (error: NativeImageFfm.NativeFailure) {
                        throw KWebNativeException(
                            code = when (error.status()) {
                                NativeImageFfm.STATUS_HANDLE_LIMIT -> KWebImageErrorCode.HANDLE_LIMIT
                                NativeImageFfm.STATUS_OUTCOME_UNKNOWN -> KWebImageErrorCode.OUTCOME_UNKNOWN
                                else -> KWebImageErrorCode.NATIVE_FAILED
                            },
                            details = mapOf("service" to descriptor.id, "operation" to "create-native-handle"),
                            message = "The native image provider could not create a handle.",
                            cause = error,
                        )
                    }
                    try {
                        val handle = JvmKWebNativeImageHandle(native, nativeHandle) {
                            operationLock.withLock { handles.remove(it) }
                        }
                        handles += handle
                        created = handle
                        handle
                    } catch (error: Throwable) {
                        runCatching { nativeHandle.close() }.exceptionOrNull()?.let(error::addSuppressed)
                        throw error
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            // withContext can cancel result delivery after native allocation succeeds.
            // Retain the result until delivery so cancellation cannot leak its handle.
            created?.close()
            throw cancelled
        }
    }

    override fun close() {
        closed.set(true)
        operationLock.withLock {
            if (lifecycleState.value == KWebLifecycleState.CLOSED) return
            closeFailure?.let { throw it }
            lifecycleState.value = KWebLifecycleState.CLOSING
            var failure: Throwable? = null
            for (handle in handles.toList()) {
                runCatching { handle.close() }.exceptionOrNull()?.let { error ->
                    if (failure == null) failure = error else failure.addSuppressed(error)
                }
            }
            runCatching { native.close() }.exceptionOrNull()?.let { error ->
                if (failure == null) failure = error else failure.addSuppressed(error)
            }
            if (failure != null) {
                lifecycleState.value = KWebLifecycleState.FAILED
                val error = KWebNativeException(
                    code = KWebImageErrorCode.OUTCOME_UNKNOWN,
                    details = mapOf("service" to descriptor.id, "operation" to "close"),
                    message = "The native image provider could not release every handle.",
                    cause = failure,
                )
                closeFailure = error
                throw error
            }
            lifecycleState.value = KWebLifecycleState.CLOSED
        }
    }

    private fun handleLimitFailure(): KWebNativeException = KWebNativeException(
        code = KWebImageErrorCode.HANDLE_LIMIT,
        details = mapOf("service" to descriptor.id),
        message = "The native image handle limit has been reached.",
    )

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
        public fun open(libraryPath: Path, resources: KWebImageResourceStore? = null): JvmKWebNativeImage = try {
            val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
            val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
            val expectedProvider = when {
                operatingSystem.startsWith("mac") && architecture in setOf("arm64", "aarch64") -> "macos.CoreGraphics.CGImage"
                operatingSystem.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows.Win32.HBITMAP"
                operatingSystem.startsWith("linux") && architecture in setOf("amd64", "x86_64") -> "linux.GdkPixbuf"
                else -> throw IllegalArgumentException("The image provider is not published for this target.")
            }
            val native = NativeImageFfm.open(libraryPath)
            if (native.providerId() != expectedProvider) {
                native.close()
                throw IllegalArgumentException("The image library does not implement the declared provider.")
            }
            JvmKWebNativeImage(JvmKWebImageCodec(resources), native)
        } catch (_: Exception) {
            throw KWebNativeException(
                code = KWebImageErrorCode.PLATFORM_UNAVAILABLE,
                details = mapOf("service" to KWebNativeImage.DESCRIPTOR.id, "platform" to System.getProperty("os.name")),
                message = "The declared native image library is missing, incompatible, or has an invalid ABI.",
            )
        } catch (_: LinkageError) {
            throw KWebNativeException(
                code = KWebImageErrorCode.PLATFORM_UNAVAILABLE,
                details = mapOf("service" to KWebNativeImage.DESCRIPTOR.id, "platform" to System.getProperty("os.name")),
                message = "The declared native image library could not be loaded for this platform.",
            )
        }
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
    private val onClosed: (JvmKWebNativeImageHandle) -> Unit,
) : KWebNativeImageHandle {
    override val providerId: String get() = owner.providerId()
    override val isClosed: Boolean get() = handle.isClosed()
    override fun close() {
        try {
            handle.close()
            onClosed(this)
        } catch (error: NativeImageFfm.NativeFailure) {
            throw KWebNativeException(
                code = KWebImageErrorCode.OUTCOME_UNKNOWN,
                details = mapOf("service" to KWebNativeImage.DESCRIPTOR.id, "operation" to "release"),
                message = "The native image handle could not be released.",
                cause = error,
            )
        }
    }
}
