package io.github.kingsword09.kwebshell.example.html5

import kotlinx.serialization.decodeFromString
import java.awt.Color
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilityLabArtifactWriterTest {
    @Test
    fun writerProducesStrictJsonRenderedHtmlAndVerifiedScreenshots() {
        withTemporaryDirectory { root ->
            val source = root.resolve("source")
            val output = root.resolve("output")
            Files.createDirectory(source)
            val screenshots = mapOf(
                "cold.png" to writeFixturePng(source.resolve("cold.png"), Color(0x22, 0x66, 0xaa)),
                "warm.png" to writeFixturePng(source.resolve("warm.png"), Color(0x22, 0x88, 0x55)),
            )

            val jsonPath = CapabilityLabArtifactWriter(output).write(
                bundle = sampleCapabilityBundle(),
                screenshots = screenshots,
                expectedOrigin = "http://127.0.0.1:1",
            )

            assertEquals(output.resolve("capability-lab-report.json"), jsonPath)
            val decoded = CapabilityLabJson.format.decodeFromString<CapabilityLabBundle>(Files.readString(jsonPath))
            assertEquals(sampleCapabilityBundle(), decoded)
            val html = Files.readString(output.resolve("capability-lab-report.html"))
            assertTrue(html.contains("KWebShell HTML5 Capability Lab"))
            assertTrue(html.contains("Host and CDP evidence"))
            assertTrue(html.contains("KWebShell capability evidence"))
            screenshots.keys.forEach { fileName ->
                val image = ImageIO.read(output.resolve(fileName).toFile())
                assertEquals(12, image.width)
                assertEquals(8, image.height)
            }
        }
    }

    private fun writeFixturePng(path: Path, color: Color): Path {
        val image = BufferedImage(12, 8, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = color
            graphics.fillRect(0, 0, image.width, image.height)
        } finally {
            graphics.dispose()
        }
        assertTrue(ImageIO.write(image, "png", path.toFile()))
        return path
    }

    private fun withTemporaryDirectory(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("kweb-capability-artifacts-")
        try {
            block(root)
        } finally {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }
}
