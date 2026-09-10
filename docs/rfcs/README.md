# KWebShell Electron Migration RFC Program

## Purpose

This directory is the implementation backlog for reaching Electron-class desktop
application capability without making Electron the KWebShell API. Each RFC is a
small publishable contract that another agent can implement independently after
its listed dependencies are complete.

The program is based on the public Electron API and security documentation
surveyed on 2026-09-10:

- [Electron API documentation](https://www.electronjs.org/docs/latest/api/app)
- [Electron breaking changes](https://www.electronjs.org/docs/latest/breaking-changes)
- [Electron security guidance](https://www.electronjs.org/docs/latest/tutorial/security)
- [Context isolation](https://www.electronjs.org/docs/latest/tutorial/context-isolation)
- [Process sandboxing](https://www.electronjs.org/docs/latest/tutorial/sandbox)

Those documents are discovery inputs, not KWebShell specifications. Every
implementation objective must pin the Electron major used by its migration
fixture because Electron's current API and defaults continue to change.

## Existing prerequisites

The following shipped contracts are prerequisites rather than proposed RFCs:

| Delivered contract | Current KWebShell ownership |
|---|---|
| Compose native-child browser | `KWebView`, `KWebPage`, `KWebProfile`, `KWebDesktopEngine` |
| Typed host transport | Exact-origin `KWebBridge` with timeout and cancellation |
| Native-service foundation | `kweb-services-core` descriptors, registry, policy, lifecycle |
| Application paths | `kweb-service-app-paths` |
| Window controls | `kweb-service-window-controls` |
| Native file pickers and scoped handles | `kweb-service-dialogs` |
| Migration inventory and generated preload facade v1 | `kweb-electron-migration` |
| Profile-scoped local protocol | Verified `app://` origin |

An RFC must extend these sources of truth. It must not create a second registry,
bridge, lifecycle, permission model, or compatibility matrix.

## Normative rules

The words **MUST**, **MUST NOT**, **SHOULD**, and **MAY** are normative.

1. Common application contracts belong in KMP `commonMain`. Desktop providers
   MAY use Kotlin/JVM, internal Java JDK 25 FFM bindings, or a small versioned C
   ABI. Platform types never enter common code.
2. Services are explicitly installed. Provider discovery, a default provider,
   environment guessing, and fallback backends are prohibited.
3. A public operation is added only with complete behavior on every target it
   advertises. Platform-specific contracts must name their target in the key and
   capability matrix.
4. Renderer access is optional and generated from one schema. Each operation is
   exact-origin, main-frame, owner, permission, and user-gesture checked.
5. Electron-shaped APIs exist only in `kweb-electron-migration`. Core, Compose,
   browser, and service modules never depend on Electron names or Node.js.
6. `DIRECT`, `ADAPTER`, `REWRITE`, and `UNSUPPORTED` describe tested migration
   outcomes. `Proposed` RFCs remain absent from the published runtime matrix.
7. Synchronous IPC, arbitrary string channels, unrestricted filesystem paths,
   renderer Node.js, hidden fallback windows, and fake success are permanent
   non-goals.
8. One RFC is one focused implementation objective, commit, and pull request.
   If an RFC cannot satisfy that constraint, it must be split before coding.

## RFC state machine

```text
Proposed -> Accepted -> Implementing -> Implemented
    |           |              |
    +--------> Rejected <-------+
                    |
                Superseded
```

- **Proposed**: reviewed backlog contract; not a capability claim.
- **Accepted**: architecture and acceptance are approved; implementation may start.
- **Implementing**: one topic branch and pull request own the objective.
- **Implemented**: code, real platform evidence, packaging, docs, and matrix row
  are on `main`.
- **Rejected/Superseded**: the document records the replacement or reason.

Only one agent may move a given RFC to `Implementing`. Dependencies must already
be `Implemented`, except when the same pull request supplies an inseparable
private primitive and the RFC explicitly permits it.

## Universal definition of done

Every capability RFC inherits all gates below. Its own acceptance section adds
capability-specific evidence.

### Contract and KMP

- Immutable common request/result/event types and stable error codes.
- Explicit application, Profile, Page, window, or operation ownership.
- `suspend` for operations, `Flow` only for ordered streams, and bounded
  cancellation/close behavior.
- Common tests for validation, lifecycle, ordering, cancellation races,
  incompatible versions, and use after close.
- No public `Any`, platform handle, raw JSON map, unbounded byte array, or path
  string that grants ambient authority.

### Desktop providers

- Exact macOS, Windows, and Linux provider contracts are documented.
- Real native integration tests exercise those APIs on each advertised target.
- FFM layouts/C ABI sizes, ownership, thread affinity, status mapping, and live
  resource count are tested where native interop exists.
- Missing OS facilities fail immediately with service, operation, platform,
  native status, and remediation details. No alternate provider is selected.

### Renderer and migration

- One schema generates Kotlin dispatch, strict TypeScript declarations, and
  browser JavaScript when renderer exposure is part of the RFC.
- Tests cover allowed main-frame origin, denied operation, child frame,
  cross-origin navigation, unconfigured Page, timeout, `AbortSignal`, owner
  close, malformed payload, and backpressure where events or bytes are involved.
- The migration fixture pins an Electron major, inventories the source API, and
  proves the declared `DIRECT`, `ADAPTER`, or `REWRITE` result. Undeclared use
  blocks packaging.
- The adapter exposes only application-declared methods; it never exports
  `ipcRenderer`, Electron objects, or a generic channel function.

### Security, packaging, and evidence

- Threat model covers confused deputy, untrusted renderer input, stale handles,
  path/scheme confusion, permission revocation, and owner shutdown as applicable.
- Sensitive details are redacted from renderer errors and logs.
- Native libraries, entitlements, manifests, licenses, and service schemas are
  reproducibly packaged for each advertised target.
- Windows x64, Linux x64, and macOS arm64 hosted jobs retain real runtime
  evidence. A source-only or mock-only result cannot publish support.
- Documentation, the migration inventory, capability matrix, and this RFC state
  are updated in the same pull request.

## Delivery waves and RFC index

RFC numbers are stable identifiers, not implementation order within a wave.
Agents may work in parallel only when dependencies do not overlap.

### Wave 0 — extensibility and migration foundations

| RFC | Priority | Depends on | Deliverable |
|---|---:|---|---|
| [0001](0001-program-governance.md) | P0 | Existing prerequisites | RFC governance and evidence manifest |
| [0002](0002-kmp-provider-sdk.md) | P0 | 0001 | KMP provider SDK and typed service dependencies |
| [0003](0003-permission-gesture-consent.md) | P0 | 0001, 0002 | Permission, user-gesture, consent, and audit contract |
| [0004](0004-typed-stream-bridge.md) | P0 | 0001, 0003 | Typed event/binary stream bridge |
| [0005](0005-migration-manifest-v2.md) | P0 | 0001, 0004 | Migration manifest v2, inventory, and codemod plan |
| [0030](0030-packaging-identity-associations.md) | P0 | 0001 | Signed app identity, installers, schemes, file associations |

### Wave 1 — application and browser ownership

| RFC | Priority | Depends on | Deliverable |
|---|---:|---|---|
| [0006](0006-application-lifecycle.md) | P0 | 0002, 0003, 0030 | App activation, single instance, deep links, file-open lifecycle |
| [0007](0007-window-hierarchy-and-fullscreen.md) | P0 | 0002, 0003 | Parent/modal windows, fullscreen, close negotiation |
| [0008](0008-page-lifecycle-and-popups.md) | P0 | 0004, 0007 | `webContents`-class Page events and popup policy |
| [0009](0009-profile-data-and-session.md) | P0 | 0002, 0003 | Cookies, cache, storage, spellcheck, session state |
| [0010](0010-network-policy-and-proxy.md) | P0 | 0004, 0009 | Request policy, proxy, network observation |
| [0011](0011-tls-auth-and-certificates.md) | P1 | 0003, 0010 | TLS errors, client certificates, HTTP authentication |
| [0012](0012-downloads.md) | P0 | 0003, 0004, 0009 | Profile downloads and scoped results |

### Wave 2 — common desktop-native services

| RFC | Priority | Depends on | Deliverable |
|---|---:|---|---|
| [0027](0027-native-image-and-icons.md) | P0 | 0002 | Shared bounded image/icon value model |
| [0013](0013-scoped-filesystem.md) | P0 | 0002, 0003, 0004 | Capability-based files and directories |
| [0014](0014-clipboard.md) | P0 | 0003, 0004, 0027 | Typed system clipboard |
| [0015](0015-shell-integration.md) | P0 | 0003, 0013 | External URLs, reveal, and trash |
| [0016](0016-notifications.md) | P0 | 0003, 0004, 0027, 0030 | Native notifications and activation |
| [0017](0017-native-menus.md) | P0 | 0003, 0004, 0027 | Application/window/context menus |
| [0018](0018-tray-status-item.md) | P1 | 0004, 0017, 0027, 0030 | Tray/status item lifecycle |
| [0040](0040-native-prompts.md) | P1 | 0003, 0007, 0027 | Owner-bound native message prompts |
| [0019](0019-theme-system-preferences-accessibility.md) | P0 | 0003, 0004 | Theme and accessibility preferences |
| [0020](0020-screen-and-display.md) | P0 | 0004 | Display topology, work areas, DPI |
| [0021](0021-global-shortcuts.md) | P1 | 0003, 0004 | Owned global shortcut registration |
| [0022](0022-power-monitor-and-blocker.md) | P1 | 0002, 0004 | Power events and sleep blockers |
| [0023](0023-secure-storage.md) | P0 | 0002, 0003, 0030 | OS credential-backed secret storage |
| [0037](0037-native-drag-and-drop.md) | P1 | 0003, 0013, 0027 | Native drag/drop and file promises |

### Wave 3 — privileged and high-volume capabilities

| RFC | Priority | Depends on | Deliverable |
|---|---:|---|---|
| [0024](0024-managed-processes.md) | P1 | 0002, 0003, 0004, 0013 | Policy-controlled utility processes |
| [0025](0025-desktop-capture.md) | P1 | 0003, 0004, 0020, 0027 | Screen/window capture selection |
| [0026](0026-printing-and-pdf.md) | P1 | 0003, 0004, 0013 | Native print and PDF output |
| [0028](0028-page-utilities.md) | P1 | 0004, 0008, 0013, 0027 | Find, zoom, capture, save, and source utilities |
| [0038](0038-device-permissions.md) | P1 | 0003, 0004, 0009 | Media, USB, HID, serial, and Bluetooth grants |
| [0039](0039-credential-and-passkey-policy.md) | P2 | 0003, 0009, 0011 | WebAuthn/passkey and credential mediation |
| [0041](0041-native-window-decoration.md) | P1 | 0007, 0017, 0019, 0020, 0027 | Typed title bars, materials, and hit-test regions |
| [0042](0042-window-content-protection.md) | P1 | 0003, 0007, 0025 | Window capture protection and privacy state |

### Wave 4 — platform delivery and migration proof

| RFC | Priority | Depends on | Deliverable |
|---|---:|---|---|
| [0029](0029-desktop-app-integration.md) | P1 | 0006, 0007, 0017, 0027, 0030 | Dock/taskbar, badges, progress, recent items, autostart |
| [0031](0031-signed-updates.md) | P0 | 0003, 0030 | Verified atomic updates and recovery |
| [0032](0032-diagnostics-crash-tracing.md) | P1 | 0003, 0004, 0030 | Crash reports, tracing, metrics, redacted logs |
| [0033](0033-macos-special-surfaces.md) | P2 | 0017, 0027, 0030 | Touch Bar and Share menu as macOS-only capabilities |
| [0034](0034-push-notifications.md) | P2 | 0004, 0016, 0030 | Push registration and activation |
| [0035](0035-platform-commerce.md) | P2 | 0003, 0004, 0030 | Explicit platform commerce capability |
| [0036](0036-reference-app-migration.md) | P0 | 0005 and application-selected RFCs | Real application migration conformance and release matrix |

## Electron API accounting

Every stable Electron module is classified below so absence from the roadmap is
intentional rather than accidental.

| Electron surface | RFC or delivered direction |
|---|---|
| `app` | 0006, 0029, 0030 |
| `BaseWindow`, `BrowserWindow`, `View`, `WebContentsView` | Delivered Compose host plus 0007, 0041, and 0042; Electron View identity is rewritten as Compose ownership |
| `webContents`, `webFrameMain`, renderer `webFrame` | Delivered `KWebPage` plus 0008 and 0028 |
| `session` | Delivered `KWebProfile` and MV3 package/runtime foundations plus 0009–0012, 0025, 0038, and 0039 |
| `session.extensions` | Existing `kweb-extensions` capability matrix; lifecycle/UI/API gaps remain separate MV3 objectives |
| Chromium Service Worker management | Existing MV3 lifecycle work plus 0008/0009; no Electron object identity |
| `protocol` | Delivered Profile-scoped `app://`; broader schemes remain intentionally absent |
| `ipcMain`, `ipcRenderer`, `contextBridge`, `MessageChannelMain` | Delivered typed bridge plus 0004 and 0005; generic IPC remains unsupported |
| `dialog` | Delivered file dialogs plus 0040 prompts; certificate trust stays in 0011 |
| Accelerator structures | 0017 for menu accelerators and 0021 for global registration |
| `clipboard` | 0014 |
| `shell` | 0015 |
| `Notification` | 0016 |
| `Menu`, `MenuItem` | 0017 |
| `Tray` | 0018 |
| `nativeTheme`, `systemPreferences` | 0019 |
| `screen` | 0020 |
| `globalShortcut` | 0021 |
| `powerMonitor`, `powerSaveBlocker` | 0022 |
| `safeStorage` | 0023 |
| `utilityProcess`, Node `child_process` | 0024; arbitrary Node execution remains unsupported |
| `desktopCapturer` | 0025 |
| Printing APIs | 0026 |
| `nativeImage` | 0027 |
| `contentTracing`, `crashReporter`, `netLog`, app metrics | 0032 |
| Dock, taskbar, Jump List, login items, recent documents | 0029 |
| `autoUpdater` | 0031 |
| `TouchBar`, `ShareMenu` | 0033 |
| `pushNotifications` | 0034 |
| `inAppPurchase` | 0035 |
| `webContents.startDrag` | 0037 |
| Media/device permission handlers | 0038 |
| `webUtils` | 0013 for scoped file capabilities; ambient `getPathForFile` remains unsupported |
| `net` | 0010; Kotlin HTTP clients remain application choices rather than an Electron-shaped API |
| `process`, `parentPort`, Node built-ins, native addons | 0024 or a dedicated KMP service rewrite |

Permanent unsupported rows include Electron `remote`, `BrowserView` identity,
`nodeIntegration`, synchronous IPC, arbitrary `executeJavaScript` as a native
service transport, loading an `app.asar` unchanged, and unrestricted renderer
access to Node modules or native addons.
