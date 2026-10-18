package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebFindDirection
import io.github.kingsword09.kwebshell.core.KWebFindRequest
import io.github.kingsword09.kwebshell.core.KWebFindResult
import io.github.kingsword09.kwebshell.core.KWebFindSelection
import io.github.kingsword09.kwebshell.core.KWebFindSession
import io.github.kingsword09.kwebshell.core.KWebFindStopOutcome
import io.github.kingsword09.kwebshell.core.KWebFindStopResult
import io.github.kingsword09.kwebshell.core.KWebPageUtilityErrorCode
import io.github.kingsword09.kwebshell.core.KWebPageUtilityException
import io.github.kingsword09.kwebshell.core.KWebPageUtilities
import io.github.kingsword09.kwebshell.core.KWebZoomState
import io.github.kingsword09.kwebshell.core.kWebZoomFactorForLevel
import io.github.kingsword09.kwebshell.core.kWebZoomLevelForFactor
import io.github.kingsword09.kwebshell.core.kWebValidatedZoomLevel
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowser
import io.github.kingsword09.kwebshell.desktop.internal.NativeBrowserEvent
import io.github.kingsword09.kwebshell.desktop.internal.NativeFindDirection
import io.github.kingsword09.kwebshell.desktop.internal.NativeStatus
import io.github.kingsword09.kwebshell.desktop.internal.pageUtilityStatusException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * RFC 0044 page utilities over the native child engine.
 *
 * One find session is active per page. The session identifier is page-owned and
 * monotonic; the native find handler echoes it, so an update that belongs to a
 * replaced session is never attributed to the replacement. Zoom is applied and
 * read back through the engine instead of echoing the requested value.
 */
internal class KWebDesktopPageUtilities(
    private val native: NativeBrowser,
) : KWebPageUtilities {
    private val mutex = Mutex()
    private val mutableActiveFind = MutableStateFlow<KWebFindSession?>(null)
    private val mutableFindResults = MutableSharedFlow<KWebFindResult>(
        replay = 0,
        extraBufferCapacity = FIND_RESULT_BUFFER,
    )
    private val pendingFinal = ConcurrentHashMap<Long, CompletableDeferred<KWebFindResult>>()
    private var nextSessionId = 0L

    /** Written on the browser callback thread, read by `stopFinding`. */
    @Volatile
    private var lastMatches = 0

    override val activeFind: StateFlow<KWebFindSession?> = mutableActiveFind.asStateFlow()
    override val findResults: Flow<KWebFindResult> = mutableFindResults.asSharedFlow()

    override suspend fun find(request: KWebFindRequest): KWebFindResult =
        withContext(Dispatchers.IO) {
            mutex.withLock { startSessionLocked(request) }
        }

    override suspend fun findNext(): KWebFindResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val active = mutableActiveFind.value ?: throw staleSessionError(null)
            continueSessionLocked(active)
        }
    }

    override suspend fun stopFinding(keepSelection: Boolean): KWebFindStopResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = mutableActiveFind.value
                    ?: return@withLock KWebFindStopResult(
                        sessionId = null,
                        outcome = KWebFindStopOutcome.NO_ACTIVE_SESSION,
                        matches = 0,
                    )
                val status = native.stopFinding(keepSelection)
                if (status != NativeStatus.OK.value) {
                    throw pageUtilityStatusException(
                        "stop-finding",
                        status,
                        mapOf("sessionId" to active.id.toString()),
                    )
                }
                mutableActiveFind.value = null
                val abandoned = lastMatches
                lastMatches = 0
                pendingFinal.remove(active.id)
                KWebFindStopResult(active.id, KWebFindStopOutcome.STOPPED, abandoned)
            }
        }

    override suspend fun zoom(): KWebZoomState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = native.zoomLevel()
            zoomState(result.status, result.level, "zoom-level")
        }
    }

    override suspend fun setZoomLevel(level: Double): KWebZoomState = withContext(Dispatchers.IO) {
        val validated = kWebValidatedZoomLevel(level)
        mutex.withLock { applyZoomLocked(validated) }
    }

    override suspend fun setZoomFactor(factor: Double): KWebZoomState =
        withContext(Dispatchers.IO) {
            val level = kWebZoomLevelForFactor(factor)
            mutex.withLock { applyZoomLocked(level) }
        }

    /** Called for every native find update while the page is live. */
    internal fun onFindResult(event: NativeBrowserEvent) {
        val sessionId = event.requestId
        val active = mutableActiveFind.value
        if (active == null || active.id != sessionId) {
            // A result for a replaced or stopped session is not this session's
            // result and must never be attributed to the current one.
            return
        }
        val update = try {
            KWebDesktopFindJson.parse(event)
        } catch (error: Throwable) {
            pendingFinal.remove(sessionId)?.completeExceptionally(error)
            throw error
        }
        lastMatches = update.matches
        if (!mutableFindResults.tryEmit(update)) {
            val error = KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
                details = mapOf("sessionId" to sessionId.toString()),
                message = "The find result stream has no capacity for the ordered update.",
            )
            pendingFinal.remove(sessionId)?.completeExceptionally(error)
            throw error
        }
        if (update.finalUpdate) {
            pendingFinal.remove(sessionId)?.complete(update)
        }
    }

    /** Resolves an in-flight find call when its page reached a terminal state. */
    internal fun onPageClosed(error: KWebPageUtilityException) {
        val active = mutableActiveFind.value
        mutableActiveFind.value = null
        active?.let { pendingFinal.remove(it.id)?.completeExceptionally(error) }
        pendingFinal.clear()
    }

    private suspend fun startSessionLocked(request: KWebFindRequest): KWebFindResult {
        val sessionId = ++nextSessionId
        val session = KWebFindSession(sessionId, request)
        return dispatchFindLocked(session, continuous = false)
    }

    private suspend fun continueSessionLocked(active: KWebFindSession): KWebFindResult =
        dispatchFindLocked(active, continuous = true)

    private suspend fun dispatchFindLocked(
        session: KWebFindSession,
        continuous: Boolean,
    ): KWebFindResult {
        val waiter = CompletableDeferred<KWebFindResult>()
        pendingFinal[session.id] = waiter
        mutableActiveFind.value = session
        lastMatches = 0
        val status = native.find(
            sessionId = session.id,
            text = session.request.text,
            direction = when (session.request.direction) {
                KWebFindDirection.FORWARD -> NativeFindDirection.FORWARD
                KWebFindDirection.BACKWARD -> NativeFindDirection.BACKWARD
            },
            matchCase = session.request.matchCase,
            continuous = continuous,
        )
        if (status != NativeStatus.OK.value) {
            pendingFinal.remove(session.id)
            if (!continuous) {
                mutableActiveFind.value = null
            }
            throw pageUtilityStatusException(
                if (continuous) "find-next" else "find",
                status,
                mapOf("sessionId" to session.id.toString()),
            )
        }
        val result = withTimeoutOrNull(FIND_TIMEOUT_MILLIS) { waiter.await() }
            ?: run {
                pendingFinal.remove(session.id)
                throw KWebPageUtilityException(
                    code = KWebPageUtilityErrorCode.FIND_TIMEOUT,
                    details = mapOf("sessionId" to session.id.toString()),
                    message = "Chromium did not finish the find request in time.",
                )
            }
        pendingFinal.remove(session.id)
        return result
    }

    private fun applyZoomLocked(level: Double): KWebZoomState {
        val result = native.setZoomLevel(level)
        return zoomState(result.status, result.level, "set-zoom-level")
    }

    private fun zoomState(status: Int, observed: Double, operation: String): KWebZoomState {
        if (status != NativeStatus.OK.value) {
            throw pageUtilityStatusException(operation, status)
        }
        return KWebZoomState(level = observed, factor = kWebZoomFactorForLevel(observed))
    }

    private fun staleSessionError(sessionId: Long?): KWebPageUtilityException =
        KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.FIND_SESSION_STALE,
            details = buildMap {
                sessionId?.let { put("sessionId", it.toString()) }
            },
            message = "The page has no active find session to continue.",
        )

    private companion object {
        const val FIND_RESULT_BUFFER = 64
        const val FIND_TIMEOUT_MILLIS = 5_000L
    }
}

