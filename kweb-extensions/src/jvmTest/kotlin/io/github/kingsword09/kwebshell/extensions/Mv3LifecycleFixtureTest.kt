package io.github.kingsword09.kwebshell.extensions

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class Mv3LifecycleFixtureTest {
    @Test
    fun verifiesBothImmutableLifecyclePackageVersionsWithOneIdentity() {
        val root = Path.of(
            requireNotNull(System.getProperty("kweb.mv3.lifecycle.fixture")) {
                "The MV3 lifecycle fixture path must be configured by Gradle."
            },
        )
        val v1 = JvmKWebExtensionPackageVerifier.verifyUnpacked(root.resolve("v1"))
        val v2 = JvmKWebExtensionPackageVerifier.verifyUnpacked(root.resolve("v2"))

        assertEquals("dhhnhmffjehhodphofnkingncijnaona", v1.packageInfo.extensionId)
        assertEquals(v1.packageInfo.extensionId, v2.packageInfo.extensionId)
        assertEquals("1.0.0", v1.packageInfo.manifest.version)
        assertEquals("2.0.0", v2.packageInfo.manifest.version)
        assertEquals(
            "http://127.0.0.1/*",
            v1.packageInfo.permissionReview.contentScriptHosts.single().permission,
        )
        assertNotEquals(v1.packageInfo.manifest.version, v2.packageInfo.manifest.version)
        assertEquals("content.js", v1.packageInfo.manifest.contentScripts.single().js.single())
        assertEquals("worker.js", v2.packageInfo.manifest.background?.serviceWorker)
        assertEquals(v1.packageInfo.extensionId, v1.packageInfo.manifest.key?.let { key ->
            KWebExtensionId.fromSha256Hash(
                java.security.MessageDigest.getInstance("SHA-256").digest(
                    java.util.Base64.getDecoder().decode(key),
                ),
            )
        })
    }
}
