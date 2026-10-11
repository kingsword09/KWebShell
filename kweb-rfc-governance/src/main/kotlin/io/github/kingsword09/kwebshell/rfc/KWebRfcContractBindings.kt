package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Repository paths whose exact bytes define one RFC's implemented contract. */
@Serializable
public data class KWebRfcContractBinding(
    public val rfcId: String,
    public val paths: List<String>,
)

/** Versioned source of truth for the contract inputs that hosted evidence binds. */
@Serializable
public data class KWebRfcContractBindingsDocument(
    public val schemaVersion: Int,
    public val bindings: List<KWebRfcContractBinding>,
)

public object KWebRfcContractBindings {
    public const val CURRENT_SCHEMA_VERSION: Int = 1

    private val json: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }
    private val rfcIdPattern: Regex = Regex("[0-9]{4}")
    private val relativePathPattern: Regex = Regex("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")

    public fun load(path: Path): KWebRfcContractBindingsDocument {
        val document = try {
            json.decodeFromString<KWebRfcContractBindingsDocument>(Files.readString(path))
        } catch (error: SerializationException) {
            throw invalid("The RFC contract bindings document is not strict schema JSON.", error)
        } catch (error: IllegalArgumentException) {
            throw invalid("The RFC contract bindings document is not strict schema JSON.", error)
        }
        validate(document)
        return document
    }

    public fun validate(document: KWebRfcContractBindingsDocument) {
        if (document.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw invalid("Only RFC contract bindings schema version $CURRENT_SCHEMA_VERSION is supported.")
        }
        if (document.bindings.map { it.rfcId }.toSet().size != document.bindings.size) {
            throw invalid("RFC contract bindings cannot repeat an RFC id.")
        }
        document.bindings.forEach { binding ->
            if (!rfcIdPattern.matches(binding.rfcId)) {
                throw invalid("RFC contract binding ids must contain exactly four digits.")
            }
            if (binding.paths.isEmpty() || binding.paths != binding.paths.distinct().sorted()) {
                throw invalid("RFC ${binding.rfcId} contract paths must be a non-empty sorted unique list.")
            }
            binding.paths.forEach { path ->
                if (!relativePathPattern.matches(path) || path.split('/').any { it == "." || it == ".." }) {
                    throw invalid("RFC ${binding.rfcId} contains an unsafe repository-relative contract path.")
                }
            }
        }
    }

    /**
     * Hashes path names and exact file bytes in stable order. A directory binding is
     * expanded to the files that belong to the repository revision, so untracked
     * local build output under a bound path cannot invalidate evidence that was
     * recorded from a committed revision. Symlinks are rejected so evidence cannot
     * escape the repository or change meaning between runners, and a bound path the
     * revision does not contain fails closed instead of hashing nothing.
     */
    public fun digest(
        document: KWebRfcContractBindingsDocument,
        repositoryRoot: Path,
        rfcId: String,
    ): String {
        validate(document)
        val binding = document.bindings.singleOrNull { it.rfcId == rfcId }
            ?: throw KWebRfcGovernanceException(
                code = KWebRfcContractBindingErrorCode.MISSING,
                details = mapOf("rfc" to rfcId),
                message = "Implemented RFC evidence requires an explicit contract binding.",
            )
        val root = repositoryRoot.toAbsolutePath().normalize()
        // Bound paths are checked before the revision is read, so a caller sees the
        // precise path problem instead of a work-tree problem.
        binding.paths.forEach { configuredPath ->
            val path = root.resolve(configuredPath).normalize()
            if (!path.startsWith(root) || !Files.exists(path)) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcContractBindingErrorCode.MISSING,
                    details = mapOf("rfc" to rfcId, "path" to configuredPath),
                    message = "An RFC contract binding path does not exist in the repository.",
                )
            }
            if (Files.isSymbolicLink(path)) {
                throw unsafe(rfcId, configuredPath)
            }
            if (!Files.isDirectory(path) && !Files.isRegularFile(path)) {
                throw unsafe(rfcId, configuredPath)
            }
        }
        val tracked = trackedFiles(root)
        val files = linkedSetOf<Path>()
        binding.paths.forEach { configuredPath ->
            val path = root.resolve(configuredPath).normalize()
            if (Files.isDirectory(path)) {
                val prefix = "$configuredPath/"
                val members = tracked.filter { it.startsWith(prefix) }.sorted()
                if (members.isEmpty()) {
                    throw notTracked(rfcId, configuredPath)
                }
                members.forEach { member ->
                    val file = root.resolve(member).normalize()
                    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) {
                        throw trackedMissing(rfcId, member)
                    }
                    files.add(file)
                }
            } else {
                if (configuredPath !in tracked) {
                    throw notTracked(rfcId, configuredPath)
                }
                files.add(path)
            }
        }
        if (files.isEmpty()) {
            throw KWebRfcGovernanceException(
                code = KWebRfcContractBindingErrorCode.MISSING,
                details = mapOf("rfc" to rfcId),
                message = "An RFC contract binding must resolve to at least one regular repository file.",
            )
        }
        val digestInput = buildString {
            files.sortedBy { root.relativize(it).toString().replace('\\', '/') }.forEach { file ->
                val relative = root.relativize(file).toString().replace('\\', '/')
                append(relative)
                append('\u0000')
                append(KWebRfcEvidenceJson.sha256(Files.readAllBytes(file)))
                append('\n')
            }
        }
        return KWebRfcEvidenceJson.sha256(digestInput.encodeToByteArray())
    }

    /**
     * The repository revision a contract digest binds: the files git tracks under the
     * worktree root. A root that is not a worktree cannot name a revision, so it fails
     * closed instead of hashing arbitrary local files.
     */
    private fun trackedFiles(root: Path): Set<String> {
        val topLevel = git(root, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw notAWorktree(root)
        val topLevelPath = try {
            Path.of(topLevel).toRealPath()
        } catch (error: Throwable) {
            throw notAWorktree(root)
        }
        if (topLevelPath != root.toRealPath()) {
            throw KWebRfcGovernanceException(
                code = KWebRfcContractBindingErrorCode.NOT_A_WORK_TREE,
                details = mapOf("root" to root.toString(), "worktree" to topLevelPath.toString()),
                message = "The contract digest requires the git worktree root, not a subdirectory of it.",
            )
        }
        val listing = git(root, "ls-files", "-z") ?: throw notAWorktree(root)
        return listing.split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    private fun git(root: Path, vararg arguments: String): String? {
        val command = listOf("git", "-C", root.toString()) + arguments
        val process = try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (error: Throwable) {
            return null
        }
        val output = process.inputStream.use { it.readBytes().decodeToString() }
        val failed = process.errorStream.use { it.readBytes().decodeToString() }
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            return null
        }
        if (failed.isNotEmpty()) return null
        return output
    }

    private fun notAWorktree(root: Path): KWebRfcGovernanceException =
        KWebRfcGovernanceException(
            code = KWebRfcContractBindingErrorCode.NOT_A_WORK_TREE,
            details = mapOf("root" to root.toString()),
            message = "A contract digest binds a repository revision, so its root must be a git work tree.",
        )

    private fun notTracked(rfcId: String, path: String): KWebRfcGovernanceException =
        KWebRfcGovernanceException(
            code = KWebRfcContractBindingErrorCode.NOT_TRACKED,
            details = mapOf("rfc" to rfcId, "path" to path),
            message = "A bound contract path must contain files the repository revision tracks.",
        )

    private fun trackedMissing(rfcId: String, path: String): KWebRfcGovernanceException =
        KWebRfcGovernanceException(
            code = KWebRfcContractBindingErrorCode.TRACKED_FILE_MISSING,
            details = mapOf("rfc" to rfcId, "path" to path),
            message = "A tracked contract file is missing from the working tree.",
        )

    private fun unsafe(rfcId: String, path: String): KWebRfcGovernanceException =
        KWebRfcGovernanceException(
            code = KWebRfcContractBindingErrorCode.UNSAFE_PATH,
            details = mapOf("rfc" to rfcId, "path" to path),
            message = "RFC contract bindings may contain regular repository files and directories only.",
        )

    private fun invalid(message: String, cause: Throwable? = null): KWebRfcGovernanceException =
        KWebRfcGovernanceException(
            code = KWebRfcContractBindingErrorCode.INVALID,
            message = message,
            cause = cause,
        )
}

