package io.github.kingsword09.kwebshell.electron.migration

import kotlinx.serialization.Serializable

@Serializable
public data class KWebElectronCapabilityMatrixEntry(
    public val id: String,
    public val electron: String,
    public val direction: String,
    public val status: KWebElectronMappingStatus,
    public val kweb: String,
    public val notes: String,
)

@Serializable
public data class KWebElectronCapabilityMatrixDocument(
    public val schemaVersion: Int,
    public val entries: List<KWebElectronCapabilityMatrixEntry>,
)

public object KWebElectronCapabilityMatrix {
    public const val schemaVersion: Int = 1
    private val ID = Regex("[a-z][a-z0-9-]{1,63}")

    public val entries: List<KWebElectronCapabilityMatrixEntry> = listOf(
        entry("browser-window", "BrowserWindow", "compose-window-page", KWebElectronMappingStatus.REWRITE, "ComposeWindow + KWebPage", "Window lifecycle is owned by Kotlin/Compose and requires host-code migration."),
        entry("web-contents", "webContents", "page-events", KWebElectronMappingStatus.DIRECT, "KWebPage.events", "CDP is explicit and must be configured."),
        entry("session-partition", "session.fromPartition", "persistent-profile", KWebElectronMappingStatus.DIRECT, "KWebProfile", "Profiles are explicit and persistent."),
        entry("profile-protocol", "protocol.handle", "profile-origin", KWebElectronMappingStatus.DIRECT, "Profile-scoped app:// origin", "Protocol handlers are verified host contracts."),
        entry("ipc-request", "ipcMain.handle + ipcRenderer.invoke", "typed-service", KWebElectronMappingStatus.ADAPTER, "Versioned service bridge", "Only declared channels are generated."),
        entry("context-bridge", "contextBridge.exposeInMainWorld", "generated-preload", KWebElectronMappingStatus.ADAPTER, "Generated preload facade", "No universal ipcRenderer object is installed."),
        entry("dialog", "dialog.showOpenDialog + dialog.showSaveDialog", "native-dialog-service", KWebElectronMappingStatus.REWRITE, "KWebDialogs + DialogsBridge", "Path results must migrate to owner-scoped handles and explicit bounded I/O."),
        entry("clipboard", "clipboard", "native-clipboard-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires explicit format and permission contracts."),
        entry("shell", "shell", "external-launch-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires scheme and path policy."),
        entry("native-theme", "nativeTheme", "theme-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires observation and lifecycle conformance."),
        entry("screen", "screen", "screen-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires multi-monitor and DPI conformance."),
        entry("global-shortcut", "globalShortcut", "shortcut-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires ownership and permission conformance."),
        entry("menu-tray", "Menu + Tray", "menu-tray-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires native UI lifecycle conformance."),
        entry("process", "utilityProcess + child_process", "process-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Node execution is never implicit."),
        entry("auto-updater", "autoUpdater", "signed-update-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires verified release metadata and recovery."),
        entry("node-runtime", "Node fs/path/os and native addons", "kotlin-service-rewrite", KWebElectronMappingStatus.REWRITE, "", "Renderer Node access must be isolated and rewritten."),
    )

    init {
        val ids = entries.map { it.id }
        require(ids.size == ids.toSet().size) { "The Electron capability matrix contains duplicate ids." }
        require(entries.all { it.id.matches(ID) && it.electron.isNotBlank() && it.notes.isNotBlank() }) {
            "The Electron capability matrix contains an invalid entry."
        }
    }

    public fun find(id: String): KWebElectronCapabilityMatrixEntry? = entries.singleOrNull { it.id == id }

    public fun document(): KWebElectronCapabilityMatrixDocument =
        KWebElectronCapabilityMatrixDocument(schemaVersion, entries)

    private fun entry(
        id: String,
        electron: String,
        direction: String,
        status: KWebElectronMappingStatus,
        kweb: String,
        notes: String,
    ): KWebElectronCapabilityMatrixEntry = KWebElectronCapabilityMatrixEntry(
        id = id,
        electron = electron,
        direction = direction,
        status = status,
        kweb = kweb,
        notes = notes,
    )
}
