package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeStatusContractTest {
    @Test
    fun nativeStatusValuesAreUniqueAndRoundTrip() {
        val statuses = NativeStatus.entries
        assertEquals(statuses.size, statuses.map { it.value }.toSet().size)
        statuses.forEach { status ->
            assertEquals(status, NativeStatus.fromValue(status.value))
            assertTrue(status.id.isNotBlank())
        }
        assertEquals(null, NativeStatus.fromValue(-1))
        assertEquals(null, NativeStatus.fromValue(Int.MAX_VALUE))
    }

    @Test
    fun resolvesOnlyTheThreeAdvertisedDesktopEngines() {
        assertEquals("kwebshell_engine.dll", nativeEngineLibraryFileName("Windows 11"))
        assertEquals("libkwebshell_engine.dylib", nativeEngineLibraryFileName("Mac OS X"))
        assertEquals("libkwebshell_engine.so", nativeEngineLibraryFileName("Linux"))
        val error = assertFailsWith<KWebConfigurationException> {
            nativeEngineLibraryFileName("FreeBSD")
        }
        assertEquals("native.platform.unsupported", error.code)
    }
}
