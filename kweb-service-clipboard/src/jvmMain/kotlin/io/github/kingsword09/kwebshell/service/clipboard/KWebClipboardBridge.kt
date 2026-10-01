package io.github.kingsword09.kwebshell.service.clipboard

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher
import io.github.kingsword09.kwebshell.service.clipboard.generated.ChangeEvent
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClearRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClearResponse
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClosePayloadRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClosePayloadResponse
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClipboardBridgeDispatcher
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClipboardBridgeHandler
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClipboardBridgeStreamDispatcher
import io.github.kingsword09.kwebshell.service.clipboard.generated.ClipboardBridgeStreamHandler
import io.github.kingsword09.kwebshell.service.clipboard.generated.ChangesRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.PayloadDescriptor
import io.github.kingsword09.kwebshell.service.clipboard.generated.ReadPayloadRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.ReadPayloadResponse
import io.github.kingsword09.kwebshell.service.clipboard.generated.ReadRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.ReadResponse
import io.github.kingsword09.kwebshell.service.clipboard.generated.WriteItem
import io.github.kingsword09.kwebshell.service.clipboard.generated.WriteRequest
import io.github.kingsword09.kwebshell.service.clipboard.generated.WriteResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

public fun KWebClipboard.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = ClipboardBridgeDispatcher(
    object : ClipboardBridgeHandler {
        override suspend fun read(request: ReadRequest): ReadResponse =
            dispatch("read", policyEngine, subject) {
                val result = read(
                    KWebClipboardReadRequest(
                        KWebClipboardSelection.fromId(request.selection),
                        request.formats.map(KWebClipboardFormat::fromId),
                    ),
                )
                ReadResponse(
                    result.sequence.toString(),
                    result.available.map { descriptor ->
                        PayloadDescriptor(
                            descriptor.handle.token,
                            descriptor.format.id,
                            descriptor.encoding.id,
                            descriptor.sizeBytes.toString(),
                        )
                    },
                )
            }

        override suspend fun readPayload(request: ReadPayloadRequest): ReadPayloadResponse =
            dispatch("read-payload", policyEngine, subject) {
                val result = readPayload(
                    KWebClipboardPayloadHandle.fromBridge(request.handle),
                    decimal(request.offset, "read-payload"),
                    request.length,
                )
                ReadPayloadResponse(result.bytes.map { it.toInt() and 0xff }, result.eof)
            }

        override suspend fun write(request: WriteRequest): WriteResponse =
            dispatch("write", policyEngine, subject) {
                val result = write(
                    KWebClipboardWriteRequest(
                        KWebClipboardSelection.fromId(request.selection),
                        request.items.map(::writeItem),
                    ),
                )
                WriteResponse(result.sequence.toString())
            }

        override suspend fun clear(request: ClearRequest): ClearResponse =
            dispatch("clear", policyEngine, subject) {
                ClearResponse(clear(KWebClipboardSelection.fromId(request.selection)).toString())
            }

        override suspend fun closePayload(request: ClosePayloadRequest): ClosePayloadResponse =
            dispatch("close-payload", policyEngine, subject) {
                closePayload(KWebClipboardPayloadHandle.fromBridge(request.handle))
                ClosePayloadResponse(true)
            }
    },
)

public fun KWebClipboard.bridgeStreamDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebStreamBridgeDispatcher = ClipboardBridgeStreamDispatcher(
    object : ClipboardBridgeStreamHandler {
        override fun changes(request: ChangesRequest): Flow<ChangeEvent> = flow {
            authorize(policyEngine, subject, "changes")
            val selection = KWebClipboardSelection.fromId(request.selection)
            this@bridgeStreamDispatcher.changes().collect { event ->
                if (event.selection == selection) {
                    emit(
                        ChangeEvent(
                            event.sequence.toString(),
                            event.selection.id,
                            event.formats.map(KWebClipboardFormat::id),
                            event.ownership.id,
                        ),
                    )
                }
            }
        }
    },
)

private suspend fun <T> KWebClipboard.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebClipboard.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: io.github.kingsword09.kwebshell.core.KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The clipboard operation failed.", cause = error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The clipboard operation failed.", cause = error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebClipboard.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebClipboard.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            verdict.reasonCode,
            "The KWebClipboard operation was denied.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            "service.policy.prompt-required",
            "The KWebClipboard operation requires consent.",
        )
    }
}

private fun writeItem(value: WriteItem): KWebClipboardWriteItem =
    KWebClipboardWriteItem(
        KWebClipboardFormat.fromId(value.format),
        KWebClipboardPayloadEncoding.entries.singleOrNull { it.id == value.encoding }
            ?: throw KWebBridgeException("service.request-invalid", "The clipboard encoding is not published."),
        bytes(value.bytes),
    )

private fun bytes(values: List<Int>): ByteArray {
    if (values.size > KWEB_CLIPBOARD_MAX_ITEM_BYTES || values.any { it !in 0..255 }) {
        throw KWebBridgeException(
            KWebClipboardErrorCode.PAYLOAD_TOO_LARGE,
            "The clipboard byte buffer is outside its bound.",
        )
    }
    return values.map(Int::toByte).toByteArray()
}

private fun decimal(value: String, operation: String): Long {
    if (!DECIMAL.matches(value)) {
        throw KWebBridgeException("service.request-invalid", "The $operation integer is not canonical.")
    }
    return value.toLongOrNull()
        ?: throw KWebBridgeException("service.request-invalid", "The $operation integer is out of range.")
}

private val DECIMAL = Regex("0|[1-9][0-9]{0,18}")
