package io.github.kingsword09.kwebshell.service.windowcontrols

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebTarget
import io.github.kingsword09.kwebshell.services.KWebNativeService
import io.github.kingsword09.kwebshell.services.KWebServiceDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceKey
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServiceScope
import io.github.kingsword09.kwebshell.services.KWebServiceVersion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public enum class KWebWindowPlacement(public val id: String) {
    FLOATING("floating"),
    MAXIMIZED("maximized"),
    FULLSCREEN("fullscreen"),
    ;

    public companion object {
        public fun fromId(id: String): KWebWindowPlacement = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = "service.request-invalid",
                details = mapOf("placement" to id),
                message = "The requested window placement is not published.",
            )
    }
}

public data class KWebWindowBounds(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        if (width <= 0 || height <= 0) {
            throw KWebConfigurationException(
                code = "window.bounds.invalid",
                details = mapOf("width" to width.toString(), "height" to height.toString()),
                message = "Window bounds must have positive width and height.",
            )
        }
    }
}

public data class KWebWindowState(
    public val title: String,
    public val bounds: KWebWindowBounds,
    public val visible: Boolean,
    public val focused: Boolean,
    public val minimized: Boolean,
    public val placement: KWebWindowPlacement,
    public val alwaysOnTop: Boolean,
    public val resizable: Boolean,
)

public data class KWebWindowEvent(
    public val sequence: Long,
    public val state: KWebWindowState,
)

public interface KWebWindowControls : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>
    public val state: StateFlow<KWebWindowState>
    public val events: Flow<KWebWindowEvent>

    public suspend fun snapshot(): KWebWindowState
    public suspend fun setTitle(title: String): KWebWindowState
    public suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState
    public suspend fun setVisible(visible: Boolean): KWebWindowState
    public suspend fun focus(): KWebWindowState
    public suspend fun minimize(): KWebWindowState
    public suspend fun restore(): KWebWindowState
    public suspend fun setMaximized(maximized: Boolean): KWebWindowState
    public suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState
    public suspend fun setResizable(resizable: Boolean): KWebWindowState

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "window-controls",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("get-state"),
                operation("set-title"),
                operation("set-bounds"),
                operation("set-visible"),
                operation("focus"),
                operation("minimize"),
                operation("restore"),
                operation("set-maximized"),
                operation("set-always-on-top"),
                operation("set-resizable"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebWindowControls> = object : KWebServiceKey<KWebWindowControls> {
            override val id: String = DESCRIPTOR.id
            override val version: KWebServiceVersion = DESCRIPTOR.version
        }

        private fun operation(id: String): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 1,
            rendererPermission = "native.window-controls.$id",
            requiresUserGesture = false,
        )
    }
}
