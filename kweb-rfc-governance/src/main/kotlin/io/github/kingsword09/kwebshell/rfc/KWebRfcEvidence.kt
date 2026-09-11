package io.github.kingsword09.kwebshell.rfc

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One retained artifact digest bound to a capability evidence record. Digests are
 * lowercase SHA-256 over the exact retained bytes, never over a description of them.
 */
@Serializable
public data class KWebRfcEvidenceArtifact(
    public val name: String,
    public val sha256: String,
)

/**
 * One hosted target record proving that an RFC capability ran for real. A record is
 * a support claim: it may only exist for an RFC whose catalog status is `Implemented`.
 */
@Serializable
public data class KWebRfcEvidenceRecord(
    public val rfcId: String,
    public val rfcStatus: String,
    public val serviceId: String? = null,
    public val serviceVersion: String? = null,
    public val schemaVersion: Int,
    public val providerId: String,
    public val target: String,
    public val cefVersion: String,
    public val chromiumVersion: String,
    public val electronFixtureMajor: Int,
    public val testRunId: String,
    public val compatibilityStatus: String,
    public val matrixRowIds: List<String> = emptyList(),
    public val artifacts: List<KWebRfcEvidenceArtifact>,
)

/**
 * The machine-readable capability evidence manifest checked in at
 * `docs/rfcs/evidence/manifest.json`. `recordsSha256` covers the canonical
 * serialization of the sorted records so any hand edit is detected.
 */
@Serializable
public data class KWebRfcEvidenceManifest(
    public val schemaVersion: Int,
    public val recordsSha256: String,
    public val records: List<KWebRfcEvidenceRecord> = emptyList(),
)

public class KWebRfcGovernanceException(
    public val code: String,
    message: String,
    public val details: Map<String, String> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)

public object KWebRfcEvidenceErrorCode {
    public const val INVALID_JSON: String = "rfc.evidence.invalid-json"
    public const val SCHEMA_UNSUPPORTED: String = "rfc.evidence.schema-unsupported"
    public const val INVALID: String = "rfc.evidence.invalid"
    public const val DIGEST_MISMATCH: String = "rfc.evidence.digest-mismatch"
    public const val REDACTION: String = "rfc.evidence.redaction"
}

/**
 * Hosted targets are the GitHub-hosted verification targets that retain real
 * runtime evidence. Evidence for a target outside this set cannot publish support.
 */
public val KWEB_RFC_HOSTED_TARGETS: Set<String> = setOf("macos-arm64", "windows-x64", "linux-x64")

/**
 * Mapping from a declared RFC platform target (front-matter `Platform targets`)
 * to the hosted target that must carry its evidence record.
 */
public val KWEB_RFC_PLATFORM_HOSTED_TARGETS: Map<String, String> = mapOf(
    "macos" to "macos-arm64",
    "windows" to "windows-x64",
    "linux" to "linux-x64",
)

public object KWebRfcEvidenceJson {
    private const val CURRENT_SCHEMA_VERSION: Int = 1

