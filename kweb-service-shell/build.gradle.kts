import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-services-core"))
            api(project(":kweb-core"))
            api(project(":kweb-bridge"))
            api(project(":kweb-service-files"))
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies { bridgeCodegen(project(":kweb-bridge-codegen")) }

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/shell-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/shell")
val generateShellBridge = tasks.register<JavaExec>("generateShellBridge") {
    group = "build"
    description = "Generates the KWebShell Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(bridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(bridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(generatedBridgeDirectory) }
tasks.named("compileKotlinJvm") { dependsOn(generateShellBridge) }

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyShellBridgeTypescript = tasks.register<Exec>("verifyShellBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebShell TypeScript bridge client."
    dependsOn(generateShellBridge)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath, "--noEmit", "--strict",
        "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("ShellBridgeBridge.ts").asFile.absolutePath,
    )
}
tasks.named("check") { dependsOn(verifyShellBridgeTypescript) }
tasks.named<Test>("jvmTest") { useJUnitPlatform() }

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_shell.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_shell.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_shell.so"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val nativeTestExecutable = providers.systemProperty("os.name").map { operatingSystem ->
    if (operatingSystem.lowercase(Locale.ROOT).startsWith("windows")) "kweb_service_shell_tests.exe" else "kweb_service_shell_tests"
}
val nativeArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported desktop architecture '$architecture'.")
    }
}
val configureNative = tasks.register<Exec>("configureNative") {
    group = "build"
    inputs.dir(nativeProjectDirectory)
    outputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    commandLine(
        "cmake", "-S", nativeProjectDirectory.asFile.absolutePath,
        "-B", nativeBuildDirectory.get().asFile.absolutePath,
        "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release",
        "-DKWEB_PROJECT_ARCH=${nativeArchitecture.get()}",
    )
}
val buildNative = tasks.register<Exec>("buildNative") {
    group = "build"
    dependsOn(configureNative)
    inputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    outputs.dir(nativeBuildDirectory.map { it.dir("contract") })
    commandLine("cmake", "--build", nativeBuildDirectory.get().asFile.absolutePath, "--config", "Release")
}
val nativeTest = tasks.register<Exec>("nativeTest") {
    group = "verification"
    dependsOn(buildNative)
    commandLine(nativeBuildDirectory.get().dir("contract").file(nativeTestExecutable.get()).asFile.absolutePath)
}
tasks.named("check") { dependsOn(nativeTest) }

val ffmJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val jvmTestRuntimeClasspath = configurations.named("jvmTestRuntimeClasspath")
val jvmTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
)
val ffmIntegrationCommand = providers.provider {
    listOf(
        ffmJava.get().executablePath.asFile.absolutePath,
        "--enable-native-access=ALL-UNNAMED",
        "-Dkweb.shell.native.library.path=${nativeLibrary.get().absolutePath}",
        "-cp", files(jvmTestClasses, jvmTestRuntimeClasspath).asPath,
        "io.github.kingsword09.kwebshell.service.shell.ShellFfmIntegrationMainKt",
    )
}
val ffmIntegrationTest = tasks.register<Exec>("ffmIntegrationTest") {
    group = "verification"
    dependsOn(tasks.named("jvmTestClasses"), nativeTest)
    commandLine(ffmIntegrationCommand.get())
}
tasks.named("check") { dependsOn(ffmIntegrationTest) }

val cleanShellIntegration = tasks.register<Delete>("cleanShellIntegration") {
    delete(layout.buildDirectory.dir("shell-integration"))
}
