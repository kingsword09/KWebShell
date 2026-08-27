package io.github.kingsword09.kwebshell.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.window.WindowScope
import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebRect
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.Color
import java.awt.Dimension
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import java.awt.event.WindowStateListener
import javax.swing.JPanel
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Attaches one existing [controller] page to the current Compose Desktop window.
 *
 * The component uses a transparent SwingPanel only as a layout anchor. Chromium
 * remains the page's direct native child and is never replaced by OSR or a
 * second window. The controller's ownership mode decides whether the page is
 * closed when the component leaves the composition.
 */
@Composable
public fun WindowScope.KWebView(
    controller: KWebViewController,
    modifier: Modifier = Modifier,
) {
    val window = requireComposeWindow(this.window)
    val dispatcher = remember(controller, window) {
        KWebViewPlacementDispatcher(controller, window)
    }
    val anchor = remember(dispatcher) { KWebViewAnchor(dispatcher) }

    DisposableEffect(controller, window, dispatcher) {
        controller.attach(dispatcher)
        val componentListener = object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                dispatcher.refreshWindowState()
            }

            override fun componentMoved(event: ComponentEvent) {
                dispatcher.refreshWindowState()
            }

            override fun componentShown(event: ComponentEvent) {
                dispatcher.refreshWindowState()
            }

            override fun componentHidden(event: ComponentEvent) {
                dispatcher.refreshWindowState()
            }
        }
        val focusListener = object : WindowFocusListener {
            override fun windowGainedFocus(event: WindowEvent) {
                dispatcher.refreshWindowState()
            }

            override fun windowLostFocus(event: WindowEvent) {
                dispatcher.refreshWindowState()
            }
        }
        val stateListener = WindowStateListener {
            dispatcher.refreshWindowState()
        }
        window.addComponentListener(componentListener)
        window.addWindowFocusListener(focusListener)
        window.addWindowStateListener(stateListener)
        onDispose {
            window.removeComponentListener(componentListener)
            window.removeWindowFocusListener(focusListener)
            window.removeWindowStateListener(stateListener)
            controller.compositionDisposed()
            dispatcher.close()
            controller.detach(dispatcher)
        }
    }

    SwingPanel(
        factory = { anchor.createPanel() },
        modifier = modifier.onGloballyPositioned { coordinates ->
            dispatcher.submitGeometry(coordinates)
        },
        update = { anchor.refresh() },
    )
}

/** Creates a stable controller wrapper for an explicitly owned page. */
@Composable
public fun rememberKWebViewController(
    page: io.github.kingsword09.kwebshell.core.KWebPage,
    ownership: KWebViewPageOwnership,
): KWebViewController = remember(page, ownership) {
    KWebViewController(page, ownership)
}

private fun requireComposeWindow(window: Window?): ComposeWindow {
    val actual = window ?: throw KWebConfigurationException(
        code = "compose.window.missing",
        details = emptyMap(),
        message = "KWebView must be composed inside a Compose Desktop Window.",
    )
    return actual as? ComposeWindow ?: throw KWebConfigurationException(
        code = "compose.window.unsupported",
        details = mapOf("type" to actual::class.qualifiedName.orEmpty()),
        message = "KWebView requires androidx.compose.ui.awt.ComposeWindow as its host.",
    )
}

internal data class KWebViewGeometry(
    val bounds: KWebRect,
)

internal data class KWebViewGeometryProbe(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomLeft: Offset,
    val size: IntSize,
    val clipped: Boolean,
    val windowWidth: Int,
    val windowHeight: Int,
)