    /** Canonical checked-in form: stable key order, two-space indent, trailing newline added by writers. */
    public val canonical: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** Compact form used only as the digest input for `recordsSha256`. */
    private val compact: Json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        isLenient = false
    }

    private val RECORD_ORDER: Comparator<KWebRfcEvidenceRecord> = compareBy(
        { it.rfcId },
        { it.target },
        { it.providerId },
    )

    public fun recordsSha256(records: List<KWebRfcEvidenceRecord>): String {
        val canonicalRecords = records.sortedWith(RECORD_ORDER)
        val serialized = compact.encodeToString(
            ListSerializer(KWebRfcEvidenceRecord.serializer()),
            canonicalRecords,
        )
        return sha256(serialized.encodeToByteArray())
    }

    public fun encodeManifest(manifest: KWebRfcEvidenceManifest): String {
        KWebRfcEvidenceValidator.validate(manifest)
        val sorted = manifest.copy(records = manifest.records.sortedWith(RECORD_ORDER))
        return canonical.encodeToString(KWebRfcEvidenceManifest.serializer(), sorted)
    }

    public fun decodeManifest(text: String): KWebRfcEvidenceManifest {
        val manifest = try {
            canonical.decodeFromString(KWebRfcEvidenceManifest.serializer(), text)
        } catch (error: SerializationException) {
            throw KWebRfcGovernanceException(
                code = KWebRfcEvidenceErrorCode.INVALID_JSON,
                message = "The RFC evidence manifest is not strict schema JSON.",
                cause = error,
            )
        } catch (error: IllegalArgumentException) {
            throw KWebRfcGovernanceException(
                code = KWebRfcEvidenceErrorCode.INVALID_JSON,
                message = "The RFC evidence manifest is not strict schema JSON.",
                cause = error,
            )
        }
        KWebRfcEvidenceValidator.validate(manifest)
        return manifest
    }

    public fun emptyManifest(): KWebRfcEvidenceManifest = KWebRfcEvidenceManifest(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        recordsSha256 = recordsSha256(emptyList()),
        records = emptyList(),
    )

    public fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

public object KWebRfcEvidenceValidator {
    public const val CURRENT_SCHEMA_VERSION: Int = 1
    public const val IMPLEMENTED_STATUS: String = "Implemented"
    public const val READY_STATUS: String = "READY"
    public const val BLOCKED_STATUS: String = "BLOCKED"

    /**
     * Serialized record field names; the packaged JSON schema must declare exactly
     * these properties. Kept in sync by KWebRfcPackagedSchemaTest.
     */
    public val RECORD_FIELDS: List<String> = listOf(
        "rfcId",
        "rfcStatus",
        "serviceId",
        "serviceVersion",
        "schemaVersion",
        "providerId",
        "target",
        "cefVersion",
        "chromiumVersion",
        "electronFixtureMajor",
        "testRunId",
        "compatibilityStatus",
        "matrixRowIds",
        "artifacts",
    )

    public val ARTIFACT_FIELDS: List<String> = listOf("name", "sha256")

    private val RFC_ID = Regex("[0-9]{4}")
    private val SERVICE_ID = Regex(
        "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?)*",
    )
    private val SEMANTIC_VERSION = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
    private val IDENTITY_VERSION = Regex("[A-Za-z0-9.+_-]{1,64}")
    private val MATRIX_ROW_ID = Regex("[a-z][a-z0-9-]{1,63}")
    private val TEST_RUN_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    private val ARTIFACT_NAME = Regex("[a-z0-9][a-z0-9.-]{0,63}")
    private val DIGEST = Regex("[0-9a-f]{64}")

    public fun validate(manifest: KWebRfcEvidenceManifest) {
        if (manifest.schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw KWebRfcGovernanceException(
                code = KWebRfcEvidenceErrorCode.SCHEMA_UNSUPPORTED,
                details = mapOf("schemaVersion" to manifest.schemaVersion.toString()),
                message = "Only RFC evidence manifest schema version $CURRENT_SCHEMA_VERSION is supported.",
            )
        }
        val expectedDigest = KWebRfcEvidenceJson.recordsSha256(manifest.records)
        if (manifest.recordsSha256 != expectedDigest) {
            throw KWebRfcGovernanceException(
                code = KWebRfcEvidenceErrorCode.DIGEST_MISMATCH,
                details = mapOf(
                    "declared" to manifest.recordsSha256,
                    "computed" to expectedDigest,
                ),
                message = "The RFC evidence manifest digest does not match its records; " +
                    "regenerate the manifest instead of editing it.",
            )
        }
        val identity = mutableSetOf<String>()
        manifest.records.forEachIndexed { index, record ->
            validateRecord(index, record, manifest.schemaVersion)
            val key = "${record.rfcId}|${record.target}|${record.providerId}"
            if (!identity.add(key)) {
                throw invalid(
                    "records[$index]",
                    "duplicate",
                    "Duplicate evidence record identity '$key'; one record per RFC, target, and provider.",
                )
            }
        }
    }

