package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebElectronMenuMigrationTest {
    private fun fixture(): KWebElectronManifest = KWebElectronMigrationJson.decode(Files.readString(Path.of(
        "kweb-electron-migration/src/jvmTest/resources/migration-fixture/migration-manifest.json",
    )))

    @Test
    fun declaredMenuPopupGeneratesOnlyItsTypedDeclaredMenuCall() {
        val sources = KWebElectronPreloadGenerator().generate(fixture())
        assertContains(sources.typescript, "MenusPopupRequest { menuId: string; x: number; y: number; }")
        assertContains(sources.typescript, "popup(request: MenusPopupRequest, options?: KWebBridgeCallOptions): Promise<MenusPopupResponse>;")
        assertContains(sources.declarations, "MenusBridge: {")
        assertContains(sources.declarations, "showDeclaredPopup(request: MenusPopupRequest, options?: KWebBridgeCallOptions): Promise<MenusPopupResponse>")
        assertContains(sources.javascript, "menusClient.showDeclaredPopup(request, options)")
        assertContains(sources.javascript, "globalThis.MenusBridge")
        // The renderer facade exposes one declared-menu popup and never a generic channel or a Function/template path.
        listOf(sources.typescript, sources.declarations, sources.javascript).forEach { generated ->
            assertTrue(!generated.contains("ipcRenderer"), generated)
            assertTrue(!generated.contains("ipcMain"), generated)
            assertTrue(!generated.contains("Function("), generated)
        }
    }

    @Test
    fun preloadTypesMustMatchTheirDeclaredMenuChannel() {
        val manifest = fixture()
        val method = manifest.preloadMethods.single { it.name == "popup" }
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
    fun wrongMenuOperationsVersionsTypesAndPoliciesAreBlocked() {
        val manifest = fixture()
        val channel = manifest.channels.single { it.name == "menus.showDeclaredPopup" }
        val policy = requireNotNull(channel.policy)
        for (invalid in listOf(
            channel.copy(operationId = "set-application-menu"),
            channel.copy(operationId = "declare-page-menu"),
            channel.copy(serviceVersion = "2.0.0"),
            channel.copy(requestType = "ArbitraryRequest"),
            channel.copy(responseType = "ArbitraryResponse"),
            channel.copy(policy = policy.copy(rendererGrant = "native.menus.decode")),
            channel.copy(policy = policy.copy(requiresUserGesture = false)),
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
