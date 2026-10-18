package io.github.kingsword09.kwebshell.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The published RFC 0044 page utility contract. */
class KWebPageUtilitiesContractTest {

    @Test
    fun aFindQueryIsBoundedToItsDeclaredByteCeiling() {
        KWebFindRequest("a")
        KWebFindRequest("a".repeat(KWebFindRequest.MAXIMUM_QUERY_BYTES))
        val oversized = assertFailsWith<KWebPageUtilityException> {
            KWebFindRequest("a".repeat(KWebFindRequest.MAXIMUM_QUERY_BYTES + 1))
        }
        assertEquals(KWebPageUtilityErrorCode.FIND_QUERY_INVALID, oversized.code)
    }

    @Test
    fun aFindQueryRejectsEmptyNulAndLoneSurrogates() {
        assertEquals(
            KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
            assertFailsWith<KWebPageUtilityException> { KWebFindRequest("") }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
            assertFailsWith<KWebPageUtilityException> { KWebFindRequest("a\u0000b") }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
            assertFailsWith<KWebPageUtilityException> { KWebFindRequest("\uD800") }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.FIND_QUERY_INVALID,
            assertFailsWith<KWebPageUtilityException> { KWebFindRequest("a\uDC00b") }.code,
        )
        // A legitimate emoji pair is a single scalar value and stays accepted.
        KWebFindRequest("\uD83D\uDE42")
    }

    @Test
    fun aFindResultRejectsAnOrdinalOutsideItsMatchCount() {
        assertEquals(
            KWebPageUtilityErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageUtilityException> {
                KWebFindResult(
                    sessionId = 1,
                    sequence = 1,
                    matches = 2,
                    activeMatchOrdinal = 3,
                    finalUpdate = true,
                    selection = null,
                )
            }.code,
        )
        KWebFindResult(1, 1, 2, 1, true, KWebFindSelection(0, 0, 10, 10))
    }

    @Test
    fun aFindSessionRequiresAPositiveIdentifier() {
        assertEquals(
            KWebPageUtilityErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageUtilityException> {
                KWebFindSession(0, KWebFindRequest("text"))
            }.code,
        )
    }

    @Test
    fun theZoomConversionsMatchChromiumsDeclaredRange() {
        assertEquals(0.0, kWebZoomLevelForFactor(1.0), 1e-12)
        assertEquals(1.0, kWebZoomFactorForLevel(0.0), 1e-12)
        val state = KWebZoomState(level = 0.0, factor = 1.0)
        assertEquals(KWebZoomState.IDENTITY, state)
        assertTrue(kWebZoomFactorForLevel(kWebZoomLevelForFactor(2.0)) > 1.999999999)
        assertTrue(kWebZoomFactorForLevel(kWebZoomLevelForFactor(2.0)) < 2.000000001)
    }

    @Test
    fun zoomInputsOutsideChromiumsRangeAreRefusedInsteadOfClamped() {
        assertEquals(
            KWebPageUtilityErrorCode.ZOOM_OUT_OF_RANGE,
            assertFailsWith<KWebPageUtilityException> { kWebZoomLevelForFactor(5.0001) }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.ZOOM_OUT_OF_RANGE,
            assertFailsWith<KWebPageUtilityException> { kWebZoomLevelForFactor(0.249) }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.ZOOM_LEVEL_INVALID,
            assertFailsWith<KWebPageUtilityException> { kWebZoomLevelForFactor(Double.NaN) }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageUtilityException> {
                KWebZoomState(level = Double.NaN, factor = 1.0)
            }.code,
        )
        assertEquals(
            KWebPageUtilityErrorCode.CONTRACT_INVALID,
            assertFailsWith<KWebPageUtilityException> {
                KWebZoomState(level = 0.0, factor = 9.0)
            }.code,
        )
    }

    @Test
    fun aFindStopWithoutSessionNamesNoIdentifier() {
        val result = KWebFindStopResult(
            sessionId = null,
            outcome = KWebFindStopOutcome.NO_ACTIVE_SESSION,
            matches = 0,
        )
        assertEquals(KWebFindStopOutcome.NO_ACTIVE_SESSION, result.outcome)
        assertEquals(null, result.sessionId)
        assertEquals(KWebFindStopOutcome.STOPPED.id, "stopped")
        assertEquals(KWebFindDirection.BACKWARD.id, "backward")
    }
}
