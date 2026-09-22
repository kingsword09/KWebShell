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
import io.github.kingsword09.kwebshell.services.KWebServiceVersionRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

public typealias KWebWindowId = String

public enum class KWebWindowModality(public val id: String) {
    NONE("none"),
    WINDOW_MODAL("window-modal"),
    APPLICATION_MODAL("application-modal"),
    ;

    public companion object {
        public fun fromId(id: String): KWebWindowModality = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = "window.registration-invalid",
                details = mapOf("modality" to id),
                message = "The requested window modality is not published.",
            )
    }
}

public enum class KWebWindowFullscreenMode(public val id: String) {
    WINDOWED("windowed"),
    FULLSCREEN("fullscreen"),
    KIOSK("kiosk"),
    ;

    public companion object {
        public fun fromId(id: String): KWebWindowFullscreenMode = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = "window.registration-invalid",
                details = mapOf("fullscreen" to id),
                message = "The requested fullscreen mode is not published.",
            )
    }
}

public enum class KWebWindowPlacement(public val id: String) {
    FLOATING("floating"),
    MAXIMIZED("maximized"),
    MINIMIZED("minimized"),
    ;

    public companion object {
        public fun fromId(id: String): KWebWindowPlacement = entries.singleOrNull { it.id == id }
            ?: throw KWebConfigurationException(
                code = "window.registration-invalid",
                details = mapOf("placement" to id),
                message = "The requested window placement is not published.",
            )
    }
}

public enum class KWebWindowAttention(public val id: String) {
    NONE("none"),
    REQUESTED("requested"),
}

public enum class KWebWindowCloseSource(public val id: String) {
    USER("user"),
    OS("os"),
    RENDERER("renderer"),
    APPLICATION("application"),
}

public enum class KWebWindowCloseDecision(public val id: String) {
    ALLOW("allow"),
    DENY("deny"),
}

public enum class KWebWindowCloseOutcome(public val id: String) {
    PENDING("pending"),
    ALLOWED("allowed"),
    DENIED("denied"),
    TIMED_OUT("timed-out"),
    FORCED("forced"),
    OWNER_CLOSED("owner-closed"),
}

public enum class KWebWindowForceCloseReason(public val id: String) {
    APPLICATION_SHUTDOWN("application-shutdown"),
    PARENT_CLOSED("parent-closed"),
    TEST("test"),
}

public data class KWebWindowBounds(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
) {
    init {
        requireCoordinate(x, "x")
        requireCoordinate(y, "y")
        requireDimension(width, "width")
        requireDimension(height, "height")
    }
}

public data class KWebWindowConstraints(
    public val minimumWidth: Int = 1,
    public val minimumHeight: Int = 1,
    public val maximumWidth: Int? = null,
    public val maximumHeight: Int? = null,
) {
    init {
        requireDimension(minimumWidth, "minimumWidth")
        requireDimension(minimumHeight, "minimumHeight")
        maximumWidth?.let { requireDimension(it, "maximumWidth") }
        maximumHeight?.let { requireDimension(it, "maximumHeight") }
        if (maximumWidth != null && maximumWidth < minimumWidth) {
            invalid("maximumWidth", "maximumWidth must not be smaller than minimumWidth")
        }
        if (maximumHeight != null && maximumHeight < minimumHeight) {
            invalid("maximumHeight", "maximumHeight must not be smaller than minimumHeight")
        }
    }
}

public data class KWebWindowRegistration(
    public val id: KWebWindowId,
    public val parentId: KWebWindowId? = null,
    public val modality: KWebWindowModality = KWebWindowModality.NONE,
    public val initialConstraints: KWebWindowConstraints = KWebWindowConstraints(),
) {
    init {
        val encoded = id.encodeToByteArray()
        if (id.isBlank() || encoded.size > MAXIMUM_ID_BYTES || id.any(Char::isISOControl)) {
            invalid("id", "Window id must be non-empty, printable, and at most 256 UTF-8 bytes.")
        }
        if (parentId == id) invalid("parentId", "A window cannot be its own parent.")
    }
}

