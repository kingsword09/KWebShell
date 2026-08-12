package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.Locale
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeEngineConfigurationTest {
    @Test
    fun validatesOneExplicitPinnedRuntimeLayout() {
        RuntimeLayoutFixture().use { fixture ->
            val validated = fixture.configuration.validated()

            assertEquals(fixture.cache.toRealPath(), validated.rootCache)
            assertTrue(Files.isRegularFile(fixture.log))
            assertEquals(fixture.runtime, validated.cefRuntime)
        }
    }

    @Test
    fun rejectsRelativeMissingAndMismatchedPaths() {
        RuntimeLayoutFixture().use { fixture ->
            val relativeError = assertFailsWith<KWebConfigurationException> {
                fixture.configuration.copy(rootCache = Path.of("relative-cache")).validated()
            }
            assertEquals("native.engine.path-not-absolute", relativeError.code)

            Files.delete(fixture.runtime)
            val missingError = assertFailsWith<KWebConfigurationException> {
                fixture.configuration.validated()
            }
            assertEquals("native.engine.path-not-found", missingError.code)
        }

        RuntimeLayoutFixture().use { fixture ->
            val other = fixture.root.resolve("other").createDirectories()
            val mismatchError = assertFailsWith<KWebConfigurationException> {
                fixture.configuration.copy(log = other.resolve("cef.log")).validated()
            }
            assertEquals("native.engine.path-mismatch", mismatchError.code)
        }

        RuntimeLayoutFixture().use { fixture ->
            val unsupportedError = assertFailsWith<KWebConfigurationException> {
                fixture.configuration.validated("FreeBSD")
            }
            assertEquals("native.platform.unsupported", unsupportedError.code)
        }
    }

    private class RuntimeLayoutFixture : AutoCloseable {
        val root: Path = Files.createTempDirectory("kwebshell-engine-config")
        val runtime: Path
        private val subprocess: Path
        private val resources: Path
        private val locales: Path
        val cache: Path = root.resolve("cache").createDirectories()
        val log: Path = cache.resolve("cef.log")
        val configuration: NativeEngineConfiguration

        init {
            val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
            when {
                operatingSystem.startsWith("mac") -> {
                    val frameworks = root.resolve("KWebShell.app/Contents/Frameworks")
                    val framework = frameworks.resolve("Chromium Embedded Framework.framework")
                    runtime = writeExecutable(framework.resolve("Chromium Embedded Framework"))
                    resources = framework.resolve("Resources").createDirectories()
                    locales = resources
                    subprocess = writeExecutable(
                        frameworks.resolve("KWebShell Helper.app/Contents/MacOS/KWebShell Helper"),
                    )
                    writeFile(resources.resolve("en.lproj/locale.pak"))
                }
                operatingSystem.startsWith("windows") -> {
                    resources = root.resolve("runtime").createDirectories()
                    runtime = writeFile(resources.resolve("libcef.dll"))
                    locales = resources.resolve("locales").createDirectories()
                    subprocess = writeFile(resources.resolve("KWebShell.exe"))
                    writeFile(locales.resolve("en-US.pak"))
                }
                else -> {
                    resources = root.resolve("runtime").createDirectories()
                    runtime = writeExecutable(resources.resolve("libcef.so"))
                    locales = resources.resolve("locales").createDirectories()
                    subprocess = writeExecutable(resources.resolve("KWebShell"))
                    writeFile(locales.resolve("en-US.pak"))
                }
            }
            writeFile(resources.resolve("resources.pak"))
            writeFile(resources.resolve("icudtl.dat"))
            configuration = NativeEngineConfiguration(
                cefRuntime = runtime,
                browserSubprocess = subprocess,
                resources = resources,
                locales = locales,
                rootCache = cache,
                log = log,
            )
        }

        override fun close() {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }

        private fun writeFile(path: Path): Path {
            path.parent.createDirectories()
            path.createFile()
            return path
        }

        private fun writeExecutable(path: Path): Path = writeFile(path).also { file ->
            assertTrue(file.toFile().setExecutable(true, false))
        }
    }
}
