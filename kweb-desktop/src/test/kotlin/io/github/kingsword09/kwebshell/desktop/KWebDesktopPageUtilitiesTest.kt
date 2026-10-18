package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebFindSelection
import io.github.kingsword09.kwebshell.core.KWebPageContentErrorCode
import io.github.kingsword09.kwebshell.core.KWebPageContentException
import io.github.kingsword09.kwebshell.core.KWebPageContentKind
import io.github.kingsword09.kwebshell.core.KWebPageUtilityErrorCode
import io.github.kingsword09.kwebshell.core.KWebPageUtilityException
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import io.github.kingsword09.kwebshell.desktop.internal.NativeStatus
import io.github.kingsword09.kwebshell.desktop.internal.pageUtilityStatusException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The engine's RFC 0044 find-result decoding and page utility status mapping. */
class KWebDesktopPageUtilitiesTest {

    @Test
    fun aFinalFindUpdateDecodesItsCountsAndSelection() {
        val result = KWebDesktopFindJson.parse(
            event(
                requestId = 7,
                sequence = 42,
                details = """
                    {"version":1,"matches":3,"active":2,"final":true,
                     "selection":{"x":12,"y":-4,"width":80,"height":18}}
                """.trimIndent(),
            ),
        )
        assertEquals(7, result.sessionId)
        assertEquals(42, result.sequence)
        assertEquals(3, result.matches)
        assertEquals(1, result.activeMatchOrdinal)
        assertEquals(true, result.finalUpdate)
        assertEquals(KWebFindSelection(x = 12, y = -4, width = 80, height = 18), result.selection)
    }

    @Test
    fun anIntermediateUpdateIsNotFinal() {
        val result = KWebDesktopFindJson.parse(
            event(
                requestId = 1,
                details = """{"version":1,"matches":0,"active":0,"final":false,
                    "selection":{"x":0,"y":0,"width":0,"height":0}}""".trimIndent(),
            ),
        )
        assertEquals(false, result.finalUpdate)
        assertEquals(0, result.matches)
        // Chromium reports ordinal 0 before it selects a match; the published
        // contract reports that state as no active match.
        assertEquals(null, result.activeMatchOrdinal)
    }

    @Test
    fun aReportWithAnotherSchemaVersionIsRejected() {
        val error = assertFailsWith<KWebPageUtilityException> {
            KWebDesktopFindJson.parse(
                event(
                    requestId = 1,
                    details = """{"version":2,"matches":1,"active":0,"final":true,
                        "selection":{"x":0,"y":0,"width":1,"height":1}}""".trimIndent(),
                ),
            )
        }
        assertEquals(KWebPageUtilityErrorCode.CONTRACT_INVALID, error.code)
        assertEquals("version", error.details["field"])
    }

    @Test
    fun aTruncatedOrMistypedReportIsRejected() {
        listOf(
            """{"version":1,"matches":1,"active":0,"selection":{"x":0,"y":0,"width":1,"height":1}}""",
            """{"version":1,"matches":"many","active":0,"final":true,
                "selection":{"x":0,"y":0,"width":1,"height":1}}""",
            """{"version":1,"matches":1,"active":0,"final":true}""",
            "not-json",
        ).forEach { details ->
            val error = assertFailsWith<KWebPageUtilityException> {
                KWebDesktopFindJson.parse(event(requestId = 1, details = details))
            }
            assertEquals(KWebPageUtilityErrorCode.CONTRACT_INVALID, error.code)
        }
    }

    @Test
    fun anOrdinalBeyondTheMatchCountIsRejectedByTheContract() {
        val error = assertFailsWith<KWebPageUtilityException> {
            KWebDesktopFindJson.parse(
                event(
                    requestId = 1,
                    details = """{"version":1,"matches":1,"active":4,"final":true,
                        "selection":{"x":0,"y":0,"width":1,"height":1}}""".trimIndent(),
                ),
            )
        }
        assertEquals(KWebPageUtilityErrorCode.CONTRACT_INVALID, error.code)
    }

    @Test
    fun pageUtilityStatusesMapToTheirStableErrorCodes() {
        assertEquals(
            "page.find-session-stale",
            pageUtilityStatusException("find-next", NativeStatus.FIND_SESSION_STALE.value).code,
        )
        assertEquals(
            "page.zoom-level-invalid",
            pageUtilityStatusException("set-zoom-level", NativeStatus.ZOOM_LEVEL_INVALID.value).code,
        )
        assertEquals(
            "page.closed",
            pageUtilityStatusException("find", NativeStatus.BROWSER_CLOSING.value).code,
        )
        assertEquals(
            "native.abi.invalid-handle",
            pageUtilityStatusException("find", NativeStatus.INVALID_HANDLE.value).code,
        )
        assertEquals(
            "page.content-request-invalid",
            pageUtilityStatusException("page-content", NativeStatus.PAGE_CONTENT_INVALID.value).code,
        )
    }

