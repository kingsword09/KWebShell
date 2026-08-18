import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

val desktopModuleName = "io.github.kingsword09.kwebshell.desktop"

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
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
    api(project(":kweb-core"))
    implementation(project(":kweb-bridge"))
    implementation(project(":kweb-extensions"))
    implementation(libs.compose.ui.desktop)
    implementation(libs.kotlinx.coroutines.core)
    runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-${skikoTarget.get()}:${libs.versions.skiko.get()}")
    testImplementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val desktopJar = tasks.named<Jar>("jar") {
    manifest.attributes["Automatic-Module-Name"] = desktopModuleName
}
val desktopTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/test"),
    layout.buildDirectory.dir("classes/java/test"),
)
val namedDesktopDependencies = files(
    providers.provider {
        val excluded = sourceSets.main.get().output.files.mapTo(hashSetOf()) { it.absoluteFile }
        excluded += desktopTestClasses.files.map(File::getAbsoluteFile)
        sourceSets.test.get().runtimeClasspath.files.filterNot { it.absoluteFile in excluded }
    },
)
val namedDesktopClasspath = files(desktopTestClasses, namedDesktopDependencies)
val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies {
    bridgeCodegen(project(":kweb-bridge-codegen"))
}

val conformanceBridgeSchema = layout.projectDirectory.file("src/testBridge/conformance-bridge.json")
val extensionLifecycleFixture =
    rootProject.layout.projectDirectory.dir("kweb-cef-native/tests/fixtures/mv3-core")
val mv3LifecycleFixture =
    rootProject.layout.projectDirectory.dir("kweb-cef-native/tests/fixtures/mv3-lifecycle")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/conformance")
val generateConformanceBridge = tasks.register<JavaExec>("generateConformanceBridge") {
    group = "build"
    description = "Generates the typed bridge conformance Kotlin and browser clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(conformanceBridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(conformanceBridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}
val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyConformanceBridgeTypescript = tasks.register<Exec>("verifyConformanceBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated TypeScript bridge client with the pinned compiler."
    dependsOn(generateConformanceBridge)
    inputs.file(generatedBridgeDirectory.map { it.file("ConformanceBridgeBridge.ts") })
    inputs.file(rootProject.layout.projectDirectory.file("package-lock.json"))
    inputs.file(typescriptCompiler)
    commandLine(
        "node",
        typescriptCompiler.asFile.absolutePath,
        "--noEmit",
        "--strict",
        "--target", "ES2022",
        "--module", "ES2022",
        "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("ConformanceBridgeBridge.ts").asFile.absolutePath,
    )
}

kotlin.sourceSets.named("test") {
    kotlin.srcDir(generatedBridgeDirectory)
}

tasks.named("compileTestKotlin") {
    dependsOn(generateConformanceBridge)
}

val nativeEngineLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_engine.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_engine.dylib"
        else -> "libkwebshell_engine.so"
    }
    rootProject.layout.projectDirectory
        .file("kweb-cef-native/build/native/contract/$fileName")
        .asFile
}

tasks.test {
    useJUnitPlatform()
    dependsOn(desktopJar)
    val modulePath = desktopJar.get().archiveFile.get().asFile.absolutePath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.desktop.module.path", modulePath)
    systemProperty("kweb.desktop.named.classpath", namedDesktopClasspath.asPath)
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
val engineIntegrationClasspath = namedDesktopDependencies
val integrationJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val expectCustomExtensionRuntime = providers.gradleProperty("kwebExpectCustomExtensionRuntime")
    .map { value ->
        value.toBooleanStrictOrNull() ?: throw GradleException(
            "-PkwebExpectCustomExtensionRuntime must be exactly 'true' or 'false'.",
        )
    }
    .orElse(false)
val cleanEngineIntegration = tasks.register<Delete>("cleanEngineIntegration") {
    delete(engineIntegrationRoot)
}
val engineIntegrationJavaCommand = buildList {
    add(integrationJava.get().executablePath.asFile.absolutePath)
    add("--module-path=${desktopJar.get().archiveFile.get().asFile.absolutePath}")
    add("--patch-module=$desktopModuleName=${desktopTestClasses.asPath}")
    add("--add-modules=$desktopModuleName,java.net.http,jdk.httpserver")
    add("--enable-native-access=$desktopModuleName")
    add("-Djava.awt.headless=false")
    add("-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}")
    add("-Dkweb.engine.integration.root=${engineIntegrationRoot.get().asFile.absolutePath}")
    add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
    add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
    add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
    add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
    add(
        "-Dkweb.engine.integration.bridge.javascript=" +
            generatedBridgeDirectory.get().file("ConformanceBridgeBridge.js").asFile.absolutePath,
    )
    add("-Dkweb.engine.integration.extension.path=${extensionLifecycleFixture.asFile.absolutePath}")
    add("-Dkweb.engine.integration.lifecycle.v1=${mv3LifecycleFixture.dir("v1").asFile.absolutePath}")
    add("-Dkweb.engine.integration.lifecycle.v2=${mv3LifecycleFixture.dir("v2").asFile.absolutePath}")
    add("-Dkweb.engine.integration.expect.custom.extension.runtime=${expectCustomExtensionRuntime.get()}")
    add("-Dkweb.desktop.module.path=${desktopJar.get().archiveFile.get().asFile.absolutePath}")
    add("-Dkweb.desktop.test.classes=${desktopTestClasses.asPath}")
    add("-Dkweb.desktop.integration.classpath=${engineIntegrationClasspath.asPath}")
    add("-cp")
    add(engineIntegrationClasspath.asPath)
    add("-m")
    add("$desktopModuleName/io.github.kingsword09.kwebshell.desktop.internal.NativeEngineIntegrationMainKt")
    add("coordinator")
}
val extensionLifecycleIntegrationJavaCommand =
    engineIntegrationJavaCommand.dropLast(1) + "extension-lifecycle-coordinator"

val engineIntegrationTest = tasks.register<Exec>("engineIntegrationTest") {
    group = "verification"
    description = "Runs the real in-process JVM/CEF engine contract in isolated JVMs."
    dependsOn(
        cleanEngineIntegration,
        generateConformanceBridge,
        tasks.testClasses,
        desktopJar,
        ":kweb-cef-native:buildNative",
    )
    mustRunAfter(tasks.test, ":kweb-cef-native:nativeTest")

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
    inputs.file(generatedBridgeDirectory.map { it.file("ConformanceBridgeBridge.js") })
    inputs.dir(extensionLifecycleFixture)

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

val extensionLifecycleIntegrationTest = tasks.register<Exec>("extensionLifecycleIntegrationTest") {
    group = "verification"
    description = "Runs install, update, reload, restart, isolation, and uninstall against patched CEF."
    dependsOn(
        cleanEngineIntegration,
        generateConformanceBridge,
        tasks.testClasses,
        desktopJar,
        ":kweb-cef-native:buildNative",
    )
    mustRunAfter(tasks.test, ":kweb-cef-native:nativeTest", engineIntegrationTest)

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
    inputs.dir(mv3LifecycleFixture)

    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(
            listOf(
                "xvfb-run",
                "--auto-servernum",
                "--server-args=-screen 0 1280x1024x24",
            ) + extensionLifecycleIntegrationJavaCommand,
        )
    } else {
        commandLine(extensionLifecycleIntegrationJavaCommand)
    }
}

tasks.named("check") {
    dependsOn(verifyConformanceBridgeTypescript)
    dependsOn(engineIntegrationTest)
}
