package io.github.kingsword09.kwebshell.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.compose.KWebView
import io.github.kingsword09.kwebshell.compose.KWebViewController
import io.github.kingsword09.kwebshell.compose.KWebViewPageOwnership
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngine
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.desktop.KWebComposeWindowHost
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebActivationSource
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebActivationBatch
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationLifecycleConfiguration
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationLifecycleController
import io.github.kingsword09.kwebshell.service.applicationlifecycle.JvmKWebApplicationLifecycleBackend
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebQuitReason
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebOpenedFile
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import javax.swing.SwingUtilities

private const val APPLICATION_ID = "io.github.kingsword09.kwebshell"
private const val DEFAULT_URI = "kweb://io.github.kingsword09.kwebshell/launch"

public fun main(arguments: Array<String>): Unit = runBlocking {
    KWebApplicationMain().run(arguments.toList())
}

internal class KWebApplicationMain {
    suspend fun run(arguments: List<String>) {
        val target = currentTarget()
        val layout = applicationLayout(target)
        val root = layout.packageRoot
        val names = NativeNames.forTarget(target)
        System.setProperty("kweb.native.library.path", root.resolve(names.engineLibrary).toString())
        System.setProperty(
            "kweb.application.lifecycle.native.library.path",
            root.resolve(names.lifecycleLibrary).toString(),
        )

        val activation = parseActivation(arguments)
        val transportRoot = writableStateRoot().resolve("transport")
        val cacheRoot = writableStateRoot().resolve("profiles")
        Files.createDirectories(transportRoot)
        Files.createDirectories(cacheRoot)
        val lifecycle = KWebApplicationLifecycleController(
            KWebApplicationLifecycleConfiguration(
                applicationId = APPLICATION_ID,
                target = target,
                packageIdentity = APPLICATION_ID,
                registeredSchemes = setOf("kweb"),
                registeredExtensions = setOf(".kweb"),
                packageRoot = root.toString(),
                transportRoot = transportRoot.toString(),
                relaunchExecutable = layout.relaunchExecutable,
                isPackaged = true,
            ),
            JvmKWebApplicationLifecycleBackend(),
        )

        if (lifecycle.start(activation) != io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationStartResult.PRIMARY) {
            return
        }

        var engine: KWebDesktopEngine? = null
        var profile: io.github.kingsword09.kwebshell.core.KWebProfile? = null
        var page: io.github.kingsword09.kwebshell.core.KWebPage? = null
        var window: ComposeWindow? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val closed = CountDownLatch(1)
        try {
            val liveEngine = KWebDesktop.openEngine(
                KWebDesktopEngineConfiguration(
                    cefRuntime = root.resolve(names.cefRuntime),
                    browserSubprocess = root.resolve(names.browserSubprocess),
                    resources = root.resolve(names.resources),
                    locales = root.resolve(names.locales),
                    rootCache = cacheRoot,
                    log = cacheRoot.resolve("kweb-cef.log"),
                ),
            )
            engine = liveEngine
            lifecycle.registerShutdownParticipant(liveEngine.applicationShutdownParticipant())
            val liveWindow = createWindow(closed)
            window = liveWindow
            val host: KWebComposeWindowHost = KWebDesktop.composeWindowHost(liveWindow)
            val liveProfile = liveEngine.openProfile("primary")
            profile = liveProfile
            val livePage = liveProfile.openPage(host, "about:blank", KWebRect(0, 0, 900, 650))
            page = livePage
            val controller = KWebViewController(livePage, KWebViewPageOwnership.EXTERNAL)
            onEventThread { liveWindow.setContent { KWebView(controller, Modifier) } }

            val activationJob = scope.launch {
                lifecycle.events.collect { event ->
                    if (event is KWebApplicationEvent.Activation &&
                        event.batch.source != KWebActivationSource.INITIAL_ARGUMENTS
                    ) {
                        onEventThread {
                            liveWindow.isVisible = true
                            liveWindow.extendedState = liveWindow.extendedState and java.awt.Frame.ICONIFIED.inv()
                            liveWindow.toFront()
                            liveWindow.requestFocus()
                        }
                    }
                }
            }

            closed.await()
            activationJob.cancel()
            page.close()
            page = null
            profile.close()
            profile = null
            lifecycle.requestQuit(KWebQuitReason.USER_REQUEST)
        } catch (error: Throwable) {
            runCatching { lifecycle.requestQuit(KWebQuitReason.SHUTDOWN) }
            throw error
        } finally {
            scope.cancel()
            page?.let { runCatching { it.close() } }
            profile?.let { runCatching { it.close() } }
            if (engine == null) runCatching { lifecycle.close() }
            window?.let { runCatching { onEventThread { it.dispose() } } }
        }
    }

