package io.github.kingsword09.kwebshell.services.consent

import io.github.kingsword09.kwebshell.core.KWebConfigurationException
import io.github.kingsword09.kwebshell.services.KWebPolicySubject
import io.github.kingsword09.kwebshell.services.policy.KWebConsentDecision
import io.github.kingsword09.kwebshell.services.policy.KWebConsentRequest
import io.github.kingsword09.kwebshell.services.policy.KWebConsentRevocation
import io.github.kingsword09.kwebshell.services.policy.KWebConsentStore
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Durable user decisions persisted as one canonical JSON document. Writes are
 * atomic (temporary file in the same directory plus an atomic move) so a crash
 * can never leave a half-recorded decision. The persistence scope is one
 * `scopeOwnerId` inside one file, e.g. one Profile.
 */
public class KWebFileConsentStore(
    override val scopeOwnerId: String,
    private val file: Path,
) : KWebConsentStore {
    private val lock = Any()
    private val mutableRevocations = MutableSharedFlow<KWebConsentRevocation>(replay = 16, extraBufferCapacity = 64)
    private var closed = false

    override val revocations: SharedFlow<KWebConsentRevocation> = mutableRevocations.asSharedFlow()

    private val format = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    private val decisions: MutableMap<String, StoredDecision>

    init {
        if (scopeOwnerId.isBlank()) {
            throw KWebConfigurationException(
                code = "service.consent.scope-invalid",
                details = mapOf(),
                message = "A consent store requires a persistence scope owner.",
            )
        }
        decisions = load()
    }

    override fun decision(request: KWebConsentRequest): KWebConsentDecision? {
        synchronized(lock) {
            requireOpen()
            return decisions[request.key()]?.let { KWebConsentDecision(it.granted, it.decidedBy) }
        }
    }

    override fun record(request: KWebConsentRequest, granted: Boolean, decidedBy: String) {
        synchronized(lock) {
            requireOpen()
            decisions[request.key()] = StoredDecision(granted, decidedBy)
            persist()
        }
    }

    override fun revoke(request: KWebConsentRequest, decidedBy: String) {
        val removed = synchronized(lock) {
            requireOpen()
            decisions.remove(request.key())?.also { persist() }
        }
        if (removed != null) {
            val emitted = mutableRevocations.tryEmit(
                KWebConsentRevocation(request.serviceId, request.operationId, request.origin, decidedBy),
            )
            if (!emitted) {
                throw KWebConfigurationException(
                    code = "service.consent.revocation-dropped",
                    details = mapOf("service" to request.serviceId),
                    message = "The consent revocation event buffer overflowed.",
                )
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
        }
    }

    private fun requireOpen() {
        if (closed) {
            throw KWebConfigurationException(
                code = "service.owner-closed",
                details = mapOf("scope" to scopeOwnerId),
                message = "The consent store is closed.",
            )
        }
    }

    private fun persist() {
        val document = format.encodeToString(
            StoredDocument.serializer(),
            StoredDocument(
                schemaVersion = SCHEMA_VERSION,
                scopeOwnerId = scopeOwnerId,
                decisions = decisions.entries
                    .sortedBy { it.key }
                    .map { StoredDecisionEntry(it.key, it.value.granted, it.value.decidedBy) },
            ),
        )
        file.parent?.let(Files::createDirectories)
        val temporary = Files.createTempFile(file.parent, file.fileName.toString(), ".tmp")
        try {
            Files.writeString(
                temporary,
                document,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun load(): MutableMap<String, StoredDecision> {
        if (!Files.exists(file) || Files.size(file) == 0L) return mutableMapOf()
        val document = try {
            format.decodeFromString(StoredDocument.serializer(), Files.readString(file, StandardCharsets.UTF_8))
        } catch (error: Throwable) {
            throw KWebConfigurationException(
                code = "service.consent.store-corrupt",
                details = mapOf("scope" to scopeOwnerId),
                message = "The consent store document is not strict schema JSON.",
                cause = error,
            )
        }
        if (document.schemaVersion != SCHEMA_VERSION || document.scopeOwnerId != scopeOwnerId) {
            throw KWebConfigurationException(
                code = "service.consent.store-scope-mismatch",
                details = mapOf(
                    "scope" to scopeOwnerId,
                    "documentScope" to document.scopeOwnerId,
                ),
                message = "The consent store document belongs to another persistence scope.",
            )
        }
        return document.decisions.associateTo(mutableMapOf()) {
            it.key to StoredDecision(it.granted, it.decidedBy)
        }
    }

    @Serializable
    private data class StoredDecision(val granted: Boolean, val decidedBy: String)

    @Serializable
    private data class StoredDecisionEntry(val key: String, val granted: Boolean, val decidedBy: String)

    @Serializable
    private data class StoredDocument(
        val schemaVersion: Int,
        val scopeOwnerId: String,
        val decisions: List<StoredDecisionEntry>,
    )

    private companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

private fun KWebConsentRequest.key(): String = "$serviceId|$operationId|$origin"
