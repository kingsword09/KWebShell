import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

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

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_25) } }
    sourceSets {
        commonMain.dependencies {
            api(project(":kweb-services-core"))
            api(project(":kweb-bridge"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.compose.ui.desktop)
        }
        jvmTest.dependencies {
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-${skikoTarget.get()}:${libs.versions.skiko.get()}")
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.compose.ui.desktop)
            implementation(project(":kweb-example-support"))
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies { bridgeCodegen(project(":kweb-bridge-codegen")) }

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/menus-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/menus")
val generateMenusBridge = tasks.register<JavaExec>("generateMenusBridge") {
    group = "build"
    description = "Generates the KWebMenus Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(bridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(bridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(generatedBridgeDirectory) }
tasks.named("compileKotlinJvm") { dependsOn(generateMenusBridge) }

val typescriptCompiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
val verifyMenusBridgeTypescript = tasks.register<Exec>("verifyMenusBridgeTypescript") {
    group = "verification"
    description = "Compiles the generated KWebMenus TypeScript bridge."
    dependsOn(generateMenusBridge)
    commandLine(
        "node", typescriptCompiler.asFile.absolutePath, "--noEmit", "--strict",
        "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedBridgeDirectory.get().file("MenusBridgeBridge.ts").asFile.absolutePath,
    )
}
tasks.named<Test>("jvmTest") { useJUnitPlatform(); dependsOn(generateMenusBridge) }
tasks.named("check") { dependsOn(verifyMenusBridgeTypescript) }

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_menus.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_menus.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_menus.so"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val menusTarget = providers.provider {
    val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
    val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val target = when {
        operatingSystem.startsWith("mac") && architecture in setOf("arm64", "aarch64") -> "macos-arm64"
        operatingSystem.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows-x64"
        operatingSystem.startsWith("linux") && architecture in setOf("amd64", "x86_64") -> "linux-x64"
        else -> throw GradleException("The native menus provider is not published for $operatingSystem/$architecture.")
    }
    val requested = providers.gradleProperty("kwebTarget").orNull
    if (requested != null && requested != target) throw GradleException("The menus target $requested does not match this host ($target).")
    target
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
    inputs.dir(nativeProjectDirectory)
    inputs.file(nativeBuildDirectory.map { it.file("build.ninja") })
    outputs.dir(nativeBuildDirectory.map { it.dir("contract") })
    commandLine("cmake", "--build", nativeBuildDirectory.get().asFile.absolutePath, "--config", "Release")
}
val nativeTest = tasks.register<Exec>("nativeTest") {
    group = "verification"
    dependsOn(buildNative)
    val report = layout.buildDirectory.file("reports/menus-native-tests.xml").get().asFile
    doFirst { report.parentFile.mkdirs() }
    commandLine("ctest", "--test-dir", nativeBuildDirectory.get().asFile.absolutePath,
        "--output-on-failure", "--output-junit", report.absolutePath)
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
val nativeMenusIntegrationTest = tasks.register<JavaExec>("nativeMenusIntegrationTest") {
    group = "verification"
    description = "Runs the real native menus provider integration."
    dependsOn(tasks.named("jvmTestClasses"), nativeTest)
    classpath = files(jvmTestClasses, jvmTestRuntimeClasspath)
    mainClass.set("io.github.kingsword09.kwebshell.service.menus.MenusIntegrationMainKt")
    javaLauncher.set(ffmJava)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.menus.native.library.path", nativeLibrary.get().absolutePath)
    systemProperty("kweb.menus.integration.root", layout.buildDirectory.dir("reports/menus-integration").get().asFile.absolutePath)
    systemProperty("kweb.menus.target", menusTarget.get())
}
tasks.named("check") { dependsOn(nativeMenusIntegrationTest) }

val nativeMenusRuntimeZip = tasks.register<Zip>("nativeMenusRuntimeZip") {
    group = "distribution"
    description = "Packages only the declared menus provider and its versioned C ABI header."
    dependsOn(buildNative)
    archiveFileName.set("kweb-service-menus-1.0.0-${menusTarget.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(nativeLibrary) { into("native/${menusTarget.get()}") }
    from(nativeProjectDirectory.file("include/kweb_menus.h")) { into("include") }
}
val verifyNativeMenusPackage = tasks.register("verifyNativeMenusPackage") {
    group = "verification"
    description = "Verifies exact native menus package contents and retains its digest."
    dependsOn(nativeMenusRuntimeZip)
    val archive = nativeMenusRuntimeZip.flatMap { it.archiveFile }
    val library = nativeLibrary.get()
    val target = menusTarget.get()
    val report = layout.buildDirectory.file("reports/menus-integration/native-menus-package.json").get().asFile
    inputs.file(archive)
    inputs.file(library)
    outputs.file(report)
    doLast {
        val packageFile = archive.get().asFile
        val libraryEntry = "native/$target/${library.name}"
        ZipFile(packageFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(names == setOf(libraryEntry, "include/kweb_menus.h")) { "Unexpected files in the native menus package." }
            check(zip.getInputStream(zip.getEntry(libraryEntry)).readBytes().contentEquals(library.readBytes())) {
                "The packaged menus library does not match the tested provider."
            }
            check(zip.getInputStream(zip.getEntry("include/kweb_menus.h")).bufferedReader().readText().contains("kweb_menus_abi_version"))
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(packageFile.readBytes()).joinToString("") { "%02x".format(it) }
        report.parentFile.mkdirs()
        report.writeText("""{"schemaVersion":1,"target":"$target","archive":"${packageFile.name}","sha256":"$digest","nativeLibrary":"${library.name}","exactContents":true}""" + "\n")
    }
}
tasks.named("check") { dependsOn(verifyNativeMenusPackage) }
