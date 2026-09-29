package io.github.kingsword09.kwebshell.service.files

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmKWebFilesTest {
    @Test
    fun boundedWorkspaceOperationsNeverExposePaths() = runBlocking {
        val root = createTempDirectory("kweb-files")
        try {
            val service = service(root)
            val grants = KWebFileGrant.entries.toSet()
            val workspace = service.openWorkspace(KWebWorkspaceRequest("documents", grants))
            assertEquals("DIRECTORY", workspace.kind.name)
            val nested = service.openDirectory(
                KWebDirectoryOpenRequest(KWebFileHandle(workspace.handle), "nested", createIfMissing = true),
            )
            val file = service.openFile(
                KWebFileOpenRequest(KWebFileHandle(nested.handle), "hello.txt", KWebFileOpenMode.READ_WRITE, createIfMissing = true),
            )
            service.writeFile(KWebFileHandle(file.handle), 0, "hello".encodeToByteArray())
            val read = service.readFile(KWebFileHandle(file.handle), 0, 16)
            assertContentEquals("hello".encodeToByteArray(), read.bytes)
            assertEquals(true, read.eof)
            assertEquals("hello.txt", service.metadata(KWebFileHandle(file.handle)).name)
            assertEquals("hello.txt", service.listDirectory(KWebFileHandle(nested.handle), 4).entries.single().name)

            val copy = service.copyFile(
                KWebFileHandle(file.handle),
                KWebFileHandle(workspace.handle),
                "copy.txt",
                KWebFileConflictPolicy.FAIL,
            )
            assertContentEquals("hello".encodeToByteArray(), service.readFile(KWebFileHandle(copy.handle), 0, 16).bytes)
            val moved = service.moveFile(
                KWebFileHandle(copy.handle),
                KWebFileHandle(workspace.handle),
                "moved.txt",
                KWebFileConflictPolicy.FAIL,
            )
            assertEquals("moved.txt", service.metadata(KWebFileHandle(moved.handle)).name)
            assertEquals(false, Files.exists(root.resolve("copy.txt")))
            service.close()
            assertFailsWith<Exception> {
                service.metadata(KWebFileHandle(file.handle))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun rejectsLinksAndCrossInstanceTokens() = runBlocking {
        val root = createTempDirectory("kweb-files-security")
        try {
            val outside = root.resolveSibling("kweb-files-outside-${System.nanoTime()}")
            outside.createDirectories()
            outside.resolve("secret.txt").writeText("secret")
            val link = root.resolve("link.txt")
            try {
                Files.createSymbolicLink(link, outside.resolve("secret.txt"))
            } catch (_: UnsupportedOperationException) {
                return@runBlocking
            } catch (_: java.nio.file.FileSystemException) {
                return@runBlocking
            }
            val owner = owner()
            val config = JvmKWebFilesConfiguration(mapOf("documents" to JvmKWebWorkspace(root, KWebFileGrant.entries.toSet())))
            val first = JvmKWebFiles.open(owner, config)
            val second = JvmKWebFiles.open(owner.copy(pageId = "other-page"), config)
            val firstRoot = first.openWorkspace(KWebWorkspaceRequest("documents", KWebFileGrant.entries.toSet()))
            assertFailsWith<Exception> {
                first.openFile(KWebFileOpenRequest(KWebFileHandle(firstRoot.handle), "link.txt", KWebFileOpenMode.READ))
            }
            assertFailsWith<Exception> {
                second.metadata(KWebFileHandle(firstRoot.handle))
            }
            first.close()
            second.close()
            outside.toFile().deleteRecursively()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun watchPublishesOrderedCreateAndClosesWithOwner() = runBlocking {
        val root = createTempDirectory("kweb-files-watch")
        try {
            val service = service(root)
            val workspace = service.openWorkspace(KWebWorkspaceRequest("documents", KWebFileGrant.entries.toSet()))
            val watcher = async {
                service.watchDirectory(KWebFileHandle(workspace.handle)).first()
            }
            delay(2_000)
            Files.writeString(root.resolve("created.txt"), "created")
            val event = withTimeout(15_000) { watcher.await() }
            assertEquals(KWebFileWatchKind.CREATED, event.kind)
            assertEquals("created.txt", event.name)
            assertEquals(1L, event.sequence)
            service.close()
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun service(root: Path): KWebFiles = JvmKWebFiles.open(
        owner(),
        JvmKWebFilesConfiguration(mapOf("documents" to JvmKWebWorkspace(root, KWebFileGrant.entries.toSet()))),
    )

    private fun owner(): KWebFileOwnerScope = KWebFileOwnerScope(
        engineId = "engine",
        profileId = "profile",
        pageId = "page",
        origin = "https://app.example",
        navigationId = 1,
    )
}
