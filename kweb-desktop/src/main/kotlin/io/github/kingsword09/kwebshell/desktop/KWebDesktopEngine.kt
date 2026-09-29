package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.services.policy.KWebGestureBinding
import io.github.kingsword09.kwebshell.services.policy.KWebUserGestureIssuer
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebNetworkPolicy
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestEvent
import io.github.kingsword09.kwebshell.core.KWebDownload
import io.github.kingsword09.kwebshell.core.KWebDownloadFile
import io.github.kingsword09.kwebshell.core.KWebDownloadCollisionPolicy
import io.github.kingsword09.kwebshell.core.KWebDownloadInterruptReason
import io.github.kingsword09.kwebshell.core.KWebDownloadState
import io.github.kingsword09.kwebshell.core.KWebDownloadStatus
import io.github.kingsword09.kwebshell.core.isTerminal
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventFlag
import io.github.kingsword09.kwebshell.core.KWebPageEventReason
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebPageFrameScope
import io.github.kingsword09.kwebshell.core.KWebPageHost
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadDecision
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadRequest
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadResult
import io.github.kingsword09.kwebshell.core.KWebPopupDecision
import io.github.kingsword09.kwebshell.core.KWebPopupFeatures
import io.github.kingsword09.kwebshell.core.KWebPopupOutcome
import io.github.kingsword09.kwebshell.core.KWebPopupRequest
import io.github.kingsword09.kwebshell.core.KWebPopupResult
import io.github.kingsword09.kwebshell.core.KWebReloadMode
import io.github.kingsword09.kwebshell.core.KWebReloadOutcome
import io.github.kingsword09.kwebshell.core.KWebReloadResult
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebSecurityChallenge
import io.github.kingsword09.kwebshell.core.KWebSecurityChallengeOutcome
import io.github.kingsword09.kwebshell.core.KWebSecurityChallengeResult
import io.github.kingsword09.kwebshell.core.KWebSecurityDecision
import io.github.kingsword09.kwebshell.core.KWebCookie
import io.github.kingsword09.kwebshell.core.KWebCookieFilter
import io.github.kingsword09.kwebshell.core.KWebCookieMutationResult
import io.github.kingsword09.kwebshell.core.KWebCookieSpec
import io.github.kingsword09.kwebshell.core.KWebProfileDataClearResult
import io.github.kingsword09.kwebshell.core.KWebProfileDataFilter
import io.github.kingsword09.kwebshell.core.KWebProfileDataKind
import io.github.kingsword09.kwebshell.core.KWebProfileFlushResult
import io.github.kingsword09.kwebshell.core.KWebSpellcheckConfiguration
import io.github.kingsword09.kwebshell.core.KWebSpellcheckState
import io.github.kingsword09.kwebshell.core.KWebStorageUsage
import io.github.kingsword09.kwebshell.services.KWebNativeServiceRegistry
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebApplicationShutdownParticipant
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebQuitReason
import io.github.kingsword09.kwebshell.service.applicationlifecycle.KWebShutdownVote
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowser
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import io.github.kingsword09.kwebshell.desktop.internal.NativeBindings
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngine
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngineConfiguration
import io.github.kingsword09.kwebshell.desktop.internal.NativeStatus
import io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException
import io.github.kingsword09.kwebshell.desktop.internal.securityChallengeStatusException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import java.net.URI
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

public data class KWebDesktopShutdownStage(
    public val id: String,
    public val elapsedMillis: Long,
)

public data class KWebDesktopShutdownReport(
    public val outcome: String,
    public val stages: List<KWebDesktopShutdownStage>,
)

public class KWebDesktopEngine private constructor(
    private val native: NativeEngine,
    private val configuration: KWebDesktopEngineConfiguration,
) : KWebEngine {
    internal val engineId: String = configuration.engineId
    internal val downloadPolicy: KWebDesktopDownloadPolicy? = configuration.downloadPolicy

    internal val openingProfileCount: Int
        get() = synchronized(lock) { openingProfiles.size }

    internal val userGestureIssuer: KWebUserGestureIssuer? = configuration.userGestureIssuer
    private val lock = Any()
    private val profiles = linkedMapOf<Path, KWebDesktopProfile>()
    private val openingProfiles = linkedSetOf<Path>()
    private val closed = AtomicBoolean(false)
    private var closeFailure: Throwable? = null
    private var shutdownReport: KWebDesktopShutdownReport? = null

    public val nativeServices: KWebNativeServiceRegistry = KWebNativeServiceRegistry()

    public val lastApplicationShutdownReport: KWebDesktopShutdownReport?
        get() = synchronized(lock) { shutdownReport }

    /**
     * Registers the complete desktop owner as one application-lifecycle
     * shutdown participant. The lifecycle service calls this after all
     * application vetoes have been resolved and before releasing its lease.
     */
    public fun applicationShutdownParticipant(): KWebApplicationShutdownParticipant =
        object : KWebApplicationShutdownParticipant {
            override val id: String = "desktop-engine"
            override val order: Int = 100

            override suspend fun requestClose(reason: KWebQuitReason): KWebShutdownVote =
                KWebShutdownVote.ALLOW

            override suspend fun close(reason: KWebQuitReason) {
                this@KWebDesktopEngine.close()
            }
        }

    override val lifecycle: StateFlow<KWebLifecycleState> = native.lifecycle
    override val capabilities: Set<KWebCapability> = buildSet {
        add(KWebCapability.NATIVE_CHILD)
        add(KWebCapability.PERSISTENT_PROFILE)
        add(KWebCapability.NAVIGATION)
        add(KWebCapability.RESIZE)
        add(KWebCapability.DEVTOOLS)
        if (configuration.remoteDebuggingPort != 0) {
            add(KWebCapability.CDP)
        }
        if (configuration.downloadPolicy != null) {
            add(KWebCapability.DOWNLOADS)
        }
    }

    override suspend fun openProfile(name: String): KWebProfile = withContext(Dispatchers.IO) {
        val path = withEngineLock {
            requireEngineOpen("open-profile")
            val path = KWebProfilePathResolver.resolve(native.rootCachePath(), name)
            if (profiles.values.any { it.isSamePhysicalPath(path) } ||
                openingProfiles.any { it == path }
            ) {
                throw KWebConfigurationException(
                    code = "profile.duplicate-physical-identity",
                    details = mapOf("profile" to path.toString()),
                    message = "The requested Profile is already open through this engine.",
                )
            }
            openingProfiles.add(path)
            path
        }
        var contextOpened = false
        try {
            native.openProfileContext(path)
            contextOpened = true
            currentCoroutineContext().ensureActive()
            withEngineLock {
                requireEngineOpen("open-profile")
                KWebDesktopProfile(this@KWebDesktopEngine, path).also { profile ->
                    profiles[path] = profile
                }
            }
        } catch (error: Throwable) {
            if (contextOpened && native.lifecycle.value == KWebLifecycleState.OPEN) {
                try {
                    native.clearProfileNetworkPolicy(path)
                } catch (cleanupFailure: Throwable) {
                    error.addSuppressed(cleanupFailure)
                }
            }
            throw error
        } finally {
            withEngineLock { openingProfiles.remove(path) }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed.get()) {
                closeFailure?.let { throw it }
                if (nativeServices.lifecycle.value == KWebLifecycleState.FAILED) {
                    try {
                        nativeServices.close()
                    } catch (error: Throwable) {
                        closeFailure = error
                        throw error
                    }
                }
                return
            }
            if (openingProfiles.isNotEmpty()) {
                throw KWebNativeException(
                    code = "desktop.profile-opening",
                    details = mapOf("count" to openingProfiles.size.toString()),
                    message = "The engine cannot close while Profile contexts are initializing.",
                )
            }
            requireEngineOpen("close-engine")
            val startedAt = System.nanoTime()
            val stages = mutableListOf<KWebDesktopShutdownStage>()
            var nativeFailure: Throwable? = null
            try {
                native.close()
                stages += KWebDesktopShutdownStage("engine-native", elapsedMillis(startedAt))
            } catch (error: Throwable) {
                nativeFailure = error
                if (lifecycle.value == KWebLifecycleState.OPEN) {
                    shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                    throw error
                }
            }
            var serviceFailure: Throwable? = null
            try {
                nativeServices.close()
                stages += KWebDesktopShutdownStage("native-services", elapsedMillis(startedAt))
            } catch (error: Throwable) {
                serviceFailure = error
            } finally {
                closed.set(true)
                profiles.values.toList().forEach { it.markClosedByEngine() }
                profiles.clear()
                stages += KWebDesktopShutdownStage("profiles", elapsedMillis(startedAt))
            }
            if (nativeFailure != null) {
                serviceFailure?.let { nativeFailure.addSuppressed(it) }
                closeFailure = nativeFailure
                shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                throw nativeFailure
            }
            serviceFailure?.let {
                closeFailure = it
                shutdownReport = KWebDesktopShutdownReport("FAILED", stages.toList())
                throw it
            }
            shutdownReport = KWebDesktopShutdownReport("GRACEFUL", stages.toList())
        }
    }

    private fun elapsedMillis(startedAt: Long): Long =
        (System.nanoTime() - startedAt) / 1_000_000L

    internal fun <T> withEngineLock(block: () -> T): T = synchronized(lock) { block() }

    internal fun nativeEngine(): NativeEngine = native

    internal fun requireEngineOpen(operation: String) {
        if (closed.get() || lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.engine.closed",
                details = mapOf("operation" to operation),
                message = "The KWebShell engine is not open.",
            )
        }
    }

    internal fun removeProfile(profile: KWebDesktopProfile) {
        synchronized(lock) {
            profiles.remove(profile.path, profile)
        }
    }

    internal companion object {
        internal fun open(configuration: KWebDesktopEngineConfiguration): KWebDesktopEngine {
            val validatedConfiguration = configuration.copy(
                downloadPolicy = configuration.downloadPolicy?.validated(),
            )
            val nativeConfiguration = NativeEngineConfiguration(
                cefRuntime = validatedConfiguration.cefRuntime,
                browserSubprocess = validatedConfiguration.browserSubprocess,
                resources = validatedConfiguration.resources,
                locales = validatedConfiguration.locales,
                rootCache = validatedConfiguration.rootCache,
                log = validatedConfiguration.log,
                remoteDebuggingPort = validatedConfiguration.remoteDebuggingPort,
            )
            return KWebDesktopEngine(
                native = NativeEngine.open(nativeConfiguration),
                configuration = validatedConfiguration,
            )
        }
    }
}

