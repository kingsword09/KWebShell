package io.github.kingsword09.kwebshell.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadDecision
import io.github.kingsword09.kwebshell.core.KWebBeforeUnloadResult
import io.github.kingsword09.kwebshell.core.KWebPopupDecision
import io.github.kingsword09.kwebshell.core.KWebPopupResult
import io.github.kingsword09.kwebshell.core.KWebReloadMode
import io.github.kingsword09.kwebshell.core.KWebReloadOutcome
import io.github.kingsword09.kwebshell.core.KWebReloadResult
import io.github.kingsword09.kwebshell.core.KWebPageHost
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebSecurityChallenge
import io.github.kingsword09.kwebshell.core.KWebSecurityChallengeResult
import io.github.kingsword09.kwebshell.core.KWebSecurityDecision
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebCookie
import io.github.kingsword09.kwebshell.core.KWebCookieFilter
import io.github.kingsword09.kwebshell.core.KWebCookieMutationResult
import io.github.kingsword09.kwebshell.core.KWebCookieSpec
import io.github.kingsword09.kwebshell.core.KWebProfileDataClearResult
import io.github.kingsword09.kwebshell.core.KWebProfileDataFilter
import io.github.kingsword09.kwebshell.core.KWebProfileFlushResult
import io.github.kingsword09.kwebshell.core.KWebNetworkPolicy
import io.github.kingsword09.kwebshell.core.KWebNetworkRequestEvent
import io.github.kingsword09.kwebshell.core.KWebSpellcheckConfiguration
import io.github.kingsword09.kwebshell.core.KWebSpellcheckState
import io.github.kingsword09.kwebshell.core.KWebStorageUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KWebViewPlacementTest {
    @Test
    fun axisAlignedGeometryConvertsToNativePixels() {
        val rect = probe().toNativeRect()

        assertEquals(KWebRect(24, 32, 640, 480), rect)
    }

    @Test
    fun transformedGeometryFailsWithoutASecondRenderer() {
        val error = assertFailsWith<KWebException> {
            probe(topRight = Offset(1288f, 32f)).toNativeRect()
        }

        assertEquals("compose.placement.transform-unsupported", error.code)
    }

    @Test
    fun clippedGeometryFailsWithAnActionablePlacementError() {
        val error = assertFailsWith<KWebException> {
            probe(clipped = true).toNativeRect()
        }

        assertEquals("compose.placement.clip-unsupported", error.code)
    }

    @Test
    fun outOfWindowGeometryFailsBeforeNativePlacement() {
        val error = assertFailsWith<KWebException> {
            probe(
                topLeft = Offset(700f, 32f),
                topRight = Offset(1340f, 32f),
                bottomLeft = Offset(700f, 512f),
                windowWidth = 1000,
            ).toNativeRect()
        }

        assertEquals("compose.placement.out-of-window", error.code)
        assertEquals("700", error.details["x"])
    }

    @Test
    fun controllerKeepsExternalPageOpenWhenClosed() {
        val page = RecordingPage()
        val controller = KWebViewController(page, KWebViewPageOwnership.EXTERNAL)

        controller.close()

        assertEquals(0, page.closeCalls)
        assertNull(controller.placementError.value)
    }

    @Test
    fun controllerClosesComponentOwnedPage() {
        val page = RecordingPage()
        val controller = KWebViewController(page, KWebViewPageOwnership.COMPONENT)

        controller.close()

        assertEquals(1, page.closeCalls)
    }

    private fun probe(
        topLeft: Offset = Offset(24f, 32f),
        topRight: Offset = Offset(664f, 32f),
        bottomLeft: Offset = Offset(24f, 512f),
        clipped: Boolean = false,
        windowWidth: Int = 1280,
        windowHeight: Int = 720,
    ): KWebViewGeometryProbe = KWebViewGeometryProbe(
        topLeft = topLeft,
        topRight = topRight,
        bottomLeft = bottomLeft,
        size = IntSize(640, 480),
        clipped = clipped,
        windowWidth = windowWidth,
        windowHeight = windowHeight,
    )
}

