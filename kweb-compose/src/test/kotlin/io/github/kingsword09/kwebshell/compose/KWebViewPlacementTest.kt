package io.github.kingsword09.kwebshell.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebPageEvent
import io.github.kingsword09.kwebshell.core.KWebPageHost
import io.github.kingsword09.kwebshell.core.KWebProfile
import io.github.kingsword09.kwebshell.core.KWebRect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    override suspend fun openPage(host: KWebPageHost, initialUrl: String, bounds: KWebRect): KWebPage =
        RecordingPage()

    override fun close() {
        mutableLifecycle.value = KWebLifecycleState.CLOSED
    }
}
