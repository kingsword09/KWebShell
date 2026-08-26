import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    val skikoOs = when {
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        else -> throw GradleException("Unsupported desktop operating system for the HTML5 lab.")
    }
    val skikoArchitecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported desktop architecture for the HTML5 lab.")
    }
    implementation(project(":kweb-desktop"))
    implementation(libs.compose.ui.desktop)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoOs-$skikoArchitecture:${libs.versions.skiko.get()}")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

val operatingSystem = providers.systemProperty("os.name")
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val nativeEngineLibrary = providers.systemProperty("os.name").map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        else -> "libkwebshell_engine.so"
    }
    rootProject.layout.projectDirectory.file("kweb-cef-native/build/native/contract/$fileName").asFile
}
val nativeCefRuntime = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> nativeReleaseDirectory.file("libcef.dll").asFile
        name.lowercase(Locale.ROOT).startsWith("mac") -> nativeReleaseDirectory.file(
            "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/" +
                "Chromium Embedded Framework",
        ).asFile
        else -> nativeReleaseDirectory.file("libcef.so").asFile
    }
}
val nativeBrowserSubprocess = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> nativeReleaseDirectory.file("KWebShell.exe").asFile
        name.lowercase(Locale.ROOT).startsWith("mac") -> nativeReleaseDirectory.file(
            "KWebShell.app/Contents/Frameworks/KWebShell Helper.app/Contents/MacOS/KWebShell Helper",
        ).asFile
        else -> nativeReleaseDirectory.file("KWebShell").asFile
    }
}
val nativeResources = operatingSystem.map { name ->
    if (name.lowercase(Locale.ROOT).startsWith("mac")) {
        nativeReleaseDirectory.dir(
            "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Resources",
        ).asFile
    } else {
        nativeReleaseDirectory.asFile
    }
}
val nativeLocales = operatingSystem.map { name ->
    if (name.lowercase(Locale.ROOT).startsWith("mac")) {
        nativeResources.get()
    } else {
        nativeReleaseDirectory.dir("locales").asFile
    }
}
val integrationRoot = layout.buildDirectory.dir("capability-lab-integration")
val integrationOutput = layout.buildDirectory.dir("reports/capability-lab")
val javaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val integrationClasspath = sourceSets.main.get().runtimeClasspath
val cleanIntegration = tasks.register<Delete>("cleanCapabilityLabIntegration") {
    delete(integrationRoot, integrationOutput)
}
val integrationCommand = providers.provider {
    buildList {
        add(javaLauncher.get().executablePath.asFile.absolutePath)
        add("--enable-native-access=ALL-UNNAMED")
        add("-Djava.awt.headless=false")
        add("-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}")
        add("-Dkweb.capability.lab.root=${integrationRoot.get().asFile.absolutePath}")
        add("-Dkweb.capability.lab.output=${integrationOutput.get().asFile.absolutePath}")
        add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
        add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
        add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
        add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
        add("-cp")
        add(integrationClasspath.asPath)
        add("io.github.kingsword09.kwebshell.example.html5.MainKt")
        add("integration")
    }
}
val capabilityLabIntegrationTest = tasks.register<Exec>("capabilityLabIntegrationTest") {
    group = "verification"
    description = "Runs the HTML5 capability lab against the real CEF runtime twice for Profile persistence."
    dependsOn(cleanIntegration, tasks.test, ":kweb-cef-native:buildNative")
    inputs.dir(layout.projectDirectory.dir("src/main/resources"))
    inputs.file(nativeEngineLibrary)
    inputs.file(nativeCefRuntime)
    inputs.file(nativeBrowserSubprocess)
    inputs.file(nativeResources.map { it.resolve("resources.pak") })
    inputs.file(nativeLocales.map { directory ->
        if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("mac")) {
            directory.resolve("en.lproj/locale.pak")
        } else {
            directory.resolve("en-US.pak")
        }
    })
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(
            listOf("xvfb-run", "--auto-servernum", "--server-args=-screen 0 1440x1000x24") +
                integrationCommand.get(),
        )
    } else {
        commandLine(integrationCommand.get())
    }
}

tasks.named("check") {
    dependsOn(capabilityLabIntegrationTest)
}
