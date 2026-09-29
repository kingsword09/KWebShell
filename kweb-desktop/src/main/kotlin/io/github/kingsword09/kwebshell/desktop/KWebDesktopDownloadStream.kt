package io.github.kingsword09.kwebshell.desktop

import io.github.kingsword09.kwebshell.core.KWebDownload
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

internal class KWebDesktopDownloadStream(
    private val capacity: Int = 64,
) {
    private val events = MutableSharedFlow<KWebDownload>(
        replay = 0,
        extraBufferCapacity = capacity,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private val mutableFailure = MutableStateFlow<KWebNativeException?>(null)
    private val lock = Any()
    private var terminalFailure: KWebNativeException? = null

    val flow: Flow<KWebDownload> = flow {
        synchronized(lock) { terminalFailure }?.let { throw it }
        coroutineScope {
            val watcher = launch {
                mutableFailure.filterNotNull().collect { throw it }
            }
            try {
                events.asSharedFlow().collect { emit(it) }
            } finally {
                watcher.cancel()
            }
        }
    }

    suspend fun awaitSubscriber() {
        events.subscriptionCount.first { it > 0 }
    }

    fun publish(download: KWebDownload): Boolean = synchronized(lock) {
        if (terminalFailure != null) return false
        if (events.subscriptionCount.value == 0) {
            failLocked(
                KWebNativeException(
                    code = "download.event-backpressure",
                    details = emptyMap(),
                    message = "The Profile download stream has no active subscriber.",
                ),
            )
            return false
        }
        if (events.tryEmit(download)) return true
        failLocked(
            KWebNativeException(
                code = "download.event-backpressure",
                details = emptyMap(),
                message = "The Profile download stream has no bounded capacity.",
            ),
        )
        false
    }

    fun close() {
        synchronized(lock) {
            if (terminalFailure == null) {
                failLocked(
                    KWebNativeException(
                        code = "download.profile-closing",
                        details = emptyMap(),
                        message = "The Profile closed while downloads were being observed.",
                    ),
                )
            }
        }
    }

    private fun failLocked(error: KWebNativeException) {
        if (terminalFailure == null) {
            terminalFailure = error
            mutableFailure.value = error
        }
    }
}
