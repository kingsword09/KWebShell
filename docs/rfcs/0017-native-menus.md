# RFC 0017: Application, window, and context menus

- Status: Accepted
- Priority: P0
- Owners: new `kweb-service-menus` KMP service, desktop host, CEF host
- Depends on: RFC 0003, RFC 0004, RFC 0027
- Electron migration surface: `Menu`, `MenuItem`, application menu, popup/context menu
- Target mapping: `REWRITE` with generated command adapter

## Objective and scope

Publish one application-scoped native menu service for Windows x64, macOS
arm64, and Linux x64, and connect it to the real Chromium page context menu
without a second menu model. Kotlin owns command handling, validation, window
association, capability reporting, and event ordering; each advertised target
contributes exactly one provider behind a versioned C ABI and the JDK 25 FFM
binding.

One implementation objective delivers:

1. the immutable typed command-tree contract, declared page menus, anchored
   popups, ordered invocation/dismissal events, declared capabilities, and
   typed errors;
2. one explicit provider per advertised target: AppKit `NSMenu` main menu,
   Win32 window menu bars and `TrackPopupMenuEx`, and GTK3 popups plus the
   session-bus `com.canonical.dbusmenu` desktop menu host;
3. the engine page-context-menu round trip: Chromium's own Alloy menu model is
   published as virtual commands, the application answers exactly once, and the
   selected Chromium command is executed by Chromium itself;
4. the renderer surface: one exact-origin, main-frame, grant, and gesture
   checked operation that can present only a menu the host already declared;
5. the closed Electron `Menu`/`MenuItem` migration mapping, real three-target
   evidence, documentation, and the final acceptance review.

## Common implementation contract

```kotlin
public interface KWebMenus : KWebNativeService {
    public val events: Flow<KWebMenuEvent>
    public suspend fun capabilities(): KWebMenuCapabilities
    public suspend fun setApplicationMenu(tree: KWebMenuTree?): KWebMenuTreeResult
    public suspend fun setWindowMenu(windowId: KWebMenuWindowId, tree: KWebMenuTree?): KWebMenuTreeResult
    public suspend fun declarePageMenu(pageToken: KWebMenuPageToken, tree: KWebMenuTree): KWebMenuTreeResult
    public suspend fun clearPageMenu(pageToken: KWebMenuPageToken, menuId: KWebMenuId)
    public suspend fun showPopup(request: KWebMenuPopupRequest): KWebMenuPopupOutcome
}
```

The v1 descriptor is version `1.0.0`, service id `menus`, `APPLICATION` scope,
and publishes `set-application-menu`, `set-window-menu`, `declare-page-menu`,
`clear-page-menu`, `show-popup`, `capabilities`, `events` as host-only
operations plus `show-declared-popup` as the single renderer operation with the
exact grant `native.menus.show-declared-popup` and a required native gesture.

### Values and bounds