private const val PROFILE_OPERATION_CLOSE_TIMEOUT_SECONDS = 30L

internal class KWebDesktopProfile(
    private val engine: KWebDesktopEngine,
    internal val path: Path,
) : KWebProfile {
    private val lock = Any()
    private val dataOperationMutex = Mutex()
    private val networkOperationMutex = Mutex()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val networkEventStream = KWebDesktopNetworkEventStream()
    private val securityChallengeStream = KWebDesktopSecurityChallengeStream()
    private val downloadStream = KWebDesktopDownloadStream()
    private val pages = linkedSetOf<KWebDesktopPage>()
    private val downloadsById = linkedMapOf<Long, KWebDesktopDownload>()
    private data class SecurityChallengeOwner(
        val browserHandle: Long,
        val challenge: KWebSecurityChallenge,
    )
    private val pendingSecurityChallenges = linkedMapOf<Long, SecurityChallengeOwner>()
    private val resolvedSecurityChallenges = linkedSetOf<Long>()
    private var closedByEngine = false
    private var activeNetworkOperation: CountDownLatch? = null

    override val name: String = path.fileName.toString()
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()
    override val networkEvents: Flow<KWebNetworkRequestEvent> = networkEventStream.events
    override val securityChallenges: Flow<KWebSecurityChallenge> = securityChallengeStream.events
    override val downloads: Flow<KWebDownload> = downloadStream.flow

    internal suspend fun awaitDownloadSubscriber() {
        downloadStream.awaitSubscriber()
    }

    override suspend fun openPage(
        host: KWebPageHost,
        initialUrl: String,
        bounds: KWebRect,
    ): KWebPage = withContext(Dispatchers.IO) {
        engine.withEngineLock {
            synchronized(lock) {
                requireOpen("open-page")
                val composeHost = host as? KWebComposeWindowHost
                    ?: throw KWebConfigurationException(
                        code = "desktop.page.host-unsupported",
                        details = mapOf("host" to host::class.qualifiedName.orEmpty()),
                        message = "The desktop CEF page requires KWebComposeWindowHost.",
                    )
                val nativeParent = validateComposeWindow(composeHost.window)
                val pageId = java.util.UUID.randomUUID().toString()
                val bridgeDispatcher = composeHost.bridgeDispatcherForPage?.create(pageId)
                val streamDispatcher = composeHost.streamDispatcherForPage?.create(pageId)
                if ((bridgeDispatcher == null) != composeHost.bridgeOrigin.isNullOrEmpty()) {
                    throw KWebConfigurationException(
                        code = "desktop.page.bridge-incomplete",
                        details = mapOf("origin" to composeHost.bridgeOrigin.orEmpty()),
                        message = "An exact-origin page requires both a bridge origin and a dispatcher.",
                    )
                }
                val gestureContext = engine.userGestureIssuer?.let { issuer ->
                    KWebPageGestureContext(
                        issuer = issuer,
                        engineId = engine.engineId,
                        profileId = name,
                        pageId = pageId,
                        origin = composeHost.bridgeOrigin.orEmpty(),
                    )
                }
                val eventStream = KWebPageEventStream(pageId, name, gestureContext)
                lateinit var nativePage: NativeBrowser
                nativePage = NativeBrowser.open(
                    engine = engine.nativeEngine(),
                    nativeParent = nativeParent,
                    profilePath = path,
                    initialUrl = initialUrl,
                    x = bounds.x,
                    y = bounds.y,
                    width = bounds.width,
                    height = bounds.height,
                    bridgeOrigin = composeHost.bridgeOrigin.orEmpty(),
                    bridgeDispatcher = bridgeDispatcher,
                    streamDispatcher = streamDispatcher,
                    downloadsEnabled = engine.downloadPolicy != null,
                    listener = listener@{ event ->
                        if (event.type == NativeBrowserEventType.NETWORK_OBSERVATION_FAILED) {
                            val code = event.details.takeIf { it == "network.observation-backpressure" }
                                ?: "network.event.invalid"
                            networkEventStream.fail(KWebNativeException(
                                code = code,
                                details = mapOf("profile" to name),
                                message = "Chromium could not deliver a bounded network observation.",
                            ))
                        } else if (event.type == NativeBrowserEventType.NETWORK_REQUEST) {
                            val networkEvent = try {
                                KWebDesktopNetworkJson.parseEvent(event.details)
                            } catch (error: Throwable) {
                                val code = if (error is KWebNetworkObservationLimitException) {
                                    "network.observation-backpressure"
                                } else {
                                    "network.event.invalid"
                                }
                                networkEventStream.fail(KWebNativeException(
                                    code = code,
                                    details = mapOf("profile" to name),
                                    message = "Chromium returned an invalid network observation event.",
                                    cause = error,
                                ))
                                return@listener
                            }
                            networkEventStream.publish(networkEvent)
                        } else if (event.type == NativeBrowserEventType.SECURITY_CHALLENGE) {
                            val challenge = KWebDesktopSecurityJson.parseChallenge(
                                event = event,
                                profileId = name,
                                pageId = pageId,
                            )
                            synchronized(lock) {
                                ensureProfileOperationOpen("security-challenge")
                                pendingSecurityChallenges[event.requestId] = SecurityChallengeOwner(
                                    browserHandle = event.browser,
                                    challenge = challenge,
                                )
                            }
                            if (!securityChallengeStream.publish(challenge)) {
                                val denyStatus = NativeBindings.browserSecurityRespond(
                                    event.browser,
                                    challenge.requestId,
                                    KWebDesktopSecurityJson.decisionPayload(
                                        challenge,
                                        securityDenyDecision(challenge),
                                    ),
                                )
                                synchronized(lock) {
                                    pendingSecurityChallenges.remove(challenge.requestId)
                                    resolvedSecurityChallenges += challenge.requestId
                                }
                                if (denyStatus != io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value) {
                                    throw securityChallengeStatusException(
                                        "security-challenge-overflow",
                                        denyStatus,
                                        mapOf("requestId" to challenge.requestId.toString()),
                                    )
                                }
                            }
                        } else if (event.type == NativeBrowserEventType.DOWNLOAD) {
                            val update = KWebDesktopDownloadJson.parse(event.details)
                            if (update.id != event.requestId) {
                                throw KWebNativeException(
                                    code = "download.event-invalid",
                                    details = mapOf("downloadId" to update.id.toString()),
                                    message = "The native download event ID does not match its ABI request ID.",
                                )
                            }
                            val download: KWebDesktopDownload
                            val created: Boolean
                            synchronized(lock) {
                                val existing = downloadsById[update.id]
                                if (existing == null) {
                                    download = KWebDesktopDownload(
                                        owner = this@KWebDesktopProfile,
                                        native = nativePage,
                                        id = update.id,
                                        profileId = name,
                                        pageId = pageId,
                                        initial = update,
                                    )
                                    downloadsById[update.id] = download
                                    created = true
                                } else {
                                    download = existing
                                    created = false
                                }
                            }
                            if (!created) {
                                download.apply(update)
                            }
                            if (created && !downloadStream.publish(download)) {
                                NativeBindings.browserDownloadControl(
                                    event.browser,
                                    update.id,
                                    1,
                                )
                                download.markOwnerClosed()
                            }
                        } else {
                            eventStream.accept(event)
                        }
                    },
                )
                val page = KWebDesktopPage(this@KWebDesktopProfile, nativePage, eventStream, pageId)
                pages += page
                page.trackTerminalOwnership()
                page
            }
        }
    }

    override suspend fun respondToSecurityChallenge(
        requestId: Long,
        decision: KWebSecurityDecision,
    ): KWebSecurityChallengeResult = withContext(Dispatchers.IO) {
        val owner = synchronized(lock) {
            if (requestId in resolvedSecurityChallenges) {
                throw securityChallengeStatusException(
                    "respond-security-challenge",
                    io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_ALREADY_RESOLVED.value,
                    mapOf("requestId" to requestId.toString()),
                )
            }
            pendingSecurityChallenges[requestId]
                ?: throw securityChallengeStatusException(
                    "respond-security-challenge",
                    io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_NOT_FOUND.value,
                    mapOf("requestId" to requestId.toString()),
                )
        }
        val payload = KWebDesktopSecurityJson.decisionPayload(owner.challenge, decision)
        val status = NativeBindings.browserSecurityRespond(owner.browserHandle, requestId, payload)
        when (status) {
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value -> {
                synchronized(lock) {
                    pendingSecurityChallenges.remove(requestId)
                    resolvedSecurityChallenges += requestId
                }
                val outcome = if (decision.isSecurityDeny()) {
                    KWebSecurityChallengeOutcome.DENIED
                } else {
                    KWebSecurityChallengeOutcome.ACCEPTED
                }
                KWebSecurityChallengeResult(requestId, outcome)
            }
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_DEADLINE_EXPIRED.value -> {
                synchronized(lock) {
                    pendingSecurityChallenges.remove(requestId)
                    resolvedSecurityChallenges += requestId
                }
                KWebSecurityChallengeResult(requestId, KWebSecurityChallengeOutcome.TIMED_OUT)
            }
            else -> {
                if (status == io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_ALREADY_RESOLVED.value ||
                    status == io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_NOT_FOUND.value ||
                    status == io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.SECURITY_CHALLENGE_PROFILE_CLOSING.value
                ) {
                    synchronized(lock) {
                        pendingSecurityChallenges.remove(requestId)
                        resolvedSecurityChallenges += requestId
                    }
                }
                throw securityChallengeStatusException(
                    "respond-security-challenge",
                    status,
                    mapOf("requestId" to requestId.toString()),
                )
            }
        }
    }

    override suspend fun listCookies(
        target: KWebPage,
        filter: KWebCookieFilter,
    ): List<KWebCookie> = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            validateCookieFilter(filter)
            readCookies(native).filter { it.matches(filter) }
        }
    }

    override suspend fun setCookie(
        target: KWebPage,
        cookie: KWebCookieSpec,
    ): KWebCookieMutationResult = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            validateCookieSpec(cookie)
            val result = native.profileData(
                KWebProfileDataOperation.SET_COOKIE,
                KWebDesktopProfileDataJson.cookieSetPayload(cookie),
            )
            if (!KWebDesktopProfileDataJson.parseSetCookieSuccess(result.payload)) {
                throw KWebNativeException(
                    code = "profile.cookie-set-failed",
                    details = mapOf("name" to cookie.name, "domain" to (cookie.domain ?: "")),
                    message = "Chromium rejected the Profile cookie.",
                )
            }
            KWebCookieMutationResult(affected = 1)
        }
    }

    override suspend fun deleteCookies(
        target: KWebPage,
        filter: KWebCookieFilter,
    ): KWebCookieMutationResult = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            validateCookieFilter(filter)
            val matching = readCookies(native).filter { it.matches(filter) }
            var deleted = 0
            matching.forEach { cookie ->
                native.profileData(
                    KWebProfileDataOperation.DELETE_COOKIE,
                    KWebDesktopProfileDataJson.cookieDeletePayload(cookie),
                )
                deleted += 1
            }
            KWebCookieMutationResult(affected = deleted)
        }
    }

    override suspend fun clearData(
        target: KWebPage,
        filter: KWebProfileDataFilter,
    ): KWebProfileDataClearResult = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            val origin = validateDataFilter(filter)
            val started = System.currentTimeMillis()
            val cleared = linkedSetOf<KWebProfileDataKind>()
            var removedCookies = 0
            if (KWebProfileDataKind.COOKIES in filter.kinds) {
                val cookieFilter = KWebCookieFilter(
                    origin = origin,
                    timeRange = filter.timeRange,
                )
                val matching = readCookies(native).filter { it.matches(cookieFilter) }
                matching.forEach { cookie ->
                    native.profileData(
                        KWebProfileDataOperation.DELETE_COOKIE,
                        KWebDesktopProfileDataJson.cookieDeletePayload(cookie),
                    )
                    removedCookies += 1
                }
                cleared += KWebProfileDataKind.COOKIES
            }
            if (KWebProfileDataKind.HTTP_CACHE in filter.kinds) {
                native.profileData(KWebProfileDataOperation.CLEAR_HTTP_CACHE, "{}")
                cleared += KWebProfileDataKind.HTTP_CACHE
            }
            val originKinds = filter.kinds - KWebProfileDataKind.COOKIES -
                KWebProfileDataKind.HTTP_CACHE
            if (originKinds.isNotEmpty()) {
                native.profileData(
                    KWebProfileDataOperation.CLEAR_ORIGIN,
                    KWebDesktopProfileDataJson.clearOriginPayload(
                        requireNotNull(origin),
                        KWebDesktopProfileDataJson.storageTypes(originKinds),
                    ),
                )
                cleared += originKinds
            }
            KWebProfileDataClearResult(
                requestedKinds = filter.kinds,
                clearedKinds = cleared,
                origin = origin,
                removedCookies = removedCookies,
                startedEpochMillis = started,
                completedEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    override suspend fun storageUsage(
        target: KWebPage,
        origin: String,
    ): KWebStorageUsage = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            val canonicalOrigin = validateOrigin(origin)
            try {
                KWebDesktopProfileDataJson.parseStorageUsage(
                    native.profileData(
                        KWebProfileDataOperation.STORAGE_USAGE,
                        KWebDesktopProfileDataJson.usagePayload(canonicalOrigin),
                    ).payload,
                    canonicalOrigin,
                )
            } catch (error: KWebNativeException) {
                throw error
            } catch (error: Throwable) {
                throw KWebNativeException(
                    code = "profile.storage-usage-failed",
                    details = mapOf("origin" to canonicalOrigin),
                    message = "Chromium returned an invalid storage usage result.",
                    cause = error,
                )
            }
        }
    }

    override suspend fun configureSpellcheck(
        target: KWebPage,
        configuration: KWebSpellcheckConfiguration,
    ): KWebSpellcheckState = withContext(Dispatchers.IO) {
        withProfileDataOperation {
            val native = requireDataTarget(target)
            validateSpellcheck(configuration)
            try {
                KWebDesktopProfileDataJson.parseSpellcheck(
                    native.profileData(
                        KWebProfileDataOperation.SET_SPELLCHECK,
                        KWebDesktopProfileDataJson.spellcheckPayload(configuration),
                    ).payload,
                )
            } catch (error: KWebNativeException) {
                throw error
            } catch (error: Throwable) {
                throw KWebNativeException(
                    code = "profile.spellcheck-set-failed",
                    details = emptyMap(),
                    message = "Chromium returned an invalid spellcheck state.",
                    cause = error,
                )
            }
        }
    }

    override suspend fun configureNetworkPolicy(policy: KWebNetworkPolicy) = withContext(Dispatchers.IO) {
        withNetworkOperation(
            pendingErrorCode = "network.operation-pending",
            closingErrorCode = "network.profile-closing",
        ) {
            requireOpen("configure-network-policy")
            val payload = KWebDesktopNetworkJson.policyPayload(policy)
            engine.nativeEngine().profileNetwork(
                profilePath = path,
                operation = KWebDesktopNetworkOperation.SET_POLICY,
                payload = payload,
            )
            Unit
        }
    }

    internal fun initialDownloadState(
        id: Long,
        pageId: String?,
        update: KWebDesktopDownloadUpdate,
    ): KWebDownloadState = stateFromUpdate(id, pageId, update, null, null)

    internal fun stateFromUpdate(
        id: Long,
        pageId: String?,
        update: KWebDesktopDownloadUpdate,
        scopedFile: KWebDownloadFile?,
        sha256: String?,
        statusOverride: KWebDownloadStatus? = null,
        reasonOverride: KWebDownloadInterruptReason? = null,
    ): KWebDownloadState {
        val status = statusOverride ?: mapDownloadStatus(update.status)
        return KWebDownloadState(
            id = id,
            profileId = name,
            pageId = pageId,
            originalUrl = update.originalUrl,
            url = update.url,
            suggestedFileName = update.suggestedFileName,
            fileName = if (status == KWebDownloadStatus.COMPLETE) {
                scopedFile?.name
            } else {
                null
            },
            contentDisposition = update.contentDisposition.takeIf { it.isNotBlank() },
            mimeType = update.mimeType.takeIf { it.isNotBlank() },
            receivedBytes = update.receivedBytes,
            totalBytes = update.totalBytes,
            currentSpeedBytesPerSecond = update.currentSpeedBytesPerSecond,
            status = status,
            interruptReason = reasonOverride ?: mapDownloadInterruptReason(update.interruptReason),
            sha256 = sha256,
            file = scopedFile,
        )
    }

    internal fun finalizeDownload(
        id: Long,
        pageId: String?,
        update: KWebDesktopDownloadUpdate,
    ): KWebDownloadState {
        val policy = engine.downloadPolicy ?: return stateFromUpdate(
            id, pageId, update, null, null,
            KWebDownloadStatus.INTERRUPTED,
            KWebDownloadInterruptReason.DESTINATION_INVALID,
        )
        val staging = update.stagingPath?.let { Path.of(it).toAbsolutePath().normalize() }
        if (staging == null || !isSafeStagingPath(staging) ||
            !Files.isRegularFile(staging, LinkOption.NOFOLLOW_LINKS)
        ) {
            deleteStaging(update.stagingPath)
            return stateFromUpdate(
                id, pageId, update, null, null,
                KWebDownloadStatus.INTERRUPTED,
                KWebDownloadInterruptReason.DESTINATION_INVALID,
            )
        }
        val actualHash = try {
            if (policy.computeSha256) sha256(staging) else null
        } catch (_: Throwable) {
            deleteStaging(update.stagingPath)
            return stateFromUpdate(
                id, pageId, update, null, null,
                KWebDownloadStatus.INTERRUPTED,
                KWebDownloadInterruptReason.DESTINATION_INVALID,
            )
        }
        val expected = policy.expectedSha256ByUrl[update.originalUrl]
            ?: policy.expectedSha256ByUrl[update.url]
        if (expected != null && expected != actualHash) {
            deleteStaging(update.stagingPath)
            return stateFromUpdate(
                id, pageId, update, null, actualHash,
                KWebDownloadStatus.INTERRUPTED,
                KWebDownloadInterruptReason.INTEGRITY_MISMATCH,
            )
        }
        val finalName = safeDownloadName(update.suggestedFileName)
            ?: run {
                deleteStaging(update.stagingPath)
                return stateFromUpdate(
                    id, pageId, update, null, actualHash,
                    KWebDownloadStatus.INTERRUPTED,
                    KWebDownloadInterruptReason.DESTINATION_INVALID,
                )
            }
        val target = try {
            moveIntoDestination(staging, finalName, policy)
        } catch (_: FileAlreadyExistsException) {
            deleteStaging(update.stagingPath)
            return stateFromUpdate(
                id, pageId, update, null, actualHash,
                KWebDownloadStatus.INTERRUPTED,
                KWebDownloadInterruptReason.FILE_EXISTS,
            )
        } catch (_: Throwable) {
            deleteStaging(update.stagingPath)
            return stateFromUpdate(
                id, pageId, update, null, actualHash,
                KWebDownloadStatus.INTERRUPTED,
                KWebDownloadInterruptReason.DESTINATION_INVALID,
            )
        }
        val file = KWebDesktopDownloadFile(
            path = target,
            name = target.fileName.toString(),
            sizeBytes = Files.size(target),
            sha256 = actualHash,
        )
        return stateFromUpdate(
            id, pageId, update, file, actualHash,
            KWebDownloadStatus.COMPLETE,
            KWebDownloadInterruptReason.NONE,
        )
    }

    internal fun deleteStaging(stagingPath: String?) {
        val value = stagingPath ?: return
        val path = runCatching { Path.of(value).toAbsolutePath().normalize() }.getOrNull() ?: return
        if (isSafeStagingPath(path)) deleteQuietly(path)
    }

    private fun isSafeStagingPath(path: Path): Boolean {
        val root = this.path.resolve(".kwebshell-downloads").toAbsolutePath().normalize()
        return path.isAbsolute && path.startsWith(root) &&
            !Files.isSymbolicLink(root) && !Files.isSymbolicLink(path) &&
            runCatching { path.parent?.toRealPath(LinkOption.NOFOLLOW_LINKS)?.startsWith(root.toRealPath(LinkOption.NOFOLLOW_LINKS)) == true }
                .getOrDefault(false)
    }

    private fun moveIntoDestination(
        staging: Path,
        name: String,
        policy: KWebDesktopDownloadPolicy,
    ): Path {
        val directory = policy.directory.toRealPath(LinkOption.NOFOLLOW_LINKS)
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw KWebNativeException(
                code = "download.destination.invalid",
                details = mapOf("directory" to directory.toString()),
                message = "The download destination is not a regular directory.",
            )
        }
        var candidate = directory.resolve(name).normalize()
        if (candidate.parent != directory) throw IOException("Download name escaped destination.")
        when (policy.collision) {
            KWebDownloadCollisionPolicy.FAIL -> {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw FileAlreadyExistsException(candidate.toString())
            }
            KWebDownloadCollisionPolicy.RENAME_UNIQUE -> {
                var index = 1
                while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    candidate = directory.resolve(uniqueDownloadName(name, index++)).normalize()
                    if (index > 10_000) throw IOException("The download destination has too many collisions.")
                }
            }
            KWebDownloadCollisionPolicy.REPLACE_EXISTING -> {
                if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS) &&
                    (Files.isSymbolicLink(candidate) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS))
                ) throw IOException("The existing download target is not a regular file.")
            }
        }
        val options = when (policy.collision) {
            KWebDownloadCollisionPolicy.REPLACE_EXISTING -> arrayOf(
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            else -> arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        return Files.move(staging, candidate, *options)
    }

    override suspend fun flush(target: KWebPage): KWebProfileFlushResult =
        withContext(Dispatchers.IO) {
            withProfileDataOperation {
                val native = requireDataTarget(target)
                native.profileData(KWebProfileDataOperation.FLUSH, "{}")
                KWebProfileFlushResult(System.currentTimeMillis())
            }
        }

    private suspend fun readCookies(native: NativeBrowser): List<KWebCookie> = try {
        KWebDesktopProfileDataJson.parseCookies(
            native.profileData(KWebProfileDataOperation.GET_COOKIES, "{}").payload,
        )
    } catch (error: KWebNativeException) {
        throw error
    } catch (error: Throwable) {
        throw KWebNativeException(
            code = "profile.native-operation-failed",
            details = emptyMap(),
            message = "Chromium returned an invalid cookie result.",
            cause = error,
        )
    }

    private fun requireDataTarget(target: KWebPage): NativeBrowser {
        requireOpen("profile-data")
        val page = target as? KWebDesktopPage
            ?: throw KWebConfigurationException(
                code = "profile.data-target-invalid",
                details = emptyMap(),
                message = "Profile data operations require a desktop KWebPage target.",
            )
        if (page.profile !== this) {
            throw KWebConfigurationException(
                code = "profile.data-target-cross-profile",
                details = mapOf("profile" to name),
                message = "The Profile data target belongs to another Profile.",
            )
        }
        return page.requireProfileDataNative()
    }

    private suspend fun <T> withProfileDataOperation(
        closingErrorCode: String = "desktop.profile.closed",
        block: suspend () -> T,
    ): T {
        synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
        if (!dataOperationMutex.tryLock()) {
            synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
            throw KWebNativeException(
                code = "profile.data-operation-pending",
                details = mapOf("profile" to path.toString()),
                message = "Another Profile data operation is already active.",
            )
        }
        try {
            synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
            return block()
        } finally {
            dataOperationMutex.unlock()
        }
    }

    private suspend fun <T> withNetworkOperation(
        pendingErrorCode: String,
        closingErrorCode: String,
        block: suspend () -> T,
    ): T {
        synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
        if (!networkOperationMutex.tryLock()) {
            synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
            throw KWebNativeException(
                code = pendingErrorCode,
                details = mapOf("profile" to path.toString()),
                message = "Another Profile network operation is already active.",
            )
        }
        val completed = CountDownLatch(1)
        try {
            synchronized(lock) {
                ensureProfileOperationOpen(closingErrorCode)
                check(activeNetworkOperation == null) {
                    "A Profile network operation is active without holding its mutex."
                }
                activeNetworkOperation = completed
            }
            val result = try {
                block()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
                throw error
            }
            synchronized(lock) { ensureProfileOperationOpen(closingErrorCode) }
            return result
        } finally {
            synchronized(lock) {
                if (activeNetworkOperation === completed) activeNetworkOperation = null
            }
            completed.countDown()
            networkOperationMutex.unlock()
        }
    }

    override fun close() {
        engine.withEngineLock {
            val activeOperation = synchronized(lock) {
                if (mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                    if (pages.isNotEmpty()) {
                        throw KWebNativeException(
                            code = "desktop.profile.live-pages",
                            details = mapOf("profile" to path.toString(), "count" to pages.size.toString()),
                            message = "The Profile cannot close while pages are still live.",
                        )
                    }
                    mutableLifecycle.value = KWebLifecycleState.CLOSING
                    activeNetworkOperation
                } else {
                    null
                }
            }
            try {
                if (activeOperation != null &&
                    !activeOperation.await(PROFILE_OPERATION_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                ) {
                    throw KWebNativeException(
                        code = "network.profile-closing",
                        details = mapOf("profile" to path.toString()),
                        message = "A pending Profile operation did not settle before close.",
                    )
                }
                synchronized(lock) {
                    if (mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                        clearProfileNetworkPolicyWithRetry()
                        networkEventStream.close()
                        downloadsById.values.forEach { it.close() }
                        downloadStream.close()
                        mutableLifecycle.value = KWebLifecycleState.CLOSED
                        engine.removeProfile(this)
                    }
                }
            } catch (error: Throwable) {
                synchronized(lock) {
                    if (!closedByEngine && mutableLifecycle.value == KWebLifecycleState.CLOSING) {
                        mutableLifecycle.value = KWebLifecycleState.OPEN
                    }
                }
                throw error
            }
        }
    }

    internal fun removePage(page: KWebDesktopPage) {
        val ownedDownloads: List<KWebDesktopDownload>
        synchronized(lock) {
            pages.remove(page)
            pendingSecurityChallenges.entries.removeIf { it.value.challenge.pageId == page.id }
            ownedDownloads = downloadsById.values.filter { it.pageId == page.id }
        }
        ownedDownloads.forEach { it.markOwnerClosed() }
    }

    internal fun isSamePhysicalPath(other: Path): Boolean =
        try {
            Files.isSameFile(path, other)
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.identity-unavailable",
                details = mapOf("profile" to path.toString(), "requested" to other.toString()),
                message = "The Profile physical identity could not be verified.",
                cause = error,
            )
        }

    internal fun markClosedByEngine() {
        val orphanedPages: List<KWebDesktopPage>
        synchronized(lock) {
            closedByEngine = true
            mutableLifecycle.value = KWebLifecycleState.CLOSED
            networkEventStream.close()
            downloadsById.values.forEach { it.markOwnerClosed() }
            downloadsById.values.forEach { it.close() }
            downloadStream.close()
            pendingSecurityChallenges.clear()
            resolvedSecurityChallenges.clear()
            orphanedPages = pages.toList()
        }
        // An Engine shutdown is a page-owner close: every page the Profile
        // still holds must have its outstanding gesture tokens invalidated
        // exactly as an explicit Page close would.
        orphanedPages.forEach { it.onEngineClosed() }
    }

    private fun ensureProfileOperationOpen(closingErrorCode: String) {
        if (mutableLifecycle.value == KWebLifecycleState.CLOSING) {
            throw KWebNativeException(
                code = closingErrorCode,
                details = mapOf("profile" to path.toString()),
                message = "The Profile is closing and cannot accept operations.",
            )
        }
        requireOpen("profile-data")
    }

    private fun requireOpen(operation: String) {
        if (closedByEngine || mutableLifecycle.value != KWebLifecycleState.OPEN) {
            val closing = mutableLifecycle.value == KWebLifecycleState.CLOSING &&
                operation == "configure-network-policy"
            throw KWebNativeException(
                code = if (closing) "network.profile-closing" else "desktop.profile.closed",
                details = mapOf("operation" to operation, "profile" to path.toString()),
                message = "The KWebShell Profile is not open.",
            )
        }
        engine.requireEngineOpen(operation)
    }

    private fun clearProfileNetworkPolicyWithRetry() {
        var lastFailure: KWebNativeException? = null
        repeat(10) {
            try {
                engine.nativeEngine().clearProfileNetworkPolicy(path)
                return
            } catch (error: KWebNativeException) {
                if (error.code != "native.abi.cef-ui-task-failed") throw error
                lastFailure = error
                Thread.sleep(100)
            }
        }
        throw requireNotNull(lastFailure)
    }

    private fun validateComposeWindow(window: ComposeWindow): Long =
        NativeEngine.onAwtEventDispatchThread {
            if (!window.isDisplayable || !window.isShowing) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-not-visible",
                    details = emptyMap(),
                    message = "ComposeWindow must be displayable and showing before page creation.",
                )
            }
            val handle = try {
                window.windowHandle
            } catch (error: Throwable) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-handle-unavailable",
                    details = emptyMap(),
                    message = "ComposeWindow.windowHandle could not be obtained.",
                    cause = error,
                )
            }
            if (handle == 0L) {
                throw KWebConfigurationException(
                    code = "desktop.page.parent-handle-invalid",
                    details = emptyMap(),
                    message = "ComposeWindow.windowHandle returned zero.",
                )
            }
            handle
        }
}

