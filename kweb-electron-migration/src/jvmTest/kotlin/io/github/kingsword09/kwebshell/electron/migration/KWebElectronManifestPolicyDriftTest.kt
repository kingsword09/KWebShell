package io.github.kingsword09.kwebshell.electron.migration

import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import io.github.kingsword09.kwebshell.service.clipboard.KWebClipboard
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

    @Test
    fun clipboardManifestOperationsMatchThePublishedDescriptor() {
        val operations = KWebClipboard.DESCRIPTOR.operations.associateBy { it.id }
        assertEquals("native.clipboard.read", operations.getValue("read").rendererPermission)
        assertEquals(true, operations.getValue("read").requiresUserGesture)
        assertEquals(true, operations.getValue("write").requiresUserGesture)
        assertEquals(true, operations.getValue("clear").requiresUserGesture)
        assertEquals(false, operations.getValue("read-payload").requiresUserGesture)
        assertEquals(false, operations.getValue("close-payload").requiresUserGesture)
    }
}
