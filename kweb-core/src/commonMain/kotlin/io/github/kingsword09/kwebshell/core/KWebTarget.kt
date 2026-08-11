package io.github.kingsword09.kwebshell.core

public enum class KWebOperatingSystem(public val id: String) {
    WINDOWS("windows"),
    MACOS("macos"),
    LINUX("linux"),
}

public enum class KWebArchitecture(public val id: String) {
    X64("x64"),
    ARM64("arm64"),
}

public data class KWebTarget(
    public val operatingSystem: KWebOperatingSystem,
    public val architecture: KWebArchitecture,
) {
    public val id: String = "${operatingSystem.id}-${architecture.id}"

    public companion object {
        private val supportedTargets: Set<KWebTarget> =
            KWebOperatingSystem.entries
                .flatMap { operatingSystem ->
                    KWebArchitecture.entries.map { architecture ->
                        KWebTarget(operatingSystem, architecture)
                    }
                }.toSet()

        public val supported: Set<KWebTarget>
            get() = supportedTargets.toSet()

        public fun parse(value: String): KWebTarget {
            val target = supportedTargets.singleOrNull { it.id == value }
            if (target != null) {
                return target
            }

            throw KWebConfigurationException(
                code = "target.invalid",
                details = mapOf("target" to value, "supported" to supportedTargets.joinToString { it.id }),
                message = "Unsupported KWebShell target '$value'.",
            )
        }
    }
}
