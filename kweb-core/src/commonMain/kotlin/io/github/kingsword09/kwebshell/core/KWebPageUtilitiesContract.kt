/**
 * The RFC 0044 page utility contract: find sessions and page zoom.
 *
 * These utilities are backed by Chromium's own semantics through the native
 * child engine. Find is one owned session per page with ordered match updates;
 * zoom is a page-scoped level whose observed value the engine reads back from
 * Chromium instead of assuming the requested value was applied.
 */
package io.github.kingsword09.kwebshell.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.ln
import kotlin.math.pow

/** Which way a find session advances through the page. */
public enum class KWebFindDirection(public val id: String) {
    FORWARD("forward"),
    BACKWARD("backward"),
}

/**
 * One find request. The query is bounded to 512 UTF-8 bytes, must not be empty,
 * and must not contain NUL or lone surrogates. Any other text is a legitimate
 * search, including whitespace and control characters a page may contain.
 */
public data class KWebFindRequest(
    public val text: String,
    public val direction: KWebFindDirection = KWebFindDirection.FORWARD,
    public val matchCase: Boolean = false,
) {
    init {
        val bytes = text.encodeToByteArray().size
        if (text.isEmpty() || text.contains('\u0000') || bytes > MAXIMUM_QUERY_BYTES) {
            throw KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
                details = mapOf("field" to "text", "bytes" to bytes.toString()),
                message = "A find query must be 1..$MAXIMUM_QUERY_BYTES UTF-8 bytes without NUL.",
            )
        }
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (character.isHighSurrogate()) {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) {
                    throw KWebPageUtilityException(
                        code = KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
                        details = mapOf("field" to "text", "reason" to "unpaired-surrogate"),
                        message = "A find query must be valid Unicode text.",
                    )
                }
                index += 2
                continue
            }
            if (character.isLowSurrogate()) {
                throw KWebPageUtilityException(
                    code = KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
                    details = mapOf("field" to "text", "reason" to "unpaired-surrogate"),
                    message = "A find query must be valid Unicode text.",
                )
            }
            index += 1
        }
    }

    public companion object {
        public const val MAXIMUM_QUERY_BYTES: Int = 512
    }
}

/** One owned find session on a page. */
public data class KWebFindSession(
    public val id: Long,
    public val request: KWebFindRequest,
) {
    init {
        if (id <= 0L) {
            throw KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "id"),
                message = "A find session identifier must be positive.",
            )
        }
    }
}

/**
 * The match location Chromium reports for the active match, in
 * device-independent window coordinates. Chromium may report a location
 * outside the visible viewport, so no bounds rule is applied here.
 */
public data class KWebFindSelection(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
)

/** One ordered find update, exactly as the engine reported it. */
public data class KWebFindResult(
    public val sessionId: Long,
    public val sequence: Long,
    public val matches: Int,
    /**
     * The zero-based position of the active match, or null when Chromium has
     * not selected a match for this request (a fresh search reports its count
     * before it selects the first match).
     */
    public val activeMatchOrdinal: Int?,
    /** True for the last update of one find request. */
    public val finalUpdate: Boolean,
    public val selection: KWebFindSelection?,
) {
    init {
        if (sessionId <= 0L || sequence <= 0L) {
            throw KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "sessionIdOrSequence"),
                message = "A find result requires a positive session id and event sequence.",
            )
        }
        if (matches < 0 || (activeMatchOrdinal != null &&
                (activeMatchOrdinal < 0 || activeMatchOrdinal >= matches))
        ) {
            throw KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "matches"),
                message = "A find result must report a match count that contains its active ordinal.",
            )
        }
    }
}

/** How a find session ended. */
public enum class KWebFindStopOutcome(public val id: String) {
    STOPPED("stopped"),
    NO_ACTIVE_SESSION("no-active-session"),
}

public data class KWebFindStopResult(
    public val sessionId: Long?,
    public val outcome: KWebFindStopOutcome,
    /** The last match count reported for the stopped session. */
    public val matches: Int,
)

/**
 * The page zoom level and its derived factor.
 *
 * The factor is `1.2^level` evaluated in double precision, so it may differ
 * from a requested factor by floating-point rounding. Chromium's declared page
 * zoom factor range is [0.25, 5.0]; a level outside it is refused instead of
 * being clamped silently.
 */
public data class KWebZoomState(
    public val level: Double,
    public val factor: Double,
) {
    init {
        if (!level.isFinite() || !factor.isFinite() || factor < MINIMUM_FACTOR || factor > MAXIMUM_FACTOR) {
            throw KWebPageUtilityException(
                code = KWebPageUtilityErrorCode.CONTRACT_INVALID,
                details = mapOf("field" to "level", "level" to level.toString()),
                message = "A page zoom state must stay inside Chromium's zoom range.",
            )
        }
    }

    public companion object {
        public const val MINIMUM_FACTOR: Double = 0.25
        public const val MAXIMUM_FACTOR: Double = 5.0
        public const val FACTOR_STEP: Double = 1.2
        public val IDENTITY: KWebZoomState = KWebZoomState(level = 0.0, factor = 1.0)
    }
}

