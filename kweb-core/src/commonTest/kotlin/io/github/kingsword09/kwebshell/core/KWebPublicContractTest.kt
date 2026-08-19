package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebPublicContractTest {
    @Test
    fun boundsRejectInvalidDimensionsWithTypedError() {
        val error = assertFailsWith<KWebConfigurationException> {
            KWebBounds(0, 480)
        }

        assertEquals("page.bounds.invalid", error.code)
        assertEquals("0", error.details["width"])
    }

    @Test
    fun capabilitiesHaveStableIdentifiers() {
        assertEquals(
            listOf("native-child", "persistent-profile", "navigation", "resize", "devtools", "cdp"),
            KWebCapability.entries.map { it.id },
        )
    }

    @Test
    fun pageEventFlagsAreImmutableValueData() {
        val event = KWebPageEvent(
            type = KWebPageEventType.RESIZED,
            sequence = 4,
            text = "",
            statusCode = 0,
            bounds = KWebBounds(800, 600),
            flags = setOf(KWebPageEventFlag.LOADING),
        )

        assertEquals(800, event.bounds?.width)
        assertEquals(setOf(KWebPageEventFlag.LOADING), event.flags)
    }
}