    private fun validateRecord(index: Int, record: KWebRfcEvidenceRecord, manifestSchemaVersion: Int) {
        if (!RFC_ID.matches(record.rfcId)) {
            throw invalid("records[$index].rfcId", record.rfcId, "An evidence record RFC id must be four digits.")
        }
        if (record.rfcStatus != IMPLEMENTED_STATUS) {
            throw invalid(
                "records[$index].rfcStatus",
                record.rfcStatus,
                "Evidence records are support claims and may only declare the Implemented RFC status.",
            )
        }
        if (record.schemaVersion != manifestSchemaVersion) {
            throw invalid(
                "records[$index].schemaVersion",
                record.schemaVersion.toString(),
                "An evidence record schema version must match the manifest schema version $manifestSchemaVersion.",
            )
        }
        if (!SERVICE_ID.matches(record.providerId) || record.providerId.isEmpty()) {
            throw invalid("records[$index].providerId", record.providerId, "An evidence provider id is invalid.")
        }
        if (record.target !in KWEB_RFC_HOSTED_TARGETS) {
            throw invalid(
                "records[$index].target",
                record.target,
                "Evidence targets must be hosted verification targets: ${KWEB_RFC_HOSTED_TARGETS.sorted()}.",
            )
        }
        if (!IDENTITY_VERSION.matches(record.cefVersion)) {
            throw invalid("records[$index].cefVersion", record.cefVersion, "An evidence record CEF identity is invalid.")
        }
        if (!IDENTITY_VERSION.matches(record.chromiumVersion)) {
            throw invalid(
                "records[$index].chromiumVersion",
                record.chromiumVersion,
                "An evidence record Chromium identity is invalid.",
            )
        }
        if (record.electronFixtureMajor !in 1..999) {
            throw invalid(
                "records[$index].electronFixtureMajor",
                record.electronFixtureMajor.toString(),
                "An evidence record Electron fixture major must be between 1 and 999.",
            )
        }
        if (!TEST_RUN_ID.matches(record.testRunId)) {
            throw invalid(
                "records[$index].testRunId",
                record.testRunId,
                "An evidence test run id must be 1..128 characters of [A-Za-z0-9._:-].",
            )
        }
        if (record.compatibilityStatus != READY_STATUS && record.compatibilityStatus != BLOCKED_STATUS) {
            throw invalid(
                "records[$index].compatibilityStatus",
                record.compatibilityStatus,
                "An evidence compatibility status must be $READY_STATUS or $BLOCKED_STATUS.",
            )
        }
        if ((record.serviceId == null) != (record.serviceVersion == null)) {
            throw invalid(
                "records[$index].serviceId",
                record.serviceId ?: record.serviceVersion ?: "",
                "An evidence record must declare service id and version together.",
            )
        }
        record.serviceId?.let { serviceId ->
            if (!SERVICE_ID.matches(serviceId)) {
                throw invalid("records[$index].serviceId", serviceId, "An evidence service id is invalid.")
            }
        }
        record.serviceVersion?.let { version ->
            if (!SEMANTIC_VERSION.matches(version)) {
                throw invalid(
                    "records[$index].serviceVersion",
                    version,
                    "An evidence service version must be a semantic version.",
                )
            }
        }
        if (record.serviceId == null && record.matrixRowIds.isNotEmpty()) {
            throw invalid(
                "records[$index].serviceId",
                message = "Evidence that backs a capability matrix row must bind a service contract.",
            )
        }
        if (record.matrixRowIds.size != record.matrixRowIds.toSet().size) {
            throw invalid(
                "records[$index].matrixRowIds",
                message = "An evidence record cannot repeat a matrix row id.",
            )
        }
        record.matrixRowIds.forEach { rowId ->
            if (!MATRIX_ROW_ID.matches(rowId)) {
                throw invalid("records[$index].matrixRowIds", rowId, "An evidence matrix row id is invalid.")
            }
        }
        if (record.artifacts.isEmpty() || record.artifacts.size > 32) {
            throw invalid(
                "records[$index].artifacts",
                record.artifacts.size.toString(),
                "An evidence record must retain 1..32 artifact digests.",
            )
        }
        record.artifacts.forEach { artifact ->
            if (!ARTIFACT_NAME.matches(artifact.name)) {
                throw invalid("records[$index].artifacts.name", artifact.name, "An artifact name is invalid.")
            }
            if (!DIGEST.matches(artifact.sha256)) {
                throw invalid(
                    "records[$index].artifacts.sha256",
                    artifact.name,
                    "An artifact digest must be lowercase SHA-256.",
                )
            }
        }
        KWebRfcRedaction.scanRecord(record)
    }

