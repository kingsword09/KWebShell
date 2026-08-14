package io.github.kingsword09.kwebshell.extensions

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class JvmKWebExtensionStoreOperation {
    INSTALL,
    UPDATE,
    RELOAD,
    UNINSTALL,
}

internal data class JvmKWebManagedExtension(
    val packageInfo: KWebExtensionPackage,
    val sourceFormat: KWebExtensionPackageFormat,
    val contentDigest: String,
    val directory: Path,
)

internal data class JvmKWebExtensionStoreTransaction(
    val token: String,
    val operation: JvmKWebExtensionStoreOperation,
    val extension: JvmKWebManagedExtension,
    val previousContentDigest: String?,
)

internal class JvmKWebExtensionProfileStore private constructor(
    internal val root: Path,
    private val permissionPolicy: KWebExtensionPermissionPolicy,
) {
    fun prepareUnpacked(source: Path): JvmKWebExtensionStoreTransaction = withStoreLock {
        val sourceSnapshot = JvmKWebExtensionPackageVerifier.verifyUnpacked(source, permissionPolicy)
        val sourceDigestBefore = treeDigest(sourceSnapshot.source) { sourceChanged(sourceSnapshot.source) }
        val staging = createStagingDirectory()
        try {
            val payload = staging.resolve(PAYLOAD_DIRECTORY)
            copyUnpackedTree(sourceSnapshot.source, payload)
            val sourceDigestAfter = treeDigest(sourceSnapshot.source) { sourceChanged(sourceSnapshot.source) }
            val managed = JvmKWebExtensionPackageVerifier.verifyUnpacked(payload, permissionPolicy)
            val managedDigest = treeDigest(payload)
            if (sourceDigestBefore != sourceDigestAfter || sourceDigestAfter != managedDigest ||
                sourceSnapshot.packageInfo != managed.packageInfo
            ) {
                storeFailure(
                    code = "extensions.store.source-changed",
                    details = mapOf("source" to sourceSnapshot.source.toString()),
                    message = "The unpacked extension changed while its managed snapshot was being created.",
                )
            }
            val stored = commitObject(
                staging = staging,
                payload = payload,
                verified = managed,
                sourceFormat = KWebExtensionPackageFormat.UNPACKED,
                digest = managedDigest,
            )
            createProvisionTransaction(stored)
        } finally {
            deleteIfPresent(staging)
        }
    }

    fun prepareCrx3(source: Path): JvmKWebExtensionStoreTransaction = withStoreLock {
        val material = JvmKWebExtensionPackageVerifier.verifyCrx3Material(source, permissionPolicy)
        val staging = createStagingDirectory()
        try {
            val payload = staging.resolve(PAYLOAD_DIRECTORY)
            extractVerifiedArchive(material.archive, payload)
            deleteIfPresent(payload.resolve(CHROMIUM_METADATA_DIRECTORY))

            val sourcePackage = material.verifiedExtension.packageInfo
            val managedManifest = sourcePackage.manifest.copy(key = sourcePackage.publicKeyBase64)
            writeForcedFile(
                payload.resolve(MANIFEST_FILE_NAME),
                MANAGED_MANIFEST_JSON.encodeToString(managedManifest).toByteArray(StandardCharsets.UTF_8),
                replace = true,
            )
            val managed = JvmKWebExtensionPackageVerifier.verifyUnpacked(payload, permissionPolicy)
            if (managed.packageInfo.extensionId != sourcePackage.extensionId ||
                managed.packageInfo.publicKeyBase64 != sourcePackage.publicKeyBase64 ||
                managed.packageInfo.manifest != managedManifest ||
                managed.packageInfo.permissionReview != sourcePackage.permissionReview
            ) {
                storeFailure(
                    code = "extensions.store.crx3-snapshot-mismatch",
                    details = mapOf("source" to material.verifiedExtension.source.toString()),
                    message = "The managed CRX3 snapshot does not preserve the verified package identity.",
                )
            }
            val managedDigest = treeDigest(payload)
            val stored = commitObject(
                staging = staging,
                payload = payload,
                verified = managed,
                sourceFormat = KWebExtensionPackageFormat.CRX3,
                digest = managedDigest,
            )
            createProvisionTransaction(stored)
        } finally {
            deleteIfPresent(staging)
        }
    }

    fun prepareUninstall(extensionId: String): JvmKWebExtensionStoreTransaction = withStoreLock {
        requireExtensionId(extensionId)
        requireNoPendingTransaction(extensionId)
        val active = readActiveLocked(extensionId) ?: storeFailure(
            code = "extensions.store.extension-not-active",
            details = mapOf("extensionId" to extensionId),
            message = "The extension cannot be uninstalled because it is not active in this Profile store.",
        )
        val record = StoreTransactionRecord(
            schemaVersion = STORE_SCHEMA_VERSION,
            token = UUID.randomUUID().toString(),
            operation = StoreOperationRecord.UNINSTALL,
            extension = active.toRecord(),
            previousContentDigest = active.contentDigest,
        )
        writeTransactionRecord(record)
        transactionToModel(record)
    }

    fun commit(token: String): JvmKWebManagedExtension? = withStoreLock {
        val transaction = readTransactionLocked(token)
        val active = readActiveLocked(transaction.extension.extensionId)
        requireExpectedActive(transaction, active)
        val result = when (transaction.operation) {
            StoreOperationRecord.INSTALL,
            StoreOperationRecord.UPDATE,
            StoreOperationRecord.RELOAD,
            -> {
                val managed = validateManagedObject(transaction.extension)
                writeActiveRecord(transaction.extension, replace = active != null)
                managed
            }
            StoreOperationRecord.UNINSTALL -> {
                val activePath = activeRecordPath(transaction.extension.extensionId)
                if (!Files.deleteIfExists(activePath)) {
                    storeFailure(
                        code = "extensions.store.active-record-missing",
                        details = mapOf("extensionId" to transaction.extension.extensionId),
                        message = "The active extension record disappeared during uninstall commit.",
                    )
                }
                null
            }
        }
        deleteTransactionRecord(transaction.token)
        result
    }

    fun abort(token: String) = withStoreLock {
        val transaction = readTransactionLocked(token)
        val active = readActiveLocked(transaction.extension.extensionId)
        requireExpectedActive(transaction, active)
        deleteTransactionRecord(transaction.token)
    }

    fun active(extensionId: String): JvmKWebManagedExtension? = withStoreLock {
        requireExtensionId(extensionId)
        readActiveLocked(extensionId)
    }

    fun pendingTransactions(): List<JvmKWebExtensionStoreTransaction> = withStoreLock {
        readTransactionsLocked().map(::transactionToModel)
    }

    fun collectGarbage(): List<Path> = withStoreLock {
        val pending = readTransactionsLocked()
        if (pending.isNotEmpty()) {
            storeFailure(
                code = "extensions.store.transactions-pending",
                details = mapOf("count" to pending.size.toString()),
                message = "Managed extension objects cannot be collected while lifecycle transactions are pending.",
            )
        }
        val activePaths = readActiveRecordsLocked()
            .mapTo(mutableSetOf()) { it.objectPath }
        val deleted = mutableListOf<Path>()
        listObjectDirectories().forEach { objectDirectory ->
            val relative = root.relativize(objectDirectory).invariantPath()
            val record = activeRecordFromObjectPath(relative)
            validateManagedObject(record)
            if (relative !in activePaths) {
                secureDelete(objectDirectory)
                deleted.add(objectDirectory)
            }
        }
        removeEmptyObjectParents()
        deleted.sortedBy(Path::toString)
    }

    private fun initializeLocked() {
        ensureDirectory(stagingRoot)
        ensureDirectory(objectsRoot)
        ensureDirectory(activeRoot)
        ensureDirectory(transactionsRoot)
        validateRootLayout()
        listObjectDirectories()
        clearDirectory(stagingRoot)
        removeTemporaryMetadata(activeRoot)
        removeTemporaryMetadata(transactionsRoot)
        readActiveRecordsLocked()
        recoverTransactionsLocked()
    }

    private fun createProvisionTransaction(extension: JvmKWebManagedExtension): JvmKWebExtensionStoreTransaction {
        val extensionId = extension.packageInfo.extensionId
        requireNoPendingTransaction(extensionId)
        val active = readActiveLocked(extensionId)
        val operation = when {
            active == null -> JvmKWebExtensionStoreOperation.INSTALL
            active.contentDigest == extension.contentDigest -> JvmKWebExtensionStoreOperation.RELOAD
            compareVersions(extension.packageInfo.manifest.version, active.packageInfo.manifest.version) < 0 -> {
                storeFailure(
                    code = "extensions.store.version-downgrade",
                    details = mapOf(
                        "extensionId" to extensionId,
                        "activeVersion" to active.packageInfo.manifest.version,
                        "requestedVersion" to extension.packageInfo.manifest.version,
                    ),
                    message = "A managed extension update cannot downgrade the active version.",
                )
            }
            compareVersions(extension.packageInfo.manifest.version, active.packageInfo.manifest.version) == 0 -> {
                storeFailure(
                    code = "extensions.store.version-content-conflict",
                    details = mapOf(
                        "extensionId" to extensionId,
                        "version" to extension.packageInfo.manifest.version,
                    ),
                    message = "The same extension version cannot identify different managed content.",
                )
            }
            else -> JvmKWebExtensionStoreOperation.UPDATE
        }
        val record = StoreTransactionRecord(
            schemaVersion = STORE_SCHEMA_VERSION,
            token = UUID.randomUUID().toString(),
            operation = StoreOperationRecord.fromModel(operation),
            extension = extension.toRecord(),
            previousContentDigest = active?.contentDigest,
        )
        writeTransactionRecord(record)
        return transactionToModel(record)
    }

    private fun commitObject(
        staging: Path,
        payload: Path,
        verified: KWebVerifiedExtension,
        sourceFormat: KWebExtensionPackageFormat,
        digest: String,
    ): JvmKWebManagedExtension {
        val packageInfo = verified.packageInfo
        val destination = objectDirectory(
            packageInfo.extensionId,
            packageInfo.manifest.version,
            digest,
        )
        ensureDirectory(destination.parent.parent)
        ensureDirectory(destination.parent)
        val record = JvmKWebManagedExtension(packageInfo, sourceFormat, digest, destination).toRecord()
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return validateManagedObject(record)
        }
        atomicMove(payload, destination, replace = false)
        deleteIfPresent(staging)
        return validateManagedObject(record)
    }

    private fun writeActiveRecord(record: StoreObjectRecord, replace: Boolean) {
        writeAtomicJson(activeRecordPath(record.extensionId), record, replace)
    }

    private fun writeTransactionRecord(record: StoreTransactionRecord) {
        writeAtomicJson(transactionRecordPath(record.token), record, replace = false)
    }

    private inline fun <reified T> writeAtomicJson(target: Path, value: T, replace: Boolean) {
        val bytes = STORE_JSON.encodeToString(value).toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_METADATA_BYTES) {
            storeFailure(
                code = "extensions.store.metadata-too-large",
                details = mapOf("path" to target.toString(), "size" to bytes.size.toString()),
                message = "Managed extension metadata exceeds its bounded size.",
            )
        }
        val temporary = Files.createTempFile(target.parent, TEMPORARY_PREFIX, METADATA_SUFFIX)
        try {
            writeForcedFile(temporary, bytes, replace = true)
            atomicMove(temporary, target, replace)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun readActiveLocked(extensionId: String): JvmKWebManagedExtension? {
        val path = activeRecordPath(extensionId)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val record = readMetadata<StoreObjectRecord>(path)
        if (path.fileName.toString() != "${record.extensionId}$METADATA_SUFFIX") metadataInvalid(path)
        return validateManagedObject(record)
    }

    private fun readActiveRecordsLocked(): List<StoreObjectRecord> = metadataFiles(activeRoot).map { path ->
        val record = readMetadata<StoreObjectRecord>(path)
        if (path.fileName.toString() != "${record.extensionId}$METADATA_SUFFIX") metadataInvalid(path)
        validateManagedObject(record)
        record
    }

    private fun readTransactionLocked(token: String): StoreTransactionRecord {
        requireTransactionToken(token)
        val path = transactionRecordPath(token)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            storeFailure(
                code = "extensions.store.transaction-not-found",
                details = mapOf("token" to token),
                message = "The managed extension transaction does not exist.",
            )
        }
        return readMetadata<StoreTransactionRecord>(path).also { validateTransactionRecord(path, it) }
    }

    private fun readTransactionsLocked(): List<StoreTransactionRecord> {
        val records = metadataFiles(transactionsRoot).map { path ->
            readMetadata<StoreTransactionRecord>(path).also { validateTransactionRecord(path, it) }
        }
        if (records.groupingBy { it.extension.extensionId }.eachCount().any { it.value > 1 }) {
            metadataInvalid(transactionsRoot)
        }
        return records
    }

    private fun validateTransactionRecord(path: Path, record: StoreTransactionRecord) {
        if (record.schemaVersion != STORE_SCHEMA_VERSION ||
            path.fileName.toString() != "${record.token}$METADATA_SUFFIX"
        ) {
            metadataInvalid(path)
        }
        requireTransactionToken(record.token)
        validateManagedObject(record.extension)
        record.previousContentDigest?.let(::requireDigest)
        when (record.operation) {
            StoreOperationRecord.INSTALL -> {
                if (record.previousContentDigest != null) metadataInvalid(path)
            }
            StoreOperationRecord.UPDATE -> {
                if (record.previousContentDigest == null ||
                    record.previousContentDigest == record.extension.contentDigest
                ) {
                    metadataInvalid(path)
                }
            }
            StoreOperationRecord.RELOAD,
            StoreOperationRecord.UNINSTALL,
            -> {
                if (record.previousContentDigest != record.extension.contentDigest) metadataInvalid(path)
            }
        }
    }

    private fun validateManagedObject(record: StoreObjectRecord): JvmKWebManagedExtension {
        if (record.schemaVersion != STORE_SCHEMA_VERSION) metadataInvalid(root.resolve(record.objectPath))
        requireExtensionId(record.extensionId)
        requireDigest(record.contentDigest)
        val expectedRelative = objectRelativePath(record.extensionId, record.version, record.contentDigest)
        if (record.objectPath != expectedRelative) metadataInvalid(root.resolve(record.objectPath))
        val directory = root.resolve(record.objectPath.replace('/', java.io.File.separatorChar)).normalize()
        if (!directory.startsWith(objectsRoot) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            metadataInvalid(directory)
        }
        val verified = JvmKWebExtensionPackageVerifier.verifyUnpacked(directory, permissionPolicy)
        val actualDigest = treeDigest(directory)
        if (verified.packageInfo.extensionId != record.extensionId ||
            verified.packageInfo.manifest.version != record.version ||
            actualDigest != record.contentDigest
        ) {
            storeFailure(
                code = "extensions.store.object-integrity-failed",
                details = mapOf("path" to directory.toString()),
                message = "A managed extension object no longer matches its recorded identity and digest.",
            )
        }
        val sourceFormat = record.sourceFormat.toModel()
        return JvmKWebManagedExtension(
            packageInfo = verified.packageInfo.copy(format = sourceFormat),
            sourceFormat = sourceFormat,
            contentDigest = record.contentDigest,
            directory = directory,
        )
    }

    private fun transactionToModel(record: StoreTransactionRecord): JvmKWebExtensionStoreTransaction =
        JvmKWebExtensionStoreTransaction(
            token = record.token,
            operation = record.operation.toModel(),
            extension = validateManagedObject(record.extension),
            previousContentDigest = record.previousContentDigest,
        )

    private fun recoverTransactionsLocked() {
        val activeById = readActiveRecordsLocked().associateBy(StoreObjectRecord::extensionId)
        readTransactionsLocked().forEach { transaction ->
            val active = activeById[transaction.extension.extensionId]
            val committed = when (transaction.operation) {
                StoreOperationRecord.INSTALL,
                StoreOperationRecord.UPDATE,
                -> active?.contentDigest == transaction.extension.contentDigest
                StoreOperationRecord.UNINSTALL -> active == null
                StoreOperationRecord.RELOAD -> false
            }
            if (committed) {
                deleteTransactionRecord(transaction.token)
            } else {
                requireExpectedActive(transaction, active?.let(::validateManagedObject))
            }
        }
    }

    private fun requireExpectedActive(
        transaction: StoreTransactionRecord,
        active: JvmKWebManagedExtension?,
    ) {
        if (active?.contentDigest != transaction.previousContentDigest) {
            storeFailure(
                code = "extensions.store.transaction-state-conflict",
                details = mapOf(
                    "token" to transaction.token,
                    "extensionId" to transaction.extension.extensionId,
                    "expectedDigest" to (transaction.previousContentDigest ?: "absent"),
                    "actualDigest" to (active?.contentDigest ?: "absent"),
                ),
                message = "The active extension state no longer matches the prepared lifecycle transaction.",
            )
        }
        if (transaction.operation == StoreOperationRecord.UPDATE && active != null &&
            compareVersions(transaction.extension.version, active.packageInfo.manifest.version) <= 0
        ) {
            storeFailure(
                code = "extensions.store.transaction-state-conflict",
                details = mapOf(
                    "token" to transaction.token,
                    "extensionId" to transaction.extension.extensionId,
                    "activeVersion" to active.packageInfo.manifest.version,
                    "requestedVersion" to transaction.extension.version,
                ),
                message = "The prepared update no longer represents a strictly newer extension version.",
            )
        }
    }

    private fun requireNoPendingTransaction(extensionId: String) {
        val conflict = readTransactionsLocked().firstOrNull { it.extension.extensionId == extensionId }
        if (conflict != null) {
            storeFailure(
                code = "extensions.store.transaction-conflict",
                details = mapOf("extensionId" to extensionId, "token" to conflict.token),
                message = "Only one managed lifecycle transaction may be pending for an extension.",
            )
        }
    }

    private fun deleteTransactionRecord(token: String) {
        if (!Files.deleteIfExists(transactionRecordPath(token))) {
            storeFailure(
                code = "extensions.store.transaction-not-found",
                details = mapOf("token" to token),
                message = "The managed extension transaction disappeared before it could be closed.",
            )
        }
    }

    private fun listObjectDirectories(): List<Path> {
        val objects = mutableListOf<Path>()
        directoryEntries(objectsRoot).forEach { idDirectory ->
            requireManagedDirectory(idDirectory)
            requireExtensionId(idDirectory.fileName.toString())
            directoryEntries(idDirectory).forEach { versionDirectory ->
                requireManagedDirectory(versionDirectory)
                directoryEntries(versionDirectory).forEach { digestDirectory ->
                    requireManagedDirectory(digestDirectory)
                    requireDigest(digestDirectory.fileName.toString())
                    objects.add(digestDirectory)
                }
            }
        }
        return objects.sortedBy(Path::toString)
    }

    private fun removeEmptyObjectParents() {
        directoryEntries(objectsRoot).forEach { idDirectory ->
            directoryEntries(idDirectory).forEach { versionDirectory ->
                if (directoryEntries(versionDirectory).isEmpty()) Files.delete(versionDirectory)
            }
            if (directoryEntries(idDirectory).isEmpty()) Files.delete(idDirectory)
        }
    }

    private fun activeRecordFromObjectPath(relative: String): StoreObjectRecord {
        val parts = relative.split('/')
        if (parts.size != 4 || parts[0] != OBJECTS_DIRECTORY) metadataInvalid(root.resolve(relative))
        return StoreObjectRecord(
            schemaVersion = STORE_SCHEMA_VERSION,
            extensionId = parts[1],
            version = parts[2],
            contentDigest = parts[3],
            objectPath = relative,
            sourceFormat = StoreSourceFormatRecord.UNPACKED,
        )
    }

    private fun createStagingDirectory(): Path = Files.createTempDirectory(stagingRoot, STAGING_PREFIX)

    private fun copyUnpackedTree(source: Path, destination: Path) {
        ensureDirectory(destination)
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir) || !attrs.isDirectory) sourceChanged(source)
                if (dir != source) ensureDirectory(destination.resolve(source.relativize(dir).toString()))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile) sourceChanged(source)
                copyRegularFile(file, destination.resolve(source.relativize(file).toString()), attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun extractVerifiedArchive(archive: ByteArray, destination: Path) {
        ensureDirectory(destination)
        ZipInputStream(ByteArrayInputStream(archive), ZIP_LEGACY_CHARSET).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name.replace('/', java.io.File.separatorChar)).normalize()
                if (!target.startsWith(destination)) {
                    storeFailure(
                        code = "extensions.store.archive-path-escape",
                        details = mapOf("entry" to entry.name),
                        message = "A verified CRX3 entry escaped the managed staging directory.",
                    )
                }
                if (entry.isDirectory) {
                    ensureDirectory(target)
                } else {
                    ensureDirectory(target.parent)
                    FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            writeAll(output, ByteBuffer.wrap(buffer, 0, count))
                        }
                        output.force(true)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun copyRegularFile(source: Path, destination: Path, expectedSize: Long) {
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            if (input.size() != expectedSize) sourceChanged(source)
            FileChannel.open(
                destination,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                var copied = 0L
                while (true) {
                    buffer.clear()
                    val count = input.read(buffer)
                    if (count < 0) break
                    copied += count
                    buffer.flip()
                    writeAll(output, buffer)
                }
                if (copied != expectedSize || input.size() != expectedSize) sourceChanged(source)
                output.force(true)
            }
        }
    }

    private fun treeDigest(
        directory: Path,
        invalidEntry: (Path) -> Nothing = ::objectIntegrityFailure,
    ): String {
        val entries = mutableListOf<DigestEntry>()
        Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(dir) || !attrs.isDirectory) invalidEntry(dir)
                if (dir != directory) entries += DigestEntry(dir, true, 0L)
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (Files.isSymbolicLink(file) || !attrs.isRegularFile) invalidEntry(file)
                entries += DigestEntry(file, false, attrs.size())
                return FileVisitResult.CONTINUE
            }
        })
        val digest = MessageDigest.getInstance("SHA-256")
        entries.sortedBy { directory.relativize(it.path).invariantPath() }.forEach { entry ->
            val relative = directory.relativize(entry.path).invariantPath()
            digest.update(if (entry.directory) DIRECTORY_TAG else FILE_TAG)
            digest.update(relative.toByteArray(StandardCharsets.UTF_8))
            digest.update(SEPARATOR)
            if (!entry.directory) {
                digest.update(entry.size.toString().toByteArray(StandardCharsets.US_ASCII))
                digest.update(SEPARATOR)
                FileChannel.open(entry.path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
                    if (input.size() != entry.size) invalidEntry(entry.path)
                    val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                    var read = 0L
                    while (true) {
                        buffer.clear()
                        val count = input.read(buffer)
                        if (count < 0) break
                        read += count
                        digest.update(buffer.array(), 0, count)
                    }
                    if (read != entry.size || input.size() != entry.size) invalidEntry(entry.path)
                }
                digest.update(SEPARATOR)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun writeForcedFile(path: Path, bytes: ByteArray, replace: Boolean) {
        val options = mutableSetOf(StandardOpenOption.WRITE)
        if (replace) {
            options += StandardOpenOption.CREATE
            options += StandardOpenOption.TRUNCATE_EXISTING
        } else {
            options += StandardOpenOption.CREATE_NEW
        }
        FileChannel.open(path, options).use { channel ->
            writeAll(channel, ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    private fun writeAll(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    private fun atomicMove(source: Path, target: Path, replace: Boolean) {
        val options = if (replace) {
            arrayOf(StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } else {
            arrayOf(StandardCopyOption.ATOMIC_MOVE)
        }
        try {
            Files.move(source, target, *options)
        } catch (error: AtomicMoveNotSupportedException) {
            storeFailure(
                code = "extensions.store.atomic-move-unsupported",
                details = mapOf("source" to source.toString(), "target" to target.toString()),
                message = "The Profile filesystem does not support the required atomic managed-store move.",
                cause = error,
            )
        } catch (error: FileAlreadyExistsException) {
            storeFailure(
                code = "extensions.store.target-already-exists",
                details = mapOf("target" to target.toString()),
                message = "A managed-store target already exists unexpectedly.",
                cause = error,
            )
        }
    }

    private inline fun <T> withStoreLock(operation: () -> T): T = storeIo(root) {
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) &&
            (Files.isSymbolicLink(lockPath) || !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS))
        ) {
            storeFailure(
                code = "extensions.store.lock-invalid",
                details = mapOf("path" to lockPath.toString()),
                message = "The managed extension store lock must be a regular file and cannot be a symbolic link.",
            )
        }
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            val lock = try {
                channel.tryLock()
            } catch (error: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                storeFailure(
                    code = "extensions.store.lock-unavailable",
                    details = mapOf("path" to lockPath.toString()),
                    message = "Another process or thread owns the Profile extension store lock.",
                )
            }
            lock.use { operation() }
        }
    }

    private inline fun <T> storeIo(path: Path, operation: () -> T): T = try {
        operation()
    } catch (error: KWebExtensionVerificationException) {
        throw error
    } catch (error: Exception) {
        storeFailure(
            code = "extensions.store.io-failed",
            details = mapOf("path" to path.toString()),
            message = "The Profile extension store operation failed.",
            cause = error,
        )
    }

    private inline fun <reified T> readMetadata(path: Path): T {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) metadataInvalid(path)
        val bytes = readMetadataBytes(path)
        val text = try {
            StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            storeFailure(
                code = "extensions.store.metadata-invalid",
                details = mapOf("path" to path.toString()),
                message = "Managed extension metadata is not valid UTF-8.",
                cause = error,
            )
        }
        KWebStrictJsonObjectKeyValidator.duplicateKey(text)?.let { duplicate ->
            storeFailure(
                code = "extensions.store.metadata-duplicate-key",
                details = mapOf("path" to path.toString(), "key" to duplicate),
                message = "Managed extension metadata contains a duplicate JSON key.",
            )
        }
        return try {
            STORE_JSON.decodeFromString<T>(text)
        } catch (error: SerializationException) {
            storeFailure(
                code = "extensions.store.metadata-invalid",
                details = mapOf("path" to path.toString()),
                message = "Managed extension metadata is malformed or has unknown fields.",
                cause = error,
            )
        }
    }

    private fun readMetadataBytes(path: Path): ByteArray =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            if (size <= 0 || size > MAX_METADATA_BYTES) metadataInvalid(path)
            val bytes = ByteArray(size.toInt())
            val target = ByteBuffer.wrap(bytes)
            while (target.hasRemaining()) {
                if (channel.read(target) < 0) metadataInvalid(path)
            }
            val probe = ByteBuffer.allocate(1)
            var trailingRead = channel.read(probe)
            while (trailingRead == 0) trailingRead = channel.read(probe)
            if (trailingRead >= 0) metadataInvalid(path)
            bytes
        }

    private fun validateRootLayout() {
        val allowed = setOf(
            STAGING_DIRECTORY,
            OBJECTS_DIRECTORY,
            ACTIVE_DIRECTORY,
            TRANSACTIONS_DIRECTORY,
            LOCK_FILE_NAME,
        )
        directoryEntries(root).forEach { entry ->
            if (entry.fileName.toString() !in allowed) metadataInvalid(entry)
        }
    }

    private fun metadataFiles(directory: Path): List<Path> = directoryEntries(directory)
        .filter { path ->
            val name = path.fileName.toString()
            if (name.startsWith(TEMPORARY_PREFIX)) return@filter false
            if (!name.endsWith(METADATA_SUFFIX) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                metadataInvalid(path)
            }
            true
        }

    private fun removeTemporaryMetadata(directory: Path) {
        directoryEntries(directory).filter { it.fileName.toString().startsWith(TEMPORARY_PREFIX) }.forEach {
            secureDelete(it)
        }
    }

    private fun clearDirectory(directory: Path) {
        directoryEntries(directory).forEach(::secureDelete)
    }

    private fun ensureDirectory(directory: Path) {
        if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            requireManagedDirectory(directory)
        } else {
            Files.createDirectory(directory)
        }
    }

    private fun requireManagedDirectory(directory: Path) {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            metadataInvalid(directory)
        }
    }

    private fun directoryEntries(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { stream ->
        stream.toList().sortedBy(Path::toString)
    }

    private fun deleteIfPresent(path: Path) {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) secureDelete(path)
    }

    private fun secureDelete(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!normalized.startsWith(root) || normalized == root) {
            storeFailure(
                code = "extensions.store.delete-outside-root",
                details = mapOf("path" to normalized.toString()),
                message = "The managed extension store refused to delete outside its dedicated root.",
            )
        }
        Files.walkFileTree(normalized, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun objectDirectory(extensionId: String, version: String, digest: String): Path =
        root.resolve(objectRelativePath(extensionId, version, digest).replace('/', java.io.File.separatorChar))

    private fun objectRelativePath(extensionId: String, version: String, digest: String): String =
        "$OBJECTS_DIRECTORY/$extensionId/$version/$digest"

    private fun activeRecordPath(extensionId: String): Path = activeRoot.resolve("$extensionId$METADATA_SUFFIX")

    private fun transactionRecordPath(token: String): Path = transactionsRoot.resolve("$token$METADATA_SUFFIX")

    private fun requireExtensionId(value: String) {
        if (!KWebExtensionId.isValid(value)) {
            storeFailure(
                code = "extensions.store.extension-id-invalid",
                details = mapOf("extensionId" to value),
                message = "Managed extension metadata contains an invalid extension ID.",
            )
        }
    }

    private fun requireDigest(value: String) {
        if (!DIGEST_PATTERN.matches(value)) {
            storeFailure(
                code = "extensions.store.digest-invalid",
                details = mapOf("digest" to value),
                message = "Managed extension metadata contains an invalid SHA-256 tree digest.",
            )
        }
    }

    private fun requireTransactionToken(value: String) {
        val valid = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!valid) metadataInvalid(transactionsRoot.resolve("$value$METADATA_SUFFIX"))
    }

    private fun compareVersions(first: String, second: String): Int {
        val firstParts = first.split('.').map(String::toLong)
        val secondParts = second.split('.').map(String::toLong)
        repeat(maxOf(firstParts.size, secondParts.size)) { index ->
            val comparison = firstParts.getOrElse(index) { 0L }.compareTo(secondParts.getOrElse(index) { 0L })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private fun sourceChanged(path: Path): Nothing = storeFailure(
        code = "extensions.store.source-changed",
        details = mapOf("source" to path.toString()),
        message = "The unpacked extension changed while its managed snapshot was being created.",
    )

    private fun objectIntegrityFailure(path: Path): Nothing = storeFailure(
        code = "extensions.store.object-integrity-failed",
        details = mapOf("path" to path.toString()),
        message = "A managed extension object contains a link, special file, or unstable content.",
    )

    private fun metadataInvalid(path: Path): Nothing = storeFailure(
        code = "extensions.store.metadata-invalid",
        details = mapOf("path" to path.toString()),
        message = "The Profile extension store layout or metadata is invalid.",
    )

    private val stagingRoot: Path = root.resolve(STAGING_DIRECTORY)
    private val objectsRoot: Path = root.resolve(OBJECTS_DIRECTORY)
    private val activeRoot: Path = root.resolve(ACTIVE_DIRECTORY)
    private val transactionsRoot: Path = root.resolve(TRANSACTIONS_DIRECTORY)
    private val lockPath: Path = root.resolve(LOCK_FILE_NAME)

    internal companion object {
        fun open(
            root: Path,
            permissionPolicy: KWebExtensionPermissionPolicy = KWebExtensionPermissionPolicy(),
        ): JvmKWebExtensionProfileStore {
            if (!root.isAbsolute) {
                storeFailure(
                    code = "extensions.store.root-relative",
                    details = mapOf("path" to root.toString()),
                    message = "The Profile extension store root must be absolute.",
                )
            }
            val normalized = root.normalize()
            return try {
                if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(normalized)) {
                    storeFailure(
                        code = "extensions.store.root-symlink",
                        details = mapOf("path" to normalized.toString()),
                        message = "The Profile extension store root cannot be a symbolic link.",
                    )
                }
                Files.createDirectories(normalized)
                if (Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
                    storeFailure(
                        code = "extensions.store.root-invalid",
                        details = mapOf("path" to normalized.toString()),
                        message = "The Profile extension store root is not a regular directory.",
                    )
                }
                val store = JvmKWebExtensionProfileStore(normalized.toRealPath(), permissionPolicy)
                store.withStoreLock { store.initializeLocked() }
                store
            } catch (error: KWebExtensionVerificationException) {
                throw error
            } catch (error: Exception) {
                storeFailure(
                    code = "extensions.store.open-failed",
                    details = mapOf("path" to normalized.toString()),
                    message = "The Profile extension store could not be opened.",
                    cause = error,
                )
            }
        }
    }
}

