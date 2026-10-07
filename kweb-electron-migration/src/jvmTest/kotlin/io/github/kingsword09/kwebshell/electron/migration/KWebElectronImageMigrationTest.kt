package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebElectronImageMigrationTest {
    private fun fixture(): KWebElectronManifest = KWebElectronMigrationJson.decode(Files.readString(Path.of(
        "kweb-electron-migration/src/jvmTest/resources/migration-fixture/migration-manifest.json",
    )))

    @Test
    fun declaredImageOperationsGenerateOnlyTheirTypedCalls() {
        val sources = KWebElectronPreloadGenerator().generate(fixture())
        assertContains(sources.javascript, "imageClient.decode(request, options)")
        assertContains(sources.javascript, "imageClient.encodePng(request, options)")
        assertContains(sources.typescript, "ImageDecodeRequest")
        assertContains(sources.declarations, "ImageEncodedResponse { format: string; pngBase64: string;")
    }

    @Test
    fun preloadTypesMustMatchTheirDeclaredImageChannel() {
        val manifest = fixture()
        val method = manifest.preloadMethods.single { it.name == "decodeImage" }
        for (invalid in listOf(method.copy(parameterType = "OtherRequest"), method.copy(returnType = "Promise<OtherResponse>"))) {
            val error = assertFailsWith<KWebElectronMigrationException> {
                KWebElectronPreloadGenerator().generate(manifest.copy(
                    preloadMethods = manifest.preloadMethods.map { if (it.name == method.name) invalid else it },
                ))
            }
            assertEquals(KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED, error.code)
        }
    }

    @Test
    fun wrongImageTypesVersionsPoliciesAndHostOperationsAreBlocked() {
        val manifest = fixture()
        val channel = manifest.channels.single { it.name == "nativeImage.decode" }
        val policy = requireNotNull(channel.policy)
        for (invalid in listOf(
            channel.copy(operationId = "to-jpeg"),
            channel.copy(operationId = "create-native-handle"),
            channel.copy(serviceVersion = "2.0.0"),
            channel.copy(requestType = "ArbitraryRequest"),
            channel.copy(responseType = "ArbitraryResponse"),
            channel.copy(policy = policy.copy(rendererGrant = "native.native-image.encode-png")),
            channel.copy(policy = policy.copy(requiresUserGesture = true)),
            channel.copy(policy = policy.copy(requiresOsConsent = true)),
        )) {
            val error = assertFailsWith<KWebElectronMigrationException> {
                KWebElectronManifestValidator.validate(manifest.copy(
                    channels = manifest.channels.map { if (it.name == channel.name) invalid else it },
                ))
            }
            val expected = if (invalid.serviceVersion != channel.serviceVersion) {
                KWebElectronMigrationErrorCode.SCHEMA_INCOMPATIBLE
            } else {
                KWebElectronMigrationErrorCode.MAPPING_UNRESOLVED
            }
            assertEquals(expected, error.code)
        }
    }
}
