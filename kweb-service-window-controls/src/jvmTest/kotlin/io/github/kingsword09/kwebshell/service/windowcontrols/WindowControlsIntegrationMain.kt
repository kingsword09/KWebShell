package io.github.kingsword09.kwebshell.service.windowcontrols

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngine
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.desktop.KWebPageDispatcherFactory
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathKind
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathsConfiguration
import io.github.kingsword09.kwebshell.service.apppaths.JvmKWebAppPaths
import io.github.kingsword09.kwebshell.services.KWebCapabilityFact
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceProviderCatalog
import io.github.kingsword09.kwebshell.services.KWebServiceProviderConfiguration
import io.github.kingsword09.kwebshell.services.KWebServiceProviderDeclaration
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.EventQueue
import java.awt.Window
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val ROOT_PROPERTY = "kweb.window-controls.integration.root"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val BRIDGE_JAVASCRIPT_PROPERTY = "kweb.window-controls.bridge.javascript"
private const val SERVICES_LIBRARY_PROPERTY = "kweb.services.native.library.path"
private const val WINDOW_CONTROLS_LIBRARY_PROPERTY = "kweb.window.controls.native.library.path"

public fun main() {
    val root = requiredPath(ROOT_PROPERTY).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val profileRoot = root.resolve("profiles")
    Files.createDirectories(profileRoot)
    val server = WindowControlsServer(Files.readString(requiredPath(BRIDGE_JAVASCRIPT_PROPERTY)))
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell window controls"
            setBounds(120, 120, 900, 650)
            isVisible = true
            require(isDisplayable && isShowing && windowHandle != 0L)
        }
    }
    val visibleWindows = visibleWindows()
    val eventScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val events = CopyOnWriteArrayList<KWebWindowEvent>()
    val closeRequests = CopyOnWriteArrayList<KWebWindowCloseRequest>()
    var eventJob: Job? = null
    var closeRequestJob: Job? = null
    var installedService: KWebWindowControls? = null
    var installedAppPaths: KWebAppPaths? = null
    var engine: KWebDesktopEngine? = null
    var profile: KWebProfile? = null
    val pages = mutableListOf<KWebPage>()
    var failure: Throwable? = null
    try {
        val cdpPort = findFreePort()
        val liveEngine = KWebDesktop.openEngine(
            KWebDesktopEngineConfiguration(
                cefRuntime = requiredPath(CEF_RUNTIME_PROPERTY),
                browserSubprocess = requiredPath(SUBPROCESS_PROPERTY),
                resources = requiredPath(RESOURCES_PROPERTY),
                locales = requiredPath(LOCALES_PROPERTY),
                rootCache = profileRoot,
                log = profileRoot.resolve("cef.log"),
                remoteDebuggingPort = cdpPort,
            ),
        )
        engine = liveEngine
        val servicesLibrary = requiredPath(SERVICES_LIBRARY_PROPERTY)
        val providerConfiguration = KWebServiceProviderConfiguration(
            target = KWebTarget.parse(currentTarget()),
            ownerId = "window-controls-provider-fixture",
            facts = setOf(
                KWebCapabilityFact(
                    id = "native-services-library",
                    available = Files.isRegularFile(servicesLibrary),
                ),
            ),
            providers = listOf(
                KWebServiceProviderDeclaration(
                    providerId = "app-paths.ffm-native",
                    key = KWebAppPaths.Key,
                    contractVersion = KWebAppPaths.DESCRIPTOR.version,
                    scope = KWebAppPaths.DESCRIPTOR.scope,
                    supportedTargets = KWebAppPaths.DESCRIPTOR.supportedTargets,
                    requiredFacts = setOf("native-services-library"),
                    dependencies = emptySet(),
                    factory = { _ ->
                        JvmKWebAppPaths.open(
                            servicesLibrary,
                            KWebAppPathsConfiguration(
                                applicationId = "io.github.kwebshell.provider-fixture",
                                applicationDataRoot = root.resolve("app-data").toString(),
                                sessionDataRoot = root.resolve("session-data").toString(),
                            ),
                        )
                    },
                ),
                KWebServiceProviderDeclaration(
                    providerId = "window-controls.ffm-native",
                    key = KWebWindowControls.Key,
                    contractVersion = KWebWindowControls.DESCRIPTOR.version,
                    scope = KWebWindowControls.DESCRIPTOR.scope,
                    supportedTargets = KWebWindowControls.DESCRIPTOR.supportedTargets,
                    requiredFacts = emptySet(),
                    dependencies = emptySet(),
                    factory = { environment ->
                        require(environment.scope == KWebWindowControls.DESCRIPTOR.scope)
                        JvmKWebWindowControls.open(
                            window,
                            KWebWindowRegistration("main-window"),
                            requiredPath(WINDOW_CONTROLS_LIBRARY_PROPERTY),
                        )
                    },
                ),
            ),
        )
        val catalogJson = KWebServiceProviderCatalog.of(providerConfiguration.catalog())
        val providerReport = liveEngine.nativeServices.installProviders(providerConfiguration)
        require(providerReport.startupOrder == listOf("app-paths", "window-controls")) {
            "Provider startup order was not deterministic: ${providerReport.startupOrder}"
        }
        require(providerReport.providerOrder == listOf("app-paths.ffm-native", "window-controls.ffm-native")) {
            "Provider selection was not deterministic: ${providerReport.providerOrder}"
        }
        val service = liveEngine.nativeServices.require(KWebWindowControls.Key)
        installedService = service
        val appPaths = liveEngine.nativeServices.require(KWebAppPaths.Key)
        installedAppPaths = appPaths
        eventJob = eventScope.launch { service.events.collect(events::add) }
        closeRequestJob = eventScope.launch { service.closeRequests.collect(closeRequests::add) }
        val directReport = exerciseDirectControls(service, window)
        val eventBufferReport = exerciseEventBufferBoundary(requiredPath(WINDOW_CONTROLS_LIBRARY_PROPERTY))
        trace("native-transition-quiescence")
        runBlocking { delay(750L) }
        val hierarchyReport = exerciseHierarchyAndClose(service, window)
        val resolvedUserData = runBlocking { appPaths.resolve(KWebAppPathKind.USER_DATA) }
        require(resolvedUserData.path.isNotBlank() && resolvedUserData.source.isNotBlank()) {
            "The provider-installed KWebAppPaths service did not resolve a real path."
        }
        val lifecycleReport = buildJsonObject {
            put("schemaVersion", 1)
            put("target", currentTarget())
            put("ownerId", "window-controls-provider-fixture")
            put("providerOrder", JsonArray(providerReport.providerOrder.map { JsonPrimitive(it) }))
            put("startupOrder", JsonArray(providerReport.startupOrder.map { JsonPrimitive(it) }))
            put("closeOrder", JsonArray(providerReport.startupOrder.asReversed().map { JsonPrimitive(it) }))
            put("catalog", Json.parseToJsonElement(catalogJson))
        }
        Files.writeString(
            root.resolve("provider-lifecycle-report.json"),
            Json.encodeToString(JsonObject.serializer(), lifecycleReport) + "\n",
        )
        val liveProfile = runBlocking { liveEngine.openProfile("window-controls") }
        profile = liveProfile
        val cdp = KWebExampleCdpClient(cdpPort, 30_000)
        val allowGestures = KWebUserGestureRegistry()
        var allowedPageId = ""
        val allowPolicy = KWebServicePermissionPolicy.exact(
            KWebWindowControls.DESCRIPTOR.operations.map { operation ->
                KWebServiceGrant(KWebWindowControls.DESCRIPTOR.id, operation.id)
            }.toSet(),
        )
        val denyPolicy = KWebServicePermissionPolicy.exact(emptySet())
        val policyEngine = KWebServicePolicyEngine(
            rendererGrants = allowPolicy,
            gestures = allowGestures,
            consentStore = KWebInMemoryConsentStore("window-controls-fixture"),
            osConsent = null,
            audit = KWebPolicyAudit(),
        )
        val denyPolicyEngine = KWebServicePolicyEngine(
            rendererGrants = denyPolicy,
            gestures = KWebUserGestureRegistry(),
            consentStore = KWebInMemoryConsentStore("window-controls-fixture-denied"),
            osConsent = null,
            audit = KWebPolicyAudit(),
        )
        val allowedPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(
                    window,
                    server.origin,
                    KWebPageDispatcherFactory { pageId ->
                        allowedPageId = pageId
                        service.bridgeDispatcher(
                            policyEngine,
                            KWebPolicySubject(
                                engineId = "window-controls-fixture",
                                profileId = "window-controls",
                                pageId = pageId,
                                origin = server.origin,
                                scope = KWebServiceScope.APPLICATION,
                            ),
                        )
                    },
                ),
                server.indexUrl,
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += allowedPage
        val cefParentReport = exerciseCefParentStability(service, window, allowedPage)
        val registrationBoundaryReport = exerciseRegistrationBoundaries(
            requiredPath(WINDOW_CONTROLS_LIBRARY_PROPERTY),
        )
        allowGestures.mint(
            KWebGestureBinding(
                engineId = "window-controls-fixture",
                profileId = "window-controls",
                pageId = allowedPageId,
                origin = server.origin,
            ),
        )
        cdp.awaitPage(server.indexUrl)
        cdp.openPageSession(server.indexUrl).use { session ->
            session.awaitTrue("typeof globalThis.WindowControlsBridge === 'object'")
            require(session.evaluateString("typeof WindowControlsBridge.createClient().requestClose") == "function")
            require(session.evaluateString("typeof WindowControlsBridge.createClient().setTitle") == "undefined")
            require(
                session.evaluateString(
                    "typeof document.getElementById('window-frame').contentWindow.WindowControlsBridge",
                ) == "object",
            )
            require(
                session.evaluateString(
                    "typeof document.getElementById('window-frame').contentWindow.__kwebBridgeQuery",
                ) == "undefined",
            )
            require(
                session.evaluateString(
                    "(async()=>{try{await document.getElementById('window-frame').contentWindow.WindowControlsBridge.createClient().requestClose({enabled:true});return 'unexpected'}catch(e){return e.code}})()",
                ) == "bridge.transport.failed",
            )
            val state = session.evaluateJson(
                "WindowControlsBridge.createClient().requestClose({enabled:true})",
            )
            require(state["id"]?.jsonPrimitive?.content == "main-window")
        }
        val windowControlsReport = buildJsonObject {
            put("schemaVersion", 1)
            put("target", currentTarget())
            put("providerId", "window-controls.ffm-native")
            put("nativeProviderId", (service as ComposeKWebWindowControls).nativeProviderId())
            put("nativeParentStable", onAwtThread { window.isDisplayable && window.windowHandle != 0L })
            put("directControls", directReport)
            put("eventBuffer", eventBufferReport)
            put("hierarchyAndClose", hierarchyReport)
            put("registrationBoundaries", registrationBoundaryReport)
            put("cefParentStability", cefParentReport)
        }
        Files.writeString(
            root.resolve("window-controls-report.json"),
            Json.encodeToString(JsonObject.serializer(), windowControlsReport) + "\n",
        )
        val rendererClose = awaitCloseRequest(closeRequests)
        val deniedClose = runBlocking {
            service.respondToClose(rendererClose.requestId, KWebWindowCloseDecision.DENY)
        }
        require(deniedClose.outcome == KWebWindowCloseOutcome.DENIED)
        require(service.lifecycle.value == KWebLifecycleState.OPEN)

        val deniedPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(
                    window,
                    server.origin,
                    KWebPageDispatcherFactory { pageId ->
                        service.bridgeDispatcher(
                            denyPolicyEngine,
                            KWebPolicySubject(
                                engineId = "window-controls-fixture",
                                profileId = "window-controls",
                                pageId = pageId,
                                origin = server.origin,
                                scope = KWebServiceScope.APPLICATION,
                            ),
                        )
                    },
                ),
                "${server.indexUrl}?denied",
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += deniedPage
        cdp.awaitPage("${server.indexUrl}?denied")
        cdp.openPageSession("${server.indexUrl}?denied").use { session ->
            session.awaitTrue("typeof globalThis.WindowControlsBridge === 'object'")
            require(
                session.evaluateString(
                    "(async()=>{try{await WindowControlsBridge.createClient().requestClose({enabled:true});return 'unexpected'}catch(e){return e.code}})()",
                ) == KWebServiceErrorCode.PERMISSION_DENIED,
            )
        }
        deniedPage.close()
        pages.remove(deniedPage)

        runBlocking { allowedPage.navigate(server.crossOriginUrl) }
        cdp.awaitPage(server.crossOriginUrl)
        cdp.openPageSession(server.crossOriginUrl).use { session ->
            require(session.evaluateString("typeof globalThis.WindowControlsBridge") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        allowedPage.close()
        pages.remove(allowedPage)

        val unconfiguredPage = runBlocking {
            liveProfile.openPage(
                KWebDesktop.composeWindowHost(window),
                "${server.indexUrl}?unconfigured",
                KWebRect(0, 0, 800, 560),
            )
        }
        pages += unconfiguredPage
        cdp.awaitPage("${server.indexUrl}?unconfigured")
        cdp.openPageSession("${server.indexUrl}?unconfigured").use { session ->
            require(session.evaluateString("typeof globalThis.WindowControlsBridge") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        unconfiguredPage.close()
        pages.remove(unconfiguredPage)

        liveProfile.close()
        profile = null
        liveEngine.close()
        engine = null
        cdp.assertUnavailable()
        require(service.lifecycle.value == KWebLifecycleState.CLOSED)
        require(installedAppPaths.lifecycle.value == KWebLifecycleState.CLOSED) {
            "The provider-installed KWebAppPaths service did not close with the registry."
        }
        require(onAwtThread { window.isDisplayable && window.isShowing }) {
            "Closing the Engine disposed the caller-owned ComposeWindow."
        }
        require(visibleWindows() == visibleWindows) {
            "Window controls or CEF created an unexpected visible top-level window."
        }
        require(events.isNotEmpty()) { "Window controls emitted no ordered state events." }
        require(events.map { it.sequence } == (1L..events.size.toLong()).toList()) {
            "Window control event sequence is not contiguous: $events"
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        pages.toList().asReversed().forEach { page ->
            try {
                if (page.lifecycle.value != KWebLifecycleState.CLOSED) page.close()
            } catch (error: Throwable) {
                failure = failure.append(error)
            }
        }
        try {
            profile?.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            engine?.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            installedService?.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        runBlocking { eventJob?.cancelAndJoin() }
        runBlocking { closeRequestJob?.cancelAndJoin() }
        eventScope.cancel()
        try {
            onAwtThread { window.dispose() }
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            server.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
    }
    failure?.let { throw it }
    verifyDisposedOwnerClosesService()
    println("KWebShell Compose window controls passed direct and exact-origin CEF integration.")
}

private fun exerciseDirectControls(service: KWebWindowControls, window: ComposeWindow): JsonObject {
    lateinit var report: JsonObject
    runBlocking {
        trace("snapshot-open")
        require(service.snapshot().placement == KWebWindowPlacement.FLOATING)
        trace("invalid-title")
        val invalidTitle = runCatching { service.setTitle("invalid\u0000title") }.exceptionOrNull()
        require(invalidTitle is io.github.kingsword09.kwebshell.core.KWebConfigurationException &&
            invalidTitle.code == KWebServiceErrorCode.REQUEST_INVALID
        )
        trace("title-and-bounds")
        require(service.setTitle("KWebShell direct title").title == "KWebShell direct title")
        require(service.setBounds(KWebWindowBounds(140, 145, 920, 680)).bounds == KWebWindowBounds(140, 145, 920, 680))
        val constrained = service.setConstraints(KWebWindowConstraints(320, 240, 1280, 900))
        require(constrained.constraints == KWebWindowConstraints(320, 240, 1280, 900))
        trace("attention")
        require(service.requestAttention().attention == KWebWindowAttention.REQUESTED)
        require(service.clearAttention().attention == KWebWindowAttention.NONE)
        trace("resizable")
        require(!service.setResizable(false).resizable)
        require(service.setResizable(true).resizable)
        trace("hidden-focus")
        require(!service.setVisible(false).visible)
        val hiddenFocus = runCatching { service.focus() }.exceptionOrNull()
        require(hiddenFocus is KWebNativeException && hiddenFocus.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE)
        trace("visible-focus")
        require(service.setVisible(true).visible)
        service.focus()
        trace("minimize-restore")
        require(service.minimize().placement == KWebWindowPlacement.MINIMIZED)
        val restored = service.restore()
        require(restored.placement == KWebWindowPlacement.FLOATING)
        trace("maximize-restore")
        require(service.setMaximized(true).placement == KWebWindowPlacement.MAXIMIZED)
        val maximizedBounds = runCatching {
            service.setBounds(KWebWindowBounds(100, 100, 800, 600))
        }.exceptionOrNull()
        require(maximizedBounds is KWebNativeException &&
            maximizedBounds.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE
        )
        trace("maximized-fullscreen")
        val maximizedFullscreen = service.setFullscreen(KWebWindowFullscreenMode.FULLSCREEN)
        require(maximizedFullscreen.fullscreen == KWebWindowFullscreenMode.FULLSCREEN)
        val afterMaximizedFullscreen = service.setFullscreen(KWebWindowFullscreenMode.WINDOWED)
        require(afterMaximizedFullscreen.placement == KWebWindowPlacement.MAXIMIZED)
        require(service.setMaximized(false).placement == KWebWindowPlacement.FLOATING)
        trace("always-on-top")
        if (onAwtThread { window.isAlwaysOnTopSupported }) {
            require(service.setAlwaysOnTop(true).alwaysOnTop)
            require(!service.setAlwaysOnTop(false).alwaysOnTop)
        } else {
            val failure = runCatching { service.setAlwaysOnTop(true) }.exceptionOrNull()
            require(failure is KWebNativeException && failure.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE)
        }
        trace("fullscreen-enter")
        val beforeFullscreen = service.snapshot()
        require(!beforeFullscreen.displayId.isNullOrBlank())
        require(beforeFullscreen.displayScale?.isFinite() == true && beforeFullscreen.displayScale > 0.0)
        val fullscreen = service.setFullscreen(KWebWindowFullscreenMode.FULLSCREEN)
        require(fullscreen.fullscreen == KWebWindowFullscreenMode.FULLSCREEN)
        trace("fullscreen-to-kiosk-denied")
        val fullscreenToKiosk = runCatching {
            service.setFullscreen(KWebWindowFullscreenMode.KIOSK)
        }.exceptionOrNull()
        require(fullscreenToKiosk is KWebNativeException &&
            fullscreenToKiosk.code == KWebServiceErrorCode.OPERATION_UNAVAILABLE &&
            service.snapshot().fullscreen == KWebWindowFullscreenMode.FULLSCREEN
        )
        trace("fullscreen-exit")
        val afterFullscreen = service.setFullscreen(KWebWindowFullscreenMode.WINDOWED)
        require(afterFullscreen.fullscreen == KWebWindowFullscreenMode.WINDOWED)
        require(afterFullscreen.bounds == beforeFullscreen.bounds) {
            "Fullscreen exit did not restore the caller-owned logical bounds."
        }
        require(afterFullscreen.displayId == beforeFullscreen.displayId)
        require(afterFullscreen.displayScale == beforeFullscreen.displayScale)
        val observedDisplayId = requireNotNull(beforeFullscreen.displayId)
        val observedDisplayScale = requireNotNull(beforeFullscreen.displayScale)
        trace("kiosk-enter")
        val kiosk = service.setFullscreen(KWebWindowFullscreenMode.KIOSK)
        require(!kiosk.movable && !kiosk.minimizable && !kiosk.maximizable && !kiosk.closable && !kiosk.resizable)
        trace("kiosk-exit")
        val afterKiosk = service.setFullscreen(KWebWindowFullscreenMode.WINDOWED)
        require(afterKiosk.movable && afterKiosk.minimizable && afterKiosk.maximizable && afterKiosk.closable)
        trace("close-negotiate")
        val firstClose = service.requestClose()
        val repeatedClose = service.requestClose()
        require(firstClose.outcome == KWebWindowCloseOutcome.PENDING)
        require(repeatedClose.requestId == firstClose.requestId && repeatedClose.outcome == KWebWindowCloseOutcome.PENDING)
        val deniedClose = service.respondToClose(firstClose.requestId, KWebWindowCloseDecision.DENY)
        require(deniedClose.outcome == KWebWindowCloseOutcome.DENIED)
        val duplicateResponse = runCatching {
            service.respondToClose(firstClose.requestId, KWebWindowCloseDecision.ALLOW)
        }.exceptionOrNull()
        require(duplicateResponse is KWebNativeException &&
            duplicateResponse.code == "service.close-request-resolved")
        report = buildJsonObject {
            put("initialBounds", boundsJson(beforeFullscreen.bounds))
            put("fullscreenRestoredBounds", boundsJson(afterFullscreen.bounds))
            put("fullscreenModeObserved", fullscreen.fullscreen.id)
            put("displayIdObserved", observedDisplayId)
            put("displayScaleObserved", observedDisplayScale)
            put("displayIdentityRestored", afterFullscreen.displayId == beforeFullscreen.displayId)
            put("kioskCapabilitiesRestricted", !kiosk.movable && !kiosk.resizable)
            put("kioskCapabilitiesRestored", afterKiosk.movable && afterKiosk.closable)
            put("closeRequestsCoalesced", repeatedClose.requestId == firstClose.requestId)
            put("closeDeniedWithoutDisposal", service.lifecycle.value == KWebLifecycleState.OPEN)
            put("duplicateCloseResponseRejected", true)
        }
    }
    return report
}

private fun exerciseEventBufferBoundary(nativeLibrary: Path): JsonObject {
    trace("event-buffer-boundary-create")
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell event buffer fixture"
            setBounds(160, 160, 420, 280)
            isVisible = true
        }
    }
    val service = JvmKWebWindowControls.open(
        window,
        KWebWindowRegistration("event-buffer-fixture"),
        nativeLibrary,
    )
    val releaseCollector = CompletableDeferred<Unit>()
    val received = AtomicInteger(0)
    val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val collector = collectorScope.launch {
        service.events.collect {
            received.incrementAndGet()
            releaseCollector.await()
        }
    }
    var failure: Throwable? = null
    var successfulEvents = 0
    try {
        runBlocking { delay(100L) }
        for (index in 0..ComposeKWebWindowControls.EVENT_REPLAY) {
            val result = runCatching {
                runBlocking { service.setTitle("event-$index") }
            }
            val error = result.exceptionOrNull()
            if (error != null) {
                failure = error
                break
            }
            successfulEvents++
        }
        require(successfulEvents == ComposeKWebWindowControls.EVENT_REPLAY) {
            "The event buffer failed before its published capacity: $successfulEvents"
        }
        val terminalFailure = failure as? KWebNativeException
            ?: error("The event buffer did not produce a typed native failure: $failure")
        require(terminalFailure.code == KWebServiceErrorCode.NATIVE_FAILED)
        require(service.lifecycle.value == KWebLifecycleState.FAILED)
    } finally {
        releaseCollector.complete(Unit)
        runBlocking { collector.cancelAndJoin() }
        collectorScope.cancel()
        service.close()
        onAwtThread { window.dispose() }
    }
    return buildJsonObject {
        put("replayCapacity", ComposeKWebWindowControls.EVENT_REPLAY)
        put("successfulEventsBeforeFailure", successfulEvents)
        put("terminalFailureObserved", true)
        put("collectorReceivedBeforeBlock", received.get())
    }
}

private fun exerciseRegistrationBoundaries(nativeLibrary: Path): JsonObject {
    trace("hierarchy-depth-boundary")
    val depthWindows = mutableListOf<ComposeWindow>()
    val depthServices = mutableListOf<ComposeKWebWindowControls>()
    var depthOverflowRejected = false
    try {
        var parentId: String? = null
        for (index in 1..64) {
            val id = "depth-$index"
            val window = onAwtThread {
                ComposeWindow().apply {
                    title = id
                    setBounds(40 + index, 40 + index, 240, 180)
                    isVisible = true
                }
            }
            depthWindows += window
            val service = JvmKWebWindowControls.open(
                window,
                KWebWindowRegistration(id, parentId = parentId),
                nativeLibrary,
            ) as ComposeKWebWindowControls
            depthServices += service
            parentId = id
        }
        runBlocking {
            depthServices.forEach { it.awaitNativeParentage() }
        }
        val overflowWindow = onAwtThread {
            ComposeWindow().apply {
                title = "depth-overflow"
                setBounds(100, 100, 240, 180)
                isVisible = true
            }
        }
        try {
            runCatching {
                JvmKWebWindowControls.open(
                    overflowWindow,
                    KWebWindowRegistration("depth-65", parentId = "depth-64"),
                    nativeLibrary,
                )
                }.onSuccess { error("The 65th hierarchy level was accepted: $it") }
                .onFailure { error ->
                    depthOverflowRejected = error is KWebConfigurationException &&
                        error.code == "window.registration-invalid"
                }
        } finally {
            onAwtThread { overflowWindow.dispose() }
        }
        require(depthOverflowRejected) { "Hierarchy depth 65 did not fail with a typed configuration error." }
    } finally {
        depthServices.firstOrNull()?.close()
        runBlocking { depthServices.forEach { it.awaitNativeTeardown() } }
        depthWindows.forEach { window -> onAwtThread { if (window.isDisplayable) window.dispose() } }
    }

    trace("application-window-limit-boundary")
    val limitWindows = mutableListOf<ComposeWindow>()
    val limitServices = mutableListOf<KWebWindowControls>()
    var limitOverflowRejected = false
    try {
        repeat(255) { index ->
            val window = onAwtThread {
                ComposeWindow().apply {
                    title = "limit-$index"
                    setBounds(10, 10, 180, 140)
                    isVisible = true
                    isVisible = false
                }
            }
            limitWindows += window
            limitServices += JvmKWebWindowControls.open(
                window,
                KWebWindowRegistration("limit-$index"),
                nativeLibrary,
            )
        }
        val overflowWindow = onAwtThread {
            ComposeWindow().apply {
                title = "limit-overflow"
                setBounds(10, 10, 180, 140)
                isVisible = true
                isVisible = false
            }
        }
        try {
            runCatching {
                JvmKWebWindowControls.open(
                    overflowWindow,
                    KWebWindowRegistration("limit-overflow"),
                    nativeLibrary,
                )
                }.onSuccess { error("The 257th application window was accepted: $it") }
                .onFailure { error ->
                    limitOverflowRejected = error is KWebConfigurationException &&
                        error.code == "window.registration-invalid"
                }
        } finally {
            onAwtThread { overflowWindow.dispose() }
        }
        require(limitOverflowRejected) { "Application window 257 did not fail with a typed configuration error." }
    } finally {
        limitServices.asReversed().forEach { it.close() }
        limitWindows.forEach { window -> onAwtThread { if (window.isDisplayable) window.dispose() } }
    }
    return buildJsonObject {
        put("depth64Accepted", depthServices.size == 64)
        put("depth65Rejected", depthOverflowRejected)
        put("applicationWindow256Accepted", limitServices.size == 255)
        put("applicationWindow257Rejected", limitOverflowRejected)
    }
}

private fun exerciseCefParentStability(
    service: KWebWindowControls,
    window: ComposeWindow,
    page: KWebPage,
): JsonObject {
    val parentHandleBefore = onAwtThread { window.windowHandle }
    require(parentHandleBefore != 0L)
    require(page.lifecycle.value == KWebLifecycleState.OPEN)
    runBlocking {
        service.setBounds(KWebWindowBounds(150, 155, 880, 640))
        page.setBounds(KWebRect(20, 20, 760, 520))
        page.setSurfaceState(visible = true, focused = true)
        service.setFullscreen(KWebWindowFullscreenMode.FULLSCREEN)
        page.setBounds(KWebRect(24, 24, 720, 480))
        service.setFullscreen(KWebWindowFullscreenMode.WINDOWED)
    }
    val parentHandleAfter = onAwtThread { window.windowHandle }
    require(parentHandleAfter == parentHandleBefore) {
        "The caller-owned native parent handle changed while the CEF child was resized."
    }
    return buildJsonObject {
        put("callerParentHandleUnchanged", true)
        put("pageLifecycleObservedOpen", page.lifecycle.value == KWebLifecycleState.OPEN)
        put("browserResizeObserved", true)
        put("parentStableThroughFullscreen", true)
    }
}

private fun exerciseHierarchyAndClose(parentService: KWebWindowControls, parentWindow: ComposeWindow): JsonObject {
    trace("modal-window-create")
    val modalWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell modal fixture"
            setBounds(180, 180, 420, 280)
            isVisible = true
        }
    }
    trace("modal-window-created")
    val nativeLibrary = requiredPath(WINDOW_CONTROLS_LIBRARY_PROPERTY)
    val modalService = JvmKWebWindowControls.open(
        modalWindow,
        KWebWindowRegistration(
            id = "modal-fixture",
            parentId = parentService.registration.id,
            modality = KWebWindowModality.WINDOW_MODAL,
        ),
        nativeLibrary,
    )
    trace("modal-registered")
    val modalDisabled = onAwtThread { !parentWindow.isEnabled }
    trace("modal-owner-disabled")
    val modalPending = runBlocking { modalService.requestClose() }
    require(modalPending.outcome == KWebWindowCloseOutcome.PENDING)
    trace("modal-close-pending")
    runBlocking { modalService.respondToClose(modalPending.requestId, KWebWindowCloseDecision.DENY) }
    trace("modal-deny")
    val modalForced = runBlocking { modalService.forceClose(KWebWindowForceCloseReason.TEST) }
    trace("modal-force-close")
    require(modalForced.outcome == KWebWindowCloseOutcome.FORCED)
    require(modalService.lifecycle.value == KWebLifecycleState.CLOSED)
    val parentReenabled = onAwtThread { parentWindow.isEnabled }
    trace("modal-owner-reenabled")

    trace("application-modal-create")
    val applicationOwnerWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell application modal owner"
            setBounds(200, 200, 420, 280)
            isVisible = true
        }
    }
    val applicationOwnerService = JvmKWebWindowControls.open(
        applicationOwnerWindow,
        KWebWindowRegistration("application-modal-owner"),
        nativeLibrary,
    )
    val applicationModalWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell application modal fixture"
            setBounds(220, 220, 380, 240)
            isVisible = true
        }
    }
    val applicationModalService = JvmKWebWindowControls.open(
        applicationModalWindow,
        KWebWindowRegistration(
            id = "application-modal-fixture",
            parentId = applicationOwnerService.registration.id,
            modality = KWebWindowModality.APPLICATION_MODAL,
        ),
        nativeLibrary,
    )
    val applicationOwnersDisabled = onAwtThread {
        !parentWindow.isEnabled && !applicationOwnerWindow.isEnabled
    }
    runBlocking { applicationModalService.focus() }
    val applicationModalForced = runBlocking {
        applicationModalService.forceClose(KWebWindowForceCloseReason.TEST)
    }
    val applicationOwnersReenabled = onAwtThread {
        parentWindow.isEnabled && applicationOwnerWindow.isEnabled
    }
    val ownerClosePending = runBlocking { applicationOwnerService.requestClose() }
    val ownerForced = runBlocking {
        applicationOwnerService.forceClose(KWebWindowForceCloseReason.APPLICATION_SHUTDOWN)
    }
    onAwtThread {
        applicationOwnerWindow.dispose()
        applicationModalWindow.dispose()
    }
    trace("application-modal-complete")

    trace("hierarchy-root-create")
    val rootWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell hierarchy root fixture"
            setBounds(220, 220, 440, 300)
            isVisible = true
        }
    }
    trace("hierarchy-root-created")
    trace("hierarchy-child-create")
    val childWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell hierarchy child fixture"
            setBounds(240, 240, 360, 240)
            isVisible = true
        }
    }
    trace("hierarchy-child-created")
    trace("hierarchy-grandchild-create")
    val grandchildWindow = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell hierarchy grandchild fixture"
            setBounds(260, 260, 320, 220)
            isVisible = true
        }
    }
    trace("hierarchy-grandchild-created")
    trace("hierarchy-root-register")
    val rootService = JvmKWebWindowControls.open(
        rootWindow,
        KWebWindowRegistration("hierarchy-root"),
        nativeLibrary,
    )
    trace("hierarchy-child-register")
    val childService = JvmKWebWindowControls.open(
        childWindow,
        KWebWindowRegistration("hierarchy-child", parentId = "hierarchy-root"),
        nativeLibrary,
    )
    trace("hierarchy-grandchild-register")
    val grandchildService = JvmKWebWindowControls.open(
        grandchildWindow,
        KWebWindowRegistration("hierarchy-grandchild", parentId = "hierarchy-child"),
        nativeLibrary,
    )
    trace("hierarchy-native-parentage-wait")
    runBlocking {
        (childService as ComposeKWebWindowControls).awaitNativeParentage()
        (grandchildService as ComposeKWebWindowControls).awaitNativeParentage()
    }
    trace("hierarchy-native-parentage-observed")
    trace("hierarchy-root-close")
    rootService.close()
    val descendantsClosedBeforeReturn =
        childService.lifecycle.value == KWebLifecycleState.CLOSED &&
            grandchildService.lifecycle.value == KWebLifecycleState.CLOSED &&
            rootService.lifecycle.value == KWebLifecycleState.CLOSED
    require(descendantsClosedBeforeReturn)
    runBlocking {
        (childService as ComposeKWebWindowControls).awaitNativeTeardown()
        (grandchildService as ComposeKWebWindowControls).awaitNativeTeardown()
    }
    trace("hierarchy-native-teardown-observed")
    trace("hierarchy-teardown-complete")
    trace("hierarchy-windows-dispose")
    onAwtThread {
        rootWindow.dispose()
        childWindow.dispose()
        grandchildWindow.dispose()
        modalWindow.dispose()
    }
    trace("hierarchy-windows-disposed")
    return buildJsonObject {
        put("modalOwnerDisabled", modalDisabled)
        put("modalOwnerReenabled", parentReenabled)
        put("forcedCloseOutcome", modalForced.outcome.id)
        put("applicationModalOwnersDisabled", applicationOwnersDisabled)
        put("applicationModalOwnersReenabled", applicationOwnersReenabled)
        put("applicationModalForcedOutcome", applicationModalForced.outcome.id)
        put("applicationForceCloseWins", ownerClosePending.outcome == KWebWindowCloseOutcome.PENDING &&
            ownerForced.outcome == KWebWindowCloseOutcome.FORCED)
        put("childBeforeParentTeardown", descendantsClosedBeforeReturn)
        put("nativeParentageObserved", true)
        put("nativeTeardownObserved", true)
        put("visibleTopLevelWindowsCreatedByService", false)
    }
}

