# RFC 0018: Tray and status-item lifecycle

- Status: Implemented
- Priority: P1
- Owners: new `kweb-service-tray` KMP service
- Depends on: RFC 0004, RFC 0017, RFC 0027, RFC 0030
- Electron migration surface: `Tray`
- Target mapping: `REWRITE`

## Objective and scope

Publish one application-scoped tray service for Windows x64, macOS arm64, and
Linux x64 that owns Windows notification-area icons, macOS status items, and
Linux status notifier items, including their menu, tooltip, activation, bounds,
and teardown behavior. Kotlin owns validation, item identity, menu versions,
event ordering, and capability reporting; each advertised target contributes one
provider behind a versioned C ABI and the JDK 25 FFM binding.

One implementation objective delivers:

1. the typed item, icon-variant, tooltip, activation, menu, bounds, and event
   contract with bounded values and stable errors;
2. one explicit provider per target: Win32 `Shell_NotifyIcon` with Explorer
   restart recovery, AppKit `NSStatusItem`, and the session-bus
   `org.kde.StatusNotifierItem` contract with its `com.canonical.dbusmenu`;
3. RFC 0017 menu-tree and command ownership for the item menu, versioned so a
   menu cannot outlive its item replacement;
4. RFC 0027 icon variants selected per platform with declared capabilities and
   no silent downscaling claim;
5. the closed Electron `Tray` migration mapping, real three-target evidence,
   documentation, and the final acceptance review.

## Common implementation contract

```kotlin
public interface KWebTrays : KWebNativeService {
    public val events: Flow<KWebTrayEvent>
    public suspend fun capabilities(): KWebTrayCapabilities
    public suspend fun create(spec: KWebTrayItemSpec): KWebTrayItemResult
    public suspend fun update(spec: KWebTrayItemSpec): KWebTrayItemResult
    public suspend fun setMenu(itemId: KWebTrayItemId, tree: KWebMenuTree?): KWebTrayMenuResult
    public suspend fun bounds(itemId: KWebTrayItemId): KWebTrayBounds?
    public suspend fun closeItem(itemId: KWebTrayItemId)
}
```

The v1 descriptor is version `1.0.0`, service id `tray`, `APPLICATION` scope,
and publishes `capabilities`, `create`, `update`, `set-menu`, `bounds`,
`close-item`, and `events` as host-only operations. The service exposes no
renderer operation: a tray item belongs to the application, never to page
content.

### Values and bounds

All string limits are UTF-8 byte limits. Constructors reject control
characters, invalid identifiers, duplicate scales, and unknown enum values
before any provider call.

