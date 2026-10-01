import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-services-core"))
            api(project(":kweb-bridge"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies {
    bridgeCodegen(project(":kweb-bridge-codegen"))
}

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/clipboard-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/clipboard")
val generateClipboardBridge = tasks.register<JavaExec>("generateClipboardBridge") {
    group = "build"
    description = "Generates the KWebClipboard Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(bridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(bridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}

kotlin.sourceSets.named("jvmMain") {
    kotlin.srcDir(generatedBridgeDirectory)
}

tasks.named("compileKotlinJvm") {
    dependsOn(generateClipboardBridge)
}

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyClipboardBridgeTypescript = tasks.register<Exec>("verifyClipboardBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebClipboard TypeScript bridge."
    dependsOn(generateClipboardBridge)
    inputs.file(generatedBridgeDirectory.map { it.file("ClipboardBridgeBridge.ts") })
    inputs.file(typescriptCompiler)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath,
        "--noEmit", "--strict", "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("ClipboardBridgeBridge.ts").asFile.absolutePath,
    )
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    dependsOn(generateClipboardBridge)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named("check") {
    dependsOn(verifyClipboardBridgeTypescript)
}

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase().startsWith("windows") -> "kwebshell_clipboard.dll"
        operatingSystem.lowercase().startsWith("mac") -> "libkwebshell_clipboard.dylib"
        operatingSystem.lowercase().startsWith("linux") -> "libkwebshell_clipboard.so"
        else -> throw GradleException("Unsupported desktop operating system '${operatingSystem}'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val nativeTestExecutable = providers.systemProperty("os.name").map { operatingSystem ->
    if (operatingSystem.lowercase().startsWith("windows")) "kweb_clipboard_tests.exe"
    else "kweb_clipboard_tests"
}
val nativeArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase()) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> throw GradleException("Unsupported desktop architecture '${architecture}'.")
    }
}
val configureNative = tasks.register<Exec>("configureNative") {
    group = "build"
    inputs.file(nativeProjectDirectory.file("CMakeLists.txt"))
    inputs.dir(nativeProjectDirectory.dir("include"))
    inputs.dir(nativeProjectDirectory.dir("src"))
    inputs.dir(nativeProjectDirectory.dir("tests"))
    outputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    commandLine(
        "cmake",
        "-S", nativeProjectDirectory.asFile.absolutePath,
        "-B", nativeBuildDirectory.get().asFile.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_BUILD_TYPE=Release",
        "-DKWEB_PROJECT_ARCH=${nativeArchitecture.get()}",
    )
}
val buildNative = tasks.register<Exec>("buildNative") {
    group = "build"
    dependsOn(configureNative)
    inputs.file(nativeProjectDirectory.file("CMakeLists.txt"))
    inputs.dir(nativeProjectDirectory.dir("include"))
    inputs.dir(nativeProjectDirectory.dir("src"))
    inputs.dir(nativeProjectDirectory.dir("tests"))
    inputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    outputs.dir(nativeBuildDirectory.map { it.dir("contract") })
    commandLine("cmake", "--build", nativeBuildDirectory.get().asFile.absolutePath, "--config", "Release")
}
val nativeTest = tasks.register<Exec>("nativeTest") {
    group = "verification"
    dependsOn(buildNative)
    commandLine(nativeBuildDirectory.get().dir("contract").file("${nativeTestExecutable.get()}").asFile.absolutePath)
}
tasks.named("check") {
    dependsOn(nativeTest)
}

val ffmJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val jvmTestRuntimeClasspath = configurations.named("jvmTestRuntimeClasspath")
val jvmTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
)
val ffmIntegrationRoot = layout.buildDirectory.dir("clipboard-integration")
val cleanIntegration = tasks.register<Delete>("cleanClipboardIntegration") {
    delete(ffmIntegrationRoot)
}
val ffmIntegrationClasspath = files(jvmTestClasses, jvmTestRuntimeClasspath)
val ffmIntegrationTest = tasks.register<JavaExec>("clipboardIntegrationTest") {
    group = "verification"
    description = "Runs the real platform clipboard provider and records redacted evidence."
    dependsOn(cleanIntegration, tasks.named("jvmTestClasses"), nativeTest)
    classpath = ffmIntegrationClasspath
    mainClass.set("io.github.kingsword09.kwebshell.service.clipboard.ClipboardIntegrationMainKt")
    javaLauncher.set(ffmJava)
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=false")
    systemProperty("kweb.clipboard.native.library.path", nativeLibrary.get().absolutePath)
    systemProperty("kweb.clipboard.integration.root", ffmIntegrationRoot.get().asFile.absolutePath)
}

tasks.named("check") {
    dependsOn(ffmIntegrationTest)
}
