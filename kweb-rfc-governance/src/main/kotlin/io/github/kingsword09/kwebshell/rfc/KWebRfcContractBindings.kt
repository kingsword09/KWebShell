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
     * Hashes path names and exact file bytes in stable order. Directory bindings are
     * expanded recursively; symlinks are rejected so evidence cannot escape the
     * repository or change meaning between runners.
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
        val files = linkedSetOf<Path>()
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
            if (Files.isDirectory(path)) {
                Files.walk(path).use { stream ->
                    stream.filter(Files::isRegularFile).forEach { file ->
                        if (Files.isSymbolicLink(file)) {
                            throw unsafe(rfcId, root.relativize(file).toString())
                        }
                        files.add(file)
                    }
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path)
            } else {
                throw unsafe(rfcId, configuredPath)
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
}
