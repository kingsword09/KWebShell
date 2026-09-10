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
            KWebElectronMappingStatus.UNSUPPORTED,
            KWebElectronCapabilityMatrix.find("clipboard")?.status,
        )
        assertEquals(
            KWebElectronMappingStatus.REWRITE,
            KWebElectronCapabilityMatrix.find("dialog")?.status,
        )
        assertEquals(
            "KWebDialogs + DialogsBridge",
            KWebElectronCapabilityMatrix.find("dialog")?.kweb,
        )
        assertNotNull(KWebElectronCapabilityMatrix.find("node-runtime"))
        assertTrue(KWebElectronCapabilityMatrix.entries.size >= 12)
    }
}
