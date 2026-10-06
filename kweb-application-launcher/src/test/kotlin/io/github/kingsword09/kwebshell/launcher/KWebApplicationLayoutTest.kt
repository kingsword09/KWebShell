package io.github.kingsword09.kwebshell.launcher

import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebApplicationLayoutTest {
    @Test
    fun onlyWindowsX64HasAPackagedLauncher() {
        for (architecture in listOf("amd64", "x86_64")) {
            assertEquals("windows-x64", KWebApplicationLayout.target("Windows 11", architecture).id)
        }
        for ((os, architecture) in listOf("Windows 11" to "aarch64", "Mac OS X" to "aarch64", "Linux" to "amd64")) {
            val failure = assertFailsWith<KWebConfigurationException> { KWebApplicationLayout.target(os, architecture) }
            assertEquals("launcher.target-unsupported", failure.code)
        }
    }

    @Test
    fun missingOrRelativeLocalAppDataCannotSelectAnotherProfileRoot() {
        for (value in listOf(null, "", " ", "relative/profile", "invalid\u0000path")) {
            val failure = assertFailsWith<KWebConfigurationException> { KWebApplicationLayout.stateRoot(value) }
            assertEquals("launcher.state-root-invalid", failure.code)
        }
        val absolute = Path.of("local-app-data").toAbsolutePath()
        assertEquals(absolute.resolve("KWebShell"), KWebApplicationLayout.stateRoot(absolute.toString()))
    }

    @Test
    fun windowsLayoutIsExplicit() {
        val root = Path.of("/opt/kwebshell/KWebShell").toAbsolutePath()
        val layout = KWebApplicationLayout.fromPackageRoot(root, KWebTarget.parse("windows-x64"))
        assertEquals(root.resolve("app"), layout.applicationDirectory)
        assertEquals(root.resolve("runtime"), layout.jreDirectory)
        assertEquals(root.resolve("native"), layout.nativeDirectory)
        assertEquals(root.resolve("cef"), layout.cefDirectory)
        assertEquals(root.resolve("KWebShell.exe"), layout.launcherExecutable)
        assertEquals("KWebShell.exe", layout.relaunchExecutable)
    }

    @Test
    fun jarInsideAppDirectoryResolvesPackageRoot() {
        val root = Path.of("/opt/kwebshell/KWebShell").toAbsolutePath()
        val layout = KWebApplicationLayout.discover(
            root.resolve("app/kweb-application-launcher.jar").toUri(),
            KWebTarget.parse("windows-x64"),
        )
        assertEquals(root, layout.packageRoot)
    }

    @Test
    fun relativeExplicitRootFails() {
        assertFailsWith<IllegalArgumentException> {
            KWebApplicationLayout.discover(
                Path.of("/opt/kwebshell/app/launcher.jar").toUri(),
                KWebTarget.parse("windows-x64"),
                "relative-root",
            )
        }
    }

    @Test
    fun macosJpackageLauncherUsesContentsMacOsLocation() {
        val root = Path.of("/Applications/KWebShell.app/Contents").toAbsolutePath()
        val layout = KWebApplicationLayout.fromPackageRoot(root, KWebTarget.parse("macos-arm64"))
        assertEquals(root.resolve("MacOS/KWebShell"), layout.launcherExecutable)
    }
}
