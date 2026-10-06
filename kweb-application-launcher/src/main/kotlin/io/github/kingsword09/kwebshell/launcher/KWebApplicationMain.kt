package io.github.kingsword09.kwebshell.launcher

import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.compose.KWebView
import io.github.kingsword09.kwebshell.compose.KWebViewController
import io.github.kingsword09.kwebshell.compose.KWebViewPageOwnership
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
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
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebQuitResult
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebOpenedFile
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
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
        System.setProperty("kweb.native.library.path", layout.nativeDirectory.resolve("kwebshell_engine.dll").toString())
        System.setProperty(
            "kweb.application.lifecycle.native.library.path",
            layout.nativeDirectory.resolve("kwebshell_application_lifecycle.dll").toString(),
        )

        val activation = parseActivation(arguments)
        val stateRoot = KWebApplicationLayout.prepareStateRoot(System.getenv("LOCALAPPDATA"))
        val transportRoot = stateRoot.resolve("transport")
        val cacheRoot = stateRoot.resolve("profiles")
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
        var failure: Throwable? = null
        fun retainFailure(error: Throwable) {
            val previous = failure
            if (previous == null) failure = error
            else if (previous !== error) previous.addSuppressed(error)
        }
        try {
            val liveEngine = KWebDesktop.openEngine(
                KWebDesktopEngineConfiguration(
                    cefRuntime = layout.cefDirectory.resolve("libcef.dll"),
                    browserSubprocess = layout.cefDirectory.resolve("KWebShellCef.exe"),
                    resources = layout.cefDirectory,
                    locales = layout.cefDirectory.resolve("locales"),
                    rootCache = cacheRoot,
                    log = cacheRoot.resolve("kweb-cef.log"),
                ),
            )
            engine = liveEngine
            lifecycle.registerShutdownParticipant(liveEngine.applicationShutdownParticipant())
            val liveProfile = liveEngine.openProfile("primary")
            profile = liveProfile
            val liveWindow = createWindow(closed)
            window = liveWindow
            val host: KWebComposeWindowHost = KWebDesktop.composeWindowHost(liveWindow)
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
        } catch (error: Throwable) {
            retainFailure(error)
        } finally {
            scope.cancel()
            withContext(NonCancellable) {
                fun release(action: () -> Unit) {
                    try { action() } catch (error: Throwable) { retainFailure(error) }
                }
                release { page?.close() }
                release { profile?.close() }
                try {
                    val result = lifecycle.requestQuit(
                        if (failure == null) KWebQuitReason.USER_REQUEST else KWebQuitReason.SHUTDOWN,
                    )
                    if (result != KWebQuitResult.GRACEFUL && result != KWebQuitResult.ALREADY_CLOSED) {
                        retainFailure(lifecycle.terminalFailure() ?: KWebConfigurationException(
                            code = "launcher.shutdown-failed",
                            details = mapOf("result" to result.name),
                            message = "The Windows application owner did not complete graceful shutdown.",
                        ))
                    }
                } catch (error: Throwable) {
                    retainFailure(error)
                }
                release { engine?.close() }
                release { window?.let { onEventThread { it.dispose() } } }
            }
        }
        failure?.let { throw it }
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

    private fun currentTarget(): KWebTarget = KWebApplicationLayout.target(
        System.getProperty("os.name"),
        System.getProperty("os.arch"),
    )

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
