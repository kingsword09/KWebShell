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
import io.github.kingsword09.kwebshell.core.KWebPdfDocument
import io.github.kingsword09.kwebshell.core.KWebPdfErrorCode
import io.github.kingsword09.kwebshell.core.KWebPdfExportException
import io.github.kingsword09.kwebshell.core.KWebPdfExportRequest
import io.github.kingsword09.kwebshell.core.KWebPdfMargins
import io.github.kingsword09.kwebshell.core.KWebPdfReadResult
import io.github.kingsword09.kwebshell.core.KWEB_PDF_MAXIMUM_READ_BYTES
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
import java.util.concurrent.atomic.AtomicBoolean
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

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
    private val pdfMutex = Mutex()
    private val mutableActiveFind = MutableStateFlow<KWebFindSession?>(null)
    private val mutableFindResults = MutableSharedFlow<KWebFindResult>(
        replay = 0,
        extraBufferCapacity = FIND_RESULT_BUFFER,
    )
    private val pendingFinal = ConcurrentHashMap<Long, CompletableDeferred<KWebFindResult>>()
    private var nextSessionId = 0L
    private var nextPdfRequestId = 0L

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

    override suspend fun exportPdf(request: KWebPdfExportRequest): KWebPdfDocument =
        withContext(Dispatchers.IO) {
            pdfMutex.withLock { exportPdfLocked(request) }
        }

    private suspend fun exportPdfLocked(request: KWebPdfExportRequest): KWebPdfDocument {
        val directory = native.profileDirectory.resolve(PDF_EXPORT_DIRECTORY)
        try {
            Files.createDirectories(directory)
        } catch (error: Throwable) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.PATH_INVALID,
                details = mapOf("directory" to PDF_EXPORT_DIRECTORY),
                message = "The Profile PDF export directory could not be created.",
                cause = error,
            )
        }
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.PATH_INVALID,
                details = mapOf("directory" to PDF_EXPORT_DIRECTORY),
                message = "The Profile PDF export directory must be a real directory.",
            )
        }
        val name = "pdf-${java.util.UUID.randomUUID()}.pdf"
        val target = directory.resolve(name)
        val requestId = ++nextPdfRequestId
        try {
            val result = native.exportPdf(requestId, target.toString(), pdfSettingsPayload(request))
            if (!result.succeeded) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.NATIVE_FAILED,
                    details = mapOf(
                        "failure" to result.failure.ifEmpty { "unknown" },
                        "requestId" to requestId.toString(),
                    ),
                    message = "Chromium did not produce the requested PDF export.",
                )
            }
            val size = try {
                Files.size(target)
            } catch (error: Throwable) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("name" to name),
                    message = "The exported PDF artifact is missing.",
                    cause = error,
                )
            }
            if (size <= 0L) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("name" to name, "sizeBytes" to size.toString()),
                    message = "The exported PDF artifact is empty.",
                )
            }
            if (size > KWebPdfDocument.MAXIMUM_SIZE_BYTES) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_TOO_LARGE,
                    details = mapOf("name" to name, "sizeBytes" to size.toString()),
                    message = "The exported PDF exceeds the declared artifact size.",
                )
            }
            if (!hasPdfHeader(target)) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("name" to name),
                    message = "The exported artifact is not a PDF document.",
                )
            }
            return KWebDesktopPdfDocument(
                name = name,
                path = target,
                sizeBytes = size,
                sha256 = sha256Of(target),
            )
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(target) }
            throw error
        }
    }

    private fun pdfSettingsPayload(request: KWebPdfExportRequest): String =
        buildJsonObject {
            put("version", 1)
            put("paperWidthInches", request.paper.widthInches)
            put("paperHeightInches", request.paper.heightInches)
            put("landscape", request.landscape)
            put("printBackground", request.printBackground)
            put("scale", request.scale)
            put(
                "marginType",
                when (request.margins) {
                    KWebPdfMargins.DEFAULT -> 0
                    KWebPdfMargins.NONE -> 1
                    is KWebPdfMargins.Custom -> 2
                },
            )
            val margins = request.margins
            if (margins is KWebPdfMargins.Custom) {
                put("marginTopInches", margins.topInches)
                put("marginRightInches", margins.rightInches)
                put("marginBottomInches", margins.bottomInches)
                put("marginLeftInches", margins.leftInches)
            }
            put("displayHeaderFooter", request.displayHeaderFooter)
            put("headerTemplate", request.headerTemplate)
            put("footerTemplate", request.footerTemplate)
            put("pageRanges", request.pageRanges)
            put("preferCssPageSize", request.preferCssPageSize)
        }.toString()

    private fun hasPdfHeader(path: Path): Boolean {
        val header = ByteArray(5)
        val read = Files.newInputStream(path).use { stream ->
            var total = 0
            while (total < header.size) {
                val count = stream.read(header, total, header.size - total)
                if (count < 0) break
                total += count
            }
            total
        }
        return read == header.size &&
            header.decodeToString() == PDF_HEADER
    }

    private fun sha256Of(path: Path): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
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
        const val PDF_EXPORT_DIRECTORY = "pdf-exports"
        const val PDF_HEADER = "%PDF-"
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