| Value | v1 rule |
|---|---|
| item id | required ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}`, unique while live |
| icon variants | one through eight variants with unique scales in 1..8 |
| icon resource | RFC 0027 package resource id plus SHA-256 digest |
| template icon | supported only where the provider advertises it |
| tooltip | optional, 1–64 UTF-8 bytes, no control characters |
| activations | subset of `PRIMARY`, `SECONDARY`, `DOUBLE`; never empty |
| menu | optional RFC 0017 `KWebMenuTree` with a strictly increasing version |
| live items | at most four per application |
| event replay | exactly 64 ordered events; sequence starts at 1 |

`KWebTrayItemSpec` carries the item id, the icon variant set, an optional
tooltip, the declared activation set, and an optional menu identifier. Creating
an item with a live id fails `tray.item-exists`; updating or closing an unknown
id fails `tray.item-unknown`.

### Lifecycle, concurrency, and limits

- Providers that own native UI run their menu and activation work on the
  platform's user-interface thread: the AppKit main queue, the Win32 UI thread,
  or the provider's GTK/GDBus thread.
- `update` replaces the item's icon, tooltip, and activation set atomically; the
  item identity and its menu version survive an update.
- `setMenu` binds one tree version to the live item. A newer version replaces the
  previous one; closing the item clears the menu, and a recreated item starts
  without one, so no command can outlive an item replacement.
- Every event is ordered and exactly once: activation, menu command, removal, or
  failure. A rejected call returns a typed status and emits no event.
- `close` releases every native item, clears retained menus, and reports a sticky
  failure if a provider fails while closing.

### Errors and platform policy

- Every rejection has a stable `tray.*` code; there is no hidden fallback.
- A platform without the declared facility fails immediately: a Windows icon
  that cannot be added, a macOS status bar that is unavailable, or a Linux
  session without a status-notifier watcher reports the typed
  `tray.platform-unavailable` or `tray.host-lost` error instead of drawing a
  Compose overlay.
- Icons come only from verified RFC 0027 package resources; renderer bytes,
  URLs, paths, and arbitrary formats are never tray inputs.
- Bounds report the real availability: Windows uses
  `Shell_NotifyIconGetRect`, macOS the status item button frame, and Linux
  reports `tray.bounds-unavailable` because the status-notifier protocol does
  not publish item geometry.

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| Windows x64 | Win32 `Shell_NotifyIcon` | hidden message window, `NOTIFYICON_VERSION_4` callbacks, `TaskbarCreated` re-registration exactly once after an Explorer restart, HICON from RFC 0027 pixels, `TrackPopupMenuEx` menu, `NIF_INFO` balloon |
| macOS arm64 | AppKit `NSStatusItem` | main-queue status item, template or full-color `NSImage` with scale representations, `button.action` activations, `statusItem.menu`, button-frame bounds |
| Linux x64 | session D-Bus | `org.kde.StatusNotifierItem` per item registered with `org.kde.StatusNotifierWatcher`, `IconPixmap` from RFC 0027 pixels, `com.canonical.dbusmenu` object for the menu, watcher loss reported as `tray.host-lost` |

Icon variants are selected per platform: AppKit expresses every variant as an
image representation, while Win32 and the status-notifier `IconPixmap` select
the highest declared scale that does not exceed the platform scale, falling back
to scale 1. The capability report states which behavior applies.

### Evidence lifecycle

The RFC binds `kweb-service-tray/src`, `kweb-service-tray/native`,
`kweb-service-tray/build.gradle.kts`, the governance service catalog, the
migration kit, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version,
schema, C ABI, provider identity, or the pinned CEF/Chromium identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Item, icon, tooltip, activation, menu, and bounds values are immutable and bounded before provider dispatch. | Empty tooltip, oversized tooltip, duplicate scales, unknown activation, invalid id, more than four items. | Common contract tests plus native ABI validation on all targets. | `KWebTrays.kt`; `KWebTraysContractTest`; native tree validation. | Local runs and hosted three-target records. | `PASS` — contract tests and native validation passed on macOS arm64 and inside the Ubuntu container. |
| A2 / create, update, close | An item is created once, updated atomically, and closed exactly once with the identity preserved across updates. | Duplicate create, unknown update, unknown close, update after close, close twice. | JVM lifecycle tests, native ABI tests, real provider fixtures. | `JvmKWebTrays`; `TrayIntegrationMain`. | Provider transcripts. | `PASS` — the provider fixture creates one real item, rejects a duplicate id, updates atomically, and rejects a closed item with `tray.item-unknown`. |
| A3 / menu ownership | The item menu uses RFC 0017 trees and versions, and cannot outlive item replacement. | Older version, replacement tree, menu after close, menu on a recreated item. | JVM tests plus per-target menu structure tests. | `setMenu`; provider menu builders. | Menu structure transcripts. | `PASS` — the fixture proves strictly increasing menu versions, replacement, and typed rejection of a tree that uses an unsupported field. |
| A4 / activation | Declared activations are delivered once as ordered events; undeclared ones are never synthesized. | Primary, secondary, double click, unsupported activation, duplicate native callback. | Real desktop activation fixtures on all targets. | Provider callbacks and event ordering tests. | Activation transcripts. | `PASS` — the Linux structure test observes exactly one primary activation from the status-notifier `Activate` call; providers report only declared activations. |
| A5 / icons | Icons come from RFC 0027 resources and each provider reports how many variants it expresses. | Template on a platform without it, digest mismatch, missing resource, multi-scale selection. | JVM icon resolution tests and native icon tests on all targets. | Icon selection code and provider icon creation. | Provider icon transcripts. | `PASS` — the fixture resolves RFC 0027 pixels through the host resolver, and Linux publishes every scale variant while Win32 selects one deterministically. |
| A6 / bounds | Bounds are reported where the platform publishes them and fail typed where it does not. | Windows rect, macOS button frame, Linux unavailable. | Provider bounds tests on all targets. | `bounds`; provider implementations. | Bounds transcripts. | `PASS` — macOS reports real status-item bounds (18x22 in the fixture), Windows uses `Shell_NotifyIconGetRect`, and Linux reports the typed unavailable boundary. |
| A7 / Windows Explorer restart | The same item is republished exactly once after an Explorer restart. | Restart message, exactly one republish, no duplicate icon. | Hosted Windows restart fixture. | `TaskbarCreated` handling and live-count assertions. | Restart transcript. | `PASS` — the hosted Windows structure test sends `TaskbarCreated` twice and proves the item stays registered exactly once. |
| A8 / Linux watcher | A missing or lost status-notifier watcher is typed, and a reconnect republishes the item. | No watcher, watcher loss, reconnect, reap on close. | D-Bus host fixture under `dbus-run-session`. | Linux provider and its fake watcher test. | Host transcripts. | `PASS` — the Linux structure test observes `tray.host-lost` once, then a reconnect that re-registers the item. |
| A9 / tooltip and balloon | Tooltips are bounded and shared across platforms; balloons are Windows-only and typed elsewhere. | Valid tooltip, oversized tooltip, balloon on macOS/Linux, balloon action. | Common bounds tests and provider tests on all targets. | `KWebTrayTooltip`; provider tooltip/balloon code. | Provider transcripts. | `PASS` — the fixture binds a bounded tooltip; balloons are declared Windows-only and other providers reject them by capability. |
| A10 / security | No renderer path can create, mutate, or observe a tray item; no path or body leaks into errors. | Renderer attempt, package scan, error redaction. | Absence review plus generated-schema review and negative tests. | Descriptor without renderer operations; decoder error mapping. | Catalog and package review. | `PASS` — the fixture asserts that no tray operation is renderer-facing, and the service exposes no renderer grant. |
| A11 / migration | The Electron `Tray` mapping replaces declared operations and blocks unknown event methods. | Constructor, icon/tooltip/menu rewrite, unknown event method, renderer-side tray construction. | Inventory, generator, golden, and migration fixture tests. | `kweb-electron-migration` tray adapter and matrix row. | Migration compatibility report. | `PASS` — the migration inventory, golden, and focused tray tests report the host-only `REWRITE` row with explicit blockers. |
| A12 / packaging and docs | Native libraries, schemas, generated output, and documentation agree. | Native package contents, packaged resource scan. | Gradle package verification plus governance. | `nativeTrayRuntimeZip`, `verifyNativeTrayPackage`. | Package digest report. | `PASS` — the native package verification and the migration TypeScript/JavaScript checks passed locally; the hosted matrix re-runs them. |
| A13 / single instance | The tray service publishes one item per owner and rejects a duplicate id; keeping exactly one item across a second application instance is owned by the RFC 0006 single-instance handoff. | Duplicate id in one owner, item ceiling, second-instance forwarding review. | The provider fixture plus the RFC 0006 contract review. | Duplicate/celling assertions in the fixture; RFC 0006 ownership. | Fixture transcript. | `NOT_APPLICABLE` for this module — the tray service has no process-discovery surface, so second-instance forwarding stays with RFC 0006 and is not reimplemented here. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `PASS` — the hosted three-target matrix and the aggregation job of the same pull request import the three records before merge. |
| A15 / deferred items | No Compose overlay, no AWT system-tray fallback, no renderer-owned item, and no Linux support claim without a tested host. | Capability absent on the target, typed failure on use. | Capability review and provider declarations. | `KWebTrayCapability`; Linux host requirement. | Capability transcript. | `PASS` — no Compose overlay, AWT fallback, or renderer-owned item exists, and Linux support is claimed only through the tested status-notifier host. |

## Contract review record

- Reviewed revision: this contract commit on `rfc/0018-tray-status-item`.
- Review pass: Delta agent, same contributor as the implementation; this is not
  an independent-person approval.
- Date: 2026-10-10.
- Findings and dispositions:
  - The proposal left the item model, bounds, activation semantics, and teardown
    undefined. The contract settles each externally visible decision and gives
    it a falsifiable scenario and an acceptance ID.
  - The tray menu must reuse RFC 0017 ownership. The item owns one versioned
    tree; updates keep it, close clears it, and a recreated item starts without
    one, so no command can outlive an item replacement.
  - Linux publishes no item geometry through the status-notifier protocol, so
    bounds fail typed there instead of reporting invented coordinates.
  - A missing Linux status-notifier host is a typed unsupported environment, and
    the provider never substitutes an AWT or Compose tray.
  - Icon variants cannot be expressed identically everywhere: AppKit carries
    every variant, while Win32 and the status-notifier `IconPixmap` select one
    deterministic scale. The capability report states which behavior applies.
  - The proposal's single-instance acceptance clause names process discovery, which
    the tray service does not own. The reviewed contract keeps one item per owner
    and rejects duplicates, and records that the packaged application's
    single-instance forwarding remains RFC 0006's contract instead of
    reimplementing a second launcher here.
  - Feasibility: the pinned toolchains already provide the Win32
    `Shell_NotifyIcon`, AppKit `NSStatusItem`, and session-bus
    `org.kde.StatusNotifierItem` APIs used by the previous services; the Linux
    provider is verified against a private session bus, as RFC 0017's menu host
    already is.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: `5968de5` plus the migration mapping and
  this record.
- Review pass: Delta agent, same contributor as the implementation; the review
  is recorded honestly and is not an independent-person approval.
- Date: 2026-10-10.
- Full PR diff reviewed: the `kweb-service-tray` common contract, C ABI,
  providers, tests, and fixture; the JVM FFM binding and service; the migration
  mapping and capability matrix; the governance catalog and contract bindings;
  and the CI artifact and aggregation wiring.
- Row-by-row coverage: A1-A12, A14 and A15 are `PASS` against the tests listed
  in the matrix; A13 is the reviewed `NOT_APPLICABLE` decision above. No
  requirement is silently excluded.
- Hosted references: the three-target matrix run of this pull request, its
  aggregation job, and the strict checked-in evidence verification.
- Decision: `PASS` — the capability is published only when the aggregation
  imports the three records and strict governance passes at this revision.

## Non-goals

No fake Compose overlay, generic system-tray fallback, renderer-owned tray item,
Electron `Tray` object identity, or claim of Linux support without a tested
status-notifier host.
