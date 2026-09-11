package io.github.kingsword09.kwebshell.rfc

import java.nio.file.Files
import java.nio.file.Path

public enum class KWebRfcStatus(public val label: String) {
    PROPOSED("Proposed"),
    ACCEPTED("Accepted"),
    IMPLEMENTING("Implementing"),
    IMPLEMENTED("Implemented"),
    REJECTED("Rejected"),
    SUPERSEDED("Superseded"),
    ;

    public companion object {
        public fun parse(value: String): KWebRfcStatus {
            val normalized = value.trim().lowercase()
            return entries.singleOrNull { it.label.lowercase() == normalized }
                ?: throw KWebRfcGovernanceException(
                    code = KWebRfcCatalogErrorCode.INVALID,
                    details = mapOf("field" to "Status", "value" to value),
                    message = "An RFC status must be one of ${entries.joinToString { it.label }}.",
                )
        }
    }
}

public object KWebRfcCatalogErrorCode {
    public const val INVALID: String = "rfc.catalog.invalid"
    public const val DUPLICATE_ID: String = "rfc.catalog.duplicate-id"
}

/**
 * Machine-readable front matter of one RFC document. Only the catalog keys below
 * are parsed; every other machine field must go through a schema, not prose.
 */
public data class KWebRfcDocument(
    public val id: String,
    public val fileName: String,
    public val title: String,
    public val status: KWebRfcStatus,
    public val priority: String,
    public val owners: String,
    public val dependsOn: List<String>,
    public val electronSurface: String,
    public val targetMapping: String,
    public val declaredPlatformTargets: Set<String>,
) {
    /**
     * Hosted targets whose evidence this RFC requires. An RFC without a
     * platform-specific declaration requires all three hosted targets.
     */
    public val requiredHostedTargets: Set<String>
        get() = if (declaredPlatformTargets.isEmpty()) {
            KWEB_RFC_HOSTED_TARGETS
        } else {
            declaredPlatformTargets.mapNotNull(KWEB_RFC_PLATFORM_HOSTED_TARGETS::get).toSet()
        }

    public val isPlatformSpecific: Boolean
        get() = declaredPlatformTargets.isNotEmpty()
}

public object KWebRfcCatalog {
    private val RFC_FILE = Regex("([0-9]{4})-[a-z0-9-]+\\.md")
    private val TITLE = Regex("^# RFC ([0-9]{4}): (.+)$")
    private val BULLET = Regex("^- ([A-Za-z ]+): (.*)$")
    private val RFC_ID = Regex("[0-9]{4}")

    /**
     * Non-numeric dependency tokens that reference delivered Phase 11
     * prerequisites rather than a numbered RFC.
     */
    private val PREREQUISITE_TOKENS: Set<String> = setOf(
        "existing prerequisites",
        "existing phase 11 prerequisites",
    )

    private val PRIORITIES: Set<String> = setOf("P0", "P1", "P2")
    private val PLATFORM_TARGETS: Set<String> = setOf("macos", "windows", "linux")

    private const val KEY_STATUS = "Status"
    private const val KEY_PRIORITY = "Priority"
    private const val KEY_OWNERS = "Owners"
    private const val KEY_DEPENDS_ON = "Depends on"
    private const val KEY_ELECTRON_SURFACE = "Electron migration surface"
    private const val KEY_TARGET_MAPPING = "Target mapping"
    private const val KEY_PLATFORM_TARGETS = "Platform targets"
    private val KNOWN_KEYS: Set<String> = setOf(
        KEY_STATUS,
        KEY_PRIORITY,
        KEY_OWNERS,
        KEY_DEPENDS_ON,
        KEY_ELECTRON_SURFACE,
        KEY_TARGET_MAPPING,
        KEY_PLATFORM_TARGETS,
    )