private fun boundsJson(bounds: KWebWindowBounds): JsonObject = buildJsonObject {
    put("x", bounds.x)
    put("y", bounds.y)
    put("width", bounds.width)
    put("height", bounds.height)
}

private fun trace(step: String) {
    println("KWEBSHELL_WINDOW_CONTROLS_STEP:$step")
}

private fun verifyDisposedOwnerClosesService() {
    val window = onAwtThread {
        ComposeWindow().apply {
            setSize(320, 240)
            isVisible = true
        }
    }
    val service = JvmKWebWindowControls.open(
        window,
        KWebWindowRegistration("disposed-window"),
        requiredPath(WINDOW_CONTROLS_LIBRARY_PROPERTY),
    )
    onAwtThread { window.dispose() }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (service.lifecycle.value != KWebLifecycleState.CLOSED && System.nanoTime() < deadline) {
        Thread.sleep(25)
    }
    require(service.lifecycle.value == KWebLifecycleState.CLOSED)
    val failure = runCatching { runBlocking { service.snapshot() } }.exceptionOrNull()
    require(failure is KWebNativeException && failure.code == KWebServiceErrorCode.OWNER_CLOSED)
}

private class WindowControlsServer(bridgeJavascript: String) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "KWebShell-window-controls-http").apply { isDaemon = true }
    }
    private val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cross = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val index = (
        "<!doctype html><meta charset=\"utf-8\"><title>window controls</title>" +
            "<iframe id=\"window-frame\" src=\"/frame\"></iframe>" +
            "<script>$bridgeJavascript</script>"
        ).toByteArray(StandardCharsets.UTF_8)
    private val frame = (
        "<!doctype html><meta charset=\"utf-8\"><title>frame</title>" +
            "<script>$bridgeJavascript</script>"
        ).toByteArray(StandardCharsets.UTF_8)

    init {
        allowed.executor = executor
        allowed.createContext("/") { exchange ->
            val body = when {
                exchange.requestURI.path == "/frame" -> frame
                exchange.requestURI.query == "unconfigured" -> "<!doctype html><title>unconfigured</title>".toByteArray()
                else -> index
            }
            send(exchange, body)
        }
        cross.executor = executor
        cross.createContext("/") { exchange -> send(exchange, "<!doctype html><title>cross</title>".toByteArray()) }
        allowed.start()
        cross.start()
    }

    val origin: String get() = "http://127.0.0.1:${allowed.address.port}"
    val indexUrl: String get() = "$origin/index.html"
    val crossOriginUrl: String get() = "http://127.0.0.1:${cross.address.port}/cross"

    override fun close() {
        allowed.stop(0)
        cross.stop(0)
        executor.shutdownNow()
        require(executor.awaitTermination(5, TimeUnit.SECONDS))
    }

    private fun send(exchange: HttpExchange, body: ByteArray) {
        try {
            exchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } finally {
            exchange.close()
        }
    }
}

