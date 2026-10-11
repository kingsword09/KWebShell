import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Zip
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

val nativeProjectDirectory = layout.projectDirectory.dir("native")
val nativeBuildDirectory = layout.buildDirectory.dir("native")
val nativeLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_system_preferences.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_system_preferences.dylib"
        operatingSystem.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_system_preferences.so"
        else -> throw GradleException("Unsupported desktop operating system '$operatingSystem'.")
    }
    nativeBuildDirectory.get().dir("contract").file(fileName).asFile
}
val preferencesTarget = providers.provider {
    val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
    val architecture = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val target = when {
        operatingSystem.startsWith("mac") && architecture in setOf("arm64", "aarch64") -> "macos-arm64"
        operatingSystem.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows-x64"
        operatingSystem.startsWith("linux") && architecture in setOf("amd64", "x86_64") -> "linux-x64"
        else -> throw GradleException("The system-preferences provider is not published for $operatingSystem/$architecture.")
    }
    val requested = providers.gradleProperty("kwebTarget").orNull
    if (requested != null && requested != target) {
        throw GradleException("The system-preferences target $requested does not match this host ($target).")
    }
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
    val report = layout.buildDirectory.file("reports/preferences-native-tests.xml").get().asFile
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
val nativePreferencesIntegrationTest = tasks.register<JavaExec>("nativePreferencesIntegrationTest") {
    group = "verification"
    description = "Runs the real native system-preferences provider integration."
    dependsOn(tasks.named("jvmTestClasses"), nativeTest)
    classpath = files(jvmTestClasses, jvmTestRuntimeClasspath)
    mainClass.set("io.github.kingsword09.kwebshell.service.preferences.SystemPreferencesIntegrationMainKt")
    javaLauncher.set(ffmJava)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.preferences.native.library.path", nativeLibrary.get().absolutePath)
    systemProperty(
        "kweb.preferences.integration.root",
        layout.buildDirectory.dir("reports/preferences-integration").get().asFile.absolutePath,
    )
    systemProperty("kweb.preferences.target", preferencesTarget.get())
}
tasks.named("check") { dependsOn(nativePreferencesIntegrationTest) }

val nativePreferencesRuntimeZip = tasks.register<Zip>("nativePreferencesRuntimeZip") {
    group = "distribution"
    description = "Packages only the declared system-preferences provider and its versioned C ABI header."
    dependsOn(buildNative)
    archiveFileName.set("kweb-service-system-preferences-1.0.0-${preferencesTarget.get()}.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(nativeLibrary) { into("native/${preferencesTarget.get()}") }
    from(nativeProjectDirectory.file("include/kweb_system_preferences.h")) { into("include") }
}
val verifyNativePreferencesPackage = tasks.register("verifyNativePreferencesPackage") {
    group = "verification"
    description = "Verifies exact native system-preferences package contents and retains its digest."
    dependsOn(nativePreferencesRuntimeZip)
    val archive = nativePreferencesRuntimeZip.flatMap { it.archiveFile }
    val library = nativeLibrary.get()
    val target = preferencesTarget.get()
    val report = layout.buildDirectory
        .file("reports/preferences-integration/native-preferences-package.json").get().asFile
    inputs.file(archive)
    inputs.file(library)
    outputs.file(report)
    doLast {
        val packageFile = archive.get().asFile
        val libraryEntry = "native/$target/${library.name}"
        ZipFile(packageFile).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toSet()
            check(names == setOf(libraryEntry, "include/kweb_system_preferences.h")) {
                "Unexpected files in the native system-preferences package."
            }
            check(zip.getInputStream(zip.getEntry(libraryEntry)).readBytes().contentEquals(library.readBytes())) {
                "The packaged system-preferences library does not match the tested provider."
            }
            check(
                zip.getInputStream(zip.getEntry("include/kweb_system_preferences.h")).bufferedReader().readText()
                    .contains("kweb_preferences_abi_version"),
            ) { "The packaged header does not declare the preferences ABI revision." }
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
tasks.named("check") { dependsOn(verifyNativePreferencesPackage) }
