package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebContextMenuRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One bounded, subscriber-gated page context-menu stream per Profile. Without a
 * subscriber the engine answers Chromium's own default menu immediately, so a
 * page never waits on an absent application policy.
 */
internal class KWebDesktopContextMenuStream(
    private val capacity: Int = 16,
) {
    private val events = MutableSharedFlow<KWebContextMenuRequest>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )

    val flow: Flow<KWebContextMenuRequest> = events.asSharedFlow()

    fun hasSubscriber(): Boolean = events.subscriptionCount.value > 0

    fun publish(request: KWebContextMenuRequest): Boolean = events.tryEmit(request)

    /** Waits until at least one collector observes this Profile's requests. */
    suspend fun awaitSubscriber() {
        events.subscriptionCount.map { it > 0 }.first { it }
    }
}