private fun KWebExampleCdpSession.awaitTrue(expression: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline) {
        if (runCatching { evaluateString(expression) }.getOrNull() == "true") return
        Thread.sleep(50)
    }
    error("CDP expression did not become true: $expression")
}

private fun KWebExampleCdpSession.evaluateString(expression: String): String =
    evaluate(expression).value?.jsonPrimitive?.content
        ?: error("CDP expression returned no primitive: $expression")

private fun KWebExampleCdpSession.evaluateJson(call: String): kotlinx.serialization.json.JsonObject {
    val encoded = evaluateString("(async()=>JSON.stringify(await $call))()")
    return Json.parseToJsonElement(encoded).jsonObject
}

private fun visibleWindows(): Set<Window> = onAwtThread {
    Window.getWindows().filterTo(linkedSetOf()) { it.isShowing }
}

private fun awaitCloseRequest(requests: List<KWebWindowCloseRequest>): KWebWindowCloseRequest {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (System.nanoTime() < deadline) {
        requests.lastOrNull()?.let { return it }
        Thread.sleep(25)
    }
    error("The renderer close request was not observed.")
}

private fun requiredPath(name: String): Path =
    Path.of(System.getProperty(name) ?: error("Missing system property '$name'."))

private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
    socket.localPort.takeIf { it in 1024..65535 } ?: error("The OS allocated an invalid CDP port.")
}

private fun <T> onAwtThread(action: () -> T): T {
    if (EventQueue.isDispatchThread()) return action()
    val result = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { result.set(runCatching(action)) }
    return result.get().getOrThrow()
}

private fun Throwable?.append(error: Throwable): Throwable = this?.also { current ->
    if (current !== error) current.addSuppressed(error)
} ?: error


private fun currentTarget(): String {
    val operatingSystem = System.getProperty("os.name").lowercase(java.util.Locale.ROOT).let {
        when {
            it.startsWith("windows") -> "windows"
            it.startsWith("mac") -> "macos"
            it.startsWith("linux") -> "linux"
            else -> error("Unsupported provider fixture operating system: $it")
        }
    }
    val architecture = when (System.getProperty("os.arch").lowercase(java.util.Locale.ROOT)) {
        "x86_64", "amd64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> error("Unsupported provider fixture architecture: ${System.getProperty("os.arch")}")
    }
    return "$operatingSystem-$architecture"
}
