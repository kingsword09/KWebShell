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
        entry("web-contents", "webContents", "page-events", KWebElectronMappingStatus.DIRECT, "KWebPage.events", "Only typed RFC 0008 page events are mapped; unknown event names and arbitrary event strings block migration. CDP is explicit and must be configured."),
        entry("web-contents-reload", "webContents.reload", "typed-page-operation", KWebElectronMappingStatus.DIRECT, "KWebPage.reload(NORMAL | IGNORE_CACHE)", "Before-unload and terminal page states produce typed outcomes."),
        entry("web-contents-before-unload", "beforeunload", "host-decision", KWebElectronMappingStatus.ADAPTER, "KWebPage.respondToBeforeUnload", "The request is page-bound, time-limited, and defaults to cancel."),
        entry("window-open-handler", "webContents.setWindowOpenHandler + window.open", "host-owned-page", KWebElectronMappingStatus.REWRITE, "KWebPage.respondToPopup", "CEF cancels the renderer popup immediately; an allowed decision creates a separate owner-bound page without a window.opener proxy."),
        entry("renderer-process-state", "render-process-gone + unresponsive", "typed-page-events", KWebElectronMappingStatus.DIRECT, "RENDERER_UNRESPONSIVE / RESPONSIVE / TERMINATED", "Renderer termination closes the native child and emits one terminal page event."),
        entry("session-partition", "session.fromPartition", "persistent-profile", KWebElectronMappingStatus.DIRECT, "KWebProfile", "Profiles are explicit and persistent."),
        entry("session-cookies", "session.cookies.get/set/remove", "profile-cookie-data", KWebElectronMappingStatus.DIRECT, "KWebProfile cookie operations", "Cookie values and metadata are typed, bounded, and always target a same-Profile page."),
        entry("session-cache-clear", "session.clearCache", "profile-cache-clear", KWebElectronMappingStatus.DIRECT, "KWebProfile.clearData(HTTP_CACHE)", "HTTP cache clearing is explicitly profile-wide because Chromium exposes no origin/time selector."),
        entry("session-storage-clear", "session.clearStorageData", "origin-storage-clear", KWebElectronMappingStatus.DIRECT, "KWebProfile.clearData(origin)", "Only declared origin-scoped storage kinds are cleared; unsupported time ranges fail typed."),
        entry("session-storage-usage", "session.getCacheSize / storage usage", "origin-storage-usage", KWebElectronMappingStatus.DIRECT, "KWebProfile.storageUsage", "Usage and quota are reported for one canonical origin with Chromium breakdown names retained."),
        entry("session-spellcheck", "session.setSpellCheckerLanguages + spellChecker", "profile-spellcheck", KWebElectronMappingStatus.DIRECT, "KWebProfile.configureSpellcheck", "Chromium preferences are read back and dictionaries are never downloaded implicitly."),
        entry("session-flush", "session.flushStorageData", "profile-flush", KWebElectronMappingStatus.DIRECT, "KWebProfile.flush", "Flush success is reported only after Chromium's cookie-store completion callback."),
        entry("session-web-request", "session.webRequest", "typed-profile-network", KWebElectronMappingStatus.REWRITE, "KWebProfile.configureNetworkPolicy + networkEvents", "Imperative Electron callback bags are not exposed; declarative block/redirect/header rules and bounded body-free observation must be migrated to the typed Profile contract."),
        entry("downloads", "webContents.session will-download + DownloadItem", "typed-profile-download", KWebElectronMappingStatus.REWRITE, "KWebProfile.downloads", "Download state, progress, control and completed bytes migrate to the Profile-scoped typed contract; renderer paths, arbitrary save-path mutation and automatic opening remain blocked."),
        entry("certificate-error", "webContents certificate-error", "typed-security-challenge", KWebElectronMappingStatus.REWRITE, "KWebProfile.securityChallenges + respondToSecurityChallenge", "TLS decisions migrate to the bounded, Profile-scoped typed challenge contract; global trust bypass and renderer handlers remain unsupported."),
        entry("login", "app on('login')", "deferred-security-capability", KWebElectronMappingStatus.UNSUPPORTED, "", "Deferred: stock CEF 151 does not deliver the required HTTP/proxy authentication callback for real browser navigations. No credential fallback or second network stack is exposed."),
        entry("select-client-certificate", "app on('select-client-certificate')", "typed-security-challenge", KWebElectronMappingStatus.REWRITE, "KWebProfile.securityChallenges + respondToSecurityChallenge", "Candidate summaries and fingerprint selection migrate to the typed Profile contract; Chromium retains certificate stores and private-key operations."),
        entry("session-set-proxy", "session.setProxy", "deferred-network-capability", KWebElectronMappingStatus.UNSUPPORTED, "", "Deferred until a separately reviewed Profile NetworkContext proxy contract is implemented; no process-wide or system-proxy fallback is exposed."),
        entry("session-resolve-proxy", "session.resolveProxy", "deferred-network-capability", KWebElectronMappingStatus.UNSUPPORTED, "", "Deferred with Profile proxy configuration; no synthetic PAC resolver or second Kotlin HTTP/network stack is exposed."),
        entry("profile-protocol", "protocol.handle", "profile-origin", KWebElectronMappingStatus.DIRECT, "Profile-scoped app:// origin", "Protocol handlers are verified host contracts."),
        entry("ipc-request", "ipcMain.handle + ipcRenderer.invoke", "typed-service", KWebElectronMappingStatus.ADAPTER, "Versioned service bridge", "Only declared channels are generated."),
        entry("context-bridge", "contextBridge.exposeInMainWorld", "generated-preload", KWebElectronMappingStatus.ADAPTER, "Generated preload facade", "No universal ipcRenderer object is installed."),
        entry("dialog", "dialog.showOpenDialog + dialog.showSaveDialog", "native-dialog-service", KWebElectronMappingStatus.REWRITE, "KWebDialogs + DialogsBridge", "Path results must migrate to owner-scoped handles and explicit bounded I/O."),
        entry("clipboard", "clipboard", "typed-clipboard-service", KWebElectronMappingStatus.REWRITE, "KWebClipboard + ClipboardBridge", "SYSTEM text/HTML/RTF/URI-list workflows rewrite to bounded typed operations with gesture and exact-origin policy; Linux PRIMARY, images, custom formats, and direct renderer access remain unsupported."),
        entry("shell", "shell", "external-launch-service", KWebElectronMappingStatus.REWRITE, "KWebShell + ShellBridge", "External URI, scoped open/reveal, and verified OS trash rewrite to named shell operations; raw paths, commands, and shortcut metadata remain blocked."),
        entry("notification", "Notification", "typed-notification-service", KWebElectronMappingStatus.UNSUPPORTED, "", "The typed notification contract and native providers exist, but the Electron migration row remains unsupported until fresh Windows and macOS OS-visible action conformance evidence is retained; no in-page fallback is exposed."),
        entry("native-theme", "nativeTheme", "theme-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires observation and lifecycle conformance."),
        entry("screen", "screen", "screen-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires multi-monitor and DPI conformance."),
        entry("global-shortcut", "globalShortcut", "shortcut-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires ownership and permission conformance."),
        entry("menu-tray", "Menu + Tray", "menu-tray-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires native UI lifecycle conformance."),
        entry("process", "utilityProcess + child_process", "process-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Node execution is never implicit."),
        entry("auto-updater", "autoUpdater", "signed-update-service", KWebElectronMappingStatus.UNSUPPORTED, "", "Requires verified release metadata and recovery."),
        entry("node-fs", "Node fs/promises + path", "scoped-filesystem", KWebElectronMappingStatus.REWRITE, "KWebFiles + FilesBridge", "Async file workflows migrate to declared workspace capabilities and normalized relative names; absolute paths, sync fs, webUtils.getPathForFile, Buffer, and generic channels remain blocked."),
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
