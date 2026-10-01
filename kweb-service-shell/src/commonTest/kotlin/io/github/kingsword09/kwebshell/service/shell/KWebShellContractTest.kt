package io.github.kingsword09.kwebshell.service.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebShellContractTest {
    @Test
    fun publishesFourGestureBoundOperations() {
        assertEquals("shell", KWebShell.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebShell.DESCRIPTOR.version.toString())
        assertEquals(
            setOf("open-external", "open-resource", "reveal-resource", "trash-resource"),
            KWebShell.DESCRIPTOR.operations.map { it.id }.toSet(),
        )
        assert(KWebShell.DESCRIPTOR.operations.all { it.requiresUserGesture })
    }

    @Test
    fun rejectsDangerousDefaultSchemesAndOversizedUris() {
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebShellConfiguration(setOf("file"))
        }
        assertFailsWith<io.github.kingsword09.kwebshell.core.KWebConfigurationException> {
            KWebShellExternalUriRequest("x".repeat(KWEB_SHELL_MAX_URI_BYTES + 1))
        }
    }

    @Test
    fun customSchemesRequireExplicitConfiguration() {
        val configuration = KWebShellConfiguration(setOf("https", "custom-app"))
        assertEquals(setOf("https", "custom-app"), configuration.allowedExternalSchemes)
    }
}
