package io.github.kingsword09.kwebshell.extensions

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

private const val MAX_MANIFEST_CHARACTERS: Int = 1024 * 1024

@Serializable
public data class KWebExtensionManifest(
    @SerialName("manifest_version") public val manifestVersion: Int,
    public val name: String,
    public val version: String,
    public val description: String? = null,
    @SerialName("version_name") public val versionName: String? = null,
    @SerialName("minimum_chrome_version") public val minimumChromeVersion: String? = null,
    public val key: String? = null,
    public val permissions: List<String> = emptyList(),
    @SerialName("optional_permissions") public val optionalPermissions: List<String> = emptyList(),
    @SerialName("host_permissions") public val hostPermissions: List<String> = emptyList(),
    @SerialName("optional_host_permissions") public val optionalHostPermissions: List<String> = emptyList(),
    public val background: KWebExtensionBackground? = null,
    @SerialName("content_scripts") public val contentScripts: List<KWebExtensionContentScript> = emptyList(),
    public val action: KWebExtensionAction? = null,
    public val icons: Map<String, String> = emptyMap(),
    @SerialName("options_page") public val optionsPage: String? = null,
    @SerialName("options_ui") public val optionsUi: KWebExtensionOptionsUi? = null,
    @SerialName("devtools_page") public val devtoolsPage: String? = null,
    @SerialName("side_panel") public val sidePanel: KWebExtensionSidePanel? = null,
    @SerialName("web_accessible_resources")
    public val webAccessibleResources: List<KWebExtensionWebAccessibleResource> = emptyList(),
    @SerialName("declarative_net_request")
    public val declarativeNetRequest: KWebExtensionDeclarativeNetRequest? = null,
    public val incognito: String? = null,
)

@Serializable
public data class KWebExtensionBackground(
    @SerialName("service_worker") public val serviceWorker: String? = null,
    public val type: String? = null,
    public val scripts: List<String>? = null,
    public val page: String? = null,
)

@Serializable
public data class KWebExtensionContentScript(
    public val matches: List<String>,
    public val js: List<String> = emptyList(),
    public val css: List<String> = emptyList(),
    @SerialName("run_at") public val runAt: String? = null,
    @SerialName("all_frames") public val allFrames: Boolean = false,
    @SerialName("match_about_blank") public val matchAboutBlank: Boolean = false,
)

@Serializable
public data class KWebExtensionAction(
    @SerialName("default_popup") public val defaultPopup: String? = null,
    @SerialName("default_title") public val defaultTitle: String? = null,
    @SerialName("default_icon") public val defaultIcon: Map<String, String> = emptyMap(),
)

@Serializable
public data class KWebExtensionOptionsUi(
    public val page: String,
    @SerialName("open_in_tab") public val openInTab: Boolean = false,
)

@Serializable
public data class KWebExtensionSidePanel(
    @SerialName("default_path") public val defaultPath: String? = null,
)

@Serializable
public data class KWebExtensionWebAccessibleResource(
    public val resources: List<String>,
    public val matches: List<String> = emptyList(),
    @SerialName("extension_ids") public val extensionIds: List<String> = emptyList(),
)

@Serializable
public data class KWebExtensionDeclarativeNetRequest(
    @SerialName("rule_resources") public val ruleResources: List<KWebExtensionRuleResource>,
)

@Serializable
public data class KWebExtensionRuleResource(
    @SerialName("id") public val id: String,
    @SerialName("enabled") public val enabled: Boolean,
    @SerialName("path") public val path: String,
)

public object KWebExtensionManifestParser {
    private val json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        isLenient = false
        allowSpecialFloatingPointValues = false
    }

    public fun parse(text: String, requirePublicKey: Boolean = false): KWebExtensionManifest {
        if (text.length > MAX_MANIFEST_CHARACTERS) {
            extensionFailure(
                code = "extensions.manifest.text-too-large",
                details = mapOf("characters" to text.length.toString()),
                message = "The extension manifest exceeds the bounded character count.",
            )
        }
        KWebStrictJsonObjectKeyValidator.validate(text)
        val manifest = try {
            json.decodeFromString<KWebExtensionManifest>(text)
        } catch (error: SerializationException) {
            extensionFailure(
                code = "extensions.manifest.invalid-json",
                message = "The extension manifest is not valid JSON for the supported MV3 schema.",
                cause = error,
            )
        }
        KWebExtensionManifestValidator.validate(manifest, requirePublicKey)
        return manifest
    }
}

