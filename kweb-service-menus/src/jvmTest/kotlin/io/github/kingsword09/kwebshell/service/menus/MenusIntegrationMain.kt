package io.github.kingsword09.kwebshell.service.menus

import androidx.compose.ui.awt.ComposeWindow
import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.swing.SwingUtilities

private const val LIBRARY_PROPERTY = "kweb.menus.native.library.path"
private const val ROOT_PROPERTY = "kweb.menus.integration.root"
private const val TARGET_PROPERTY = "kweb.menus.target"

/**
 * Real provider integration: one live Compose window, the packaged native
 * menus library, and the published typed contract. It writes a machine-readable
 * report that the hosted evidence flow retains; it never claims a capability
 * the provider does not advertise.
 */
public fun main() {
    val library = requiredPath(LIBRARY_PROPERTY)
    val root = requiredPath(ROOT_PROPERTY)
    val target = requireNotNull(System.getProperty(TARGET_PROPERTY)) { "kweb.menus.target is required." }
    Files.createDirectories(root)
    val window = onAwtThread {
        ComposeWindow().apply {
            title = "KWebShell menus"
            setBounds(140, 140, 900, 650)
            isVisible = true
            require(isDisplayable && isShowing)
        }
    }
    val report = buildJsonObject {
        put("schemaVersion", 1)
        put("target", target)
        put("library", library.fileName.toString())
    }
    var failure: Throwable? = null
    val details = mutableMapOf<String, String>()
    var session: JvmKWebMenusSession? = null
    try {
        val binding = KWebMenuWindowBinding(KWebMenuWindowId("main-window"), window)
        session = JvmKWebMenus.open(
            applicationId = "io.github.kingsword09.kwebshell.menus.fixture",
            nativeLibrary = library,
            windows = listOf(binding),
            packageIdentity = "io.github.kingsword09.kwebshell.menus.fixture",
            iconResolver = KWebMenuIconResolver { _, _ ->
                KWebMenuIconPixels(width = 2, height = 2, rgba = ByteArray(16) { 0xFF.toByte() })
            },
        )
        runBlocking {
            val capabilities = session.capabilities()
            check(capabilities.providerId.isNotBlank()) { "The provider identity is empty." }
            details["provider"] = capabilities.providerId
            details["capabilities"] = capabilities.flags.sortedBy { it.ordinal }.joinToString(",")
            details["nativeRoles"] = capabilities.nativeRoles.sortedBy { it.ordinal }.joinToString(",")
            val applicationBar = capabilities.supports(KWebMenuCapability.APPLICATION_MENU) ||
                capabilities.supports(KWebMenuCapability.WINDOW_MENU)
            val mnemonics = capabilities.supports(KWebMenuCapability.MNEMONICS)
            val icons = capabilities.supports(KWebMenuCapability.ITEM_ICONS)

            fun barItem(id: String, label: String, withIcon: Boolean) = KWebMenuItem.Command(
                KWebMenuId(id),
                label,
                accelerator = KWebMenuAccelerator(listOf(KWebMenuModifier.PRIMARY), KWebMenuKey.O),
                icon = if (withIcon) KWebMenuIcon("icons.open", "0".repeat(64)) else null,
            )

            val tree = KWebMenuTree(
                KWebMenuId("app.menu"),
                1,
                listOf(
                    KWebMenuItem.Submenu(
                        KWebMenuId("file"),
                        "File",
                        listOf(
                            barItem("file.open", "Open", icons),
                            KWebMenuItem.Separator(),
                            KWebMenuItem.Command(KWebMenuId("file.quit"), "Quit", role = KWebMenuRole.QUIT),
                        ),
                    ),
                    KWebMenuItem.Submenu(
                        KWebMenuId("view"),
                        "View",
                        listOf(
                            KWebMenuItem.Command(
                                KWebMenuId("view.autosave"),
                                "Autosave",
                                toggle = KWebMenuToggleKind.CHECKBOX,
                                checked = true,
                            ),
                        ),
                    ),
                ),
            )
            if (applicationBar) {
                val result = session.setApplicationMenu(tree)
                check(result.version == 1L) { "The applied tree version is not reported." }
                details["appliedItems"] = result.itemCount.toString()
                details["applicationBar"] = "native"
            } else {
                val boundary = runCatching { session.setApplicationMenu(tree) }.exceptionOrNull()
                check((boundary as? KWebServiceException)?.code == KWebMenuErrorCode.TARGET_UNSUPPORTED) {
                    "A provider without an application bar accepted an application menu."
                }
                details["applicationBar"] = KWebMenuErrorCode.TARGET_UNSUPPORTED
            }

            session.registerPageAnchor(KWebMenuPageToken("page-token"), KWebMenuWindowId("main-window"), 0, 0)
            fun pageTree(version: Long, label: String) = KWebMenuTree(
                KWebMenuId("editor.context"),
                version,
                listOf(
                    KWebMenuItem.Command(KWebMenuId("editor.copy"), label),
                    KWebMenuItem.Separator(),
                    KWebMenuItem.Command(KWebMenuId("editor.paste"), "Paste"),
                ),
            )
            val declared = session.declarePageMenu(KWebMenuPageToken("page-token"), pageTree(1, "Copy"))
            details["pageMenuItems"] = declared.itemCount.toString()
            session.declarePageMenu(KWebMenuPageToken("page-token"), pageTree(2, "Copy again"))
            val stale = runCatching {
                session.declarePageMenu(KWebMenuPageToken("page-token"), pageTree(1, "Copy"))
            }.exceptionOrNull()
            check((stale as? KWebServiceException)?.code == KWebMenuErrorCode.VERSION_STALE) {
                "A stale declared menu version was accepted."
            }
            details["staleVersion"] = KWebMenuErrorCode.VERSION_STALE
            if (!mnemonics) {
                val mnemonicRejected = runCatching {
                    session.declarePageMenu(
                        KWebMenuPageToken("page-token"),
                        KWebMenuTree(
                            KWebMenuId("editor.mnemonic"),
                            1,
                            listOf(KWebMenuItem.Command(KWebMenuId("editor.new"), "New", mnemonic = 'N')),
                        ),
                    )
                }.exceptionOrNull()
                check((mnemonicRejected as? KWebServiceException)?.code == KWebMenuErrorCode.MNEMONIC_UNSUPPORTED) {
                    "The provider must reject a mnemonic it cannot render."
                }
                details["mnemonicBoundary"] = KWebMenuErrorCode.MNEMONIC_UNSUPPORTED
            } else {
                session.declarePageMenu(
                    KWebMenuPageToken("page-token"),
                    KWebMenuTree(
                        KWebMenuId("editor.mnemonic"),
                        1,
                        listOf(KWebMenuItem.Command(KWebMenuId("editor.new"), "New", mnemonic = 'N')),
                    ),
                )
                details["mnemonicBoundary"] = "rendered"
                session.clearPageMenu(KWebMenuPageToken("page-token"), KWebMenuId("editor.mnemonic"))
            }
            if (!icons) {
                val iconRejected = runCatching {
                    session.declarePageMenu(
                        KWebMenuPageToken("page-token"),
                        KWebMenuTree(
                            KWebMenuId("editor.icon"),
                            1,
                            listOf(
                                KWebMenuItem.Command(
                                    KWebMenuId("editor.icon.open"),
                                    "Open",
                                    icon = KWebMenuIcon("icons.open", "0".repeat(64)),
                                ),
                            ),
                        ),
                    )
                }.exceptionOrNull()
                check((iconRejected as? KWebServiceException)?.code == KWebMenuErrorCode.ICON_UNSUPPORTED) {
                    "The provider must reject an icon it cannot render."
                }
                details["iconBoundary"] = KWebMenuErrorCode.ICON_UNSUPPORTED
            } else {
                details["iconBoundary"] = "rendered"
            }

            session.clearPageMenu(KWebMenuPageToken("page-token"), KWebMenuId("editor.context"))
            val undeclared = runCatching {
                session.showPopup(
                    KWebMenuPopupRequest(
                        KWebMenuId("editor.context"),
                        KWebMenuOwner.Page(KWebMenuPageToken("page-token"), KWebMenuWindowId("main-window")),
                        KWebMenuPosition(KWebMenuCoordinateSpace.PAGE, 10, 10),
                    ),
                )
            }.exceptionOrNull()
            check((undeclared as? KWebServiceException)?.code == KWebMenuErrorCode.MENU_NOT_DECLARED) {
                "An undeclared renderer menu was accepted."
            }
            details["undeclaredMenu"] = KWebMenuErrorCode.MENU_NOT_DECLARED

            val unknownWindow = runCatching {
                session.setWindowMenu(KWebMenuWindowId("missing-window"), tree)
            }.exceptionOrNull()
            details["unknownWindowMenus"] = ((unknownWindow as? KWebServiceException)?.code ?: "none")
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        runCatching { session?.close() }
        onAwtThread { window.dispose() }
    }

    val reportFile = root.resolve("menus-integration.json")
    val payload = JsonObject(
        report.toMutableMap().apply {
            put("outcome", JsonPrimitive(if (failure == null) "passed" else "failed"))
            putAll(details.mapValues { JsonPrimitive(it.value) })
            failure?.let { put("failure", JsonPrimitive(it::class.simpleName ?: "Throwable")) }
            failure?.let { put("failureCode", JsonPrimitive((it as? KWebException)?.code ?: "none")) }
        },
    )
    Files.writeString(reportFile, payload.toString() + "\n")
    if (failure != null) {
        System.err.println("The menus integration fixture failed: $reportFile")
        throw failure
    }
    println("The menus integration fixture passed: $reportFile")
}

private fun requiredPath(property: String): Path =
    Path.of(requireNotNull(System.getProperty(property)) { "$property is required." }).toAbsolutePath().normalize()

private fun <T> onAwtThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: T? = null
    var failure: Throwable? = null
    SwingUtilities.invokeAndWait {
        try {
            result = block()
        } catch (error: Throwable) {
            failure = error
        }
    }
    failure?.let { throw it }
    return requireNotNull(result)
}
