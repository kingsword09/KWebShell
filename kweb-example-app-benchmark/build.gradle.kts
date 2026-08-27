import java.util.Locale
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
        else -> throw GradleException("Unsupported desktop operating system for the application benchmark.")
    }
    val skikoArchitecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported desktop architecture for the application benchmark.")
    }
    implementation(project(":kweb-desktop"))
    implementation(project(":kweb-example-support"))
    implementation(libs.compose.ui.desktop)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-$skikoOs-$skikoArchitecture:${libs.versions.skiko.get()}")

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    mustRunAfter(":kweb-cef-native:buildNative")
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

val operatingSystem = providers.systemProperty("os.name")
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val nativeEngineLibrary = operatingSystem.map { name ->
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
            "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework",
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
    if (name.lowercase(Locale.ROOT).startsWith("mac")) nativeResources.get()
    else nativeReleaseDirectory.dir("locales").asFile
}
val javaLauncher = javaToolchains.launcherFor { languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(25)) }
val integrationRoot = layout.buildDirectory.dir("application-benchmark-integration")
val integrationOutput = layout.buildDirectory.dir("reports/application-benchmark")
val integrationClasspath = sourceSets.main.get().runtimeClasspath
val benchmarkMachineClass = providers.gradleProperty("kwebBenchmarkMachineClass").orElse("unspecified")
val cleanIntegration = tasks.register<Delete>("cleanApplicationBenchmarkIntegration") {
    delete(integrationRoot, integrationOutput)
}
val integrationCommand = providers.provider {
    buildList {
        add(javaLauncher.get().executablePath.asFile.absolutePath)
        add("--enable-native-access=ALL-UNNAMED")
        add("-Djava.awt.headless=false")
        add("-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}")
        add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
        add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
        add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
        add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
        add("-Dkweb.benchmark.root=${integrationRoot.get().asFile.absolutePath}/profiles")
        add("-Dkweb.benchmark.output=${integrationOutput.get().asFile.absolutePath}")
        add("-Dkweb.benchmark.workload.root=${layout.projectDirectory.dir("src/main/resources/workload").asFile.absolutePath}")
        add("-Dkweb.benchmark.workload.lock=${layout.projectDirectory.file("src/main/resources/workload.lock.json").asFile.absolutePath}")
        add("-Dkweb.benchmark.baseline.catalog=${layout.projectDirectory.file("src/main/resources/benchmark-baselines.json").asFile.absolutePath}")
        add("-Dkweb.benchmark.machine-class=${benchmarkMachineClass.get()}")
        add("-Dkweb.benchmark.git-revision=${System.getProperty("kweb.benchmark.git-revision") ?: "working-tree"}")
        add("-cp")
        add(integrationClasspath.asPath)
        add("io.github.kingsword09.kwebshell.example.benchmark.MainKt")
        add("integration")
    }
}
val applicationBenchmarkIntegrationTest = tasks.register<Exec>("applicationBenchmarkIntegrationTest") {
    group = "verification"
    description = "Runs the locked application workload benchmark against real CEF with one warmup and ten measured pairs."
    dependsOn(cleanIntegration, tasks.test, ":kweb-cef-native:buildNative")
    inputs.dir(layout.projectDirectory.dir("src/main/resources/workload"))
    inputs.file(layout.projectDirectory.file("src/main/resources/workload.lock.json"))
    inputs.file(layout.projectDirectory.file("src/main/resources/benchmark-baselines.json"))
    inputs.file(nativeEngineLibrary)
    inputs.file(nativeCefRuntime)
    inputs.file(nativeBrowserSubprocess)
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(listOf("xvfb-run", "--auto-servernum", "--server-args=-screen 0 1600x1200x24") + integrationCommand.get())
    } else {
        commandLine(integrationCommand.get())
    }
}

val applicationBenchmarkPreview = tasks.register<JavaExec>("applicationBenchmarkPreview") {
    group = "application"
    description = "Serves the locked application workload for visual and browser inspection."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.example.benchmark.MainKt")
    args("preview")
    systemProperty("kweb.benchmark.workload.root", layout.projectDirectory.dir("src/main/resources/workload").asFile.absolutePath)
    systemProperty("kweb.benchmark.workload.lock", layout.projectDirectory.file("src/main/resources/workload.lock.json").asFile.absolutePath)
}
