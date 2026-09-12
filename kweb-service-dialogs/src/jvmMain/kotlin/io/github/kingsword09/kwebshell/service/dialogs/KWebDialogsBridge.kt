package io.github.kingsword09.kwebshell.service.dialogs

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.dialogs.generated.CloseFileRequest
import io.github.kingsword09.kwebshell.service.dialogs.generated.CloseFileResponse
import io.github.kingsword09.kwebshell.service.dialogs.generated.DialogsBridgeDispatcher
import io.github.kingsword09.kwebshell.service.dialogs.generated.DialogsBridgeHandler
import io.github.kingsword09.kwebshell.service.dialogs.generated.FileFilter
import io.github.kingsword09.kwebshell.service.dialogs.generated.ReadFileRequest
import io.github.kingsword09.kwebshell.service.dialogs.generated.ReadFileResponse
import io.github.kingsword09.kwebshell.service.dialogs.generated.SelectFileRequest
import io.github.kingsword09.kwebshell.service.dialogs.generated.SelectFileResponse
import io.github.kingsword09.kwebshell.service.dialogs.generated.TruncateFileRequest
import io.github.kingsword09.kwebshell.service.dialogs.generated.TruncateFileResponse
import io.github.kingsword09.kwebshell.service.dialogs.generated.WriteFileRequest
import io.github.kingsword09.kwebshell.service.dialogs.generated.WriteFileResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.KWebServiceOperationDescriptor
import io.github.kingsword09.kwebshell.services.KWebServicePermissionPolicy
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException

public fun KWebDialogs.bridgeDispatcher(
    policy: KWebServicePermissionPolicy,
): KWebBridgeDispatcher = bridgeDispatcher { operationId ->
    if (!policy.allows(KWebDialogs.DESCRIPTOR.id, operationId)) {
        throw KWebBridgeException(
            code = "service.permission-denied",
            message = "The page is not granted '$operationId' on KWebDialogs.",
        )
    }
}

public fun KWebDialogs.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = bridgeDispatcher { operationId ->
    val operation = KWebDialogs.DESCRIPTOR.operations.first { it.id == operationId }
    val verdict = policyEngine.authorize(subject, KWebDialogs.DESCRIPTOR.id, operation)
    when (verdict.decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException(
            code = verdict.reasonCode,
            message = "The policy engine denied '$operationId' on KWebDialogs.",
        )
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException(
            code = KWebServicePolicyEngine.REASON_PROMPT,
            message = "The policy engine requires explicit consent for '$operationId' on KWebDialogs.",
        )
    }
}

private fun KWebDialogs.bridgeDispatcher(
    policyCheck: suspend (String) -> Unit,
): KWebBridgeDispatcher = DialogsBridgeDispatcher(
    object : DialogsBridgeHandler {
        override suspend fun selectFile(request: SelectFileRequest): SelectFileResponse =
            dispatch("select-file") {
                val selected = selectFile(
                    KWebFileDialogRequest(
                        mode = KWebFileDialogMode.fromId(request.mode),
                        title = request.title,
                        defaultName = request.defaultName,
                        filters = request.filters.map(::toFilter),
                    ),
                )
                selected?.let {
                    SelectFileResponse(
                        selected = true,
                        handle = it.handle,
                        name = it.name,
                        sizeBytes = it.sizeBytes.toString(),
                        mode = it.mode.id,
                    )
                } ?: SelectFileResponse(
                    selected = false,
                    handle = null,
                    name = null,
                    sizeBytes = null,
                    mode = null,
                )
            }

        override suspend fun readFile(request: ReadFileRequest): ReadFileResponse =
            dispatch("read-file") {
                val result = readFile(request.handle, request.offset.toLongValue("read-file"), request.length)
                ReadFileResponse(bytes = result.bytes, eof = result.eof)
            }

        override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse =
            dispatch("write-file") {
                WriteFileResponse(
                    written = writeFile(
                        request.handle,
                        request.offset.toLongValue("write-file"),
                        request.bytes,
                    ).written,
                )
            }

        override suspend fun truncateFile(request: TruncateFileRequest): TruncateFileResponse =
            dispatch("truncate-file") {
                TruncateFileResponse(
                    sizeBytes = truncateFile(
                        request.handle,
                        request.sizeBytes.toLongValue("truncate-file"),
                    ).sizeBytes.toString(),
                )
            }

        override suspend fun closeFile(request: CloseFileRequest): CloseFileResponse =
            dispatch("close-file") {
                closeFile(request.handle)
                CloseFileResponse(closed = true)
            }

        private suspend fun <T> dispatch(
            operationId: String,
            action: suspend () -> T,
        ): T {
            policyCheck(operationId)
            return try {
                action()
            } catch (error: CancellationException) {
                throw error
            } catch (error: KWebBridgeException) {
                throw error
            } catch (error: KWebException) {
                throw KWebBridgeException(
                    code = error.code,
                    message = "The KWebDialogs operation failed (${error.code}).",
                    cause = error,
                )
            } catch (error: Exception) {
                throw KWebBridgeException(
                    code = "service.native-failed",
                    message = "The KWebDialogs native operation failed.",
                    cause = error,
                )
            }
        }
    },
)

private fun toFilter(filter: FileFilter): KWebFileFilter = KWebFileFilter(
    description = filter.description,
    extensions = filter.extensions.map { it.removePrefix(".").lowercase() },
)

private val DECIMAL_OFFSET = Regex("0|[1-9][0-9]{0,18}")

private fun String.toLongValue(operation: String): Long =
    takeIf { DECIMAL_OFFSET.matches(it) }?.toLongOrNull() ?: throw KWebBridgeException(
        code = "service.request-invalid",
        message = "The $operation value must be a canonical, non-negative 64-bit decimal integer.",
    )