internal class KWebDesktopPage(
    private val owner: KWebDesktopProfile,
    private val native: NativeBrowser,
    private val eventStream: KWebPageEventStream,
    override val id: String,
) : KWebPage {
    private val closeLock = Any()

    internal fun trackTerminalOwnership() {
        eventStream.setPageClosedListener { owner.removePage(this) }
    }

    override val lifecycle: StateFlow<KWebLifecycleState> = native.lifecycle
    override val events: Flow<KWebPageEvent> = eventStream.events
    override val profile: KWebProfile = owner

    internal fun requireProfileDataNative(): NativeBrowser {
        if (lifecycle.value != KWebLifecycleState.OPEN || native.rendererHasTerminated) {
            throw KWebNativeException(
                code = if (native.rendererHasTerminated) {
                    "profile.data-target-terminated"
                } else {
                    "profile.data-target-invalid"
                },
                details = mapOf("pageId" to id),
                message = "The Profile data target page is not live.",
            )
        }
        native.requireLiveHandle("profile-data")
        return native
    }

    internal fun startDownload(url: String) {
        val status = native.startDownload(url)
        if (status != NativeStatus.OK.value) {
            throw nativeStatusException(
                "start-download",
                status,
                mapOf("url" to url),
            )
        }
    }

    override suspend fun navigate(url: String) {
        withContext(Dispatchers.IO) {
            requireOpen("navigate")
            native.navigate(url)
        }
    }

    override suspend fun reload(mode: KWebReloadMode): KWebReloadResult = withContext(Dispatchers.IO) {
        if (native.rendererHasTerminated) {
            throw KWebNativeException(
                code = "page.renderer-terminated",
                details = mapOf("pageId" to id),
                message = "The renderer for this page has terminated.",
            )
        }
        if (lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "page.closed",
                details = mapOf("pageId" to id),
                message = "A closed page cannot be reloaded.",
            )
        }
        val status = native.reload(mode == KWebReloadMode.IGNORE_CACHE)
        when (status) {
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value ->
                KWebReloadResult(mode, KWebReloadOutcome.STARTED)
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_OPERATION_PENDING.value ->
                throw KWebNativeException(
                    code = "page.operation-pending",
                    details = mapOf("operation" to "reload", "pageId" to id),
                    message = "A before-unload decision is still pending for this page.",
                )
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.BROWSER_NOT_READY.value,
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.BROWSER_CLOSING.value ->
                throw KWebNativeException(
                    code = "page.closed",
                    details = mapOf("pageId" to id),
                    message = "The page cannot accept a reload while it is closing.",
                )
            else -> throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-reload",
                status,
                mapOf("pageId" to id),
            )
        }
    }

    override suspend fun respondToBeforeUnload(
        requestId: Long,
        decision: KWebBeforeUnloadDecision,
    ): KWebBeforeUnloadResult = withContext(Dispatchers.IO) {
        val deadlineNanos = eventStream.takeBeforeUnload(requestId)
        if (System.nanoTime() >= deadlineNanos) {
            return@withContext KWebBeforeUnloadResult(
                requestId,
                KWebBeforeUnloadDecision.CANCEL,
                timedOut = true,
            )
        }
        val status = native.respondToBeforeUnload(
            requestId,
            decision == KWebBeforeUnloadDecision.PROCEED,
        )
        when (status) {
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value ->
                KWebBeforeUnloadResult(requestId, decision, timedOut = false)
            io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_REQUEST_NOT_FOUND.value ->
                if (System.nanoTime() >= deadlineNanos) {
                    KWebBeforeUnloadResult(
                        requestId,
                        KWebBeforeUnloadDecision.CANCEL,
                        timedOut = true,
                    )
                } else {
                    throw KWebNativeException(
                        code = "page.before-unload-stale",
                        details = mapOf("requestId" to requestId.toString()),
                        message = "The before-unload request is stale or belongs to another page.",
                    )
                }
            else -> throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-before-unload-respond",
                status,
                mapOf("requestId" to requestId.toString()),
            )
        }
    }

    override suspend fun respondToPopup(
        requestId: Long,
        decision: KWebPopupDecision,
    ): KWebPopupResult = withContext(Dispatchers.IO) {
        // Resolve the page-bound request first. This makes a stale or forged
        // request fail with the contract error even when the supplied decision
        // also contains an invalid owner or bounds.
        val requestCandidate = eventStream.requirePopup(requestId)
        val allowedHost = when (decision) {
            KWebPopupDecision.DENY -> null
            is KWebPopupDecision.ALLOW -> {
                if (decision.bounds.x > 32768 || decision.bounds.y > 32768 ||
                    decision.bounds.width > 32768 || decision.bounds.height > 32768
                ) {
                    throw KWebConfigurationException(
                        code = "page.popup-bounds-invalid",
                        details = mapOf(
                            "x" to decision.bounds.x.toString(),
                            "y" to decision.bounds.y.toString(),
                            "width" to decision.bounds.width.toString(),
                            "height" to decision.bounds.height.toString(),
                        ),
                        message = "Popup child dimensions must not exceed the native viewport limit.",
                    )
                }
                decision.owner as? KWebComposeWindowHost ?: throw KWebNativeException(
                    code = "page.popup-owner-invalid",
                    details = mapOf("requestId" to requestId.toString()),
                    message = "An allowed popup requires a caller-owned Compose window host.",
                )
            }
        }
        val request = eventStream.takePopup(requestId)
        if (System.nanoTime() >= request.second) {
            return@withContext KWebPopupResult(requestId, KWebPopupOutcome.TIMED_OUT)
        }
        val allow = decision is KWebPopupDecision.ALLOW
        val status = native.respondToPopup(requestId, allow)
        if (status == io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.PAGE_REQUEST_NOT_FOUND.value) {
            return@withContext if (System.nanoTime() >= request.second) {
                KWebPopupResult(requestId, KWebPopupOutcome.TIMED_OUT)
            } else {
                throw KWebNativeException(
                    code = "page.popup-stale",
                    details = mapOf("requestId" to requestId.toString()),
                    message = "The popup request is stale or belongs to another page.",
                )
            }
        }
        if (status != io.github.kingsword09.kwebshell.desktop.internal.NativeStatus.OK.value) {
            throw io.github.kingsword09.kwebshell.desktop.internal.nativeStatusException(
                "browser-popup-respond",
                status,
                mapOf("requestId" to requestId.toString()),
            )
        }
        when (decision) {
            KWebPopupDecision.DENY -> KWebPopupResult(requestId, KWebPopupOutcome.DENIED)
            is KWebPopupDecision.ALLOW -> {
                val page = owner.openPage(requireNotNull(allowedHost), requestCandidate.targetUrl, decision.bounds)
                KWebPopupResult(requestId, KWebPopupOutcome.ALLOWED, page)
            }
        }
    }

    override suspend fun setBounds(bounds: KWebRect) {
        withContext(Dispatchers.IO) {
            requireOpen("set-bounds")
            native.setBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        }
    }

    override suspend fun setSurfaceState(visible: Boolean, focused: Boolean) {
        withContext(Dispatchers.IO) {
            requireOpen("set-surface-state")
            native.setSurfaceState(visible, focused)
        }
    }

    override suspend fun openDevTools() {
        withContext(Dispatchers.IO) {
            requireOpen("open-devtools")
            native.openDevTools()
        }
    }

    override suspend fun closeDevTools() {
        withContext(Dispatchers.IO) {
            requireOpen("close-devtools")
            native.closeDevTools()
        }
    }

    override fun close() {
        synchronized(closeLock) {
            if (lifecycle.value == KWebLifecycleState.CLOSED) {
                eventStream.onPageClosed()
                return
            }
            try {
                native.close()
            } finally {
                if (lifecycle.value == KWebLifecycleState.CLOSED) {
                    eventStream.onPageClosed()
                }
            }
        }
    }

    internal fun onEngineClosed() {
        eventStream.onPageClosed()
    }

    internal fun requireNativeHandle(operation: String): Long = native.requireLiveHandle(operation)

    private fun requireOpen(operation: String) {
        if (native.rendererHasTerminated) {
            throw KWebNativeException(
                code = "page.renderer-terminated",
                details = mapOf("operation" to operation, "pageId" to id),
                message = "The renderer for this page has terminated.",
            )
        }
        if (lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.page.closed",
                details = mapOf("operation" to operation),
                message = "The KWebShell page is not open.",
            )
        }
    }
}

