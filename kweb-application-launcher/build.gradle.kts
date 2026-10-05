import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.file.RelativePath
import java.io.File
import java.nio.file.LinkOption
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    application
}

kotlin {
    explicitApi()
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("io.github.kingsword09.kwebshell.launcher.KWebApplicationMainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=io.github.kingsword09.kwebshell.desktop",
        "--enable-native-access=ALL-UNNAMED",
    )
}

dependencies {
    implementation(project(":kweb-core"))
    implementation(project(":kweb-desktop"))
    implementation(project(":kweb-compose"))
    implementation(project(":kweb-service-application-lifecycle"))
    implementation(libs.compose.ui.desktop)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

val launcherRuntimeVersion = "25.0.4.1+1"
val bundledJreArchiveName = "OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip"
val bundledJreArchiveSha256 = "4c95451cea98556def2c54f7782933f52a26d4a36bd85e1d59f0364464828b07"
val nativeReleaseDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/Release")
val nativeContractDirectory = rootProject.layout.projectDirectory.dir("kweb-cef-native/build/native/contract")
val launcherImageDirectory = layout.buildDirectory.dir("launcher-image")
val bundledJreArchive = layout.buildDirectory.file("downloads/$bundledJreArchiveName")
val windowsJreDirectory = layout.buildDirectory.dir("windows-jre")
val windowsAppImageDirectory = layout.buildDirectory.dir("windows-app-image")
val windowsAppImage = windowsAppImageDirectory.map { it.dir("KWebShell") }
val windowsCefStageDirectory = layout.buildDirectory.dir("windows-cef-stage")
val windowsCefPayloadArchive = rootProject.layout.projectDirectory.file(
    "kweb-runtime-pack/build/runtime-payload/KWebShell-${project.version}-windows-x64.zip",
)
val windowsIcon = rootProject.layout.projectDirectory.file("runtime/assets/windows/KWebShell.ico")

private fun windowsProcessesForSmoke(
    packageRoot: java.nio.file.Path,
    smokeDataRoot: java.nio.file.Path,
): List<ProcessHandle> {
    val packagePrefix = packageRoot.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/') + "/"
    val smokeDataMarker = smokeDataRoot.toAbsolutePath().normalize().toString().replace('\\', '/').trimEnd('/')
    return ProcessHandle.allProcesses().filter { process ->
        val command = process.info().command().orElse("").replace('\\', '/')
        val commandLine = process.info().commandLine().orElse("").replace('\\', '/')
        command.startsWith(packagePrefix, ignoreCase = true) ||
            commandLine.contains(smokeDataMarker, ignoreCase = true)
    }.toList()
}

private fun deleteWindowsSmokeState(root: java.nio.file.Path) {
    if (!Files.exists(root)) return
    val normalized = root.toAbsolutePath().normalize()
    require(normalized.fileName.toString().equals("KWebShell", ignoreCase = true)) {
        "Refusing to remove a Windows smoke data root with an unexpected leaf name: $normalized"
    }
    var lastFailure: Exception? = null
    repeat(5) { attempt ->
        try {
            Files.walk(normalized).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        runCatching { Files.setAttribute(path, "dos:readonly", false, LinkOption.NOFOLLOW_LINKS) }
                    }
                    Files.deleteIfExists(path)
                }
            }
            return
        } catch (error: Exception) {
            lastFailure = error
            if (attempt < 4) Thread.sleep(500L * (attempt + 1))
        }
    }
    throw GradleException("Unable to remove test-owned Windows launcher smoke state '$normalized'.", lastFailure)
}

