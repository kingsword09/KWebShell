package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.clipboard.internal.ClipboardFfm
import io.github.kingsword09.kwebshell.services.KWebServiceErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.channels.awaitClose

private const val LIBRARY_PROPERTY = "kweb.clipboard.native.library.path"

public object JvmKWebClipboard {
    public fun open(): KWebClipboard {
        val path = System.getProperty(LIBRARY_PROPERTY)
            ?: throw nativeFailure(
                KWebClipboardErrorCode.NATIVE_UNAVAILABLE,
                "The KWebClipboard native library path is not configured.",
                "open",
            )
        return open(Path.of(path))
    }

    public fun open(libraryPath: Path): KWebClipboard = try {
        NativeKWebClipboard(ClipboardFfm.open(libraryPath))
    } catch (error: ClipboardFfm.NativeFailure) {
        throw mapFailure("open", error)
    } catch (error: IllegalCallerException) {
        throw nativeFailure(
            "service.native-access-required",
            "JDK native access must be enabled for the KWebClipboard provider.",
            "open",
            error,
        )
    } catch (error: Exception) {
        throw nativeFailure(
            KWebClipboardErrorCode.NATIVE_UNAVAILABLE,
            "The KWebClipboard native provider could not be opened.",
            "open",
            error,
        )
    }
}