    /** Loads and validates every RFC document in the catalog directory. */
    public fun load(directory: Path): List<KWebRfcDocument> {
        if (!Files.isDirectory(directory)) {
            throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("operation" to "load"),
                message = "The RFC catalog directory does not exist.",
            )
        }
        val documents = Files.list(directory).use { stream ->
            stream
                .map { it.fileName.toString() }
                .toList()
                .filter { file -> RFC_FILE.matches(file) }
                .map { file ->
                    parse(
                        fileName = file,
                        text = Files.readString(directory.resolve(file)),
                    )
                }
        }
        return validate(documents)
    }

    /** Parses one RFC document; validation across the catalog happens in [validate]. */
    public fun parse(fileName: String, text: String): KWebRfcDocument {
        val fileMatch = RFC_FILE.matchEntire(fileName)
            ?: throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("file" to fileName),
                message = "An RFC file must be named NNNN-lowercase-slug.md.",
            )
        val fileId = fileMatch.groupValues[1]

        val lines = text.lines()
        val titleLine = lines.firstOrNull { TITLE.matches(it) }
            ?: throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("file" to fileName),
                message = "An RFC document must start with '# RFC NNNN: Title'.",
            )
        val titleMatch = TITLE.matchEntire(titleLine)!!
        val titleId = titleMatch.groupValues[1]
        if (titleId != fileId) {
            throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("file" to fileName, "titleId" to titleId),
                message = "The RFC title id does not match its file name.",
            )
        }

        var bulletsStarted = false
        val frontMatter = linkedMapOf<String, String>()
        for (line in lines) {
            if (line.isBlank() && !bulletsStarted) continue
            val bullet = BULLET.matchEntire(line)
            if (bullet == null) {
                if (bulletsStarted && line.startsWith(" ") && frontMatter.isNotEmpty()) {
                    // Markdown bullet continuation: fold into the previous value.
                    val lastKey = frontMatter.keys.last()
                    frontMatter[lastKey] = frontMatter.getValue(lastKey) + " " + line.trim()
                    continue
                }
                if (bulletsStarted) break
                continue
            }
            bulletsStarted = true
            val key = bullet.groupValues[1].trim()
            val value = bullet.groupValues[2].trim()
            if (key !in KNOWN_KEYS) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcCatalogErrorCode.INVALID,
                    details = mapOf("file" to fileName, "field" to key),
                    message = "An RFC front-matter key is not part of the RFC schema.",
                )
            }
            if (frontMatter.containsKey(key)) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcCatalogErrorCode.INVALID,
                    details = mapOf("file" to fileName, "field" to key),
                    message = "An RFC front-matter key is declared twice.",
                )
            }
            frontMatter[key] = value
        }

        val missing = listOf(KEY_STATUS, KEY_PRIORITY, KEY_OWNERS, KEY_DEPENDS_ON, KEY_ELECTRON_SURFACE, KEY_TARGET_MAPPING)
            .filterNot(frontMatter::containsKey)
        if (missing.isNotEmpty()) {
            throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("file" to fileName, "missing" to missing.joinToString()),
                message = "An RFC document is missing required front matter.",
            )
        }

        val status = KWebRfcStatus.parse(frontMatter.getValue(KEY_STATUS))
        val priority = frontMatter.getValue(KEY_PRIORITY)
        if (priority !in PRIORITIES) {
            throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.INVALID,
                details = mapOf("file" to fileName, "value" to priority),
                message = "An RFC priority must be one of ${PRIORITIES.sorted()}.",
            )
        }

        val dependsOn = frontMatter.getValue(KEY_DEPENDS_ON)
            .split(',')
            .map { token ->
                // The catalog spells dependencies as "RFC 0001"; normalize to the id.
                token.trim().replace(Regex("^[Rr][Ff][Cc]\\s+"), "").trim()
            }
            .filterNot(String::isEmpty)
        dependsOn.forEach { token ->
            if (!RFC_ID.matches(token) && token.lowercase() !in PREREQUISITE_TOKENS) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcCatalogErrorCode.INVALID,
                    details = mapOf("file" to fileName, "value" to token),
                    message = "An RFC dependency must be a four-digit RFC id or the existing-prerequisites token.",
                )
            }
        }

        val platformTargets = frontMatter[KEY_PLATFORM_TARGETS]
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filterNot(String::isEmpty)
            ?.toSet()
            ?: emptySet()
        platformTargets.forEach { target ->
            if (target !in PLATFORM_TARGETS) {
                throw KWebRfcGovernanceException(
                    code = KWebRfcCatalogErrorCode.INVALID,
                    details = mapOf("file" to fileName, "value" to target),
                    message = "An RFC platform target must be one of ${PLATFORM_TARGETS.sorted()}.",
                )
            }
        }

        val document = KWebRfcDocument(
            id = fileId,
            fileName = fileName,
            title = titleMatch.groupValues[2].trim(),
            status = status,
            priority = priority,
            owners = frontMatter.getValue(KEY_OWNERS),
            dependsOn = dependsOn,
            electronSurface = frontMatter.getValue(KEY_ELECTRON_SURFACE),
            targetMapping = frontMatter.getValue(KEY_TARGET_MAPPING),
            declaredPlatformTargets = platformTargets,
        )
        KWebRfcRedaction.scanDocument(document)
        return document
    }

    /** Validates cross-document rules: unique ids, existing dependencies, and dependency readiness. */
    public fun validate(documents: List<KWebRfcDocument>): List<KWebRfcDocument> {
        val byId = documents.associateBy { it.id }
        if (byId.size != documents.size) {
            val duplicates = documents.groupBy { it.id }.filterValues { it.size > 1 }.keys
            throw KWebRfcGovernanceException(
                code = KWebRfcCatalogErrorCode.DUPLICATE_ID,
                details = mapOf("rfcIds" to duplicates.sorted().joinToString()),
                message = "The RFC catalog contains duplicate RFC ids.",
            )
        }
        val ordered = documents.sortedBy { it.id }
        ordered.forEach { document ->
            val numericDependencies = document.dependsOn.filter(RFC_ID::matches)
            numericDependencies.forEach { dependency ->
                if (dependency !in byId) {
                    throw KWebRfcGovernanceException(
                        code = KWebRfcCatalogErrorCode.INVALID,
                        details = mapOf("rfc" to document.id, "dependency" to dependency),
                        message = "An RFC depends on an unknown RFC id.",
                    )
                }
            }
            if (document.status == KWebRfcStatus.IMPLEMENTING || document.status == KWebRfcStatus.IMPLEMENTED) {
                val notImplemented = numericDependencies
                    .filter { byId.getValue(it).status != KWebRfcStatus.IMPLEMENTED }
                if (notImplemented.isNotEmpty()) {
                    throw KWebRfcGovernanceException(
                        code = KWebRfcCatalogErrorCode.INVALID,
                        details = mapOf("rfc" to document.id, "dependencies" to notImplemented.joinToString()),
                        message = "An Implementing or Implemented RFC requires all numeric dependencies to be Implemented.",
                    )
                }
            }
        }
        return ordered
    }
}