/**
 * Gesture minting context for one page. The issuer is application-supplied;
 * minting happens only from the native input event path and binding uses the
 * page's exact configured bridge origin.
 */
internal class KWebPageGestureContext(
    private val issuer: KWebUserGestureIssuer,
    private val engineId: String,
    private val profileId: String,
    private val pageId: String,
    private val origin: String,
) {
    fun onInputGesture() {
        if (origin.isEmpty()) return
        issuer.mint(
            KWebGestureBinding(
                engineId = engineId,
                profileId = profileId,
                pageId = pageId,
                origin = origin,
            ),
        )
    }

    fun onNavigationStarted() {
        if (origin.isNotEmpty()) issuer.invalidateNavigation(pageId)
    }

    fun onPageClosed() {
        if (origin.isNotEmpty()) issuer.invalidatePage(pageId)
    }
}

internal class KWebPageEventStream(
    private val pageId: String,
    private val profileId: String,
    private val gestureContext: KWebPageGestureContext? = null,
) {
    private val mutableEvents = MutableSharedFlow<KWebPageEvent>(
        replay = 128,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val events: Flow<KWebPageEvent> = mutableEvents.asSharedFlow()
    private val requestLock = Any()
    private data class PendingPopup(val request: KWebPopupRequest, val deadlineNanos: Long)
    private val pendingPopups = linkedMapOf<Long, PendingPopup>()
    private val pendingBeforeUnload = linkedMapOf<Long, Long>()
    private var nextPublicSequence = 1L
    private var pageClosed = false
    private var pageClosedListener: (() -> Unit)? = null

    internal fun accept(event: NativeBrowserEvent) {
        if (event.type == NativeBrowserEventType.INPUT_GESTURE) {
            gestureContext?.onInputGesture()
            return
        }
        if (event.type == NativeBrowserEventType.NAVIGATION_STARTED) {
            gestureContext?.onNavigationStarted()
        }
        val publicSequence = synchronized(requestLock) {
            val sequence = nextPublicSequence
            nextPublicSequence += 1
            sequence
        }
        val publicEvent = event.toPublicEvent(pageId, profileId, publicSequence)
        synchronized(requestLock) {
            publicEvent.popupRequest?.let {
                pendingPopups[it.requestId] = PendingPopup(
                    it,
                    System.nanoTime() + 5_000_000_000L,
                )
            }
            publicEvent.beforeUnloadRequest?.let {
                pendingBeforeUnload[it.requestId] = System.nanoTime() + 5_000_000_000L
            }
        }
        val emitted = mutableEvents.tryEmit(publicEvent)
        if (event.type == NativeBrowserEventType.CLOSED) {
            onPageClosed()
        }
        if (!emitted) {
            throw KWebNativeException(
                code = "desktop.page.event-backpressure",
                details = mapOf("sequence" to event.sequence.toString()),
                message = "The page event stream has no capacity for the ordered native event.",
            )
        }
    }

    internal fun setPageClosedListener(listener: () -> Unit) {
        val invokeNow = synchronized(requestLock) {
            pageClosedListener = listener
            pageClosed
        }
        if (invokeNow) listener()
    }

    internal fun onPageClosed() {
        gestureContext?.onPageClosed()
        val listener = synchronized(requestLock) {
            pendingPopups.clear()
            pendingBeforeUnload.clear()
            if (pageClosed) null else {
                pageClosed = true
                pageClosedListener
            }
        }
        listener?.invoke()
    }

    internal fun requirePopup(requestId: Long): KWebPopupRequest = synchronized(requestLock) {
        pendingPopups[requestId]?.request
    } ?: throw KWebNativeException(
        code = "page.popup-stale",
        details = mapOf("requestId" to requestId.toString()),
        message = "The popup request is stale or belongs to another page.",
    )

    internal fun takePopup(requestId: Long): Pair<KWebPopupRequest, Long> {
        val pending = synchronized(requestLock) { pendingPopups.remove(requestId) }
            ?: throw KWebNativeException(
                code = "page.popup-stale",
                details = mapOf("requestId" to requestId.toString()),
                message = "The popup request is stale or belongs to another page.",
            )
        return pending.request to pending.deadlineNanos
    }

    internal fun takeBeforeUnload(requestId: Long): Long {
        return synchronized(requestLock) {
            pendingBeforeUnload.remove(requestId)
        } ?: throw KWebNativeException(
            code = "page.before-unload-stale",
            details = mapOf("requestId" to requestId.toString()),
            message = "The before-unload request is stale or belongs to another page.",
        )
    }
}

private fun NativeBrowserEvent.toPublicEvent(
    pageId: String,
    profileId: String,
    publicSequence: Long,
): KWebPageEvent {
    val type = when (type) {
        NativeBrowserEventType.CREATED -> KWebPageEventType.CREATED
        NativeBrowserEventType.NAVIGATION_STARTED -> KWebPageEventType.NAVIGATION_STARTED
        NativeBrowserEventType.NAVIGATION_COMMITTED -> KWebPageEventType.NAVIGATION_COMMITTED
        NativeBrowserEventType.SAME_DOCUMENT_NAVIGATION -> KWebPageEventType.SAME_DOCUMENT_NAVIGATION
        NativeBrowserEventType.ADDRESS_CHANGED -> KWebPageEventType.ADDRESS_CHANGED
        NativeBrowserEventType.LOADING_STATE_CHANGED -> KWebPageEventType.LOADING_STATE_CHANGED
        NativeBrowserEventType.LOAD_ENDED -> KWebPageEventType.LOAD_ENDED
        NativeBrowserEventType.LOAD_FAILED -> KWebPageEventType.LOAD_FAILED
        NativeBrowserEventType.RESIZED -> KWebPageEventType.RESIZED
        NativeBrowserEventType.FATAL_ERROR -> KWebPageEventType.FATAL_ERROR
        NativeBrowserEventType.TITLE_CHANGED -> KWebPageEventType.TITLE_CHANGED
        NativeBrowserEventType.FAVICON_CHANGED -> KWebPageEventType.FAVICON_CHANGED
        NativeBrowserEventType.BEFORE_UNLOAD_REQUESTED -> KWebPageEventType.BEFORE_UNLOAD_REQUESTED
        NativeBrowserEventType.POPUP_REQUESTED -> KWebPageEventType.POPUP_REQUESTED
        NativeBrowserEventType.RENDERER_UNRESPONSIVE -> KWebPageEventType.RENDERER_UNRESPONSIVE
        NativeBrowserEventType.RENDERER_RESPONSIVE -> KWebPageEventType.RENDERER_RESPONSIVE
        NativeBrowserEventType.RENDERER_TERMINATED -> KWebPageEventType.RENDERER_TERMINATED
        NativeBrowserEventType.CLOSED -> KWebPageEventType.CLOSED
        NativeBrowserEventType.DEVTOOLS_OPENED -> KWebPageEventType.DEVTOOLS_OPENED
        NativeBrowserEventType.DEVTOOLS_CLOSED -> KWebPageEventType.DEVTOOLS_CLOSED
        NativeBrowserEventType.DEVTOOLS_FAILED -> KWebPageEventType.DEVTOOLS_FAILED
        NativeBrowserEventType.INPUT_GESTURE ->
            // Filtered by KWebPageEventStream before the public mapping.
            throw IllegalStateException("The internal input-gesture event reached the public mapping.")
        NativeBrowserEventType.NETWORK_REQUEST ->
            throw IllegalStateException("The Profile network event reached the page mapping.")
        NativeBrowserEventType.NETWORK_OBSERVATION_FAILED ->
            throw IllegalStateException("The Profile network observation failure reached the page mapping.")
        NativeBrowserEventType.SECURITY_CHALLENGE ->
            throw IllegalStateException("The Profile security challenge reached the page mapping.")
        NativeBrowserEventType.DOWNLOAD ->
            throw IllegalStateException("The Profile download event reached the page mapping.")
    }
    val flags = buildSet {
        if (this@toPublicEvent.flags and 1 != 0) add(KWebPageEventFlag.LOADING)
        if (this@toPublicEvent.flags and 2 != 0) add(KWebPageEventFlag.CAN_GO_BACK)
        if (this@toPublicEvent.flags and 4 != 0) add(KWebPageEventFlag.CAN_GO_FORWARD)
        if (this@toPublicEvent.flags and 8 != 0) add(KWebPageEventFlag.USER_GESTURE)
        if (this@toPublicEvent.flags and 16 != 0) add(KWebPageEventFlag.REDIRECT)
    }
    val eventBounds = if (width > 0 && height > 0) KWebBounds(width, height) else null
    val publicOrigin = origin.takeIf { it.isNotEmpty() }
    val publicUrl = url.ifEmpty {
        if (type in setOf(
                KWebPageEventType.NAVIGATION_STARTED,
                KWebPageEventType.NAVIGATION_COMMITTED,
                KWebPageEventType.SAME_DOCUMENT_NAVIGATION,
                KWebPageEventType.ADDRESS_CHANGED,
                KWebPageEventType.LOAD_ENDED,
                KWebPageEventType.LOAD_FAILED,
            )
        ) text else ""
    }.takeIf { it.isNotEmpty() }
    val publicTitle = if (type == KWebPageEventType.TITLE_CHANGED) {
        boundedPublicText(title.ifEmpty { text }, 4096)
    } else {
        null
    }
    val publicReason = when (reason) {
        1 -> KWebPageEventReason.NAVIGATION_FAILED
        2 -> KWebPageEventReason.NAVIGATION_ABORTED
        3 -> KWebPageEventReason.BEFORE_UNLOAD_TIMEOUT
        4 -> KWebPageEventReason.POPUP_TIMEOUT
        5 -> KWebPageEventReason.RENDERER_CRASH
        6 -> KWebPageEventReason.RENDERER_KILLED
        7 -> KWebPageEventReason.RENDERER_OOM
        8 -> KWebPageEventReason.RENDERER_UNKNOWN
        9 -> KWebPageEventReason.NATIVE_FAILURE
        else -> KWebPageEventReason.NONE
    }
    val popupFeatures = if (type == KWebPageEventType.POPUP_REQUESTED) {
        val values = details.split(',')
        fun valueAt(index: Int): Int? = values.getOrNull(index)?.toIntOrNull()?.takeIf { it >= 0 }
        KWebPopupFeatures(
            x = valueAt(0),
            y = valueAt(1),
            width = valueAt(2),
            height = valueAt(3),
            isPopup = values.getOrNull(4) == "1",
        )
    } else {
        null
    }
    val popup = popupFeatures?.let {
        KWebPopupRequest(
            requestId = requestId,
            pageId = pageId,
            profileId = profileId,
            frameId = frameId,
            origin = publicOrigin,
            targetUrl = publicUrl.orEmpty(),
            frameName = title,
            userGesture = KWebPageEventFlag.USER_GESTURE in flags,
            features = it,
        )
    }
    val beforeUnload = if (type == KWebPageEventType.BEFORE_UNLOAD_REQUESTED) {
        KWebBeforeUnloadRequest(requestId, pageId, frameId, publicOrigin, text.takeIf { it.isNotEmpty() })
    } else {
        null
    }
    val rendererReason = when (reason) {
        5 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.CRASH
        6 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.KILLED
        7 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.OOM
        8 -> io.github.kingsword09.kwebshell.core.KWebRendererFailureReason.UNKNOWN
        else -> null
    }
    return KWebPageEvent(
        type = type,
        sequence = publicSequence,
        statusCode = statusCode,
        bounds = eventBounds,
        flags = flags,
        pageId = pageId,
        profileId = profileId,
        frameId = frameId.ifEmpty { "0" },
        frameScope = if (frameScope == 2) KWebPageFrameScope.SUBFRAME else KWebPageFrameScope.MAIN,
        origin = publicOrigin,
        url = publicUrl,
        title = publicTitle,
        faviconUrls = if (type == KWebPageEventType.FAVICON_CHANGED && details.isNotEmpty()) details.split('\n') else emptyList(),
        reason = publicReason,
        rendererFailureReason = rendererReason,
        beforeUnloadRequest = beforeUnload,
        popupRequest = popup,
    )
}

private fun boundedPublicText(value: String, maximumBytes: Int): String {
    val sanitized = value.replace('\u0000', '\uFFFD')
    if (sanitized.encodeToByteArray().size <= maximumBytes) return sanitized
    var end = sanitized.length
    while (end > 0 && sanitized.substring(0, end).encodeToByteArray().size > maximumBytes) {
        end -= 1
    }
    return sanitized.substring(0, end)
}

internal object KWebProfilePathResolver {
    private val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

    internal fun resolve(root: Path, name: String): Path {
        if (!NAME_PATTERN.matches(name) || name.equals("default", ignoreCase = true)) {
            throw KWebConfigurationException(
                code = "profile.name.invalid",
                details = mapOf("name" to name),
                message = "Profile names must be one safe directory component and cannot be Default.",
            )
        }
        val realRoot = try {
            root.toAbsolutePath().normalize().toRealPath()
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.root.invalid",
                details = mapOf("root" to root.toString()),
                message = "The engine Profile root cannot be canonicalized.",
                cause = error,
            )
        }
        val candidate = realRoot.resolve(name).normalize()
        if (candidate.parent != realRoot) {
            throw KWebConfigurationException(
                code = "profile.path.invalid",
                details = mapOf("name" to name),
                message = "The Profile must be a direct child of the engine root cache.",
            )
        }
        try {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(candidate)) {
                    throw KWebConfigurationException(
                        code = "profile.path.invalid",
                        details = mapOf("path" to candidate.toString()),
                        message = "The Profile path is not a directory.",
                    )
                }
            } else {
                Files.createDirectory(candidate)
            }
            val canonical = candidate.toRealPath()
            if (canonical.parent != realRoot || !Files.isDirectory(canonical)) {
                throw KWebConfigurationException(
                    code = "profile.path.invalid",
                    details = mapOf("path" to canonical.toString()),
                    message = "The Profile path resolves outside the engine root cache.",
                )
            }
            return canonical
        } catch (error: KWebConfigurationException) {
            throw error
        } catch (error: java.io.IOException) {
            throw KWebConfigurationException(
                code = "profile.path.invalid",
                details = mapOf("path" to candidate.toString()),
                message = "The Profile directory could not be created or canonicalized.",
                cause = error,
            )
        }
    }
}