public object KWebExtensionManifestValidator {
    public fun validate(manifest: KWebExtensionManifest, requirePublicKey: Boolean = false) {
        if (manifest.manifestVersion != 3) {
            extensionFailure(
                code = "extensions.manifest.version-unsupported",
                details = mapOf("manifestVersion" to manifest.manifestVersion.toString()),
                message = "Only Manifest V3 extensions are accepted.",
            )
        }
        if (manifest.name.isBlank()) {
            extensionFailure(
                code = "extensions.manifest.name-empty",
                message = "The extension name cannot be blank.",
            )
        }
        requireLength(manifest.name, 1, 75, "name")
        requireVersion(manifest.version, "version")
        manifest.description?.let { requireLength(it, 0, 132, "description") }
        manifest.versionName?.let { requireLength(it, 1, 132, "version_name") }
        manifest.minimumChromeVersion?.let { requireVersion(it, "minimum_chrome_version") }
        validateResourcePath(manifest.optionsPage, "options_page")
        validateResourcePath(manifest.devtoolsPage, "devtools_page")
        validateResourcePath(manifest.background?.serviceWorker, "background.service_worker")
        manifest.background?.let { background ->
            if (background.scripts != null || background.page != null) {
                extensionFailure(
                    code = "extensions.manifest.background-mv2-field",
                    message = "Manifest V3 background cannot declare scripts or page.",
                )
            }
            if (background.serviceWorker.isNullOrBlank()) {
                extensionFailure(
                    code = "extensions.manifest.background-service-worker-missing",
                    message = "Manifest V3 background must declare service_worker.",
                )
            }
            if (background.type != null && background.type != "module") {
                extensionFailure(
                    code = "extensions.manifest.background-type-invalid",
                    details = mapOf("type" to background.type),
                    message = "Manifest V3 background type must be module when present.",
                )
            }
        }
        validateIcons(manifest.icons, "icons")
        manifest.action?.let { action ->
            validateResourcePath(action.defaultPopup, "action.default_popup")
            validateIcons(action.defaultIcon, "action.default_icon")
            action.defaultTitle?.let { requireLength(it, 0, 75, "action.default_title") }
        }
        if (manifest.optionsPage != null && manifest.optionsUi != null) {
            extensionFailure(
                code = "extensions.manifest.options-conflict",
                message = "A manifest cannot declare both options_page and options_ui.",
            )
        }
        manifest.optionsUi?.let { validateResourcePath(it.page, "options_ui.page") }
        manifest.sidePanel?.defaultPath?.let { validateResourcePath(it, "side_panel.default_path") }
        manifest.contentScripts.forEachIndexed { index, script ->
            requireUnique(script.matches, "content_scripts[$index].matches")
            requireUnique(script.js, "content_scripts[$index].js")
            requireUnique(script.css, "content_scripts[$index].css")
            if (script.matches.isEmpty()) {
                extensionFailure(
                    code = "extensions.manifest.content-script-matches-missing",
                    details = mapOf("index" to index.toString()),
                    message = "Every content script must declare at least one match pattern.",
                )
            }
            script.matches.forEach { validateHostPattern(it, "content_scripts[$index].matches") }
            if (script.js.isEmpty() && script.css.isEmpty()) {
                extensionFailure(
                    code = "extensions.manifest.content-script-resources-missing",
                    details = mapOf("index" to index.toString()),
                    message = "Every content script must declare JavaScript or CSS resources.",
                )
            }
            validatePaths(script.js, "content_scripts[$index].js")
            validatePaths(script.css, "content_scripts[$index].css")
            script.runAt?.let { runAt ->
                if (runAt !in setOf("document_start", "document_end", "document_idle")) {
                    extensionFailure(
                        code = "extensions.manifest.content-script-run-at-invalid",
                        details = mapOf("runAt" to runAt),
                        message = "Content script run_at must be a published MV3 value.",
                    )
                }
            }
        }
        manifest.webAccessibleResources.forEachIndexed { index, resource ->
            val canonicalResources = resource.resources.map(::canonicalWebAccessibleResourcePath)
            requireUnique(canonicalResources, "web_accessible_resources[$index].resources")
            requireUnique(resource.matches, "web_accessible_resources[$index].matches")
            requireUnique(resource.extensionIds, "web_accessible_resources[$index].extension_ids")
            if (resource.resources.isEmpty() || resource.matches.isEmpty() && resource.extensionIds.isEmpty()) {
                extensionFailure(
                    code = "extensions.manifest.web-accessible-resource-incomplete",
                    details = mapOf("index" to index.toString()),
                    message = "Web accessible resources need resources and matches or extension_ids.",
                )
            }
            canonicalResources.forEach {
                validateResourcePath(it, "web_accessible_resources[$index].resources", allowWildcard = true)
            }
            resource.matches.forEach {
                validateWebAccessibleMatchPattern(it, "web_accessible_resources[$index].matches")
            }
            resource.extensionIds.forEach { validateExtensionId(it, "web_accessible_resources[$index].extension_ids") }
        }
        manifest.declarativeNetRequest?.let { declaration ->
            if (declaration.ruleResources.isEmpty()) {
                extensionFailure(
                    code = "extensions.manifest.rule-resources-missing",
                    message = "declarative_net_request must declare at least one rule resource.",
                )
            }
            if (manifest.permissions.none { it in DECLARATIVE_NET_REQUEST_PERMISSIONS }) {
                extensionFailure(
                    code = "extensions.manifest.declarative-net-request-permission-missing",
                    message = "declarative_net_request requires an admissible declarativeNetRequest permission.",
                )
            }
            requireUnique(declaration.ruleResources.map { it.id }, "declarative_net_request.rule_resources.id")
            declaration.ruleResources.forEach { rule ->
                if (!RULE_ID.matches(rule.id)) {
                    extensionFailure(
                        code = "extensions.manifest.rule-resource-id-invalid",
                        details = mapOf("id" to rule.id),
                        message = "declarative_net_request rule resource IDs must be ASCII identifiers.",
                    )
                }
                validateResourcePath(rule.path, "declarative_net_request.rule_resources.path")
            }
        }
        manifest.incognito?.let { value ->
            if (value !in setOf("spanning", "split", "not_allowed")) {
                extensionFailure(
                    code = "extensions.manifest.incognito-invalid",
                    details = mapOf("incognito" to value),
                    message = "incognito must be spanning, split, or not_allowed when present.",
                )
            }
        }
        requireUnique(manifest.permissions, "permissions")
        requireUnique(manifest.optionalPermissions, "optional_permissions")
        requireUnique(manifest.hostPermissions, "host_permissions")
        requireUnique(manifest.optionalHostPermissions, "optional_host_permissions")
        requireUnique(
            manifest.permissions + manifest.hostPermissions,
            "required_permissions_and_hosts",
        )
        requireUnique(
            manifest.optionalPermissions + manifest.optionalHostPermissions,
            "optional_permissions_and_hosts",
        )
        requireDisjoint(
            manifest.permissions + manifest.hostPermissions,
            manifest.optionalPermissions + manifest.optionalHostPermissions,
        )
        validateExtensionPermissions(manifest.permissions, "permissions")
        validateExtensionPermissions(manifest.optionalPermissions, "optional_permissions")
        manifest.hostPermissions.forEach { validateHostPattern(it, "host_permissions") }
        manifest.optionalHostPermissions.forEach { validateHostPattern(it, "optional_host_permissions") }
        manifest.key?.let { key ->
            if (key.isBlank()) {
                extensionFailure(
                    code = "extensions.manifest.public-key-empty",
                    message = "The manifest public key cannot be empty.",
                )
            }
            decodeKWebPublicKeyDer(key)
        }
        if (requirePublicKey && manifest.key == null) {
            extensionFailure(
                code = "extensions.manifest.public-key-missing",
                message = "An unpacked extension must declare its public key.",
            )
        }
    }

