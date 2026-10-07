package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.service.image.internal.NativeImageFfm
import io.github.kingsword09.kwebshell.service.image.internal.ImageTestNativeAccess
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

public fun main() = runBlocking {
    val library = Path.of(
        System.getProperty("kweb.image.native.library.path")
            ?: error("Missing kweb.image.native.library.path"),
    )
    val target = System.getProperty("kweb.image.target") ?: error("Missing kweb.image.target")
    val expectedProvider = when (target) {
        "macos-arm64" -> "macos.CoreGraphics.CGImage"
        "windows-x64" -> "windows.Win32.HBITMAP"
        "linux-x64" -> "linux.GdkPixbuf"
        else -> error("Unsupported image integration target")
    }
    var provider = ""
    repeat(8) {
    NativeImageFfm.open(library).use { native ->
        provider = native.providerId()
        check(provider == expectedProvider)
        check(native.liveCount() == 0)
        val pixels = ByteArray(2 * 2 * 4) { 0x7f }
        native.create(pixels, 2, 2).use { handle ->
            check(handle.value() != 0L)
            check(native.liveCount() == 1)
        }
        check(native.liveCount() == 0)
    }
    }
    val providerOwner = NativeImageFfm.open(library)
    NativeImageFfm.open(library).use { observer ->
        val liveHandles = List(3) { providerOwner.create(ByteArray(16) { 0x7f }, 2, 2) }
        check(observer.liveCount() == liveHandles.size)
        providerOwner.close()
        providerOwner.close()
        check(liveHandles.all { it.isClosed() })
        check(observer.liveCount() == 0)
    }

    val fixture = KWebImageEncoded(KWebImageFormat.PNG, fixturePng())
    val digest = sha256(fixture.bytes)
    val resourceId = KWebImageResourceId("icons/fixture.png")
    val resources = KWebImageResourceStore(listOf(KWebImageResource(resourceId, digest, fixture)))
    val service = JvmKWebNativeImage.open(library, resources)
    val decodedPng: KWebImage
    val decodedJpeg: KWebImage
    val deterministic: Boolean
    val resourceChecked: Boolean
    val rejected = linkedMapOf<String, String>()
    try {
        val image = service.decode(
            KWebImageSource.Encoded(
                KWebImageEncoded(KWebImageFormat.PNG, fixturePng()),
            ),
        )
        decodedPng = image
        decodedJpeg = service.decode(KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.JPEG, fixtureJpeg())))
        check(decodedPng.width == 2 && decodedPng.height == 2 && decodedPng.alphaMode == KWebImageAlphaMode.STRAIGHT)
        check(decodedJpeg.width == 2 && decodedJpeg.height == 2 && decodedJpeg.alphaMode == KWebImageAlphaMode.OPAQUE)
        deterministic = image.png == service.encodePng(image) && image.png == service.encodePng(image)
        check(deterministic)
        resourceChecked = service.decode(KWebImageSource.PackageResource(resourceId, digest)).png == image.png
        check(resourceChecked)
        val mismatch = runCatching { service.decode(KWebImageSource.PackageResource(resourceId, "0".repeat(64))) }.exceptionOrNull()
        check(mismatch is KWebException && mismatch.code == KWebImageErrorCode.RESOURCE_DIGEST_MISMATCH)
        rejected["digestMismatch"] = mismatch.code
        for (format in listOf("image/webp", "image/gif", "image/svg+xml", "image/x-icon")) {
            val error = runCatching { KWebImageFormat.fromMimeType(format) }.exceptionOrNull()
            check(error is KWebException && error.code == KWebImageErrorCode.FORMAT_UNSUPPORTED)
            rejected[format] = error.code
        }
        val handle = service.createNativeHandle(image)
        check(handle.providerId.isNotBlank())
        check(!handle.isClosed)
        handle.close()
        check(handle.isClosed)
    } finally {
        service.close()
    }

    val observer = NativeImageFfm.open(library)
    val limitedService = JvmKWebNativeImage.open(library)
    val boundedImage = limitedService.decode(
        KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, fixturePng())),
    )
    val liveHandles = List(KWEB_IMAGE_MAX_NATIVE_HANDLES) { limitedService.createNativeHandle(boundedImage) }
    check(observer.liveCount() == KWEB_IMAGE_MAX_NATIVE_HANDLES)
    val limitError = runCatching { limitedService.createNativeHandle(boundedImage) }.exceptionOrNull()
    check(limitError is KWebNativeException && limitError.code == KWebImageErrorCode.HANDLE_LIMIT)
    limitedService.close()
    limitedService.close()
    check(liveHandles.all { it.isClosed })
    check(observer.liveCount() == 0)
    check(limitedService.lifecycle.value == KWebLifecycleState.CLOSED)

    val concurrentService = JvmKWebNativeImage.open(library)
    val concurrentImage = concurrentService.decode(
        KWebImageSource.Encoded(KWebImageEncoded(KWebImageFormat.PNG, fixturePng())),
    )
    val concurrentHandle = concurrentService.createNativeHandle(concurrentImage)
    race(
        { concurrentService.close() },
        { concurrentHandle.close() },
        { concurrentService.close() },
    )
    check(concurrentHandle.isClosed)
    check(concurrentService.lifecycle.value == KWebLifecycleState.CLOSED)
    check(observer.liveCount() == 0)
    val delivery = LinkedBlockingQueue<Runnable>()
    val returnDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) { delivery.put(block) }
    }
    val cancellationService = JvmKWebNativeImage.open(library)
    val cancelledCreation = CoroutineScope(coroutineContext + returnDispatcher).async(start = CoroutineStart.UNDISPATCHED) {
        cancellationService.createNativeHandle(boundedImage)
    }
    val resume = checkNotNull(delivery.poll(10, TimeUnit.SECONDS)) { "Native creation did not reach result delivery." }
    check(observer.liveCount() == 1)
    cancelledCreation.cancel()
    resume.run()
    cancelledCreation.join()
    check(observer.liveCount() == 0)
    cancellationService.close()
    val absent = runCatching { JvmKWebNativeImage.open(library.resolveSibling("absent-native-image-library")) }.exceptionOrNull()
    check(absent is KWebNativeException && absent.code == KWebImageErrorCode.PLATFORM_UNAVAILABLE)
    check(!absent.toString().contains(library.parent.toString()))
    val failedOwner = NativeImageFfm.open(library)
    val invalidatedHandle = failedOwner.create(ByteArray(16) { 0x7f }, 2, 2)
    check(ImageTestNativeAccess.releaseFromAnotherOwner(library, invalidatedHandle.value()) == NativeImageFfm.STATUS_OK)
    val releaseFailure = runCatching { invalidatedHandle.close() }.exceptionOrNull()
    check(releaseFailure is NativeImageFfm.NativeFailure && releaseFailure.status() == NativeImageFfm.STATUS_INVALID_ARGUMENT)
    check(runCatching { invalidatedHandle.close() }.exceptionOrNull() === releaseFailure)
    check(runCatching { failedOwner.close() }.exceptionOrNull() === releaseFailure)
    check(runCatching { failedOwner.close() }.exceptionOrNull() === releaseFailure)
    check(observer.liveCount() == 0)
    observer.close()
    val closedError = runCatching { service.decode(KWebImageSource.Encoded(fixture)) }.exceptionOrNull()
    check(closedError is KWebException && closedError.code == KWebImageErrorCode.OWNER_CLOSED)
    rejected["ownerClosed"] = closedError.code
    writeEvidence(library, target, provider, decodedPng, decodedJpeg, deterministic, resourceChecked, rejected)
}