/** Parses the bounded find-result payload the engine publishes. */
internal object KWebDesktopFindJson {
    private val json = Json {
        explicitNulls = false
        ignoreUnknownKeys = false
        isLenient = false
    }

    internal fun parse(event: NativeBrowserEvent): KWebFindResult {
        val root = try {
            json.parseToJsonElement(event.details).jsonObject
        } catch (error: IllegalArgumentException) {
            throw invalid("payload", error)
        }
        if (requiredInt(root, "version") != 1) invalid("version")
        val reportedOrdinal = requiredInt(root, "active")
        if (reportedOrdinal < 0) invalid("active")
        return KWebFindResult(
            sessionId = event.requestId,
            sequence = event.sequence,
            matches = requiredInt(root, "matches"),
            // Chromium reports 0 before it selects a match and 1-based positions
            // afterwards; the published contract is zero-based with null for
            // "no active match yet".
            activeMatchOrdinal = if (reportedOrdinal == 0) null else reportedOrdinal - 1,
            finalUpdate = requiredBoolean(root, "final"),
            selection = parseSelection(root),
        )
    }

    private fun parseSelection(root: JsonObject): KWebFindSelection {
        val element = root["selection"]
            ?: throw invalid("selection")
        val selection: JsonObject = try {
            element.jsonObject
        } catch (error: IllegalArgumentException) {
            throw invalid("selection", error)
        }
        return KWebFindSelection(
            x = requiredInt(selection, "x"),
            y = requiredInt(selection, "y"),
            width = requiredInt(selection, "width"),
            height = requiredInt(selection, "height"),
        )
    }

    private fun requiredInt(root: JsonObject, name: String): Int =
        root[name]?.jsonPrimitive?.intOrNull ?: throw invalid(name)

    private fun requiredBoolean(root: JsonObject, name: String): Boolean =
        root[name]?.jsonPrimitive?.booleanOrNull ?: throw invalid(name)

    private fun invalid(name: String, cause: Throwable? = null): Nothing =
        throw KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
            details = mapOf("field" to name),
            message = "The engine find result field '$name' is invalid.",
            cause = cause,
        )
}