private fun verifyWindowsFailedStartup(packageRoot: java.nio.file.Path, reports: File) {
    val dataRoot = reports.toPath().resolve("failed-start-local-app-data/KWebShell")
    val profiles = dataRoot.resolve("profiles")
    Files.createDirectories(profiles)
    Files.writeString(profiles.resolve("primary"), "invalid-profile-directory", StandardOpenOption.CREATE_NEW)
    val stderr = reports.resolve("failed-start-stderr.log")
    val process = ProcessBuilder(
        packageRoot.resolve("runtime/bin/java.exe").toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp", packageRoot.resolve("app").toString() + File.separator + "*",
        "io.github.kingsword09.kwebshell.launcher.KWebApplicationMainKt",
    ).directory(packageRoot.toFile())
        .redirectOutput(reports.resolve("failed-start-stdout.log"))
        .redirectError(stderr)
        .apply { environment()["LOCALAPPDATA"] = dataRoot.parent.toString() }
        .start()
    var exitCode: Int? = null
    var profileFailureObserved = false
    var processesDrained = false
    try {
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            throw GradleException("The packaged JVM did not exit after a real Profile startup failure.")
        }
        exitCode = process.exitValue()
        profileFailureObserved = stderr.readText().contains("The Profile path is not a directory.")
        if (exitCode == 0 || !profileFailureObserved) {
            throw GradleException("The packaged JVM did not report the expected Profile failure (exit=$exitCode).")
        }
        val deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
        while (System.nanoTime() < deadline && windowsProcessesForSmoke(packageRoot, dataRoot).isNotEmpty()) {
            Thread.sleep(250)
        }
        processesDrained = windowsProcessesForSmoke(packageRoot, dataRoot).isEmpty()
        if (!processesDrained) throw GradleException("A packaged JVM or CEF process remained after failed startup.")
    } finally {
        if (process.isAlive) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS)
        windowsProcessesForSmoke(packageRoot, dataRoot).forEach { child ->
            if (child.isAlive) child.destroyForcibly()
            child.onExit().get(10, TimeUnit.SECONDS)
        }
        Files.writeString(reports.toPath().resolve("failed-start-summary.txt"), buildString {
            appendLine("exitCode=$exitCode")
            appendLine("profileFailureObserved=$profileFailureObserved")
            appendLine("processesDrained=$processesDrained")
        })
        deleteWindowsSmokeState(dataRoot)
    }
}

tasks.register("downloadPinnedWindowsJre") {
    group = "distribution"
    description = "Downloads the SHA-256-pinned Temurin 25 Windows x64 JRE archive."
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    outputs.file(bundledJreArchive)
    notCompatibleWithConfigurationCache("Downloads and verifies a hosted Windows runtime archive in a task action.")
    doLast {
        val output = bundledJreArchive.get().asFile.toPath()
        Files.createDirectories(output.parent)
        if (!Files.isRegularFile(output)) {
            val source = URI.create(
                "https://github.com/adoptium/temurin25-binaries/releases/download/" +
                    "jdk-25.0.4.1%2B1/$bundledJreArchiveName",
            ).toURL()
            var lastFailure: Exception? = null
            repeat(3) { attempt ->
                if (!Files.isRegularFile(output)) {
                    val temporary = Files.createTempFile(output.parent, ".temurin-jre-", ".download")
                    val connection = source.openConnection().apply {
                        connectTimeout = 30_000
                        readTimeout = 180_000
                    }
                    try {
                        connection.getInputStream().use { input -> Files.newOutputStream(temporary).use(input::copyTo) }
                        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(temporary))
                            .joinToString("") { "%02x".format(it) }
                        if (digest != bundledJreArchiveSha256) {
                            throw GradleException(
                                "Temurin JRE archive SHA-256 mismatch: expected $bundledJreArchiveSha256, got $digest",
                            )
                        }
                        Files.move(
                            temporary,
                            output,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } catch (error: Exception) {
                        lastFailure = error
                        Files.deleteIfExists(temporary)
                        if (attempt == 2) {
                            throw GradleException("Unable to download the pinned Temurin JRE after three attempts.", error)
                        }
                        Thread.sleep(1_000L * (attempt + 1))
                    } finally {
                        (connection as? java.net.HttpURLConnection)?.disconnect()
                    }
                }
            }
            if (!Files.isRegularFile(output)) throw GradleException("Pinned Temurin JRE download failed.", lastFailure)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(output))
            .joinToString("") { "%02x".format(it) }
        if (digest != bundledJreArchiveSha256) {
            Files.deleteIfExists(output)
            throw GradleException("Temurin JRE archive SHA-256 mismatch: expected $bundledJreArchiveSha256, got $digest")
        }
    }
}

