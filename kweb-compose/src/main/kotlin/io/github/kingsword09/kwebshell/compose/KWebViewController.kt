package io.github.kingsword09.kwebshell.compose

import androidx.compose.runtime.Stable
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.core.KWebPage
import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

public enum class KWebViewPageOwnership {
    EXTERNAL,
    COMPONENT,
}

@Stable
public class KWebViewController(
    public val page: KWebPage,
    public val ownership: KWebViewPageOwnership,
) : AutoCloseable {
    private val mutablePlacementError = MutableStateFlow<KWebException?>(null)
    private val mutableDesiredFocus = MutableStateFlow(false)
    private val attachedSurface = AtomicReference<KWebViewSurfaceBinding?>(null)
    private val scopeJob = SupervisorJob()
    private val scope = CoroutineScope(scopeJob + Dispatchers.IO)

    public val lifecycle: kotlinx.coroutines.flow.StateFlow<KWebLifecycleState> = page.lifecycle
    public val events: Flow<io.github.kingsword09.kwebshell.core.KWebPageEvent> = page.events
    public val placementError: StateFlow<KWebException?> = mutablePlacementError.asStateFlow()

    internal val desiredFocus: StateFlow<Boolean> = mutableDesiredFocus.asStateFlow()

    public suspend fun navigate(url: String) {
        page.navigate(url)
    }

    public suspend fun openDevTools() {
        page.openDevTools()
    }

    public suspend fun closeDevTools() {
        page.closeDevTools()
    }

    public fun requestFocus() {
        mutableDesiredFocus.value = true
        attachedSurface.get()?.requestFocus()
    }

    public fun clearFocus() {
        mutableDesiredFocus.value = false
        attachedSurface.get()?.clearFocus()
    }

    override fun close() {
        if (ownership == KWebViewPageOwnership.COMPONENT) {
            page.close()
        }
        scope.cancel()
    }

    internal fun attach(binding: KWebViewSurfaceBinding) {
        val previous = attachedSurface.get()
        if (previous != null && previous !== binding) {
            throw KWebNativeException(
                code = "compose.webview.multiple-attachments",
                details = emptyMap(),
                message = "A KWebViewController cannot be attached to multiple Compose surfaces.",
            )
        }
        attachedSurface.set(binding)
    }

    internal fun detach(binding: KWebViewSurfaceBinding) {
        attachedSurface.compareAndSet(binding, null)
    }

    internal fun reportPlacementError(error: KWebException) {
        mutablePlacementError.compareAndSet(null, error)
    }

    internal fun clearPlacementError() {
        mutablePlacementError.value = null
    }

    internal fun placementScope(): CoroutineScope = scope

    internal fun compositionDisposed() {
        attachedSurface.get()?.disposeSurface()
    }
}

internal interface KWebViewSurfaceBinding {
    fun requestFocus()
    fun clearFocus()
    fun disposeSurface()
}