private fun validateCookieSpec(cookie: KWebCookieSpec) {
    val url = try {
        URI(cookie.url)
    } catch (error: Throwable) {
        throw KWebConfigurationException(
            code = "profile.cookie-invalid",
            details = mapOf("field" to "url"),
            message = "The cookie URL is not a valid URI.",
            cause = error,
        )
    }
    if (!url.isAbsolute || url.scheme?.lowercase() !in setOf("http", "https") ||
        url.host.isNullOrBlank() || url.userInfo != null
    ) {
        throw KWebConfigurationException(
            code = "profile.cookie-invalid",
            details = mapOf("field" to "url"),
            message = "Cookie URLs must be absolute http or https URLs without user information.",
        )
    }
    if (cookie.name.isBlank() || cookie.name.any { it.code < 0x20 || it == ';' || it == '=' }) {
        throw KWebConfigurationException(
            code = "profile.cookie-invalid",
            details = mapOf("field" to "name"),
            message = "Cookie names must be non-empty and contain no control, separator, or equals characters.",
        )
    }
    if (cookie.value.encodeToByteArray().size > 1024 * 1024) {
        throw KWebConfigurationException(
            code = "profile.cookie-invalid",
            details = mapOf("field" to "value"),
            message = "Cookie values cannot exceed 1 MiB UTF-8.",
        )
    }
    if (cookie.path.isBlank() || !cookie.path.startsWith("/") ||
        cookie.path.contains('\u0000') ||
        cookie.sourcePort != null && cookie.sourcePort != -1 && cookie.sourcePort !in 1..65535 ||
        cookie.sourcePort != null && cookie.sourceScheme == null ||
        cookie.sourcePort != null && cookie.sourceScheme == io.github.kingsword09.kwebshell.core.KWebCookieSourceScheme.UNKNOWN
    ) {
        throw KWebConfigurationException(
            code = "profile.cookie-invalid",
            details = mapOf("field" to "path-or-source-port"),
            message = "Cookie path and source port values are invalid.",
        )
    }
    cookie.expiresEpochMillis?.let {
        if (it < 0) {
            throw KWebConfigurationException(
                code = "profile.cookie-invalid",
                details = mapOf("field" to "expiresEpochMillis"),
                message = "Cookie expiry cannot be negative.",
            )
        }
    }
    cookie.partitionKey?.let { partitionKey ->
        if (partitionKey.topLevelSite.isNullOrBlank() || partitionKey.opaque) {
            throw KWebConfigurationException(
                code = "profile.cookie-invalid",
                details = mapOf("field" to "partitionKey"),
                message = "Set-cookie partition keys must be non-opaque canonical origins.",
            )
        }
        validateOrigin(requireNotNull(partitionKey.topLevelSite))
    }
}

