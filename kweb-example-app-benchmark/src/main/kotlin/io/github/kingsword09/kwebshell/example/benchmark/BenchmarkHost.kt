package io.github.kingsword09.kwebshell.example.benchmark

import java.util.Locale

internal data class BenchmarkHost(
    val platform: String,
    val architecture: String,
) {
    companion object {
        fun current(): BenchmarkHost {
            val platform = when {
                System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac") -> "macos"
                System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") -> "windows"
                System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux") -> "linux"
                else -> throw BenchmarkException("host.platform-unsupported", "Unsupported benchmark platform '${System.getProperty("os.name")}'.")
            }
            val architecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
                "aarch64", "arm64" -> "arm64"
                "amd64", "x86_64" -> "x64"
                else -> throw BenchmarkException("host.architecture-unsupported", "Unsupported benchmark architecture '${System.getProperty("os.arch")}'.")
            }
            return BenchmarkHost(platform, architecture)
        }
    }
}
