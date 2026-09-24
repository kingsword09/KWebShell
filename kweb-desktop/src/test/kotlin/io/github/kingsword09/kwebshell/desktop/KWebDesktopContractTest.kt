package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebPageEventFlag
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebPageFrameScope
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
import kotlin.test.assertNotNull
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
        val stream = KWebPageEventStream(pageId = "page-1", profileId = "profile-1")
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
        assertEquals(1, event.sequence)
        assertEquals(setOf(KWebPageEventFlag.LOADING, KWebPageEventFlag.USER_GESTURE), event.flags)
        assertEquals(800, event.bounds?.width)
        assertEquals("page-1", event.pageId)
        assertEquals("profile-1", event.profileId)
    }

    @Test
    fun internalGestureEventsDoNotCreateGapsInPublicSequence() = runBlocking {
        val stream = KWebPageEventStream(pageId = "page-1", profileId = "profile-1")
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.INPUT_GESTURE,
                engine = 1,
                browser = 2,
                sequence = 1,
                flags = 0,
                text = "",
                statusCode = 0,
                width = 0,
                height = 0,
            ),
        )
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.RESIZED,
                engine = 1,
                browser = 2,
                sequence = 2,
                flags = 0,
                text = "",
                statusCode = 0,
                width = 320,
                height = 240,
            ),
        )

        assertEquals(1, stream.events.first().sequence)
    }

    @Test
    fun popupAndBeforeUnloadEventsCarryTypedPageBoundRequests() = runBlocking {
        val stream = KWebPageEventStream(pageId = "page-1", profileId = "profile-1")
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.POPUP_REQUESTED,
                engine = 1,
                browser = 2,
                sequence = 1,
                flags = 8,
                text = "https://example.com/child",
                statusCode = 0,
                width = 0,
                height = 0,
                requestId = 41,
                frameId = "17",
                frameScope = 1,
                origin = "https://example.com",
                url = "https://example.com/child",
                title = "popup-frame",
                details = "12,24,640,480,1",
            ),
        )

        val popup = stream.events.first()
        val request = assertNotNull(popup.popupRequest)
        assertEquals(KWebPageEventType.POPUP_REQUESTED, popup.type)
        assertEquals(KWebPageFrameScope.MAIN, popup.frameScope)
        assertEquals("https://example.com/child", request.targetUrl)
        assertEquals("popup-frame", request.frameName)
        assertTrue(request.userGesture)
        assertEquals(640, request.features.width)
        assertEquals(true, request.features.isPopup)

        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.BEFORE_UNLOAD_REQUESTED,
                engine = 1,
                browser = 2,
                sequence = 2,
                flags = 0,
                text = "Leave this page?",
                statusCode = 0,
                width = 0,
                height = 0,
                requestId = 42,
                frameId = "17",
                frameScope = 1,
                origin = "https://example.com",
                url = "https://example.com/child",
            ),
        )
        val beforeUnload = stream.events.first { it.type == KWebPageEventType.BEFORE_UNLOAD_REQUESTED }
        assertEquals(42, beforeUnload.beforeUnloadRequest?.requestId)
        assertEquals("Leave this page?", beforeUnload.beforeUnloadRequest?.message)
        assertTrue(stream.takeBeforeUnload(42) > System.nanoTime())
    }

    @Test
    fun pageBoundRequestsBecomeStaleAfterConsumptionOrClose() = runBlocking {
        val stream = KWebPageEventStream(pageId = "page-1", profileId = "profile-1")
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.POPUP_REQUESTED,
                engine = 1,
                browser = 2,
                sequence = 1,
                flags = 0,
                text = "https://example.com/child",
                statusCode = 0,
                width = 0,
                height = 0,
                requestId = 9,
                frameId = "1",
                origin = "https://example.com",
                url = "https://example.com/child",
                title = "",
                details = "-1,-1,-1,-1,0",
            ),
        )
        stream.takePopup(9)
        val stale = assertFailsWith<KWebNativeException> { stream.requirePopup(9) }
        assertEquals("page.popup-stale", stale.code)

        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.BEFORE_UNLOAD_REQUESTED,
                engine = 1,
                browser = 2,
                sequence = 2,
                flags = 0,
                text = "Leave?",
                statusCode = 0,
                width = 0,
                height = 0,
                requestId = 10,
                frameId = "1",
                origin = "https://example.com",
                url = "https://example.com/child",
            ),
        )
        stream.onPageClosed()
        val closed = assertFailsWith<KWebNativeException> { stream.takeBeforeUnload(10) }
        assertEquals("page.before-unload-stale", closed.code)
    }

    @Test
    fun titleEventsReplaceNulAndRemainBoundedAtThePublicBoundary() = runBlocking {
        val stream = KWebPageEventStream(pageId = "page-1", profileId = "profile-1")
        stream.accept(
            NativeBrowserEvent(
                type = NativeBrowserEventType.TITLE_CHANGED,
                engine = 1,
                browser = 2,
                sequence = 1,
                flags = 0,
                text = "ignored",
                statusCode = 0,
                width = 0,
                height = 0,
                frameId = "1",
                origin = "https://example.com",
                url = "https://example.com/",
                title = "a\u0000" + "b".repeat(4096),
            ),
        )

        val title = stream.events.first()
        assertEquals(4096, title.title.orEmpty().encodeToByteArray().size)
        assertTrue('\u0000' !in title.title.orEmpty())
        assertTrue(title.title.orEmpty().contains('\uFFFD'))
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