public fun interface KWebRfcContractDigestProvider {
    public fun digest(rfcId: String): String

    public companion object {
        public fun forRepository(
            document: KWebRfcContractBindingsDocument,
            repositoryRoot: Path,
        ): KWebRfcContractDigestProvider =
            KWebRfcContractDigestProvider { rfcId -> KWebRfcContractBindings.digest(document, repositoryRoot, rfcId) }
    }
}

public fun interface KWebRfcArtifactDigestProvider {
    public fun digest(repositoryRelativePath: String): String?

    public companion object {
        public fun forRepository(repositoryRoot: Path): KWebRfcArtifactDigestProvider {
            val root = repositoryRoot.toAbsolutePath().normalize()
            return KWebRfcArtifactDigestProvider { repositoryRelativePath ->
                val path = root.resolve(repositoryRelativePath).normalize()
                if (!path.startsWith(root) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    null
                } else {
                    KWebRfcEvidenceJson.sha256(Files.readAllBytes(path))
                }
            }
        }
    }
}

public object KWebRfcContractBindingErrorCode {
    public const val INVALID: String = "rfc.contract-binding.invalid"
    public const val MISSING: String = "rfc.contract-binding.missing"
    public const val UNSAFE_PATH: String = "rfc.contract-binding.unsafe-path"
    public const val NOT_A_WORK_TREE: String = "rfc.contract-binding.not-a-work-tree"
    public const val NOT_TRACKED: String = "rfc.contract-binding.not-tracked"
    public const val TRACKED_FILE_MISSING: String = "rfc.contract-binding.tracked-file-missing"
}