    @Test
    fun aCompleteSourceReadDecodesItsMetadataAndBytes() {
        val content = KWebDesktopPageContentJson.parse(
            contentEvent(
                requestId = 5,
                metadata = """{"version":1,"kind":1,"totalBytes":38,"truncated":false,"url":"http://127.0.0.1/a"}""",
                document = "<html><body><p>hello</p></body></html>",
            ),
        )
        assertEquals(KWebPageContentKind.SOURCE, content.kind)
        assertEquals("http://127.0.0.1/a", content.url)
        assertEquals(38L, content.totalBytes)
        assertEquals(false, content.truncated)
        assertEquals("<html><body><p>hello</p></body></html>", content.text)
    }

    @Test
    fun aTruncatedTextReadDecodesTheFullSize() {
        val content = KWebDesktopPageContentJson.parse(
            contentEvent(
                requestId = 6,
                metadata = """{"version":1,"kind":2,"totalBytes":4096,"truncated":true,"url":"http://127.0.0.1/b"}""",
                document = "border",
            ),
        )
        assertEquals(KWebPageContentKind.TEXT, content.kind)
        assertEquals(4096L, content.totalBytes)
        assertEquals(true, content.truncated)
    }

    @Test
    fun aContentReportThatContradictsItsPayloadIsRejected() {
        listOf(
            // The document claims fewer bytes than the payload carries.
            """{"version":1,"kind":1,"totalBytes":2,"truncated":true,"url":"u"}""",
            // The document claims completeness while the payload is short.
            """{"version":1,"kind":1,"totalBytes":99,"truncated":false,"url":"u"}""",
            // An unknown document view.
            """{"version":1,"kind":3,"totalBytes":5,"truncated":false,"url":"u"}""",
            // Another schema version.
            """{"version":2,"kind":1,"totalBytes":5,"truncated":false,"url":"u"}""",
            // A missing URL is not defaulted.
            """{"version":1,"kind":1,"totalBytes":5,"truncated":false}""",
            "not-json",
        ).forEach { metadata ->
            val error = assertFailsWith<KWebPageContentException> {
                KWebDesktopPageContentJson.parse(
                    contentEvent(requestId = 1, metadata = metadata, document = "hello"),
                )
            }
            assertEquals(KWebPageContentErrorCode.CONTRACT_INVALID, error.code)
        }
    }

    @Test
    fun theOwnedContentWaitsFailTogetherOnATerminalPageState() = kotlinx.coroutines.runBlocking {
        val waiters = KWebContentWaiters()
        val first = waiters.register(1)
        val second = waiters.register(2)
        assertEquals(2, waiters.size)
        // A read that reached its own deadline is removed before the page ends.
        val timedOut = requireNotNull(waiters.take(1))
        assertEquals(1, waiters.size)
        // A late observation for a request that already ended finds no waiter.
        assertEquals(null, waiters.take(1))
        timedOut.completeExceptionally(
            KWebPageContentException(
                code = KWebPageContentErrorCode.TIMEOUT,
                details = mapOf("requestId" to "1"),
                message = "Chromium did not deliver the requested document in time.",
            ),
        )
        waiters.failAll(
            KWebPageContentException(
                code = "page.closed",
                details = mapOf("pageId" to "page-1"),
                message = "The page closed before the content read completed.",
            ),
        )
        assertEquals(0, waiters.size)
        assertEquals(
            KWebPageContentErrorCode.TIMEOUT,
            assertFailsWith<KWebPageContentException> { first.await() }.code,
        )
        assertEquals("page.closed", assertFailsWith<KWebPageContentException> { second.await() }.code)
    }

    private fun contentEvent(
        requestId: Long,
        metadata: String,
        document: String,
        sequence: Long = 11,
    ): NativeBrowserEvent = NativeBrowserEvent(
        type = NativeBrowserEventType.PAGE_CONTENT,
        engine = 1,
        browser = 2,
        sequence = sequence,
        flags = 0,
        text = metadata,
        statusCode = 0,
        width = 0,
        height = 0,
        requestId = requestId,
        details = document,
    )

    private fun event(
        requestId: Long,
        sequence: Long = 9,
        details: String,
    ): NativeBrowserEvent = NativeBrowserEvent(
        type = NativeBrowserEventType.FIND_RESULT,
        engine = 1,
        browser = 2,
        sequence = sequence,
        flags = 0,
        text = "",
        statusCode = 0,
        width = 0,
        height = 0,
        requestId = requestId,
        details = details,
    )
}
