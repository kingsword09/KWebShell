import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
import java.util.Locale

plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

val skikoTarget = providers.systemProperty("os.name").zip(
    providers.systemProperty("os.arch"),
) { operatingSystem, architecture ->
    val os = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        else -> throw GradleException("Unsupported interop probe operating system '$operatingSystem'.")
    }
    val arch = when (architecture.lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported interop probe architecture '$architecture'.")
    }
    "$os-$arch"
}

dependencies {
    implementation(libs.compose.ui.desktop)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-${skikoTarget.get()}:${libs.versions.skiko.get()}")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val jdk25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val nativeContractDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/contract")
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val operatingSystem = providers.systemProperty("os.name")
val interopProbeLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_interop_probe.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_interop_probe.dylib"
        else -> "libkwebshell_interop_probe.so"
    }
    nativeContractDirectory.file(fileName).asFile
}
val engineLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        else -> "libkwebshell_engine.so"
    }
    nativeContractDirectory.file(fileName).asFile
}
val cefRuntimeLibrary = operatingSystem.map { name ->
    when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> nativeReleaseDirectory.file("libcef.dll").asFile
        name.lowercase(Locale.ROOT).startsWith("mac") -> nativeReleaseDirectory.file(
            "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/" +
                "Chromium Embedded Framework",
        ).asFile
        else -> nativeReleaseDirectory.file("libcef.so").asFile
    }
}
val realCefVerificationTasks = listOf(
    ":kweb-cef-native:nativeTest",
    ":kweb-desktop:engineIntegrationTest",
    ":kweb-desktop:extensionLifecycleIntegrationTest",
)

val verifyFfmProbe = tasks.register<JavaExec>("verifyFfmProbe") {
    group = "verification"
    description = "Verifies JDK 25 FFM layouts, strict Unicode, native-thread upcalls, and Arena lifetime."
    dependsOn(tasks.classes, ":kweb-cef-native:buildNative")
    javaLauncher.set(jdk25Launcher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.interop.probe.ProbeContractMain")
    args(interopProbeLibrary.get().absolutePath)
    inputs.file(interopProbeLibrary)
    mustRunAfter(realCefVerificationTasks)
}

val verifyJniFfmContract = tasks.register<JavaExec>("verifyJniFfmContract") {
    group = "verification"
    description = "Compares JNI and JDK 25 FFM behavior through one native probe library."
    dependsOn(tasks.classes, ":kweb-cef-native:buildNative")
    javaLauncher.set(jdk25Launcher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.interop.probe.JniFfmContractMain")
    args(interopProbeLibrary.get().absolutePath)
    inputs.file(interopProbeLibrary)
    mustRunAfter(verifyFfmProbe)
}

val verifyFfmEngineAbi = tasks.register<JavaExec>("verifyFfmEngineAbi") {
    group = "verification"
    description = "Binds all 18 frozen engine ABI exports and exercises safe calls through JDK 25 FFM."
    dependsOn(tasks.classes, ":kweb-cef-native:buildNative")
    javaLauncher.set(jdk25Launcher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.interop.probe.EngineInventoryMain")
    args(cefRuntimeLibrary.get().absolutePath, engineLibrary.get().absolutePath)
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("windows")) {
        environment(
            "PATH",
            nativeReleaseDirectory.asFile.absolutePath + File.pathSeparator +
                providers.environmentVariable("PATH").get(),
        )
    }
    inputs.file(cefRuntimeLibrary)
    inputs.file(engineLibrary)
    mustRunAfter(verifyJniFfmContract)
}

val parentJavaCommand = listOf(
    jdk25Launcher.get().executablePath.asFile.absolutePath,
    "--enable-native-access=ALL-UNNAMED",
    "-Djava.awt.headless=false",
    "-cp",
    sourceSets.main.get().runtimeClasspath.asPath,
    "io.github.kingsword09.kwebshell.interop.probe.ComposeParentContractMain",
    interopProbeLibrary.get().absolutePath,
)
val verifyComposeNativeParent = tasks.register<Exec>("verifyComposeNativeParent") {
    group = "verification"
    description = "Proves ComposeWindow.windowHandle is a valid native top-level parent."
    dependsOn(tasks.classes, ":kweb-cef-native:buildNative")
    inputs.file(interopProbeLibrary)
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(
            listOf("xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x1024x24") +
                parentJavaCommand,
        )
    } else {
        commandLine(parentJavaCommand)
    }
    mustRunAfter(verifyFfmEngineAbi)
}

val benchmarkReport = layout.buildDirectory.file("reports/interop-benchmark/results.json")
val benchmarkInterop = tasks.register<JavaExec>("benchmarkInterop") {
    group = "verification"
    description = "Measures the same native operations through JNI and JDK 25 FFM."
    dependsOn(tasks.classes, ":kweb-cef-native:buildNative")
    javaLauncher.set(jdk25Launcher)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.interop.probe.InteropBenchmarkMain")
    args(
        interopProbeLibrary.get().absolutePath,
        benchmarkReport.get().asFile.absolutePath,
    )
    inputs.file(interopProbeLibrary)
    mustRunAfter(verifyComposeNativeParent)
}

tasks.named("check") {
    dependsOn(":kweb-cef-native:nativeTest")
    dependsOn(verifyFfmProbe)
    dependsOn(verifyJniFfmContract)
    dependsOn(verifyFfmEngineAbi)
    dependsOn(verifyComposeNativeParent)
    dependsOn(benchmarkInterop)
}