| Value | v1 rule |
|---|---|
| command, menu, window, page identifier | ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}` |
| label | 1–256 UTF-8 bytes, no control characters |
| mnemonic | one letter that appears in the label |
| accelerator | one to three distinct modifiers, exactly one published key |
| icon | one RFC 0027 package resource id plus SHA-256 digest, 1–256 px, straight RGBA8 |
| nodes per tree | at most 512, at most 8 nested levels |
| separator | only between two items, never first, last, or adjacent |
| tree version | strictly increasing per owner and menu identifier |
| open popups | at most 4 per service |
| event replay | exactly 64 ordered events |
| popup anchor | provider screen coordinates within ±32767 |

`KWebMenuTree` is immutable and carries its own `menuId` and `version`. A newer
version replaces the previous tree atomically; a stale version fails with
`menus.version-stale`. Duplicate command identifiers or accelerators inside one
tree are rejected before any provider call.

### Lifecycle, concurrency, and limits

- Owners are `application`, `window`, and `page`; a window owner must be
  registered, and a page owner must have a declared anchor before any page
  operation.
- Every native menu call is dispatched to the AWT event thread; the macOS
  provider additionally hops to the main queue because AppKit owns the main
  menu.
- A popup owns an immutable snapshot of the tree version that is current when
  it opens; replacement applies to the next presentation.
- Exactly one terminal event is emitted per presented popup: `Invoked` or
  `Dismissed`. A rejected presentation returns a typed status and emits no
  event.
- `close` releases every native menu, cancels pending presentation, and reports
  a sticky failure if a provider fails while closing.

### Errors, renderer, and migration policy

- Every rejection has a stable `menus.*` code; there is no fallback provider,
  no hidden in-window menu bar, and no in-page menu rendering.
- Capability-gated fields fail instead of disappearing: a mnemonic on a
  provider without mnemonic rendering fails `menus.mnemonic-unsupported`, and
  an icon on a provider without item icons fails `menus.icon-unsupported`.
- The renderer operation accepts only a declared menu identifier and a bounded
  page-space anchor. Menu templates, commands, native handles, and arbitrary
  coordinates are never accepted; the call settles when the presented popup
  closes, and a bridge timeout never cancels the host-owned popup.
- The page context-menu decision payload is JSON: `{"decision":"continue",
  "commandId":"chromium.copy"}` for a Chromium item, or `{"decision":
  "dismiss"}`. A second decision for one request fails
  `menus`-side as `page.context-menu.already-resolved`.

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| macOS arm64 | AppKit `NSMenu` | application main menu, native roles, icons, accelerators, popups; window menus report `menus.target-unsupported` because AppKit owns one main menu; mnemonics are not a macOS affordance |
| Windows x64 | Win32 | per-window menu bars with `SetMenu` plus subclassed `WM_COMMAND` routing, `TrackPopupMenuEx`, mnemonics, accelerator activation, radio runs; item icons are not advertised because standard Win32 menus cannot carry a bitmap beside a label |
| Linux x64 | GTK3 + GDBus | popups on a dedicated GTK thread; application/window bars are published as one `com.canonical.dbusmenu` object per window and registered with `com.canonical.AppMenu.Registrar`, advertised only while that host owns the session-bus name |

The application menu is the native bar when the provider reports
`APPLICATION_MENU` without `WINDOW_MENU`; otherwise the same tree is installed
on every declared window with the `application` owner, and a window registered
later receives the current tree.

### Engine page context menu

The engine publishes `KWEB_BROWSER_EVENT_CONTEXT_MENU` (ABI v17) with the
request id, view coordinates, frame identity, URL, origin, editable state,
selection, link, and the ordered item list. Items carry Chromium's own command
identifiers behind stable virtual commands (`chromium.back`, `chromium.copy`,
`chromium.id.<n>`); the host holds the mapping and completes the modal request
exactly once with `Continue(int id)` or `Cancel()`. Late or duplicate responses
fail with `context-menu-not-found` / `context-menu-already-resolved`. Without
`KWEB_BROWSER_CONFIG_CONTEXT_MENUS_ENABLED` the engine never installs the
handler, so Chromium's default menu remains the only behavior.

A Profile without a context-menu subscriber answers `dismiss` immediately, so
nothing waits on an absent application policy. A malformed payload is dismissed
and then fails the page with `page.context-menu.event-invalid`.

### Evidence lifecycle

The RFC binds `kweb-service-menus/src`, `kweb-service-menus/native`,
`kweb-cef-native/src`, `kweb-cef-native/include`, and the migration kit paths in
`docs/rfcs/evidence/contracts.json`. Records change when the service version,
schema, C ABI, engine ABI version, provider identity, or the pinned
CEF/Chromium identity changes; a stale record is never support.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Values are immutable, bounded, and validated before provider dispatch. | Empty/oversized labels, invalid identifiers, duplicate commands, duplicate accelerators, misplaced separators, deep or oversized trees. | Common contract tests plus native ABI validation on all targets. | `KWebMenus.kt`; `KWebMenusContractTest`; `menus_tests.cc` tree validation. | Local macOS and container Linux runs; hosted three-target records. | `NOT_RUN` before implementation. |
| A2 / tree application | Versions are strictly increasing per owner and applied atomically; stale versions fail typed. | Apply, re-apply the same version, apply a newer version, clear. | Common/JVM tests, native tests, real provider fixture. | `prepare`/`setApplicationMenu`; `TestApplicationMenuVersions`; `MenusIntegrationMain`. | Provider reports and integration JSON. | `NOT_RUN` before implementation. |
| A3 / application menu per target | The declared tree becomes a real native menu on the declared provider. | AppKit main menu items, Win32 menu bar item counts, D-Bus published layout. | Per-target structure tests with real UI objects. | `menus_structure_mac.mm`, `menus_structure_win.cc`, `menus_structure_linux.cc` (fake registrar on a private session bus). | Target provider transcripts. | `NOT_RUN` before implementation. |
| A4 / roles | Declared roles map to native behavior where the platform owns it, and are reported per provider. | Native role set per provider; a role outside the set is delivered as a command. | Capability assertions plus the AppKit structure test. | `KWebMenuRole`, `nativeRoles`, `TestLifecycle`. | Capability transcript. | `NOT_RUN` before implementation. |
| A5 / item forms | Separators, checkbox, radio, mnemonics, accelerators, and icons render where advertised. | Checkbox state, radio run, mnemonic letter, accelerator text, icon bytes. | Provider structure tests and the Compose/native fixture. | `BuildMenuItem`/`AppendItems`, `TestMacApplicationMenuStructure`, `MenusIntegrationMain`. | Fixture JSON per target. | `NOT_RUN` before implementation. |
| A6 / capability-gated fields | An unsupported field fails typed instead of being dropped. | Mnemonic on macOS, icon on Windows, icon without a resolver. | JVM policy tests and the capability-driven fixture. | `flatten`; `menus.mnemonic-unsupported`; `menus.icon-unsupported`. | Fixture boundary fields. | `NOT_RUN` before implementation. |
| A7 / popups and owners | A popup presents only a declared menu for the invoking owner with exactly one terminal outcome. | Undeclared menu, unknown window, page without anchor, out-of-range anchor, popup limit, window-owner space mismatch. | JVM tests, native ABI tests, provider fixtures. | `showPopup`; `menus_tests.cc` popup validation; `menus_abi.cc` request validation. | Native test transcript. | `NOT_RUN` before implementation. |
| A8 / renderer authority | The renderer presents only a predeclared menu id with a bounded anchor under exact-origin, main-frame, grant, and gesture policy. | Granted request, missing grant, child frame, missing gesture, malformed menu id, duplicate decision. | Policy tests on the generated bridge. | `KWebMenusBridge`; `KWebMenusBridgeTest`. | Test transcript. | `NOT_RUN` before implementation. |
| A9 / page context menu | Chromium's Alloy model reaches Kotlin once and the application completes it once. | Exactly one request per interaction, main frame, URL/origin, coordinates, `chromium.*` items, CONTINUE, duplicate decision, second interaction dismissed. | Real CEF fixture on every target. | `browser_session.cc` context-menu handlers and registry; `engine_abi.h`; `NativeEngineIntegrationMain` mode `context-menu`. | `KWEBSHELL_CONTEXT_MENU_PROBE` transcript plus hosted records. | `NOT_RUN` before implementation. |
| A10 / engine opt-in | Without the engine configuration flag Chromium's default menu stays untouched. | Flag absent, flag present, late response, response after close. | Engine ABI tests plus the fixture with the flag enabled. | `KWEB_BROWSER_CONFIG_CONTEXT_MENUS_ENABLED`; `GetContextMenuHandler`; `context-menu-not-found`, `context-menu-already-resolved`, `context-menu-closing`. | Fixture transcript. | `NOT_RUN` before implementation. |
| A11 / migration | Electron templates map to the typed model and unsupported shapes stay explicit blockers. | Template rewrite, custom click closure, unknown role, popup of a declared menu, absence of generic channels. | Inventory, generator, golden, and migration fixture tests. | `kweb-electron-migration` menu adapter, golden files, `KWebElectronCapabilityMatrixEntry` for menus. | Migration compatibility report. | `NOT_RUN` before implementation. |
| A12 / packaging and docs | Native libraries, schemas, generated output, and documentation agree. | Native package contents, generated TypeScript compile, packaged resource scan. | Gradle package/TypeScript tasks plus governance. | `nativeMenusRuntimeZip`, `verifyNativeMenusPackage`, `verifyMenusBridgeTypescript`. | Package digest report. | `NOT_RUN` before implementation. |
| A13 / security and evidence | No template, command, handle, path, or body leaks into errors, logs, or evidence. | Malformed payload, denied operation, provider failure, evidence scan. | Negative tests, typed error mapping, governance redaction scan. | `KWebMenuErrorCode`; decoder negative tests; evidence check. | Redacted provider/status facts. | `NOT_RUN` before implementation. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `NOT_RUN` before implementation. |
| A15 / deferred items | Tray menus, in-window Linux menu bars without a desktop host, and Windows item icons stay absent instead of approximated. | Declared capability absent on the target, typed failure on use. | Capability review and provider declarations. | `KWebMenuCapability`, Linux provider capability gate, Windows provider flags. | Capability transcript. | `NOT_RUN` before implementation. |

## Contract review record

- Reviewed revision: branch `rfc/0017-native-menus` at `3f524b5` (the contract,
  providers, engine round trip, and renderer bridge as implemented).
- Review pass: Delta agent, same contributor as the implementation; this is not
  an independent-person approval.
- Date: 2026-10-09.
- Findings and dispositions:
  - The proposal left the model, bounds, errors, owner semantics, and popup
    lifecycle undefined. The contract above settles every externally visible
    decision and gives each one a falsifiable scenario and an acceptance ID.
  - Item icons cannot be composed with a label by the standard Win32 menu API.
    Icons are therefore capability-declared and Windows reports the typed
    `menus.icon-unsupported` boundary instead of approximating the drawing;
    A6 and A15 record it.
  - macOS has no mnemonic affordance and one application-level menu bar.
    Mnemonics fail typed there, and window menus report
    `menus.target-unsupported`; both are declared target boundaries rather than
    silent drops.
  - Linux has no portable in-window native menu bar. The declared Linux
    application/window behavior is the desktop menu host over
    `com.canonical.dbusmenu`, advertised only while the registrar owns the
    session-bus name; without that host the capability is absent and the call
    fails typed. A hidden in-window bar is never substituted.
  - The page context menu had to preserve Chromium's own navigation/editing
    commands while letting the application apply policy. The reviewed design
    publishes Chromium's default Alloy model as virtual commands, lets the
    application compose the presented tree, and returns only the selected
    command to the host, which keeps the command mapping, the exactly-once
    completion, and the modal callback on the host side.
  - Feasibility probes: the pinned CEF runtime builds the new handler, the real
    CEF fixture passes the round trip on macOS arm64, the Win32 provider
    compiles in the hosted matrix, and the Linux provider compiles and passes
    its D-Bus structure test in an Ubuntu 24.04 container under
    `xvfb-run`/`dbus-run-session`.
- Decision: `READY`.

## Non-goals

No Electron `Menu` object identity, JavaScript function serialization, HTML or
in-page menu fallback, implicit global accelerator registration (RFC 0021), tray
status items (RFC 0018), custom icon bytes (RFC 0027 owns icon values), and no
second context-menu model beside Chromium's own Alloy model.
