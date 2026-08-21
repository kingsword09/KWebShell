package io.github.kingsword09.kwebshell.example.benchmark

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BenchmarkContractTest {
    @Test
    fun verifiesTheCheckedInWorkloadLockAgainstEveryResource() {
        val root = Path.of("src/main/resources/workload")
        val lock = Path.of("src/main/resources/workload.lock.json")
        val result = BenchmarkWorkloadVerifier.loadAndVerify(lock, root)
        assertEquals("index.html", result.entryPoint)
        assertEquals(7, result.files.size)
    }

    @Test
    fun rejectsAChangedWorkloadFileWithoutFallback() {
        val sourceRoot = Path.of("src/main/resources/workload")
        val sourceLock = Path.of("src/main/resources/workload.lock.json")
        val temporary = Files.createTempDirectory("kwebshell-workload-tamper")
        try {
            val copiedRoot = temporary.resolve("workload")
            copyTree(sourceRoot, copiedRoot)
            val copiedLock = temporary.resolve("workload.lock.json")
            Files.copy(sourceLock, copiedLock)
            copiedRoot.resolve("worker.js").writeText(copiedRoot.resolve("worker.js").toFile().readText() + "\nchanged")
            val error = assertFailsWith<BenchmarkException> {
                BenchmarkWorkloadVerifier.loadAndVerify(copiedLock, copiedRoot)
            }
            assertEquals("workload.file-digest-mismatch", error.code)
        } finally {
            Files.walk(temporary).use { entries ->
                entries.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun percentileUsesDeterministicNearestRankAndRejectsEmptyInput() {
        assertEquals(3.0, percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.5))
        assertEquals(5.0, percentile(listOf(1.0, 2.0, 3.0, 4.0, 5.0), 0.95))
        assertFailsWith<IllegalArgumentException> { percentile(emptyList(), 0.5) }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { entries ->
            entries.forEach { entry ->
                val relative = source.relativize(entry)
                val destination = target.resolve(relative.toString())
                if (Files.isDirectory(entry)) destination.createDirectories()
                else Files.copy(entry, destination, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}
