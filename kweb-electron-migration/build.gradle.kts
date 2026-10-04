import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.jvm.toolchain.JavaLanguageVersion
import java.io.File
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
        }
        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            runtimeOnly(libs.junit.platform.launcher)
            implementation(project(":kweb-desktop"))
            implementation(project(":kweb-service-app-paths"))
            implementation(project(":kweb-service-files"))
            implementation(project(":kweb-service-clipboard"))
            implementation(project(":kweb-service-shell"))
            implementation(project(":kweb-service-notifications"))
            implementation(project(":kweb-example-support"))
            implementation(libs.compose.ui.desktop)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    systemProperty("kweb.migration.typescript", rootProject.layout.projectDirectory.dir("node_modules/typescript-ast").asFile.absolutePath)
    workingDir(rootProject.projectDir)
}

val fixtureRoot = layout.projectDirectory.dir("src/jvmTest/resources/migration-fixture")
val fixtureManifest = fixtureRoot.file("migration-manifest.json")
val generatedDirectory = layout.buildDirectory.dir("generated/electron-migration")
val inventoryOutput = layout.buildDirectory.file("reports/electron-migration/inventory.json")
val compatibilityOutput = layout.buildDirectory.file("reports/electron-migration/compatibility.json")
val migrationJvmTestClasses = files(
    layout.buildDirectory.dir("classes/kotlin/jvm/test"),
    layout.buildDirectory.dir("classes/java/jvmTest"),
    layout.buildDirectory.dir("classes/kotlin/jvm/main"),
    layout.buildDirectory.dir("classes/java/jvmMain"),
)
val migrationCliClasspath = files(migrationJvmTestClasses, configurations.named("jvmTestRuntimeClasspath"))
val migrationCliJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

val verifyElectronMigrationManifest = tasks.register<JavaExec>("verifyElectronMigrationManifest") {
    group = "verification"
    description = "Validates the strict Electron migration manifest."
    dependsOn(tasks.named("jvmTestClasses"))
    classpath = migrationCliClasspath
    mainClass.set("io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli")
    args("manifest", fixtureManifest.asFile.absolutePath)
}

val generateElectronMigrationPreload = tasks.register<JavaExec>("generateElectronMigrationPreload") {
    group = "build"
    description = "Generates the typed Electron migration preload facade."
    dependsOn(tasks.named("jvmTestClasses"), verifyElectronMigrationManifest)
    classpath = migrationCliClasspath
    mainClass.set("io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli")
    args("generate", fixtureManifest.asFile.absolutePath, generatedDirectory.get().asFile.absolutePath)
    inputs.file(fixtureManifest)
    outputs.dir(generatedDirectory)
}

val verifyElectronMigrationTypescript = tasks.register<Exec>("verifyElectronMigrationTypescript") {
    group = "verification"
    description = "Compiles the generated Electron migration facade and fixture renderer with strict TypeScript."
    dependsOn(generateElectronMigrationPreload)
    val compiler = rootProject.layout.projectDirectory.file("node_modules/typescript/bin/tsc")
    inputs.file(compiler)
    inputs.dir(generatedDirectory)
    inputs.file(fixtureRoot.file("renderer/renderer.ts"))
    commandLine(
        "node", compiler.asFile.absolutePath,
        "--noEmit", "--strict", "--target", "ES2022", "--module", "ES2022", "--lib", "ES2022,DOM",
        generatedDirectory.get().file("KWebElectronPreload.ts").asFile.absolutePath,
        fixtureRoot.file("renderer/renderer.ts").asFile.absolutePath,
    )
}

val verifyElectronMigrationJavascript = tasks.register<Exec>("verifyElectronMigrationJavascript") {
    group = "verification"
    description = "Executes the generated Electron preload facade with a strict bridge fixture."
    dependsOn(generateElectronMigrationPreload)
    val runtimeTest = layout.projectDirectory.file("src/jvmTest/resources/preload-runtime-test.mjs")
    inputs.file(runtimeTest)
    inputs.file(generatedDirectory.get().file("KWebElectronPreload.js"))
    commandLine("node", runtimeTest.asFile.absolutePath, generatedDirectory.get().file("KWebElectronPreload.js").asFile.absolutePath)
}

val electronMigrationInventory = tasks.register<JavaExec>("electronMigrationInventory") {
    group = "verification"
    description = "Inventories Electron and Node usage in the migration fixture."
    dependsOn(tasks.named("jvmTestClasses"), verifyElectronMigrationManifest)
    classpath = migrationCliClasspath
    mainClass.set("io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli")
    args("inventory", fixtureRoot.asFile.absolutePath, fixtureManifest.asFile.absolutePath, inventoryOutput.get().asFile.absolutePath)
    inputs.dir(fixtureRoot)
    outputs.file(inventoryOutput)
}