    private fun validateExtensionPermissions(values: List<String>, field: String) {
        values.forEach { value ->
            if (value.isBlank()) {
                extensionFailure(
                    code = "extensions.manifest.permission-empty",
                    details = mapOf("field" to field),
                    message = "Manifest permissions cannot contain empty strings.",
                )
            }
            if (value == "<all_urls>" || value.contains("://")) {
                extensionFailure(
                    code = "extensions.manifest.host-permission-field-invalid",
                    details = mapOf("field" to field, "permission" to value),
                    message = "Manifest V3 host patterns must be declared in a host permission field.",
                )
            }
        }
    }

    private fun validatePaths(values: Collection<String>, field: String) {
        values.forEach { validateResourcePath(it, field) }
    }

    private fun validateIcons(values: Map<String, String>, field: String) {
        values.forEach { (size, path) ->
            if (!ICON_SIZE.matches(size) || size.toIntOrNull() !in 1..MAX_ICON_SIZE) {
                extensionFailure(
                    code = "extensions.manifest.icon-size-invalid",
                    details = mapOf("field" to field, "size" to size),
                    message = "Manifest icon size '$size' is not a portable positive pixel size.",
                )
            }
            validateResourcePath(path, field)
        }
    }

    private fun validateResourcePath(value: String?, field: String, allowWildcard: Boolean = false) {
        if (value == null) return
        when (portablePathIssue(value, allowWildcard)) {
            null -> Unit
            KWebPortablePathIssue.TRAVERSAL -> extensionFailure(
                code = "extensions.manifest.resource-path-traversal",
                details = mapOf("field" to field, "path" to value),
                message = "Manifest resource path '$value' contains unsafe traversal segments.",
            )
            KWebPortablePathIssue.WILDCARD -> extensionFailure(
                code = "extensions.manifest.resource-path-wildcard-invalid",
                details = mapOf("field" to field, "path" to value),
                message = "Manifest resource path '$value' cannot contain wildcard syntax.",
            )
            else -> extensionFailure(
                code = "extensions.manifest.resource-path-invalid",
                details = mapOf("field" to field, "path" to value),
                message = "Manifest resource path '$value' is not portable across desktop filesystems.",
            )
        }
    }

