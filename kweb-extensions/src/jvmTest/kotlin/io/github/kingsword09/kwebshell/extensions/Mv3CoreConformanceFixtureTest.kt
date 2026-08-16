package io.github.kingsword09.kwebshell.extensions

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class Mv3CoreConformanceFixtureTest {
    @Test
    fun verifiesTheNativeRuntimeFixtureThroughThePackageBoundary() {
        val fixturePath = Path.of(
            requireNotNull(System.getProperty("kweb.mv3.core.fixture")) {
                "The MV3 core fixture path must be configured by Gradle."
            },
        )

        val verified = JvmKWebExtensionPackageVerifier.verifyUnpacked(fixturePath)

        assertEquals(KWebExtensionPackageFormat.UNPACKED, verified.packageInfo.format)
        assertEquals(EXPECTED_EXTENSION_ID, verified.packageInfo.extensionId)
        assertEquals("worker.js", verified.packageInfo.manifest.background?.serviceWorker)
        assertEquals(listOf("storage"), verified.packageInfo.manifest.permissions)
        assertEquals("content.js", verified.packageInfo.manifest.contentScripts.single().js.single())
        assertEquals(
            KWebExtensionOptionsUi(page = "options.html", openInTab = true),
            verified.packageInfo.manifest.optionsUi,
        )
        assertEquals(
            KWebExtensionPermissionDecision("storage", KWebExtensionPermissionKind.API_PERMISSION),
            verified.packageInfo.permissionReview.required.single(),
        )
        assertEquals(
            KWebExtensionPermissionDecision("https://kwebshell.test/*", KWebExtensionPermissionKind.HOST_ACCESS),
            verified.packageInfo.permissionReview.contentScriptHosts.single(),
        )
    }

    private companion object {
        const val EXPECTED_EXTENSION_ID: String = "dhhnhmffjehhodphofnkingncijnaona"
    }
}