internal class NativeKWebClipboard(
    private val native: ClipboardFfm,
) : KWebClipboard {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val payloads = linkedMapOf<String, Payload>()
    private val expiredPayloads = LinkedHashSet<String>()
    private val subscribers = linkedSetOf<SendChannel<KWebClipboardChange>>()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val random = SecureRandom()
    private val providerId = native.providerId()
    private var signature = pollLocked()
    private var monitorJob: Job = scope.launch { monitor() }
    private var closeFailure: KWebNativeException? = null

    override val descriptor = KWebClipboard.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    internal fun providerIdForEvidence(): String = providerId

    override suspend fun read(request: KWebClipboardReadRequest): KWebClipboardReadResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("read")
                requireSystem(request.selection)
                publishIfChangedLocked()
                val descriptors = request.formats.mapNotNull { format ->
                    val raw = readNative(format, "read") ?: return@mapNotNull null
                    val bytes = ClipboardPolicy.normalize(format, raw)
                    if (payloads.size >= MAX_PAYLOAD_HANDLES ||
                        payloads.values.sumOf { it.bytes.size.toLong() } + bytes.size > MAX_SNAPSHOT_BYTES
                    ) {
                        throw nativeFailure(
                            KWebClipboardErrorCode.PAYLOAD_LIMIT,
                            "The clipboard snapshot limit has been reached.",
                            "read",
                        )
                    }
                    val token = nextToken()
                    payloads[token] = Payload(bytes, signature.sequence)
                    KWebClipboardPayloadDescriptor(
                        KWebClipboardPayloadHandle(token),
                        format,
                        format.encoding,
                        bytes.size.toLong(),
                    )
                }
                KWebClipboardReadResult(signature.sequence, descriptors)
            }
        }

    override suspend fun readPayload(
        handle: KWebClipboardPayloadHandle,
        offset: Long,
        length: Int,
    ): KWebClipboardReadChunk = withContext(Dispatchers.IO) {
        synchronized(lock) {
            requireOpen("read-payload")
            if (offset < 0 || length !in 0..KWEB_CLIPBOARD_MAX_READ_CHUNK_BYTES ||
                offset > Long.MAX_VALUE - length
            ) {
                throw nativeFailure(
                    KWebClipboardErrorCode.PAYLOAD_INVALID,
                    "The clipboard payload offset or length is outside its bound.",
                    "read-payload",
                )
            }
            val payload = payloads[handle.token] ?: throw nativeFailure(
                if (handle.token in expiredPayloads) KWebClipboardErrorCode.PAYLOAD_EXPIRED
                else KWebClipboardErrorCode.PAYLOAD_NOT_FOUND,
                if (handle.token in expiredPayloads) "The clipboard payload expired."
                else "The clipboard payload handle is not live.",
                "read-payload",
            )
            if (payload.sequence != signature.sequence) {
                expirePayloadLocked(handle.token)
                throw nativeFailure(
                    KWebClipboardErrorCode.PAYLOAD_EXPIRED,
                    "The clipboard payload expired after a clipboard state change.",
                    "read-payload",
                )
            }
            if (offset >= payload.bytes.size) {
                return@withContext KWebClipboardReadChunk(ByteArray(0), true)
            }
            val end = minOf(payload.bytes.size.toLong(), offset + length).toInt()
            KWebClipboardReadChunk(
                payload.bytes.copyOfRange(offset.toInt(), end),
                end >= payload.bytes.size,
            )
        }
    }

    override suspend fun write(request: KWebClipboardWriteRequest): KWebClipboardWriteResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("write")
                requireSystem(request.selection)
                val prepared = request.items.map { item ->
                    item.format to ClipboardPolicy.normalize(item.format, item.bytes)
                }
                try {
                    native.write(prepared.map { (format, bytes) ->
                        ClipboardFfm.NativeItem(format.nativeId, bytes)
                    }.toTypedArray())
                } catch (error: ClipboardFfm.NativeFailure) {
                    throw mapFailure("write", error)
                }
                publishIfChangedLocked(force = true)
                KWebClipboardWriteResult(signature.sequence)
            }
        }

    override suspend fun clear(selection: KWebClipboardSelection): Long =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("clear")
                requireSystem(selection)
                try {
                    native.clear()
                } catch (error: ClipboardFfm.NativeFailure) {
                    throw mapFailure("clear", error)
                }
                publishIfChangedLocked(force = true)
                signature.sequence
            }
        }

    override fun changes(): Flow<KWebClipboardChange> = channelFlow {
        val channel = this
        val initial = synchronized(lock) {
            requireOpen("changes")
            subscribers += channel
            signature.toChange()
        }
        if (channel.trySend(initial).isFailure) {
            synchronized(lock) { subscribers.remove(channel) }
            return@channelFlow
        }
        awaitClose {
            synchronized(lock) { subscribers.remove(channel) }
        }
    }.buffer(KWEB_CLIPBOARD_CHANGE_CAPACITY)

    override suspend fun closePayload(handle: KWebClipboardPayloadHandle) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("close-payload")
                if (payloads.remove(handle.token) == null) {
                    throw nativeFailure(
                        if (handle.token in expiredPayloads) KWebClipboardErrorCode.PAYLOAD_EXPIRED
                        else KWebClipboardErrorCode.PAYLOAD_NOT_FOUND,
                        "The clipboard payload handle is not live.",
                        "close-payload",
                    )
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            closeFailure?.let { throw it }
            mutableLifecycle.value = KWebLifecycleState.CLOSING
            try {
                monitorJob.cancel()
                expireAllPayloadsLocked()
                subscribers.toList().forEach { it.close() }
                subscribers.clear()
                native.close()
                scope.cancel()
                mutableLifecycle.value = KWebLifecycleState.CLOSED
            } catch (error: ClipboardFfm.NativeFailure) {
                val failure = mapFailure("close", error)
                closeFailure = failure
                mutableLifecycle.value = KWebLifecycleState.FAILED
                throw failure
            } catch (error: Exception) {
                val failure = nativeFailure(
                    KWebClipboardErrorCode.NATIVE_UNAVAILABLE,
                    "The KWebClipboard native provider could not close.",
                    "close",
                    error,
                )
                closeFailure = failure
                mutableLifecycle.value = KWebLifecycleState.FAILED
                throw failure
            }
        }
    }

    private suspend fun monitor() {
        while (scope.coroutineContext[Job]?.isActive == true) {
            delay(100)
            synchronized(lock) {
                if (mutableLifecycle.value != KWebLifecycleState.OPEN) return
                publishIfChangedLocked()
            }
        }
    }

    private fun publishIfChangedLocked(force: Boolean = false) {
        val current = pollLocked()
        if (!force && current == signature) return
        signature = current
        expireAllPayloadsLocked()
        val event = current.toChange()
        subscribers.toList().forEach { channel ->
            if (channel.trySend(event).isFailure) {
                subscribers.remove(channel)
                channel.close(
                    nativeFailure(
                        KWebClipboardErrorCode.CHANGE_OVERFLOW,
                        "The clipboard change stream exceeded its bounded capacity.",
                        "changes",
                    ),
                )
            }
        }
    }

    private fun pollLocked(): Signature {
        val snapshot = try {
            native.snapshot()
        } catch (error: ClipboardFfm.NativeFailure) {
            throw mapFailure("snapshot", error)
        }
        return Signature(
            sequence = snapshot.sequence(),
            formats = KWebClipboardFormat.entries.filter { snapshot.formatMask() and it.nativeBit != 0 },
            ownership = when (snapshot.ownership()) {
                OWNERSHIP_OWNED -> KWebClipboardOwnership.OWNED
                OWNERSHIP_FOREIGN -> KWebClipboardOwnership.FOREIGN
                OWNERSHIP_EMPTY -> KWebClipboardOwnership.EMPTY
                else -> KWebClipboardOwnership.UNAVAILABLE
            },
        )
    }

    private fun readNative(format: KWebClipboardFormat, operation: String): ByteArray? = try {
        native.read(format.nativeId)
    } catch (error: ClipboardFfm.NativeFailure) {
        if (error.status() == ClipboardFfm.STATUS_FORMAT_UNSUPPORTED) null
        else throw mapFailure(operation, error)
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) {
            throw nativeFailure(KWebServiceErrorCode.OWNER_CLOSED, "The clipboard service is not open.", operation)
        }
    }

    private fun requireSystem(selection: KWebClipboardSelection) {
        if (selection != KWebClipboardSelection.SYSTEM) {
            throw nativeFailure(
                KWebClipboardErrorCode.SELECTION_UNSUPPORTED,
                "Only the SYSTEM clipboard selection is published.",
                "selection",
            )
        }
    }

    private fun nextToken(): String {
        var token: String
        do {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        } while (payloads.containsKey(token))
        return token
    }

    private fun expirePayloadLocked(token: String) {
        payloads.remove(token)
        expiredPayloads += token
        while (expiredPayloads.size > MAX_EXPIRED_PAYLOADS) expiredPayloads.removeFirst()
    }

    private fun expireAllPayloadsLocked() {
        payloads.keys.toList().forEach(::expirePayloadLocked)
    }

    private data class Payload(val bytes: ByteArray, val sequence: Long)

    private data class Signature(
        val sequence: Long,
        val formats: List<KWebClipboardFormat>,
        val ownership: KWebClipboardOwnership,
    ) {
        fun toChange(): KWebClipboardChange =
            KWebClipboardChange(sequence, KWebClipboardSelection.SYSTEM, formats, ownership)
    }

    private companion object {
        const val MAX_PAYLOAD_HANDLES = 16
        const val MAX_SNAPSHOT_BYTES = KWEB_CLIPBOARD_MAX_WRITE_BYTES.toLong()
        const val MAX_EXPIRED_PAYLOADS = 64
        const val OWNERSHIP_OWNED = 1
        const val OWNERSHIP_FOREIGN = 2
        const val OWNERSHIP_EMPTY = 3
    }
}

