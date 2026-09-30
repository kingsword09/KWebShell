package io.github.kingsword09.kwebshell.service.files

import io.github.kingsword09.kwebshell.core.KWebLifecycleState
import io.github.kingsword09.kwebshell.core.KWebNativeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributes
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

public data class JvmKWebWorkspace(
    public val root: Path,
    public val grants: Set<KWebFileGrant>,
) {
    init {
        require(root.isAbsolute) { "A file workspace root must be absolute." }
        require(grants.isNotEmpty()) { "A file workspace must declare at least one grant." }
    }
}

public data class JvmKWebFilesConfiguration(
    public val workspaces: Map<String, JvmKWebWorkspace>,
) {
    init {
        require(workspaces.isNotEmpty()) { "At least one file workspace must be configured." }
        workspaces.keys.forEach { require(WORKSPACE_ID.matches(it)) { "Invalid workspace id '$it'." } }
    }
}

public object JvmKWebFiles {
    public fun open(owner: KWebFileOwnerScope, configuration: JvmKWebFilesConfiguration): KWebFiles =
        JvmKWebFilesProvider(owner, configuration)
}

private class JvmKWebFilesProvider(
    private val owner: KWebFileOwnerScope,
    configuration: JvmKWebFilesConfiguration,
) : KWebFiles {
    private val lock = Any()
    private val mutableLifecycle = MutableStateFlow(KWebLifecycleState.OPEN)
    private val random = SecureRandom()
    private val handles = linkedMapOf<String, Node>()
    private val watchers = linkedSetOf<WatchService>()
    private val workspaces = configuration.workspaces.mapValues { (id, workspace) ->
        id to CanonicalWorkspace(id, canonicalRoot(workspace.root), workspace.grants)
    }.values.toMap()

    override val descriptor = KWebFiles.DESCRIPTOR
    override val lifecycle: StateFlow<KWebLifecycleState> = mutableLifecycle.asStateFlow()

    override suspend fun openWorkspace(request: KWebWorkspaceRequest): KWebFileCapabilityDescriptor =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("open-workspace")
                val workspace = workspaces[request.workspaceId] ?: throw failure(
                    KWebFilesErrorCode.WORKSPACE_NOT_ALLOWED,
                    "The requested workspace is not configured.",
                    "open-workspace",
                )
                if (!request.grants.all { it in workspace.grants }) {
                    throw failure(
                        KWebFilesErrorCode.WORKSPACE_NOT_ALLOWED,
                        "The requested workspace grants exceed the host declaration.",
                        "open-workspace",
                    )
                }
                register(Node(workspace.root, KWebFileNodeKind.DIRECTORY, request.grants, workspace, request.workspaceId))
            }
        }

    override suspend fun openFile(request: KWebFileOpenRequest): KWebFileCapabilityDescriptor =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("open-file")
                val parent = requireDirectory(request.parent, "open-file")
                requireGrant(parent, KWebFileGrant.ENUMERATE, "open-file")
                if (request.createIfMissing) requireGrant(parent, KWebFileGrant.CREATE, "open-file")
                val path = childPath(parent, request.name, "open-file")
                val channel = openFileChannel(path, request, "open-file")
                val grants = fileGrants(parent, request.mode)
                register(Node(path, KWebFileNodeKind.FILE, grants, parent.workspace, parent.workspaceId, channel))
            }
        }

    override suspend fun openDirectory(request: KWebDirectoryOpenRequest): KWebFileCapabilityDescriptor =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOpen("open-directory")
                val parent = requireDirectory(request.parent, "open-directory")
                requireGrant(parent, KWebFileGrant.ENUMERATE, "open-directory")
                if (request.createIfMissing) requireGrant(parent, KWebFileGrant.CREATE, "open-directory")
                val path = childPath(parent, request.name, "open-directory")
                if (request.createIfMissing && !Files.exists(path, NOFOLLOW_LINKS)) {
                    try {
                        Files.createDirectory(path)
                    } catch (error: Exception) {
                        throw mapIo(error, KWebFilesErrorCode.PLATFORM_UNAVAILABLE, "open-directory")
                    }
                }
                validateExisting(path, KWebFileNodeKind.DIRECTORY, "open-directory")
                register(Node(path, KWebFileNodeKind.DIRECTORY, parent.grants, parent.workspace, parent.workspaceId))
            }
        }

    override suspend fun readFile(handle: KWebFileHandle, offset: Long, length: Int): KWebFileReadResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOffset(offset, length, "read-file")
                val node = requireFile(handle, "read-file")
                requireGrant(node, KWebFileGrant.READ, "read-file")
                val channel = requireChannel(node, "read-file")
                val buffer = ByteBuffer.allocate(length)
                var total = 0
                while (buffer.hasRemaining()) {
                    val count = try {
                        channel.read(buffer, offset + total)
                    } catch (error: Exception) {
                        throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "read-file")
                    }
                    if (count < 0) break
                    if (count == 0) break
                    total += count
                }
                KWebFileReadResult(buffer.array().copyOf(total), offset + total >= channel.size())
            }
        }

    override suspend fun writeFile(handle: KWebFileHandle, offset: Long, bytes: ByteArray): KWebFileWriteResult =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                requireOffset(offset, bytes.size, "write-file")
                val node = requireFile(handle, "write-file")
                requireGrant(node, KWebFileGrant.WRITE, "write-file")
                val channel = requireChannel(node, "write-file")
                val buffer = ByteBuffer.wrap(bytes.copyOf())
                var total = 0
                while (buffer.hasRemaining()) {
                    val count = try {
                        channel.write(buffer, offset + total)
                    } catch (error: Exception) {
                        throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "write-file")
                    }
                    if (count <= 0) throw failure(KWebServiceFallback.NATIVE_FAILED, "The file write made no progress.", "write-file")
                    total += count
                }
                KWebFileWriteResult(total)
            }
        }

    override suspend fun truncateFile(handle: KWebFileHandle, sizeBytes: Long): Long =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (sizeBytes < 0) throw failure(KWebFilesErrorCode.IO_BOUNDS, "The file size cannot be negative.", "truncate-file")
                val node = requireFile(handle, "truncate-file")
                requireGrant(node, KWebFileGrant.WRITE, "truncate-file")
                try {
                    requireChannel(node, "truncate-file").truncate(sizeBytes)
                    requireChannel(node, "truncate-file").size()
                } catch (error: Exception) {
                    throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "truncate-file")
                }
            }
        }

    override suspend fun listDirectory(handle: KWebFileHandle, limit: Int): KWebDirectoryListing =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                if (limit !in 1..KWEB_FILES_MAX_DIRECTORY_ENTRIES) {
                    throw failure(KWebFilesErrorCode.ENUMERATION_LIMIT, "The directory limit is outside the bounded range.", "list-directory")
                }
                val node = requireDirectory(handle, "list-directory")
                requireGrant(node, KWebFileGrant.ENUMERATE, "list-directory")
                val entries = mutableListOf<KWebDirectoryEntry>()
                var truncated = false
                try {
                    Files.newDirectoryStream(node.path).use { stream ->
                        for (child in stream) {
                            if (entries.size >= limit) {
                                truncated = true
                                break
                            }
                            val childNode = validateExisting(child, null, "list-directory")
                            val metadata = metadataFor(childNode.path, childNode.kind, childNode.name, "list-directory")
                            entries += KWebDirectoryEntry(
                                metadata.name,
                                metadata.kind,
                                metadata.sizeBytes,
                                metadata.lastModifiedEpochMillis,
                            )
                        }
                    }
                } catch (error: KWebNativeException) {
                    throw error
                } catch (error: Exception) {
                    throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "list-directory")
                }
                KWebDirectoryListing(entries.sortedBy { it.name }, truncated)
            }
        }

    override suspend fun metadata(handle: KWebFileHandle): KWebFileMetadata =
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val node = requireNode(handle, "metadata")
                requireGrant(node, KWebFileGrant.METADATA, "metadata")
                metadataFor(node.path, node.kind, node.name, "metadata")
            }
        }

    override suspend fun copyFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sourceNode = requireFile(source, "copy-file")
            requireGrant(sourceNode, KWebFileGrant.COPY, "copy-file")
            val targetNode = requireDirectory(targetDirectory, "copy-file")
            requireGrant(targetNode, KWebFileGrant.CREATE, "copy-file")
            requireFileName(targetName, "targetName")
            val target = childPath(targetNode, targetName, "copy-file")
            ensureTargetAvailable(target, conflict, "copy-file")
            val temporary = try {
                Files.createTempFile(targetNode.path, ".kweb-copy-", ".part")
            } catch (error: Exception) {
                throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "copy-file")
            }
            try {
                Files.copy(sourceNode.path, temporary, NOFOLLOW_LINKS, COPY_ATTRIBUTES, REPLACE_EXISTING)
                atomicPublish(temporary, target, conflict, "copy-file")
                register(Node(
                    target,
                    KWebFileNodeKind.FILE,
                    sourceNode.grants,
                    targetNode.workspace,
                    targetNode.workspaceId,
                    openCapabilityChannel(target, sourceNode.grants, "copy-file"),
                ))
            } catch (error: KWebNativeException) {
                throw error
            } catch (error: Exception) {
                throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "copy-file")
            } finally {
                try { Files.deleteIfExists(temporary) } catch (_: Exception) { }
            }
        }
    }

    override suspend fun moveFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val sourceNode = requireFile(source, "move-file")
            requireGrant(sourceNode, KWebFileGrant.MOVE, "move-file")
            val targetNode = requireDirectory(targetDirectory, "move-file")
            requireGrant(targetNode, KWebFileGrant.CREATE, "move-file")
            if (sourceNode.workspaceId != targetNode.workspaceId) {
                throw failure(
                    KWebFilesErrorCode.ATOMIC_MOVE_UNAVAILABLE,
                    "A move cannot cross logical workspace boundaries.",
                    "move-file",
                )
            }
            requireFileName(targetName, "targetName")
            val target = childPath(targetNode, targetName, "move-file")
            ensureTargetAvailable(target, conflict, "move-file")
            try {
                val options = buildList {
                    add(ATOMIC_MOVE)
                    if (conflict == KWebFileConflictPolicy.REPLACE) add(REPLACE_EXISTING)
                }.toTypedArray()
                Files.move(sourceNode.path, target, *options)
            } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
                throw failure(KWebFilesErrorCode.ATOMIC_MOVE_UNAVAILABLE, "The provider cannot perform an atomic move.", "move-file", error)
            } catch (error: Exception) {
                throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "move-file")
            }
            closeAndRemove(sourceNode.token)
            register(Node(
                target,
                KWebFileNodeKind.FILE,
                sourceNode.grants,
                targetNode.workspace,
                targetNode.workspaceId,
                openCapabilityChannel(target, sourceNode.grants, "move-file"),
            ))
        }
    }

    override fun watchDirectory(handle: KWebFileHandle): Flow<KWebFileWatchEvent> = flow {
        val node = synchronized(lock) {
            requireOpen("watch-directory")
            val value = requireDirectory(handle, "watch-directory")
            requireGrant(value, KWebFileGrant.WATCH, "watch-directory")
            value
        }
        val service = try {
            FileSystems.getDefault().newWatchService()
        } catch (error: Exception) {
            throw failure(KWebFilesErrorCode.PLATFORM_UNAVAILABLE, "The filesystem provider has no usable watch service.", "watch-directory", error)
        }
        synchronized(lock) { watchers += service }
        val key = try {
            node.path.register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
        } catch (error: Exception) {
            synchronized(lock) { watchers -= service }
            try { service.close() } catch (_: Exception) { }
            throw mapIo(error, KWebFilesErrorCode.PLATFORM_UNAVAILABLE, "watch-directory")
        }
        var sequence = 1L
        try {
            while (currentCoroutineContext().isActive) {
                val next = try {
                    withContext(Dispatchers.IO) { service.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
                } catch (_: java.nio.file.ClosedWatchServiceException) {
                    break
                }
                    ?: continue
                val events = next.pollEvents()
                // Polling and native WatchService implementations may coalesce
                // create/modify events; sequence order remains the contract.
                for (event in events) {
                    if (event.kind() == OVERFLOW) {
                        emit(KWebFileWatchEvent(sequence++, KWebFileWatchKind.OVERFLOW, null))
                        throw failure(KWebFilesErrorCode.WATCH_OVERFLOW, "The operating system watch queue overflowed.", "watch-directory")
                    }
                    val context = event.context() as? Path ?: continue
                    val kind = when (event.kind()) {
                        ENTRY_CREATE -> KWebFileWatchKind.CREATED
                        ENTRY_MODIFY -> KWebFileWatchKind.MODIFIED
                        ENTRY_DELETE -> KWebFileWatchKind.DELETED
                        else -> continue
                    }
                    emit(KWebFileWatchEvent(sequence++, kind, context.fileName.toString()))
                }
                if (!next.reset()) break
            }
        } finally {
            key.cancel()
            synchronized(lock) { watchers -= service }
            try { service.close() } catch (_: Exception) { }
        }
    }

    override suspend fun closeHandle(handle: KWebFileHandle) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            requireOpen("close-handle")
            val token = handle.token
            if (handles[token] == null) throw failure(KWebFilesErrorCode.HANDLE_NOT_FOUND, "The file handle is not open.", "close-handle")
            closeAndRemove(token)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (mutableLifecycle.value == KWebLifecycleState.CLOSED) return
            if (mutableLifecycle.value != KWebLifecycleState.OPEN) return
            mutableLifecycle.value = KWebLifecycleState.CLOSING
            watchers.toList().forEach { service -> try { service.close() } catch (_: Exception) { } }
            watchers.clear()
            handles.values.toList().forEach { node -> try { node.channel?.close() } catch (_: Exception) { } }
            handles.clear()
            mutableLifecycle.value = KWebLifecycleState.CLOSED
        }
    }

    private fun register(node: Node): KWebFileCapabilityDescriptor {
        val token = nextToken()
        val stored = node.copy(token = token)
        handles[token] = stored
        return descriptor(stored)
    }

    private fun descriptor(node: Node): KWebFileCapabilityDescriptor = KWebFileCapabilityDescriptor(
        handle = node.token,
        kind = node.kind,
        name = node.name,
        grants = node.grants,
    )

    private fun nextToken(): String {
        while (true) {
            val bytes = ByteArray(32)
            random.nextBytes(bytes)
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            if (token !in handles) return token
        }
    }

    private fun requireNode(handle: KWebFileHandle, operation: String): Node =
        handles[handle.token] ?: throw failure(KWebFilesErrorCode.HANDLE_NOT_FOUND, "The file handle is not open.", operation)

    private fun requireFile(handle: KWebFileHandle, operation: String): Node {
        val node = requireNode(handle, operation)
        if (node.kind != KWebFileNodeKind.FILE) throw failure(KWebFilesErrorCode.HANDLE_KIND, "The handle is not a file.", operation)
        return node
    }

    private fun requireDirectory(handle: KWebFileHandle, operation: String): Node {
        val node = requireNode(handle, operation)
        if (node.kind != KWebFileNodeKind.DIRECTORY) throw failure(KWebFilesErrorCode.HANDLE_KIND, "The handle is not a directory.", operation)
        return node
    }

    private fun requireGrant(node: Node, grant: KWebFileGrant, operation: String) {
        if (grant !in node.grants) throw failure(KWebFilesErrorCode.HANDLE_GRANT, "The handle does not grant '$grant'.", operation)
    }

    private fun requireChannel(node: Node, operation: String): FileChannel =
        node.channel ?: throw failure(KWebFilesErrorCode.HANDLE_GRANT, "The file handle has no channel for '$operation'.", operation)

    private fun childPath(parent: Node, name: String, operation: String): Path {
        requireFileName(name, "name")
        validateExisting(parent.path, KWebFileNodeKind.DIRECTORY, operation)
        val parentReal = try {
            parent.path.toRealPath(NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw mapIo(error, KWebFilesErrorCode.ROOT_ESCAPE, operation)
        }
        if (!parentReal.startsWith(parent.workspace.root)) {
            throw failure(KWebFilesErrorCode.ROOT_ESCAPE, "The requested child escapes the workspace root.", operation)
        }
        val child = parentReal.resolve(name).normalize()
        if (!child.startsWith(parent.workspace.root)) throw failure(KWebFilesErrorCode.ROOT_ESCAPE, "The requested child escapes the workspace root.", operation)
        return child
    }

    private fun validateExisting(path: Path, expected: KWebFileNodeKind?, operation: String): ExistingNode {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw mapIo(error, KWebFilesErrorCode.ROOT_ESCAPE, operation)
        }
        if (attributes.isSymbolicLink || attributes.isOther || isReparse(path)) {
            throw failure(KWebFilesErrorCode.ROOT_ESCAPE, "Symbolic links and reparse targets are not allowed.", operation)
        }
        val kind = when {
            attributes.isRegularFile -> KWebFileNodeKind.FILE
            attributes.isDirectory -> KWebFileNodeKind.DIRECTORY
            else -> throw failure(KWebFilesErrorCode.ROOT_ESCAPE, "The filesystem node type is not supported.", operation)
        }
        if (expected != null && kind != expected) throw failure(KWebFilesErrorCode.HANDLE_KIND, "The filesystem node type is not permitted.", operation)
        return ExistingNode(path, kind, requireNotNull(path.fileName).toString())
    }

    private fun metadataFor(path: Path, kind: KWebFileNodeKind, name: String, operation: String): KWebFileMetadata {
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, operation)
        }
        if (attributes.isSymbolicLink || attributes.isOther || isReparse(path)) {
            throw failure(KWebFilesErrorCode.ROOT_ESCAPE, "The filesystem node changed to a link or reparse target.", operation)
        }
        return KWebFileMetadata(kind, name, if (kind == KWebFileNodeKind.FILE) attributes.size() else null, attributes.lastModifiedTime().toMillis().coerceAtLeast(0))
    }

    private fun openFileChannel(path: Path, request: KWebFileOpenRequest, operation: String): FileChannel {
        val exists = Files.exists(path, NOFOLLOW_LINKS)
        if (exists) validateExisting(path, KWebFileNodeKind.FILE, operation)
        if (!exists && !request.createIfMissing) throw failure(KWebFilesErrorCode.HANDLE_NOT_FOUND, "The requested file does not exist.", operation)
        val options = buildList<java.nio.file.OpenOption> {
            when (request.mode) {
                KWebFileOpenMode.READ -> add(READ)
                KWebFileOpenMode.WRITE -> add(WRITE)
                KWebFileOpenMode.READ_WRITE -> { add(READ); add(WRITE) }
            }
            add(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
        try {
            val channel = if (exists) {
                FileChannel.open(path, *options.toTypedArray())
            } else {
                FileChannel.open(path, *(options + CREATE_NEW).toTypedArray())
            }
            validateExisting(path, KWebFileNodeKind.FILE, operation)
            return channel
        } catch (error: FileAlreadyExistsException) {
            validateExisting(path, KWebFileNodeKind.FILE, operation)
            return FileChannel.open(path, *options.toTypedArray())
        } catch (error: Exception) {
            throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, operation)
        }
    }

    private fun openCapabilityChannel(path: Path, grants: Set<KWebFileGrant>, operation: String): FileChannel? {
        val options = buildList<java.nio.file.OpenOption> {
            if (KWebFileGrant.READ in grants) add(READ)
            if (KWebFileGrant.WRITE in grants) add(WRITE)
            add(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        }
        if (options.size == 1) return null
        return try {
            FileChannel.open(path, *options.toTypedArray())
        } catch (error: Exception) {
            throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, operation)
        }
    }

    private fun fileGrants(parent: Node, mode: KWebFileOpenMode): Set<KWebFileGrant> = buildSet {
        if (mode == KWebFileOpenMode.READ || mode == KWebFileOpenMode.READ_WRITE) if (KWebFileGrant.READ in parent.grants) add(KWebFileGrant.READ)
        if (mode == KWebFileOpenMode.WRITE || mode == KWebFileOpenMode.READ_WRITE) if (KWebFileGrant.WRITE in parent.grants) add(KWebFileGrant.WRITE)
        setOf(KWebFileGrant.METADATA, KWebFileGrant.COPY, KWebFileGrant.MOVE).forEach { if (it in parent.grants) add(it) }
    }

    private fun ensureTargetAvailable(target: Path, conflict: KWebFileConflictPolicy, operation: String) {
        if (Files.exists(target, NOFOLLOW_LINKS)) {
            validateExisting(target, KWebFileNodeKind.FILE, operation)
            if (conflict == KWebFileConflictPolicy.FAIL) throw failure(KWebFilesErrorCode.CONFLICT, "The destination already exists.", operation)
        }
    }

    private fun atomicPublish(temporary: Path, target: Path, conflict: KWebFileConflictPolicy, operation: String) {
        try {
            val options = buildList {
                add(ATOMIC_MOVE)
                if (conflict == KWebFileConflictPolicy.REPLACE) add(REPLACE_EXISTING)
            }.toTypedArray()
            Files.move(temporary, target, *options)
        } catch (error: java.nio.file.AtomicMoveNotSupportedException) {
            throw failure(KWebFilesErrorCode.ATOMIC_MOVE_UNAVAILABLE, "The provider cannot atomically publish the destination.", operation, error)
        }
    }

    private fun closeAndRemove(token: String) {
        val node = handles.remove(token) ?: return
        try { node.channel?.close() } catch (error: Exception) {
            throw mapIo(error, KWebServiceFallback.NATIVE_FAILED, "close-handle")
        }
    }

    private fun requireOffset(offset: Long, length: Int, operation: String) {
        if (offset < 0 || length !in 0..KWEB_FILES_MAX_TRANSFER_BYTES || offset > Long.MAX_VALUE - length) {
            throw failure(KWebFilesErrorCode.IO_BOUNDS, "The file range is outside the bounded range.", operation)
        }
    }

    private fun requireOpen(operation: String) {
        if (mutableLifecycle.value != KWebLifecycleState.OPEN) throw failure("service.owner-closed", "The file service is not open.", operation)
    }

    private fun isReparse(path: Path): Boolean = try {
        val attributes = Files.readAttributes(path, DosFileAttributes::class.java, NOFOLLOW_LINKS)
        attributes.isOther
    } catch (_: Exception) {
        false
    }

    private fun failure(code: String, message: String, operation: String, cause: Throwable? = null): KWebNativeException =
        KWebNativeException(
            code = code,
            details = mapOf("service" to descriptor.id, "operation" to operation),
            message = message,
            cause = cause,
        )

    private fun mapIo(error: Exception, fallback: String, operation: String): KWebNativeException = when (error) {
        is KWebNativeException -> error
        is java.nio.file.AccessDeniedException -> failure("service.native-failed", "The filesystem provider denied access.", operation, error)
        is java.nio.file.NoSuchFileException -> failure(KWebFilesErrorCode.HANDLE_NOT_FOUND, "The filesystem node no longer exists.", operation, error)
        is java.nio.file.InvalidPathException -> failure(KWebFilesErrorCode.NAME_INVALID, "The filesystem name is invalid.", operation, error)
        is FileSystemException -> failure(fallback, "The filesystem provider rejected the operation.", operation, error)
        else -> failure(fallback, "The filesystem operation failed.", operation, error)
    }

    private data class CanonicalWorkspace(val id: String, val root: Path, val grants: Set<KWebFileGrant>)
    private data class ExistingNode(val path: Path, val kind: KWebFileNodeKind, val name: String)
    private data class Node(
        val path: Path,
        val kind: KWebFileNodeKind,
        val grants: Set<KWebFileGrant>,
        val workspace: CanonicalWorkspace,
        val workspaceId: String,
        val channel: FileChannel? = null,
        val token: String = "",
        val name: String = path.fileName?.toString() ?: workspaceId,
    )

    private object KWebServiceFallback {
        const val NATIVE_FAILED: String = "service.native-failed"
    }
}

private fun canonicalRoot(path: Path): Path {
    if (!path.isAbsolute || !Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
        throw KWebNativeException(
            code = KWebFilesErrorCode.WORKSPACE_INVALID,
            details = emptyMap(),
            message = "A file workspace root must be an existing absolute non-link directory.",
        )
    }
    return try {
        path.toRealPath(NOFOLLOW_LINKS)
    } catch (error: Exception) {
        throw KWebNativeException(
            code = KWebFilesErrorCode.WORKSPACE_INVALID,
            details = emptyMap(),
            message = "The file workspace root could not be canonicalized.",
            cause = error,
        )
    }
}

private val WORKSPACE_ID = Regex("[a-z][a-z0-9-]{0,63}")
