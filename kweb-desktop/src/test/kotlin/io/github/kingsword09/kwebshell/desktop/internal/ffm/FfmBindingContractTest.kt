package io.github.kingsword09.kwebshell.desktop.internal.ffm

import java.lang.foreign.Arena
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmBindingContractTest {
    @Test
    fun productionInventoryMatchesAbiVersionSix() {
        assertEquals(6, FfmAbi.VERSION)
        assertEquals(18, FfmAbi.FUNCTIONS.size)
        assertEquals(16, FfmLayouts.STRING_VIEW.byteSize())
        assertEquals(32, FfmLayouts.ENGINE_EVENT.byteSize())
        assertEquals(128, FfmLayouts.ENGINE_CONFIG.byteSize())
        assertEquals(72, FfmLayouts.BROWSER_EVENT.byteSize())
        assertEquals(56, FfmLayouts.BRIDGE_EVENT.byteSize())
        assertEquals(128, FfmLayouts.EXTENSION_RESULT.byteSize())
        assertEquals(80, FfmLayouts.EXTENSION_CONFIG.byteSize())
        assertEquals(128, FfmLayouts.BROWSER_CONFIG.byteSize())
        assertEquals(24, FfmLayouts.offset(FfmLayouts.BROWSER_CONFIG, "native_parent"))
        assertEquals(80, FfmLayouts.offset(FfmLayouts.BROWSER_CONFIG, "callback"))
        assertEquals(112, FfmLayouts.offset(FfmLayouts.BROWSER_CONFIG, "bridge_callback"))
        assertEquals(48, FfmLayouts.offset(FfmLayouts.EXTENSION_RESULT, "extension_id"))
        assertEquals(4, listOf(
            FfmAbi.ENGINE_CALLBACK,
            FfmAbi.BROWSER_CALLBACK,
            FfmAbi.BRIDGE_CALLBACK,
            FfmAbi.EXTENSION_CALLBACK,
        ).size)
    }

    @Test
    fun productionUtf8ConversionCompletesAndRejectsInvalidInput() {
        Arena.ofConfined().use { arena ->
            val text = "KWebShell 第一页\uD83D\uDE42"
            val encoded = FfmMemory.encode(text, arena, FfmMemory.MAXIMUM_TEXT_SIZE)
            assertEquals(text.toByteArray(Charsets.UTF_8).size.toLong(), encoded.size())
            assertEquals(text, FfmMemory.decode(encoded.segment(), encoded.size(), FfmMemory.MAXIMUM_TEXT_SIZE))

            val malformed = assertFailsWith<FfmTextException> {
                FfmMemory.encode("\uD800", arena, FfmMemory.MAXIMUM_TEXT_SIZE)
            }
            assertEquals(FfmStatus.INVALID_TEXT_ENCODING, malformed.status())

            val oversized = assertFailsWith<FfmTextException> {
                FfmMemory.encode("four", arena, 3)
            }
            assertEquals(FfmStatus.TEXT_TOO_LARGE, oversized.status())
        }
    }

    @Test
    fun missingNativeAccessProducesTypedDiagnostic() {
        val output = runNativeAccessProbe("disabled", grantNativeAccess = false)
        assertTrue("KWEBSHELL_NATIVE_ACCESS_TYPED_DIAGNOSTIC_OK" in output, output)
    }

    @Test
    fun namedDesktopModuleAcceptsItsExplicitNativeAccessGrant() {
        val output = runNativeAccessProbe("enabled", grantNativeAccess = true)
        assertTrue("KWEBSHELL_NAMED_NATIVE_ACCESS_OK" in output, output)
    }

    private fun runNativeAccessProbe(mode: String, grantNativeAccess: Boolean): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
        val java = Path.of(System.getProperty("java.home"), "bin", executable)
        val modulePath = requireNotNull(System.getProperty("kweb.desktop.module.path"))
        val childClasspath = requireNotNull(System.getProperty("kweb.desktop.named.classpath"))
        val command = buildList {
            add(java.toString())
            add("--module-path=$modulePath")
            add("--add-modules=io.github.kingsword09.kwebshell.desktop")
            if (grantNativeAccess) {
                add("--enable-native-access=io.github.kingsword09.kwebshell.desktop")
            }
            add("-cp")
            add(childClasspath)
            add("io.github.kingsword09.kwebshell.probe.FfmNativeAccessProbeMainKt")
            add(mode)
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val completed = process.waitFor(30, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(completed, "Native-access diagnostic process timed out: $output")
        assertEquals(0, process.exitValue(), "classpath=$childClasspath\n$output")
        return output
    }
}