internal fun KWebViewGeometryProbe.toNativeRect(): KWebRect {
    if (size.width <= 0 || size.height <= 0) {
        throw KWebConfigurationException(
            code = "compose.placement.empty",
            details = mapOf("width" to size.width.toString(), "height" to size.height.toString()),
            message = "KWebView requires a positive laid-out size.",
        )
    }
    if (!topLeft.x.isFinite() || !topLeft.y.isFinite() ||
        !topRight.x.isFinite() || !topRight.y.isFinite() ||
        !bottomLeft.x.isFinite() || !bottomLeft.y.isFinite()
    ) {
        throw KWebConfigurationException(
            code = "compose.placement.non-finite",
            details = emptyMap(),
            message = "KWebView geometry contained a non-finite coordinate.",
        )
    }
    val horizontal = topRight - topLeft
    val vertical = bottomLeft - topLeft
    if (abs(horizontal.y) > 0.5f || abs(vertical.x) > 0.5f ||
        abs(horizontal.x - size.width) > 0.5f ||
        abs(vertical.y - size.height) > 0.5f || horizontal.x <= 0f || vertical.y <= 0f
    ) {
        throw KWebConfigurationException(
            code = "compose.placement.transform-unsupported",
            details = emptyMap(),
            message = "KWebView supports only axis-aligned, unscaled Compose geometry.",
        )
    }
    if (clipped) {
        throw KWebConfigurationException(
            code = "compose.placement.clip-unsupported",
            details = emptyMap(),
            message = "KWebView cannot represent a Compose-clipped native child.",
        )
    }
    val x = topLeft.x.roundToInt()
    val y = topLeft.y.roundToInt()
    if (windowWidth > 0 && windowHeight > 0 &&
        (x + size.width > windowWidth || y + size.height > windowHeight)
    ) {
        throw KWebConfigurationException(
            code = "compose.placement.out-of-window",
            details = mapOf(
                "x" to x.toString(),
                "y" to y.toString(),
                "width" to size.width.toString(),
                "height" to size.height.toString(),
                "windowWidth" to windowWidth.toString(),
                "windowHeight" to windowHeight.toString(),
            ),
            message = "KWebView must remain inside the ComposeWindow content bounds.",
        )
    }
    return KWebRect(x, y, size.width, size.height)
}

private data class KWebViewPlacement(
    val geometry: KWebViewGeometry,
    val visible: Boolean,
    val focused: Boolean,
)

