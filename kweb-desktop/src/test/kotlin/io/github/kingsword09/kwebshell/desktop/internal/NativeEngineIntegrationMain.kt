package io.github.kingsword09.kwebshell.desktop.internal

import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.desktop.KWebComposeWindowHost
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.extensions.KWebExtensionLifecycleResolution
import io.github.kingsword09.kwebshell.extensions.JvmKWebExtensionLifecycleCoordinator
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntime
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeDispatchState
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeException
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeOperation
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeOutcome
import io.github.kingsword09.kwebshell.extensions.KWebExtensionRuntimeState
import io.github.kingsword09.kwebshell.desktop.generated.AckResponse
import io.github.kingsword09.kwebshell.desktop.generated.ConformanceBridgeDispatcher
import io.github.kingsword09.kwebshell.desktop.generated.ConformanceBridgeHandler
import io.github.kingsword09.kwebshell.desktop.generated.FailureRequest
import io.github.kingsword09.kwebshell.desktop.generated.ProbeRequest
import io.github.kingsword09.kwebshell.desktop.generated.ProbeResponse
import io.github.kingsword09.kwebshell.desktop.generated.WaitRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.awt.ComposeWindow
import java.awt.AWTEvent
import java.awt.Point
import java.awt.Robot
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.io.BufferedWriter
import java.net.InetSocketAddress
import java.net.URI
import java.net.ServerSocket
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

private const val INTEGRATION_ROOT_PROPERTY = "kweb.engine.integration.root"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val CDP_PORT_PROPERTY = "kweb.engine.integration.cdp.port"
private const val BRIDGE_JAVASCRIPT_PROPERTY = "kweb.engine.integration.bridge.javascript"
private const val EXTENSION_PATH_PROPERTY = "kweb.engine.integration.extension.path"
private const val LIFECYCLE_V1_PROPERTY = "kweb.engine.integration.lifecycle.v1"
private const val LIFECYCLE_V2_PROPERTY = "kweb.engine.integration.lifecycle.v2"
private const val EXPECT_CUSTOM_EXTENSION_RUNTIME_PROPERTY =
    "kweb.engine.integration.expect.custom.extension.runtime"
private const val DESKTOP_MODULE_PATH_PROPERTY = "kweb.desktop.module.path"
private const val DESKTOP_TEST_CLASSES_PROPERTY = "kweb.desktop.test.classes"
private const val DESKTOP_INTEGRATION_CLASSPATH_PROPERTY = "kweb.desktop.integration.classpath"
private const val DESKTOP_MODULE_NAME = "io.github.kingsword09.kwebshell.desktop"
private const val LIFECYCLE_EXTENSION_ID = "dhhnhmffjehhodphofnkingncijnaona"
private const val LIFECYCLE_CANCEL_RECONCILIATION_CYCLES = 5
private const val FFM_BROWSER_STRESS_LIFECYCLES = 1_000
private const val LIFECYCLE_CRASH_DURABLE_MARKER = "KWEBSHELL_EXTENSION_CRASH_STATE_DURABLE"
private const val HOLDER_OPENED_MARKER = "KWEBSHELL_ENGINE_HOLDER_OPENED"
private const val STAGE_PREFIX = "KWEBSHELL_ENGINE_STAGE:"
private const val MACOS_BROWSER_POLICY_MARKER =
    "KWEBSHELL_NATIVE_ENGINE:macos_browser_policy_applied"
private const val MACOS_PEER_VALIDATION_MARKER =
    "KWEBSHELL_NATIVE_ENGINE:macos_peer_validation_disabled"
private const val MACOS_PEER_VALIDATION_FEATURES_MARKER =
    "KWEBSHELL_NATIVE_ENGINE:macos_peer_validation_features_disabled"
private const val MACOS_PROCESS_REQUIREMENT_METRICS_MARKER =
    "KWEBSHELL_NATIVE_ENGINE:macos_process_requirement_metrics_disabled"
private const val MAIN_CLASS =
    "io.github.kingsword09.kwebshell.desktop.internal.NativeEngineIntegrationMainKt"
private const val FIRST_TITLE = "KWebShell profile 第一页🙂"
private const val SECOND_TITLE = "KWebShell navigation 路径🚀"

private enum class IntegrationMode(val argument: String) {
    COORDINATOR("coordinator"),
    SUCCESS("success"),
    CALLBACK_FAILURE("callback-failure"),
    PROFILE_CONTEXT_WAITERS("profile-context-waiters"),
    LISTENER_FAILURE("listener-failure"),
    FFM_STRESS("ffm-stress"),
    PUBLIC_FACADE("public-facade"),
    HOLDER("holder"),
    INITIALIZATION_FAILURE("initialization-failure"),
    PORT_COLLISION("port-collision"),
    CDP_DISABLED("cdp-disabled"),
    EXTENSION_LIFECYCLE_COORDINATOR("extension-lifecycle-coordinator"),
    EXTENSION_LIFECYCLE_STAGE1("extension-lifecycle-stage1"),
    EXTENSION_LIFECYCLE_CRASH("extension-lifecycle-crash"),
    EXTENSION_LIFECYCLE_STAGE2("extension-lifecycle-stage2"),
    EXTENSION_LIFECYCLE_STAGE3("extension-lifecycle-stage3"),
    ;

    companion object {
        fun parse(value: String): IntegrationMode = entries.singleOrNull { it.argument == value }
            ?: error("Unknown native engine integration mode: $value")
    }
}

fun main(arguments: Array<String>) {
    try {
        val mode = IntegrationMode.parse(arguments.singleOrNull() ?: IntegrationMode.COORDINATOR.argument)
        when (mode) {
            IntegrationMode.COORDINATOR -> runCoordinator()
            IntegrationMode.SUCCESS -> runSuccessfulLifecycle()
            IntegrationMode.CALLBACK_FAILURE -> runCallbackFailureLifecycle()
            IntegrationMode.PROFILE_CONTEXT_WAITERS -> runProfileContextWaiterLifecycle()
            IntegrationMode.LISTENER_FAILURE -> runListenerFailureLifecycle()
            IntegrationMode.FFM_STRESS -> runFfmStressLifecycle()
            IntegrationMode.PUBLIC_FACADE -> runPublicFacadeLifecycle()
            IntegrationMode.HOLDER -> runHolderLifecycle()
            IntegrationMode.INITIALIZATION_FAILURE -> runInitializationFailureLifecycle()
            IntegrationMode.PORT_COLLISION -> runPortCollisionLifecycle()
            IntegrationMode.CDP_DISABLED -> runCdpDisabledLifecycle()
            IntegrationMode.EXTENSION_LIFECYCLE_COORDINATOR -> runExtensionLifecycleCoordinator()
            IntegrationMode.EXTENSION_LIFECYCLE_STAGE1 -> runExtensionLifecycleStage1()
            IntegrationMode.EXTENSION_LIFECYCLE_CRASH -> runExtensionLifecycleCrash()
            IntegrationMode.EXTENSION_LIFECYCLE_STAGE2 -> runExtensionLifecycleStage2()
            IntegrationMode.EXTENSION_LIFECYCLE_STAGE3 -> runExtensionLifecycleStage3()
        }
    } catch (error: Throwable) {
        error.printStackTrace(System.err)
        exitProcess(1)
    }
}

