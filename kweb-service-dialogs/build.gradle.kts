import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale
import java.time.Duration
import org.gradle.jvm.toolchain.JavaLanguageVersion

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
        jvmMain.dependencies {
            implementation(libs.compose.ui.desktop)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.compose.ui.desktop)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":kweb-desktop"))
            implementation(project(":kweb-example-support"))
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val skikoTarget = providers.systemProperty("os.name").zip(
    providers.systemProperty("os.arch"),
) { operatingSystem, architecture ->
    val os = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "windows"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "macos"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "linux"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    val arch = when (architecture.lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException("Unsupported desktop architecture '$architecture'.")
    }
    "$os-$arch"
}

dependencies {
    add("jvmTestRuntimeOnly", "org.jetbrains.skiko:skiko-awt-runtime-${skikoTarget.get()}:${libs.versions.skiko.get()}")
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies {
    bridgeCodegen(project(":kweb-bridge-codegen"))
}

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/dialogs-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/dialogs")
val generateDialogsBridge = tasks.register<JavaExec>("generateDialogsBridge") {
    group = "build"
    description = "Generates the KWebDialogs Kotlin and browser bridge clients."
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
    dependsOn(generateDialogsBridge)
}

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyDialogsBridgeTypescript = tasks.register<Exec>("verifyDialogsBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebDialogs TypeScript bridge."
    dependsOn(generateDialogsBridge)
    inputs.file(generatedBridgeDirectory.map { it.file("DialogsBridgeBridge.ts") })
    inputs.file(typescriptCompiler)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath,
        "--noEmit", "--strict", "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("DialogsBridgeBridge.ts").asFile.absolutePath,
    )
}

tasks.named<Test>("jvmTest") {
    dependsOn(generateDialogsBridge)
}

tasks.named("check") {
    dependsOn(verifyDialogsBridgeTypescript)
}

