import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
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
            implementation(libs.kotlinx.serialization.json)
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
            implementation(project(":kweb-desktop"))
            implementation(project(":kweb-example-support"))
            implementation(libs.compose.ui.desktop)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies {
    bridgeCodegen(project(":kweb-bridge-codegen"))
}
val bridgeSchema = layout.projectDirectory.file("src/mainBridge/window-controls-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/windowControls")
val generateWindowControlsBridge = tasks.register<JavaExec>("generateWindowControlsBridge") {
    group = "build"
    description = "Generates the KWebWindowControls Kotlin and browser bridge clients."
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
    dependsOn(generateWindowControlsBridge)
}

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyWindowControlsBridgeTypescript = tasks.register<Exec>("verifyWindowControlsBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebWindowControls TypeScript bridge."
    dependsOn(generateWindowControlsBridge)
    inputs.file(generatedBridgeDirectory.map { it.file("WindowControlsBridgeBridge.ts") })
    inputs.file(typescriptCompiler)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath,
        "--noEmit", "--strict", "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("WindowControlsBridgeBridge.ts").asFile.absolutePath,
    )
}

tasks.named<Test>("jvmTest") {
    dependsOn(generateWindowControlsBridge)
}

tasks.named("check") {
    dependsOn(verifyWindowControlsBridgeTypescript)
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
            "KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework",
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
    if (name.lowercase(Locale.ROOT).startsWith("mac")) nativeResources.get()
    else nativeReleaseDirectory.dir("locales").asFile
}
val integrationRoot = layout.buildDirectory.dir("window-controls-integration")
val integrationClasspath = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
    configurations.named("jvmTestRuntimeClasspath"),
)
val integrationJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) }
val integrationCommand = providers.provider {
    buildList {
        add(integrationJava.get().executablePath.asFile.absolutePath)
        add("--enable-native-access=ALL-UNNAMED")
        add("-Djava.awt.headless=false")
        add("-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}")
        add("-Dkweb.window-controls.integration.root=${integrationRoot.get().asFile.absolutePath}")
        add("-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}")
        add("-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}")
        add("-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}")
        add("-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}")
        add("-Dkweb.window-controls.bridge.javascript=${generatedBridgeDirectory.get().file("WindowControlsBridgeBridge.js").asFile.absolutePath}")
        add("-cp")
        add(integrationClasspath.asPath)
        add("io.github.kingsword09.kwebshell.service.windowcontrols.WindowControlsIntegrationMainKt")
    }
}
val cleanIntegration = tasks.register<Delete>("cleanWindowControlsIntegration") {
    delete(integrationRoot)
}
val windowControlsIntegrationTest = tasks.register<Exec>("windowControlsIntegrationTest") {
    group = "verification"
    description = "Runs real ComposeWindow and exact-origin KWebWindowControls integration."
    dependsOn(
        cleanIntegration,
        tasks.named("jvmTestClasses"),
        generateWindowControlsBridge,
        ":kweb-desktop:jar",
        ":kweb-cef-native:buildNative",
    )
    mustRunAfter(
        ":kweb-desktop:engineIntegrationTest",
        ":kweb-compose:composeIntegrationTest",
        ":kweb-electron-migration:electronMigrationIntegrationTest",
    )
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
    inputs.file(generatedBridgeDirectory.get().file("WindowControlsBridgeBridge.js"))
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(listOf("xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x1024x24") + integrationCommand.get())
    } else {
        commandLine(integrationCommand.get())
    }
}
tasks.named("check") {
    dependsOn(windowControlsIntegrationTest)
}