private fun runCoordinator() {
    val root = requiredPathProperty(INTEGRATION_ROOT_PROPERTY)
    Files.createDirectories(root)
    runChildAndRequireSuccess(IntegrationMode.SUCCESS, root.resolve("success"))
    runChildAndRequireSuccess(IntegrationMode.CDP_DISABLED, root.resolve("cdp-disabled"))
    runChildAndRequireSuccess(IntegrationMode.PORT_COLLISION, root.resolve("port-collision"))
    runChildAndRequireSuccess(IntegrationMode.CALLBACK_FAILURE, root.resolve("callback-failure"))
    runChildAndRequireSuccess(IntegrationMode.PROFILE_CONTEXT_WAITERS, root.resolve("profile-context-waiters"))
    runChildAndRequireSuccess(IntegrationMode.LISTENER_FAILURE, root.resolve("listener-failure"))
    runChildAndRequireSuccess(IntegrationMode.FFM_STRESS, root.resolve("ffm-stress"))
    runChildAndRequireSuccess(IntegrationMode.PUBLIC_FACADE, root.resolve("public-facade"))

    val sharedRoot = root.resolve("initialization-failure")
    val holder = startChild(IntegrationMode.HOLDER, sharedRoot)
    require(holder.opened.await(60, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(holder.process)
        holder.process.destroyForcibly()
        "The holder engine did not reach OnContextInitialized.\n${holder.output()}\n$diagnostics"
    }
    try {
        runChildAndRequireSuccess(IntegrationMode.INITIALIZATION_FAILURE, sharedRoot)
    } finally {
        holder.input.newLine()
        holder.input.flush()
        require(holder.process.waitFor(60, TimeUnit.SECONDS)) {
            val diagnostics = collectTimeoutDiagnostics(holder.process)
            holder.process.destroyForcibly()
            "The holder engine did not shut down in time.\n${holder.output()}\n$diagnostics"
        }
        holder.reader.join(5000)
        require(holder.process.exitValue() == 0) {
            "The holder engine exited with ${holder.process.exitValue()}.\n${holder.output()}"
        }
    }
    println("KWebShell real CEF engine integration passed in isolated JVM processes.")
}

private fun runExtensionLifecycleCoordinator() {
    val root = requiredPathProperty(INTEGRATION_ROOT_PROPERTY)
    Files.createDirectories(root)
    val sharedRoot = root.resolve("extension-lifecycle")
    runChildAndRequireSuccess(IntegrationMode.EXTENSION_LIFECYCLE_STAGE1, sharedRoot)
    runChildAndRequireCrash(IntegrationMode.EXTENSION_LIFECYCLE_CRASH, sharedRoot)
    val pendingAfterCrash = JvmKWebExtensionLifecycleCoordinator.open(
        storeRoot = sharedRoot.resolve("crash").resolve(NativeBrowser.EXTENSION_STORE_DIRECTORY),
        runtime = KWebExtensionRuntime { request ->
            error("Crash-journal inspection unexpectedly dispatched ${request.operation}.")
        },
    ).pendingExtensionIds()
    require(pendingAfterCrash == setOf(LIFECYCLE_EXTENSION_ID)) {
        "The hard crash did not leave the exact install journal pending: $pendingAfterCrash"
    }
    runChildAndRequireSuccess(IntegrationMode.EXTENSION_LIFECYCLE_STAGE2, sharedRoot)
    runChildAndRequireSuccess(IntegrationMode.EXTENSION_LIFECYCLE_STAGE3, sharedRoot)
    println("KWebShell patched CEF MV3 lifecycle conformance passed across restart and Profiles.")
}

private fun runSuccessfulLifecycle() {
    val events = CopyOnWriteArrayList<NativeEngineEvent>()
    val callbackThreads = CopyOnWriteArrayList<String>()
    val configuration = runtimeConfiguration()
    reportStage("before_open")
    val engine = NativeEngine.open(configuration) { event ->
        events += event
        callbackThreads += Thread.currentThread().name
        if (event.type == NativeEngineEventType.CLOSED) {
            require(NativeEngine.liveNativeEngineCount() == 0L)
        }
    }
    reportStage("opened")
    require(engine.lifecycle.value == KWebLifecycleState.OPEN)
    val handle = events.single { it.type == NativeEngineEventType.OPENED }.handle
    require(engine.remoteDebuggingPort in 1024..65535)
    val cdp = CdpClient(engine.remoteDebuggingPort)

    val browserEvents = CopyOnWriteArrayList<NativeBrowserEvent>()
    val firstTitle = CountDownLatch(1)
    val secondTitle = CountDownLatch(1)
    val secondLoad = CountDownLatch(1)
    val resized = CountDownLatch(1)
    val firstDevToolsClosed = CountDownLatch(1)
    val profile = configuration.rootCache.resolve("integration-profile")
    BrowserOrigin().use { origin ->
        val surface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(800, 600) }
        try {
            requireCreateFailure(
                NativeEngine.onAwtEventDispatchThread {
                    rawBrowserCreate(
                        engine,
                        surface.nativeParent,
                        configuration.rootCache.resolve("nested/profile"),
                        origin.firstUrl,
                        800,
                        600,
                    )
                },
                NativeStatus.PROFILE_PATH_INVALID,
            )
            requireCreateFailure(
                NativeEngine.onAwtEventDispatchThread {
                    rawBrowserCreate(
                        engine,
                        surface.nativeParent,
                        configuration.rootCache.resolve("invalid-url-profile"),
                        "not-a-url",
                        800,
                        600,
                    )
                },
                NativeStatus.NAVIGATION_INVALID,
            )
            runRawBridgeAbiConformance(engine, surface.nativeParent, configuration.rootCache, origin, cdp)
            requireStatus(
                NativeEngine.onAwtEventDispatchThread {
                    NativeBindings.browserResize(0L, 0, 600)
                },
                NativeStatus.INVALID_DIMENSIONS,
                "invalid browser dimensions",
            )
            val bridgeHandler = ConformanceBridgeTestHandler()
            val browser = NativeBrowser.open(
                engine = engine,
                nativeParent = surface.nativeParent,
                profilePath = profile,
                initialUrl = origin.firstUrl,
                width = 800,
                height = 600,
                bridgeOrigin = origin.origin,
                bridgeDispatcher = ConformanceBridgeDispatcher(bridgeHandler),
            ) { event ->
                browserEvents += event
                when {
                    event.type == NativeBrowserEventType.TITLE_CHANGED && event.text == FIRST_TITLE -> {
                        firstTitle.countDown()
                    }
                    event.type == NativeBrowserEventType.TITLE_CHANGED && event.text == SECOND_TITLE -> {
                        secondTitle.countDown()
                    }
                    event.type == NativeBrowserEventType.LOAD_ENDED && event.text == origin.secondUrl -> {
                        secondLoad.countDown()
                    }
                    event.type == NativeBrowserEventType.RESIZED && event.width == 960 && event.height == 640 -> {
                        resized.countDown()
                    }
                    event.type == NativeBrowserEventType.DEVTOOLS_CLOSED -> {
                        firstDevToolsClosed.countDown()
                    }
                }
            }
            require(firstTitle.await(30, TimeUnit.SECONDS)) {
                "The first real Chromium page did not publish its Unicode title: $browserEvents"
            }
            if (requiredBooleanProperty(EXPECT_CUSTOM_EXTENSION_RUNTIME_PROPERTY)) {
                val customQuery = kotlinx.coroutines.runBlocking {
                    browser.queryExtension(LIFECYCLE_EXTENSION_ID)
                }
                require(
                    customQuery.operation == KWebExtensionRuntimeOperation.QUERY &&
                        customQuery.outcome == KWebExtensionRuntimeOutcome.SUCCESS &&
                        customQuery.state == KWebExtensionRuntimeState.ABSENT &&
                        customQuery.version == null && customQuery.path == null,
                ) {
                    "Custom CEF did not expose an initially absent Profile extension state: $customQuery"
                }
            } else {
                val stockExtensionResult = kotlinx.coroutines.runBlocking {
                    browser.installUnpackedExtension(requiredPathProperty(EXTENSION_PATH_PROPERTY))
                }
                require(stockExtensionResult.resolution == KWebExtensionLifecycleResolution.ABORTED) {
                    "Stock CEF did not abort the undispatched extension transaction: $stockExtensionResult"
                }
                require(stockExtensionResult.failure?.code == "native.abi.extension-runtime-abi-missing") {
                    "Stock CEF did not report the missing pinned extension adapter: $stockExtensionResult"
                }
                require(kotlinx.coroutines.runBlocking { browser.reconcileExtensions() }.isEmpty()) {
                    "An undispatched stock CEF extension attempt left a pending journal."
                }
            }
            require(NativeExtensionRuntime.liveNativeOperationCount() == 0L) {
                "The engine integration extension contract leaked a native operation."
            }
            cdp.awaitPage(origin.firstUrl)
            cdp.awaitBridge()
            require(cdp.evaluate("document.title") == FIRST_TITLE)
            runBridgeConformance(cdp, bridgeHandler)
            require(cdp.evaluate("typeof document.getElementById('bridge-frame').contentWindow.__kwebBridgeQuery") == "undefined")
            require(cdp.evaluate("void ConformanceBridge.createClient().wait({delayMs:60000}); 'started'") == "started")
            bridgeHandler.awaitStarted("navigation")
            browser.navigate(origin.crossOriginUrl)
            cdp.awaitPage(origin.crossOriginUrl)
            bridgeHandler.awaitCancelled("navigation")
            require(cdp.evaluate("typeof globalThis.__kwebBridgeQuery") == "undefined")
            require(cdp.evaluate("typeof globalThis.ConformanceBridge") == "undefined")
            browser.navigate(origin.firstUrl)
            cdp.awaitPage(origin.firstUrl)
            cdp.awaitBridge()
            requireStatus(
                NativeBindings.browserCloseDevTools(browser.requireLiveHandle("devtools-test")),
                NativeStatus.DEVTOOLS_NOT_OPEN,
                "close missing DevTools",
            )
            browser.openDevTools()
            require(browserEvents.any { it.type == NativeBrowserEventType.DEVTOOLS_OPENED })
            cdp.awaitDevToolsTarget()
            val duplicateDevTools = try {
                browser.openDevTools()
                null
            } catch (error: KWebNativeException) {
                error
            }
            require(duplicateDevTools?.code == "native.abi.devtools-already-open") {
                "Duplicate DevTools open returned $duplicateDevTools"
            }
            val browserHandle = browser.requireLiveHandle("devtools-test")
            requireStatus(
                NativeBindings.browserCloseDevTools(browserHandle),
                NativeStatus.OK,
                "close open DevTools",
            )
            requireStatus(
                NativeBindings.browserCloseDevTools(browserHandle),
                NativeStatus.DEVTOOLS_CLOSING,
                "close closing DevTools",
            )
            require(firstDevToolsClosed.await(30, TimeUnit.SECONDS)) {
                "The explicitly closed DevTools window did not publish its terminal event."
            }
            require(browserEvents.any { it.type == NativeBrowserEventType.DEVTOOLS_CLOSED })
            cdp.awaitNoDevToolsTarget()
            browser.openDevTools()
            cdp.awaitDevToolsTarget()
            browser.closeDevTools()
            cdp.awaitNoDevToolsTarget()
            require(NativeBrowser.liveNativeBrowserCount() == 1L)
            val rejectedEngineClose = try {
                NativeEngine.onAwtEventDispatchThread { engine.close() }
                null
            } catch (error: KWebNativeException) {
                error
            }
            require(rejectedEngineClose?.code == "native.abi.engine-has-live-browsers") {
                "Engine close with a live browser returned $rejectedEngineClose."
            }
            requireEquals(KWebLifecycleState.OPEN, engine.lifecycle.value, "engine remains open after rejected close")
            requireEquals(handle, engine.requireLiveHandle("browser-test"), "engine handle remains owned")

            browser.navigate(origin.secondUrl)
            require(secondTitle.await(30, TimeUnit.SECONDS)) {
                "The second real Chromium page did not publish its Unicode title: $browserEvents"
            }
            require(secondLoad.await(30, TimeUnit.SECONDS)) {
                "The second real Chromium navigation did not complete: $browserEvents"
            }
            cdp.awaitBridge()
            browser.resize(960, 640)
            require(resized.await(30, TimeUnit.SECONDS)) {
                "The native Chromium child did not confirm the requested size: $browserEvents"
            }

            browser.openDevTools()
            cdp.awaitDevToolsTarget()

            require(cdp.evaluate("void ConformanceBridge.createClient().wait({delayMs:60000}); 'started'") == "started")
            bridgeHandler.awaitStarted("browser close")

            browser.close()
            bridgeHandler.awaitCancelled("browser close")
            require(browserEvents.any { it.type == NativeBrowserEventType.DEVTOOLS_CLOSED }) {
                "Closing the page did not close its native DevTools window."
            }
            cdp.awaitNoDevToolsTarget()
            val staleBrowserHandle = browserEvents.first().browser
            val browserCallbackCountAfterClose = browserEvents.size
            Thread.sleep(100)
            browser.close()
            require(browserEvents.size == browserCallbackCountAfterClose)
            require(browser.lifecycle.value == KWebLifecycleState.CLOSED)
            require(NativeBrowser.liveNativeBrowserCount() == 0L)
            requireStatus(
                NativeEngine.onAwtEventDispatchThread { NativeBindings.browserClose(staleBrowserHandle) },
                NativeStatus.INVALID_HANDLE,
                "stale browser close",
            )
            require(browserEvents.first().type == NativeBrowserEventType.CREATED)
            require(browserEvents.last().type == NativeBrowserEventType.CLOSED)
            val devToolsClosedIndex = browserEvents.indexOfLast {
                it.type == NativeBrowserEventType.DEVTOOLS_CLOSED
            }
            val browserClosedIndex = browserEvents.indexOfLast {
                it.type == NativeBrowserEventType.CLOSED
            }
            require(devToolsClosedIndex in 0 until browserClosedIndex) {
                "The page closed before its native DevTools window."
            }
            require(browserEvents.map { it.sequence } == (1L..browserEvents.size.toLong()).toList())
            require(browserEvents.any { it.type == NativeBrowserEventType.NAVIGATION_STARTED && it.text == origin.secondUrl })
            require(browserEvents.any { it.type == NativeBrowserEventType.ADDRESS_CHANGED && it.text == origin.secondUrl })
            require(browserEvents.none { it.type == NativeBrowserEventType.FATAL_ERROR })
        }
        finally {
            NativeEngine.onAwtEventDispatchThread(surface::close)
        }
    }

    val duplicateCreate = NativeEngine.onAwtEventDispatchThread {
        rawCreate(configuration, NativeEngineEventSink { _, _, _ -> })
    }
    requireCreateFailure(duplicateCreate, NativeStatus.ENGINE_ALREADY_EXISTS)

    val wrongThreadStatus = NativeBindings.engineClose(handle)
    requireStatus(wrongThreadStatus, NativeStatus.WRONG_THREAD, "wrong-thread close")
    require(NativeEngine.liveNativeEngineCount() == 1L)

    Thread.sleep(2_000)
    reportStage("before_close")
    NativeEngine.onAwtEventDispatchThread(engine::close)
    reportStage("closed")
    cdp.assertUnavailable()
    val callbackCountAfterClose = events.size
    Thread.sleep(100)
    engine.close()

    require(events.map { it.type } == listOf(NativeEngineEventType.OPENED, NativeEngineEventType.CLOSED))
    require(events.map { it.sequence } == listOf(1L, 2L))
    require(events.size == callbackCountAfterClose)
    require(callbackThreads.toSet().size == 1)
    require(callbackThreads.toSet().single().startsWith("KWebShell-engine-callback-"))
    require(engine.lifecycle.value == KWebLifecycleState.CLOSED)
    require(NativeEngine.liveNativeEngineCount() == 0L)
    requireProfileDiskState(profile)

    val staleClose = NativeEngine.onAwtEventDispatchThread { NativeBindings.engineClose(handle) }
    requireStatus(staleClose, NativeStatus.INVALID_HANDLE, "stale engine close")
    val restart = NativeEngine.onAwtEventDispatchThread {
        rawCreate(configuration, NativeEngineEventSink { _, _, _ -> })
    }
    requireCreateFailure(restart, NativeStatus.ENGINE_RESTART_FORBIDDEN)
    reportStage("main_complete")
    println("KWebShell engine success lifecycle passed.")
}