private fun race(vararg actions: () -> Unit) {
    val start = CountDownLatch(1)
    val failures = ConcurrentLinkedQueue<Throwable>()
    val workers = actions.map { action ->
        Thread {
            try {
                start.await()
                action()
            } catch (error: Throwable) {
                failures += error
            }
        }.apply { start() }
    }
    start.countDown()
    workers.forEach(Thread::join)
    failures.peek()?.let { throw AssertionError("Concurrent image lifecycle operation failed.", it) }
}

private fun fixturePng(): ByteArray = java.io.ByteArrayOutputStream().also { output ->
    val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    image.setRGB(0, 0, 2, 2, intArrayOf(0x7fff0000, 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt()), 0, 2)
    check(javax.imageio.ImageIO.write(image, "png", output))
}.toByteArray()

private fun fixtureJpeg(): ByteArray = java.io.ByteArrayOutputStream().also { output ->
    val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB)
    image.setRGB(0, 0, 2, 2, intArrayOf(0xff0000, 0x00ff00, 0x0000ff, 0xffffff), 0, 2)
    check(javax.imageio.ImageIO.write(image, "jpeg", output))
}.toByteArray()

private fun writeEvidence(
    library: Path,
    target: String,
    provider: String,
    png: KWebImage,
    jpeg: KWebImage,
    deterministic: Boolean,
    resourceChecked: Boolean,
    rejected: Map<String, String>,
) {
    val root = Path.of(System.getProperty("kweb.image.integration.root") ?: error("Missing kweb.image.integration.root"))
    Files.createDirectories(root)
    val liveCount = NativeImageFfm.open(library).use { it.liveCount() }
    check(liveCount == 0)
    val evidence = buildJsonObject {
        put("schemaVersion", 1)
        put("target", target)
        put("providerId", provider)
        put("nativeLibrary", library.fileName.toString())
        put("nativeLibrarySha256", sha256(Files.readAllBytes(library)))
        put("decode", buildJsonObject {
            for ((format, image) in listOf("png" to png, "jpeg" to jpeg)) {
                put(format, buildJsonObject {
                    put("width", image.width)
                    put("height", image.height)
                    put("alphaMode", image.alphaMode.name)
                    put("colorSpace", image.colorSpace.name)
                    put("pngSha256", sha256(image.png.bytes))
                    put("pngBytes", image.png.bytes.size)
                })
            }
        })
        put("deterministicPng", deterministic)
        put("resourceDigestChecked", resourceChecked)
        put("rejected", buildJsonObject { rejected.forEach { (name, code) -> put(name, code) } })
        put("liveCountAfterClose", liveCount)
    }
    Files.writeString(root.resolve("native-image-evidence.json"), evidence.toString() + "\n")
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