private class RecordingPage : KWebPage {
    override val id: String = "recording-page"
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    var closeCalls: Int = 0
    var navigateCalls: Int = 0
    var setBoundsCalls: Int = 0
    var surfaceStateCalls: Int = 0
    var openDevToolsCalls: Int = 0
    var closeDevToolsCalls: Int = 0

    override val lifecycle = mutableLifecycle
    override val events: Flow<KWebPageEvent> = emptyFlow()
    override val profile: KWebProfile = RecordingProfile()

    override suspend fun navigate(url: String) {
        require(url.isNotBlank())
        navigateCalls += 1
    }

    override suspend fun reload(mode: KWebReloadMode): KWebReloadResult =
        KWebReloadResult(mode, KWebReloadOutcome.STARTED)

    override suspend fun respondToBeforeUnload(
        requestId: Long,
        decision: KWebBeforeUnloadDecision,
    ): KWebBeforeUnloadResult = KWebBeforeUnloadResult(requestId, decision, timedOut = false)

    override suspend fun respondToPopup(
        requestId: Long,
        decision: KWebPopupDecision,
    ): KWebPopupResult = KWebPopupResult(requestId, io.github.kingsword09.kwebshell.core.KWebPopupOutcome.DENIED)

    override suspend fun setBounds(bounds: KWebRect) {
        setBoundsCalls += 1
    }

    override suspend fun setSurfaceState(visible: Boolean, focused: Boolean) {
        surfaceStateCalls += 1
    }

    override suspend fun openDevTools() {
        openDevToolsCalls += 1
    }

    override suspend fun closeDevTools() {
        closeDevToolsCalls += 1
    }

    override fun close() {
        closeCalls += 1
        mutableLifecycle.value = KWebLifecycleState.CLOSED
    }
}

private class RecordingProfile : KWebProfile {
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)

    override val name: String = "test"
    override val lifecycle = mutableLifecycle
    override val networkEvents = emptyFlow<KWebNetworkRequestEvent>()
    override val securityChallenges = emptyFlow<KWebSecurityChallenge>()

    override suspend fun openPage(host: KWebPageHost, initialUrl: String, bounds: KWebRect): KWebPage =
        RecordingPage()

    override suspend fun listCookies(
        target: KWebPage,
        filter: KWebCookieFilter,
    ): List<KWebCookie> = emptyList()

    override suspend fun setCookie(
        target: KWebPage,
        cookie: KWebCookieSpec,
    ): KWebCookieMutationResult = KWebCookieMutationResult(affected = 1)

    override suspend fun deleteCookies(
        target: KWebPage,
        filter: KWebCookieFilter,
    ): KWebCookieMutationResult = KWebCookieMutationResult(affected = 0)

    override suspend fun clearData(
        target: KWebPage,
        filter: KWebProfileDataFilter,
    ): KWebProfileDataClearResult = KWebProfileDataClearResult(
        requestedKinds = filter.kinds,
        clearedKinds = filter.kinds,
        origin = filter.origin,
        removedCookies = 0,
        startedEpochMillis = 0,
        completedEpochMillis = 0,
    )

    override suspend fun storageUsage(
        target: KWebPage,
        origin: String,
    ): KWebStorageUsage = KWebStorageUsage(
        origin = origin,
        usageBytes = 0,
        quotaBytes = 0,
        breakdown = emptyList(),
    )

    override suspend fun configureSpellcheck(
        target: KWebPage,
        configuration: KWebSpellcheckConfiguration,
    ): KWebSpellcheckState = KWebSpellcheckState(
        enabled = configuration.enabled,
        languages = configuration.languages,
    )

    override suspend fun configureNetworkPolicy(policy: KWebNetworkPolicy) = Unit

    override suspend fun respondToSecurityChallenge(
        requestId: Long,
        decision: KWebSecurityDecision,
    ): KWebSecurityChallengeResult = throw KWebNativeException(
        code = "security.challenge.not-found",
        details = mapOf("requestId" to requestId.toString()),
        message = "The recording Profile has no live security challenge.",
    )

    override suspend fun flush(target: KWebPage): KWebProfileFlushResult =
        KWebProfileFlushResult(completedEpochMillis = 0)

    override fun close() {
        mutableLifecycle.value = KWebLifecycleState.CLOSED
    }
}