private fun runPublicFacadeLifecycle() {
    val configuration = runtimeConfiguration()
    val engine = KWebDesktop.openEngine(
        KWebDesktopEngineConfiguration(
            cefRuntime = configuration.cefRuntime,
            browserSubprocess = configuration.browserSubprocess,
            resources = configuration.resources,
            locales = configuration.locales,
            rootCache = configuration.rootCache,
            log = configuration.log,
            remoteDebuggingPort = configuration.remoteDebuggingPort,
        ),
    )
    var surface: ComposeBrowserSurface? = null
    var profile: io.github.kingsword09.kwebshell.core.KWebProfile? = null
    var page: io.github.kingsword09.kwebshell.core.KWebPage? = null
    try {
        require(KWebCapability.NATIVE_CHILD in engine.capabilities)
        require(KWebCapability.PERSISTENT_PROFILE in engine.capabilities)
        require(KWebCapability.NAVIGATION in engine.capabilities)
        require(KWebCapability.RESIZE in engine.capabilities)
        require(KWebCapability.DEVTOOLS in engine.capabilities)
        if (configuration.remoteDebuggingPort != 0) {
            require(KWebCapability.CDP in engine.capabilities)
        }

        profile = kotlinx.coroutines.runBlocking { engine.openProfile("public-facade") }
        val duplicate = try {
            kotlinx.coroutines.runBlocking { engine.openProfile("public-facade") }
            null
        } catch (error: KWebConfigurationException) {
            error
        }
        require(duplicate?.code == "profile.duplicate-physical-identity") {
            "The public facade allowed a duplicate physical Profile: $duplicate"
        }

        BrowserOrigin().use { origin ->
            val invalidWindow = NativeEngine.onAwtEventDispatchThread { ComposeWindow() }
            try {
                val beforeInvalidParent = NativeBrowser.liveNativeBrowserCount()
                val invalidParent = try {
                    kotlinx.coroutines.runBlocking {
                        profile!!.openPage(
                            KWebDesktop.composeWindowHost(invalidWindow),
                            origin.firstUrl,
                            KWebBounds(800, 600),
                        )
                    }
                    null
                } catch (error: KWebConfigurationException) {
                    error
                }
                require(invalidParent?.code == "desktop.page.parent-not-visible") {
                    "The public facade created a page for a non-visible ComposeWindow: $invalidParent"
                }
                require(NativeBrowser.liveNativeBrowserCount() == beforeInvalidParent) {
                    "Invalid ComposeWindow validation created a native browser."
                }
            } finally {
                NativeEngine.onAwtEventDispatchThread(invalidWindow::dispose)
            }
            surface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(800, 600) }
            val nativeParent = surface!!.nativeParent
            val host: KWebComposeWindowHost = KWebDesktop.composeWindowHost(surface!!.window)
            page = kotlinx.coroutines.runBlocking {
                profile!!.openPage(host, origin.firstUrl, KWebBounds(800, 600))
            }
            val publicEvents = CopyOnWriteArrayList<io.github.kingsword09.kwebshell.core.KWebPageEvent>()
            val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val eventJob = eventScope.launch {
                page!!.events.collect { publicEvents += it }
            }
            try {
                kotlinx.coroutines.runBlocking {
                    withTimeout(30_000) {
                        page!!.events.first { it.type == KWebPageEventType.CREATED }
                    }
                }
                kotlinx.coroutines.runBlocking { page!!.navigate(origin.secondUrl) }
                kotlinx.coroutines.runBlocking {
                    withTimeout(30_000) {
                        page!!.events.first {
                            it.type == KWebPageEventType.ADDRESS_CHANGED && it.text == origin.secondUrl
                        }
                    }
                }
                kotlinx.coroutines.runBlocking { page!!.resize(KWebBounds(960, 640)) }
                kotlinx.coroutines.runBlocking {
                    withTimeout(30_000) {
                        page!!.events.first {
                            val eventBounds = it.bounds
                            it.type == KWebPageEventType.RESIZED &&
                                eventBounds?.width == 960 && eventBounds?.height == 640
                        }
                    }
                }
                kotlinx.coroutines.runBlocking { page!!.openDevTools() }
                kotlinx.coroutines.runBlocking { page!!.closeDevTools() }

                val profileClose = try {
                    profile!!.close()
                    null
                } catch (error: KWebNativeException) {
                    error
                }
                require(profileClose?.code == "desktop.profile.live-pages") {
                    "The public Profile closed with a live page: $profileClose"
                }
                val engineClose = try {
                    engine.close()
                    null
                } catch (error: KWebNativeException) {
                    error
                }
                require(engineClose?.code == "native.abi.engine-has-live-browsers") {
                    "The public Engine did not reject a live page: $engineClose"
                }

                page!!.close()
                kotlinx.coroutines.runBlocking {
                    withTimeout(30_000) {
                        page!!.events.first { it.type == KWebPageEventType.CLOSED }
                    }
                }
                require(
                    NativeEngine.onAwtEventDispatchThread {
                        surface!!.window.isShowing && surface!!.window.windowHandle == nativeParent
                    },
                ) {
                    "The ComposeWindow parent changed or stopped showing after public page close."
                }
                profile!!.close()
                engine.close()
            } finally {
                kotlinx.coroutines.runBlocking { eventJob.cancelAndJoin() }
                eventScope.cancel()
            }
            require(publicEvents.firstOrNull()?.type == KWebPageEventType.CREATED)
            require(publicEvents.lastOrNull()?.type == KWebPageEventType.CLOSED)
            require(publicEvents.map { it.sequence } == (1L..publicEvents.size.toLong()).toList()) {
                "The public facade reordered page events: $publicEvents"
            }
            require(NativeBrowser.liveNativeBrowserCount() == 0L)
        }
    } finally {
        if (page?.lifecycle?.value != KWebLifecycleState.CLOSED) {
            page?.close()
        }
        if (profile?.lifecycle?.value != KWebLifecycleState.CLOSED) {
            profile?.close()
        }
        if (engine.lifecycle.value != KWebLifecycleState.CLOSED) {
            engine.close()
        }
        surface?.let { NativeEngine.onAwtEventDispatchThread(it::close) }
    }
    require(NativeBrowser.liveNativeBrowserCount() == 0L)
    require(NativeEngine.liveNativeEngineCount() == 0L)
    awaitFfmCallbackOwnerCount(0)
    println("KWebShell public desktop facade lifecycle passed.")
}

private fun runExtensionLifecycleStage1() = withExtensionLifecycleBrowsers { engine, alpha, beta, _, origin, cdp, root ->
    val v1 = requiredPathProperty(LIFECYCLE_V1_PROPERTY)
    val v2 = requiredPathProperty(LIFECYCLE_V2_PROPERTY)
    requireCommitted(
        kotlinx.coroutines.runBlocking { alpha.installUnpackedExtension(v1) },
        "install alpha v1",
    )
    requireCommitted(
        kotlinx.coroutines.runBlocking { beta.installUnpackedExtension(v1) },
        "install beta v1",
    )

    alpha.navigate("${origin.firstUrl}?lifecycle=alpha-v1")
    beta.navigate("${origin.secondUrl}?lifecycle=beta-v1")
    val alphaV1 = awaitLifecycleProbe(cdp, "${origin.firstUrl}?lifecycle=alpha-v1")
    val betaV1 = awaitLifecycleProbe(cdp, "${origin.secondUrl}?lifecycle=beta-v1")
    alphaV1.requireIdentity("1.0.0", 1)
    betaV1.requireIdentity("1.0.0", 1)

    requireCommitted(
        kotlinx.coroutines.runBlocking { alpha.installUnpackedExtension(v2) },
        "update alpha to v2",
    )
    alpha.navigate("${origin.firstUrl}?lifecycle=alpha-v2")
    val alphaV2 = awaitLifecycleProbe(cdp, "${origin.firstUrl}?lifecycle=alpha-v2")
    alphaV2.requireIdentity("2.0.0", 2)

    requireCommitted(
        kotlinx.coroutines.runBlocking { alpha.reloadExtension(LIFECYCLE_EXTENSION_ID) },
        "reload alpha v2",
    )
    alpha.navigate("${origin.firstUrl}?lifecycle=alpha-reload")
    val alphaReloaded = awaitLifecycleProbe(cdp, "${origin.firstUrl}?lifecycle=alpha-reload")
    alphaReloaded.requireIdentity("2.0.0", 3)
    require(alphaReloaded.workerInstance != alphaV2.workerInstance) {
        "Reload did not replace the alpha Service Worker instance."
    }

    val cancellationCoordinator = JvmKWebExtensionLifecycleCoordinator.open(
        storeRoot = root.resolve("alpha").resolve(NativeBrowser.EXTENSION_STORE_DIRECTORY),
        runtime = KWebExtensionRuntime { request ->
            coroutineScope {
                val operation = async(start = CoroutineStart.UNDISPATCHED) {
                    NativeExtensionRuntime(engine, alpha).execute(request)
                }
                require(NativeExtensionRuntime.liveNativeOperationCount() == 1L) {
                    "The reload cancellation fixture did not dispatch exactly one native operation."
                }
                try {
                    awaitCancellation()
                } finally {
                    operation.cancelAndJoin()
                }
            }
        },
    )
    var alphaRecovered = alphaReloaded
    repeat(LIFECYCLE_CANCEL_RECONCILIATION_CYCLES) { cycle ->
        val cancellation = kotlinx.coroutines.runBlocking {
            val operation = async(start = CoroutineStart.UNDISPATCHED) {
                cancellationCoordinator.reload(LIFECYCLE_EXTENSION_ID)
            }
            operation.cancel(CancellationException("MV3 reload cancellation conformance"))
            try {
                operation.await()
                null
            } catch (error: CancellationException) {
                error
            }
        }
        require(cancellation != null) { "The dispatched reload did not propagate cancellation." }
        awaitNativeExtensionOperationCount(0L)
        val reconciledReload = kotlinx.coroutines.runBlocking { alpha.reconcileExtensions() }
        require(reconciledReload.size == 1) {
            "The cancelled reload did not retain exactly one journal: $reconciledReload"
        }
        requireCommitted(reconciledReload.single(), "reconcile cancelled alpha reload")
        val reconciliationUrl = "${origin.firstUrl}?lifecycle=alpha-cancel-reconciled-$cycle"
        alpha.navigate(reconciliationUrl)
        val recovered = awaitLifecycleProbe(cdp, reconciliationUrl)
        recovered.requireIdentity("2.0.0", 4 + cycle)
        require(recovered.workerInstance != alphaRecovered.workerInstance) {
            "Reconciliation cycle $cycle did not replace the worker after a cancelled reload."
        }
        alphaRecovered = recovered
    }

    beta.navigate("${origin.secondUrl}?lifecycle=beta-isolation")
    val betaIsolated = awaitLifecycleProbe(cdp, "${origin.secondUrl}?lifecycle=beta-isolation")
    betaIsolated.requireIdentity("1.0.0", 2)
    require(betaIsolated.workerInstance == betaV1.workerInstance) {
        "Alpha update/reload unexpectedly replaced beta's Service Worker."
    }
    require(alphaRecovered.workerInstance != betaIsolated.workerInstance) {
        "Separate Profiles unexpectedly shared one Service Worker instance."
    }

    val evidence = kotlinx.serialization.json.buildJsonObject {
        put("alphaWorkerInstance", alphaRecovered.workerInstance)
        put("betaWorkerInstance", betaIsolated.workerInstance)
        put("alphaProbeCount", alphaRecovered.probeCount)
        put("betaProbeCount", betaIsolated.probeCount)
    }
    Files.writeString(root.resolve("lifecycle-stage1.json"), evidence.toString(), StandardCharsets.UTF_8)
    require(NativeExtensionRuntime.liveNativeOperationCount() == 0L)
}

