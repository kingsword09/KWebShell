package io.github.kingsword09.kwebshell.desktop

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebBounds
import io.github.kingsword09.kwebshell.core.KWebCapability
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebEngine
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageEventFlag
import io.github.kingsword09.kwebshell.core.KWebPageEventType
import io.github.kingsword09.kwebshell.core.KWebPageHost
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowser
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEventType
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngine
import io.github.kingsword09.kwebshell.desktop.internal.NativeEngineConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.BufferOverflow
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

public class KWebDesktopEngine private constructor(
    private val native: NativeEngine,
    private val configuration: KWebDesktopEngineConfiguration,
) : KWebEngine {
    private val lock = Any()
    private val profiles = linkedMapOf<Path, KWebDesktopProfile>()
    private val closed = AtomicBoolean(false)

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
    }

    override suspend fun openProfile(name: String): KWebProfile = withContext(Dispatchers.IO) {
        withEngineLock {
            requireEngineOpen("open-profile")
            val path = KWebProfilePathResolver.resolve(native.rootCachePath(), name)
            if (profiles.values.any { it.isSamePhysicalPath(path) }) {
                throw KWebConfigurationException(
                    code = "profile.duplicate-physical-identity",
                    details = mapOf("profile" to path.toString()),
                    message = "The requested Profile is already open through this engine.",
                )
            }
            val profile = KWebDesktopProfile(this@KWebDesktopEngine, path)
            profiles[path] = profile
            profile
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed.get() && lifecycle.value == KWebLifecycleState.CLOSED) {
                return
            }
            requireEngineOpen("close-engine")
            try {
                native.close()
            } catch (error: Throwable) {
                throw error
            }
            closed.set(true)
            profiles.values.toList().forEach { it.markClosedByEngine() }
            profiles.clear()
        }
    }

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
            val nativeConfiguration = NativeEngineConfiguration(
                cefRuntime = configuration.cefRuntime,
                browserSubprocess = configuration.browserSubprocess,
                resources = configuration.resources,
                locales = configuration.locales,
                rootCache = configuration.rootCache,
                log = configuration.log,
                remoteDebuggingPort = configuration.remoteDebuggingPort,
            )
            return KWebDesktopEngine(
                native = NativeEngine.open(nativeConfiguration),
                configuration = configuration,
            )
        }
    }
}

internal class KWebDesktopProfile(
    private val engine: KWebDesktopEngine,
    internal val path: Path,
) : KWebProfile {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val pages = linkedSetOf<KWebDesktopPage>()
    private var closedByEngine = false

    override val name: String = path.fileName.toString()
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun openPage(
        host: KWebPageHost,
        initialUrl: String,
        bounds: KWebBounds,
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
                val eventStream = KWebPageEventStream()
                val nativePage = NativeBrowser.open(
                    engine = engine.nativeEngine(),
                    nativeParent = nativeParent,
                    profilePath = path,
                    initialUrl = initialUrl,
                    width = bounds.width,
                    height = bounds.height,
                    listener = eventStream::accept,
                )
                val page = KWebDesktopPage(this@KWebDesktopProfile, nativePage, eventStream)
                pages += page
                page
            }
        }
    }

    override fun close() {
        engine.withEngineLock {
            synchronized(lock) {
                if (mutableLifecycle.value != KWebLifecycleState.CLOSED) {
                    if (pages.isNotEmpty()) {
                        throw KWebNativeException(
                            code = "desktop.profile.live-pages",
                            details = mapOf("profile" to path.toString(), "count" to pages.size.toString()),
                            message = "The Profile cannot close while pages are still live.",
                        )
                    }
                    mutableLifecycle.value = KWebLifecycleState.CLOSED
                    engine.removeProfile(this)
                }
            }
        }
    }

    internal fun removePage(page: KWebDesktopPage) {
        synchronized(lock) {
            pages.remove(page)
        }
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
        synchronized(lock) {
            closedByEngine = true
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }

    private fun requireOpen(operation: String) {
        if (closedByEngine || mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.profile.closed",
                details = mapOf("operation" to operation, "profile" to path.toString()),
                message = "The KWebShell Profile is not open.",
            )
        }
        engine.requireEngineOpen(operation)
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
) : KWebPage {
    private val closeLock = Any()

    override val lifecycle: StateFlow<KWebLifecycleState> = native.lifecycle
    override val events: Flow<KWebPageEvent> = eventStream.events
    override val profile: KWebProfile = owner

    override suspend fun navigate(url: String) {
        withContext(Dispatchers.IO) {
            requireOpen("navigate")
            native.navigate(url)
        }
    }

    override suspend fun resize(bounds: KWebBounds) {
        withContext(Dispatchers.IO) {
            requireOpen("resize")
            native.resize(bounds.width, bounds.height)
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
            if (lifecycle.value == KWebLifecycleState.CLOSED) return
            native.close()
            if (lifecycle.value == KWebLifecycleState.CLOSED) {
                owner.removePage(this)
            }
        }
    }

    private fun requireOpen(operation: String) {
        if (lifecycle.value != KWebLifecycleState.OPEN) {
            throw KWebNativeException(
                code = "desktop.page.closed",
                details = mapOf("operation" to operation),
                message = "The KWebShell page is not open.",
            )
        }
    }
}