private val KWebClipboardFormat.nativeId: Int
    get() = ordinal + 1

private val KWebClipboardFormat.nativeBit: Int
    get() = 1 shl ordinal

private val KWebClipboardFormat.encoding: KWebClipboardPayloadEncoding
    get() = if (this == KWebClipboardFormat.TEXT_RTF) KWebClipboardPayloadEncoding.RTF_BYTES
    else KWebClipboardPayloadEncoding.UTF8

private fun mapFailure(operation: String, error: ClipboardFfm.NativeFailure): KWebNativeException {
    val code = when (error.status()) {
        ClipboardFfm.STATUS_NATIVE_UNAVAILABLE -> KWebClipboardErrorCode.PLATFORM_UNAVAILABLE
        ClipboardFfm.STATUS_READ_UNAVAILABLE -> KWebClipboardErrorCode.READ_UNAVAILABLE
        ClipboardFfm.STATUS_WRITE_UNAVAILABLE -> KWebClipboardErrorCode.WRITE_UNAVAILABLE
        ClipboardFfm.STATUS_WRITE_OUTCOME_UNKNOWN -> KWebClipboardErrorCode.WRITE_OUTCOME_UNKNOWN
        ClipboardFfm.STATUS_FORMAT_UNSUPPORTED -> KWebClipboardErrorCode.FORMAT_UNSUPPORTED
        else -> KWebClipboardErrorCode.NATIVE_UNAVAILABLE
    }
    return nativeFailure(code, "The native clipboard operation failed.", operation, error)
}

private fun nativeFailure(
    code: String,
    message: String,
    operation: String,
    cause: Throwable? = null,
): KWebNativeException = KWebNativeException(
    code = code,
    details = mapOf("service" to KWebClipboard.DESCRIPTOR.id, "operation" to operation),
    message = message,
    cause = cause,
)