    private fun validateHostPattern(value: String, field: String) {
        if (value == "<all_urls>") return
        if (parseKWebHostPattern(value) == null) {
            extensionFailure(
                code = "extensions.manifest.host-pattern-invalid",
                details = mapOf("field" to field, "pattern" to value),
                message = "Manifest host pattern '$value' is invalid.",
            )
        }
    }

    private fun validateWebAccessibleMatchPattern(value: String, field: String) {
        validateHostPattern(value, field)
        if (value == "<all_urls>") return
        val pathStart = value.indexOf('/', value.indexOf("://") + 3)
        if (pathStart < 0 || value.substring(pathStart) != "/*") {
            extensionFailure(
                code = "extensions.manifest.web-accessible-match-path-invalid",
                details = mapOf("field" to field, "pattern" to value),
                message = "Web accessible resource match patterns must expose an origin with the path '/*'.",
            )
        }
    }

    private fun requireUnique(values: List<String>, field: String) {
        val duplicate = values.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key ?: return
        extensionFailure(
            code = "extensions.manifest.duplicate-list-entry",
            details = mapOf("field" to field, "value" to duplicate),
            message = "Manifest list '$field' contains duplicate value '$duplicate'.",
        )
    }

    private fun requireDisjoint(required: List<String>, optional: List<String>) {
        val duplicate = required.toSet().intersect(optional.toSet()).firstOrNull() ?: return
        extensionFailure(
            code = "extensions.manifest.required-optional-overlap",
            details = mapOf("value" to duplicate),
            message = "Manifest value '$duplicate' cannot be both required and optional.",
        )
    }

    private fun validateExtensionId(value: String, field: String) {
        if (!EXTENSION_ID.matches(value)) {
            extensionFailure(
                code = "extensions.manifest.extension-id-invalid",
                details = mapOf("field" to field, "id" to value),
                message = "Manifest extension ID '$value' is invalid.",
            )
        }
    }

    private fun requireLength(value: String, minimum: Int, maximum: Int, field: String) {
        if (value.length !in minimum..maximum) {
            extensionFailure(
                code = "extensions.manifest.field-length-invalid",
                details = mapOf("field" to field, "length" to value.length.toString()),
                message = "Manifest field '$field' has an invalid length.",
            )
        }
    }