/**
 * The page's find, zoom, PDF, and content operations.
 *
 * Every call is page-scoped and serialized per page. A find call resolves with
 * the final update of its own request, while [findResults] publishes every
 * ordered update the engine reported, including the final one.
 */
public interface KWebPageUtilities {
    /** The newest accepted find session, or null when no session is active. */
    public val activeFind: StateFlow<KWebFindSession?>

    /** Every ordered find update for this page. */
    public val findResults: Flow<KWebFindResult>

    /** Starts a find session, replacing any previous one. */
    public suspend fun find(request: KWebFindRequest): KWebFindResult

    /** Advances the active session in its declared direction. */
    public suspend fun findNext(): KWebFindResult

    /** Ends the active session. */
    public suspend fun stopFinding(keepSelection: Boolean = true): KWebFindStopResult

    /** Reads the zoom level Chromium currently observes for this page. */
    public suspend fun zoom(): KWebZoomState

    /** Applies an absolute zoom level. */
    public suspend fun setZoomLevel(level: Double): KWebZoomState

    /** Applies an absolute zoom factor. */
    public suspend fun setZoomFactor(factor: Double): KWebZoomState

    /**
     * Reads the page's HTML source or its rendered text, bounded by the
     * request's declared byte limit. The read observes the live document: a
     * script mutation performed before the call is part of the result.
     */
    public suspend fun content(request: KWebPageContentRequest): KWebPageContent

    /**
     * Writes the current page as a PDF artifact. The artifact is engine-owned:
     * it lives inside the Profile directory and [KWebPdfDocument.close] removes it.
     */
    public suspend fun exportPdf(
        request: KWebPdfExportRequest = KWebPdfExportRequest(),
    ): KWebPdfDocument
}

/** Stable page utility error identifiers. */
public object KWebPageUtilityErrorCode {
    public const val CONTRACT_INVALID: String = "page.utility-contract-invalid"
    public const val FIND_QUERY_INVALID: String = "page.find-query-invalid"
    public const val FIND_SESSION_STALE: String = "page.find-session-stale"
    public const val FIND_TIMEOUT: String = "page.find-timeout"
    public const val ZOOM_LEVEL_INVALID: String = "page.zoom-level-invalid"
    public const val ZOOM_OUT_OF_RANGE: String = "page.zoom-out-of-range"
}

public class KWebPageUtilityException(
    code: String,
    details: Map<String, String>,
    message: String,
    cause: Throwable? = null,
) : KWebException(code, details, message, cause)

/**
 * Converts a zoom factor to the level that produces it. Values outside
 * Chromium's factor range are refused instead of being clamped.
 */
public fun kWebZoomLevelForFactor(factor: Double): Double {
    if (!factor.isFinite()) {
        throw KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.ZOOM_LEVEL_INVALID,
            details = mapOf("factor" to factor.toString()),
            message = "A page zoom factor must be a finite number.",
        )
    }
    if (factor < KWebZoomState.MINIMUM_FACTOR || factor > KWebZoomState.MAXIMUM_FACTOR) {
        throw KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.ZOOM_OUT_OF_RANGE,
            details = mapOf("factor" to factor.toString()),
            message = "A page zoom factor must stay inside Chromium's declared range.",
        )
    }
    return ln(factor) / ln(KWebZoomState.FACTOR_STEP)
}

/** Converts a zoom level to its factor in double precision. */
public fun kWebZoomFactorForLevel(level: Double): Double =
    KWebZoomState.FACTOR_STEP.pow(level)

/**
 * Validates an absolute zoom level: it must be finite and its factor must stay
 * inside Chromium's declared range. Out-of-range input is refused instead of
 * being clamped by the engine.
 */
public fun kWebValidatedZoomLevel(level: Double): Double {
    if (!level.isFinite()) {
        throw KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.ZOOM_LEVEL_INVALID,
            details = mapOf("level" to level.toString()),
            message = "A page zoom level must be a finite number.",
        )
    }
    val factor = kWebZoomFactorForLevel(level)
    if (factor < KWebZoomState.MINIMUM_FACTOR || factor > KWebZoomState.MAXIMUM_FACTOR) {
        throw KWebPageUtilityException(
            code = KWebPageUtilityErrorCode.ZOOM_OUT_OF_RANGE,
            details = mapOf("level" to level.toString()),
            message = "A page zoom level must stay inside Chromium's declared range.",
        )
    }
    return level
}
