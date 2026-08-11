import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider

private class CefRuntimeArtifactArguments(
    @get:Input
    @get:Optional
    val target: Provider<String>,
    @get:Input
    @get:Optional
    val archive: Provider<String>,
    @get:Input
    val manifestPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val targetValue = target.orNull ?: throw GradleException(
            "Missing -PkwebTarget=<windows-x64|windows-arm64|macos-x64|macos-arm64|linux-x64|linux-arm64>.",
        )
        val archiveValue = archive.orNull ?: throw GradleException(
            "Missing -PcefRuntimeArchive=<absolute-path-to-cef-archive>.",
        )
        val archiveFile = File(archiveValue)
        if (!archiveFile.isAbsolute) {
            throw GradleException("-PcefRuntimeArchive must be an absolute path: '$archiveValue'.")
        }
        return listOf("artifact", manifestPath, targetValue, archiveFile.absolutePath)
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.register<JavaExec>("verifyCefRuntimeManifest") {
    group = "verification"
    description = "Validates the pinned CEF runtime catalog."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")
    args("manifest", rootProject.layout.projectDirectory.file("runtime/cef-runtime.json").asFile.absolutePath)
}

tasks.register<JavaExec>("verifyCefRuntimeArtifact") {
    group = "verification"
    description = "Verifies one downloaded CEF archive against the pinned runtime catalog."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")

    argumentProviders.add(
        CefRuntimeArtifactArguments(
            target = providers.gradleProperty("kwebTarget"),
            archive = providers.gradleProperty("cefRuntimeArchive"),
            manifestPath = rootProject.layout.projectDirectory.file("runtime/cef-runtime.json").asFile.absolutePath,
        ),
    )
}