@Serializable
private data class StoreObjectRecord(
    val schemaVersion: Int,
    val extensionId: String,
    val version: String,
    val contentDigest: String,
    val objectPath: String,
    val sourceFormat: StoreSourceFormatRecord,
)

@Serializable
private data class StoreTransactionRecord(
    val schemaVersion: Int,
    val token: String,
    val operation: StoreOperationRecord,
    val extension: StoreObjectRecord,
    val previousContentDigest: String?,
)

@Serializable
private enum class StoreOperationRecord {
    INSTALL,
    UPDATE,
    RELOAD,
    UNINSTALL;

    fun toModel(): JvmKWebExtensionStoreOperation = when (this) {
        INSTALL -> JvmKWebExtensionStoreOperation.INSTALL
        UPDATE -> JvmKWebExtensionStoreOperation.UPDATE
        RELOAD -> JvmKWebExtensionStoreOperation.RELOAD
        UNINSTALL -> JvmKWebExtensionStoreOperation.UNINSTALL
    }

    companion object {
        fun fromModel(value: JvmKWebExtensionStoreOperation): StoreOperationRecord = when (value) {
            JvmKWebExtensionStoreOperation.INSTALL -> INSTALL
            JvmKWebExtensionStoreOperation.UPDATE -> UPDATE
            JvmKWebExtensionStoreOperation.RELOAD -> RELOAD
            JvmKWebExtensionStoreOperation.UNINSTALL -> UNINSTALL
        }
    }
}

