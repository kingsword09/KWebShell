import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

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
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":kweb-example-support"))
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.named<Test>("jvmTest") { useJUnitPlatform() }

val bridgeCodegen = configurations.create("bridgeCodegen")
dependencies { bridgeCodegen(project(":kweb-bridge-codegen")) }

val bridgeSchema = layout.projectDirectory.file("src/mainBridge/power-bridge.json")
val generatedBridgeDirectory = layout.buildDirectory.dir("generated/kwebBridge/power")
val generatePowerBridge = tasks.register<JavaExec>("generatePowerBridge") {
    group = "build"
    description = "Generates the KWebPower Kotlin and TypeScript bridge clients."
    classpath = bridgeCodegen
    mainClass.set("io.github.kingsword09.kwebshell.bridge.codegen.MainKt")
    inputs.file(bridgeSchema)
    outputs.dir(generatedBridgeDirectory)
    args(bridgeSchema.asFile.absolutePath, generatedBridgeDirectory.get().asFile.absolutePath)
}
kotlin.sourceSets.named("jvmMain") { kotlin.srcDir(generatedBridgeDirectory) }
tasks.named("compileKotlinJvm") { dependsOn(generatePowerBridge) }

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_power.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_power.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_power.so"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val powerTarget = providers.provider {
    val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
    val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val target = when {
        operatingSystem.startsWith("mac") && architecture in setOf("arm64", "aarch64") -> "macos-arm64"
        operatingSystem.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows-x64"
        operatingSystem.startsWith("linux") && architecture in setOf("amd64", "x86_64") -> "linux-x64"
        else -> throw GradleException("The native shortcut provider is not published for $operatingSystem/$architecture.")
    }
    val requested = providers.gradleProperty("kwebTarget").orNull
    if (requested != null && requested != target) throw GradleException("The power target $requested does not match this host ($target).")
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
    description = "Runs the native C ABI and provider structure tests of the current target."
    dependsOn(buildNative)
    val report = layout.buildDirectory.file("reports/power-native-tests.xml").get().asFile
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
val nativePowerIntegrationTest = tasks.register<JavaExec>("nativePowerIntegrationTest") {
    group = "verification"
    description = "Runs the real native shortcut provider integration."
    dependsOn(tasks.named("jvmTestClasses"), nativeTest)
    classpath = files(jvmTestClasses, jvmTestRuntimeClasspath)
    mainClass.set("io.github.kingsword09.kwebshell.service.power.PowerIntegrationMainKt")
    javaLauncher.set(ffmJava)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.power.native.library.path", nativeLibrary.get().absolutePath)
    systemProperty(
        "kweb.power.integration.root",
        layout.buildDirectory.dir("reports/power-integration").get().asFile.absolutePath,
    )
    systemProperty("kweb.power.target", powerTarget.get())
}
tasks.named("check") { dependsOn(nativePowerIntegrationTest) }

val nativePowerRuntimeZip = tasks.register<Zip>("nativePowerRuntimeZip") {
    group = "distribution"
    description = "Packages only the declared shortcut provider and its versioned C ABI header."
    dependsOn(buildNative)
    archiveFileName.set("kweb-service-power-1.0.0-${powerTarget.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(nativeLibrary) { into("native/${powerTarget.get()}") }
    from(nativeProjectDirectory.file("include/kweb_power.h")) { into("include") }
}
val verifyNativePowerPackage = tasks.register("verifyNativePowerPackage") {
    group = "verification"
    description = "Verifies exact native shortcut package contents and retains its digest."
    dependsOn(nativePowerRuntimeZip)
    val archive = nativePowerRuntimeZip.flatMap { it.archiveFile }
    val library = nativeLibrary.get()
    val target = powerTarget.get()
    val report = layout.buildDirectory.file("reports/power-integration/native-power-package.json").get().asFile
    inputs.file(archive)
    inputs.file(library)
    outputs.file(report)
    doLast {
        val packageFile = archive.get().asFile
        val libraryEntry = "native/$target/${library.name}"
        ZipFile(packageFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(names == setOf(libraryEntry, "include/kweb_power.h")) {
                "Unexpected files in the native power package."
            }
            check(zip.getInputStream(zip.getEntry(libraryEntry)).readBytes().contentEquals(library.readBytes())) {
                "The packaged shortcut library does not match the tested provider."
            }
            check(
                zip.getInputStream(zip.getEntry("include/kweb_power.h")).bufferedReader().readText()
                    .contains("kweb_power_abi_version"),
            ) { "The packaged header does not declare the power ABI revision." }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(packageFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        report.parentFile.mkdirs()
        report.writeText(
            """{"schemaVersion":1,"target":"$target","archive":"${packageFile.name}","sha256":"$digest",""" +
                """"nativeLibrary":"${library.name}","exactContents":true}""" + "\n",
        )
    }
}
tasks.named("check") { dependsOn(verifyNativePowerPackage) }
