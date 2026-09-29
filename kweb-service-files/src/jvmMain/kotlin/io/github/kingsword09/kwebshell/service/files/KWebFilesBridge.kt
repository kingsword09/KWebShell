package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.bridge.KWebBridgeDispatcher
import io.github.kingsword09.kwebshell.bridge.KWebBridgeException
import io.github.kingsword09.kwebshell.bridge.KWebStreamBridgeDispatcher
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.files.generated.CloseHandleRequest
import io.github.kingsword09.kwebshell.service.files.generated.CloseHandleResponse
import io.github.kingsword09.kwebshell.service.files.generated.CopyMoveRequest
import io.github.kingsword09.kwebshell.service.files.generated.DirectoryEntry
import io.github.kingsword09.kwebshell.service.files.generated.FilesBridgeDispatcher
import io.github.kingsword09.kwebshell.service.files.generated.FilesBridgeHandler
import io.github.kingsword09.kwebshell.service.files.generated.FilesBridgeStreamDispatcher
import io.github.kingsword09.kwebshell.service.files.generated.FilesBridgeStreamHandler
import io.github.kingsword09.kwebshell.service.files.generated.HandleDescriptor
import io.github.kingsword09.kwebshell.service.files.generated.ListDirectoryRequest
import io.github.kingsword09.kwebshell.service.files.generated.ListDirectoryResponse
import io.github.kingsword09.kwebshell.service.files.generated.MetadataRequest
import io.github.kingsword09.kwebshell.service.files.generated.MetadataResponse
import io.github.kingsword09.kwebshell.service.files.generated.OpenDirectoryRequest
import io.github.kingsword09.kwebshell.service.files.generated.OpenFileRequest
import io.github.kingsword09.kwebshell.service.files.generated.ReadFileRequest
import io.github.kingsword09.kwebshell.service.files.generated.ReadFileResponse
import io.github.kingsword09.kwebshell.service.files.generated.TruncateFileRequest
import io.github.kingsword09.kwebshell.service.files.generated.TruncateFileResponse
import io.github.kingsword09.kwebshell.service.files.generated.WatchDirectoryRequest
import io.github.kingsword09.kwebshell.service.files.generated.WatchEvent
import io.github.kingsword09.kwebshell.service.files.generated.WorkspaceRequest
import io.github.kingsword09.kwebshell.service.files.generated.WriteFileRequest
import io.github.kingsword09.kwebshell.service.files.generated.WriteFileResponse
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebPolicyDecision
import io.github.kingsword09.kwebshell.services.policy.KWebServicePolicyEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

public fun KWebFiles.bridgeDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebBridgeDispatcher = FilesBridgeDispatcher(
    object : FilesBridgeHandler {
        override suspend fun openWorkspace(request: WorkspaceRequest): HandleDescriptor = dispatch("open-workspace", policyEngine, subject) {
            toDescriptor(openWorkspace(KWebWorkspaceRequest(request.workspaceId, request.grants.map(::grant).toSet())))
        }

        override suspend fun openFile(request: OpenFileRequest): HandleDescriptor = dispatch("open-file", policyEngine, subject) {
            toDescriptor(openFile(KWebFileOpenRequest(
                parent = KWebFileHandle.fromBridge(request.parent),
                name = request.name,
                mode = openMode(request.mode),
                createIfMissing = request.createIfMissing,
            )))
        }

        override suspend fun openDirectory(request: OpenDirectoryRequest): HandleDescriptor = dispatch("open-directory", policyEngine, subject) {
            toDescriptor(openDirectory(KWebDirectoryOpenRequest(
                parent = KWebFileHandle.fromBridge(request.parent),
                name = request.name,
                createIfMissing = request.createIfMissing,
            )))
        }

        override suspend fun readFile(request: ReadFileRequest): ReadFileResponse = dispatch("read-file", policyEngine, subject) {
            val result = readFile(KWebFileHandle.fromBridge(request.handle), decimal(request.offset, "read-file"), request.length)
            ReadFileResponse(result.bytes.map { it.toInt() and 0xff }, result.eof)
        }

        override suspend fun writeFile(request: WriteFileRequest): WriteFileResponse = dispatch("write-file", policyEngine, subject) {
            WriteFileResponse(
                writeFile(
                    KWebFileHandle.fromBridge(request.handle),
                    decimal(request.offset, "write-file"),
                    bytes(request.bytes, "write-file"),
                ).written,
            )
        }

        override suspend fun truncateFile(request: TruncateFileRequest): TruncateFileResponse = dispatch("truncate-file", policyEngine, subject) {
            TruncateFileResponse(truncateFile(KWebFileHandle.fromBridge(request.handle), decimal(request.sizeBytes, "truncate-file")).toString())
        }

        override suspend fun listDirectory(request: ListDirectoryRequest): ListDirectoryResponse = dispatch("list-directory", policyEngine, subject) {
            val result = listDirectory(KWebFileHandle.fromBridge(request.handle), request.limit)
            ListDirectoryResponse(result.entries.map(::entry), result.truncated)
        }

        override suspend fun metadata(request: MetadataRequest): MetadataResponse = dispatch("metadata", policyEngine, subject) {
            val result = metadata(KWebFileHandle.fromBridge(request.handle))
            MetadataResponse(result.kind.id, result.name, result.sizeBytes?.toString(), result.lastModifiedEpochMillis.toString())
        }

        override suspend fun copyFile(request: CopyMoveRequest): HandleDescriptor = dispatch("copy-file", policyEngine, subject) {
            toDescriptor(copyFile(
                KWebFileHandle.fromBridge(request.source),
                KWebFileHandle.fromBridge(request.targetDirectory),
                request.targetName,
                conflict(request.conflict),
            ))
        }

        override suspend fun moveFile(request: CopyMoveRequest): HandleDescriptor = dispatch("move-file", policyEngine, subject) {
            toDescriptor(moveFile(
                KWebFileHandle.fromBridge(request.source),
                KWebFileHandle.fromBridge(request.targetDirectory),
                request.targetName,
                conflict(request.conflict),
            ))
        }

        override suspend fun closeHandle(request: CloseHandleRequest): CloseHandleResponse = dispatch("close-handle", policyEngine, subject) {
            closeHandle(KWebFileHandle.fromBridge(request.handle))
            CloseHandleResponse(true)
        }
    },
)

