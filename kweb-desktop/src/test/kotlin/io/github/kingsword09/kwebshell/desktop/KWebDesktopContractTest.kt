package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebPageEventFlag
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebDesktopContractTest {
    @Test
    fun profileResolverCreatesOneCanonicalDirectChild() {
        temporaryDirectory().use { root ->
            val first = KWebProfilePathResolver.resolve(root, "alpha")
            val second = KWebProfilePathResolver.resolve(root, "alpha")

            assertEquals(first, second)
            assertEquals(root.toRealPath(), first.parent)
            assertTrue(Files.isDirectory(first))
        }
    }

    @Test
    fun profileResolverRejectsTraversalDefaultAndPathSeparators() {
        temporaryDirectory().use { root ->
            listOf("", ".", "..", "Default", "../escape", "nested/name", "bad:name").forEach { name ->
                val error = assertFailsWith<KWebConfigurationException> {
                    KWebProfilePathResolver.resolve(root, name)
                }
                assertEquals("profile.name.invalid", error.code, name)
            }
        }
    }

    @Test
    fun nativeEventsMapToThePublicOrderedEventContract() = runBlocking {
        val stream = KWebPageEventStream()
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.RESIZED,
                engine = 1,
                browser = 2,
                sequence = 4,
                flags = 1 or 8,
                text = "",
                statusCode = 0,
                width = 800,
                height = 600,
            ),
        )

        val event = stream.events.first()
        assertEquals(KWebPageEventType.RESIZED, event.type)
        assertEquals(4, event.sequence)
        assertEquals(setOf(KWebPageEventFlag.LOADING, KWebPageEventFlag.USER_GESTURE), event.flags)
        assertEquals(800, event.bounds?.width)
    }

    private fun temporaryDirectory(): Path = Files.createTempDirectory("kweb-desktop-contract")

    private fun Path.use(block: (Path) -> Unit) {
        try {
            block(this)
        } finally {
            Files.walk(this).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
