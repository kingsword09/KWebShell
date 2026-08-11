package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals

class KWebExceptionTest {
    @Test
    fun snapshotsErrorDetailsAtConstruction() {
        val mutableDetails = mutableMapOf("field" to "initial")
        val error = KWebConfigurationException("config.invalid", mutableDetails, "Invalid configuration")

        mutableDetails["field"] = "changed"

        assertEquals("initial", error.details["field"])
    }
}
