package io.github.kingsword09.kwebshell.extensions

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPair
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JvmKWebExtensionProfileStoreTest {
    @Test
    fun snapshotsUnpackedSourceAndReusesTheImmutableObject() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))

        val first = store.prepareUnpacked(source)

        assertEquals(JvmKWebExtensionStoreOperation.INSTALL, first.operation)
        assertEquals(KWebExtensionPackageFormat.UNPACKED, first.extension.sourceFormat)
        assertEquals(KWebExtensionPackageFormat.UNPACKED, first.extension.packageInfo.format)
        assertNull(first.previousContentDigest)
        assertNull(store.active(extensionId()))
        assertEquals(listOf(first), store.pendingTransactions())
        assertTrue(first.extension.directory.startsWith(store.root.resolve("objects")))

        source.resolve("worker.js").writeText(WORKER_V2)
        assertEquals(WORKER_V1, first.extension.directory.resolve("worker.js").readText())
        store.abort(first.token)

        source.resolve("worker.js").writeText(WORKER_V1)
        val second = store.prepareUnpacked(source)
        assertEquals(first.extension.contentDigest, second.extension.contentDigest)
        assertEquals(first.extension.directory, second.extension.directory)
        store.abort(second.token)
        assertTrue(store.pendingTransactions().isEmpty())
    }

    @Test
    fun commitsAndAbortsInstallReloadUpdateAndUninstall() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))
        val id = extensionId()

        val install = store.prepareUnpacked(source)
        val installed = assertNotNull(store.commit(install.token))
        assertEquals("1.0.0", installed.packageInfo.manifest.version)
        assertEquals(installed, store.active(id))
        assertTrue(store.pendingTransactions().isEmpty())

        val reload = store.prepareUnpacked(source)
        assertEquals(JvmKWebExtensionStoreOperation.RELOAD, reload.operation)
        assertEquals(installed.contentDigest, reload.previousContentDigest)
        store.abort(reload.token)
        assertEquals(installed, store.active(id))

        writeUnpackedPackage(source, version = "2.0.0", worker = WORKER_V2)
        val abortedUpdate = store.prepareUnpacked(source)
        assertEquals(JvmKWebExtensionStoreOperation.UPDATE, abortedUpdate.operation)
        assertEquals(installed.contentDigest, abortedUpdate.previousContentDigest)
        store.abort(abortedUpdate.token)
        assertEquals(installed, store.active(id))

        val update = store.prepareUnpacked(source)
        val updated = assertNotNull(store.commit(update.token))
        assertEquals("2.0.0", updated.packageInfo.manifest.version)
        assertEquals(updated, store.active(id))

        val abortedUninstall = store.prepareUninstall(id)
        assertEquals(JvmKWebExtensionStoreOperation.UNINSTALL, abortedUninstall.operation)
        assertEquals(updated, store.active(id))
        store.abort(abortedUninstall.token)
        assertEquals(updated, store.active(id))

        val uninstall = store.prepareUninstall(id)
        assertNull(store.commit(uninstall.token))
        assertNull(store.active(id))
        assertTrue(store.pendingTransactions().isEmpty())
    }

    @Test
    fun rejectsDowngradesAndSameVersionContentReplacement() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "2.0.0", worker = WORKER_V2)
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))
        val active = assertNotNull(store.commit(store.prepareUnpacked(source).token))

        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        assertCode("extensions.store.version-downgrade") {
            store.prepareUnpacked(source)
        }

        writeUnpackedPackage(source, version = "2.0.0", worker = "console.log('different');")
        assertCode("extensions.store.version-content-conflict") {
            store.prepareUnpacked(source)
        }

        assertEquals(active, store.active(extensionId()))
        assertTrue(store.pendingTransactions().isEmpty())
    }

    @Test
    fun verifiesAndExtractsCrx3WithItsSigningIdentity() = withTempDirectory { root ->
        val crx = root.resolve("fixture.crx")
        val publicKey = Base64.getEncoder().encodeToString(TEST_KEY_PAIR.public.encoded)
        val archive = JvmKWebCrx3TestFixture.zipArchive(
            linkedMapOf(
                "manifest.json" to crxManifest("1.0.0"),
                "worker.js" to WORKER_V1,
                "_metadata/" to "",
                "_metadata/verified_contents.json" to "{}",
            ),
        )
        Files.write(crx, JvmKWebCrx3TestFixture.buildCrx3(TEST_KEY_PAIR, archive))
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))

        val transaction = store.prepareCrx3(crx)

        assertEquals(JvmKWebExtensionStoreOperation.INSTALL, transaction.operation)
        assertEquals(KWebExtensionPackageFormat.CRX3, transaction.extension.sourceFormat)
        assertEquals(KWebExtensionPackageFormat.CRX3, transaction.extension.packageInfo.format)
        assertEquals(extensionId(), transaction.extension.packageInfo.extensionId)
        assertEquals(publicKey, transaction.extension.packageInfo.manifest.key)
        assertFalse(Files.exists(transaction.extension.directory.resolve("_metadata"), LinkOption.NOFOLLOW_LINKS))
        val manifestText = transaction.extension.directory.resolve("manifest.json").readText()
        val manifestJson = Json.parseToJsonElement(manifestText).jsonObject
        assertEquals(publicKey, manifestJson.getValue("key").jsonPrimitive.content)
        assertFalse(manifestText.contains(":null"))

        Files.write(crx, byteArrayOf(1, 2, 3))
        val installed = assertNotNull(store.commit(transaction.token))
        assertEquals(WORKER_V1, installed.directory.resolve("worker.js").readText())
        assertEquals(KWebExtensionPackageFormat.CRX3, store.active(extensionId())?.packageInfo?.format)
    }

    @Test
    fun isolatesObjectsJournalsAndActiveStateByProfile() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val firstStore = JvmKWebExtensionProfileStore.open(root.resolve("profile-a"))
        val secondStore = JvmKWebExtensionProfileStore.open(root.resolve("profile-b"))

        val first = assertNotNull(firstStore.commit(firstStore.prepareUnpacked(source).token))
        assertNull(secondStore.active(extensionId()))
        val secondTransaction = secondStore.prepareUnpacked(source)
        assertNotEquals(first.directory, secondTransaction.extension.directory)
        assertEquals(first.contentDigest, secondTransaction.extension.contentDigest)
        assertTrue(firstStore.pendingTransactions().isEmpty())
        assertEquals(listOf(secondTransaction), secondStore.pendingTransactions())

        val second = assertNotNull(secondStore.commit(secondTransaction.token))
        assertEquals(first.contentDigest, second.contentDigest)
        assertTrue(first.directory.startsWith(firstStore.root))
        assertTrue(second.directory.startsWith(secondStore.root))
    }

    @Test
    fun detectsManagedObjectCorruptionBeforeUseOrReuse() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))
        val installed = assertNotNull(store.commit(store.prepareUnpacked(source).token))
        installed.directory.resolve("worker.js").writeText("tampered")

        assertCode("extensions.store.object-integrity-failed") {
            store.active(extensionId())
        }
        assertCode("extensions.store.object-integrity-failed") {
            store.prepareUnpacked(source)
        }
    }

    @Test
    fun rejectsSymlinkSourcesAndDeletesStaleStagingWithoutFollowingLinks() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        Files.createSymbolicLink(source.resolve("linked.js"), source.resolve("worker.js"))
        val storeRoot = root.resolve("profile-store")
        val store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertCode("extensions.package.symlink-rejected") {
            store.prepareUnpacked(source)
        }

        val outside = root.resolve("outside")
        Files.createDirectories(outside)
        val marker = outside.resolve("keep.txt")
        marker.writeText("keep")
        val stale = store.root.resolve(".staging/stale")
        Files.createDirectories(stale)
        stale.resolve("partial.txt").writeText("partial")
        Files.createSymbolicLink(stale.resolve("outside-link"), outside)
        store.root.resolve("active/.tmp-stale.json").writeText("partial")
        store.root.resolve("transactions/.tmp-stale.json").writeText("partial")

        JvmKWebExtensionProfileStore.open(storeRoot)

        assertTrue(directoryEntries(store.root.resolve(".staging")).isEmpty())
        assertTrue(Files.exists(marker))
        assertFalse(Files.exists(store.root.resolve("active/.tmp-stale.json"), LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(store.root.resolve("transactions/.tmp-stale.json"), LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun rejectsRelativeSymlinkedAndMalformedStoreRoots() = withTempDirectory { root ->
        assertCode("extensions.store.root-relative") {
            JvmKWebExtensionProfileStore.open(Path.of("relative-store"))
        }

        val target = root.resolve("target")
        Files.createDirectory(target)
        val link = root.resolve("store-link")
        Files.createSymbolicLink(link, target)
        assertCode("extensions.store.root-symlink") {
            JvmKWebExtensionProfileStore.open(link)
        }

        val malformed = root.resolve("malformed")
        Files.createDirectory(malformed)
        malformed.resolve("unexpected").writeText("data")
        assertCode("extensions.store.metadata-invalid") {
            JvmKWebExtensionProfileStore.open(malformed)
        }

        val malformedObjects = root.resolve("malformed-objects")
        val initialized = JvmKWebExtensionProfileStore.open(malformedObjects)
        initialized.root.resolve("objects/not-a-directory").writeText("data")
        assertCode("extensions.store.metadata-invalid") {
            JvmKWebExtensionProfileStore.open(malformedObjects)
        }

        val invalidLock = root.resolve("invalid-lock")
        Files.createDirectories(invalidLock.resolve("store.lock"))
        assertCode("extensions.store.lock-invalid") {
            JvmKWebExtensionProfileStore.open(invalidLock)
        }
    }

    @Test
    fun retainsAmbiguousJournalsAndRecoversProvenRuntimeCommits() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val storeRoot = root.resolve("profile-store")
        var store = JvmKWebExtensionProfileStore.open(storeRoot)

        val install = store.prepareUnpacked(source)
        store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertEquals(listOf(install), store.pendingTransactions())

        simulateRuntimeActiveWrite(store, install)
        store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertTrue(store.pendingTransactions().isEmpty())
        assertEquals(install.extension.contentDigest, store.active(extensionId())?.contentDigest)

        val reload = store.prepareUnpacked(source)
        assertEquals(JvmKWebExtensionStoreOperation.RELOAD, reload.operation)
        store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertEquals(listOf(reload), store.pendingTransactions())
        store.abort(reload.token)

        writeUnpackedPackage(source, version = "2.0.0", worker = WORKER_V2)
        val update = store.prepareUnpacked(source)
        simulateRuntimeActiveWrite(store, update)
        store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertTrue(store.pendingTransactions().isEmpty())
        assertEquals("2.0.0", store.active(extensionId())?.packageInfo?.manifest?.version)

        val uninstall = store.prepareUninstall(extensionId())
        Files.delete(activeRecordPath(store, extensionId()))
        store = JvmKWebExtensionProfileStore.open(storeRoot)
        assertTrue(store.pendingTransactions().isEmpty())
        assertNull(store.active(extensionId()))
        assertFalse(Files.exists(transactionRecordPath(store, uninstall.token)))
    }

    @Test
    fun refusesOperationsWhileAnotherOwnerHoldsTheStoreLock() = withTempDirectory { root ->
        val storeRoot = root.resolve("profile-store")
        val store = JvmKWebExtensionProfileStore.open(storeRoot)
        FileChannel.open(store.root.resolve("store.lock"), StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                assertCode("extensions.store.lock-unavailable") {
                    store.active(extensionId())
                }
                assertCode("extensions.store.lock-unavailable") {
                    JvmKWebExtensionProfileStore.open(storeRoot)
                }
            }
        }

        val javaExecutable = javaExecutable()
        val testClasses = Path.of(JvmKWebStoreLockHolder::class.java.protectionDomain.codeSource.location.toURI())
        val process = ProcessBuilder(
            javaExecutable.toString(),
            "-cp",
            testClasses.toString(),
            JvmKWebStoreLockHolder::class.java.name,
            store.root.resolve("store.lock").toString(),
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader()
        try {
            val signal = CompletableFuture.supplyAsync(output::readLine).get(15, TimeUnit.SECONDS)
            assertEquals("LOCKED", signal)
            assertCode("extensions.store.lock-unavailable") {
                store.active(extensionId())
            }
            assertCode("extensions.store.lock-unavailable") {
                JvmKWebExtensionProfileStore.open(storeRoot)
            }
        } finally {
            output.close()
            if (process.isAlive) {
                process.outputStream.write(0)
                process.outputStream.close()
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), "The lock-holder process did not terminate.")
            }
        }
    }

    @Test
    fun blocksGarbageCollectionWithPendingTransactionsAndDeletesOnlyOrphans() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)
        val store = JvmKWebExtensionProfileStore.open(root.resolve("profile-store"))
        val active = assertNotNull(store.commit(store.prepareUnpacked(source).token))

        writeUnpackedPackage(source, version = "2.0.0", worker = WORKER_V2)
        val pending = store.prepareUnpacked(source)
        assertCode("extensions.store.transactions-pending") {
            store.collectGarbage()
        }

        store.abort(pending.token)
        val deleted = store.collectGarbage()
        assertEquals(listOf(pending.extension.directory), deleted)
        assertTrue(Files.isDirectory(active.directory))
        assertFalse(Files.exists(pending.extension.directory, LinkOption.NOFOLLOW_LINKS))
        assertEquals(active, store.active(extensionId()))
    }

    @Test
    fun rejectsUnknownDuplicateInvalidAndConflictingMetadata() = withTempDirectory { root ->
        val source = root.resolve("source")
        writeUnpackedPackage(source, version = "1.0.0", worker = WORKER_V1)

        val unknownRoot = root.resolve("unknown-store")
        var store = JvmKWebExtensionProfileStore.open(unknownRoot)
        store.commit(store.prepareUnpacked(source).token)
        val unknownActive = activeRecordPath(store, extensionId())
        val activeJson = Json.parseToJsonElement(unknownActive.readText()).jsonObject
        unknownActive.writeText(JsonObject(activeJson + ("unknown" to JsonPrimitive(true))).toString())
        assertCode("extensions.store.metadata-invalid") {
            JvmKWebExtensionProfileStore.open(unknownRoot)
        }

        val duplicateRoot = root.resolve("duplicate-store")
        store = JvmKWebExtensionProfileStore.open(duplicateRoot)
        store.commit(store.prepareUnpacked(source).token)
        val duplicateActive = activeRecordPath(store, extensionId())
        duplicateActive.writeText(
            duplicateActive.readText().replaceFirst(
                "\"schemaVersion\":1",
                "\"schemaVersion\":1,\"schemaVersion\":1",
            ),
        )
        assertCode("extensions.store.metadata-duplicate-key") {
            JvmKWebExtensionProfileStore.open(duplicateRoot)
        }

        val utf8Root = root.resolve("utf8-store")
        store = JvmKWebExtensionProfileStore.open(utf8Root)
        store.commit(store.prepareUnpacked(source).token)
        Files.write(activeRecordPath(store, extensionId()), byteArrayOf(0xc3.toByte(), 0x28))
        assertCode("extensions.store.metadata-invalid") {
            JvmKWebExtensionProfileStore.open(utf8Root)
        }

        val conflictRoot = root.resolve("conflict-store")
        store = JvmKWebExtensionProfileStore.open(conflictRoot)
        val transaction = store.prepareUnpacked(source)
        val original = transactionRecordPath(store, transaction.token).readText()
        val secondToken = UUID.randomUUID().toString()
        transactionRecordPath(store, secondToken).writeText(
            original.replace("\"token\":\"${transaction.token}\"", "\"token\":\"$secondToken\""),
        )
        assertCode("extensions.store.metadata-invalid") {
            JvmKWebExtensionProfileStore.open(conflictRoot)
        }
    }

    private fun simulateRuntimeActiveWrite(
        store: JvmKWebExtensionProfileStore,
        transaction: JvmKWebExtensionStoreTransaction,
    ) {
        val transactionJson = Json.parseToJsonElement(
            transactionRecordPath(store, transaction.token).readText(),
        ).jsonObject
        activeRecordPath(store, transaction.extension.packageInfo.extensionId).writeText(
            transactionJson.getValue("extension").toString(),
        )
    }

    private fun writeUnpackedPackage(
        root: Path,
        version: String,
        worker: String,
        keyPair: KeyPair = TEST_KEY_PAIR,
    ) {
        Files.createDirectories(root)
        val key = Base64.getEncoder().encodeToString(keyPair.public.encoded)
        root.resolve("manifest.json").writeText(unpackedManifest(version, key))
        root.resolve("worker.js").writeText(worker)
    }

    private fun unpackedManifest(version: String, key: String): String =
        """{"manifest_version":3,"name":"Store Fixture","version":"$version","key":"$key","background":{"service_worker":"worker.js"}}"""

    private fun crxManifest(version: String): String =
        """{"manifest_version":3,"name":"Store Fixture","version":"$version","background":{"service_worker":"worker.js"}}"""

    private fun extensionId(): String = JvmKWebCrx3TestFixture.extensionId(TEST_KEY_PAIR)

    private fun activeRecordPath(store: JvmKWebExtensionProfileStore, extensionId: String): Path =
        store.root.resolve("active/$extensionId.json")

    private fun transactionRecordPath(store: JvmKWebExtensionProfileStore, token: String): Path =
        store.root.resolve("transactions/$token.json")

    private fun directoryEntries(directory: Path): List<Path> = Files.newDirectoryStream(directory).use { stream ->
        stream.toList()
    }

    private fun javaExecutable(): Path {
        val bin = Path.of(System.getProperty("java.home"), "bin")
        val executable = bin.resolve("java")
        return if (Files.isRegularFile(executable)) executable else bin.resolve("java.exe")
    }

    private fun assertCode(code: String, operation: () -> Unit) {
        val error = assertFailsWith<KWebExtensionVerificationException>(message = code, block = operation)
        assertEquals(code, error.code)
    }

    private inline fun <T> withTempDirectory(operation: (Path) -> T): T {
        val root = Files.createTempDirectory("kweb-extension-store-test-").toRealPath()
        try {
            return operation(root)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
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

    private companion object {
        val TEST_KEY_PAIR: KeyPair = JvmKWebCrx3TestFixture.rsaKeyPair()
        const val WORKER_V1: String = "self.oninstall=()=>{};"
        const val WORKER_V2: String = "self.onmessage=()=>{};"
    }
}
