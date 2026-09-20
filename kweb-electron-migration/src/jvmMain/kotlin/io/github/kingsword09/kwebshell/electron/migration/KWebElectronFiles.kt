package io.github.kingsword09.kwebshell.electron.migration

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal object KWebElectronFiles {
    private val sourceExtensions = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "vue", "svelte", "html", "css", "node", "gyp")
    private val locks = setOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb")

    fun sources(root: Path): Map<String, String> = entries(root) { it.fileName.toString().substringAfterLast('.') in sourceExtensions || it.fileName.toString() == "package.json" }
    fun lockfiles(root: Path): Map<String, String> = entries(root) { it.fileName.toString() in locks }
    fun tree(root: Path): String = digestEntries(entries(root) { true })

    fun entries(root: Path, include: (Path) -> Boolean): Map<String, String> {
        if (!Files.isDirectory(root)) return emptyMap()
        return Files.walk(root).use { paths ->
            paths.filter { path -> root.relativize(path).none { it.toString() in setOf("node_modules", ".git", ".gradle") } }
                .filter { Files.isRegularFile(it) && include(it) }
                .map { path ->
                    if (Files.isSymbolicLink(path) || !path.toRealPath().startsWith(root.toRealPath())) throw KWebElectronMigrationException(KWebElectronMigrationErrorCode.INVENTORY_BLOCKED, "Migration inputs must be regular files inside the application root.")
                    root.relativize(path).toString().replace(path.fileSystem.separator, "/") to digest(Files.readAllBytes(path))
                }.toList().toMap().toSortedMap()
        }
    }

    fun digestEntries(entries: Map<String, String>): String = text(entries.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" })
    fun text(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
