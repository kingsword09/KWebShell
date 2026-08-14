package io.github.kingsword09.kwebshell.extensions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KWebExtensionManifestTest {
    @Test
    fun parsesACompleteMv3Manifest() {
        val manifest = KWebExtensionManifestParser.parse(
            """
            {
              "manifest_version": 3,
              "name": "Example extension",
              "version": "1.2.3",
              "description": "A complete package boundary fixture.",
              "background": {"service_worker": "worker.js", "type": "module"},
              "permissions": ["storage", "declarativeNetRequest"],
              "host_permissions": ["https://*.example.com/*"],
              "content_scripts": [{"matches": ["https://example.com/*"], "js": ["content.js"], "run_at": "document_idle"}],
              "action": {"default_popup": "popup.html", "default_title": "Open", "default_icon": {"128": "icon.png"}},
              "icons": {"128": "icon.png"},
              "options_ui": {"page": "options.html", "open_in_tab": true},
              "web_accessible_resources": [{"resources": ["/assets/*.png"], "matches": ["https://example.com/*"]}],
              "declarative_net_request": {"rule_resources": [{"id": "rules", "enabled": true, "path": "rules.json"}]}
            }
            """.trimIndent(),
        )

        assertEquals(3, manifest.manifestVersion)
        assertEquals("worker.js", manifest.background?.serviceWorker)
        assertEquals("rules.json", manifest.declarativeNetRequest?.ruleResources?.single()?.path)
    }

    @Test
    fun rejectsUnknownFieldsAndMv2Background() {
        val unknown = assertFailsWith<KWebExtensionVerificationException> {
            KWebExtensionManifestParser.parse("""{"manifest_version":3,"name":"x","version":"1","unknown":true}""")
        }
        assertEquals("extensions.manifest.invalid-json", unknown.code)

        val mv2 = assertFailsWith<KWebExtensionVerificationException> {
            KWebExtensionManifestParser.parse(
                """{"manifest_version":3,"name":"x","version":"1","background":{"scripts":["legacy.js"]}}""",
            )
        }
        assertEquals("extensions.manifest.background-mv2-field", mv2.code)

        assertCode("extensions.manifest.invalid-json") {
            KWebExtensionManifestParser.parse(
                """{"manifest_version":3,"name":"x","version":"1","commands":{"open":{"suggested_key":{"default":"Ctrl+K"}}}}""",
            )
        }
    }

    @Test
    fun rejectsDuplicateJsonKeysIncludingEscapedNames() {
        val duplicate = assertFailsWith<KWebExtensionVerificationException> {
            KWebExtensionManifestParser.parse(
                """{"manifest_version":3,"name":"first","\u006eame":"second","version":"1"}""",
            )
        }

        assertEquals("extensions.manifest.duplicate-json-key", duplicate.code)
        assertEquals("name", duplicate.details["key"])
    }

    @Test
    fun rejectsMalformedAndUnboundedJsonBeforeDeserialization() {
        assertCode("extensions.manifest.invalid-json") {
            KWebExtensionManifestParser.parse("""{"manifest_version":3,"name":"x","version":"1",}""")
        }
        assertCode("extensions.manifest.text-too-large") {
            KWebExtensionManifestParser.parse(" ".repeat(1024 * 1024 + 1))
        }
        assertCode("extensions.manifest.public-key-invalid-base64") {
            KWebExtensionManifestParser.parse(
                """{"manifest_version":3,"name":"x","version":"1","key":"%%%"}""",
            )
        }
    }

    @Test
    fun rejectsUnsafePatternsAndNondeterministicLists() {
        val cases = listOf(
            KWebExtensionManifest(
                manifestVersion = 3,
                name = "x",
                version = "1",
                hostPermissions = listOf("https://example.com:443/*"),
            ) to "extensions.manifest.host-pattern-invalid",
            KWebExtensionManifest(
                manifestVersion = 3,
                name = "x",
                version = "1",
                background = KWebExtensionBackground(serviceWorker = "../worker.js"),
            ) to "extensions.manifest.resource-path-traversal",
            KWebExtensionManifest(
                manifestVersion = 3,
                name = "x",
                version = "1",
                permissions = listOf("storage", "storage"),
            ) to "extensions.manifest.duplicate-list-entry",
            KWebExtensionManifest(
                manifestVersion = 3,
                name = "x",
                version = "1",
                permissions = listOf("storage"),
                optionalPermissions = listOf("storage"),
            ) to "extensions.manifest.required-optional-overlap",
            KWebExtensionManifest(
                manifestVersion = 3,
                name = "x",
                version = "1",
                permissions = listOf("https://example.com/*"),
            ) to "extensions.manifest.host-permission-field-invalid",
        )
        cases.forEach { (manifest, code) ->
            val error = assertFailsWith<KWebExtensionVerificationException> {
                KWebExtensionManifestValidator.validate(manifest)
            }
            assertEquals(code, error.code)
        }
    }

    @Test
    fun rejectsMalformedVersionOptionsIconsAndDnrMetadata() {
        val base = KWebExtensionManifest(manifestVersion = 3, name = "x", version = "1")
        val cases = listOf(
            base.copy(manifestVersion = 2) to "extensions.manifest.version-unsupported",
            base.copy(name = "   ") to "extensions.manifest.name-empty",
            base.copy(version = "01.0") to "extensions.manifest.version-invalid",
            base.copy(version = "0.0") to "extensions.manifest.version-invalid",
            base.copy(icons = mapOf("zero" to "icon.png")) to "extensions.manifest.icon-size-invalid",
            base.copy(optionsPage = "options.html", optionsUi = KWebExtensionOptionsUi("options.html")) to
                "extensions.manifest.options-conflict",
            base.copy(sidePanel = KWebExtensionSidePanel(defaultPath = "NUL.html")) to
                "extensions.manifest.resource-path-invalid",
            base.copy(sidePanel = KWebExtensionSidePanel(defaultPath = "COM\u00b9.html")) to
                "extensions.manifest.resource-path-invalid",
            base.copy(
                declarativeNetRequest = KWebExtensionDeclarativeNetRequest(
                    listOf(KWebExtensionRuleResource("rules", true, "rules.json")),
                ),
            ) to "extensions.manifest.declarative-net-request-permission-missing",
            base.copy(
                permissions = listOf("declarativeNetRequest"),
                declarativeNetRequest = KWebExtensionDeclarativeNetRequest(
                    listOf(
                        KWebExtensionRuleResource("rules", true, "first.json"),
                        KWebExtensionRuleResource("rules", false, "second.json"),
                    ),
                ),
            ) to "extensions.manifest.duplicate-list-entry",
            base.copy(
                webAccessibleResources = listOf(
                    KWebExtensionWebAccessibleResource(
                        resources = listOf("asset.js"),
                        matches = listOf("https://example.com/private/*"),
                    ),
                ),
            ) to "extensions.manifest.web-accessible-match-path-invalid",
        )
        cases.forEach { (manifest, code) ->
            assertCode(code) { KWebExtensionManifestValidator.validate(manifest) }
        }

        KWebExtensionManifestValidator.validate(
            base.copy(hostPermissions = listOf("http://*:*/*")),
        )
        assertCode("extensions.manifest.host-pattern-invalid") {
            KWebExtensionManifestValidator.validate(
                base.copy(hostPermissions = listOf("ftp://example.com/*")),
            )
        }
    }

    @Test
    fun validatesPermissionPolicyWithoutAdvertisingRuntimeSupport() {
        val manifest = KWebExtensionManifest(
            manifestVersion = 3,
            name = "x",
            version = "1",
            permissions = listOf("storage"),
            hostPermissions = listOf("https://example.com/*"),
            contentScripts = listOf(
                KWebExtensionContentScript(matches = listOf("https://example.com/*"), js = listOf("content.js")),
            ),
        )
        val review = KWebExtensionPermissionPolicy().review(manifest)
        assertEquals(KWebExtensionPermissionKind.API_PERMISSION, review.required.single().kind)
        assertEquals(KWebExtensionPermissionKind.HOST_ACCESS, review.requiredHosts.single().kind)
        assertEquals(KWebExtensionPermissionKind.HOST_ACCESS, review.contentScriptHosts.single().kind)
        assertTrue("storage" in KWebExtensionPermissionPolicy.PACKAGE_ADMISSIBLE_PERMISSIONS)

        val denied = assertFailsWith<KWebExtensionVerificationException> {
            KWebExtensionPermissionPolicy().review(manifest.copy(hostPermissions = listOf("<all_urls>")))
        }
        assertEquals("extensions.permission.all-urls-denied", denied.code)

        assertCode("extensions.permission.denied") {
            KWebExtensionPermissionPolicy().review(manifest.copy(permissions = listOf("nativeMessaging")))
        }
        assertCode("extensions.permission.unsupported") {
            KWebExtensionPermissionPolicy().review(manifest.copy(permissions = listOf("bookmarks")))
        }
        assertCode("extensions.permission.file-access-denied") {
            KWebExtensionPermissionPolicy().review(manifest.copy(hostPermissions = listOf("file:///*")))
        }
        assertCode("extensions.permission.all-urls-denied") {
            KWebExtensionPermissionPolicy(allowBroadHosts = true).review(
                manifest.copy(hostPermissions = listOf("<all_urls>")),
            )
        }
        assertCode("extensions.permission.broad-host-denied") {
            KWebExtensionPermissionPolicy().review(
                manifest.copy(hostPermissions = listOf("*://*/*")),
            )
        }
        val networkBroad = KWebExtensionPermissionPolicy(allowBroadHosts = true).review(
            manifest.copy(hostPermissions = listOf("https://*/*")),
        )
        assertEquals(KWebExtensionPermissionKind.HOST_ACCESS, networkBroad.requiredHosts.single().kind)

        val broad = KWebExtensionPermissionPolicy(allowBroadHosts = true, allowFileUrls = true).review(
            manifest.copy(hostPermissions = listOf("<all_urls>")),
        )
        assertEquals(KWebExtensionPermissionKind.HOST_ACCESS, broad.requiredHosts.single().kind)
    }

    @Test
    fun derivesChromiumIdFromKnownHash() {
        assertEquals(
            "abcdefghijklmnopabcdefghijklmnop",
            KWebExtensionId.fromSha256Hex("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        )
        assertTrue(KWebExtensionId.isValid("abcdefghijklmnopabcdefghijklmnop"))
    }

    private fun assertCode(code: String, operation: () -> Unit) {
        val error = assertFailsWith<KWebExtensionVerificationException>(message = code, block = operation)
        assertEquals(code, error.code)
    }
}