val electronMigrationReport = tasks.register<JavaExec>("electronMigrationReport") {
    group = "verification"
    description = "Builds the digest-bound Electron migration compatibility report."
    dependsOn(generateElectronMigrationPreload, electronMigrationInventory)
    classpath = migrationCliClasspath
    mainClass.set("io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCli")
    args(
        "report",
        fixtureManifest.asFile.absolutePath,
        generatedDirectory.get().asFile.absolutePath,
        inventoryOutput.get().asFile.absolutePath,
        compatibilityOutput.get().asFile.absolutePath,
    )
    systemProperty("kweb.migration.runtime.manifest", rootProject.file("runtime/cef-runtime.json").absolutePath)
    systemProperty("kweb.migration.rfc.evidence", rootProject.file("docs/rfcs/evidence/manifest.json").absolutePath)
    systemProperty("kweb.migration.rfc.catalog", rootProject.file("docs/rfcs").absolutePath)
    inputs.file(rootProject.file("runtime/cef-runtime.json"))
    inputs.file(rootProject.file("docs/rfcs/evidence/manifest.json"))
    inputs.files(rootProject.fileTree("docs/rfcs") { include("????-*.md") })
    systemProperty("kweb.migration.target", providers.gradleProperty("kwebMigrationTarget").orElse(currentTarget()).get())
    inputs.file(fixtureManifest)
    inputs.dir(generatedDirectory)
    inputs.file(inventoryOutput)
    outputs.file(compatibilityOutput)
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
val servicesNativeLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_services.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_services.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_services.so"
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
    }
    rootProject.layout.projectDirectory.file("kweb-service-app-paths/build/native/contract/$fileName").asFile
}
val appPathsBridgeJavascript = rootProject.layout.projectDirectory.file(
    "kweb-service-app-paths/build/generated/kwebBridge/appPaths/AppPathsBridgeBridge.js",
)
val filesBridgeJavascript = rootProject.layout.projectDirectory.file(
    "kweb-service-files/build/generated/kwebBridge/files/FilesBridgeBridge.js",
)
val shellNativeLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_shell.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_shell.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_shell.so"
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
    }
    rootProject.layout.projectDirectory.file("kweb-service-shell/build/native/contract/$fileName").asFile
}
val shellBridgeJavascript = rootProject.layout.projectDirectory.file(
    "kweb-service-shell/build/generated/kwebBridge/shell/ShellBridgeBridge.js",
)
val clipboardNativeLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_clipboard.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_clipboard.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_clipboard.so"
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
    }
    rootProject.layout.projectDirectory.file("kweb-service-clipboard/build/native/contract/$fileName").asFile
}
val clipboardBridgeJavascript = rootProject.layout.projectDirectory.file(
    "kweb-service-clipboard/build/generated/kwebBridge/clipboard/ClipboardBridgeBridge.js",
)
val notificationsNativeLibrary = operatingSystem.map { name ->
    val fileName = when {
        name.lowercase(Locale.ROOT).startsWith("windows") -> "kwebshell_notifications.dll"
        name.lowercase(Locale.ROOT).startsWith("mac") -> "libkwebshell_notifications.dylib"
        name.lowercase(Locale.ROOT).startsWith("linux") -> "libkwebshell_notifications.so"
        else -> throw GradleException("Unsupported desktop operating system '$name'.")
    }
    rootProject.layout.projectDirectory.file("kweb-service-notifications/build/native/contract/$fileName").asFile
}
val notificationsBridgeJavascript = rootProject.layout.projectDirectory.file(
    "kweb-service-notifications/build/generated/kwebBridge/notifications/NotificationsBridgeBridge.js",
)
val migrationIntegrationRoot = layout.buildDirectory.dir("migration-integration")
val migrationIntegrationReport = compatibilityOutput
val macNotificationActionFixtureSource = rootProject.layout.projectDirectory.file(
    ".github/scripts/macos-notification-action-fixture.swift",
)
val macNotificationActionFixture = layout.buildDirectory.file("macos-notification-action-fixture")
val windowsNotificationActionFixture = rootProject.layout.projectDirectory.file(
    ".github/scripts/windows-notification-action-fixture.ps1",
)
val compileMacNotificationActionFixture = tasks.register<Exec>("compileMacNotificationActionFixture") {
    group = "verification"
    description = "Builds the macOS Accessibility driver for a real Notification Center action response."
    val executable = macNotificationActionFixture.get().asFile
    inputs.file(macNotificationActionFixtureSource)
    outputs.file(executable)
    doFirst { executable.parentFile.mkdirs() }
    commandLine("swiftc", macNotificationActionFixtureSource.asFile.absolutePath, "-o", executable.absolutePath)
}
val migrationIntegrationJavaOptions = providers.provider {
    listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.awt.headless=false",
        "-Dkweb.native.library.path=${nativeEngineLibrary.get().absolutePath}",
        "-Dkweb.services.native.library.path=${servicesNativeLibrary.get().absolutePath}",
        "-Dkweb.shell.native.library.path=${shellNativeLibrary.get().absolutePath}",
        "-Dkweb.clipboard.native.library.path=${clipboardNativeLibrary.get().absolutePath}",
        "-Dkweb.engine.cef.runtime.path=${nativeCefRuntime.get().absolutePath}",
        "-Dkweb.engine.subprocess.path=${nativeBrowserSubprocess.get().absolutePath}",
        "-Dkweb.engine.resources.path=${nativeResources.get().absolutePath}",
        "-Dkweb.engine.locales.path=${nativeLocales.get().absolutePath}",
        "-Dkweb.migration.integration.root=${migrationIntegrationRoot.get().asFile.absolutePath}",
        "-Dkweb.migration.app-paths.bridge.javascript=${appPathsBridgeJavascript.asFile.absolutePath}",
        "-Dkweb.migration.files.bridge.javascript=${filesBridgeJavascript.asFile.absolutePath}",
        "-Dkweb.migration.shell.bridge.javascript=${shellBridgeJavascript.asFile.absolutePath}",
        "-Dkweb.migration.clipboard.bridge.javascript=${clipboardBridgeJavascript.asFile.absolutePath}",
        "-Dkweb.notifications.native.library.path=${notificationsNativeLibrary.get().absolutePath}",
        "-Dkweb.migration.notification.action.helper=${macNotificationActionFixture.get().asFile.absolutePath}",
        "-Dkweb.migration.notification.action.helper.windows=${windowsNotificationActionFixture.asFile.absolutePath}",
        "-Dkweb.migration.notifications.bridge.javascript=${notificationsBridgeJavascript.asFile.absolutePath}",
        "-Dkweb.migration.preload.javascript=${generatedDirectory.get().file("KWebElectronPreload.js").asFile.absolutePath}",
        "-Dkweb.migration.manifest=${fixtureManifest.asFile.absolutePath}",
        "-Dkweb.migration.inventory=${inventoryOutput.get().asFile.absolutePath}",
        "-Dkweb.migration.report=${migrationIntegrationReport.get().asFile.absolutePath}",
        "-Dkweb.migration.cef.version=${providers.gradleProperty("kwebMigrationCefVersion").orElse("151.3.16").get()}",
        "-Dkweb.migration.chromium.version=${providers.gradleProperty("kwebMigrationChromiumVersion").orElse("151.0.7922.109").get()}",
        "-Dkweb.migration.target=${providers.gradleProperty("kwebMigrationTarget").orElse(currentTarget()).get()}",
    )
}
fun quoteMacJavaOption(option: String): String {
    if (!option.startsWith("-D")) return option
    val separator = option.indexOf('=')
    if (separator < 0) return option
    val value = option.substring(separator + 1).replace("\\", "\\\\").replace("\"", "\\\"")
    return option.substring(0, separator + 1) + "\"" + value + "\""
}
val migrationIntegrationMainClass = "io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationIntegrationMainKt"
val migrationIntegrationCommand = providers.provider {
    buildList {
        add(migrationCliJava.get().executablePath.asFile.absolutePath)
        addAll(migrationIntegrationJavaOptions.get())
        add("-cp")
        add(migrationCliClasspath.asPath)
        add(migrationIntegrationMainClass)
    }
}
val cleanMigrationIntegration = tasks.register<Delete>("cleanElectronMigrationIntegration") {
    delete(migrationIntegrationRoot)
}
val migrationMacAppImageDirectory = layout.buildDirectory.dir("macos-migration-app-image")
val migrationMacAppInputDirectory = layout.buildDirectory.dir("macos-migration-app-input")
val migrationMacLauncherClass = "io/github/kingsword09/kwebshell/electron/migration/MacBundleIntegrationLauncher.class"
val migrationMacLauncherMain = "io.github.kingsword09.kwebshell.electron.migration.MacBundleIntegrationLauncher"
val cleanMigrationMacAppImage = tasks.register<Delete>("cleanMigrationMacAppImage") {
    delete(migrationMacAppImageDirectory, migrationMacAppInputDirectory)
}
val migrationMacLauncherJar = tasks.register<Jar>("migrationMacLauncherJar") {
    dependsOn(tasks.named("jvmTestClasses"))
    archiveBaseName.set("kweb-electron-migration-launcher")
    from(layout.buildDirectory.dir("classes/java/jvmTest")) { include(migrationMacLauncherClass) }
    manifest.attributes["Main-Class"] = migrationMacLauncherMain
}
val prepareMigrationMacAppImageInput = tasks.register<Sync>("prepareMigrationMacAppImageInput") {
    dependsOn(cleanMigrationMacAppImage, migrationMacLauncherJar)
    from(migrationMacLauncherJar)
    into(migrationMacAppInputDirectory)
}
val packageMigrationMacAppImage = tasks.register<Exec>("packageMigrationMacAppImage") {
    group = "verification"
    description = "Creates a real macOS app bundle for notification provider integration with the RFC 0030 bundle identity."
    dependsOn(prepareMigrationMacAppImageInput)
    val launcherJar = migrationMacLauncherJar.flatMap { it.archiveFile }
    inputs.dir(migrationMacAppInputDirectory)
    outputs.dir(migrationMacAppImageDirectory)
    commandLine(
        listOf(
            migrationCliJava.get().metadata.installationPath.file("bin/jpackage").asFile.absolutePath,
            "--type", "app-image",
            "--name", "KWebShellMigrationFixture",
            "--dest", migrationMacAppImageDirectory.get().asFile.absolutePath,
            "--input", migrationMacAppInputDirectory.get().asFile.absolutePath,
            "--main-jar", launcherJar.get().asFile.name,
            "--main-class", migrationMacLauncherMain,
            "--mac-package-identifier", "io.github.kingsword09.kwebshell",
        ) + (listOf("--java-options", "-Dkweb.migration.launch.classpath=${migrationCliClasspath.asPath}") +
            migrationIntegrationJavaOptions.get().flatMap { option -> listOf("--java-options", quoteMacJavaOption(option)) }),
    )
}
val registerMigrationMacAppImage = tasks.register<Exec>("registerMigrationMacAppImage") {
    group = "verification"
    description = "Registers the packaged fixture identity with Launch Services before notification authorization."
    dependsOn(packageMigrationMacAppImage)
    commandLine(
        "/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister",
        "-f", migrationMacAppImageDirectory.get().dir("KWebShellMigrationFixture.app").asFile.absolutePath,
    )
}
val electronMigrationIntegrationTest = tasks.register<Exec>("electronMigrationIntegrationTest") {
    group = "verification"
    description = "Runs the typed Electron migration fixture against real CEF."
    dependsOn(
        cleanMigrationIntegration,
        electronMigrationReport,
        tasks.named("jvmTestClasses"),
        ":kweb-cef-native:buildNative",
        ":kweb-service-app-paths:buildNative",
        ":kweb-service-app-paths:generateAppPathsBridge",
        ":kweb-service-files:generateFilesBridge",
        ":kweb-service-shell:buildNative",
        ":kweb-service-shell:generateShellBridge",
        ":kweb-service-clipboard:buildNative",
        ":kweb-service-clipboard:generateClipboardBridge",
        ":kweb-service-notifications:nativeTest",
        ":kweb-service-notifications:generateNotificationsBridge",
    )
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("mac")) {
        dependsOn(registerMigrationMacAppImage, compileMacNotificationActionFixture)
    }
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
    inputs.file(appPathsBridgeJavascript)
    inputs.file(filesBridgeJavascript)
    inputs.file(shellBridgeJavascript)
    inputs.file(shellNativeLibrary)
    inputs.file(clipboardBridgeJavascript)
    inputs.file(clipboardNativeLibrary)
    inputs.file(notificationsBridgeJavascript)
    inputs.file(notificationsNativeLibrary)
    inputs.file(servicesNativeLibrary)
    inputs.file(generatedDirectory.get().file("KWebElectronPreload.js"))
    inputs.file(migrationIntegrationReport)
    inputs.file(windowsNotificationActionFixture)
    if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("mac")) {
        commandLine(
            migrationMacAppImageDirectory.get().dir("KWebShellMigrationFixture.app")
                .file("Contents/MacOS/KWebShellMigrationFixture").asFile.absolutePath,
        )
    } else if (operatingSystem.get().lowercase(Locale.ROOT).startsWith("linux")) {
        commandLine(listOf("xvfb-run", "--auto-servernum", "--server-args=-screen 0 1280x1024x24") + migrationIntegrationCommand.get())
    } else {
        commandLine(migrationIntegrationCommand.get())
    }
}

tasks.named("check") {
    dependsOn(
        verifyElectronMigrationManifest,
        verifyElectronMigrationTypescript,
        verifyElectronMigrationJavascript,
        electronMigrationInventory,
        electronMigrationReport,
        electronMigrationIntegrationTest,
    )
}

fun currentTarget(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT).let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "macos"
            it.startsWith("linux") -> "linux"
            else -> error("Unsupported migration target operating system: $it")
        }
    }
    val architecture = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported migration target architecture: ${System.getProperty("os.arch")}")
    }
    return "$os-$architecture"
}

// Inventory is build-time tooling; its pinned parser is supplied explicitly.
tasks.withType<JavaExec>().configureEach {
    systemProperty("kweb.migration.typescript", rootProject.layout.projectDirectory.dir("node_modules/typescript-ast").asFile.absolutePath)
}
