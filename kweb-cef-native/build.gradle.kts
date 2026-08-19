import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider
import java.util.Locale

private class CefCmakeArguments(
    @get:Input
    @get:Optional
    val cefRoot: Provider<String>,
    @get:Input
    val projectArchitecture: Provider<String>,
    @get:Input
    val expectHardwareGpuUnavailable: Provider<String>,
    @get:Input
    val expectCustomExtensionRuntime: Provider<String>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val cefRootValue = cefRoot.orNull ?: throw GradleException(
            "Missing -PcefRoot=<absolute-path-to-extracted-cef-distribution> for kweb-cef-native.",
        )
        val cefRootFile = File(cefRootValue)
        if (!cefRootFile.isAbsolute) {
            throw GradleException("-PcefRoot must be an absolute path: '$cefRootValue'.")
        }
        if (!cefRootFile.resolve("cmake/FindCEF.cmake").isFile) {
            throw GradleException(
                "-PcefRoot does not contain cmake/FindCEF.cmake: '${cefRootFile.absolutePath}'.",
            )
        }
        val expectUnavailable = when (expectHardwareGpuUnavailable.get().lowercase(Locale.ROOT)) {
            "true" -> "ON"
            "false" -> "OFF"
            else -> throw GradleException(
                "-PkwebExpectHardwareGpuUnavailable must be 'true' or 'false'.",
            )
        }
        val expectCustomRuntime = when (expectCustomExtensionRuntime.get().lowercase(Locale.ROOT)) {
            "true" -> "ON"
            "false" -> "OFF"
            else -> throw GradleException(
                "-PkwebExpectCustomExtensionRuntime must be 'true' or 'false'.",
            )
        }
        return listOf(
            "-DCEF_ROOT=${cefRootFile.absolutePath}",
            "-DPROJECT_ARCH=${projectArchitecture.get()}",
            "-DKWEB_EXPECT_HARDWARE_GPU_UNAVAILABLE=$expectUnavailable",
            "-DKWEB_EXPECT_CUSTOM_EXTENSION_RUNTIME=$expectCustomRuntime",
        )
    }
}

plugins {
    `java-base`
}

val cefRoot = providers.gradleProperty("cefRoot")
val expectHardwareGpuUnavailable =
    providers.gradleProperty("kwebExpectHardwareGpuUnavailable").orElse("false")
val expectCustomExtensionRuntime =
    providers.gradleProperty("kwebExpectCustomExtensionRuntime").orElse("false")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeContractDirectory = nativeBuildDirectory.map { it.dir("contract") }
val nativeUnitTestExecutable = providers.systemProperty("os.name").map { operatingSystem ->
    if (operatingSystem.lowercase(Locale.ROOT).startsWith("windows")) {
        "kweb_host_unit_tests.exe"
    } else {
        "kweb_host_unit_tests"
    }
}
val engineLibraryFileName = providers.systemProperty("os.name").map { operatingSystem ->
    when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        else -> "libkwebshell_engine.so"
    }
}
val interopProbeLibraryFileName = providers.systemProperty("os.name").map { operatingSystem ->
    when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_interop_probe.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_interop_probe.dylib"
        else -> "libkwebshell_interop_probe.so"
    }
}
val projectArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException(
            "kweb-cef-native does not support host architecture '$architecture'.",
        )
    }
}

tasks.register<Exec>("configureNative") {
    group = "build"
    description = "Configures the native CEF host with CMake."
    inputs.file(layout.projectDirectory.file("CMakeLists.txt"))
    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.dir(layout.projectDirectory.dir("include"))
    inputs.dir(layout.projectDirectory.dir("resources"))
    inputs.dir(layout.projectDirectory.dir("tests"))
    outputs.file(nativeBuildDirectory.map { it.file("build.ninja") })

    commandLine(
        "cmake",
        "-S", layout.projectDirectory.asFile.absolutePath,
        "-B", nativeBuildDirectory.get().asFile.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
    )
    argumentProviders.add(
        CefCmakeArguments(
            cefRoot,
            projectArchitecture,
            expectHardwareGpuUnavailable,
            expectCustomExtensionRuntime,
        ),
    )
}

tasks.register<Exec>("buildNative") {
    group = "build"
    description = "Builds the native CEF host and its subprocesses."
    dependsOn("configureNative")
    inputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    inputs.file(layout.projectDirectory.file("CMakeLists.txt"))
    inputs.dir(layout.projectDirectory.dir("src"))
    inputs.dir(layout.projectDirectory.dir("include"))
    inputs.dir(layout.projectDirectory.dir("resources"))
    inputs.dir(layout.projectDirectory.dir("tests"))
    outputs.dir(nativeBuildDirectory.map { it.dir("Release") })
    outputs.file(nativeContractDirectory.zip(engineLibraryFileName) { directory, fileName ->
        directory.file(fileName)
    })
    outputs.file(nativeContractDirectory.zip(interopProbeLibraryFileName) { directory, fileName ->
        directory.file(fileName)
    })
    outputs.file(nativeBuildDirectory.zip(nativeUnitTestExecutable) { directory, executable ->
        directory.file(executable)
    })
    commandLine("cmake", "--build", nativeBuildDirectory.get().asFile.absolutePath, "--config", "Release")
}

tasks.register<Exec>("nativeTest") {
    group = "verification"
    description = "Runs the configured native CEF host test contract."
    dependsOn("buildNative")
    mustRunAfter(
        ":kweb-core:check",
        ":kweb-runtime-pack:check",
        ":kweb-runtime-pack:verifyCefRuntimeManifest",
    )
    commandLine(
        "ctest",
        "--test-dir", nativeBuildDirectory.get().asFile.absolutePath,
        "--build-config", "Release",
        "--output-on-failure",
    )
}

tasks.named("check") {
    dependsOn("nativeTest")
}