private fun runExtensionLifecycleCrash() = withExtensionLifecycleBrowsers {
        engine, _, _, crash, origin, cdp, root ->
    val profile = root.resolve("crash")
    val runtime = NativeExtensionRuntime(engine, crash)
    val crashUrl = "${origin.firstUrl}?lifecycle=crash-durable"
    val crashingRuntime = KWebExtensionRuntime { request ->
        require(request.operation == KWebExtensionRuntimeOperation.INSTALL) {
            "The crash fixture prepared ${request.operation} instead of INSTALL."
        }
        val result = coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { runtime.execute(request) }
            require(NativeExtensionRuntime.liveNativeOperationCount() == 1L) {
                "The duplicate install fixture did not dispatch exactly one native operation."
            }
            val duplicateFailure = try {
                runtime.execute(request)
                null
            } catch (error: KWebExtensionRuntimeException) {
                error
            }
            require(
                duplicateFailure?.dispatchState == KWebExtensionRuntimeDispatchState.NOT_DISPATCHED &&
                    duplicateFailure.code == "native.abi.extension-operation-active",
            ) { "The duplicate native install was not rejected before dispatch: $duplicateFailure" }
            first.await()
        }
        require(
            result.outcome == KWebExtensionRuntimeOutcome.SUCCESS &&
                result.state == KWebExtensionRuntimeState.ENABLED &&
                result.extensionId == LIFECYCLE_EXTENSION_ID &&
                result.version == "2.0.0" &&
                result.path == request.extensionPath,
        ) { "Chromium did not complete the crash fixture install: $result" }

        crash.navigate(crashUrl)
        val probe = awaitLifecycleProbe(cdp, crashUrl)
        probe.requireIdentity("2.0.0", 1)
        awaitPersistedExtensionPreference(profile, LIFECYCLE_EXTENSION_ID)
        val evidence = kotlinx.serialization.json.buildJsonObject {
            put("workerInstance", probe.workerInstance)
            put("probeCount", probe.probeCount)
        }
        Files.writeString(root.resolve("lifecycle-crash.json"), evidence.toString(), StandardCharsets.UTF_8)
        println(LIFECYCLE_CRASH_DURABLE_MARKER)
        System.out.flush()
        CountDownLatch(1).await()
        error("The crash-conformance process resumed without parent termination.")
    }
    val coordinator = JvmKWebExtensionLifecycleCoordinator.open(
        storeRoot = profile.resolve(NativeBrowser.EXTENSION_STORE_DIRECTORY),
        runtime = crashingRuntime,
    )
    kotlinx.coroutines.runBlocking {
        coordinator.installUnpacked(requiredPathProperty(LIFECYCLE_V2_PROPERTY))
    }
    error("The crash-conformance install returned without terminating the process.")
}

