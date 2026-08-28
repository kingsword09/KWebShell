package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.service.apppaths.internal.AppPathsFfm
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPathsFfmLayoutTest {
    @Test
    fun mirrorsTheVersioned64BitAbiLayout() {
        assertEquals(16, AppPathsFfm.stringViewLayoutSize())
        assertEquals(64, AppPathsFfm.requestLayoutSize())
        assertEquals(48, AppPathsFfm.resultLayoutSize())
        assertEquals(0, AppPathsFfm.requestFieldOffset("struct_size"))
        assertEquals(4, AppPathsFfm.requestFieldOffset("abi_version"))
        assertEquals(8, AppPathsFfm.requestFieldOffset("kind"))
        assertEquals(12, AppPathsFfm.requestFieldOffset("reserved"))
        assertEquals(16, AppPathsFfm.requestFieldOffset("application_id"))
        assertEquals(32, AppPathsFfm.requestFieldOffset("application_data_root"))
        assertEquals(48, AppPathsFfm.requestFieldOffset("session_data_root"))
        assertEquals(0, AppPathsFfm.resultFieldOffset("struct_size"))
        assertEquals(4, AppPathsFfm.resultFieldOffset("abi_version"))
        assertEquals(8, AppPathsFfm.resultFieldOffset("kind"))
        assertEquals(12, AppPathsFfm.resultFieldOffset("reserved"))
        assertEquals(16, AppPathsFfm.resultFieldOffset("path"))
        assertEquals(32, AppPathsFfm.resultFieldOffset("source"))
    }
}
