package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The published RFC 0025 capture decision contract. */
class KWebCaptureContractTest {

    @Test
    fun theOnlyPublishedCapabilityReportDeclaresNoCapture() {
        val capabilities = KWebCaptureCapabilities.DECLARED
        assertTrue(!capabilities.desktopCapture)
        assertTrue(!capabilities.deviceCapture)
        assertEquals(
            setOf(
                KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED,
                KWebCaptureDecisionReason.FRAME_REFUSED,
                KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED,
            ),
            capabilities.refusalReasons,
        )
    }

    @Test
    fun aCapabilityReportCannotClaimDesktopOrDeviceCapture() {
        val desktop = assertFailsWith<KWebConfigurationException> {
            KWebCaptureCapabilities(
                desktopCapture = true,
                deviceCapture = false,
                refusalReasons = setOf(KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED),
            )
        }
        assertEquals(KWebCaptureErrorCode.CONTRACT_INVALID, desktop.code)
        assertEquals("desktopCapture", desktop.details["field"])

        val device = assertFailsWith<KWebConfigurationException> {
            KWebCaptureCapabilities(
                desktopCapture = false,
                deviceCapture = true,
                refusalReasons = setOf(KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED),
            )
        }
        assertEquals("deviceCapture", device.details["field"])
    }

    @Test
    fun aReportWithoutTheDeclaredRefusalIsRejected() {
        val failure = assertFailsWith<KWebConfigurationException> {
            KWebCaptureCapabilities(
                desktopCapture = false,
                deviceCapture = false,
                refusalReasons = setOf(KWebCaptureDecisionReason.FRAME_REFUSED),
            )
        }
        assertEquals("refusalReasons", failure.details["field"])
    }

    @Test
    fun aDenialAlwaysCarriesItsOwnReasonAndAtLeastOneKind() {
        val denied = KWebCaptureDecision.Denied(
            origin = "https://example.test",
            frameId = "7",
            isMainFrame = true,
            requested = setOf(KWebCaptureKind.DESKTOP_VIDEO),
            reason = KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED,
        )
        assertEquals(KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED, denied.reason)
        assertEquals(setOf(KWebCaptureKind.DESKTOP_VIDEO), denied.requested)

        val noReason = assertFailsWith<KWebConfigurationException> {
            KWebCaptureDecision.Denied(
                origin = "https://example.test",
                frameId = "7",
                isMainFrame = true,
                requested = setOf(KWebCaptureKind.DESKTOP_VIDEO),
                reason = KWebCaptureDecisionReason.NONE,
            )
        }
        assertEquals("reason", noReason.details["field"])

        val noKind = assertFailsWith<KWebConfigurationException> {
            KWebCaptureDecision.Denied(
                origin = "https://example.test",
                frameId = "7",
                isMainFrame = true,
                requested = emptySet(),
                reason = KWebCaptureDecisionReason.DESKTOP_CAPTURE_UNSUPPORTED,
            )
        }
        assertEquals("requested", noKind.details["field"])

        val noOrigin = assertFailsWith<KWebConfigurationException> {
            KWebCaptureDecision.Denied(
                origin = "",
                frameId = "7",
                isMainFrame = true,
                requested = setOf(KWebCaptureKind.CAMERA),
                reason = KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED,
            )
        }
        assertEquals("origin", noOrigin.details["field"])
    }

    @Test
    fun everyCaptureKindAndReasonHasAStableIdentifier() {
        assertEquals(
            listOf("desktop-video", "desktop-audio", "camera", "microphone"),
            KWebCaptureKind.entries.map { it.id },
        )
        assertEquals(
            listOf(
                "none",
                "desktop-capture-unsupported",
                "frame-refused",
                "device-capture-not-granted",
            ),
            KWebCaptureDecisionReason.entries.map { it.id },
        )
        assertEquals("capture.desktop-unsupported", KWebCaptureErrorCode.DESKTOP_CAPTURE_UNSUPPORTED)
        assertEquals("capture.frame-refused", KWebCaptureErrorCode.FRAME_REFUSED)
        assertEquals("capture.device-not-granted", KWebCaptureErrorCode.DEVICE_CAPTURE_NOT_GRANTED)
    }

    @Test
    fun anEventSequenceMustBePositive() {
        val decision = KWebCaptureDecision.Denied(
            origin = "https://example.test",
            frameId = "1",
            isMainFrame = true,
            requested = setOf(KWebCaptureKind.MICROPHONE),
            reason = KWebCaptureDecisionReason.DEVICE_CAPTURE_NOT_GRANTED,
        )
        assertEquals(3L, KWebCapturePermissionEvent(3L, decision).sequence)
        assertFailsWith<KWebConfigurationException> { KWebCapturePermissionEvent(0L, decision) }
    }
}
