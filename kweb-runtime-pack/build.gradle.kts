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

private class CefSourcePatchArguments(
    @get:Input
    @get:Optional
    val sourceRoot: Provider<String>,
    @get:Input
    val manifestPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val source = sourceRoot.orNull ?: throw GradleException(
            "Missing -PcefSourceRoot=<absolute-path-to-clean-pinned-cef-worktree>.",
        )
        val sourceDirectory = File(source)
        if (!sourceDirectory.isAbsolute) {
            throw GradleException("-PcefSourceRoot must be an absolute path: '$source'.")
        }
        return listOf("source", manifestPath, sourceDirectory.absolutePath)
    }
}

private class CefCustomRuntimeArtifactArguments(
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
            "Missing -PkwebTarget=<macos-arm64|windows-x64|linux-x64>.",
        )
        val archiveValue = archive.orNull ?: throw GradleException(
            "Missing -PkwebCustomCefArchive=<absolute-path-to-custom-cef-zip>.",
        )
        val archiveFile = File(archiveValue)
        if (!archiveFile.isAbsolute) {
            throw GradleException("-PkwebCustomCefArchive must be an absolute path: '$archiveValue'.")
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

val cefSourcePatchManifest =
    rootProject.layout.projectDirectory.file("runtime/cef/extension-adapter-patch.json")
val cefSourceBuildTool = rootProject.layout.projectDirectory.file("runtime/cef/build-custom-runtime.py")
val cefSourceBuildTests = rootProject.layout.projectDirectory.dir("runtime/cef/tests")

tasks.test {
    useJUnitPlatform()
    inputs.file(cefSourcePatchManifest)
    inputs.dir(rootProject.layout.projectDirectory.dir("runtime/cef/patches"))
    systemProperty("kweb.cef.source.patch.manifest", cefSourcePatchManifest.asFile.absolutePath)
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

val verifyCefSourcePatchManifest = tasks.register<JavaExec>("verifyCefSourcePatchManifest") {
    group = "verification"
    description = "Validates the pinned CEF extension adapter patch manifest and patch digest."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefSourcePatchManifestCliKt")
    args("manifest", cefSourcePatchManifest.asFile.absolutePath)
    inputs.file(cefSourcePatchManifest)
    inputs.dir(rootProject.layout.projectDirectory.dir("runtime/cef/patches"))
}

tasks.register<JavaExec>("verifyCefSourcePatchTree") {
    group = "verification"
    description = "Verifies the extension adapter patch against a clean pinned CEF source worktree."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefSourcePatchManifestCliKt")
    inputs.file(cefSourcePatchManifest)
    inputs.dir(rootProject.layout.projectDirectory.dir("runtime/cef/patches"))
    argumentProviders.add(
        CefSourcePatchArguments(
            sourceRoot = providers.gradleProperty("cefSourceRoot"),
            manifestPath = cefSourcePatchManifest.asFile.absolutePath,
        ),
    )
}

tasks.register<JavaExec>("verifyCefCustomRuntimeArtifact") {
    group = "verification"
    description = "Verifies a custom CEF ZIP, packaged libcef, ABI header, exports, and fingerprint evidence."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefSourcePatchManifestCliKt")
    inputs.file(cefSourcePatchManifest)
    argumentProviders.add(
        CefCustomRuntimeArtifactArguments(
            target = providers.gradleProperty("kwebTarget"),
            archive = providers.gradleProperty("kwebCustomCefArchive"),
            manifestPath = cefSourcePatchManifest.asFile.absolutePath,
        ),
    )
}

tasks.register<JavaExec>("verifyCefCustomRuntimePublication") {
    group = "verification"
    description = "Requires checksum-pinned custom CEF artifacts for all three desktop lifecycle targets."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefSourcePatchManifestCliKt")
    args("publication", cefSourcePatchManifest.asFile.absolutePath)
    inputs.file(cefSourcePatchManifest)
}

val verifyCefSourceBuildTool = tasks.register<Exec>("verifyCefSourceBuildTool") {
    group = "verification"
    description = "Runs the cross-platform custom CEF source-build orchestration tests."
    inputs.file(cefSourceBuildTool)
    inputs.dir(cefSourceBuildTests)
    inputs.file(cefSourcePatchManifest)
    workingDir(rootProject.layout.projectDirectory)
    commandLine(
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "python" else "python3",
        "-m",
        "unittest",
        "discover",
        "-s", cefSourceBuildTests.asFile.absolutePath,
        "-p", "test_*.py",
    )
}

tasks.named("check") {
    dependsOn(verifyCefSourcePatchManifest)
    dependsOn(verifyCefSourceBuildTool)
}