public fun KWebFiles.bridgeStreamDispatcher(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
): KWebStreamBridgeDispatcher = FilesBridgeStreamDispatcher(
    object : FilesBridgeStreamHandler {
        override fun watchDirectory(request: WatchDirectoryRequest): Flow<WatchEvent> =
            kotlinx.coroutines.flow.flow {
                authorize(policyEngine, subject, "watch-directory")
                watchDirectory(KWebFileHandle.fromBridge(request.handle)).collect { event ->
                    emit(WatchEvent(event.sequence.toString(), event.kind.id, event.name))
                }
            }
    },
)

private suspend fun <T> KWebFiles.dispatch(
    operation: String,
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    action: suspend KWebFiles.() -> T,
): T {
    authorize(policyEngine, subject, operation)
    return try {
        action()
    } catch (error: CancellationException) {
        throw error
    } catch (error: KWebBridgeException) {
        throw error
    } catch (error: KWebException) {
        throw KWebBridgeException(error.code, error.message ?: "The KWebFiles operation failed.", cause = error)
    } catch (error: Exception) {
        throw KWebBridgeException("service.native-failed", "The KWebFiles operation failed.", cause = error)
    }
}

private suspend fun authorize(
    policyEngine: KWebServicePolicyEngine,
    subject: KWebPolicySubject,
    operationId: String,
) {
    val operation = KWebFiles.DESCRIPTOR.operations.first { it.id == operationId }
    when (val verdict = policyEngine.authorize(subject, KWebFiles.DESCRIPTOR.id, operation).decision) {
        KWebPolicyDecision.ALLOW -> Unit
        KWebPolicyDecision.DENY -> throw KWebBridgeException("service.permission-denied", "The KWebFiles operation was denied.")
        KWebPolicyDecision.PROMPT_REQUIRED -> throw KWebBridgeException("service.policy.prompt-required", "The KWebFiles operation requires consent.")
    }
}

private fun toDescriptor(value: KWebFileCapabilityDescriptor): HandleDescriptor = HandleDescriptor(
    handle = value.handle,
    kind = value.kind.id,
    name = value.name,
    grants = value.grants.map(KWebFileGrant::id).sorted(),
)

private fun entry(value: KWebDirectoryEntry): DirectoryEntry = DirectoryEntry(
    name = value.name,
    kind = value.kind.id,
    sizeBytes = value.sizeBytes?.toString(),
    lastModifiedEpochMillis = value.lastModifiedEpochMillis.toString(),
)

private fun grant(value: String): KWebFileGrant = KWebFileGrant.entries.singleOrNull { it.id == value }
    ?: throw KWebBridgeException("service.request-invalid", "The requested file grant is not published.")

private fun openMode(value: String): KWebFileOpenMode = KWebFileOpenMode.entries.singleOrNull { it.id == value }
    ?: throw KWebBridgeException("service.request-invalid", "The requested file open mode is not published.")

private fun conflict(value: String): KWebFileConflictPolicy = KWebFileConflictPolicy.entries.singleOrNull { it.id == value }
    ?: throw KWebBridgeException("service.request-invalid", "The requested file conflict policy is not published.")

private fun bytes(values: List<Int>, operation: String): ByteArray {
    if (values.size > KWEB_FILES_MAX_TRANSFER_BYTES || values.any { it !in 0..255 }) {
        throw KWebBridgeException(KWebFilesErrorCode.IO_BOUNDS, "The $operation byte buffer is outside the bounded range.")
    }
    return values.map(Int::toByte).toByteArray()
}

private fun decimal(value: String, operation: String): Long {
    if (!DECIMAL.matches(value)) throw KWebBridgeException("service.request-invalid", "The $operation integer is not canonical.")
    return value.toLongOrNull() ?: throw KWebBridgeException("service.request-invalid", "The $operation integer is out of range.")
}

private val DECIMAL = Regex("0|[1-9][0-9]{0,18}")

private val KWebFileGrant.id: String
    get() = name.lowercase().replace('_', '-')

private val KWebFileNodeKind.id: String
    get() = name.lowercase()

private val KWebFileOpenMode.id: String
    get() = name.lowercase().replace('_', '-')

private val KWebFileConflictPolicy.id: String
    get() = name.lowercase()

private val KWebFileWatchKind.id: String
    get() = name.lowercase()
