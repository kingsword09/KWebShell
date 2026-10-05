package io.github.kingsword09.kwebshell.electron.migration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KWebElectronCapabilityMatrixTest {
    @Test
    fun publishesVersionedStatusesWithoutDuplicateRows() {
        assertEquals(1, KWebElectronCapabilityMatrix.schemaVersion)
        assertEquals(
            KWebElectronMappingStatus.ADAPTER,
            KWebElectronCapabilityMatrix.find("context-bridge")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("clipboard")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("shell")?.status,
        )
        assertEquals("KWebShell + ShellBridge", KWebElectronCapabilityMatrix.find("shell")?.kweb)
        assertEquals(
            KWebElectronMappingStatus.UNSUPPORTED,
            KWebElectronCapabilityMatrix.find("notification")?.status,
        )
        assertEquals("", KWebElectronCapabilityMatrix.find("notification")?.kweb)
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("dialog")?.status,
        )
        assertEquals(
            "KWebDialogs + DialogsBridge",
            KWebElectronCapabilityMatrix.find("dialog")?.kweb,
        )
        assertEquals(
            KWebElectronMappingStatus.DIRECT,
            KWebElectronCapabilityMatrix.find("web-contents-reload")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.ADAPTER,
            KWebElectronCapabilityMatrix.find("web-contents-before-unload")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("window-open-handler")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.DIRECT,
            KWebElectronCapabilityMatrix.find("renderer-process-state")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.DIRECT,
            KWebElectronCapabilityMatrix.find("session-cookies")?.status,
        )
        assertEquals(
            "KWebProfile.flush",
            KWebElectronCapabilityMatrix.find("session-flush")?.kweb,
        )
        assertEquals(
            KWebElectronMappingStatus.DIRECT,
            KWebElectronCapabilityMatrix.find("session-storage-clear")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("session-web-request")?.status,
        )
        listOf("certificate-error", "select-client-certificate").forEach { id ->
            assertEquals(
                KWebElectronMappingStatus.REWRITE,
                KWebElectronCapabilityMatrix.find(id)?.status,
            )
            assertEquals(
                "KWebProfile.securityChallenges + respondToSecurityChallenge",
                KWebElectronCapabilityMatrix.find(id)?.kweb,
            )
        }
        assertEquals(
            KWebElectronMappingStatus.UNSUPPORTED,
            KWebElectronCapabilityMatrix.find("login")?.status,
        )
        assertEquals("", KWebElectronCapabilityMatrix.find("login")?.kweb)
        assertEquals(
            KWebElectronMappingStatus.UNSUPPORTED,
            KWebElectronCapabilityMatrix.find("session-set-proxy")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.UNSUPPORTED,
            KWebElectronCapabilityMatrix.find("session-resolve-proxy")?.status,
        )
        assertEquals("", KWebElectronCapabilityMatrix.find("session-resolve-proxy")?.kweb)
        assertNotNull(KWebElectronCapabilityMatrix.find("node-runtime"))
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("node-fs")?.status,
        )
        assertEquals("KWebFiles + FilesBridge", KWebElectronCapabilityMatrix.find("node-fs")?.kweb)
        assertTrue(KWebElectronCapabilityMatrix.entries.size >= 18)
    }
}
