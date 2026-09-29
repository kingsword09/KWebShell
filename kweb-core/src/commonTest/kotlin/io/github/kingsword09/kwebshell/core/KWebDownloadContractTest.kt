package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebDownloadContractTest {
    @Test
    fun downloadStateUsesUtf8FilenameBoundsAndTerminalFileRules() {
        val acceptedName = "é".repeat(127) + "a"
        KWebDownloadState(
            id = 1,
            profileId = "profile",
            pageId = null,
            originalUrl = "https://example.test/file",
            url = "https://example.test/file",
            suggestedFileName = acceptedName,
            fileName = null,
            contentDisposition = null,
            mimeType = null,
            receivedBytes = 0,
            totalBytes = 0,
            currentSpeedBytesPerSecond = 0,
            status = KWebDownloadStatus.STARTING,
            interruptReason = KWebDownloadInterruptReason.NONE,
            sha256 = null,
            file = null,
        )

        assertFailsWith<IllegalArgumentException> {
            KWebDownloadState(
                id = 1,
                profileId = "profile",
                pageId = null,
                originalUrl = "https://example.test/file",
                url = "https://example.test/file",
                suggestedFileName = "🙂".repeat(64),
                fileName = null,
                contentDisposition = null,
                mimeType = null,
                receivedBytes = 0,
                totalBytes = 0,
                currentSpeedBytesPerSecond = 0,
                status = KWebDownloadStatus.STARTING,
                interruptReason = KWebDownloadInterruptReason.NONE,
                sha256 = null,
                file = null,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            KWebDownloadState(
                id = 1,
                profileId = "profile",
                pageId = null,
                originalUrl = "https://example.test/file",
                url = "https://example.test/file",
                suggestedFileName = "file.bin",
                fileName = "file.bin",
                contentDisposition = null,
                mimeType = null,
                receivedBytes = 1,
                totalBytes = 1,
                currentSpeedBytesPerSecond = 0,
                status = KWebDownloadStatus.COMPLETE,
                interruptReason = KWebDownloadInterruptReason.NONE,
                sha256 = null,
                file = null,
            )
        }
    }

    @Test
    fun readResultIsBounded() {
        assertTrue(KWebDownloadReadResult(ByteArray(KWEB_DOWNLOAD_MAX_READ_BYTES), true).eof)
        assertFailsWith<IllegalArgumentException> {
            KWebDownloadReadResult(ByteArray(KWEB_DOWNLOAD_MAX_READ_BYTES + 1), false)
        }
    }
}
