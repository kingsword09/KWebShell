package io.github.kingsword09.kwebshell.service.image

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class KWebImageContractTest {
    @Test
    fun encodedBytesAreCopiedAndOnlyPublishedFormatsAreAccepted() {
        val bytes = byteArrayOf(1, 2, 3)
        val encoded = KWebImageEncoded(KWebImageFormat.PNG, bytes)
        bytes[0] = 9
        assertEquals(1, encoded.bytes[0])
        val returned = encoded.bytes
        returned[1] = 8
        assertEquals(2, encoded.bytes[1])
        assertFailsWith<KWebConfigurationException> {
            KWebImageEncoded(KWebImageFormat.PNG, ByteArray(KWEB_IMAGE_MAX_ENCODED_BYTES + 1))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebImageFormat.fromMimeType("image/webp")
        }
    }

    @Test
    fun resourceIdentifiersAndDigestsArePathSafe() {
        assertFailsWith<KWebConfigurationException> { KWebImageResourceId("../icon.png") }
        assertFailsWith<KWebConfigurationException> { KWebImageResourceId("/icon.png") }
        assertFailsWith<KWebConfigurationException> { KWebImageResourceId("icons\\icon.png") }
        assertFailsWith<KWebConfigurationException> { KWebImageResourceId("icons/bad:name.png") }
        assertFailsWith<KWebConfigurationException> { KWebImageSource.PackageResource(KWebImageResourceId("icon.png"), "bad") }
    }

    @Test
    fun variantsRequireMatchingLogicalDimensionsAndUniqueScales() {
        val image = KWebImage(
            width = 1,
            height = 1,
            alphaMode = KWebImageAlphaMode.OPAQUE,
            colorSpace = KWebImageColorSpace.SRGB,
            intent = KWebImageIntent.NORMAL,
            png = KWebImageEncoded(KWebImageFormat.PNG, byteArrayOf(1)),
        )
        KWebImageVariants(1, 1, listOf(KWebImageVariant(1, image)))
        assertFailsWith<KWebConfigurationException> {
            KWebImageVariants(1, 1, listOf(KWebImageVariant(2, image)))
        }
        assertFailsWith<KWebConfigurationException> {
            KWebImageVariants(1, 1, listOf(KWebImageVariant(1, image), KWebImageVariant(1, image)))
        }
    }

    @Test
    fun resourceStoreRejectsDigestMismatchBeforeUse() {
        val bytes = byteArrayOf(1, 2, 3)
        val encoded = KWebImageEncoded(KWebImageFormat.PNG, bytes)
        val digest = kWebImageSha256(bytes)
        val store = KWebImageResourceStore(listOf(KWebImageResource(KWebImageResourceId("icons/icon.png"), digest, encoded)))
        assertEquals(encoded, store.resolve(KWebImageSource.PackageResource(KWebImageResourceId("icons/icon.png"), digest)))
        assertNotEquals(digest, "0".repeat(64))
        assertFailsWith<KWebConfigurationException> {
            store.resolve(KWebImageSource.PackageResource(KWebImageResourceId("icons/icon.png"), "0".repeat(64)))
        }
    }

    @Test
    fun descriptorPublishesApplicationOperationsAndTargets() {
        assertEquals("native-image", KWebNativeImage.DESCRIPTOR.id)
        assertEquals("APPLICATION", KWebNativeImage.DESCRIPTOR.scope.name)
        assertEquals(setOf("decode", "encode-png", "create-native-handle"), KWebNativeImage.DESCRIPTOR.operations.map { it.id }.toSet())
        assertEquals(3, KWebNativeImage.DESCRIPTOR.supportedTargets.size)
    }
}