@Serializable
private enum class StoreSourceFormatRecord {
    UNPACKED,
    CRX3;

    fun toModel(): KWebExtensionPackageFormat = when (this) {
        UNPACKED -> KWebExtensionPackageFormat.UNPACKED
        CRX3 -> KWebExtensionPackageFormat.CRX3
    }

    companion object {
        fun fromModel(value: KWebExtensionPackageFormat): StoreSourceFormatRecord = when (value) {
            KWebExtensionPackageFormat.UNPACKED -> UNPACKED
            KWebExtensionPackageFormat.CRX3 -> CRX3
        }
    }
}

private fun JvmKWebManagedExtension.toRecord(): StoreObjectRecord = StoreObjectRecord(
    schemaVersion = STORE_SCHEMA_VERSION,
    extensionId = packageInfo.extensionId,
    version = packageInfo.manifest.version,
    contentDigest = contentDigest,
    objectPath = "objects/${packageInfo.extensionId}/${packageInfo.manifest.version}/$contentDigest",
    sourceFormat = StoreSourceFormatRecord.fromModel(sourceFormat),
)

private data class DigestEntry(val path: Path, val directory: Boolean, val size: Long)

private fun Path.invariantPath(): String = toString().replace(java.io.File.separatorChar, '/')

private fun storeFailure(
    code: String,
    details: Map<String, String> = emptyMap(),
    message: String,
    cause: Throwable? = null,
): Nothing = extensionFailure(code, details, message, cause)