tasks.register("verifyBundledJreArchive") {
    group = "verification"
    description = "Verifies the pinned Temurin Windows x64 JRE archive by SHA-256."
    dependsOn("downloadPinnedWindowsJre")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    inputs.file(bundledJreArchive)
    notCompatibleWithConfigurationCache("Reads the extracted Windows runtime archive during verification.")
    doLast {
        val archive = bundledJreArchive.get().asFile.toPath()
        if (!Files.isRegularFile(archive)) throw GradleException("Missing pinned bundled JRE archive: $archive")
        val digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(archive))
            .joinToString("") { "%02x".format(it) }
        if (digest != bundledJreArchiveSha256) {
            throw GradleException("Temurin JRE archive SHA-256 mismatch: expected $bundledJreArchiveSha256, got $digest")
        }
    }
}

tasks.register<Sync>("stageBundledWindowsJre") {
    group = "distribution"
    description = "Stages the verified Temurin 25.0.4.1+1 Windows x64 JRE for jpackage."
    dependsOn("verifyBundledJreArchive")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    from(zipTree(bundledJreArchive)) {
        eachFile {
            val segments = relativePath.segments
            if (segments.size < 2 || !segments.first().startsWith("jdk-25.0.4.1")) {
                throw GradleException("Unexpected top-level entry in the pinned JRE archive: $relativePath")
            }
            relativePath = RelativePath(true, *segments.drop(1).toTypedArray())
        }
        includeEmptyDirs = false
    }
    into(windowsJreDirectory)
    notCompatibleWithConfigurationCache("Stages the hosted Windows JRE with platform-specific archive rules.")
    doLast {
        val jre = windowsJreDirectory.get().asFile.toPath()
        val java = jre.resolve("bin/java.exe")
        val release = jre.resolve("release")
        if (!Files.isRegularFile(java) || !Files.isRegularFile(release)) {
            throw GradleException("The extracted bundled JRE is incomplete: $jre")
        }
        val metadata = Files.readString(release)
        if (!metadata.contains("JAVA_VERSION=\"25.0.4.1\"") || !metadata.contains("OS_ARCH=\"x86_64\"")) {
            throw GradleException("The extracted runtime is not the pinned Windows x64 Temurin 25.0.4 JRE: $jre")
        }
    }
}

tasks.register("verifyBundledJre") {
    group = "verification"
    description = "Verifies an explicitly supplied extracted pinned Temurin 25 runtime."
    doLast {
        val configured = providers.gradleProperty("kwebBundledJre").orNull
            ?: throw GradleException("Missing -PkwebBundledJre=<absolute-path-to-extracted-temurin-25.0.4.1+1-jre>.")
        val root = file(configured).toPath().toAbsolutePath().normalize()
        val executable = if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) {
            root.resolve("bin/java.exe")
        } else {
            root.resolve("bin/java")
        }
        if (!executable.toFile().isFile) {
            throw GradleException("The pinned bundled JRE is missing its launcher: $executable")
        }
        val release = root.resolve("release")
        if (!release.toFile().isFile || !Files.readString(release).contains("JAVA_VERSION=\"25.0.4.1\"")) {
            throw GradleException("The bundled runtime is not Temurin $launcherRuntimeVersion: $root")
        }
    }
}

tasks.register<Sync>("stageLauncherApplication") {
    group = "distribution"
    description = "Stages the JVM launcher distribution before MSIX packaging."
    dependsOn(tasks.named("installDist"))
    from(layout.buildDirectory.dir("install/kweb-application-launcher"))
    into(launcherImageDirectory)
}

tasks.register("verifyLauncherPayload") {
    group = "verification"
    description = "Verifies the launcher distribution and native subprocess naming contract."
    dependsOn("stageLauncherApplication")
    doLast {
        val image = launcherImageDirectory.get().asFile.toPath()
        val launcherJar = image.resolve("lib/kweb-application-launcher-${project.version}.jar")
        if (!launcherJar.toFile().isFile) throw GradleException("The launcher JAR was not staged: $launcherJar")
        val subprocess = nativeReleaseDirectory.asFile.toPath().resolve("KWebShellCef.exe")
        if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") && !subprocess.toFile().isFile) {
            throw GradleException("The Windows CEF subprocess must be KWebShellCef.exe: $subprocess")
        }
    }
}

