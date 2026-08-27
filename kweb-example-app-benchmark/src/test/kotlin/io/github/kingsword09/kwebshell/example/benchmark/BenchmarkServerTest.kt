package io.github.kingsword09.kwebshell.example.benchmark

import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BenchmarkServerTest {
    @Test
    fun servesImmutableLockedResourcesAndRejectsUnknownPaths() {
        BenchmarkServer(Path.of("src/main/resources/workload")).use { server ->
            val client = HttpClient.newHttpClient()
            val resource = client.send(
                HttpRequest.newBuilder(URI("${server.origin}/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, resource.statusCode())
            assertEquals("public, max-age=31536000, immutable", resource.headers().firstValue("Cache-Control").orElseThrow())
            val missing = client.send(
                HttpRequest.newBuilder(URI("${server.origin}/missing.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(404, missing.statusCode())
        }
    }

    @Test
    fun observationValidationCompletesTheWaiterWithTheTypedFailure() {
        BenchmarkServer(Path.of("src/main/resources/workload")).use { server ->
            val sampleId = "00000000-0000-0000-0000-000000000001"
            server.registerSample(sampleId, "cold", "fixture")
            val client = HttpClient.newHttpClient()
            val response = client.send(
                HttpRequest.newBuilder(URI("${server.origin}/observation?sampleId=$sampleId"))
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(400, response.statusCode())
            val error = assertFailsWith<BenchmarkException> { server.awaitObservation(sampleId, 1_000L) }
            assertTrue(error.code == "server.observation-json-invalid" || error.code == "page.identity")
        }
    }
}
