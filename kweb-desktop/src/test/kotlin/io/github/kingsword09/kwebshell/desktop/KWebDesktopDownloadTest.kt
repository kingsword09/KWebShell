package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebDownload
import io.github.kingsword09.kwebshell.core.KWebDownloadControlResult
import io.github.kingsword09.kwebshell.core.KWebDownloadFile
import io.github.kingsword09.kwebshell.core.KWebDownloadReadResult
import io.github.kingsword09.kwebshell.core.KWebDownloadState
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KWebDesktopDownloadTest {
    @Test
    fun safeNamesRejectTraversalReservedAndUtf8Overflow() {
        listOf("", ".", "..", "../escape", "a/b", "a\\b", "CON.txt", "LPT1", "name.", "name ")
            .forEach { assertEquals(null, safeDownloadName(it), it) }
        assertEquals(null, safeDownloadName("🙂".repeat(64)))
        assertEquals("数据.bin", safeDownloadName("数据.bin"))
    }

    @Test
    fun uniqueNamesPreserveExtensionAndBoundLength() {
        assertEquals("report (1).bin", uniqueDownloadName("report.bin", 1))
        assertEquals("report (2)", uniqueDownloadName("report", 2))
        assertTrue(uniqueDownloadName("x".repeat(255), 1).encodeToByteArray().size <= 255)
    }

    @Test
    fun nativeJsonAllowsAbsentOptionalDispositionAndMime() {
        val update = KWebDesktopDownloadJson.parse(
            """
            {
              "version":1,"downloadId":"7","status":"starting",
              "originalUrl":"https://example.test/file","url":"https://example.test/file",
              "suggestedFileName":"file.bin","contentDisposition":"","mimeType":"",
              "receivedBytes":"0","totalBytes":null,"currentSpeedBytesPerSecond":"0",
              "interruptReason":0,"stagingPath":null
            }
            """.trimIndent(),
        )
        assertEquals("", update.contentDisposition)
        assertEquals("", update.mimeType)
        assertEquals(null, update.totalBytes)
        assertEquals(null, update.stagingPath)
    }

    @Test
    fun policyRequiresExistingDirectoryAndValidatedExpectedHashes() {
        val invalidDirectory = assertFailsWith<KWebConfigurationException> {
            KWebDesktopDownloadPolicy(Path.of("relative-downloads")).validated()
        }
        assertEquals("download.destination.invalid", invalidDirectory.code)

        val directory = Files.createTempDirectory("kweb-download-policy")
        try {
            val invalidHash = assertFailsWith<KWebConfigurationException> {
                KWebDesktopDownloadPolicy(
                    directory,
                    expectedSha256ByUrl = mapOf("https://example.test/file" to "bad"),
                ).validated()
            }
            assertEquals("download.policy.invalid", invalidHash.code)
        } finally {
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun completedFileReadsWithinBoundsAndCloses() = runBlocking {
        val path = Files.createTempFile("kweb-download", ".bin")
        try {
            Files.write(path, byteArrayOf(1, 2, 3))
            val file = KWebDesktopDownloadFile(path, "file.bin", 3, null)
            assertEquals(listOf<Byte>(1, 2), file.read(0, 2).bytes.toList())
            assertTrue(file.read(3, 1).eof)
            val bounds = assertFailsWith<KWebNativeException> { file.read(0, 0) }
            assertEquals("download.file-read-bounds", bounds.code)
            file.close()
            val closed = assertFailsWith<KWebNativeException> { file.read(0, 1) }
            assertEquals("download.file-closed", closed.code)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun downloadStreamRejectsUnobservedEventsAsBackpressure() = runBlocking {
        val stream = KWebDesktopDownloadStream()
        assertFalse(stream.publish(testDownload(1)))
        val error = assertFailsWith<KWebNativeException> { stream.flow.first() }
        assertEquals("download.event-backpressure", error.code)
    }

    @Test
    fun downloadStreamPublishesOnlyAfterSubscriberIsReady() = runBlocking {
        val stream = KWebDesktopDownloadStream()
        val received = MutableStateFlow<KWebDownload?>(null)
        val collector = launch {
            stream.flow.collect { received.value = it; awaitCancellation() }
        }
        stream.awaitSubscriber()
        assertTrue(stream.publish(testDownload(2)))
        assertEquals(2L, received.first { it != null }?.id)
        collector.cancel()
    }

    private fun testDownload(id: Long): KWebDownload = object : KWebDownload {
        override val id: Long = id
        override val profileId: String = "profile"
        override val pageId: String? = null
        override val state = MutableStateFlow(
            KWebDownloadState(
                id = id,
                profileId = profileId,
                pageId = pageId,
                originalUrl = "https://example.test/file",
                url = "https://example.test/file",
                suggestedFileName = "file.bin",
                fileName = null,
                contentDisposition = null,
                mimeType = null,
                receivedBytes = 0,
                totalBytes = null,
                currentSpeedBytesPerSecond = 0,
                status = io.github.kingsword09.kwebshell.core.KWebDownloadStatus.STARTING,
                interruptReason = io.github.kingsword09.kwebshell.core.KWebDownloadInterruptReason.NONE,
                sha256 = null,
                file = null,
            ),
        )

        override suspend fun pause(): KWebDownloadControlResult = error("test")
        override suspend fun resume(): KWebDownloadControlResult = error("test")
        override suspend fun cancel(): KWebDownloadControlResult = error("test")
        override fun close() = Unit
    }
}
