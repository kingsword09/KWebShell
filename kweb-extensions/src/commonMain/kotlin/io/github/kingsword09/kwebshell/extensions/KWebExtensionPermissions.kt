package io.github.kingsword09.kwebshell.extensions

public enum class KWebExtensionPermissionKind {
    API_PERMISSION,
    HOST_ACCESS,
}

public data class KWebExtensionPermissionDecision(
    public val permission: String,
    public val kind: KWebExtensionPermissionKind,
)

public data class KWebExtensionPermissionReview(
    public val required: List<KWebExtensionPermissionDecision>,
    public val optional: List<KWebExtensionPermissionDecision>,
    public val requiredHosts: List<KWebExtensionPermissionDecision>,
    public val optionalHosts: List<KWebExtensionPermissionDecision>,
    public val contentScriptHosts: List<KWebExtensionPermissionDecision>,
    public val webAccessibleResourceHosts: List<KWebExtensionPermissionDecision>,
)

public class KWebExtensionPermissionPolicy(
    public val allowBroadHosts: Boolean = false,
    public val allowFileUrls: Boolean = false,
) {
    public fun review(manifest: KWebExtensionManifest): KWebExtensionPermissionReview {
        KWebExtensionManifestValidator.validate(manifest)
        return KWebExtensionPermissionReview(
            required = reviewValues(manifest.permissions, "permissions"),
            optional = reviewValues(manifest.optionalPermissions, "optional_permissions"),
            requiredHosts = reviewHosts(manifest.hostPermissions, "host_permissions"),
            optionalHosts = reviewHosts(manifest.optionalHostPermissions, "optional_host_permissions"),
            contentScriptHosts = reviewHosts(
                manifest.contentScripts.flatMap { it.matches }.distinct(),
                "content_scripts.matches",
            ),
            webAccessibleResourceHosts = reviewHosts(
                manifest.webAccessibleResources.flatMap { it.matches }.distinct(),
                "web_accessible_resources.matches",
            ),
        )
    }

    private fun reviewHosts(values: List<String>, field: String): List<KWebExtensionPermissionDecision> =
        values.map { value ->
            when {
                value == "<all_urls>" && (!allowBroadHosts || !allowFileUrls) -> extensionFailure(
                    code = "extensions.permission.all-urls-denied",
                    details = mapOf("field" to field),
                    message = "The extension requests <all_urls>, which requires broad-host and file-access approval.",
                )
                parseKWebHostPattern(value)?.host == "*" && !allowBroadHosts -> extensionFailure(
                    code = "extensions.permission.broad-host-denied",
                    details = mapOf("field" to field, "pattern" to value),
                    message = "The extension requests access to every host for a URL scheme.",
                )
                value.startsWith("file://") && !allowFileUrls -> extensionFailure(
                    code = "extensions.permission.file-access-denied",
                    details = mapOf("field" to field),
                    message = "The extension requests file:// access, which this policy denies.",
                )
                else -> KWebExtensionPermissionDecision(value, KWebExtensionPermissionKind.HOST_ACCESS)
            }
        }

    private fun reviewValues(values: List<String>, field: String): List<KWebExtensionPermissionDecision> =
        values.map { value ->
            if (value in DENIED_PERMISSIONS) {
                extensionFailure(
                    code = "extensions.permission.denied",
                    details = mapOf("permission" to value, "field" to field),
                    message = "The extension permission '$value' is denied by KWebShell policy.",
                )
            }
            if (value !in PACKAGE_ADMISSIBLE_PERMISSIONS) {
                extensionFailure(
                    code = "extensions.permission.unsupported",
                    details = mapOf("permission" to value, "field" to field),
                    message = "The extension permission '$value' is outside the published capability matrix.",
                )
            }
            KWebExtensionPermissionDecision(value, KWebExtensionPermissionKind.API_PERMISSION)
        }

    public companion object {
        public val PACKAGE_ADMISSIBLE_PERMISSIONS: Set<String> = setOf(
            "activeTab",
            "alarms",
            "contextMenus",
            "declarativeNetRequest",
            "declarativeNetRequestFeedback",
            "declarativeNetRequestWithHostAccess",
            "notifications",
            "offscreen",
            "scripting",
            "storage",
            "tabs",
            "unlimitedStorage",
            "windows",
        )

        public val DENIED_PERMISSIONS: Set<String> = setOf(
            "debugger",
            "management",
            "nativeMessaging",
            "proxy",
            "webRequestBlocking",
        )
    }
}