internal class KWebPageEventStream {
    private val mutableEvents = MutableSharedFlow<KWebPageEvent>(
        replay = 128,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    internal val events: Flow<KWebPageEvent> = mutableEvents.asSharedFlow()

    internal fun accept(event: NativeBrowserEvent) {
        val publicEvent = event.toPublicEvent()
        if (!mutableEvents.tryEmit(publicEvent)) {
            throw KWebNativeException(
                code = "desktop.page.event-backpressure",
                details = mapOf("sequence" to event.sequence.toString()),
                message = "The page event stream has no capacity for the ordered native event.",
            )
        }
    }
}

private fun NativeBrowserEvent.toPublicEvent(): KWebPageEvent {
    val type = when (type) {
        NativeBrowserEventType.CREATED -> KWebPageEventType.CREATED
        NativeBrowserEventType.NAVIGATION_STARTED -> KWebPageEventType.NAVIGATION_STARTED
        NativeBrowserEventType.ADDRESS_CHANGED -> KWebPageEventType.ADDRESS_CHANGED
        NativeBrowserEventType.LOADING_STATE_CHANGED -> KWebPageEventType.LOADING_STATE_CHANGED
        NativeBrowserEventType.LOAD_ENDED -> KWebPageEventType.LOAD_ENDED
        NativeBrowserEventType.LOAD_FAILED -> KWebPageEventType.LOAD_FAILED
        NativeBrowserEventType.RESIZED -> KWebPageEventType.RESIZED
        NativeBrowserEventType.FATAL_ERROR -> KWebPageEventType.FATAL_ERROR
        NativeBrowserEventType.TITLE_CHANGED -> KWebPageEventType.TITLE_CHANGED
        NativeBrowserEventType.CLOSED -> KWebPageEventType.CLOSED
        NativeBrowserEventType.DEVTOOLS_OPENED -> KWebPageEventType.DEVTOOLS_OPENED
        NativeBrowserEventType.DEVTOOLS_CLOSED -> KWebPageEventType.DEVTOOLS_CLOSED
        NativeBrowserEventType.DEVTOOLS_FAILED -> KWebPageEventType.DEVTOOLS_FAILED
    }
    val flags = buildSet {
        if (this@toPublicEvent.flags and 1 != 0) add(KWebPageEventFlag.LOADING)
        if (this@toPublicEvent.flags and 2 != 0) add(KWebPageEventFlag.CAN_GO_BACK)
        if (this@toPublicEvent.flags and 4 != 0) add(KWebPageEventFlag.CAN_GO_FORWARD)
        if (this@toPublicEvent.flags and 8 != 0) add(KWebPageEventFlag.USER_GESTURE)
        if (this@toPublicEvent.flags and 16 != 0) add(KWebPageEventFlag.REDIRECT)
    }
    val eventBounds = if (width > 0 && height > 0) KWebBounds(width, height) else null
    return KWebPageEvent(type, sequence, text, statusCode, eventBounds, flags)
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
