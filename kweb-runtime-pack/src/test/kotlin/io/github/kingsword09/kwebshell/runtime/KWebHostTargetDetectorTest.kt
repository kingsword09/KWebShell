package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebHostTargetDetectorTest {
    @Test
    fun detectsEverySupportedHostAlias() {
        val cases = mapOf(
            ("Mac OS X" to "aarch64") to "macos-arm64",
            ("Darwin" to "x86_64") to "macos-x64",
            ("Linux" to "amd64") to "linux-x64",
            ("Linux" to "arm64") to "linux-arm64",
            ("Windows 11" to "amd64") to "windows-x64",
            ("Windows Server 2025" to "aarch64") to "windows-arm64",
        )

        cases.forEach { (host, expected) ->
            assertEquals(expected, KWebHostTargetDetector.detect(host.first, host.second).id)
        }
    }

    @Test
    fun rejectsUnknownOperatingSystem() {
        val error = assertFailsWith<KWebConfigurationException> {
            KWebHostTargetDetector.detect("FreeBSD", "amd64")
        }
        assertEquals("target.unsupported-host", error.code)
    }

    @Test
    fun rejectsUnknownArchitecture() {
        val error = assertFailsWith<KWebConfigurationException> {
            KWebHostTargetDetector.detect("Linux", "riscv64")
        }
        assertEquals("target.unsupported-host", error.code)
    }
}