private fun validateCookieFilter(filter: KWebCookieFilter) {
    filter.origin?.let(::validateOrigin)
    val range = filter.timeRange
    val since = range.sinceEpochMillis
    val until = range.untilEpochMillis
    if (since?.let { it < 0 } == true ||
        until?.let { it < 0 } == true ||
        since != null && until != null && since > until
    ) {
        throw KWebConfigurationException(
            code = "profile.data-time-range-invalid",
            details = emptyMap(),
            message = "The Profile data time range is invalid.",
        )
    }
}

private fun validateDataFilter(filter: KWebProfileDataFilter): String? {
    if (filter.kinds.isEmpty()) {
        throw KWebConfigurationException(
            code = "profile.data-kind-invalid",
            details = emptyMap(),
            message = "At least one Profile data kind is required.",
        )
    }
    val range = filter.timeRange
    val since = range.sinceEpochMillis
    val until = range.untilEpochMillis
    if (since?.let { it < 0 } == true ||
        until?.let { it < 0 } == true ||
        since != null && until != null && since > until
    ) {
        throw KWebConfigurationException(
            code = "profile.data-time-range-invalid",
            details = emptyMap(),
            message = "The Profile data time range is invalid.",
        )
    }
    if (KWebProfileDataKind.HTTP_CACHE in filter.kinds &&
        (filter.origin != null || range.sinceEpochMillis != null || range.untilEpochMillis != null)
    ) {
        throw KWebConfigurationException(
            code = "profile.data-time-range-unsupported",
            details = mapOf("kind" to KWebProfileDataKind.HTTP_CACHE.name),
            message = "HTTP cache clearing is profile-wide and does not support origin or time filters.",
        )
    }
    val originKinds = filter.kinds - KWebProfileDataKind.COOKIES -
        KWebProfileDataKind.HTTP_CACHE
    if (originKinds.isNotEmpty() && filter.origin == null) {
        throw KWebConfigurationException(
            code = "profile.data-origin-invalid",
            details = mapOf("kinds" to originKinds.joinToString(",")),
            message = "Origin-scoped Profile data kinds require an exact origin.",
        )
    }
    if (originKinds.isNotEmpty() &&
        (range.sinceEpochMillis != null || range.untilEpochMillis != null)
    ) {
        throw KWebConfigurationException(
            code = "profile.data-time-range-unsupported",
            details = mapOf("kinds" to originKinds.joinToString(",")),
            message = "Chromium origin storage clearing does not support time ranges.",
        )
    }
    return filter.origin?.let(::validateOrigin)
}

