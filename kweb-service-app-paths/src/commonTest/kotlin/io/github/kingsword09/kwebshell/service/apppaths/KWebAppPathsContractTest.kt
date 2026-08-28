package io.github.kingsword09.kwebshell.service.apppaths

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KWebAppPathsContractTest {
    @Test
    fun publishesOnlyTheClosedPathKindSet() {
        assertEquals(
            listOf(
                "home", "app-data", "app-cache", "user-data", "session-data", "temp",
                "desktop", "documents", "downloads", "music", "pictures", "videos",
            ),
            KWebAppPathKind.entries.map(KWebAppPathKind::id),
        )
        assertEquals(KWebAppPathKind.DOWNLOADS, KWebAppPathKind.fromId("downloads"))
        assertFailsWith<KWebConfigurationException> { KWebAppPathKind.fromId("logs") }
        assertEquals("appData", KWebAppPathKind.APP_DATA.electronName)
        assertEquals(null, KWebAppPathKind.APP_CACHE.electronName)
    }

    @Test
    fun configurationRejectsUnsafeOrEmptyValues() {
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("bad id", "/tmp/data", "/tmp/session")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("io..example", "/tmp/data", "/tmp/session")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("io.example-", "/tmp/data", "/tmp/session")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("io.example.app", "", "/tmp/session")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("io.example." + "a".repeat(124), "/tmp/data", "/tmp/session")
        }
        assertFailsWith<KWebConfigurationException> {
            KWebAppPathsConfiguration("io.example.app", "/tmp/data", "/tmp/with\u0000nul")
        }
    }

    @Test
    fun descriptorDeclaresOneRendererOperation() {
        assertEquals("app-paths", KWebAppPaths.DESCRIPTOR.id)
        assertEquals("1.0.0", KWebAppPaths.DESCRIPTOR.version.toString())
        assertEquals(setOf("resolve"), KWebAppPaths.DESCRIPTOR.operations.map { it.id }.toSet())
        assertEquals("native.app-paths.resolve", KWebAppPaths.DESCRIPTOR.operations.single().rendererPermission)
    }
}
