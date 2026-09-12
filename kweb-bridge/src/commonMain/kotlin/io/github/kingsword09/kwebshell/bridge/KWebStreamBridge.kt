package io.github.kingsword09.kwebshell.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One typed stream frame on the bridge. Frames travel over the same
 * exact-origin transport as requests: a stream opens as one persistent query
 * and every frame is one response on that query, so there is exactly one
 * transport and one origin policy.
 */
@Serializable
public data class KWebStreamFrame(
    public val version: Int,
    public val sequence: Long,
    public val kind: KWebStreamFrameKind,
    public val payload: JsonElement? = null,
    public val reason: String? = null,
)

public enum class KWebStreamFrameKind {
    @kotlinx.serialization.SerialName("data")
    DATA,

    @kotlinx.serialization.SerialName("terminal")
    TERMINAL,
}

public object KWebStreamBridgeErrorCode {
    public const val SCHEMA_UNSUPPORTED: String = "bridge.stream.schema-unsupported"
    public const val FRAME_INVALID: String = "bridge.stream.frame-invalid"
    public const val SEQUENCE_INVALID: String = "bridge.stream.sequence-invalid"
    public const val TRANSPORT_CLOSED: String = "bridge.stream.transport-closed"
    public const val AGGREGATE_EXCEEDED: String = "bridge.stream.aggregate-exceeded"
    public const val ACK_UNKNOWN_STREAM: String = "bridge.stream.ack-unknown-stream"
}

/** The one declared terminal reason for a stream the server completed normally. */
public const val KWEB_STREAM_COMPLETED: String = "bridge.stream.completed"

public object KWebStreamProtocol {
    public const val VERSION: Int = 1

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    /** Encodes one frame for transport; data frames carry no reason, terminal frames carry none but theirs. */
    public fun encode(frame: KWebStreamFrame): String {
        validateShape(frame)
        return json.encodeToString(KWebStreamFrame.serializer(), frame)
    }

    /** Decodes and validates one frame; any violation is a typed [KWebBridgeException]. */
    public fun decode(text: String): KWebStreamFrame {
        val frame = try {
            json.decodeFromString(KWebStreamFrame.serializer(), text)
        } catch (error: SerializationException) {
            throw KWebBridgeException(
                code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                message = "The stream frame is not strict protocol JSON.",
                cause = error,
            )
        } catch (error: IllegalArgumentException) {
            throw KWebBridgeException(
                code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                message = "The stream frame is not strict protocol JSON.",
                cause = error,
            )
        }
        validateShape(frame)
        return frame
    }

    private fun validateShape(frame: KWebStreamFrame) {
        if (frame.version != VERSION) {
            throw KWebBridgeException(
                code = KWebStreamBridgeErrorCode.SCHEMA_UNSUPPORTED,
                message = "Only stream protocol version $VERSION is supported.",
            )
        }
        when (frame.kind) {
            KWebStreamFrameKind.DATA -> {
                if (frame.sequence < 1) {
                    throw KWebBridgeException(
                        code = KWebStreamBridgeErrorCode.SEQUENCE_INVALID,
                        message = "A data frame sequence starts at 1 and increases per stream.",
                    )
                }
                if (frame.payload == null) {
                    throw KWebBridgeException(
                        code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                        message = "A data frame must carry its typed payload.",
                    )
                }
                if (frame.reason != null) {
                    throw KWebBridgeException(
                        code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                        message = "A data frame cannot carry a terminal reason.",
                    )
                }
            }
            KWebStreamFrameKind.TERMINAL -> {
                if (frame.sequence != 0L) {
                    throw KWebBridgeException(
                        code = KWebStreamBridgeErrorCode.SEQUENCE_INVALID,
                        message = "A terminal frame is outside the data sequence numbering.",
                    )
                }
                if (frame.reason.isNullOrBlank()) {
                    throw KWebBridgeException(
                        code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                        message = "A terminal frame must declare its reason code.",
                    )
                }
            }
        }
    }
}

/**
 * The sink one open stream publishes frames through. Sinks are implemented by
 * the transport host; frames are never dropped and never buffered without bound.
 */
public interface KWebStreamFrameSink {
    /**
     * Sends one data frame. Returns false when the transport refused the frame
     * (owner closed, query gone); the stream must then end without a terminal
     * frame because the peer is gone.
     */
    public suspend fun send(frameJson: String): Boolean

    /**
     * Ends the stream after the encoded terminal frame with the transport's
     * one declared completion signal.
     */
    public suspend fun complete(frameJson: String)
}

/**
 * Dispatches declared stream operations and their acknowledgements. The
 * dispatcher owns the whole stream: it publishes frames through
 * [KWebStreamFrameSink] and returns when the stream has ended (normally,
 * cancelled, or failed). Cancellation of the dispatch coroutine is the
 * transport's declared terminal result. The host owns the credit gate: it
 * creates one gate per open stream with the schema capacity and passes it to
 * both the stream and its acknowledgements.
 */
public interface KWebStreamBridgeDispatcher {
    /** The stream open methods this dispatcher owns. */
    public val streamMethods: Set<String>

    /** The acknowledgement methods this dispatcher owns, one per stream. */
    public val acknowledgedMethods: Set<String>

    /** The declared renderer queue capacity of one stream, its initial credit. */
    public fun capacity(method: String): Int

    /** Applies one acknowledgement frame to the stream's credit gate. */
    public suspend fun acknowledge(request: KWebBridgeRequest, gate: KWebStreamCreditGate)

    /** Publishes one stream until it ends or is cancelled. */
    public suspend fun dispatchStream(request: KWebBridgeRequest, sink: KWebStreamFrameSink, gate: KWebStreamCreditGate)
}

/**
 * The credit gate enforcing backpressure. The renderer grants credits as it
 * consumes frames; the publisher suspends in [acquire] while no credit is left
 * instead of dropping, buffering without bound, or truncating. Suspension never
 * blocks a thread.
 */
public class KWebStreamCreditGate(
    initialCredits: Int,
) {
    private data class State(val credits: Int, val closed: Boolean)

    private val state = MutableStateFlow(State(initialCredits, closed = false))

    init {
        if (initialCredits < 1) {
            throw KWebBridgeException(
                code = KWebStreamBridgeErrorCode.FRAME_INVALID,
                message = "A stream credit gate starts with at least one credit.",
            )
        }
    }

    /**
     * Suspends until one credit is available; returns false once the gate is
     * closed (stream ended) without consuming a credit. Waiting suspends the
     * coroutine and never blocks a thread.
     */
    public suspend fun acquire(): Boolean {
        while (true) {
            val snapshot = state.value
            if (snapshot.closed) return false
            if (snapshot.credits > 0) {
                if (state.compareAndSet(snapshot, snapshot.copy(credits = snapshot.credits - 1))) {
                    return true
                }
                continue
            }
            // Wait for any state change; every mutation publishes a new State.
            state.first { it !== snapshot }
        }
    }

    public fun grant(count: Int) {
        if (count <= 0) return
        state.update { current ->
            if (current.closed) current else current.copy(credits = current.credits + count)
        }
    }

    public fun close() {
        state.update { it.copy(closed = true) }
    }
}