private val STORE_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}

private val MANAGED_MANIFEST_JSON = Json {
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    isLenient = false
    allowSpecialFloatingPointValues = false
}

private val ZIP_LEGACY_CHARSET = java.nio.charset.Charset.forName("IBM437")
private val DIGEST_PATTERN = Regex("[0-9a-f]{64}")
private val DIRECTORY_TAG = byteArrayOf('D'.code.toByte(), 0)
private val FILE_TAG = byteArrayOf('F'.code.toByte(), 0)
private val SEPARATOR = byteArrayOf(0)
private const val STORE_SCHEMA_VERSION: Int = 1
private const val MAX_METADATA_BYTES: Long = 64L * 1024
private const val COPY_BUFFER_BYTES: Int = 64 * 1024
private const val STAGING_DIRECTORY: String = ".staging"
private const val OBJECTS_DIRECTORY: String = "objects"
private const val ACTIVE_DIRECTORY: String = "active"
private const val TRANSACTIONS_DIRECTORY: String = "transactions"
private const val PAYLOAD_DIRECTORY: String = "payload"
private const val LOCK_FILE_NAME: String = "store.lock"
private const val METADATA_SUFFIX: String = ".json"
private const val TEMPORARY_PREFIX: String = ".tmp-"
private const val STAGING_PREFIX: String = "provision-"
private const val MANIFEST_FILE_NAME: String = "manifest.json"
private const val CHROMIUM_METADATA_DIRECTORY: String = "_metadata"
