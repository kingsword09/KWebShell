import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.JavaExec
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
            api(project(":kweb-core"))
            api(project(":kweb-services-core"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

val nativeLifecycleLibrary = providers.systemProperty("os.name").map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_application_lifecycle.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_application_lifecycle.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_application_lifecycle.so"
        else -> throw GradleException("Unsupported application lifecycle operating system '$name'.")
    }
    rootProject.layout.projectDirectory.file("kweb-cef-native/build/native/contract/$fileName").asFile
}
val lifecycleIntegrationReport = layout.buildDirectory.file("reports/application-lifecycle/application-lifecycle-report.json")
val lifecycleIntegrationJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}
val lifecycleJvmTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
)
val lifecycleIntegrationClasspath = files(
    lifecycleJvmTestClasses,
    configurations.named("jvmTestRuntimeClasspath"),
)

tasks.register<JavaExec>("applicationLifecycleIntegrationTest") {
    group = "verification"
    description = "Runs the real native application lifecycle two-process lease and activation integration test."
    dependsOn(":kweb-cef-native:buildNative", tasks.named("jvmTestClasses"))
    classpath = lifecycleIntegrationClasspath
    mainClass.set("io.github.kingsword09.kwebshell.service.applicationlifecycle.ApplicationLifecycleIntegrationMainKt")
    javaLauncher.set(lifecycleIntegrationJava)
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("kweb.application.lifecycle.native.library.path", nativeLifecycleLibrary.get().absolutePath)
    systemProperty("kweb.application.lifecycle.report", lifecycleIntegrationReport.get().asFile.absolutePath)
    systemProperty("kweb.application.lifecycle.target", providers.systemProperty("kwebTarget").orElse("host").get())
    inputs.file(nativeLifecycleLibrary)
    outputs.file(lifecycleIntegrationReport)
}