public data class KWebWindowState(
    public val id: KWebWindowId,
    public val parentId: KWebWindowId?,
    public val modality: KWebWindowModality,
    public val title: String,
    public val bounds: KWebWindowBounds,
    public val restoredBounds: KWebWindowBounds?,
    public val placement: KWebWindowPlacement,
    public val fullscreen: KWebWindowFullscreenMode,
    public val visible: Boolean,
    public val focused: Boolean,
    public val movable: Boolean,
    public val minimizable: Boolean,
    public val maximizable: Boolean,
    public val closable: Boolean,
    public val resizable: Boolean,
    public val alwaysOnTop: Boolean,
    public val constraints: KWebWindowConstraints,
    public val attention: KWebWindowAttention,
    public val displayId: String?,
    public val displayScale: Double?,
)

public data class KWebWindowEvent(
    public val sequence: Long,
    public val state: KWebWindowState,
)

public data class KWebWindowCloseRequest(
    public val requestId: Long,
    public val source: KWebWindowCloseSource,
    public val deadlineMillis: Long,
)

public data class KWebWindowCloseResult(
    public val requestId: Long,
    public val outcome: KWebWindowCloseOutcome,
    public val state: KWebWindowState,
)

public interface KWebWindowControls : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>
    public val registration: KWebWindowRegistration
    public val state: StateFlow<KWebWindowState>
    public val events: Flow<KWebWindowEvent>
    public val closeRequests: Flow<KWebWindowCloseRequest>

    public suspend fun snapshot(): KWebWindowState
    public suspend fun setTitle(title: String): KWebWindowState
    public suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState
    public suspend fun setConstraints(constraints: KWebWindowConstraints): KWebWindowState
    public suspend fun setVisible(visible: Boolean): KWebWindowState
    public suspend fun focus(): KWebWindowState
    public suspend fun minimize(): KWebWindowState
    public suspend fun restore(): KWebWindowState
    public suspend fun setMaximized(maximized: Boolean): KWebWindowState
    public suspend fun setFullscreen(mode: KWebWindowFullscreenMode): KWebWindowState
    public suspend fun setMovable(movable: Boolean): KWebWindowState
    public suspend fun setMinimizable(minimizable: Boolean): KWebWindowState
    public suspend fun setMaximizable(maximizable: Boolean): KWebWindowState
    public suspend fun setClosable(closable: Boolean): KWebWindowState
    public suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState
    public suspend fun setResizable(resizable: Boolean): KWebWindowState
    public suspend fun requestAttention(): KWebWindowState
    public suspend fun clearAttention(): KWebWindowState
    public suspend fun requestClose(): KWebWindowCloseResult
    public suspend fun respondToClose(
        requestId: Long,
        decision: KWebWindowCloseDecision,
    ): KWebWindowCloseResult
    public suspend fun forceClose(reason: KWebWindowForceCloseReason): KWebWindowCloseResult

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "window-controls",
            version = KWebServiceVersion(2, 0, 0),
            scope = KWebServiceScope.APPLICATION,
            operations = setOf(
                operation("get-state"),
                operation("set-title"),
                operation("set-bounds"),
                operation("set-constraints"),
                operation("set-visible"),
                operation("focus"),
                operation("minimize"),
                operation("restore"),
                operation("set-maximized"),
                operation("set-fullscreen"),
                operation("set-movable"),
                operation("set-minimizable"),
                operation("set-maximizable"),
                operation("set-closable"),
                operation("set-always-on-top"),
                operation("set-resizable"),
                operation("request-attention"),
                operation("clear-attention"),
                operation("request-close", requiresUserGesture = true),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebWindowControls> = object : KWebServiceKey<KWebWindowControls> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(
            id: String,
            requiresUserGesture: Boolean = false,
        ): KWebServiceOperationDescriptor = KWebServiceOperationDescriptor(
            id = id,
            schemaVersion = 2,
            rendererPermission = "native.window-controls.$id",
            requiresUserGesture = requiresUserGesture,
        )
    }
}

internal const val MAXIMUM_ID_BYTES: Int = 256
internal const val MAXIMUM_COORDINATE: Int = 1_000_000
internal const val MAXIMUM_DIMENSION: Int = 16_384

private fun requireCoordinate(value: Int, field: String) {
    if (value !in -MAXIMUM_COORDINATE..MAXIMUM_COORDINATE) {
        invalid(field, "Window coordinate is outside the published range.")
    }
}

private fun requireDimension(value: Int, field: String) {
    if (value !in 1..MAXIMUM_DIMENSION) {
        invalid(field, "Window dimension is outside the published range.")
    }
}

private fun invalid(field: String, message: String): Nothing = throw KWebConfigurationException(
    code = "window.registration-invalid",
    details = mapOf("field" to field),
    message = message,
)