private fun validateSpellcheck(configuration: KWebSpellcheckConfiguration) {
    if (configuration.enabled && configuration.languages.isEmpty()) {
        throw KWebConfigurationException(
            code = "profile.spellcheck-invalid",
            details = emptyMap(),
            message = "Enabled spellcheck requires at least one language.",
        )
    }
    val languagePattern = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{2,8})*")
    if (configuration.languages.any { !languagePattern.matches(it) } ||
        configuration.languages.toSet().size != configuration.languages.size
    ) {
        throw KWebConfigurationException(
            code = "profile.spellcheck-invalid",
            details = emptyMap(),
            message = "Spellcheck languages must be unique BCP-47-like tags.",
        )
    }
}

private fun validateOrigin(value: String): String {
    val uri = try {
        URI(value)
    } catch (error: Throwable) {
        throw KWebConfigurationException(
            code = "profile.data-origin-invalid",
            details = mapOf("origin" to value),
            message = "The Profile data origin is not a valid URI.",
            cause = error,
        )
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host?.lowercase()
    if (!uri.isAbsolute || scheme !in setOf("http", "https") ||
        host.isNullOrBlank() || uri.rawPath.isNotEmpty() || uri.rawQuery != null ||
        uri.rawFragment != null || uri.userInfo != null
    ) {
        throw KWebConfigurationException(
            code = "profile.data-origin-invalid",
            details = mapOf("origin" to value),
            message = "Profile data origins must be canonical http or https origins without paths or credentials.",
        )
    }
    val defaultPort = if (scheme == "http") 80 else 443
    val port = if (uri.port == -1) defaultPort else uri.port
    if (port !in 1..65535) {
        throw KWebConfigurationException(
            code = "profile.data-origin-invalid",
            details = mapOf("origin" to value),
            message = "The Profile data origin port is invalid.",
        )
    }
    val portPart = if (port == defaultPort) "" else ":$port"
    return "$scheme://$host$portPart"
}
