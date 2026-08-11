package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KWebLifecycleStateTest {
    @Test
    fun keepsLifecycleStatesExplicitAndFinite() {
        assertEquals(
            listOf("OPENING", "OPEN", "CLOSING", "CLOSED", "FAILED"),
            KWebLifecycleState.entries.map { it.name },
        )
    }
}
