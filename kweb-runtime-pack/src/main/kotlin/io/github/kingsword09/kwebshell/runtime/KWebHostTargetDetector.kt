package io.github.kingsword09.kwebshell.runtime

import io.github.kingsword09.kwebshell.core.KWebArchitecture
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebOperatingSystem
import io.github.kingsword09.kwebshell.core.KWebTarget
import java.util.Locale

public object KWebHostTargetDetector {
    public fun detect(
        osName: String = System.getProperty("os.name"),
        osArchitecture: String = System.getProperty("os.arch"),
    ): KWebTarget {
        val operatingSystem = when (osName.lowercase(Locale.ROOT)) {
            "mac os x", "macos", "darwin" -> KWebOperatingSystem.MACOS
            "linux" -> KWebOperatingSystem.LINUX
            "windows 10", "windows 11", "windows server 2022", "windows server 2025" ->
                KWebOperatingSystem.WINDOWS
            else -> unsupportedHost(osName, osArchitecture)
        }
        val architecture = when (osArchitecture.lowercase(Locale.ROOT)) {
            "x86_64", "amd64" -> KWebArchitecture.X64
            "aarch64", "arm64" -> KWebArchitecture.ARM64
            else -> unsupportedHost(osName, osArchitecture)
        }
        return KWebTarget(operatingSystem, architecture)
    }

    private fun unsupportedHost(osName: String, osArchitecture: String): Nothing {
        throw KWebConfigurationException(
            code = "target.unsupported-host",
            details = mapOf("os.name" to osName, "os.arch" to osArchitecture),
            message = "KWebShell does not support host '$osName/$osArchitecture'.",
        )
    }
}