    private fun invalid(field: String, value: String, message: String): Nothing =
        throw KWebRfcGovernanceException(
            code = KWebRfcEvidenceErrorCode.INVALID,
            details = mapOf("field" to field, "value" to value),
            message = message,
        )

    private fun invalid(field: String, message: String): Nothing =
        throw KWebRfcGovernanceException(
            code = KWebRfcEvidenceErrorCode.INVALID,
            details = mapOf("field" to field),
            message = message,
        )
}

public object KWebRfcRedaction {
    private val PATTERNS: List<Pair<String, Regex>> = listOf(
        "runner-token" to Regex("(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{16,}"),
        "runner-token" to Regex("github_pat_[A-Za-z0-9_]{16,}"),
        "cloud-credential" to Regex("AKIA[0-9A-Z]{16}"),
        "cloud-credential" to Regex("xox[baprs]-[A-Za-z0-9-]{10,}"),
        "cloud-credential" to Regex("sk-[A-Za-z0-9]{20,}"),
        "private-key" to Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        "native-pointer" to Regex("0x[0-9a-fA-F]{8,}"),
        "private-path" to Regex("/Users/[A-Za-z0-9._-]+"),
        "private-path" to Regex("/home/[A-Za-z0-9._-]+"),
        "private-path" to Regex("C:\\\\Users\\\\[A-Za-z0-9._-]+"),
        "private-path" to Regex("/private/var/"),
    )

    /** Scans one labeled value; the detected value itself is never echoed back. */
    public fun scan(context: String, value: String) {
        PATTERNS.forEach { (kind, pattern) ->
            if (pattern.containsMatchIn(value)) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcEvidenceErrorCode.REDACTION,
                    details = mapOf("context" to context, "pattern" to kind),
                    message = "RFC governance output must not contain $kind material in '$context'.",
                )
            }
        }
    }

    public fun scanRecord(record: KWebRfcEvidenceRecord) {
        scan("rfcId", record.rfcId)
        scan("providerId", record.providerId)
        scan("target", record.target)
        scan("cefVersion", record.cefVersion)
        scan("chromiumVersion", record.chromiumVersion)
        scan("testRunId", record.testRunId)
        record.serviceId?.let { scan("serviceId", it) }
        record.serviceVersion?.let { scan("serviceVersion", it) }
        record.matrixRowIds.forEach { scan("matrixRowIds", it) }
        record.artifacts.forEach { artifact ->
            scan("artifacts.${artifact.name}.name", artifact.name)
            scan("artifacts.${artifact.name}.sha256", artifact.sha256)
        }
    }

    public fun scanDocument(document: KWebRfcDocument) {
        scan("title", document.title)
        scan("owners", document.owners)
        scan("electronSurface", document.electronSurface)
        scan("targetMapping", document.targetMapping)
        document.dependsOn.forEach { scan("dependsOn", it) }
    }

    public fun scanText(context: String, text: String) {
        scan(context, text)
    }
}
