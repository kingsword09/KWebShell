package io.github.kingsword09.kwebshell.launcher

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@EnabledOnOs(OS.WINDOWS)
class WindowsStateDirectoryTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun resolvesUnicodeDirectoryAndReleasesEveryNativeHandle() {
        val selected = temporary.resolve("目录 with spaces")
        val state = KWebApplicationLayout.prepareStateRoot(selected.toString())
        assertEquals(selected.resolve("KWebShell").toRealPath(), state)
        repeat(32) { assertEquals(state, WindowsStateDirectory.resolve(state)) }
        Files.delete(state)
        Files.delete(selected)
        assertTrue(Files.notExists(state))
    }

    @Test
    fun nativeResolutionRejectsMissingDirectoryWithoutCreatingIt() {
        val missing = temporary.resolve("missing")
        val error = assertFailsWith<IOException> { WindowsStateDirectory.resolve(missing) }
        assertTrue(error.message!!.contains("CreateFileW(directory) failed with Win32 error"))
        assertTrue(Files.notExists(missing))
    }

    @Test
    fun blockedSelectedDirectoryRetainsTypedFailureAndOriginalFile() {
        val selected = Files.createDirectories(temporary.resolve("blocked"))
        val state = selected.resolve("KWebShell")
        Files.writeString(state, "existing user data")
        val error = assertFailsWith<KWebConfigurationException> {
            KWebApplicationLayout.prepareStateRoot(selected.toString())
        }
        assertEquals("launcher.state-root-unavailable", error.code)
        assertNotNull(error.cause)
        assertEquals("existing user data", Files.readString(state))
    }
}
