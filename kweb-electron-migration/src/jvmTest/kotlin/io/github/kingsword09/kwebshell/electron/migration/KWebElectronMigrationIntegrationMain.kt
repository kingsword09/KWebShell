package io.github.kingsword09.kwebshell.electron.migration

import androidx.compose.ui.awt.ComposeWindow
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatchers
import io.github.kingsword09.kwebshell.bridge.KWebBridgeRoute
import io.github.kingsword09.kwebshell.bridge.KWebBridgeProtocol
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.desktop.KWebDesktop
import io.github.kingsword09.kwebshell.desktop.KWebDesktopEngineConfiguration
import io.github.kingsword09.kwebshell.desktop.KWebPageDispatcherFactory
import io.github.kingsword09.kwebshell.desktop.KWebPageStreamDispatcherFactory
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpClient
import io.github.kingsword09.kwebshell.example.support.KWebExampleCdpSession
import io.github.kingsword09.kwebshell.service.apppaths.JvmKWebAppPaths
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathKind
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPaths
import io.github.kingsword09.kwebshell.service.apppaths.KWebAppPathsConfiguration
import io.github.kingsword09.kwebshell.service.apppaths.bridgeDispatcher
import io.github.kingsword09.kwebshell.service.clipboard.JvmKWebClipboard
import io.github.kingsword09.kwebshell.service.clipboard.KWebClipboard
import io.github.kingsword09.kwebshell.service.clipboard.bridgeDispatcher as clipboardBridgeDispatcher
import io.github.kingsword09.kwebshell.service.notifications.JvmKWebNotifications
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationActivation
import io.github.kingsword09.kwebshell.service.notifications.KWebNotificationPermissionStatus
import io.github.kingsword09.kwebshell.service.notifications.KWebNotifications
import io.github.kingsword09.kwebshell.service.notifications.bridgeDispatcher as notificationsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebActivationBatch
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebActivationSource
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationBackendStart
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationEvent
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationLifecycleBackend
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationLifecycleConfiguration
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationLifecycleController
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationRegistrationOperation
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationRegistrationReport
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationStartResult
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebRelaunchResult
import io.github.kingsword09.kwebshell.service.files.JvmKWebFiles
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesConfiguration
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesPageOwner
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResolver
import io.github.kingsword09.kwebshell.service.files.JvmKWebWorkspace
import io.github.kingsword09.kwebshell.service.files.KWebFileGrant
import io.github.kingsword09.kwebshell.service.files.KWebFileOwnerScope
import io.github.kingsword09.kwebshell.service.files.KWebFiles
import io.github.kingsword09.kwebshell.service.shell.JvmKWebShell
import io.github.kingsword09.kwebshell.service.shell.JvmKWebShellHandle
import io.github.kingsword09.kwebshell.service.shell.KWebShell
import io.github.kingsword09.kwebshell.service.shell.KWebShellConfiguration
import io.github.kingsword09.kwebshell.service.shell.bridgeDispatcher as shellBridgeDispatcher
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceGrant
import io.github.kingsword09.kwebshell.services.policy.KWebInMemoryConsentStore
import io.github.kingsword09.kwebshell.services.policy.KWebConsentRequest
import io.github.kingsword09.kwebshell.services.policy.KWebConsentStatus
import io.github.kingsword09.kwebshell.services.policy.KWebOsConsentProvider
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyAudit
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureRegistry
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.awt.EventQueue
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.SwingUtilities

private const val NATIVE_LIBRARY_PROPERTY = "kweb.native.library.path"
private const val SERVICES_LIBRARY_PROPERTY = "kweb.services.native.library.path"
private const val CEF_RUNTIME_PROPERTY = "kweb.engine.cef.runtime.path"
private const val SUBPROCESS_PROPERTY = "kweb.engine.subprocess.path"
private const val RESOURCES_PROPERTY = "kweb.engine.resources.path"
private const val LOCALES_PROPERTY = "kweb.engine.locales.path"
private const val ROOT_PROPERTY = "kweb.migration.integration.root"
private const val APP_PATHS_BRIDGE_PROPERTY = "kweb.migration.app-paths.bridge.javascript"
private const val FILES_BRIDGE_PROPERTY = "kweb.migration.files.bridge.javascript"
private const val SHELL_LIBRARY_PROPERTY = "kweb.shell.native.library.path"
private const val SHELL_BRIDGE_PROPERTY = "kweb.migration.shell.bridge.javascript"
private const val CLIPBOARD_LIBRARY_PROPERTY = "kweb.clipboard.native.library.path"
private const val CLIPBOARD_BRIDGE_PROPERTY = "kweb.migration.clipboard.bridge.javascript"
private const val NOTIFICATIONS_LIBRARY_PROPERTY = "kweb.notifications.native.library.path"
private const val NOTIFICATION_ACTION_HELPER_PROPERTY = "kweb.migration.notification.action.helper"
private const val WINDOWS_NOTIFICATION_ACTION_HELPER_PROPERTY = "kweb.migration.notification.action.helper.windows"
private const val NOTIFICATIONS_BRIDGE_PROPERTY = "kweb.migration.notifications.bridge.javascript"
private const val PRELOAD_PROPERTY = "kweb.migration.preload.javascript"
private const val REPORT_PROPERTY = "kweb.migration.report"