tasks.register<Sync>("stageWindowsCefPayload") {
    group = "distribution"
    description = "Stages the independently verified Windows CEF and native payload beside the JVM app-image."
    dependsOn(":kweb-runtime-pack:buildHostRuntimePayload")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    from(zipTree(windowsCefPayloadArchive)) {
        include("runtime/**", "native/**", "licenses/**")
        eachFile {
            val segments = relativePath.segments
            if (segments.firstOrNull() == "runtime") {
                if (segments.size < 2) exclude()
                else relativePath = RelativePath(true, "cef", *segments.drop(1).toTypedArray())
            }
        }
        includeEmptyDirs = false
    }
    into(windowsCefStageDirectory)
    inputs.file(windowsCefPayloadArchive)
}

tasks.register<Exec>("buildWindowsApplicationImage") {
    group = "distribution"
    description = "Builds the Windows JVM launcher app-image with the pinned JRE and verified CEF subprocess payload."
    dependsOn(tasks.named("installDist"), "stageBundledWindowsJre", "stageWindowsCefPayload")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    notCompatibleWithConfigurationCache("Invokes the Windows jpackage tool and mutates an app-image tree.")
    val jpackage = providers.provider {
        val executable = if (System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows")) "jpackage.exe" else "jpackage"
        File(System.getProperty("java.home"), "bin/$executable").absolutePath
    }
    doFirst {
        val destination = windowsAppImageDirectory.get().asFile
        if (destination.exists()) {
            val containsPayload = Files.walk(destination.toPath()).use { paths ->
                paths.anyMatch { path ->
                    !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                }
            }
            if (containsPayload) {
                throw GradleException("The Windows app-image destination contains stale payload; use a clean task output: $destination")
            }
            Files.walk(destination.toPath()).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
            }
        }
    }
    commandLine(
        jpackage.get(),
        "--type", "app-image",
        "--name", "KWebShell",
        "--input", layout.buildDirectory.dir("install/kweb-application-launcher/lib").get().asFile.absolutePath,
        "--main-jar", "kweb-application-launcher-${project.version}.jar",
        "--main-class", "io.github.kingsword09.kwebshell.launcher.KWebApplicationMainKt",
        "--runtime-image", windowsJreDirectory.get().asFile.absolutePath,
        "--dest", windowsAppImageDirectory.get().asFile.absolutePath,
        "--app-version", project.version.toString().substringBefore("-"),
        "--vendor", "KWebShell Authors",
        "--icon", windowsIcon.asFile.absolutePath,
        "--java-options", "--enable-native-access=io.github.kingsword09.kwebshell.desktop",
        "--java-options", "--enable-native-access=ALL-UNNAMED",
    )
    doLast {
        val staged = windowsCefStageDirectory.get().asFile.toPath()
        val destination = windowsAppImage.get().asFile.toPath()
        Files.walk(staged).use { paths ->
            paths.forEach { source ->
                val relative = staged.relativize(source)
                val output = destination.resolve(relative.toString())
                if (Files.isDirectory(source)) Files.createDirectories(output)
                else {
                    Files.createDirectories(output.parent)
                    Files.copy(source, output)
                }
            }
        }
    }
    inputs.dir(layout.buildDirectory.dir("install/kweb-application-launcher/lib"))
    inputs.dir(windowsJreDirectory)
    inputs.file(windowsIcon)
    outputs.dir(windowsAppImage)
}

