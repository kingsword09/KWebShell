import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":kweb-core"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val nativeJniLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_jni.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_jni.dylib"
        else -> "libkwebshell_jni.so"
    }
    rootProject.layout.projectDirectory
        .file("kweb-cef-native/build/native/contract/$fileName")
        .asFile
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":kweb-cef-native:buildNative")
    inputs.file(nativeJniLibrary)
    systemProperty(
        "kweb.native.library.path",
        nativeJniLibrary.map { it.absolutePath }.get(),
    )
}

val operatingSystem = providers.systemProperty("os.name")
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val nativeCefRuntime = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> {
            nativeReleaseDirectory.file("libcef.dll").asFile
        }
        name.lowercase(Locale.ROOT).startsWith("mac") -> {
            nativeReleaseDirectory.file(
                "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/" +
                    "Chromium Embedded Framework",
            ).asFile
        }
        else -> nativeReleaseDirectory.file("libcef.so").asFile
    }
}
val nativeBrowserSubprocess = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> {
            nativeReleaseDirectory.file("KWebShell.exe").asFile
        }
        name.lowercase(Locale.ROOT).startsWith("mac") -> {
            nativeReleaseDirectory.file(
                "KWebShell.app/Contents/Frameworks/KWebShell Helper.app/Contents/MacOS/KWebShell Helper",
            ).asFile
        }
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
val engineIntegrationRoot = layout.buildDirectory.dir("engine-integration")
val integrationJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}
val cleanEngineIntegration = tasks.register<Delete>("cleanEngineIntegration") {
    delete(engineIntegrationRoot)
}
val engineIntegrationJavaCommand = buildList {
    add(integrationJava.get().executablePath.asFile.absolutePath)
    add("-Djava.awt.headless=false")
    add("-Dkweb.native.library.path=${nativeJniLibrary.get().absolutePath}")
    add("-Dkweb.engine.integration.root=${engineIntegrationRoot.get().asFile.absolutePath}")
    add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
    add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
    add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
    add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
    add("-cp")
    add(sourceSets.test.get().runtimeClasspath.asPath)
    add("io.github.kingsword09.kwebshell.desktop.internal.NativeEngineIntegrationMainKt")
    add("coordinator")
}

val engineIntegrationTest = tasks.register<Exec>("engineIntegrationTest") {
    group = "verification"
    description = "Runs the real in-process JVM/CEF engine contract in isolated JVMs."
    dependsOn(cleanEngineIntegration, tasks.testClasses, ":kweb-cef-native:buildNative")
    mustRunAfter(tasks.test, ":kweb-cef-native:nativeTest")

    inputs.file(nativeJniLibrary)
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
            ) + engineIntegrationJavaCommand,
        )
    } else {
        commandLine(engineIntegrationJavaCommand)
    }
}

tasks.named("check") {
    dependsOn(engineIntegrationTest)
}
