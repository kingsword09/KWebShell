package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebCaptureDecision
import io.github.kingsword09.kwebshell.core.KWebCaptureDecisionReason
import io.github.kingsword09.kwebshell.core.KWebCaptureKind
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The engine's capture report decoding and the published refusal reasons. */
class KWebDesktopCaptureTest {

    @Test
    fun aDesktopVideoRequestDecodesAsTheDeclaredRefusal() {
        val decision = KWebDesktopCaptureJson.parse(
            event(
                origin = "https://example.test",
                frameId = "5",
                reason = 1,
                details = """
                    {"version":1,"requested":8,"granted":0,"reason":1,"mainFrame":true,
                     "desktopVideo":true,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                """.trimIndent(),
            ),
        ) as KWebCaptureDecision.Denied
        assertEquals("https://example.test", decision.origin)
        assertEquals("5", decision.frameId)
        assertTrue(decision.isMainFrame)
        assertEquals(setOf(KWebCaptureKind.DESKTOP_VIDEO), decision.requested)
        assertEquals(KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED, decision.reason)
    }

    @Test
    fun aChildFrameRequestReportsTheFrameRefusal() {
        val decision = KWebDesktopCaptureJson.parse(
            event(
                origin = "https://example.test",
                frameId = "9",
                reason = 3,
                details = """
                    {"version":1,"requested":8,"granted":0,"reason":3,"mainFrame":false,
                     "desktopVideo":true,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                """.trimIndent(),
            ),
        ) as KWebCaptureDecision.Denied
        assertEquals(KWebCaptureDecisionReason.FRAME_REFUSED, decision.reason)
        assertTrue(!decision.isMainFrame)
    }

    @Test
    fun deviceCaptureIsDistinguishedFromDesktopCapture() {
        val decision = KWebDesktopCaptureJson.parse(
            event(
                origin = "https://example.test",
                details = """
                    {"version":1,"requested":3,"granted":0,"reason":4,"mainFrame":true,
                     "desktopVideo":false,"desktopAudio":false,"deviceVideo":true,"deviceAudio":true}
                """.trimIndent(),
            ),
        ) as KWebCaptureDecision.Denied
        assertEquals(setOf(KWebCaptureKind.CAMERA, KWebCaptureKind.MICROPHONE), decision.requested)
        assertEquals(KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED, decision.reason)
    }

    @Test
    fun aReportThatClaimsAGrantIsRejected() {
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebDesktopCaptureJson.parse(
                event(
                    origin = "https://example.test",
                    details = """
                        {"version":1,"requested":8,"granted":8,"reason":0,"mainFrame":true,
                         "desktopVideo":true,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("granted", failure.details["field"])
    }

    @Test
    fun anUnknownReportVersionOrReasonIsRejected() {
        val version = assertFailsWith<KWebConfigurationException> {
            KWebDesktopCaptureJson.parse(
                event(
                    origin = "https://example.test",
                    details = """
                        {"version":2,"requested":8,"granted":0,"reason":1,"mainFrame":true,
                         "desktopVideo":true,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("version", version.details["field"])

        val reason = assertFailsWith<KWebConfigurationException> {
            KWebDesktopCaptureJson.parse(
                event(
                    origin = "https://example.test",
                    details = """
                        {"version":1,"requested":8,"granted":0,"reason":99,"mainFrame":true,
                         "desktopVideo":true,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("reason", reason.details["field"])
    }

    @Test
    fun anUndefinedPermissionBitIsRejected() {
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebDesktopCaptureJson.parse(
                event(
                    origin = "https://example.test",
                    details = """
                        {"version":1,"requested":16,"granted":0,"reason":1,"mainFrame":true,
                         "desktopVideo":false,"desktopAudio":false,"deviceVideo":false,"deviceAudio":false}
                    """.trimIndent(),
                ),
            )
        }
        assertEquals("requested", failure.details["field"])
    }

    private fun event(
        origin: String = "",
        frameId: String = "1",
        reason: Int = 1,
        details: String,
    ): NativeBrowserEvent = NativeBrowserEvent(
        type = NativeBrowserEventType.MEDIA_PERMISSION,
        engine = 1,
        browser = 2,
        sequence = 11,
        flags = 0,
        text = "",
        statusCode = 0,
        width = 0,
        height = 0,
        frameId = frameId,
        reason = reason,
        origin = origin,
        details = details,
    )
}