tasks.register("verifyWindowsApplicationImage") {
    group = "verification"
    description = "Rejects a Windows app-image with missing launcher, bundled JRE, CEF host, or native libraries."
    dependsOn("buildWindowsApplicationImage")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    notCompatibleWithConfigurationCache("Inspects the real Windows jpackage app-image tree.")
    doLast {
        val root = windowsAppImage.get().asFile.toPath()
        val required = listOf(
            "KWebShell.exe",
            "app/KWebShell.cfg",
            "app/kweb-application-launcher-${project.version}.jar",
            "runtime/bin/java.exe",
            "cef/KWebShellCef.exe",
            "cef/libcef.dll",
            "native/kwebshell_engine.dll",
            "native/kwebshell_application_lifecycle.dll",
            "runtime/legal/java.base/LICENSE",
            "runtime/legal/java.base/ASSEMBLY_EXCEPTION",
            "licenses/CEF-LICENSE.txt",
            "licenses/CEF-CREDITS.html",
        )
        val missing = required.filterNot { Files.isRegularFile(root.resolve(it)) }
        if (missing.isNotEmpty()) throw GradleException("The Windows app-image is incomplete: ${missing.joinToString()}")
        val runtime = Files.readString(root.resolve("runtime/release"))
        if (!runtime.contains("JAVA_VERSION=\"25.0.4.1\"") || !runtime.contains("OS_ARCH=\"x86_64\"")) {
            throw GradleException("The generated Windows app-image does not carry the pinned Temurin x64 runtime.")
        }
    }
}

