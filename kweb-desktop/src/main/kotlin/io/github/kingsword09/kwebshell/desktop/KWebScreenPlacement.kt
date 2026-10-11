package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.service.screen.KWebScreenGeometry
import io.github.kingsword09.kwebshell.service.screen.KWebScreenId
import io.github.kingsword09.kwebshell.service.screen.KWebScreenPoint
import io.github.kingsword09.kwebshell.service.screen.KWebScreenSpace
import io.github.kingsword09.kwebshell.service.screen.KWebScreens
import io.github.kingsword09.kwebshell.service.screen.KWEB_SCREEN_MAX_EXTENT

/** One resolved window rectangle on a display that exists in the live topology. */
public data class KWebWindowPlacement(
    public val displayId: KWebScreenId,
    public val bounds: KWebScreenGeometry,
    /** The display the request named when that display no longer exists. */
    public val movedFrom: KWebScreenId? = null,
)

/**
 * Resolves window rectangles against the live screen topology. Every placement is
 * resolved from one topology read, so a display that was removed while a window
 * was open is never used as a placement target again: the request moves to the
 * nearest display of its seed point, or to the primary display, and reports the
 * identifier it moved from.
 */
public class KWebScreenPlacer(private val screens: KWebScreens) {
    /**
     * Resolves one window rectangle. The bounds always lie inside the chosen
     * display's work area, and a window larger than that area is clipped to it
     * instead of being placed outside the desktop.
     */
    public suspend fun place(
        displayId: KWebScreenId?,
        width: Int,
        height: Int,
        seed: KWebScreenPoint? = null,
    ): KWebWindowPlacement {
        if (width !in 1..KWEB_SCREEN_MAX_EXTENT || height !in 1..KWEB_SCREEN_MAX_EXTENT) {
            throw KWebConfigurationException(
                code = "desktop.placement.size-invalid",
                details = mapOf("width" to width.toString(), "height" to height.toString()),
                message = "A window placement requires a positive bounded size.",
            )
        }
        val topology = screens.topology()
        val requested = displayId?.let { topology.display(it) }
        val target = requested
            ?: seed?.let { point -> topology.nearest(point, KWebScreenSpace.LOGICAL) }
            ?: topology.primary
            ?: topology.displays.first()
        val area = target.workArea ?: target.geometry
        val boundedWidth = minOf(width, area.width)
        val boundedHeight = minOf(height, area.height)
        val left = area.left + (area.width - boundedWidth) / 2
        val top = area.top + (area.height - boundedHeight) / 2
        return KWebWindowPlacement(
            displayId = target.id,
            bounds = KWebScreenGeometry(left, top, boundedWidth, boundedHeight),
            movedFrom = if (requested == null) displayId else null,
        )
    }
}
