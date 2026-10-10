package io.github.kingsword09.kwebshell.service.tray

import io.github.kingsword09.kwebshell.core.KWebException
import io.github.kingsword09.kwebshell.service.menus.KWebMenuItem
import io.github.kingsword09.kwebshell.service.menus.KWebMenuId
import io.github.kingsword09.kwebshell.service.menus.KWebMenuRole
import io.github.kingsword09.kwebshell.service.menus.KWebMenuTree
import io.github.kingsword09.kwebshell.services.KWebServiceException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

private const val LIBRARY_PROPERTY = "kweb.tray.native.library.path"
private const val ROOT_PROPERTY = "kweb.tray.integration.root"
private const val TARGET_PROPERTY = "kweb.tray.target"

/**
 * Real provider integration: the packaged native tray library with the published
 * typed contract. The fixture is capability-driven, so an environment without
 * its declared host (a Linux session without a status-notifier watcher, or a
 * hidden status bar) records that typed boundary instead of pretending support.
 */
public fun main() {
    val library = requiredPath(LIBRARY_PROPERTY)
    val root = requiredPath(ROOT_PROPERTY)
    val target = requireNotNull(System.getProperty(TARGET_PROPERTY)) { "kweb.tray.target is required." }
    Files.createDirectories(root)
    val details = mutableMapOf<String, String>()
    var failure: Throwable? = null
    var session: JvmKWebTraysSession? = null
    try {
        session = JvmKWebTrays.open(
            applicationId = "io.github.kingsword09.kwebshell.tray.fixture",
            nativeLibrary = library,
            packageIdentity = "io.github.kingsword09.kwebshell.tray.fixture",
            iconResolver = KWebTrayIconResolver { _, _ ->
                KWebTrayIconPixels(width = 2, height = 2, rgba = ByteArray(16) { 0xFF.toByte() })
            },
        )
        val live = session
        runBlocking {
            val capabilities = live.capabilities()
            check(capabilities.providerId.isNotBlank()) { "The provider identity is empty." }
            details["provider"] = capabilities.providerId
            details["capabilities"] = capabilities.flags.sortedBy { it.ordinal }.joinToString(",")
            details["activations"] = capabilities.activations.sortedBy { it.ordinal }.joinToString(",")
            check(KWebTrays.DESCRIPTOR.operations.all { it.rendererPermission == null }) {
                "A tray item must never be renderer-owned."
            }

            val item = KWebTrayItemSpec(
                id = KWebTrayItemId("status.item"),
                icon = KWebTrayIcon(listOf(KWebTrayIconVariant(1, "icons/tray.png", "0".repeat(64)))),
                tooltip = "KWebShell tray",
                activations = setOf(KWebTrayActivation.PRIMARY, KWebTrayActivation.SECONDARY),
            )
            val created = runCatching { live.create(item) }.exceptionOrNull()
            if (created is KWebServiceException && created.code == KWebTrayErrorCode.PLATFORM_UNAVAILABLE) {
                details["host"] = KWebTrayErrorCode.PLATFORM_UNAVAILABLE
                details["created"] = "false"
                return@runBlocking
            }
            check(created == null) { "The first item creation failed: $created" }
            details["host"] = "available"
            details["created"] = "true"

            val duplicate = runCatching { live.create(item) }.exceptionOrNull()
            check((duplicate as? KWebServiceException)?.code == KWebTrayErrorCode.ITEM_EXISTS) {
                "A duplicate item id was accepted."
            }
            details["duplicateItem"] = KWebTrayErrorCode.ITEM_EXISTS

            val updated = live.update(item.copy(tooltip = "KWebShell tray updated"))
            check(!updated.additional) { "Updating a live item must not report a creation." }
            details["updated"] = "true"

            val tree = KWebMenuTree(
                KWebMenuId("tray.menu"),
                1,
                listOf(
                    KWebMenuItem.Command(KWebMenuId("tray.open"), "Open"),
                    KWebMenuItem.Separator(),
                    KWebMenuItem.Command(KWebMenuId("tray.quit"), "Quit"),
                ),
            )
            val applied = live.setMenu(item.id, tree)
            check(applied.version == 1L) { "The applied menu version is not reported." }
            details["menuItems"] = applied.itemCount.toString()

            val stale = runCatching { live.setMenu(item.id, tree) }.exceptionOrNull()
            check((stale as? KWebServiceException)?.code == KWebTrayErrorCode.MENU_VERSION_STALE) {
                "A stale menu version was accepted."
            }
            details["staleMenu"] = KWebTrayErrorCode.MENU_VERSION_STALE

            val unsupportedField = runCatching {
                live.setMenu(
                    item.id,
                    KWebMenuTree(
                        KWebMenuId("tray.menu"),
                        2,
                        listOf(
                            KWebMenuItem.Command(
                                KWebMenuId("tray.role"),
                                "Quit",
                                role = KWebMenuRole.QUIT,
                            ),
                        ),
                    ),
                )
            }.exceptionOrNull()
            check((unsupportedField as? KWebServiceException)?.code == KWebTrayErrorCode.MENU_INVALID) {
                "A tray menu role was dropped instead of failing."
            }
            details["roleMenuBoundary"] = KWebTrayErrorCode.MENU_INVALID

            val newer = live.setMenu(
                item.id,
                KWebMenuTree(
                    KWebMenuId("tray.menu"),
                    2,
                    listOf(KWebMenuItem.Command(KWebMenuId("tray.open"), "Open")),
                ),
            )
            check(newer.version == 2L) { "The replacement menu version is not reported." }

            val bounds = live.bounds(item.id)
            details["bounds"] = if (bounds == null) "unavailable" else "${bounds.width}x${bounds.height}"

            live.closeItem(item.id)
            val closed = runCatching { live.bounds(item.id) }.exceptionOrNull()
            check((closed as? KWebServiceException)?.code == KWebTrayErrorCode.ITEM_UNKNOWN) {
                "A closed item was still addressable."
            }
            details["closedItem"] = KWebTrayErrorCode.ITEM_UNKNOWN
        }
    } catch (error: Throwable) {
        failure = error
    } finally {
        runCatching { session?.close() }
    }

    val report = buildJsonObject {
        put("schemaVersion", 1)
        put("target", target)
        put("library", library.fileName.toString())
        put("outcome", if (failure == null) "passed" else "failed")
        details.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        failure?.let {
            put("failure", JsonPrimitive(it::class.simpleName ?: "Throwable"))
            put("failureCode", JsonPrimitive((it as? KWebException)?.code ?: "none"))
        }
    }
    val reportFile = root.resolve("tray-integration.json")
    Files.writeString(reportFile, (report as JsonObject).toString() + "\n")
    if (failure != null) {
        System.err.println("The tray integration fixture failed: $reportFile")
        throw failure
    }
    println("The tray integration fixture passed: $reportFile")
}

private fun requiredPath(property: String): Path =
    Path.of(requireNotNull(System.getProperty(property)) { "$property is required." }).toAbsolutePath().normalize()