private class KWebViewPlacementDispatcher(
    private val controller: KWebViewController,
    private val window: ComposeWindow,
) : KWebViewSurfaceBinding, AutoCloseable {
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)
    private val placements = MutableStateFlow<KWebViewPlacement?>(null)
    private var latestGeometry: KWebViewGeometry? = null
    private var closed = false
    private val applyJob: Job = scope.launch {
        placements.filterNotNull().collectLatest { placement ->
            applyPlacement(placement)
        }
    }

    override fun requestFocus() {
        refreshWindowState()
    }

    override fun clearFocus() {
        refreshWindowState()
    }

    fun submitGeometry(coordinates: LayoutCoordinates) {
        if (closed || controller.placementError.value != null) return
        try {
            val geometry = readGeometry(coordinates)
            latestGeometry = geometry
            publishPlacement(geometry)
        } catch (error: KWebException) {
            fail(error)
        } catch (error: Throwable) {
            fail(
                KWebConfigurationException(
                    code = "compose.placement.geometry-failed",
                    details = emptyMap(),
                    message = "Compose geometry could not be converted to native pixels.",
                    cause = error,
                ),
            )
        }
    }

    fun refreshWindowState() {
        if (closed) return
        latestGeometry?.let(::publishPlacement)
    }

    override fun disposeSurface() {
        if (closed) return
        val page = controller.page
        controller.placementScope().launch {
            try {
                val state = page.lifecycle.first { it != KWebLifecycleState.OPENING }
                if (state == KWebLifecycleState.OPEN) {
                    page.setSurfaceState(visible = false, focused = false)
                    if (controller.ownership == KWebViewPageOwnership.COMPONENT) {
                        page.close()
                    }
                } else if (controller.ownership == KWebViewPageOwnership.COMPONENT &&
                    state != KWebLifecycleState.CLOSED
                ) {
                    page.close()
                }
            } catch (error: Throwable) {
                controller.reportPlacementError(
                    KWebNativeException(
                        code = "compose.webview.dispose-failed",
                        details = emptyMap(),
                        message = "The native page could not be detached from the Compose surface.",
                        cause = error,
                    ),
                )
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        placements.value = null
        applyJob.cancel()
        scope.cancel()
    }

    private suspend fun applyPlacement(placement: KWebViewPlacement) {
        val page = controller.page
        val state = page.lifecycle.first { it != KWebLifecycleState.OPENING }
        if (state != KWebLifecycleState.OPEN) {
            if (state == KWebLifecycleState.FAILED) {
                fail(
                    KWebNativeException(
                        code = "compose.webview.page-failed",
                        details = emptyMap(),
                        message = "The KWeb page failed before it could be placed.",
                    ),
                )
            }
            return
        }
        try {
            page.setBounds(placement.geometry.bounds)
            page.setSurfaceState(placement.visible, placement.focused)
        } catch (error: KWebException) {
            fail(error)
        } catch (error: Throwable) {
            fail(
                KWebNativeException(
                    code = "compose.placement.native-update-failed",
                    details = emptyMap(),
                    message = "The native page rejected a Compose placement update.",
                    cause = error,
                ),
            )
        }
    }

    private fun publishPlacement(geometry: KWebViewGeometry) {
        val visible = window.isDisplayable && window.isShowing && !window.isMinimized
        val focused = visible && controller.desiredFocus.value && window.isFocused
        placements.value = KWebViewPlacement(geometry, visible, focused)
    }

    private fun fail(error: KWebException) {
        if (controller.placementError.value == null) {
            controller.reportPlacementError(error)
            placements.value = null
            controller.placementScope().launch {
                try {
                    if (controller.page.lifecycle.first { it != KWebLifecycleState.OPENING } ==
                        KWebLifecycleState.OPEN
                    ) {
                        controller.page.setSurfaceState(visible = false, focused = false)
                    }
                } catch (detachError: Throwable) {
                    error.addSuppressed(detachError)
                }
            }
        }
    }

    private fun readGeometry(coordinates: LayoutCoordinates): KWebViewGeometry {
        if (!coordinates.isAttached) {
            throw KWebConfigurationException(
                code = "compose.placement.detached",
                details = emptyMap(),
                message = "The KWebView layout node is detached from the Compose tree.",
            )
        }
        val size = coordinates.size
        val topLeft = coordinates.localToWindow(Offset.Zero)
        val topRight = coordinates.localToWindow(Offset(size.width.toFloat(), 0f))
        val bottomLeft = coordinates.localToWindow(Offset(0f, size.height.toFloat()))
        val root = rootCoordinates(coordinates)
        val unclipped = root.localBoundingBoxOf(coordinates, clipBounds = false)
        val clipped = root.localBoundingBoxOf(coordinates, clipBounds = true)
        val content = window.contentPane
        return KWebViewGeometry(
            KWebViewGeometryProbe(
                topLeft = topLeft,
                topRight = topRight,
                bottomLeft = bottomLeft,
                size = size,
                clipped = !sameRect(unclipped, clipped),
                windowWidth = content.width,
                windowHeight = content.height,
            ).toNativeRect(),
        )
    }

    private fun rootCoordinates(coordinates: LayoutCoordinates): LayoutCoordinates {
        var root = coordinates
        while (root.parentLayoutCoordinates != null) {
            root = requireNotNull(root.parentLayoutCoordinates)
        }
        return root
    }

    private fun sameRect(first: Rect, second: Rect): Boolean =
        abs(first.left - second.left) < 0.5f &&
            abs(first.top - second.top) < 0.5f &&
            abs(first.right - second.right) < 0.5f &&
            abs(first.bottom - second.bottom) < 0.5f
}

private class KWebViewAnchor(
    private val dispatcher: KWebViewPlacementDispatcher,
) {
    private var panel: KWebViewAnchorPanel? = null

    fun createPanel(): JPanel {
        val created = KWebViewAnchorPanel(dispatcher)
        panel = created
        return created
    }

    fun refresh() {
        panel?.revalidate()
        panel?.repaint()
    }
}

private class KWebViewAnchorPanel(
    private val dispatcher: KWebViewPlacementDispatcher,
) : JPanel(null) {
    init {
        isOpaque = false
        background = Color(0, 0, 0, 0)
        isFocusable = false
        preferredSize = Dimension(1, 1)
        minimumSize = Dimension(1, 1)
        setIgnoreRepaint(true)
    }
}