val nativeBuildRoot = layout.buildDirectory.dir("native")
val dialogsNativeLibrary = nativeBuildRoot.map { directory ->
    val name = when {
        System.getProperty("os.name").startsWith("Mac") -> "libkwebshell_dialogs.dylib"
        System.getProperty("os.name").startsWith("Windows") -> "kwebshell_dialogs.dll"
        else -> "libkwebshell_dialogs.so"
    }
    directory.file("contract/$name")
}
val configureNative = tasks.register<Exec>("configureNative") {
    inputs.dir("native")
    outputs.file(nativeBuildRoot.map { it.file("build.ninja") })
    commandLine("cmake", "-S", layout.projectDirectory.dir("native").asFile.absolutePath,
        "-B", nativeBuildRoot.get().asFile.absolutePath, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release")
}
val buildNative = tasks.register<Exec>("buildNative") {
    dependsOn(configureNative)
    inputs.dir("native")
    outputs.file(dialogsNativeLibrary)
    commandLine("cmake", "--build", nativeBuildRoot.get().asFile.absolutePath)
}
val nativeTest = tasks.register<Exec>("nativeTest") {
    dependsOn(buildNative)
    commandLine("ctest", "--test-dir", nativeBuildRoot.get().asFile.absolutePath, "--output-on-failure")
}
tasks.named("check") { dependsOn(nativeTest) }

val packageNativeDialogs = tasks.register<Zip>("packageNativeDialogs") {
    group = "distribution"
    description = "Packages the optional dialogs provider for the current desktop target."
    dependsOn(buildNative)
    archiveBaseName.set("kwebshell-dialogs")
    archiveClassifier.set(skikoTarget)
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(dialogsNativeLibrary) { into("lib") }
    from("native/include/kweb_dialogs.h") { into("include") }
    from("README.md")
}
val unpackedNativeDialogs = layout.buildDirectory.dir("packaged-native")
val unpackNativeDialogs = tasks.register<Sync>("unpackNativeDialogs") {
    dependsOn(packageNativeDialogs)
    from(packageNativeDialogs.map { zipTree(it.archiveFile) })
    into(unpackedNativeDialogs)
}
val packagedDialogsLibrary = unpackedNativeDialogs.zip(dialogsNativeLibrary) { directory, library ->
    directory.file("lib/${library.asFile.name}")
}

val nativeDialogSmokeTest = tasks.register<JavaExec>("nativeDialogSmokeTest") {
    group = "verification"
    description = "Verifies native open/save selection, real file IO, and cancellation."
    dependsOn(tasks.named("jvmTestClasses"), unpackNativeDialogs)
    classpath = sourceSets["jvmTest"].runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.service.dialogs.NativeDialogSmokeMainKt")
    jvmArgs("-Djava.awt.headless=false", "--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.dialogs.library", packagedDialogsLibrary.get().asFile.absolutePath)
    systemProperty("kweb.dialogs.ui.evidence", layout.buildDirectory.dir("reports/native-dialog-ui").get().asFile.absolutePath)
    timeout.set(Duration.ofSeconds(120))
}

tasks.named("check") {
    dependsOn(nativeDialogSmokeTest)
}

val nativeRelease = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val hostPlatform = skikoTarget.get().substringBefore('-')
val cefRuntime = when (hostPlatform) {
    "macos" -> nativeRelease.file("KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework")
    "windows" -> nativeRelease.file("libcef.dll")
    else -> nativeRelease.file("libcef.so")
}
val browserSubprocess = when (hostPlatform) {
    "macos" -> nativeRelease.file("KWebShell.app/Contents/Frameworks/KWebShell Helper.app/Contents/MacOS/KWebShell Helper")
    "windows" -> nativeRelease.file("KWebShell.exe")
    else -> nativeRelease.file("KWebShell")
}
val cefResources = if (hostPlatform == "macos") {
    nativeRelease.dir("KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Resources")
} else nativeRelease
val engineLibraryName = when (hostPlatform) {
    "macos" -> "libkwebshell_engine.dylib"
    "windows" -> "kwebshell_engine.dll"
    else -> "libkwebshell_engine.so"
}
val engineLibrary = rootProject.layout.projectDirectory.file("kweb-cef-native/build/native/contract/$engineLibraryName")
val nativeDialogIntegrationTest = tasks.register<JavaExec>("nativeDialogIntegrationTest") {
    group = "verification"
    description = "Runs native selection and scoped file IO through the real exact-origin CEF bridge."
    dependsOn("jvmTestClasses", unpackNativeDialogs, ":kweb-cef-native:buildNative", ":kweb-desktop:jar", generateDialogsBridge)
    mustRunAfter(nativeDialogSmokeTest, ":kweb-desktop:engineIntegrationTest", ":kweb-service-window-controls:windowControlsIntegrationTest")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    classpath = sourceSets["jvmTest"].runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.service.dialogs.DialogsIntegrationMainKt")
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=false")
    systemProperty("kweb.native.library.path", engineLibrary.asFile.absolutePath)
    systemProperty("kweb.dialogs.library", packagedDialogsLibrary.get().asFile.absolutePath)
    systemProperty("kweb.dialogs.integration.root", layout.buildDirectory.dir("dialogs-integration").get().asFile.absolutePath)
    systemProperty("kweb.dialogs.ui.evidence", layout.buildDirectory.dir("reports/dialogs-cef-ui").get().asFile.absolutePath)
    systemProperty("kweb.dialogs.bridge.javascript", generatedBridgeDirectory.get().file("DialogsBridgeBridge.js").asFile.absolutePath)
    systemProperty("kweb.engine.cef.runtime.path", cefRuntime.asFile.absolutePath)
    systemProperty("kweb.engine.subprocess.path", browserSubprocess.asFile.absolutePath)
    systemProperty("kweb.engine.resources.path", cefResources.asFile.absolutePath)
    systemProperty("kweb.engine.locales.path", (if (hostPlatform == "macos") cefResources else nativeRelease.dir("locales")).asFile.absolutePath)
    timeout.set(Duration.ofMinutes(4))
}
tasks.named("check") { dependsOn(nativeDialogIntegrationTest) }
