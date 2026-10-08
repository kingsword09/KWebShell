package io.github.kingsword09.kwebshell.electron.migration

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object KWebElectronFiles {
    private val sourceExtensions = setOf("js", "jsx", "ts", "tsx", "mjs", "cjs", "mts", "cts", "vue", "svelte", "html", "css", "node", "gyp")
    private val locks = setOf("package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock", "bun.lock", "bun.lockb")
    private val excludedDirectories = setOf("node_modules", ".git", ".gradle")

    fun sources(root: Path): Map<String, String> = entries(root) { it.fileName.toString().substringAfterLast('.') in sourceExtensions || it.fileName.toString() == "package.json" }
    fun applicationInputs(root: Path): Map<String, String> = entries(root) { it.fileName.toString() !in locks }
    fun lockfiles(root: Path): Map<String, String> = entries(root) { it.fileName.toString() in locks }
    fun tree(root: Path): String = digestEntries(entries(root) { true })

    fun entries(root: Path, include: (Path) -> Boolean): Map<String, String> {
        val normalized = root.toAbsolutePath().normalize()
        try {
            if (Files.isSymbolicLink(normalized)) reject(normalized, "Migration input roots must not be symbolic links.")
            if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return emptyMap()
            if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) reject(normalized, "Migration input roots must be directories.")
            val entries = sortedMapOf<String, String>()
            Files.walkFileTree(normalized, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                    if (directory != normalized && directory.fileName.toString() in excludedDirectories) return FileVisitResult.SKIP_SUBTREE
                    validatePath(normalized, directory)
                    if (attributes.isSymbolicLink || attributes.isOther) reject(directory, "Migration input directories must not be links or special files.")
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                    validatePath(normalized, file)
                    if (attributes.isSymbolicLink || !attributes.isRegularFile || attributes.isOther) reject(file, "Migration inputs must be regular files, not links or special files.")
                    if (include(file)) {
                        val relative = normalized.relativize(file).toString().replace(file.fileSystem.separator, "/")
                        entries[relative] = digest(Files.readAllBytes(file))
                    }
                    return FileVisitResult.CONTINUE
                }
            })
            return entries
        } catch (error: IOException) {
            throw KWebElectronMigrationException(
                KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
                "Migration inputs could not be read completely.",
                details = mapOf("root" to normalized.toString()),
                cause = error,
            )
        }
    }

    private fun validatePath(root: Path, path: Path) {
        if (root.relativize(path).any { component -> component.toString().any { it.code < 0x20 || it.code in 0x7f..0x9f } }) {
            reject(path, "Migration input paths must not contain control characters.")
        }
    }

    private fun reject(path: Path, message: String): Nothing = throw KWebElectronMigrationException(
        KWebElectronMigrationErrorCode.INVENTORY_BLOCKED,
        message,
        details = mapOf("path" to path.toString()),
    )

    fun digestEntries(entries: Map<String, String>): String = text(entries.toSortedMap().entries.joinToString("\n") { "${it.key}\u0000${it.value}" })
    fun text(value: String): String = digest(value.toByteArray(Charsets.UTF_8))
    fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
