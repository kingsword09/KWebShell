package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebTargetTest {
    @Test
    fun exposesEverySupportedDesktopTarget() {
        assertEquals(
            setOf(
                "windows-x64",
                "windows-arm64",
                "macos-x64",
                "macos-arm64",
                "linux-x64",
                "linux-arm64",
            ),
            KWebTarget.supported.mapTo(mutableSetOf()) { it.id },
        )
    }

    @Test
    fun parsesEveryAdvertisedTargetExactly() {
        KWebTarget.supported.forEach { target ->
            assertEquals(target, KWebTarget.parse(target.id))
        }
    }

    @Test
    fun doesNotExposeMutableGlobalTargetState() {
        val exposed = KWebTarget.supported as MutableSet<KWebTarget>
        exposed.clear()

        assertEquals(6, KWebTarget.supported.size)
    }

    @Test
    fun rejectsUnknownTargetsWithTypedConfigurationError() {
        val error = assertFailsWith<KWebConfigurationException> {
            KWebTarget.parse("macos-riscv64")
        }

        assertEquals("target.invalid", error.code)
        assertEquals("macos-riscv64", error.details["target"])
    }

    @Test
    fun rejectsCaseAndWhitespaceInsteadOfNormalizingSilently() {
        listOf("MACOS-ARM64", " macos-arm64", "macos-arm64 ").forEach { value ->
            assertFailsWith<KWebConfigurationException> {
                KWebTarget.parse(value)
            }
        }
    }
}
