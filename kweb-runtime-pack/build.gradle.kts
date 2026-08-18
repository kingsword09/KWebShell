import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.process.CommandLineArgumentProvider
import java.util.Locale

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

private class HostRuntimePayloadArguments(
    @get:Input
    @get:Optional
    val cefRoot: Provider<String>,
    @get:Input
    @get:Optional
    val requestedTarget: Provider<String>,
    @get:Input
    val target: String,
    @get:Input
    val productVersion: String,
    @get:Input
    val manifestPath: String,
    @get:Input
    val nativeReleasePath: String,
    @get:Input
    val nativeContractPath: String,
    @get:Input
    val outputArchivePath: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val cefRootValue = cefRoot.orNull ?: throw GradleException(
            "Missing -PcefRoot=<absolute-path-to-extracted-cef-distribution> for runtime payload.",
        )
        val cefRootFile = File(cefRootValue)
        if (!cefRootFile.isAbsolute) {
            throw GradleException("-PcefRoot must be an absolute path: '$cefRootValue'.")
        }
        val outputFile = File(outputArchivePath)
        val outputParent = outputFile.parentFile
            ?: throw GradleException("The runtime payload output must have a parent directory: '$outputFile'.")
        if (!outputParent.isDirectory && !outputParent.mkdirs() && !outputParent.isDirectory) {
            throw GradleException("Unable to create the runtime payload output directory: '$outputParent'.")
        }
        requestedTarget.orNull?.let { requested ->
            if (requested != target) {
                throw GradleException(
                    "-PkwebTarget '$requested' does not match the host runtime payload target '$target'.",
                )
            }
        }
        return listOf(
            "payload-build",
            manifestPath,
            target,
            productVersion,
            cefRootFile.absolutePath,
            nativeReleasePath,
            nativeContractPath,
            outputArchivePath,
        )
    }
}

private class HostRuntimeReleaseBuildArguments(
    @get:Input
    @get:Optional
    val privateKey: Provider<String>,
    @get:Input
    @get:Optional
    val publicKey: Provider<String>,
    @get:Input
    @get:Optional
    val outputPack: Provider<String>,
    @get:Input
    val manifestPath: String,
    @get:Input
    val target: String,
    @get:Input
    val productVersion: String,
    @get:Input
    val payloadPath: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val privateKeyFile = requireAbsoluteProperty(
            privateKey,
            "kwebReleasePrivateKey",
            "absolute-path-to-ed25519-private-key.pk8",
        )
        val publicKeyFile = requireAbsoluteProperty(
            publicKey,
            "kwebReleasePublicKey",
            "absolute-path-to-ed25519-public-key.der",
        )
        val outputFile = requireAbsoluteProperty(
            outputPack,
            "kwebRuntimeReleaseOutput",
            "absolute-path-to-output-release.zip",
        )
        return listOf(
            "release-build",
            manifestPath,
            target,
            productVersion,
            payloadPath,
            privateKeyFile.absolutePath,
            publicKeyFile.absolutePath,
            outputFile.absolutePath,
        )
    }

    private fun requireAbsoluteProperty(
        provider: Provider<String>,
        name: String,
        example: String,
    ): File {
        val value = provider.orNull ?: throw GradleException("Missing -P$name=<$example>.")
        val file = File(value)
        if (!file.isAbsolute) throw GradleException("-P$name must be an absolute path: '$value'.")
        return file
    }
}

private class RuntimeReleaseVerifyArguments(
    @get:Input
    @get:Optional
    val target: Provider<String>,
    @get:Input
    @get:Optional
    val pack: Provider<String>,
    @get:Input
    @get:Optional
    val publicKey: Provider<String>,
    @get:Input
    val manifestPath: String,
    @get:Input
    val productVersion: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val targetValue = target.orNull ?: throw GradleException(
            "Missing -PkwebTarget=<windows-x64|windows-arm64|macos-x64|macos-arm64|linux-x64|linux-arm64>.",
        )
        val packFile = requireAbsolute(pack, "kwebRuntimeRelease", "absolute-path-to-release.zip")
        val publicKeyFile = requireAbsolute(
            publicKey,
            "kwebReleasePublicKey",
            "absolute-path-to-ed25519-public-key.der",
        )
        return listOf(
            "release-verify",
            manifestPath,
            targetValue,
            productVersion,
            packFile.absolutePath,
            publicKeyFile.absolutePath,
        )
    }

    private fun requireAbsolute(
        provider: Provider<String>,
        name: String,
        example: String,
    ): File {
        val value = provider.orNull ?: throw GradleException("Missing -P$name=<$example>.")
        val file = File(value)
        if (!file.isAbsolute) throw GradleException("-P$name must be an absolute path: '$value'.")
        return file
    }
}

private fun detectHostRuntimeTarget(): String {
    val operatingSystem = System.getProperty("os.name").lowercase(Locale.ROOT)
    val os = when {
        operatingSystem.startsWith("windows") -> "windows"
        operatingSystem.startsWith("mac") || operatingSystem.startsWith("darwin") -> "macos"
        operatingSystem.startsWith("linux") -> "linux"
        else -> throw GradleException("Unsupported host operating system for runtime payload: '$operatingSystem'.")
    }
    val architecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> throw GradleException(
            "Unsupported host architecture for runtime payload: '${System.getProperty("os.arch")}'.",
        )
    }
    return "$os-$architecture"
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

