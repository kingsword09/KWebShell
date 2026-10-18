package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebFindSelection
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
    }

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