tasks.register("runWindowsApplicationImageSmokeTest") {
    group = "verification"
    description = "Starts the real Windows launcher app-image, observes the Compose window and CEF child, then verifies normal shutdown."
    dependsOn("verifyWindowsApplicationImage")
    onlyIf { System.getProperty("os.name").lowercase(Locale.ROOT).startsWith("windows") }
    notCompatibleWithConfigurationCache("Starts and observes the real Windows launcher and CEF subprocess.")
    doLast {
        val root = windowsAppImage.get().asFile.toPath()
        val launcher = root.resolve("KWebShell.exe")
        val smokeLogs = layout.buildDirectory.dir("reports/windows-launcher-smoke").get().asFile
        smokeLogs.mkdirs()
        verifyWindowsFailedStartup(root, smokeLogs)
        val smokeDataRoot = File(smokeLogs, "local-app-data/KWebShell").toPath()
        val previousCefProcesses = ProcessHandle.allProcesses()
            .filter { process -> process.info().command().orElse("").replace('\\', '/').endsWith("/KWebShellCef.exe", ignoreCase = true) }
            .map { it.pid() }
            .toList()
        val main = ProcessBuilder(launcher.toString())
            .directory(root.toFile())
            .redirectOutput(File(smokeLogs, "stdout.log"))
            .redirectError(File(smokeLogs, "stderr.log"))
            .apply { environment()["LOCALAPPDATA"] = File(smokeLogs, "local-app-data").absolutePath }
            .start()
        var cefProcess: ProcessHandle? = null
        var windowProcess: ProcessHandle? = null
        var failure: Throwable? = null
        var windowObserved = false
        var cefObserved = false
        var processSnapshot: List<String> = emptyList()
        fun updateProcessSnapshot() {
            processSnapshot = windowsProcessesForSmoke(root, smokeDataRoot).map { process ->
                "${process.pid()}:${process.info().command().orElse("")}"
            }.sorted()
        }
        fun writeSmokeSummary() {
            updateProcessSnapshot()
            Files.writeString(
                smokeLogs.toPath().resolve("smoke-summary.txt"),
                buildString {
                    appendLine("launcherPid=${main.pid()}")
                    appendLine("launcherAlive=${main.isAlive}")
                    if (!main.isAlive) appendLine("launcherExit=${runCatching { main.exitValue() }.getOrNull()}")
                    appendLine("windowObserved=$windowObserved")
                    appendLine("windowPid=${windowProcess?.pid()}")
                    appendLine("windowAlive=${windowProcess?.isAlive}")
                    appendLine("cefObserved=$cefObserved")
                    appendLine("cefPid=${cefProcess?.pid()}")
                    appendLine("cefAlive=${cefProcess?.isAlive}")
                    appendLine("packageProcesses=${processSnapshot.joinToString(" | ")}")
                    appendLine("failure=${failure?.message}")
                },
            )
        }
        fun findVisibleWindowProcessId(): Long? {
            val command =
                "\$root=[IO.Path]::GetFullPath(\$env:KWEB_SMOKE_ROOT).TrimEnd(" +
                    "[IO.Path]::DirectorySeparatorChar,[IO.Path]::AltDirectorySeparatorChar) + " +
                    "[IO.Path]::DirectorySeparatorChar; " +
                    "\$p=Get-Process | ForEach-Object { try { " +
                    "\$path=\$_.Path; " +
                    "if (\$_.MainWindowHandle -ne 0 -and \$path -and " +
                    "\$path.StartsWith(\$root,[StringComparison]::OrdinalIgnoreCase)) { \$_.Id } " +
                    "} catch {} } | Select-Object -First 1; " +
                    "if (\$p) { Write-Output \$p; exit 0 } else { exit 1 }"
            val probe = ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-Command", command,
            ).apply {
                environment()["KWEB_SMOKE_ROOT"] = root.toString()
                redirectErrorStream(true)
            }.start()
            val output = probe.inputStream.bufferedReader().use { it.readText().trim() }
            if (!probe.waitFor(15, TimeUnit.SECONDS) || probe.exitValue() != 0) return null
            return output.toLongOrNull()
        }
        try {
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (System.nanoTime() < deadline) {
                val visiblePid = findVisibleWindowProcessId()
                windowProcess = visiblePid?.let { ProcessHandle.of(it).orElse(null) }
                windowObserved = windowProcess?.isAlive == true
                cefProcess = ProcessHandle.allProcesses().filter { process ->
                    process.pid() !in previousCefProcesses &&
                        process.info().command().orElse("").replace('\\', '/').endsWith("/KWebShellCef.exe", ignoreCase = true)
                }.findFirst().orElse(null)
                cefObserved = cefProcess?.isAlive == true
                if (windowObserved && cefObserved) break
                Thread.sleep(500)
            }
            if (!windowObserved) {
                val exit = if (main.isAlive) "alive" else "exit=${main.exitValue()}"
                throw GradleException(
                    "The Windows launcher app-image did not create a visible Compose window " +
                        "(launcherPid=${main.pid()}, $exit, stdout=${smokeLogs.resolve("stdout.log")}, " +
                        "stderr=${smokeLogs.resolve("stderr.log")}).",
                )
            }
            if (!cefObserved) throw GradleException("The Windows launcher app-image did not start cef/KWebShellCef.exe.")
            val close = ProcessBuilder(
                "powershell.exe", "-NoLogo", "-NoProfile", "-Command",
                "\$p=Get-Process -Id ${windowProcess!!.pid()} -ErrorAction SilentlyContinue; if (\$p) { \$p.CloseMainWindow() | Out-Null }",
            ).start()
            if (!close.waitFor(15, TimeUnit.SECONDS)) close.destroyForcibly()
            if (!main.waitFor(60, TimeUnit.SECONDS)) {
                main.destroyForcibly()
                throw GradleException("The Windows launcher app-image failed to exit after normal window close.")
            }
            if (main.exitValue() != 0) throw GradleException("The Windows launcher exited with ${main.exitValue()}.")
            val cefDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (System.nanoTime() < cefDeadline && cefProcess?.isAlive == true) Thread.sleep(250)
            if (cefProcess?.isAlive == true) throw GradleException("The CEF subprocess remained alive after normal launcher shutdown.")
            val processDeadline = System.nanoTime() + Duration.ofSeconds(30).toNanos()
            while (System.nanoTime() < processDeadline && windowsProcessesForSmoke(root, smokeDataRoot).isNotEmpty()) Thread.sleep(250)
            updateProcessSnapshot()
            if (processSnapshot.isNotEmpty()) {
                throw GradleException("Windows app-image processes remained after normal launcher shutdown: ${processSnapshot.joinToString(" | ")}")
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            if (main.isAlive) main.destroyForcibly()
            windowsProcessesForSmoke(root, smokeDataRoot).forEach { process -> if (process.isAlive) process.destroyForcibly() }
            windowProcess?.onExit()?.get(10, TimeUnit.SECONDS)
            cefProcess?.onExit()?.get(10, TimeUnit.SECONDS)
            windowsProcessesForSmoke(root, smokeDataRoot).forEach { process ->
                runCatching { process.onExit().get(10, TimeUnit.SECONDS) }
            }
            writeSmokeSummary()
            deleteWindowsSmokeState(smokeDataRoot)
        }
    }
}
