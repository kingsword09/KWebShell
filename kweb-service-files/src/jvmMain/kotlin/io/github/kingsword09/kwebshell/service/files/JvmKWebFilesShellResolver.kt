package io.github.kingsword09.kwebshell.service.files

import java.nio.file.Path

public enum class JvmKWebFilesShellAccess {
    OPEN,
    REVEAL,
    TRASH,
}

public data class JvmKWebFilesShellResource(
    public val path: Path,
    public val kind: KWebFileNodeKind,
)

public interface JvmKWebFilesShellResolver {
    public suspend fun <T> withResource(
        token: String,
        access: JvmKWebFilesShellAccess,
        action: (JvmKWebFilesShellResource) -> T,
    ): T
}