/**
 * The engine-owned PDF artifact published by RFC 0026.
 *
 * The file lives inside the Profile's PDF export directory; bounded reads are
 * the only access, and closing the document removes the artifact.
 */
internal class KWebDesktopPdfDocument(
    override val name: String,
    private val path: Path,
    override val sizeBytes: Long,
    override val sha256: String,
) : KWebPdfDocument {
    private val closed = AtomicBoolean(false)

    init {
        if (!NAME_PATTERN.matches(name) || !name.endsWith(".pdf")) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.ARTIFACT_INVALID,
                details = mapOf("name" to name),
                message = "A PDF artifact name must be a safe .pdf file name.",
            )
        }
        if (sizeBytes <= 0L || sizeBytes > KWebPdfDocument.MAXIMUM_SIZE_BYTES) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.ARTIFACT_INVALID,
                details = mapOf("sizeBytes" to sizeBytes.toString()),
                message = "A PDF artifact size must be positive and bounded.",
            )
        }
        if (!SHA256_PATTERN.matches(sha256)) {
            throw KWebPdfExportException(
                code = KWebPdfErrorCode.ARTIFACT_INVALID,
                details = mapOf("field" to "sha256"),
                message = "A PDF artifact digest must be a lower-case SHA-256 value.",
            )
        }
    }

    override suspend fun read(offset: Long, length: Int): KWebPdfReadResult =
        withContext(Dispatchers.IO) {
            if (closed.get()) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("name" to name, "reason" to "closed"),
                    message = "A closed PDF artifact cannot be read.",
                )
            }
            if (offset < 0L || offset >= sizeBytes) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("offset" to offset.toString(), "sizeBytes" to sizeBytes.toString()),
                    message = "A PDF artifact read offset must be inside the artifact.",
                )
            }
            if (length <= 0 || length > KWEB_PDF_MAXIMUM_READ_BYTES) {
                throw KWebPdfExportException(
                    code = KWebPdfErrorCode.ARTIFACT_INVALID,
                    details = mapOf("length" to length.toString()),
                    message = "A PDF artifact read must be bounded by $KWEB_PDF_MAXIMUM_READ_BYTES bytes.",
                )
            }
            val available = minOf(length.toLong(), sizeBytes - offset).toInt()
            val bytes = java.io.RandomAccessFile(path.toFile(), "r").use { file ->
                file.seek(offset)
                ByteArray(available).also(file::readFully)
            }
            KWebPdfReadResult(bytes = bytes, eof = offset + available >= sizeBytes)
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { Files.deleteIfExists(path) }
        }
    }

    private companion object {
        val NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val SHA256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
