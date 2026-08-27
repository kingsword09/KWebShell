import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":kweb-core"))
    implementation(project(":kweb-desktop"))
    implementation(libs.compose.ui.desktop)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val operatingSystem = providers.systemProperty("os.name")
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val nativeEngineLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_engine.so"
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
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
        name.lowercase(Locale.ROOT).startsWith("linux") -> nativeReleaseDirectory.file("libcef.so").asFile
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
    }
}
val nativeBrowserSubprocess = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> nativeReleaseDirectory.file("KWebShell.exe").asFile
        name.lowercase(Locale.ROOT).startsWith("mac") -> nativeReleaseDirectory.file(
            "KWebShell.app/Contents/Frameworks/KWebShell Helper.app/Contents/MacOS/KWebShell Helper",
        ).asFile
        name.lowercase(Locale.ROOT).startsWith("linux") -> nativeReleaseDirectory.file("KWebShell").asFile
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
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
val composeIntegrationRoot = layout.buildDirectory.dir("compose-integration")
val composeIntegrationClasspath = sourceSets.test.get().runtimeClasspath
val composeIntegrationJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val cleanComposeIntegration = tasks.register<Delete>("cleanComposeIntegration") {
    delete(composeIntegrationRoot)
}
val composeIntegrationCommand = providers.provider {
    buildList {
        add(composeIntegrationJava.get().executablePath.asFile.absolutePath)
        add("--enable-native-access=ALL-UNNAMED")
        add("-Djava.awt.headless=false")
        add("-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}")
        add("-Dkweb.compose.integration.root=${composeIntegrationRoot.get().asFile.absolutePath}")
        add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
        add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
        add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
        add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
        add("-cp")
        add(composeIntegrationClasspath.asPath)
        add("io.github.kingsword09.kwebshell.compose.KWebViewIntegrationMainKt")
    }
}
val composeIntegrationTest = tasks.register<Exec>("composeIntegrationTest") {
    group = "verification"
    description = "Runs the real ComposeWindow/native-child KWebView lifecycle against CEF."
    dependsOn(cleanComposeIntegration, tasks.testClasses, ":kweb-cef-native:buildNative")
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
            listOf(
                "xvfb-run",
                "--auto-servernum",
                "--server-args=-screen 0 1280x1024x24",
            ) + composeIntegrationCommand.get(),
        )
    } else {
        commandLine(composeIntegrationCommand.get())
    }
}

tasks.named("check") {
    dependsOn(composeIntegrationTest)
}
