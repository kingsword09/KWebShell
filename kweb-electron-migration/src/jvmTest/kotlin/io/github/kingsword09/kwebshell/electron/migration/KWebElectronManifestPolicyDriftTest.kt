package io.github.kingsword09.kwebshell.electron.migration

import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The manifest validator pins the published app-paths policy constants. This
 * test fails when the live service descriptor drifts from those constants so
 * the two contracts can never disagree silently.
 */
class KWebElectronManifestPolicyDriftTest {
    @Test
    fun appPathsPolicyMatchesThePublishedDescriptor() {
        val resolve = KWebAppPaths.DESCRIPTOR.operations.single { it.id == "resolve" }
        assertEquals("native.app-paths.resolve", resolve.rendererPermission)
        assertEquals(false, resolve.requiresUserGesture)
        assertEquals(false, resolve.requiresOsConsent)
        assertTrue(KWebAppPaths.DESCRIPTOR.supportedTargets.isNotEmpty())
    }
}
