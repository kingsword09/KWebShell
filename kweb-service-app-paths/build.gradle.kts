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
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-services-core"))
            api(project(":kweb-bridge"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies {
    bridgeCodegen(project(":kweb-bridge-codegen"))
}
val appPathsBridgeSchema = layout.projectDirectory.file("src/mainBridge/app-paths-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/appPaths")
val generateAppPathsBridge = tasks.register<JavaExec>("generateAppPathsBridge") {
    group = "build"
    description = "Generates the KWebAppPaths Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(appPathsBridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(appPathsBridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}
kotlin.sourceSets.named("jvmMain") {
    kotlin.srcDir(generatedBridgeDirectory)
}
tasks.named("compileKotlinJvm") {
    dependsOn(generateAppPathsBridge)
}
val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyAppPathsBridgeTypescript = tasks.register<Exec>("verifyAppPathsBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebAppPaths TypeScript bridge client."
    dependsOn(generateAppPathsBridge)
    inputs.file(generatedBridgeDirectory.map { it.file("AppPathsBridgeBridge.ts") })
    inputs.file(typescriptCompiler)
    commandLine(
        "node",
        typescriptCompiler.asFile.absolutePath,
        "--noEmit",
        "--strict",
        "--target", "ES2022",
        "--module", "ES2022",
        "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("AppPathsBridgeBridge.ts").asFile.absolutePath,
    )
}
tasks.named("check") {
    dependsOn(verifyAppPathsBridgeTypescript)
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_services.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_services.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_services.so"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val nativeTestExecutable = providers.systemProperty("os.name").map { operatingSystem ->
    if (operatingSystem.lowercase(Locale.ROOT).startsWith("windows")) {
        "kweb_service_app_paths_tests.exe"
    } else {
        "kweb_service_app_paths_tests"
    }
}
val nativeArchitecture = providers.systemProperty("os.arch").map { architecture ->
    when (architecture.lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported desktop architecture '$architecture'.")
    }
}
val cefRootForNativeConfiguration = providers.gradleProperty("cefRoot").orElse("")
val configureNative = tasks.register<Exec>("configureNative") {
    group = "build"
    description = "Configures the standalone native KWebAppPaths ABI."
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
    description = "Builds the standalone KWebAppPaths native provider and contract tests."
    dependsOn(configureNative)
    inputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    outputs.dir(nativeBuildDirectory.map { it.dir("contract") })
    commandLine("cmake", "--build", nativeBuildDirectory.get().asFile.absolutePath, "--config", "Release")
}
val nativeTest = tasks.register<Exec>("nativeTest") {
    group = "verification"
    description = "Runs the real platform KWebAppPaths ABI contract tests."
    dependsOn(buildNative)
    commandLine(
        nativeBuildDirectory.get().dir("contract").file(nativeTestExecutable.get()).asFile.absolutePath,
    )
}
tasks.named("check") {
    dependsOn(nativeTest)
}

val nativeLibraryForFfm = nativeLibrary
val ffmIntegrationRoot = layout.buildDirectory.dir("ffm-integration")
val ffmJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val jvmTestRuntimeClasspath = configurations.named("jvmTestRuntimeClasspath")
val jvmTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
)
val ffmIntegrationClasspath = files(jvmTestClasses, jvmTestRuntimeClasspath)
val ffmIntegrationCommand = providers.provider {
    buildList {
        add(ffmJava.get().executablePath.asFile.absolutePath)
        add("--enable-native-access=ALL-UNNAMED")
        add("-Dkweb.services.native.library.path=${nativeLibraryForFfm.get().absolutePath}")
        add("-Dkweb.services.integration.root=${ffmIntegrationRoot.get().asFile.absolutePath}")
        add("-cp")
        add(ffmIntegrationClasspath.asPath)
        add("io.github.kingsword09.kwebshell.service.apppaths.AppPathsFfmIntegrationMainKt")
    }
}
val cleanFfmIntegration = tasks.register<Delete>("cleanFfmIntegration") {
    delete(ffmIntegrationRoot)
}
val ffmIntegrationTest = tasks.register<Exec>("ffmIntegrationTest") {
    group = "verification"
    description = "Runs the JDK 25 FFM KWebAppPaths provider against the native ABI."
    dependsOn(cleanFfmIntegration, tasks.named("jvmTestClasses"), nativeTest)
    inputs.file(nativeLibraryForFfm)
    commandLine(ffmIntegrationCommand.get())
}
tasks.named("check") {
    dependsOn(ffmIntegrationTest)
}