    private fun requireVersion(value: String, field: String) {
        val components = value.split('.')
        if (components.size !in 1..4 || components.any { component ->
                component.isEmpty() || component.length > 5 ||
                    component.length > 1 && component.startsWith('0') ||
                    component.any { it !in '0'..'9' } ||
                    component.toIntOrNull() !in 0..MAX_VERSION_COMPONENT
            }
            || components.all { it.toIntOrNull() == 0 }
        ) {
            extensionFailure(
                code = "extensions.manifest.version-invalid",
                details = mapOf("field" to field, "value" to value),
                message = "Manifest field '$field' is not a valid Chrome version.",
            )
        }
    }

    private val EXTENSION_ID = Regex("[a-p]{32}")
    private val RULE_ID = Regex("[A-Za-z0-9_-]{1,64}")
    private val ICON_SIZE = Regex("[1-9][0-9]{0,3}")
    private val DECLARATIVE_NET_REQUEST_PERMISSIONS = setOf(
        "declarativeNetRequest",
        "declarativeNetRequestWithHostAccess",
    )
    private const val MAX_ICON_SIZE: Int = 1024
    private const val MAX_VERSION_COMPONENT: Int = 65_535
}

internal data class KWebParsedHostPattern(
    val scheme: String,
    val host: String,
    val path: String,
)

internal fun parseKWebHostPattern(value: String): KWebParsedHostPattern? {
    if (value.any { it == '\\' || it == '\u0000' || it.isWhitespace() }) return null
    val separator = value.indexOf("://")
    if (separator <= 0 || separator != value.lastIndexOf("://")) return null
    val scheme = value.substring(0, separator)
    if (scheme !in MATCH_SCHEMES) return null
    val authorityAndPath = value.substring(separator + 3)
    val pathStart = authorityAndPath.indexOf('/')
    if (pathStart < 0) return null
    val authority = authorityAndPath.substring(0, pathStart)
    val path = authorityAndPath.substring(pathStart)
    if (path.isEmpty() || '#' in path) return null
    if (scheme == "file") {
        return if (authority.isEmpty()) KWebParsedHostPattern(scheme, "", path) else null
    }
    if (authority.isEmpty() || '@' in authority) return null
    val host = authority.removeSuffix(":*")
    if (':' in host || host.isEmpty()) return null
    if (host == "*") return KWebParsedHostPattern(scheme, host, path)
    val domain = if (host.startsWith("*.")) host.substring(2) else host
    if ('*' in domain || domain.length > 253 || domain.endsWith('.')) return null
    val validDomain = domain.split('.').all { label ->
        label.isNotEmpty() && label.length <= 63 &&
            label.first().isAsciiLetterOrDigit() && label.last().isAsciiLetterOrDigit() &&
            label.all { it.isAsciiLetterOrDigit() || it == '-' }
    }
    return if (validDomain) KWebParsedHostPattern(scheme, host, path) else null
}

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private val MATCH_SCHEMES = setOf("*", "http", "https", "file")

internal fun decodeKWebPublicKeyDer(value: String): ByteArray {
    if (value.length > MAX_PUBLIC_KEY_BASE64_CHARACTERS) {
        extensionFailure(
            code = "extensions.manifest.public-key-too-large",
            details = mapOf("characters" to value.length.toString()),
            message = "The extension public key exceeds the bounded SubjectPublicKeyInfo size.",
        )
    }
    val decoded = try {
        Base64.Default.decode(value)
    } catch (error: IllegalArgumentException) {
        extensionFailure(
            code = "extensions.manifest.public-key-invalid-base64",
            message = "The extension public key is not valid base64.",
            cause = error,
        )
    }
    if (decoded.isEmpty() || decoded.size > KWEB_EXTENSION_MAX_PUBLIC_KEY_BYTES) {
        extensionFailure(
            code = "extensions.manifest.public-key-size-invalid",
            details = mapOf("size" to decoded.size.toString()),
            message = "The extension public key has an invalid bounded SubjectPublicKeyInfo size.",
        )
    }
    return decoded
}

internal const val KWEB_EXTENSION_MAX_PUBLIC_KEY_BYTES: Int = 16 * 1024
private const val MAX_PUBLIC_KEY_BASE64_CHARACTERS = 24 * 1024