public fun main() {
    val root = requiredPath(ROOT_PROPERTY).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val report = KWebElectronMigrationJson.format.decodeFromString<KWebElectronCompatibilityReport>(
        Files.readString(requiredPath(REPORT_PROPERTY)),
    )
    KWebElectronCompatibilityReportValidator.validate(report)
    require(report.migrationReady) { "The migration fixture cannot run with a blocked compatibility report." }
    val appRoot = root.resolve("app-data")
    val sessionRoot = root.resolve("session-data")
    val profileRoot = root.resolve("profiles")
    val workspaceRoot = root.resolve("files-workspace")
    Files.createDirectories(appRoot)
    Files.createDirectories(sessionRoot)
    Files.createDirectories(profileRoot)
    Files.createDirectories(workspaceRoot)
    val port = findFreePort()
    val server = MigrationFixtureServer(
        appPathsBridge = Files.readString(requiredPath(APP_PATHS_BRIDGE_PROPERTY)),
        filesBridge = Files.readString(requiredPath(FILES_BRIDGE_PROPERTY)),
        shellBridge = Files.readString(requiredPath(SHELL_BRIDGE_PROPERTY)),
        clipboardBridge = Files.readString(requiredPath(CLIPBOARD_BRIDGE_PROPERTY)),
        notificationsBridge = Files.readString(requiredPath(NOTIFICATIONS_BRIDGE_PROPERTY)),
        preload = Files.readString(requiredPath(PRELOAD_PROPERTY)),
    )
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell Electron Migration Fixture"
            setSize(960, 700)
            setLocationRelativeTo(null)
            isVisible = true
            require(isDisplayable && isShowing && windowHandle != 0L)
        }
    }
    val applicationActivations = CopyOnWriteArrayList<KWebApplicationEvent.Activation>()
    val applicationTarget = io.github.kingsword09.kwebshell.core.KWebTarget.parse(
        System.getProperty("kweb.migration.target") ?: "macos-arm64",
    )
    val applicationLifecycle = KWebApplicationLifecycleController(
        KWebApplicationLifecycleConfiguration(
            applicationId = "io.github.kwebshell.migration.fixture",
            target = applicationTarget,
            packageIdentity = "io.github.kwebshell.migration.fixture",
            registeredSchemes = setOf("kweb"),
            registeredExtensions = emptySet(),
            packageRoot = root.toString(),
            transportRoot = root.resolve("application-lifecycle-transport").toString(),
            relaunchExecutable = null,
            isPackaged = false,
        ),
        object : KWebApplicationLifecycleBackend {
            override suspend fun acquire(
                configuration: KWebApplicationLifecycleConfiguration,
                initial: KWebActivationBatch,
                onActivation: suspend (KWebActivationBatch) -> Unit,
            ): KWebApplicationBackendStart = KWebApplicationBackendStart.Primary

            override suspend fun release() = Unit
            override suspend fun relaunch(preservePendingActivation: Boolean): KWebRelaunchResult =
                KWebRelaunchResult.UNAVAILABLE

            override suspend fun installAssociations(): KWebApplicationRegistrationReport = registration(true)
            override suspend fun removeAssociations(): KWebApplicationRegistrationReport = registration(false)

            private fun registration(registered: Boolean) = KWebApplicationRegistrationReport(
                operation = if (registered) KWebApplicationRegistrationOperation.INSTALL
                else KWebApplicationRegistrationOperation.REMOVE,
                target = applicationTarget,
                applicationId = "io.github.kwebshell.migration.fixture",
                provider = "migration-fixture.lifecycle-test-backend",
                registered = registered,
                observedDigest = "fixture-only",
            )
        },
    )
    require(
        runBlocking {
            applicationLifecycle.start(
                KWebActivationBatch(
                    source = KWebActivationSource.INITIAL_ARGUMENTS,
                    uris = listOf("kweb://migration-fixture/start"),
                ),
            )
        } == KWebApplicationStartResult.PRIMARY,
    ) { "The RFC 0006 application lifecycle controller did not acquire its fixture owner." }
    val applicationActivationCollector = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        applicationLifecycle.events.collect { event ->
            if (event is KWebApplicationEvent.Activation) applicationActivations += event
        }
    }
    val notificationActivations = CopyOnWriteArrayList<KWebNotificationActivation>()
    val notifications = run {
        var opened: KWebNotifications? = null
        try {
            if (applicationTarget.id.startsWith("macos-")) {
                val mismatch = runCatching {
                    JvmKWebNotifications.open(
                        requiredPath(NOTIFICATIONS_LIBRARY_PROPERTY),
                        "io.github.kwebshell.migration.fixture",
                        "io.github.kwebshell.migration.wrong-bundle",
                    ) { error("A mismatched notification identity cannot route activation.") }
                }
                mismatch.getOrNull()?.close()
                require((mismatch.exceptionOrNull() as? io.github.kingsword09.kwebshell.core.KWebNativeException)?.code ==
                    "notifications.platform-unavailable") { "The native provider accepted a mismatched macOS bundle identity." }
            }
            val notifications = JvmKWebNotifications.open(
                requiredPath(NOTIFICATIONS_LIBRARY_PROPERTY),
                "io.github.kwebshell.migration.fixture",
                if (applicationTarget.id.startsWith("macos-")) "io.github.kwebshell.migration.notifications"
                else "io.github.kwebshell.migration.fixture",
            ) { activation ->
                notificationActivations += activation
                applicationLifecycle.acceptProtocolActivation(activation.toProtocolUri())
            }
            opened = notifications
            val initialPermission = runBlocking { notifications.permission() }
            val permission = if (initialPermission.status == KWebNotificationPermissionStatus.NOT_DETERMINED) {
                val authorization = if (applicationTarget.id.startsWith("macos-")) {
                    ProcessBuilder(requiredPath(NOTIFICATION_ACTION_HELPER_PROPERTY).toString(), "--authorize")
                        .inheritIO().start()
                } else null
                try {
                    runBlocking { notifications.requestPermission() }
                } finally {
                    if (authorization != null && !authorization.waitFor(2, TimeUnit.SECONDS)) {
                        authorization.destroyForcibly().waitFor()
                    }
                }
            } else initialPermission
            val expectedPermission = if (applicationTarget.id.startsWith("linux-")) {
                KWebNotificationPermissionStatus.NOT_APPLICABLE
            } else KWebNotificationPermissionStatus.GRANTED
            require(permission.status == expectedPermission) {
                "Notification fixture: provider=${permission.provider} permission=${permission.status}; expected=$expectedPermission"
            }
            println("Notification fixture: provider=${permission.provider} permission=${permission.status}")
            notifications
        } catch (error: Throwable) {
            val cleanup = listOf<() -> Unit>(
                { opened?.close() },
                { applicationActivationCollector.cancel() },
                { applicationLifecycle.close() },
                { server.close() },
                { onAwtThread { window.dispose() } },
            )
            cleanup.forEach { action ->
                runCatching(action).exceptionOrNull()?.let(error::addSuppressed)
            }
            throw error
        }
    }
    val gestures = KWebUserGestureRegistry()
    val engine = KWebDesktop.openEngine(
        KWebDesktopEngineConfiguration(
            cefRuntime = requiredPath(CEF_RUNTIME_PROPERTY),
            browserSubprocess = requiredPath(SUBPROCESS_PROPERTY),
            resources = requiredPath(RESOURCES_PROPERTY),
            locales = requiredPath(LOCALES_PROPERTY),
            rootCache = profileRoot,
            log = profileRoot.resolve("cef.log"),
            remoteDebuggingPort = port,
            userGestureIssuer = gestures,
            engineId = "migration-fixture",
        ),
    )
    val appPaths = JvmKWebAppPaths.open(
        requiredPath(SERVICES_LIBRARY_PROPERTY),
        KWebAppPathsConfiguration(
            applicationId = "io.github.kwebshell.migration.fixture",
            applicationDataRoot = appRoot.toString(),
            sessionDataRoot = sessionRoot.toString(),
        ),
    )
    val clipboard = JvmKWebClipboard.open(requiredPath(CLIPBOARD_LIBRARY_PROPERTY))
    engine.nativeServices.install(KWebAppPaths.Key, appPaths)
    engine.nativeServices.install(KWebClipboard.Key, clipboard)
    engine.nativeServices.install(KWebNotifications.Key, notifications)
    val profile = runBlocking { engine.openProfile("electron-migration-fixture") }
    val cdp = KWebExampleCdpClient(port, 30_000)
    val allowPolicy = KWebServicePermissionPolicy.exact(
        setOf(KWebServiceGrant(KWebAppPaths.DESCRIPTOR.id, "resolve")),
    )
    val denyPolicy = KWebServicePermissionPolicy.exact(emptySet())
    val allowPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = allowPolicy,
        gestures = KWebUserGestureRegistry(),
        consentStore = KWebInMemoryConsentStore("migration-fixture"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val denyPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = denyPolicy,
        gestures = KWebUserGestureRegistry(),
        consentStore = KWebInMemoryConsentStore("migration-fixture-denied"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val filesPolicy = KWebServicePermissionPolicy.exact(
        KWebFiles.DESCRIPTOR.operations.map { operation ->
            KWebServiceGrant(KWebFiles.DESCRIPTOR.id, operation.id)
        }.toSet(),
    )
    val filesPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = filesPolicy,
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-files"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val clipboardPolicy = KWebServicePermissionPolicy.exact(
        setOf("read", "read-payload", "write", "clear", "close-payload").map { operation ->
            KWebServiceGrant(KWebClipboard.DESCRIPTOR.id, operation)
        }.toSet(),
    )
    val clipboardPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = clipboardPolicy,
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-clipboard"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val deniedClipboardPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(emptySet()),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-clipboard-denied"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val notificationConsentStore = KWebInMemoryConsentStore("migration-notifications")
    val notificationPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(
            KWebNotifications.DESCRIPTOR.operations.filter { it.rendererPermission != null }.map { operation ->
                KWebServiceGrant(KWebNotifications.DESCRIPTOR.id, operation.id)
            }.toSet(),
        ),
        gestures = gestures,
        consentStore = notificationConsentStore,
        osConsent = object : KWebOsConsentProvider {
            override val facility: String = "fixture.notifications"
            override suspend fun status(request: KWebConsentRequest): KWebConsentStatus = KWebConsentStatus.GRANTED
        },
        audit = KWebPolicyAudit(),
    )
    KWebNotifications.DESCRIPTOR.operations.filter { it.requiresOsConsent }.forEach { operation ->
        notificationConsentStore.record(
            KWebConsentRequest(KWebNotifications.DESCRIPTOR.id, operation.id, server.origin, "fixture.notifications"),
            granted = true,
            decidedBy = "hosted-fixture",
        )
    }
    val deniedNotificationPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(emptySet()),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-notifications-denied"),
        osConsent = object : KWebOsConsentProvider {
            override val facility: String = "fixture.notifications"
            override suspend fun status(request: KWebConsentRequest): KWebConsentStatus = KWebConsentStatus.GRANTED
        },
        audit = KWebPolicyAudit(),
    )
    val deniedShellPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = KWebServicePermissionPolicy.exact(emptySet()),
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-shell-denied"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val shellPolicy = KWebServicePermissionPolicy.exact(
        KWebShell.DESCRIPTOR.operations.map { operation ->
            KWebServiceGrant(KWebShell.DESCRIPTOR.id, operation.id)
        }.toSet(),
    )
    val shellPolicyEngine = KWebServicePolicyEngine(
        rendererGrants = shellPolicy,
        gestures = gestures,
        consentStore = KWebInMemoryConsentStore("migration-shell"),
        osConsent = null,
        audit = KWebPolicyAudit(),
    )
    val slowDispatcher = SlowAppPathsDispatcher(
        appPaths.bridgeDispatcher(
            allowPolicyEngine,
            KWebPolicySubject(
                engineId = "migration-fixture",
                profileId = "electron-migration-fixture",
                pageId = "bridge-page",
                origin = server.origin,
                scope = KWebServiceScope.APPLICATION,
            ),
        ),
    )
    val filesOwner = AtomicReference<JvmKWebFilesPageOwner?>()
    val shellService = AtomicReference<JvmKWebShellHandle?>()
    val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val activatedNotificationActions = CopyOnWriteArrayList<String>()
    var activePage: KWebPage? = null
    var deniedFiles: KWebFiles? = null
    var deniedShell: JvmKWebShellHandle? = null
    var failure: Throwable? = null
    try {
        val directHome = runBlocking { appPaths.resolve(KWebAppPathKind.HOME) }.path
        val directDownloads = runBlocking { appPaths.resolve(KWebAppPathKind.DOWNLOADS) }.path
        val page = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(
                    window,
                    server.origin,
                    KWebPageDispatcherFactory { pageId ->
                        val owner = JvmKWebFilesPageOwner(
                                engineId = "migration-fixture",
                                profileId = "electron-migration-fixture",
                                pageId = pageId,
                                expectedOrigin = server.origin,
                                configuration = JvmKWebFilesConfiguration(
                                mapOf(
                                    "documents" to JvmKWebWorkspace(
                                        workspaceRoot,
                                        KWebFileGrant.entries.toSet(),
                                    ),
                                ),
                            ),
                            policyEngine = filesPolicyEngine,
                        )
                        val shell = JvmKWebShell.open(
                            requiredPath(SHELL_LIBRARY_PROPERTY),
                            KWebShellConfiguration(),
                            owner.shellResourceResolver(),
                        )
                        try {
                            require(filesOwner.compareAndSet(null, owner)) {
                                "The migration fixture created more than one PAGE-scoped files owner."
                            }
                            require(shellService.compareAndSet(null, shell)) {
                                "The migration fixture created more than one PAGE-scoped shell service."
                            }
                        } catch (error: Throwable) {
                            shell.close()
                            owner.close()
                            throw error
                        }
                        KWebBridgeDispatchers.exact(
                            KWebBridgeRoute(
                                methods = setOf("resolve"),
                                dispatcher = slowDispatcher,
                            ),
                            KWebBridgeRoute(
                                methods = setOf(
                                    "openWorkspace",
                                    "openFile",
                                    "writeFile",
                                    "readFile",
                                    "listDirectory",
                                    "closeHandle",
                                ),
                                dispatcher = owner.bridgeDispatcher(),
                            ),
                            KWebBridgeRoute(
                                methods = setOf("openExternal", "openResource", "revealResource", "trashResource"),
                                dispatcher = shell.shellBridgeDispatcher(
                                    shellPolicyEngine,
                                    KWebPolicySubject(
                                        engineId = "migration-fixture",
                                        profileId = "electron-migration-fixture",
                                        pageId = pageId,
                                        origin = server.origin,
                                        scope = KWebServiceScope.PAGE,
                                    ),
                                ),
                            ),
                            KWebBridgeRoute(
                                methods = setOf("read", "readPayload", "write", "clear", "closePayload"),
                                dispatcher = clipboard.clipboardBridgeDispatcher(
                                    clipboardPolicyEngine,
                                    KWebPolicySubject(
                                        engineId = "migration-fixture",
                                        profileId = "electron-migration-fixture",
                                        pageId = pageId,
                                        origin = server.origin,
                                        scope = KWebServiceScope.APPLICATION,
                                    ),
                                ),
                            ),
                            KWebBridgeRoute(
                                methods = setOf("permission", "requestPermission", "capabilities", "show", "close"),
                                dispatcher = notifications.notificationsBridgeDispatcher(
                                    notificationPolicyEngine,
                                    KWebPolicySubject(
                                        engineId = "migration-fixture",
                                        profileId = "electron-migration-fixture",
                                        pageId = pageId,
                                        origin = server.origin,
                                        scope = KWebServiceScope.APPLICATION,
                                    ),
                                ),
                            ),
                        )
                    },
                    KWebPageStreamDispatcherFactory { _ ->
                        filesOwner.get()?.bridgeStreamDispatcher()
                            ?: error("The files PAGE owner was requested before it existed.")
                    },
                ),
                server.indexUrl,
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = page
        ownerScope.launch {
            page.events.collect { event ->
                when (event.type) {
                    KWebPageEventType.NAVIGATION_STARTED -> {
                        shellService.get()?.onNavigationStarted()
                        filesOwner.get()?.onNavigationStarted()
                    }
                    KWebPageEventType.NAVIGATION_COMMITTED -> {
                        shellService.get()?.onNavigationCommitted()
                        filesOwner.get()?.onNavigationCommitted(event.origin.orEmpty())
                    }
                    KWebPageEventType.CLOSED -> {
                        shellService.get()?.close()
                        filesOwner.get()?.close()
                    }
                    else -> Unit
                }
            }
        }
        cdp.awaitPage(server.indexUrl)
        cdp.openPageSession(server.indexUrl).use { session ->
            session.awaitExpression("typeof globalThis.desktop === 'object'")
            val paths = session.evaluateString(
                "(async()=>JSON.stringify(await Promise.all([window.desktop.getPath('home'), window.desktop.getPath('downloads')])))()",
            )
            val pathValues = Json.parseToJsonElement(paths).jsonArray.map { it.jsonPrimitive.content }
            require(pathValues == listOf(directHome, directDownloads)) {
                "Concurrent preload calls crossed responses: $pathValues"
            }
            val gestureDenied = session.evaluateString(
                "(async()=>{try{await window.desktop.openWorkspace({workspaceId:'documents',grants:['read']});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(gestureDenied == "service.user-gesture-required") {
                "The migration files adapter did not enforce its native gesture policy: $gestureDenied"
            }
            val clipboardGestureDenied = session.evaluateString(
                "(async()=>{try{await window.desktop.writeClipboard({selection:'system',items:[{format:'text/plain',encoding:'utf8',bytes:[103,101,115,116,117,114,101]}]});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(clipboardGestureDenied == "service.user-gesture-required") {
                "The migration clipboard adapter did not enforce its native gesture policy: $clipboardGestureDenied"
            }
            onAwtThread {
                window.toFront()
                window.requestFocus()
                window.requestFocusInWindow()
            }
            session.command("Page.bringToFront")
            session.command(
                "Input.dispatchKeyEvent",
                buildJsonObject {
                    put("type", "rawKeyDown")
                    put("windowsVirtualKeyCode", 65)
                    put("nativeVirtualKeyCode", 65)
                    put("key", "a")
                    put("code", "KeyA")
                },
            )
            awaitGestureMint(
                gestures,
                KWebGestureBinding(
                    engineId = "migration-fixture",
                    profileId = "electron-migration-fixture",
                    pageId = page.id,
                    origin = server.origin,
                ),
            )
            fun mintClipboardGesture() {
                session.command("Page.bringToFront")
                session.command(
                    "Input.dispatchKeyEvent",
                    buildJsonObject {
                        put("type", "rawKeyDown")
                        put("windowsVirtualKeyCode", 66)
                        put("nativeVirtualKeyCode", 66)
                        put("key", "b")
                        put("code", "KeyB")
                    },
                )
                awaitGestureMint(
                    gestures,
                    KWebGestureBinding(
                        engineId = "migration-fixture",
                        profileId = "electron-migration-fixture",
                        pageId = page.id,
                        origin = server.origin,
                    ),
                )
            }
            mintClipboardGesture()
            val clipboard = session.evaluateString(
                """
                (async()=>{
                  const written=await window.desktop.writeClipboard({selection:"system",items:[{format:"text/plain",encoding:"utf8",bytes:[109,105,103,114,97,116,105,111,110,45,99,108,105,112,98,111,97,114,100]}]});
                  return JSON.stringify({written});
                })()
                """.trimIndent(),
            )
            val clipboardJson = Json.parseToJsonElement(clipboard).jsonObject
            require(clipboardJson["written"]?.jsonObject?.get("sequence")?.jsonPrimitive?.content?.toLongOrNull() != null)
            mintClipboardGesture()
            val clipboardRead = session.evaluateString(
                """
                (async()=>{
                  const read=await window.desktop.readClipboard({selection:"system",formats:["text/plain"]});
                  const payload=read.available.find(item=>item.format==="text/plain");
                  if(!payload) return JSON.stringify({read});
                  try {
                    const chunk=await window.desktop.readClipboardPayload({handle:payload.handle,offset:"0",length:Number(payload.sizeBytes)});
                    return JSON.stringify({read,chunk,text:new TextDecoder().decode(new Uint8Array(chunk.bytes))});
                  } finally {
                    await window.desktop.closeClipboardPayload({handle:payload.handle});
                  }
                })()
                """.trimIndent(),
            )
            val clipboardReadJson = Json.parseToJsonElement(clipboardRead).jsonObject
            require(clipboardReadJson["read"]?.jsonObject?.get("available")?.jsonArray?.any {
                it.jsonObject["format"]?.jsonPrimitive?.content == "text/plain"
            } == true) {
                "The real CEF clipboard bridge did not expose the written text/plain payload: $clipboardRead"
            }
            require(clipboardReadJson["text"]?.jsonPrimitive?.content == "migration-clipboard") {
                "The real CEF clipboard bridge did not round-trip text/plain bytes: $clipboardRead"
            }
            mintClipboardGesture()
            val clipboardClear = session.evaluateString(
                "(async()=>JSON.stringify({cleared:await window.desktop.clearClipboard({selection:'system'})}))()",
            )
            val clipboardClearJson = Json.parseToJsonElement(clipboardClear).jsonObject
            require(clipboardClearJson["cleared"]?.jsonObject?.get("sequence")?.jsonPrimitive?.content?.toLongOrNull() != null)
            val notificationCapabilities = Json.parseToJsonElement(
                session.evaluateString(
                    "(async()=>JSON.stringify(await window.desktop.getNotificationCapabilities({scope:'application'})))()",
                ),
            ).jsonObject
            require(notificationCapabilities["actions"]?.jsonPrimitive?.content == "true") {
                "The hosted notification provider did not advertise actions: $notificationCapabilities"
            }
            val notificationShown = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.showNotification({
                      id:"migration-notification-1",tag:"migration-fixture",title:"KWebShell notification fixture",
                      body:"notification fixture body",icon:"APPLICATION",urgency:"NORMAL",timeout:"SYSTEM",
                      actions:[{id:"open",title:"Open fixture",kind:"BUTTON",replyPlaceholder:null}]
                    })))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(notificationShown["id"]?.jsonPrimitive?.content == "migration-notification-1")
            if ((System.getProperty("kweb.migration.target") ?: "").startsWith("linux-")) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (notificationActivations.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
                require(notificationActivations.singleOrNull()?.actionId == "open") {
                    "The Linux notification fixture did not route its ActionInvoked signal exactly once: $notificationActivations"
                }
                activatedNotificationActions += checkNotNull(notificationActivations.single().actionId)
            }
            if ((System.getProperty("kweb.migration.target") ?: "").startsWith("macos-")) {
                val actionHelper = requiredPath(NOTIFICATION_ACTION_HELPER_PROPERTY).toAbsolutePath().normalize()
                val actionProcess = ProcessBuilder(actionHelper.toString()).inheritIO().start()
                require(actionProcess.waitFor(30, TimeUnit.SECONDS)) {
                    actionProcess.destroyForcibly()
                    "The macOS notification Accessibility action fixture exceeded its deadline."
                }
                require(actionProcess.exitValue() == 0) {
                    "The macOS notification Accessibility action fixture failed with exit ${actionProcess.exitValue()}."
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (notificationActivations.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
                require(notificationActivations.singleOrNull()?.actionId == "open") {
                    "The macOS notification fixture did not route its OS action exactly once: $notificationActivations"
                }
                activatedNotificationActions += checkNotNull(notificationActivations.single().actionId)
            }
            if ((System.getProperty("kweb.migration.target") ?: "").startsWith("windows-")) {
                val actionHelper = requiredPath(WINDOWS_NOTIFICATION_ACTION_HELPER_PROPERTY).toAbsolutePath().normalize()
                val actionProcess = ProcessBuilder(
                    "powershell.exe",
                    "-NoLogo",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-File",
                    actionHelper.toString(),
                ).inheritIO().start()
                require(actionProcess.waitFor(40, TimeUnit.SECONDS)) {
                    actionProcess.destroyForcibly()
                    "The Windows notification UI action fixture exceeded its deadline."
                }
                require(actionProcess.exitValue() == 0) {
                    "The Windows notification UI action fixture failed with exit ${actionProcess.exitValue()}."
                }
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (notificationActivations.isEmpty() && System.nanoTime() < deadline) Thread.sleep(50)
                require(notificationActivations.singleOrNull()?.actionId == "open") {
                    "The Windows notification fixture did not route its OS action exactly once: $notificationActivations"
                }
                activatedNotificationActions += checkNotNull(notificationActivations.single().actionId)
            }
            if (activatedNotificationActions.isNotEmpty()) {
                val activationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (
                    applicationActivations.none { it.batch.source == KWebActivationSource.PROTOCOL } &&
                    System.nanoTime() < activationDeadline
                ) Thread.sleep(50)
                val protocolActivations = applicationActivations.filter {
                    it.batch.source == KWebActivationSource.PROTOCOL
                }
                require(protocolActivations.size == 1) {
                    "The RFC 0006 application owner did not receive exactly one notification activation: $protocolActivations"
                }
                val routed = protocolActivations.single().batch.uris.singleOrNull().orEmpty()
                require(
                    routed.startsWith(
                        "kweb://notification?app=io.github.kwebshell.migration.fixture&" +
                            "id=migration-notification-1&tag=migration-fixture&action=open",
                    ),
                ) {
                    "The RFC 0006 owner received a mismatched notification activation envelope."
                }
            }
            val notificationReplaced = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.showNotification({
                      id:"migration-notification-2",tag:"migration-fixture",title:"KWebShell notification fixture",
                      body:"notification fixture replacement",icon:"APPLICATION",urgency:"NORMAL",timeout:"SYSTEM",
                      actions:[]
                    })))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(notificationReplaced["outcome"]?.jsonPrimitive?.content == "REPLACED")
            val notificationClosed = Json.parseToJsonElement(
                session.evaluateString(
                    "(async()=>JSON.stringify(await window.desktop.closeNotification({id:'migration-notification-2'})))()",
                ),
            ).jsonObject
            require(notificationClosed["id"]?.jsonPrimitive?.content == "migration-notification-2")
            mintClipboardGesture()
            val files = session.evaluateString(
                """
                (async()=>{
                  const grants=["read","write","create","enumerate","watch","metadata","copy","move"];
                  const workspace=await window.desktop.openWorkspace({workspaceId:"documents",grants});
                  const file=await window.desktop.openFile({parent:workspace.handle,name:"fixture.txt",mode:"read-write",createIfMissing:true});
                  await window.desktop.writeFile({handle:file.handle,offset:"0",bytes:[109,105,103,114,97,116,105,111,110]});
                  const read=await window.desktop.readFile({handle:file.handle,offset:"0",length:64});
                  const listing=await window.desktop.listDirectory({handle:workspace.handle,limit:16});
                  await window.desktop.closeHandle({handle:file.handle});
                  globalThis.__migrationWorkspace=workspace;
                  return JSON.stringify({bytes:read.bytes,eof:read.eof,entries:listing.entries.map(entry=>entry.name),descriptor:workspace});
                })()
                """.trimIndent(),
            )
            val filesJson = Json.parseToJsonElement(files).jsonObject
            require(filesJson["eof"]?.jsonPrimitive?.content == "true")
            require(filesJson["entries"]?.jsonArray?.any { it.jsonPrimitive.content == "fixture.txt" } == true)
            require(!files.contains(workspaceRoot.toString())) { "The migration files descriptor exposed an absolute path." }
            require(!files.contains("\"path\"")) { "The migration files descriptor exposed a path field." }
            val shellResource = session.evaluateString(
                """
                (async()=>{
                  const file=await window.desktop.openFile({parent:globalThis.__migrationWorkspace.handle,name:"shell.txt",mode:"read-write",createIfMissing:true});
                  await window.desktop.writeFile({handle:file.handle,offset:"0",bytes:[115,104,101,108,108]});
                  globalThis.__migrationShellFile=file;
                  return JSON.stringify({kind:file.kind,grants:file.grants});
                })()
                """.trimIndent(),
            )
            val shellResourceJson = Json.parseToJsonElement(shellResource).jsonObject
            require(shellResourceJson["kind"]?.jsonPrimitive?.content == "file")
            val shellGestureDenied = session.evaluateString(
                """
                (async()=>{try{await window.desktop.openExternal({uri:"https://example.invalid"});return "unexpected"}catch(e){return e.code}})()
                """.trimIndent(),
            )
            require(shellGestureDenied == "service.user-gesture-required") {
                "The migration shell adapter accepted an external URI without a native gesture: $shellGestureDenied"
            }
            fun mintShellGesture() = mintClipboardGesture()
            mintShellGesture()
            val external = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.openExternal({uri:"https://example.invalid"})))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(external["action"]?.jsonPrimitive?.content == "open-external")
            require(external["outcome"]?.jsonPrimitive?.content == "handler-accepted")
            mintShellGesture()
            val opened = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.openResource({handle:globalThis.__migrationShellFile.handle})))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(opened["action"]?.jsonPrimitive?.content == "open-resource")
            require(opened["resourceKind"]?.jsonPrimitive?.content == "file")
            mintShellGesture()
            val revealed = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.revealResource({handle:globalThis.__migrationShellFile.handle})))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(revealed["action"]?.jsonPrimitive?.content == "reveal-resource")
            mintShellGesture()
            val directoryTrashCode = session.evaluateString(
                """
                (async()=>{try{await window.desktop.trashResource({handle:globalThis.__migrationWorkspace.handle});return "unexpected"}catch(e){return e.code}})()
                """.trimIndent(),
            )
            require(directoryTrashCode == "shell.directory-not-allowed") {
                "The migration shell adapter accepted directory trash without host policy: $directoryTrashCode"
            }
            mintShellGesture()
            val trashed = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.trashResource({handle:globalThis.__migrationShellFile.handle})))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(trashed["action"]?.jsonPrimitive?.content == "trash-resource")
            require(trashed["outcome"]?.jsonPrimitive?.content == "moved-to-trash")
            val afterTrash = Json.parseToJsonElement(
                session.evaluateString(
                    """
                    (async()=>JSON.stringify(await window.desktop.listDirectory({handle:globalThis.__migrationWorkspace.handle,limit:32})))()
                    """.trimIndent(),
                ),
            ).jsonObject
            require(afterTrash["entries"]?.jsonArray?.none { it.jsonObject["name"]?.jsonPrimitive?.content == "shell.txt" } == true) {
                "The migration shell adapter did not remove the trashed resource from its source directory."
            }
            session.evaluateString(
                """
                (async()=>{await window.desktop.closeHandle({handle:globalThis.__migrationShellFile.handle});delete globalThis.__migrationShellFile;return "closed"})()
                """.trimIndent(),
            )
            val watchStart = session.evaluateString(
                """
                (async()=>{
                  globalThis.__migrationWatch=(async()=>{
                    const stream=window.desktop.watchDirectory({handle:globalThis.__migrationWorkspace.handle});
                    try { for await(const event of stream){ stream.close(); return JSON.stringify(event); } }
                    catch(e){ return JSON.stringify({code:e.code}); }
                    return "closed";
                  })();
                  return "watching";
                })()
                """.trimIndent(),
            )
            require(watchStart == "watching")
            Thread.sleep(2_000)
            Files.writeString(workspaceRoot.resolve("watch.txt"), "watch", StandardCharsets.UTF_8)
            val watchResult = session.evaluateString("globalThis.__migrationWatch")
            val watchJson = Json.parseToJsonElement(watchResult).jsonObject
            require(watchJson["kind"]?.jsonPrimitive?.content == "created")
            require(watchJson["name"]?.jsonPrimitive?.content == "watch.txt")
            val progress = session.evaluateString(
                "(async()=>{const values=[];for await(const chunk of window.desktop.progress({downloadId:'fixture'})) values.push(chunk);return JSON.stringify(values)})()",
            )
            val progressValues = Json.parseToJsonElement(progress).jsonArray
            require(progressValues.size == 2 && progressValues[0].jsonObject["downloadId"]?.jsonPrimitive?.content == "fixture") {
                "The named application stream adapter did not deliver its declared AsyncIterable values: $progress"
            }
            require(session.evaluateString("typeof document.getElementById('fixture-frame').contentWindow.desktop") == "undefined")
            require(session.evaluateString("typeof document.getElementById('fixture-frame').contentWindow.__kwebBridgeQuery") == "undefined")
            require(
                session.evaluateString(
                    "(async()=>{try{await window.desktop.getPath('logs');return 'unexpected'}catch(e){return e.code}})()",
                ) == "migration.path-name.unsupported",
            )

            require(
                session.evaluateString(
                    """
                    (()=>{globalThis.__musicAbortController=new AbortController();globalThis.__musicCall=window.desktop.getPath('music',{signal:globalThis.__musicAbortController.signal});return 'started'})()
                    """.trimIndent(),
                ) == "started",
            )
            slowDispatcher.awaitStarted("music")
            val abortCode = session.evaluateString(
                """
                (async()=>{globalThis.__musicAbortController.abort();try{await globalThis.__musicCall;return 'unexpected'}catch(e){return e.code}finally{delete globalThis.__musicAbortController;delete globalThis.__musicCall}})()
                """.trimIndent(),
            )
            require(abortCode == "bridge.call.cancelled") { "AbortSignal returned '$abortCode'." }
            slowDispatcher.awaitCancelled("music")

            val timeoutCode = session.evaluateString(
                "(async()=>{try{await window.desktop.getPath('pictures',{timeoutMs:25});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(timeoutCode == "bridge.call.timeout") { "Timeout returned '$timeoutCode'." }
            slowDispatcher.awaitCancelled("pictures")

            require(session.evaluateString("void (globalThis.__navigationCall=window.desktop.getPath('videos'));'started'") == "started")
            slowDispatcher.awaitStarted("videos")
        }
        runBlocking { page.navigate(server.crossOriginUrl) }
        slowDispatcher.awaitCancelled("videos")
        cdp.awaitPage(server.crossOriginUrl)
        cdp.openPageSession(server.crossOriginUrl).use { session ->
            require(session.evaluateString("typeof globalThis.desktop") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }

        runBlocking { page.navigate(server.indexUrl) }
        cdp.awaitPage(server.indexUrl)
        val ownerSession = cdp.openPageSession(server.indexUrl)
        ownerSession.awaitExpression("typeof globalThis.desktop === 'object'")
        require(ownerSession.evaluateString("void (globalThis.__ownerCall=window.desktop.getPath('documents'));'started'") == "started")
        slowDispatcher.awaitStarted("documents")
        ownerSession.close()
        page.close()
        activePage = null
        slowDispatcher.awaitCancelled("documents")
        val filesPageOwner = requireNotNull(filesOwner.get())
        val filesCloseDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (filesPageOwner.isClosed().not() && System.nanoTime() < filesCloseDeadline) {
            Thread.sleep(25)
        }
        require(filesPageOwner.isClosed()) {
            "The PAGE-scoped files service did not close with the migration page owner."
        }

        deniedFiles = JvmKWebFiles.open(
            KWebFileOwnerScope(
                engineId = "migration-fixture",
                profileId = "electron-migration-fixture",
                pageId = "denied-page",
                origin = server.origin,
                navigationId = 1,
            ),
            JvmKWebFilesConfiguration(
                mapOf(
                    "documents" to JvmKWebWorkspace(
                        workspaceRoot,
                        KWebFileGrant.entries.toSet(),
                    ),
                ),
            ),
        )
        deniedShell = JvmKWebShell.open(
            requiredPath(SHELL_LIBRARY_PROPERTY),
            KWebShellConfiguration(),
            deniedFiles as JvmKWebFilesShellResolver,
        )
        val deniedShellService = requireNotNull(deniedShell)
        val deniedFilesService = requireNotNull(deniedFiles)
        val deniedPage = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(
                    window,
                    server.origin,
                    KWebBridgeDispatchers.exact(
                        KWebBridgeRoute(
                            methods = setOf("resolve"),
                            dispatcher = appPaths.bridgeDispatcher(
                                denyPolicyEngine,
                                KWebPolicySubject(
                                    engineId = "migration-fixture",
                                    profileId = "electron-migration-fixture",
                                    pageId = "denied-page",
                                    origin = server.origin,
                                    scope = KWebServiceScope.APPLICATION,
                                ),
                            ),
                        ),
                        KWebBridgeRoute(
                            methods = setOf("read", "readPayload", "write", "clear", "closePayload"),
                            dispatcher = clipboard.clipboardBridgeDispatcher(
                                deniedClipboardPolicyEngine,
                                KWebPolicySubject(
                                    engineId = "migration-fixture",
                                    profileId = "electron-migration-fixture",
                                    pageId = "denied-page",
                                    origin = server.origin,
                                    scope = KWebServiceScope.APPLICATION,
                                ),
                            ),
                        ),
                        KWebBridgeRoute(
                            methods = setOf("openExternal", "openResource", "revealResource", "trashResource"),
                            dispatcher = deniedShellService.shellBridgeDispatcher(
                                deniedShellPolicyEngine,
                                KWebPolicySubject(
                                    engineId = "migration-fixture",
                                    profileId = "electron-migration-fixture",
                                    pageId = "denied-page",
                                    origin = server.origin,
                                    scope = KWebServiceScope.PAGE,
                                ),
                            ),
                        ),
                        KWebBridgeRoute(
                            methods = setOf("permission", "requestPermission", "capabilities", "show", "close"),
                            dispatcher = notifications.notificationsBridgeDispatcher(
                                deniedNotificationPolicyEngine,
                                KWebPolicySubject(
                                    engineId = "migration-fixture",
                                    profileId = "electron-migration-fixture",
                                    pageId = "denied-page",
                                    origin = server.origin,
                                    scope = KWebServiceScope.APPLICATION,
                                ),
                            ),
                        ),
                    ),
                ),
                "${server.indexUrl}?denied",
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = deniedPage
        cdp.awaitPage("${server.indexUrl}?denied")
        cdp.openPageSession("${server.indexUrl}?denied").use { session ->
            session.awaitExpression("typeof globalThis.desktop === 'object'")
            val code = session.evaluateString(
                "(async()=>{try{await window.desktop.getPath('home');return 'unexpected'}catch(e){return e.code}})()",
            )
            require(code == "service.permission-denied") { "Denied page returned '$code'." }
            val clipboardCode = session.evaluateString(
                "(async()=>{try{await window.desktop.readClipboard({selection:'system',formats:['text/plain']});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(clipboardCode == "service.permission-denied") { "Denied clipboard page returned '$clipboardCode'." }
            val shellCode = session.evaluateString(
                "(async()=>{try{await window.desktop.openExternal({uri:'https://example.invalid'});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(shellCode == "service.permission-denied") { "Denied shell page returned '$shellCode'." }
            val notificationCode = session.evaluateString(
                "(async()=>{try{await window.desktop.getNotificationCapabilities({scope:'application'});return 'unexpected'}catch(e){return e.code}})()",
            )
            require(notificationCode == "service.permission-denied") {
                "Denied notification page returned '$notificationCode'."
            }
        }
        deniedPage.close()
        activePage = null
        deniedShellService.close()
        deniedShell = null
        deniedFilesService.close()
        deniedFiles = null

        val unconfiguredPage = runBlocking {
            profile.openPage(
                KWebDesktop.composeWindowHost(window),
                "${server.indexUrl}?unconfigured",
                KWebRect(0, 0, 960, 700),
            )
        }
        activePage = unconfiguredPage
        cdp.awaitPage("${server.indexUrl}?unconfigured")
        cdp.openPageSession("${server.indexUrl}?unconfigured").use { session ->
            require(session.evaluateString("typeof globalThis.desktop") == "undefined")
            require(session.evaluateString("typeof globalThis.__kwebBridgeQuery") == "undefined")
        }
        unconfiguredPage.close()
        activePage = null
        profile.close()
        if (notifications.lifecycle.value != KWebLifecycleState.CLOSED) notifications.close()
        engine.close()
        applicationActivationCollector.cancel()
        applicationLifecycle.close()
        require(appPaths.lifecycle.value == KWebLifecycleState.CLOSED)
        require(engine.nativeServices.lifecycle.value == KWebLifecycleState.CLOSED)
    } catch (error: Throwable) {
        failure = error
    } finally {
        try {
            activePage?.let { page ->
                if (page.lifecycle.value != KWebLifecycleState.CLOSED) page.close()
            }
            activePage = null
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (profile.lifecycle.value != KWebLifecycleState.CLOSED) profile.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (engine.lifecycle.value != KWebLifecycleState.CLOSED) engine.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (appPaths.lifecycle.value != KWebLifecycleState.CLOSED) appPaths.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (clipboard.lifecycle.value != KWebLifecycleState.CLOSED) clipboard.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            if (notifications.lifecycle.value != KWebLifecycleState.CLOSED) notifications.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
        try {
            applicationActivationCollector.cancel()
            if (applicationLifecycle.lifecycle.value != KWebLifecycleState.CLOSED) applicationLifecycle.close()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
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
        try {
            shellService.getAndSet(null)?.close()
            filesOwner.getAndSet(null)?.close()
            deniedShell?.close()
            deniedFiles?.close()
            ownerScope.cancel()
        } catch (error: Throwable) {
            failure = failure.append(error)
        }
    }
    failure?.let { throw it }
    Files.writeString(
        root.resolve("migration-clipboard-evidence.json"),
        """
        {
          "schemaVersion": 1,
          "target": "${System.getProperty("kweb.migration.target") ?: "unknown"}",
          "cefRuntime": "stock-cef-151",
          "contractRevision": "2026-09-30.3",
          "exactOriginMainFrame": true,
          "childFrameTransportAbsent": true,
          "crossOriginBridgeAbsent": true,
          "gestureDeniedWithoutNativeInput": true,
          "textPlainRoundTrip": true,
          "lazyPayloadReadAndClose": true,
          "clearCompleted": true,
          "permissionDenied": true,
          "unconfiguredBridgeAbsent": true,
          "ownerClosed": true
        }
        """.trimIndent() + "\n",
    )
    Files.writeString(
        root.resolve("migration-shell-evidence.json"),
        """
        {
          "schemaVersion": 1,
          "target": "${System.getProperty("kweb.migration.target") ?: "unknown"}",
          "cefRuntime": "stock-cef-151",
          "contractRevision": "2026-10-01.1",
          "exactOriginMainFrame": true,
          "childFrameTransportAbsent": true,
          "crossOriginBridgeAbsent": true,
          "gestureDeniedWithoutNativeInput": true,
          "externalUriAccepted": true,
          "resourceOpenAccepted": true,
          "resourceRevealAccepted": true,
          "directoryTrashDeniedByPolicy": true,
          "resourceTrashMovedToOsTrash": true,
          "sourceResourceAbsentAfterTrash": true,
          "permissionDenied": true,
          "ownerClosed": true
        }
        """.trimIndent() + "\n",
    )
    Files.writeString(
        root.resolve("migration-notifications-evidence.json"),
        """
        {
          "schemaVersion": 1,
          "target": "${System.getProperty("kweb.migration.target") ?: "unknown"}",
          "cefRuntime": "stock-cef-151",
          "contractRevision": "2026-10-02.1",
          "exactOriginMainFrame": true,
          "childFrameTransportAbsent": true,
          "crossOriginBridgeAbsent": true,
          "namedOperationsOnly": true,
          "capabilitiesObserved": true,
          "showObserved": true,
          "replacementObserved": true,
          "closeObserved": true,
          "actionActivationObserved": ${activatedNotificationActions.isNotEmpty()},
          "actionActivationCount": ${activatedNotificationActions.size},
          "activatedActionIds": [${activatedNotificationActions.joinToString(",") { "\"$it\"" }}],
          "applicationOwnerActivationObserved": ${applicationActivations.any { it.batch.source == KWebActivationSource.PROTOCOL }},
          "applicationOwnerActivationCount": ${applicationActivations.count { it.batch.source == KWebActivationSource.PROTOCOL }},
          "permissionDenied": true,
          "customImageInputAbsent": true,
          "ownerClosed": true
        }
        """.trimIndent() + "\n",
    )
    println("KWebShell typed Electron migration fixture passed against real CEF.")
}

private class SlowAppPathsDispatcher(
    private val delegate: KWebBridgeDispatcher,
) : KWebBridgeDispatcher {
    private val started = ConcurrentHashMap<String, CountDownLatch>()
    private val cancelled = ConcurrentHashMap<String, CountDownLatch>()

    override suspend fun dispatch(requestJson: String): String {
        val request = KWebBridgeProtocol.decodeRequest(requestJson)
        val kind = request.payload.jsonObject["kind"]?.jsonPrimitive?.content.orEmpty()
        if (kind in SLOW_KINDS) {
            started.computeIfAbsent(kind) { CountDownLatch(1) }.countDown()
            try {
                awaitCancellation()
            } finally {
                cancelled.computeIfAbsent(kind) { CountDownLatch(1) }.countDown()
            }
        }
        return delegate.dispatch(requestJson)
    }

    fun awaitStarted(kind: String) {
        require(started.computeIfAbsent(kind) { CountDownLatch(1) }.await(30, TimeUnit.SECONDS)) {
            "The '$kind' bridge request did not start."
        }
    }

    fun awaitCancelled(kind: String) {
        require(cancelled.computeIfAbsent(kind) { CountDownLatch(1) }.await(30, TimeUnit.SECONDS)) {
            "The '$kind' bridge request was not cancelled."
        }
    }

    private companion object {
        val SLOW_KINDS = setOf("music", "pictures", "videos", "documents")
    }
}

private class MigrationFixtureServer(
    appPathsBridge: String,
    filesBridge: String,
    shellBridge: String,
    clipboardBridge: String,
    notificationsBridge: String,
    preload: String,
) : AutoCloseable {
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "KWebShell-migration-fixture-http").apply { isDaemon = true }
    }
    private val allowed = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val cross = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val index = (
        "<!doctype html><meta charset=\"utf-8\"><title>KWebShell Migration Fixture</title>" +
            "<iframe id=\"fixture-frame\" src=\"/frame\"></iframe>" +
            "<script>$appPathsBridge</script>" +
            "<script>$filesBridge</script>" +
            "<script>$shellBridge</script>" +
            "<script>$clipboardBridge</script>" +
            "<script>$notificationsBridge</script>" +
            "<script>globalThis.KWebApplicationStreamsBridge={createClient(){return {openDownloadProgress:async function* (request){yield {downloadId:request.downloadId,done:false};yield {downloadId:request.downloadId,done:true}}}}}</script>" +
            "<script>$preload</script>"
        ).toByteArray(StandardCharsets.UTF_8)

    init {
        allowed.executor = executor
        allowed.createContext("/") { exchange ->
            val body = if (exchange.requestURI.path == "/frame") {
                "<!doctype html><meta charset=\"utf-8\"><title>frame</title>".toByteArray(StandardCharsets.UTF_8)
            } else if (exchange.requestURI.query == "unconfigured") {
                "<!doctype html><meta charset=\"utf-8\"><title>unconfigured</title>".toByteArray(StandardCharsets.UTF_8)
            } else {
                index
            }
            send(exchange, body)
        }
        cross.executor = executor
        cross.createContext("/") { exchange ->
            send(exchange, "<!doctype html><meta charset=\"utf-8\"><title>cross</title>".toByteArray(StandardCharsets.UTF_8))
        }
        allowed.start()
        cross.start()
    }

    val origin: String
        get() = "http://127.0.0.1:${allowed.address.port}"
    val indexUrl: String
        get() = "$origin/index.html"
    val crossOriginUrl: String
        get() = "http://127.0.0.1:${cross.address.port}/cross"

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

private fun KWebExampleCdpSession.awaitExpression(expression: String) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (System.nanoTime() < deadline) {
        val value = runCatching { evaluateString(expression) }.getOrNull()
        if (value == "true") return
        Thread.sleep(50)
    }
    error("CDP expression did not become true: $expression")
}

private fun KWebExampleCdpSession.evaluateString(expression: String): String =
    evaluate(expression).value?.jsonPrimitive?.content
        ?: error("CDP expression returned no primitive value: $expression")

private fun requiredPath(name: String): Path =
    Path.of(System.getProperty(name) ?: error("Missing system property '$name'."))

private fun findFreePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { socket ->
    socket.localPort.takeIf { it in 1024..65535 } ?: error("The OS allocated an invalid CDP port.")
}

private fun awaitGestureMint(
    gestures: KWebUserGestureRegistry,
    binding: KWebGestureBinding,
) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (System.nanoTime() < deadline) {
        if (gestures.current(binding) != null) return
        Thread.sleep(25)
    }
    error("The migration fixture did not receive a native user gesture.")
}

private fun <T> onAwtThread(operation: () -> T): T {
    if (EventQueue.isDispatchThread()) return operation()
    val result = AtomicReference<Result<T>>()
    SwingUtilities.invokeAndWait { result.set(runCatching(operation)) }
    return result.get().getOrThrow()
}

private fun Throwable?.append(error: Throwable): Throwable = this?.also { current ->
    if (current !== error) current.addSuppressed(error)
} ?: error