private fun runExtensionLifecycleStage2() = withExtensionLifecycleBrowsers {
        _, alpha, beta, crash, origin, cdp, root ->
    val evidence = kotlinx.serialization.json.Json.parseToJsonElement(
        Files.readString(root.resolve("lifecycle-stage1.json"), StandardCharsets.UTF_8),
    ).jsonObject
    val previousAlphaWorker = evidence.getValue("alphaWorkerInstance").jsonPrimitive.content
    val previousBetaWorker = evidence.getValue("betaWorkerInstance").jsonPrimitive.content
    val previousAlphaCount = evidence.getValue("alphaProbeCount").jsonPrimitive.content.toInt()
    val previousBetaCount = evidence.getValue("betaProbeCount").jsonPrimitive.content.toInt()
    val crashEvidence = kotlinx.serialization.json.Json.parseToJsonElement(
        Files.readString(root.resolve("lifecycle-crash.json"), StandardCharsets.UTF_8),
    ).jsonObject
    val previousCrashWorker = crashEvidence.getValue("workerInstance").jsonPrimitive.content
    val previousCrashCount = crashEvidence.getValue("probeCount").jsonPrimitive.content.toInt()

    val alphaQuery = kotlinx.coroutines.runBlocking { alpha.queryExtension(LIFECYCLE_EXTENSION_ID) }
    val betaQuery = kotlinx.coroutines.runBlocking { beta.queryExtension(LIFECYCLE_EXTENSION_ID) }
    val crashQuery = kotlinx.coroutines.runBlocking { crash.queryExtension(LIFECYCLE_EXTENSION_ID) }
    require(alphaQuery.state == KWebExtensionRuntimeState.ENABLED && alphaQuery.version == "2.0.0") {
        "Alpha extension did not survive restart: $alphaQuery"
    }
    require(betaQuery.state == KWebExtensionRuntimeState.ENABLED && betaQuery.version == "1.0.0") {
        "Beta extension did not survive restart independently: $betaQuery"
    }
    require(crashQuery.state == KWebExtensionRuntimeState.ENABLED && crashQuery.version == "2.0.0") {
        "Crash Profile reconciliation did not recover the installed extension: $crashQuery"
    }
    require(kotlinx.coroutines.runBlocking { crash.reconcileExtensions() }.isEmpty()) {
        "Crash Profile retained a journal after startup reconciliation."
    }

    alpha.navigate("${origin.firstUrl}?lifecycle=alpha-restart")
    beta.navigate("${origin.secondUrl}?lifecycle=beta-restart")
    crash.navigate("${origin.firstUrl}?lifecycle=crash-restart")
    val alphaRestarted = awaitLifecycleProbe(cdp, "${origin.firstUrl}?lifecycle=alpha-restart")
    val betaRestarted = awaitLifecycleProbe(cdp, "${origin.secondUrl}?lifecycle=beta-restart")
    val crashRestarted = awaitLifecycleProbe(cdp, "${origin.firstUrl}?lifecycle=crash-restart")
    alphaRestarted.requireIdentity("2.0.0", previousAlphaCount + 1)
    betaRestarted.requireIdentity("1.0.0", previousBetaCount + 1)
    crashRestarted.requireIdentity("2.0.0", previousCrashCount + 1)
    require(alphaRestarted.workerInstance != previousAlphaWorker) {
        "Complete process restart reused alpha's Service Worker instance."
    }
    require(betaRestarted.workerInstance != previousBetaWorker) {
        "Complete process restart reused beta's Service Worker instance."
    }
    require(crashRestarted.workerInstance != previousCrashWorker) {
        "Hard-crash recovery reused the previous Service Worker instance."
    }

    requireCommitted(
        kotlinx.coroutines.runBlocking { alpha.uninstallExtension(LIFECYCLE_EXTENSION_ID) },
        "uninstall alpha",
    )
    requireCommitted(
        kotlinx.coroutines.runBlocking { beta.uninstallExtension(LIFECYCLE_EXTENSION_ID) },
        "uninstall beta",
    )
    requireCommitted(
        kotlinx.coroutines.runBlocking { crash.uninstallExtension(LIFECYCLE_EXTENSION_ID) },
        "uninstall crash Profile",
    )
    require(
        kotlinx.coroutines.runBlocking { alpha.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Alpha registry did not become absent after uninstall." }
    require(
        kotlinx.coroutines.runBlocking { beta.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Beta registry did not become absent after uninstall." }
    require(
        kotlinx.coroutines.runBlocking { crash.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Crash Profile registry did not become absent after uninstall." }
    require(NativeExtensionRuntime.liveNativeOperationCount() == 0L)
}

private fun runExtensionLifecycleStage3() = withExtensionLifecycleBrowsers {
        _, alpha, beta, crash, origin, cdp, _ ->
    require(
        kotlinx.coroutines.runBlocking { alpha.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Alpha extension reappeared after uninstall and restart." }
    require(
        kotlinx.coroutines.runBlocking { beta.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Beta extension reappeared after uninstall and restart." }
    require(
        kotlinx.coroutines.runBlocking { crash.queryExtension(LIFECYCLE_EXTENSION_ID) }.state ==
            KWebExtensionRuntimeState.ABSENT,
    ) { "Crash Profile extension reappeared after uninstall and restart." }

    alpha.navigate("${origin.firstUrl}?lifecycle=alpha-absent")
    beta.navigate("${origin.secondUrl}?lifecycle=beta-absent")
    crash.navigate("${origin.firstUrl}?lifecycle=crash-absent")
    requireNoLifecycleInjection(cdp, "${origin.firstUrl}?lifecycle=alpha-absent")
    requireNoLifecycleInjection(cdp, "${origin.secondUrl}?lifecycle=beta-absent")
    requireNoLifecycleInjection(cdp, "${origin.firstUrl}?lifecycle=crash-absent")
    require(NativeExtensionRuntime.liveNativeOperationCount() == 0L)
}

private fun withExtensionLifecycleBrowsers(
    operation: (
        engine: NativeEngine,
        alpha: NativeBrowser,
        beta: NativeBrowser,
        crash: NativeBrowser,
        origin: BrowserOrigin,
        cdp: CdpClient,
        root: Path,
    ) -> Unit,
) {
    val configuration = runtimeConfiguration()
    require(configuration.remoteDebuggingPort in 1024..65535)
    val engine = NativeEngine.open(configuration)
    try {
        BrowserOrigin().use { origin ->
            val alphaSurface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(800, 600) }
            val betaSurface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(800, 600) }
            val crashSurface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(800, 600) }
            var alpha: NativeBrowser? = null
            var beta: NativeBrowser? = null
            var crash: NativeBrowser? = null
            try {
                val alphaBrowser = NativeBrowser.open(
                    engine = engine,
                    nativeParent = alphaSurface.nativeParent,
                    profilePath = configuration.rootCache.resolve("alpha"),
                    initialUrl = "about:blank",
                    width = 800,
                    height = 600,
                )
                alpha = alphaBrowser
                val betaBrowser = NativeBrowser.open(
                    engine = engine,
                    nativeParent = betaSurface.nativeParent,
                    profilePath = configuration.rootCache.resolve("beta"),
                    initialUrl = "about:blank",
                    width = 800,
                    height = 600,
                )
                beta = betaBrowser
                val crashBrowser = NativeBrowser.open(
                    engine = engine,
                    nativeParent = crashSurface.nativeParent,
                    profilePath = configuration.rootCache.resolve("crash"),
                    initialUrl = "about:blank",
                    width = 800,
                    height = 600,
                )
                crash = crashBrowser
                operation(
                    engine,
                    alphaBrowser,
                    betaBrowser,
                    crashBrowser,
                    origin,
                    CdpClient(engine.remoteDebuggingPort),
                    configuration.rootCache,
                )
            } finally {
                crash?.close()
                beta?.close()
                alpha?.close()
                NativeEngine.onAwtEventDispatchThread(crashSurface::close)
                NativeEngine.onAwtEventDispatchThread(betaSurface::close)
                NativeEngine.onAwtEventDispatchThread(alphaSurface::close)
            }
        }
    } finally {
        NativeEngine.onAwtEventDispatchThread(engine::close)
    }
    require(NativeBrowser.liveNativeBrowserCount() == 0L)
    require(NativeEngine.liveNativeEngineCount() == 0L)
    awaitNativeExtensionOperationCount(0L)
    awaitFfmCallbackOwnerCount(0)
}

private fun awaitPersistedExtensionPreference(profile: Path, extensionId: String) {
    val preferences = profile.resolve("Secure Preferences")
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    var lastFailure: Throwable? = null
    while (System.nanoTime() < deadline) {
        try {
            val root = kotlinx.serialization.json.Json.parseToJsonElement(
                Files.readString(preferences, StandardCharsets.UTF_8),
            ).jsonObject
            val settings = root["extensions"]?.jsonObject
                ?.get("settings")?.jsonObject
            if (settings?.containsKey(extensionId) == true) return
        } catch (error: Throwable) {
            lastFailure = error
        }
        Thread.sleep(100)
    }
    error(
        "Chromium did not persist extension '$extensionId' in '$preferences' before the hard crash; " +
            "last failure=$lastFailure",
    )
}

private fun awaitNativeExtensionOperationCount(expected: Long) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    var actual = NativeExtensionRuntime.liveNativeOperationCount()
    while (actual != expected && System.nanoTime() < deadline) {
        Thread.sleep(25)
        actual = NativeExtensionRuntime.liveNativeOperationCount()
    }
    require(actual == expected) {
        "Native extension operation count remained $actual instead of $expected."
    }
}

private fun awaitFfmCallbackOwnerCount(expected: Int) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    var actual = NativeBindings.liveCallbackOwnerCount()
    while (actual != expected && System.nanoTime() < deadline) {
        Thread.sleep(25)
        actual = NativeBindings.liveCallbackOwnerCount()
    }
    require(actual == expected) {
        "FFM callback owner count remained $actual instead of $expected."
    }
}

private data class LifecycleProbe(
    val extensionId: String,
    val version: String,
    val workerInstance: String,
    val probeCount: Int,
    val senderUrl: String,
) {
    fun requireIdentity(expectedVersion: String, expectedCount: Int) {
        require(extensionId == LIFECYCLE_EXTENSION_ID)
        require(version == expectedVersion) {
            "Lifecycle probe version was '$version' instead of '$expectedVersion'."
        }
        require(probeCount == expectedCount) {
            "Lifecycle probe count was $probeCount instead of $expectedCount."
        }
        require(workerInstance.isNotBlank())
        require(senderUrl.startsWith("http://127.0.0.1:"))
    }
}

private fun awaitLifecycleProbe(cdp: CdpClient, url: String): LifecycleProbe {
    cdp.awaitPage(url)
    val evidenceExpression =
        "JSON.stringify({" +
            "injected: document.documentElement.dataset.kwebLifecycleInjected ?? null," +
            "result: document.documentElement.dataset.kwebLifecycle ?? null," +
            "error: document.documentElement.dataset.kwebLifecycleError ?? null" +
            "})"
    try {
        cdp.awaitExpression(
            "typeof document.documentElement.dataset.kwebLifecycle === 'string' || " +
                "typeof document.documentElement.dataset.kwebLifecycleError === 'string'",
        )
    } catch (error: Throwable) {
        throw IllegalStateException(
            "Lifecycle probe did not reach a terminal page state for '$url': " +
                "${cdp.evaluate(evidenceExpression)}; targets=${cdp.targetSnapshot()}",
            error,
        )
    }
    val evidence = kotlinx.serialization.json.Json.parseToJsonElement(
        cdp.evaluate(evidenceExpression),
    ).jsonObject
    require(evidence["error"]?.jsonPrimitive?.contentOrNull == null) {
        "Lifecycle content script failed for '$url': $evidence; targets=${cdp.targetSnapshot()}"
    }
    val result = kotlinx.serialization.json.Json.parseToJsonElement(
        evidence.getValue("result").jsonPrimitive.content,
    ).jsonObject
    require(result["error"] == null) { "Lifecycle Service Worker returned an error: $result" }
    return LifecycleProbe(
        extensionId = result.getValue("extensionId").jsonPrimitive.content,
        version = result.getValue("version").jsonPrimitive.content,
        workerInstance = result.getValue("workerInstance").jsonPrimitive.content,
        probeCount = result.getValue("probeCount").jsonPrimitive.content.toInt(),
        senderUrl = result.getValue("senderUrl").jsonPrimitive.content,
    )
}

private fun requireNoLifecycleInjection(cdp: CdpClient, url: String) {
    cdp.awaitPage(url)
    Thread.sleep(1_000)
    require(cdp.evaluate("typeof document.documentElement.dataset.kwebLifecycle") == "undefined") {
        "An uninstalled extension injected a lifecycle probe into '$url'."
    }
    require(cdp.evaluate("typeof document.documentElement.dataset.kwebLifecycleError") == "undefined") {
        "An uninstalled extension injected a lifecycle error into '$url'."
    }
}

private fun requireCommitted(result: io.github.kingsword09.kwebshell.extensions.KWebExtensionLifecycleResult, operation: String) {
    require(result.resolution == KWebExtensionLifecycleResolution.COMMITTED && result.failure == null) {
        "$operation did not commit: $result"
    }
}

private data class RawBridgeEvent(
    val requestId: Long,
    val type: NativeBridgeEventType,
    val payload: String,
)

private fun runRawBridgeAbiConformance(
    engine: NativeEngine,
    nativeParent: Long,
    rootCache: Path,
    origin: BrowserOrigin,
    cdp: CdpClient,
) {
    val eventSink = NativeBrowserEventSink { _, _, _, _, _, _, _, _, _ -> }
    val bridgeSink = NativeBridgeEventSink { _, _, _, _, _ -> }
    requireCreateFailure(
        NativeEngine.onAwtEventDispatchThread {
            NativeBindings.browserCreate(
                engine.requireLiveHandle("bridge-config-test"),
                eventSink,
                nativeParent,
                rootCache.resolve("bridge-origin-without-sink").toString(),
                origin.firstUrl,
                0,
                0,
                800,
                600,
                origin.origin,
                null,
            )
        },
        NativeStatus.INVALID_ARGUMENT,
    )
    requireCreateFailure(
        NativeEngine.onAwtEventDispatchThread {
            NativeBindings.browserCreate(
                engine.requireLiveHandle("bridge-config-test"),
                eventSink,
                nativeParent,
                rootCache.resolve("bridge-sink-without-origin").toString(),
                origin.firstUrl,
                0,
                0,
                800,
                600,
                "",
                bridgeSink,
            )
        },
        NativeStatus.INVALID_ARGUMENT,
    )
    requireCreateFailure(
        NativeEngine.onAwtEventDispatchThread {
            NativeBindings.browserCreate(
                engine.requireLiveHandle("bridge-origin-test"),
                eventSink,
                nativeParent,
                rootCache.resolve("bridge-invalid-origin").toString(),
                origin.firstUrl,
                0,
                0,
                800,
                600,
                "https://example.com/path",
                bridgeSink,
            )
        },
        NativeStatus.BRIDGE_ORIGIN_INVALID,
    )
    val created = CountDownLatch(1)
    val closed = CountDownLatch(1)
    val browserHandle = AtomicLong(0)
    val terminalHandleStatus = AtomicInteger(Int.MIN_VALUE)
    val fatalEvents = CopyOnWriteArrayList<String>()
    val browserEvents = NativeBrowserEventSink { _, browser, _, type, _, text, statusCode, _, _ ->
        browserHandle.compareAndSet(0, browser)
        when (NativeBrowserEventType.fromValue(type)) {
            NativeBrowserEventType.CREATED -> created.countDown()
            NativeBrowserEventType.CLOSED -> {
                terminalHandleStatus.compareAndSet(
                    Int.MIN_VALUE,
                    NativeBindings.browserResize(browser, 800, 600),
                )
                closed.countDown()
            }
            NativeBrowserEventType.FATAL_ERROR ->
                fatalEvents += "code='$text' status=$statusCode"
            else -> Unit
        }
    }
    val bridgeEvents = LinkedBlockingQueue<RawBridgeEvent>()
    val rawBridgeSink = NativeBridgeEventSink { _, browser, requestId, type, payload ->
        require(browser == browserHandle.get())
        val eventType = requireNotNull(NativeBridgeEventType.fromValue(type))
        bridgeEvents.put(RawBridgeEvent(requestId, eventType, payload))
    }
    val handle = NativeEngine.onAwtEventDispatchThread {
        NativeBindings.browserCreate(
            engine.requireLiveHandle("raw-bridge-create"),
            browserEvents,
            nativeParent,
            rootCache.resolve("raw-bridge-profile").toString(),
            origin.firstUrl,
            0,
            0,
            800,
            600,
            origin.origin,
            rawBridgeSink,
        )
    }
    require(handle > 0L) { "Raw bridge browser create returned $handle." }
    browserHandle.compareAndSet(0, handle)
    try {
        require(created.await(30, TimeUnit.SECONDS)) {
            "The raw bridge browser did not publish its created event."
        }
        cdp.awaitPage(origin.firstUrl)
        cdp.awaitBridge()
        require(
            cdp.evaluate(
                """
                void (globalThis.rawBridgePromise = new Promise((resolve, reject) => {
                  globalThis.rawBridgeQueryId = __kwebBridgeQuery({
                    request: JSON.stringify({version:1, method:"rawProbe", payload:{value:"请求🙂"}}),
                    persistent: false,
                    onSuccess: resolve,
                    onFailure: reject
                  });
                }));
                "started"
                """.trimIndent(),
            ) == "started",
        )
        val request = requireNotNull(bridgeEvents.poll(30, TimeUnit.SECONDS)) {
            "The raw bridge request event did not arrive."
        }
        require(request.type == NativeBridgeEventType.REQUEST)
        require(request.payload.contains("请求🙂"))
        requireStatus(
            NativeBindings.browserBridgeRespond(handle, request.requestId, "not-json"),
            NativeStatus.BRIDGE_RESPONSE_INVALID,
            "invalid raw bridge response",
        )
        requireStatus(
            NativeBindings.browserBridgeRespond(handle, request.requestId, "{\"accepted\":true}"),
            NativeStatus.OK,
            "valid raw bridge response",
        )
        requireStatus(
            NativeBindings.browserBridgeRespond(handle, request.requestId, "{\"accepted\":true}"),
            NativeStatus.BRIDGE_REQUEST_NOT_FOUND,
            "duplicate raw bridge response",
        )
        val response = cdp.evaluate("globalThis.rawBridgePromise")
        require(response == "{\"accepted\":true}") {
            "The raw bridge returned '$response'."
        }

        require(
            cdp.evaluate(
                """
                globalThis.rawBridgeQueryId = __kwebBridgeQuery({
                  request: JSON.stringify({version:1, method:"rawCancel", payload:{}}),
                  persistent: false,
                  onSuccess: () => {},
                  onFailure: () => {}
                });
                "started"
                """.trimIndent(),
            ) == "started",
        )
        val cancelledRequest = requireNotNull(bridgeEvents.poll(30, TimeUnit.SECONDS)) {
            "The cancellable raw bridge request event did not arrive."
        }
        require(cancelledRequest.type == NativeBridgeEventType.REQUEST)
        require(cdp.evaluate("__kwebBridgeCancel(globalThis.rawBridgeQueryId); 'cancelled'") == "cancelled")
        val cancellation = requireNotNull(bridgeEvents.poll(30, TimeUnit.SECONDS)) {
            "The raw bridge cancellation event did not arrive."
        }
        require(cancellation.type == NativeBridgeEventType.CANCELLED)
        require(cancellation.requestId == cancelledRequest.requestId)
        requireStatus(
            NativeBindings.browserBridgeFail(
                handle,
                cancelledRequest.requestId,
                "{\"code\":\"late\",\"message\":\"late\"}",
            ),
            NativeStatus.BRIDGE_REQUEST_NOT_FOUND,
            "late raw bridge response",
        )
    } finally {
        val prematureTerminal = closed.count == 0L
        val fatalSummary = fatalEvents.joinToString("; ").ifEmpty { null }
        requireStatus(
            NativeEngine.onAwtEventDispatchThread { NativeBindings.browserClose(handle) },
            NativeStatus.OK,
            "close raw bridge browser" +
                (if (prematureTerminal) " after a premature terminal event" else "") +
                (fatalSummary?.let { " with fatal events: $it" } ?: ""),
        )
        require(closed.await(30, TimeUnit.SECONDS)) {
            "The raw bridge browser did not publish its terminal event."
        }
        require(fatalEvents.isEmpty()) {
            "The raw bridge browser reported fatal events: ${fatalEvents.joinToString("; ")}."
        }
        val ownerFailure = NativeBindings.releaseBrowserOwner(handle)
        requireStatus(
            terminalHandleStatus.get(),
            NativeStatus.INVALID_HANDLE,
            "raw bridge terminal callback stale browser resize",
        )
        require(ownerFailure == null) {
            "The raw bridge browser FFM owner reported a callback failure."
        }
    }
    require(NativeBrowser.liveNativeBrowserCount() == 0L)
}

private class BrowserOrigin : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val crossOriginServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val port: Int
    private val bridgeJavascript = Files.readString(requiredPathProperty(BRIDGE_JAVASCRIPT_PROPERTY))

    val origin: String
    val firstUrl: String
    val secondUrl: String
    val crossOriginUrl: String

    init {
        server.createContext("/", ::serve)
        server.executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "KWebShell-browser-origin").also { it.isDaemon = true }
        }
        server.start()
        crossOriginServer.createContext("/") { exchange ->
            val body = "<!doctype html><meta charset=\"utf-8\"><title>cross-origin</title>"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        crossOriginServer.executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "KWebShell-cross-origin").also { it.isDaemon = true }
        }
        crossOriginServer.start()
        port = server.address.port
        origin = "http://127.0.0.1:$port"
        firstUrl = "http://127.0.0.1:$port/first"
        secondUrl = URI("http", null, "127.0.0.1", port, "/路径", "q=🙂", null).toASCIIString()
        crossOriginUrl = "http://127.0.0.1:${crossOriginServer.address.port}/cross"
    }

    private fun serve(exchange: HttpExchange) {
        if (exchange.requestURI.rawPath == "/frame") {
            val frameBody = "<!doctype html><meta charset=\"utf-8\"><title>frame</title>"
                .toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.sendResponseHeaders(200, frameBody.size.toLong())
            exchange.responseBody.use { it.write(frameBody) }
            return
        }
        val second = exchange.requestURI.rawPath != "/first"
        val title = if (second) SECOND_TITLE else FIRST_TITLE
        val script = if (second) {
            "document.title = ${javascriptString(title)};"
        } else {
            "localStorage.setItem('kwebshell-integration', 'persisted');" +
                "document.cookie = 'kwebshell-session=persisted; path=/';" +
                "document.title = ${javascriptString(title)};"
        }
        val body = (
            "<!doctype html><meta charset=\"utf-8\">" +
                "<iframe id=\"bridge-frame\" src=\"/frame\"></iframe>" +
                "<script>$bridgeJavascript</script><script>$script</script>"
            ).toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
        exchange.responseHeaders.set("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() {
        server.stop(0)
        crossOriginServer.stop(0)
        (server.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        (crossOriginServer.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
    }
}

private class ConformanceBridgeTestHandler : ConformanceBridgeHandler {
    private val started = LinkedBlockingQueue<Unit>()
    private val cancelled = LinkedBlockingQueue<Unit>()

    override suspend fun probe(request: ProbeRequest): ProbeResponse = ProbeResponse(
        text = "${request.text}|host🙂",
        count = request.count + 1,
        tags = request.tags.reversed(),
        note = request.note?.uppercase(),
        metadata = request.metadata.copy(ratio = request.metadata.ratio * 2),
        handlerThread = Thread.currentThread().name,
    )

    override suspend fun fail(request: FailureRequest): AckResponse {
        throw KWebBridgeException("conformance.${request.reason}", "拒绝:${request.reason}🙂")
    }

    override suspend fun crash(request: FailureRequest): AckResponse {
        throw IllegalStateException("internal:${request.reason}")
    }

    override suspend fun wait(request: WaitRequest): AckResponse {
        started.put(Unit)
        try {
            delay(request.delayMs.toLong())
            return AckResponse(accepted = true)
        } catch (error: CancellationException) {
            cancelled.put(Unit)
            throw error
        }
    }

    fun awaitStarted(operation: String) {
        require(started.poll(30, TimeUnit.SECONDS) != null) {
            "The bridge handler did not start for $operation."
        }
    }

    fun awaitCancelled(operation: String) {
        require(cancelled.poll(30, TimeUnit.SECONDS) != null) {
            "The bridge handler was not cancelled for $operation."
        }
    }
}

private fun runBridgeConformance(cdp: CdpClient, handler: ConformanceBridgeTestHandler) {
    val probe = kotlinx.serialization.json.Json.parseToJsonElement(
        cdp.evaluate(
            """
            (async () => JSON.stringify(await ConformanceBridge.createClient().probe({
              text: "请求🙂", count: 41, tags: ["甲", "乙"], note: "mix",
              metadata: { enabled: true, ratio: 1.25 }
            })))()
            """.trimIndent(),
        ),
    ).jsonObject
    require(probe["text"]!!.jsonPrimitive.content == "请求🙂|host🙂")
    require(probe["count"]!!.jsonPrimitive.content == "42")
    require(probe["tags"]!!.jsonArray.map { it.jsonPrimitive.content } == listOf("乙", "甲"))
    require(probe["note"]!!.jsonPrimitive.content == "MIX")
    require(probe["metadata"]!!.jsonObject["ratio"]!!.jsonPrimitive.content == "2.5")
    require(probe["handlerThread"]!!.jsonPrimitive.content.startsWith("DefaultDispatcher-worker-"))

    val failure = bridgeFailure(cdp, "ConformanceBridge.createClient().fail({reason:'denied'})")
    require(failure["code"]!!.jsonPrimitive.content == "conformance.denied")
    require(failure["message"]!!.jsonPrimitive.content == "拒绝:denied🙂")

    val unexpected = bridgeFailure(cdp, "ConformanceBridge.createClient().crash({reason:'secret'})")
    require(unexpected["code"]!!.jsonPrimitive.content == "bridge.handler.failed")
    require(unexpected["message"]!!.jsonPrimitive.content == "The bridge handler failed.")

    val unknown = kotlinx.serialization.json.Json.parseToJsonElement(
        cdp.evaluate(
            """
            new Promise(resolve => __kwebBridgeQuery({
              request: JSON.stringify({version:1, method:"missing", payload:{}}),
              persistent: false,
              onSuccess: response => resolve(response),
              onFailure: (_code, message) => resolve(message)
            }))
            """.trimIndent(),
        ),
    ).jsonObject
    require(unknown["code"]!!.jsonPrimitive.content == "bridge.method.unknown")

    val timeout = bridgeFailure(
        cdp,
        "ConformanceBridge.createClient().wait({delayMs:10000},{timeoutMs:100})",
    )
    require(timeout["code"]!!.jsonPrimitive.content == "bridge.call.timeout")
    handler.awaitStarted("timeout")
    handler.awaitCancelled("timeout")

    val aborted = bridgeFailure(
        cdp,
        """
        (() => {
          const controller = new AbortController();
          setTimeout(() => controller.abort(), 100);
          return ConformanceBridge.createClient().wait({delayMs:10000},{signal:controller.signal});
        })()
        """.trimIndent(),
    )
    require(aborted["code"]!!.jsonPrimitive.content == "bridge.call.cancelled")
    handler.awaitStarted("AbortSignal")
    handler.awaitCancelled("AbortSignal")
}

private fun bridgeFailure(cdp: CdpClient, call: String): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.Json.parseToJsonElement(
        cdp.evaluate("(async()=>{try{await ($call);return 'unexpected-success'}catch(e){return JSON.stringify({code:e.code,message:e.message})}})()"),
    ).jsonObject

private fun javascriptString(value: String): String = buildString {
    append('\'')
    value.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '\'' -> append("\\'")
            else -> append(character)
        }
    }
    append('\'')
}

private fun requireProfileDiskState(profile: Path) {
    val cookieStore = if (isWindows()) {
        profile.resolve("Network/Cookies")
    } else {
        profile.resolve("Cookies")
    }
    listOf(
        profile.resolve("Preferences"),
        cookieStore,
        profile.resolve("Local Storage/leveldb/CURRENT"),
    ).forEach { path ->
        require(Files.isRegularFile(path) && Files.size(path) > 0L) {
            "Chromium did not persist required Profile state at '$path'."
        }
    }
}

private fun runCallbackFailureLifecycle() {
    val configuration = NativeEngine.prepareNativeRuntime(runtimeConfiguration())
    val callbackObserved = CountDownLatch(1)
    val closedObserved = CountDownLatch(1)
    val result = NativeEngine.onAwtEventDispatchThread {
        rawCreate(
            configuration,
            NativeEngineEventSink { _, _, type ->
                if (type == NativeEngineEventType.CLOSED.value) {
                    closedObserved.countDown()
                } else {
                    callbackObserved.countDown()
                    error("deliberate FFM callback contract failure")
                }
            },
        )
    }
    require(result > 0L)
    require(callbackObserved.await(30, TimeUnit.SECONDS))
    val closeStatus = NativeEngine.onAwtEventDispatchThread { NativeBindings.engineClose(result) }
    requireStatus(closeStatus, NativeStatus.OK, "FFM callback failure close")
    require(closedObserved.await(30, TimeUnit.SECONDS))
    val callbackFailure = NativeBindings.releaseEngineOwner(result)
    require(callbackFailure is IllegalStateException &&
        callbackFailure.message == "deliberate FFM callback contract failure"
    ) {
        "The FFM owner did not contain the deliberate callback failure: $callbackFailure"
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine FFM callback failure contract passed.")
}

private fun runProfileContextWaiterLifecycle() {
    val configuration = runtimeConfiguration()
    val engine = NativeEngine.open(configuration)
    val surface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(320, 240) }
    val profile = configuration.rootCache.resolve("pending-profile")
    Files.createDirectories(profile)
    val alias = if (isWindows()) {
        configuration.rootCache.resolve("PENDING-PROFILE")
    } else {
        Files.createSymbolicLink(
            configuration.rootCache.resolve("pending-profile-alias"),
            profile.fileName,
        )
    }
    val closed = List(8) { CountDownLatch(1) }
    val fatalEvents = List(8) { CopyOnWriteArrayList<String>() }
    val handles = mutableListOf<Long>()
    try {
        repeat(closed.size) { index ->
            val handle = NativeBindings.browserCreate(
                engine.requireLiveHandle("profile-context-waiter-create"),
                NativeBrowserEventSink { _, _, _, type, _, text, _, _, _ ->
                    when (NativeBrowserEventType.fromValue(type)) {
                        NativeBrowserEventType.FATAL_ERROR -> fatalEvents[index] += text
                        NativeBrowserEventType.CLOSED -> closed[index].countDown()
                        else -> Unit
                    }
                },
                surface.nativeParent,
                (if (index % 2 == 0) profile else alias).toString(),
                "about:blank#pending-$index",
                0,
                0,
                320,
                240,
                "",
                null,
            )
            require(handle > 0L) { "Pending Profile browser $index failed to create: $handle" }
            handles += handle
        }
        handles.forEachIndexed { index, handle ->
            val status = NativeBindings.browserClose(handle)
            require(status == NativeStatus.OK.value || status == NativeStatus.BROWSER_CLOSING.value) {
                "Pending Profile browser $index close returned $status."
            }
        }
        handles.forEachIndexed { index, handle ->
            require(closed[index].await(30, TimeUnit.SECONDS)) {
                "Pending Profile browser $index did not close."
            }
            require(fatalEvents[index].isEmpty()) {
                "Pending Profile browser $index reported ${fatalEvents[index]}."
            }
            require(NativeBindings.releaseBrowserOwner(handle) == null) {
                "Pending Profile browser $index recorded an FFM callback failure."
            }
        }
        handles.clear()

        NativeBrowser.open(
            engine = engine,
            nativeParent = surface.nativeParent,
            profilePath = profile,
            initialUrl = "about:blank#survivor",
            width = 320,
            height = 240,
        ).use { survivor ->
            require(survivor.lifecycle.value == KWebLifecycleState.OPEN)
        }
        NativeBrowser.open(
            engine = engine,
            nativeParent = surface.nativeParent,
            profilePath = alias,
            initialUrl = "about:blank#physical-alias",
            width = 320,
            height = 240,
        ).use { aliasBrowser ->
            require(aliasBrowser.lifecycle.value == KWebLifecycleState.OPEN)
        }
        require(NativeBrowser.liveNativeBrowserCount() == 0L)
        require(NativeBindings.liveCallbackOwnerCount() == 1)
    } finally {
        handles.forEachIndexed { index, handle ->
            runCatching { NativeBindings.browserClose(handle) }
            if (closed[index].await(30, TimeUnit.SECONDS)) {
                runCatching { NativeBindings.releaseBrowserOwner(handle) }
            }
        }
        NativeEngine.onAwtEventDispatchThread(surface::close)
        NativeEngine.onAwtEventDispatchThread(engine::close)
    }
    require(NativeBindings.liveCallbackOwnerCount() == 0)
    println("KWebShell Profile context waiter cancellation contract passed.")
}

private fun runFfmStressLifecycle() {
    val configuration = runtimeConfiguration()
    val engine = NativeEngine.open(configuration)
    val surface = NativeEngine.onAwtEventDispatchThread { ComposeBrowserSurface.create(320, 240) }
    val hostInteractionRobot = if (isWindows()) Robot() else null
    val executor = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "KWebShell-ffm-stress-race").also { it.isDaemon = true }
    }
    var primaryFailure: Throwable? = null
    var currentLifecycle = 0
    try {
        repeat(FFM_BROWSER_STRESS_LIFECYCLES) { index ->
            currentLifecycle = index + 1
            val events = CopyOnWriteArrayList<NativeBrowserEvent>()
            val browser = NativeBrowser.open(
                engine = engine,
                nativeParent = surface.nativeParent,
                profilePath = configuration.rootCache.resolve("stress-profile"),
                initialUrl = "about:blank",
                width = 320,
                height = 240,
                listener = events::add,
            )
            val staleHandle = browser.requireLiveHandle("ffm-stress")
            val start = CountDownLatch(1)
            val command = executor.submit<Throwable?> {
                start.await()
                runCatching { browser.navigate("about:blank#stress-$index") }.exceptionOrNull()
            }
            val close = executor.submit {
                start.await()
                browser.close()
            }
            start.countDown()
            close.get(30, TimeUnit.SECONDS)
            val commandFailure = command.get(30, TimeUnit.SECONDS)
            if (commandFailure != null) {
                require(commandFailure is KWebNativeException && commandFailure.code in setOf(
                    "native.browser.closed",
                    "native.abi.browser-closing",
                    "native.abi.browser-not-ready",
                    "native.abi.invalid-handle",
                )) {
                    "Concurrent FFM command failed unexpectedly at lifecycle $index: $commandFailure"
                }
            }
            browser.close()
            require(browser.lifecycle.value == KWebLifecycleState.CLOSED)
            require(events.first().type == NativeBrowserEventType.CREATED)
            require(events.last().type == NativeBrowserEventType.CLOSED)
            require(events.map(NativeBrowserEvent::sequence) == (1L..events.size.toLong()).toList())
            val callbackCount = events.size
            requireStatus(
                NativeBindings.browserResize(staleHandle, 320, 240),
                NativeStatus.INVALID_HANDLE,
                "FFM stress stale browser resize",
            )
            require(events.size == callbackCount) {
                "A browser callback arrived after lifecycle $index closed."
            }
            if (hostInteractionRobot != null) {
                requireWindowsHostWindowInteractive(surface, index + 1, hostInteractionRobot)
            }
            require(NativeBrowser.liveNativeBrowserCount() == 0L)
            require(NativeExtensionRuntime.liveNativeOperationCount() == 0L)
            require(NativeBindings.liveCallbackOwnerCount() == 1) {
                "Lifecycle $index leaked an FFM callback owner."
            }
            if ((index + 1) % 100 == 0) {
                println("KWebShell FFM browser stress completed ${index + 1} lifecycles.")
                System.out.flush()
            }
        }
    } catch (error: Throwable) {
        val contextualFailure = IllegalStateException("FFM stress lifecycle $currentLifecycle failed.", error)
        primaryFailure = contextualFailure
        throw contextualFailure
    } finally {
        executor.shutdownNow()
        val cleanupFailures = listOfNotNull(
            runCatching { NativeEngine.onAwtEventDispatchThread(surface::close) }.exceptionOrNull(),
            runCatching { NativeEngine.onAwtEventDispatchThread(engine::close) }.exceptionOrNull(),
        )
        val failure = primaryFailure
        if (failure != null) {
            cleanupFailures.forEach(failure::addSuppressed)
        } else if (cleanupFailures.isNotEmpty()) {
            val cleanupFailure = IllegalStateException("FFM stress cleanup failed.", cleanupFailures.first())
            cleanupFailures.drop(1).forEach(cleanupFailure::addSuppressed)
            throw cleanupFailure
        }
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    require(NativeBrowser.liveNativeBrowserCount() == 0L)
    require(NativeExtensionRuntime.liveNativeOperationCount() == 0L)
    require(NativeBindings.liveCallbackOwnerCount() == 0)
    println("KWebShell FFM completed $FFM_BROWSER_STRESS_LIFECYCLES real browser lifecycles.")
}

private fun requireWindowsHostWindowInteractive(
    surface: ComposeBrowserSurface,
    lifecycle: Int,
    robot: Robot,
) {
    val clickObserved = CountDownLatch(1)
    lateinit var listener: AWTEventListener
    val clickPoint = NativeEngine.onAwtEventDispatchThread {
        val window = surface.window
        require(window.isDisplayable && window.isShowing && window.isEnabled) {
            "The ComposeWindow stopped showing after Windows browser lifecycle $lifecycle."
        }
        require(window.windowHandle == surface.nativeParent) {
            "The ComposeWindow HWND changed after Windows browser lifecycle $lifecycle."
        }
        val content = window.contentPane
        require(content.isShowing && content.width > 0 && content.height > 0) {
            "The ComposeWindow content stopped showing after Windows browser lifecycle $lifecycle."
        }
        listener = AWTEventListener { event ->
            if (event is MouseEvent &&
                event.id == MouseEvent.MOUSE_PRESSED &&
                SwingUtilities.getWindowAncestor(event.component) === window
            ) {
                clickObserved.countDown()
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, AWTEvent.MOUSE_EVENT_MASK)
        window.toFront()
        val location = content.locationOnScreen
        Point(location.x + content.width / 2, location.y + content.height / 2)
    }
    try {
        robot.mouseMove(clickPoint.x, clickPoint.y)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        require(clickObserved.await(5, TimeUnit.SECONDS)) {
            "The ComposeWindow did not receive a real mouse click after Windows browser lifecycle $lifecycle."
        }
        NativeEngine.onAwtEventDispatchThread {
            val marker = "KWebShell host interaction $lifecycle"
            surface.window.title = marker
            Toolkit.getDefaultToolkit().sync()
            require(surface.window.isShowing && surface.window.title == marker) {
                "The ComposeWindow stopped responding after Windows browser lifecycle $lifecycle."
            }
        }
    } finally {
        NativeEngine.onAwtEventDispatchThread {
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
        }
    }
}

private fun runListenerFailureLifecycle() {
    val error = try {
        NativeEngine.open(runtimeConfiguration()) { event ->
            if (event.type == NativeEngineEventType.OPENED) {
                throw IllegalStateException("deliberate listener failure")
            }
        }
        null
    } catch (failure: KWebNativeException) {
        failure
    }
    require(error?.code == "native.engine.listener-failed") {
        "Expected a typed listener failure, got $error"
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine listener failure contract passed.")
}

private fun runHolderLifecycle() {
    val engine = NativeEngine.open(runtimeConfiguration())
    println(HOLDER_OPENED_MARKER)
    System.out.flush()
    System.`in`.bufferedReader().readLine()
    engine.close()
    require(NativeEngine.liveNativeEngineCount() == 0L)
}

private fun runInitializationFailureLifecycle() {
    val failure = try {
        NativeEngine.open(runtimeConfiguration())
        null
    } catch (error: KWebNativeException) {
        error
    }
    require(failure?.code == "native.abi.cef-initialize-failed") {
        "Expected the real CEF process-singleton initialization failure, got $failure"
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell engine initialization failure contract passed.")
}

private fun runPortCollisionLifecycle() {
    ServerSocket(0).use { occupied ->
        val configuration = runtimeConfiguration().copy(
            remoteDebuggingPort = occupied.localPort,
        )
        val failure = try {
            NativeEngine.open(configuration)
            null
        } catch (error: KWebNativeException) {
            error
        }
        require(failure?.code == "native.abi.remote-debugging-port-unavailable") {
            "Expected a typed fixed-port collision failure, got $failure"
        }
        require(NativeEngine.liveNativeEngineCount() == 0L)
    }
    println("KWebShell fixed CDP port collision contract passed.")
}

private fun runCdpDisabledLifecycle() {
    val configuration = runtimeConfiguration().copy(remoteDebuggingPort = 0)
    val engine = NativeEngine.open(configuration)
    val probePort = findFreePort()
    try {
        require(!Files.exists(configuration.rootCache.resolve("DevToolsActivePort"))) {
            "CEF created a DevToolsActivePort marker while CDP was disabled."
        }
        CdpClient(probePort).assertUnavailable()
    } finally {
        NativeEngine.onAwtEventDispatchThread(engine::close)
    }
    require(!Files.exists(configuration.rootCache.resolve("DevToolsActivePort"))) {
        "CEF left a DevToolsActivePort marker after disabled-CDP shutdown."
    }
    require(NativeEngine.liveNativeEngineCount() == 0L)
    println("KWebShell disabled CDP contract passed.")
}

private fun rawCreate(configuration: NativeEngineConfiguration, sink: NativeEngineEventSink): Long =
    NativeBindings.engineCreate(
        sink,
        configuration.cefRuntime.toString(),
        configuration.browserSubprocess.toString(),
        configuration.resources.toString(),
        configuration.locales.toString(),
        configuration.rootCache.toString(),
        configuration.log.toString(),
        configuration.remoteDebuggingPort,
    )

private fun rawBrowserCreate(
    engine: NativeEngine,
    nativeParent: Long,
    profile: Path,
    initialUrl: String,
    width: Int,
    height: Int,
): Long = NativeBindings.browserCreate(
    engine.requireLiveHandle("browser-create-test"),
    NativeBrowserEventSink { _, _, _, _, _, _, _, _, _ -> },
    nativeParent,
    profile.toString(),
    initialUrl,
    0,
    0,
    width,
    height,
    "",
    null,
)

private fun runtimeConfiguration(): NativeEngineConfiguration {
    val root = requiredPathProperty(INTEGRATION_ROOT_PROPERTY)
    Files.createDirectories(root)
    return NativeEngineConfiguration(
        cefRuntime = requiredPathProperty(CEF_RUNTIME_PROPERTY),
        browserSubprocess = requiredPathProperty(SUBPROCESS_PROPERTY),
        resources = requiredPathProperty(RESOURCES_PROPERTY),
        locales = requiredPathProperty(LOCALES_PROPERTY),
        rootCache = root,
        log = root.resolve("cef.log"),
        remoteDebuggingPort = System.getProperty(CDP_PORT_PROPERTY)?.toIntOrNull() ?: 0,
    )
}

private class CdpClient(private val port: Int) {
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
    private var host = "127.0.0.1"
    private var activePageTargetId: String? = null

    fun awaitPage(url: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                val version = getLoopback("/json/version")
                val webSocketUrl = version["webSocketDebuggerUrl"]?.jsonPrimitive?.content.orEmpty()
                require(
                    webSocketUrl.startsWith("ws://127.0.0.1:") ||
                        webSocketUrl.startsWith("ws://[::1]:")
                ) {
                    "CDP endpoint was not loopback-only: $version"
                }
                val targets = getArray("/json/list")
                val page = targets.firstOrNull {
                    it["type"]?.jsonPrimitive?.content == "page" &&
                        it["url"]?.jsonPrimitive?.content == url
                }
                if (page != null) {
                    activePageTargetId = page["id"]!!.jsonPrimitive.content
                    webSocket(page["webSocketDebuggerUrl"]!!.jsonPrimitive.content).close()
                    return
                }
            } catch (error: Throwable) {
                lastFailure = error
            }
            Thread.sleep(100)
        }
        error("CDP page discovery timed out: $lastFailure")
    }

    fun evaluate(expression: String): String {
        val targetId = checkNotNull(activePageTargetId) {
            "awaitPage must select a browser page before CDP evaluation."
        }
        val targets = getArray("/json/list")
        val page = targets.singleOrNull { it["id"]?.jsonPrimitive?.content == targetId }
            ?: error("The selected CDP page target '$targetId' is no longer available: $targets")
        webSocket(page["webSocketDebuggerUrl"]!!.jsonPrimitive.content).use { socket ->
            return socket.evaluate(expression)
        }
    }

    fun awaitExpression(expression: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var lastResult: String? = null
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                lastResult = evaluate(expression)
                if (lastResult == "true") return
            } catch (error: Throwable) {
                lastFailure = error
            }
            Thread.sleep(100)
        }
        error("CDP expression did not become true: '$expression', last result=$lastResult, last failure=$lastFailure")
    }

    fun awaitBridge() {
        awaitExpression(
            "typeof globalThis.ConformanceBridge === 'object' && " +
                "typeof globalThis.__kwebBridgeQuery === 'function'",
        )
    }

    fun awaitDevToolsTarget() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (getArray("/json/list").any {
                    it["url"]?.jsonPrimitive?.content?.startsWith("devtools://") == true
            }) return
            Thread.sleep(100)
        }
        error("The DevTools target did not appear in CDP target discovery.")
    }

    fun awaitNoDevToolsTarget() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            val hasDevTools = try {
                getArray("/json/list").any { it["url"]?.jsonPrimitive?.content?.startsWith("devtools://") == true }
            } catch (_: Throwable) {
                false
            }
            if (!hasDevTools) return
            Thread.sleep(100)
        }
        error("The DevTools target remained after close.")
    }

    fun assertUnavailable() {
        val responses = listOf("127.0.0.1", "[::1]").mapNotNull { candidate ->
            try {
                http.sendAsync(
                    HttpRequest.newBuilder(URI("http://$candidate:$port/json/version")).GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).orTimeout(3, TimeUnit.SECONDS).join()
            } catch (_: Throwable) {
                null
            }
        }
        require(responses.isEmpty()) {
            "CDP endpoint remained available on loopback: $responses"
        }
    }

    fun targetSnapshot(): String = try {
        getArray("/json/list").joinToString(prefix = "[", postfix = "]") { target ->
            "{id=${target["id"]?.jsonPrimitive?.contentOrNull}," +
                "type=${target["type"]?.jsonPrimitive?.contentOrNull}," +
                "url=${target["url"]?.jsonPrimitive?.contentOrNull}," +
                "title=${target["title"]?.jsonPrimitive?.contentOrNull}}"
        }
    } catch (error: Throwable) {
        "<unavailable: ${error.message}>"
    }

    private fun get(path: String): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(
            http.send(
                HttpRequest.newBuilder(URI("http://$host:$port$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        ).jsonObject

    private fun getLoopback(path: String): kotlinx.serialization.json.JsonObject {
        return try {
            get(path)
        } catch (first: Throwable) {
            host = "[::1]"
            try {
                get(path)
            } catch (second: Throwable) {
                second.addSuppressed(first)
                throw second
            }
        }
    }

    private fun getArray(path: String): List<kotlinx.serialization.json.JsonObject> =
        kotlinx.serialization.json.Json.parseToJsonElement(
            http.send(
                HttpRequest.newBuilder(URI("http://$host:$port$path")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body(),
        ).jsonArray.map { it.jsonObject }

    private fun webSocket(url: String): CdpWebSocket = CdpWebSocket(url)
}

private class CdpWebSocket(url: String) : AutoCloseable {
    private val messages = CompletableFuture<String>()
    private val socket = HttpClient.newHttpClient().newWebSocketBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .buildAsync(URI(url), object : java.net.http.WebSocket.Listener {
            override fun onOpen(webSocket: java.net.http.WebSocket) {
                webSocket.request(1)
            }

            override fun onText(
                webSocket: java.net.http.WebSocket,
                data: CharSequence,
                last: Boolean,
            ): java.util.concurrent.CompletionStage<*> {
                if (last) messages.complete(data.toString())
                webSocket.request(1)
                return CompletableFuture.completedFuture(null)
            }

            override fun onError(webSocket: java.net.http.WebSocket, error: Throwable) {
                messages.completeExceptionally(error)
            }
        }).join()

    fun evaluate(expression: String): String {
        socket.sendText(
            "{\"id\":1,\"method\":\"Runtime.evaluate\",\"params\":{\"expression\":${jsonString(expression)},\"returnByValue\":true,\"awaitPromise\":true}}",
            true,
        ).join()
        val response = kotlinx.serialization.json.Json.parseToJsonElement(
            messages.orTimeout(10, TimeUnit.SECONDS).join(),
        ).jsonObject
        require(response["result"]!!.jsonObject["exceptionDetails"] == null) {
            "CDP evaluation failed: $response"
        }
        return response["result"]!!.jsonObject["result"]!!.jsonObject["value"]!!.jsonPrimitive.content
    }

    override fun close() {
        socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join()
    }
}

private fun jsonString(value: String): String =
    kotlinx.serialization.json.JsonPrimitive(value).toString()

private fun requiredPathProperty(name: String): Path {
    val value = System.getProperty(name) ?: error("Missing required system property '$name'.")
    return Path.of(value)
}

private fun requiredBooleanProperty(name: String): Boolean {
    val value = System.getProperty(name) ?: error("Missing required system property '$name'.")
    return value.toBooleanStrictOrNull() ?: error("System property '$name' must be exactly 'true' or 'false'.")
}

private fun requireStatus(actual: Int, expected: NativeStatus, operation: String) {
    require(actual == expected.value) {
        "$operation returned $actual instead of ${expected.value} (${expected.id})."
    }
}

private fun requireEquals(expected: Any, actual: Any, operation: String) {
    require(expected == actual) { "$operation was $actual instead of $expected." }
}

private fun requireCreateFailure(result: Long, expected: NativeStatus) {
    require(result == -expected.value.toLong()) {
        "Native create returned $result instead of -${expected.value} (${expected.id})."
    }
}

private fun reportStage(stage: String) {
    println("$STAGE_PREFIX$stage")
    System.out.flush()
}

private data class ChildProcess(
    val process: Process,
    val input: BufferedWriter,
    val lines: CopyOnWriteArrayList<String>,
    val opened: CountDownLatch,
    val crashStateDurable: CountDownLatch,
    val reader: Thread,
) {
    fun output(): String = lines.joinToString(System.lineSeparator())
}

private fun runChildAndRequireSuccess(mode: IntegrationMode, root: Path) {
    val child = startChild(mode, root)
    val timeoutSeconds = if (mode == IntegrationMode.FFM_STRESS) 1_800L else 120L
    require(child.process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(child.process)
        child.process.destroyForcibly()
        "Native engine child '${mode.argument}' timed out.\n${child.output()}\n$diagnostics"
    }
    child.reader.join(5000)
    require(child.process.exitValue() == 0) {
        "Native engine child '${mode.argument}' exited with ${child.process.exitValue()}.\n${child.output()}"
    }
    if (mode == IntegrationMode.FFM_STRESS && isWindows()) {
        val windowsDestructionDiagnostics = child.lines.filter { line ->
            line.contains("Check failed: !is_destroyed_", ignoreCase = true) ||
                line.contains("ui/aura/window.cc", ignoreCase = true) &&
                line.contains("destroy", ignoreCase = true) ||
                line.contains("KWEBSHELL_CLOSE_ERROR", ignoreCase = false)
        }
        require(windowsDestructionDiagnostics.isEmpty()) {
            "Windows FFM stress reported native destruction diagnostics.\n${child.output()}"
        }
    }
    if (mode == IntegrationMode.SUCCESS && isMacOs()) {
        val policyIndexes = listOf(
            MACOS_PEER_VALIDATION_MARKER,
            MACOS_PEER_VALIDATION_FEATURES_MARKER,
            MACOS_PROCESS_REQUIREMENT_METRICS_MARKER,
            MACOS_BROWSER_POLICY_MARKER,
        ).map { marker ->
            child.lines.mapIndexedNotNull { index, line ->
                index.takeIf { line == marker }
            }
        }
        val openedIndex = child.lines.indexOf("${STAGE_PREFIX}opened")
        require(policyIndexes.all { it.size == 1 && openedIndex > it.single() }) {
            "The macOS process policies were not applied exactly once before OnContextInitialized.\n${child.output()}"
        }
    }
}

private fun runChildAndRequireCrash(mode: IntegrationMode, root: Path) {
    require(mode == IntegrationMode.EXTENSION_LIFECYCLE_CRASH)
    val child = startChild(mode, root)
    require(child.crashStateDurable.await(120, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(child.process)
        child.process.destroyForcibly()
        "Native engine crash child did not publish durable state.\n${child.output()}\n$diagnostics"
    }
    child.process.destroyForcibly()
    require(child.process.waitFor(60, TimeUnit.SECONDS)) {
        val diagnostics = collectTimeoutDiagnostics(child.process)
        child.process.destroyForcibly()
        "Native engine crash child resisted forced termination.\n${child.output()}\n$diagnostics"
    }
    child.reader.join(5000)
    require(child.process.exitValue() != 0) {
        "Native engine crash child reported successful exit after forced termination.\n${child.output()}"
    }
    require(child.lines.count { it == LIFECYCLE_CRASH_DURABLE_MARKER } == 1) {
        "Native engine crash child did not prove durable Chromium state exactly once.\n${child.output()}"
    }
}

private fun isMacOs(): Boolean = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

private fun isWindows(): Boolean = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

private fun collectTimeoutDiagnostics(process: Process): String = buildString {
    appendLine("JVM thread dump:")
    appendLine(
        runDiagnostic(
            Path.of(
                System.getProperty("java.home"),
                "bin",
                if (isWindows()) "jcmd.exe" else "jcmd",
            ).toString(),
            process.pid().toString(),
            "Thread.print",
            "-l",
        ),
    )
    if (isMacOs()) {
        appendLine("macOS native sample:")
        appendLine(runDiagnostic("/usr/bin/sample", process.pid().toString(), "5"))
    }
}

private fun runDiagnostic(vararg command: String): String {
    val diagnostic = try {
        ProcessBuilder(*command).redirectErrorStream(true).start()
    } catch (error: Exception) {
        return "Could not start '${command.first()}': $error"
    }
    val output = StringBuilder()
    val reader = thread(name = "KWebShell-engine-timeout-diagnostic", isDaemon = true) {
        diagnostic.inputStream.bufferedReader().use { stream -> output.append(stream.readText()) }
    }
    if (!diagnostic.waitFor(15, TimeUnit.SECONDS)) {
        diagnostic.destroyForcibly()
        reader.join(1_000)
        return "Diagnostic '${command.first()}' timed out.\n$output"
    }
    reader.join(1_000)
    return output.toString()
}

private fun startChild(mode: IntegrationMode, root: Path): ChildProcess {
    Files.createDirectories(root)
    val javaExecutable = Path.of(
        System.getProperty("java.home"),
        "bin",
        if (isWindows()) "java.exe" else "java",
    )
    val inheritedProperties = listOf(
        NATIVE_LIBRARY_PATH_PROPERTY,
        CEF_RUNTIME_PROPERTY,
        SUBPROCESS_PROPERTY,
        RESOURCES_PROPERTY,
        LOCALES_PROPERTY,
        BRIDGE_JAVASCRIPT_PROPERTY,
        EXTENSION_PATH_PROPERTY,
        LIFECYCLE_V1_PROPERTY,
        LIFECYCLE_V2_PROPERTY,
        EXPECT_CUSTOM_EXTENSION_RUNTIME_PROPERTY,
    )
    val command = buildList {
        add(javaExecutable.toString())
        add("--module-path=${System.getProperty(DESKTOP_MODULE_PATH_PROPERTY)}")
        add("--patch-module=$DESKTOP_MODULE_NAME=${System.getProperty(DESKTOP_TEST_CLASSES_PROPERTY)}")
        add("--add-modules=$DESKTOP_MODULE_NAME,java.net.http,jdk.httpserver")
        add("--enable-native-access=$DESKTOP_MODULE_NAME")
        add("-Djava.awt.headless=false")
        inheritedProperties.forEach { name ->
            add("-D$name=${System.getProperty(name) ?: error("Missing '$name'.")}")
        }
        add("-D$INTEGRATION_ROOT_PROPERTY=$root")
        if (mode == IntegrationMode.SUCCESS ||
            mode == IntegrationMode.EXTENSION_LIFECYCLE_CRASH ||
            mode.name.startsWith("EXTENSION_LIFECYCLE_STAGE")
        ) {
            add("-D$CDP_PORT_PROPERTY=${findFreePort()}")
        }
        add("-cp")
        add(System.getProperty(DESKTOP_INTEGRATION_CLASSPATH_PROPERTY))
        add("-m")
        add("$DESKTOP_MODULE_NAME/$MAIN_CLASS")
        add(mode.argument)
    }
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val lines = CopyOnWriteArrayList<String>()
    val opened = CountDownLatch(1)
    val crashStateDurable = CountDownLatch(1)
    val reader = thread(name = "KWebShell-engine-child-${mode.argument}", isDaemon = true) {
        process.inputStream.bufferedReader().useLines { outputLines ->
            outputLines.forEach { line ->
                lines += line
                if (line == HOLDER_OPENED_MARKER) {
                    opened.countDown()
                }
                if (line == LIFECYCLE_CRASH_DURABLE_MARKER) {
                    crashStateDurable.countDown()
                }
            }
        }
    }
    return ChildProcess(
        process,
        process.outputStream.bufferedWriter(),
        lines,
        opened,
        crashStateDurable,
        reader,
    )
}

private fun findFreePort(): Int = ServerSocket().use { socket ->
    socket.reuseAddress = false
    socket.bind(InetSocketAddress("127.0.0.1", 0))
    socket.localPort
}
