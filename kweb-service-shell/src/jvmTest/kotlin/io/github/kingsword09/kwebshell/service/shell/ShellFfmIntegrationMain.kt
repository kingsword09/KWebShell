package io.github.kingsword09.kwebshell.service.shell

import io.github.kingsword09.kwebshell.core.KWebNativeException
import io.github.kingsword09.kwebshell.service.files.JvmKWebFiles
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesConfiguration
import io.github.kingsword09.kwebshell.service.files.JvmKWebFilesShellResolver
import io.github.kingsword09.kwebshell.service.files.JvmKWebWorkspace
import io.github.kingsword09.kwebshell.service.files.KWebFileGrant
import io.github.kingsword09.kwebshell.service.files.KWebFileOpenMode
import io.github.kingsword09.kwebshell.service.files.KWebFileOwnerScope
import io.github.kingsword09.kwebshell.service.files.KWebFileOpenRequest
import io.github.kingsword09.kwebshell.service.files.KWebWorkspaceRequest
import io.github.kingsword09.kwebshell.service.files.jvmKWebFileHandle
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

public fun main(): Unit = runBlocking {
    val library = Path.of(System.getProperty("kweb.shell.native.library.path") ?: error("Missing native library path"))
    val root = Files.createTempDirectory("kweb-shell-integration")
    val files = JvmKWebFiles.open(
        KWebFileOwnerScope("shell-integration-engine", "default", "shell-integration-page", "app://shell-fixture", 1),
        JvmKWebFilesConfiguration(
            mapOf(
                "documents" to JvmKWebWorkspace(
                    root,
                    setOf(KWebFileGrant.READ, KWebFileGrant.WRITE, KWebFileGrant.CREATE,
                        KWebFileGrant.ENUMERATE, KWebFileGrant.METADATA),
                ),
            ),
        ),
    )
    val resolver = files as JvmKWebFilesShellResolver
    val shell = JvmKWebShell.open(library, KWebShellConfiguration(), resolver)
    try {
        val denied = runCatching {
            shell.openExternal(KWebShellExternalUriRequest("javascript:alert(1)"))
        }.exceptionOrNull()
        check(denied is KWebNativeException && denied.code == KWebShellErrorCode.SCHEME_DENIED)

        val workspace = files.openWorkspace(
            KWebWorkspaceRequest(
                "documents",
                setOf(KWebFileGrant.READ, KWebFileGrant.WRITE, KWebFileGrant.CREATE,
                    KWebFileGrant.ENUMERATE, KWebFileGrant.METADATA),
            ),
        )
        val file = files.openFile(
            KWebFileOpenRequest(
                jvmKWebFileHandle(workspace.handle),
                "fixture.txt",
                KWebFileOpenMode.READ_WRITE,
                createIfMissing = true,
            ),
        )
        val result = shell.trashResource(KWebShellResourceHandle.fromBridge(file.handle))
        check(result.outcome == KWebShellActionOutcome.MOVED_TO_TRASH)
        check(!Files.exists(root.resolve("fixture.txt")))
        files.closeHandle(jvmKWebFileHandle(file.handle))
        files.closeHandle(jvmKWebFileHandle(workspace.handle))
    } finally {
        shell.close()
        files.close()
        Files.deleteIfExists(root)
    }
    println("KWebShell FFM integration passed on ${System.getProperty("os.name")}.")
}
