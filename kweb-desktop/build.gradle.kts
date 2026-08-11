import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Locale

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    api(project(":kweb-core"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val nativeJniLibrary = providers.systemProperty("os.name").map { operatingSystem ->
    val fileName = when {
        operatingSystem.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_jni.dll"
        operatingSystem.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_jni.dylib"
        else -> "libkwebshell_jni.so"
    }
    rootProject.layout.projectDirectory
        .file("kweb-cef-native/build/native/contract/$fileName")
        .asFile
}

tasks.test {
    useJUnitPlatform()
    dependsOn(":kweb-cef-native:buildNative")
    inputs.file(nativeJniLibrary)
    systemProperty(
        "kweb.native.library.path",
        nativeJniLibrary.map { it.absolutePath }.get(),
    )
}
