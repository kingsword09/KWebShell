package io.github.kingsword09.kwebshell.service.applicationlifecycle

import io.github.kingsword09.kwebshell.service.applicationlifecycle.internal.FfmApplicationLifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

public fun main() = runBlocking {
    val role = System.getProperty("kweb.application.lifecycle.role", "primary")
    val applicationId = System.getProperty(
        "kweb.application.lifecycle.application-id",
        "io.github.kwebshell.lifecycle.integration",
    )
    val transportRoot = Path.of(
        System.getProperty(
            "kweb.application.lifecycle.transport-root",
            Files.createTempDirectory("kweb-lifecycle-integration-").toString(),
        ),
    ).toAbsolutePath().normalize()
    Files.createDirectories(transportRoot)
    val targetId = System.getProperty("kweb.application.lifecycle.target", "host").let { configured ->
        if (configured != "host") configured else {
            val os = System.getProperty("os.name").lowercase()
            val architecture = System.getProperty("os.arch").lowercase()
            when {
                os.startsWith("windows") && architecture in setOf("amd64", "x86_64") -> "windows-x64"
                os.startsWith("mac") && architecture in setOf("aarch64", "arm64") -> "macos-arm64"
                os.startsWith("linux") && architecture in setOf("amd64", "x86_64") -> "linux-x64"
                else -> error("Unsupported lifecycle integration host: $os/$architecture")
            }
        }
    }
    val relaunchExecutable = when {
        targetId.startsWith("macos-") -> "KWebShell.app/Contents/MacOS/KWebShell"
        targetId.startsWith("windows-") -> "KWebShell.exe"
        else -> "KWebShell"
    }
    createPackageFixture(transportRoot, targetId, applicationId, relaunchExecutable)
    val configuration = KWebApplicationLifecycleConfiguration(
        applicationId = applicationId,
        target = io.github.kingsword09.kwebshell.core.KWebTarget.parse(targetId),
        packageIdentity = applicationId,
        registeredSchemes = setOf("kweb"),
        registeredExtensions = setOf(".kweb"),
        packageRoot = transportRoot.toString(),
        transportRoot = transportRoot.toString(),
        relaunchExecutable = relaunchExecutable,
        isPackaged = true,
    )
    val backend = JvmKWebApplicationLifecycleBackend()
    val lifecycle = KWebApplicationLifecycleController(configuration, backend)
    if (role == "secondary") {
        val result = lifecycle.start(
            KWebActivationBatch(
                source = KWebActivationSource.SECOND_INSTANCE,
                uris = listOf("kweb://integration/${UUID.randomUUID()}"),
                files = listOf(
                    KWebOpenedFile(
                        absolutePath = transportRoot.resolve("资料.kweb").toString(),
                        exists = false,
                    ),
                ),
            ),
        )
        check(result == KWebApplicationStartResult.SECONDARY_FORWARDED) {
            "The secondary process did not forward its activation: $result"
        }
        return@runBlocking
    }

    val forwarded = CompletableDeferred<KWebApplicationEvent.Activation>()
    val activationEvents = mutableListOf<String>()
    val collector = launch(Dispatchers.Default) {
        lifecycle.events.collect { event ->
            if (event is KWebApplicationEvent.Activation) {
                activationEvents += "${event.sequence}:${event.batch.source}"
                if (event.batch.source == KWebActivationSource.SECOND_INSTANCE) {
                    forwarded.complete(event)
                }
            }
        }
    }
    lifecycle.start(
        KWebActivationBatch(
            source = KWebActivationSource.INITIAL_ARGUMENTS,
            uris = listOf("kweb://integration/initial"),
        ),
    )
    val javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java")
    val classpath = System.getProperty("java.class.path")
    val child = ProcessBuilder(
        javaExecutable.toString(),
        "--enable-native-access=ALL-UNNAMED",
        "-cp", classpath,
        "-Dkweb.application.lifecycle.native.library.path=${System.getProperty("kweb.application.lifecycle.native.library.path")}",
        "-Dkweb.application.lifecycle.role=secondary",
        "-Dkweb.application.lifecycle.application-id=$applicationId",
        "-Dkweb.application.lifecycle.transport-root=$transportRoot",
        "io.github.kingsword09.kwebshell.service.applicationlifecycle.ApplicationLifecycleIntegrationMainKt",
    ).inheritIO().start()
    check(child.waitFor() == 0) { "The secondary lifecycle process failed." }
    val activation = withTimeout(10_000) { forwarded.await() }
    check(activation.batch.uris.single().startsWith("kweb://integration/")) {
        "The primary process received an invalid forwarded activation."
    }
    check(activation.batch.files.single().absolutePath.endsWith("资料.kweb")) {
        "The primary process did not receive the Unicode file activation."
    }
    val installed = lifecycle.installAssociations()
    check(installed.registered && installed.observedDigest.isNotBlank()) {
        "The lifecycle provider did not report an observed association installation."
    }
    val removed = lifecycle.removeAssociations()
    check(!removed.registered && removed.observedDigest.isNotBlank()) {
        "The lifecycle provider did not report an observed association cleanup."
    }
    collector.cancel()
    check(lifecycle.requestQuit(KWebQuitReason.TEST) == KWebQuitResult.GRACEFUL)

    val report = Path.of(System.getProperty("kweb.application.lifecycle.report"))
    Files.createDirectories(report.parent)
    Files.writeString(
        report,
        """
        {
          "schemaVersion": 1,
          "target": "$targetId",
          "provider": "${FfmApplicationLifecycle.providerId()}",
          "abiVersion": ${FfmApplicationLifecycle.abiVersion()},
          "secondaryForwarded": true,
          "unicodeFileForwarded": true,
          "activationSequence": ${activation.sequence},
          "activationEvents": [${activationEvents.joinToString(",") { "\"$it\"" }}],
          "registrationInstallDigest": "${installed.observedDigest}",
          "registrationRemoveDigest": "${removed.observedDigest}",
          "registrationCleaned": true,
          "shutdown": "GRACEFUL",
          "liveNativeOwners": ${io.github.kingsword09.kwebshell.service.applicationlifecycle.internal.FfmApplicationLifecycle.liveCount()},
          "status": "PASS"
        }
        """.trimIndent() + "\n",
    )
}

private fun createPackageFixture(
    root: Path,
    target: String,
    applicationId: String,
    relaunchExecutable: String,
) {
    val executable = root.resolve(relaunchExecutable)
    Files.createDirectories(executable.parent)
    if (!Files.exists(executable)) Files.write(executable, byteArrayOf(0x4b, 0x57, 0x45, 0x42))
    executable.toFile().setExecutable(true, false)
    if (target.startsWith("macos-")) {
        Files.writeString(
            root.resolve("KWebShell.app/Contents/Info.plist"),
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0"><dict>
              <key>CFBundleIdentifier</key><string>$applicationId</string>
              <key>CFBundleExecutable</key><string>KWebShell</string>
              <key>CFBundlePackageType</key><string>APPL</string>
              <key>CFBundleURLTypes</key><array><dict><key>CFBundleURLSchemes</key><array><string>kweb</string></array></dict></array>
              <key>CFBundleDocumentTypes</key><array><dict><key>CFBundleTypeExtensions</key><array><string>kweb</string></array><key>CFBundleTypeRole</key><string>Viewer</string></dict></array>
            </dict></plist>
            """.trimIndent() + "\n",
        )
    }
}