dependencies {
    api(project(":kweb-core"))
    implementation(libs.commons.compress)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

val cefSourcePatchManifest =
    rootProject.layout.projectDirectory.file("runtime/cef/extension-adapter-patch.json")
val cefSourceBuildTool = rootProject.layout.projectDirectory.file("runtime/cef/build-custom-runtime.py")
val cefSourceBuildTests = rootProject.layout.projectDirectory.dir("runtime/cef/tests")
val hostRuntimeTarget = detectHostRuntimeTarget()
val hostRuntimePayloadDirectory = layout.buildDirectory.dir("runtime-payload")
val hostRuntimePayloadArchive = hostRuntimePayloadDirectory.map {
    it.file("KWebShell-${project.version}-$hostRuntimeTarget.zip")
}
val nativeProjectDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native")
val nativeReleaseDirectory = nativeProjectDirectory.dir("build/native/Release")
val nativeContractDirectory = nativeProjectDirectory.dir("build/native/contract")
val runtimeCatalogPath = rootProject.layout.projectDirectory.file("runtime/cef-runtime.json")

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

val buildHostRuntimePayload = tasks.register<JavaExec>("buildHostRuntimePayload") {
    group = "build"
    description = "Builds and independently verifies the deterministic host runtime payload."
    dependsOn(":kweb-cef-native:buildNative")
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")
    inputs.file(runtimeCatalogPath)
    inputs.property("cefRoot", providers.gradleProperty("cefRoot"))
    inputs.files(
        providers.gradleProperty("cefRoot").map { root ->
            listOf(File(root, "LICENSE.txt"), File(root, "CREDITS.html"))
        },
    )
    inputs.dir(nativeReleaseDirectory)
    inputs.dir(nativeContractDirectory)
    outputs.file(hostRuntimePayloadArchive)
    argumentProviders.add(
        HostRuntimePayloadArguments(
            cefRoot = providers.gradleProperty("cefRoot"),
            requestedTarget = providers.gradleProperty("kwebTarget"),
            target = hostRuntimeTarget,
            productVersion = project.version.toString(),
            manifestPath = runtimeCatalogPath.asFile.absolutePath,
            nativeReleasePath = nativeReleaseDirectory.asFile.absolutePath,
            nativeContractPath = nativeContractDirectory.asFile.absolutePath,
            outputArchivePath = hostRuntimePayloadArchive.get().asFile.absolutePath,
        ),
    )
}

tasks.register<JavaExec>("verifyHostRuntimePayload") {
    group = "verification"
    description = "Reopens and verifies the deterministic host runtime payload."
    dependsOn(buildHostRuntimePayload)
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")
    args(
        "payload-verify",
        runtimeCatalogPath.asFile.absolutePath,
        hostRuntimeTarget,
        project.version.toString(),
        hostRuntimePayloadArchive.get().asFile.absolutePath,
    )
    inputs.file(runtimeCatalogPath)
    inputs.file(hostRuntimePayloadArchive)
}

tasks.register<JavaExec>("buildHostRuntimeRelease") {
    group = "build"
    description = "Signs and independently verifies the deterministic host runtime release pack."
    dependsOn(buildHostRuntimePayload)
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")
    inputs.file(runtimeCatalogPath)
    inputs.file(hostRuntimePayloadArchive)
    inputs.file(providers.gradleProperty("kwebReleasePrivateKey"))
    inputs.file(providers.gradleProperty("kwebReleasePublicKey"))
    outputs.file(providers.gradleProperty("kwebRuntimeReleaseOutput"))
    argumentProviders.add(
        HostRuntimeReleaseBuildArguments(
            privateKey = providers.gradleProperty("kwebReleasePrivateKey"),
            publicKey = providers.gradleProperty("kwebReleasePublicKey"),
            outputPack = providers.gradleProperty("kwebRuntimeReleaseOutput"),
            manifestPath = runtimeCatalogPath.asFile.absolutePath,
            target = hostRuntimeTarget,
            productVersion = project.version.toString(),
            payloadPath = hostRuntimePayloadArchive.get().asFile.absolutePath,
        ),
    )
}

tasks.register<JavaExec>("verifyRuntimeRelease") {
    group = "verification"
    description = "Verifies a signed runtime release with one explicit trusted Ed25519 public key."
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.github.kingsword09.kwebshell.runtime.CefRuntimeManifestCliKt")
    inputs.file(runtimeCatalogPath)
    inputs.file(providers.gradleProperty("kwebRuntimeRelease"))
    inputs.file(providers.gradleProperty("kwebReleasePublicKey"))
    argumentProviders.add(
        RuntimeReleaseVerifyArguments(
            target = providers.gradleProperty("kwebTarget"),
            pack = providers.gradleProperty("kwebRuntimeRelease"),
            publicKey = providers.gradleProperty("kwebReleasePublicKey"),
            manifestPath = runtimeCatalogPath.asFile.absolutePath,
            productVersion = project.version.toString(),
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