    private fun parseActivation(arguments: List<String>): KWebActivationBatch {
        val uris = arguments.filter { it.startsWith("kweb:", ignoreCase = true) }
        val files = arguments.filter { !it.startsWith("-") && !it.startsWith("kweb:", ignoreCase = true) }
            .map { value ->
                val path = Paths.get(value)
                if (!path.isAbsolute) error("Application activation path must be absolute: $value")
                KWebOpenedFile(path.normalize().toString(), Files.isRegularFile(path))
            }
        return KWebActivationBatch(
            source = KWebActivationSource.INITIAL_ARGUMENTS,
            uris = if (uris.isEmpty() && files.isEmpty()) listOf(DEFAULT_URI) else uris,
            files = files,
        )
    }

    private fun applicationLayout(target: KWebTarget): KWebApplicationLayout =
        KWebApplicationLayout.discover(
            KWebApplicationMain::class.java.protectionDomain.codeSource.location.toURI(),
            target,
        )

    private fun writableStateRoot(): Path = Path.of(
        System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"),
        "KWebShell",
    )

    private fun currentTarget(): KWebTarget = when {
        System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("amd64", "x86_64") -> KWebTarget.parse("windows-x64")
        System.getProperty("os.name").startsWith("Mac") && System.getProperty("os.arch") in setOf("aarch64", "arm64") -> KWebTarget.parse("macos-arm64")
        System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") in setOf("amd64", "x86_64") -> KWebTarget.parse("linux-x64")
        else -> error("Unsupported KWebShell launcher target: ${System.getProperty("os.name")}/${System.getProperty("os.arch")}")
    }

    private fun createWindow(closed: CountDownLatch): ComposeWindow = onEventThread {
        ComposeWindow().apply {
            title = "KWebShell"
            defaultCloseOperation = javax.swing.WindowConstants.DO_NOTHING_ON_CLOSE
            setSize(900, 650)
            isVisible = true
            addWindowListener(object : WindowAdapter() {
                override fun windowClosing(event: WindowEvent) { closed.countDown() }
            })
        }
    }

    private fun <T> onEventThread(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}

private data class NativeNames(
    val engineLibrary: String,
    val lifecycleLibrary: String,
    val cefRuntime: String,
    val browserSubprocess: String,
    val resources: String,
    val locales: String,
) {
    companion object {
        fun forTarget(target: KWebTarget): NativeNames = when (target.operatingSystem.id) {
            "windows" -> NativeNames("native/kwebshell_engine.dll", "native/kwebshell_application_lifecycle.dll", "cef/libcef.dll", "cef/KWebShellCef.exe", "cef", "cef/locales")
            "macos" -> NativeNames("native/libkwebshell_engine.dylib", "native/libkwebshell_application_lifecycle.dylib", "runtime/KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Chromium Embedded Framework", "runtime/KWebShell.app/Contents/Frameworks/KWebShell Helper.app/Contents/MacOS/KWebShell Helper", "runtime/KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Resources", "runtime/KWebShell.app/Contents/Frameworks/Chromium Embedded Framework.framework/Resources")
            else -> NativeNames("native/libkwebshell_engine.so", "native/libkwebshell_application_lifecycle.so", "runtime/libcef.so", "runtime/KWebShell", "runtime", "runtime/locales")
        }
    }
}
