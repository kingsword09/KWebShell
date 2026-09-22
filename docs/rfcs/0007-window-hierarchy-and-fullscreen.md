# RFC 0007: Window hierarchy, modal ownership, fullscreen, and close negotiation

- Status: Implementing
- Priority: P0
- Owners: `kweb-service-window-controls`, `kweb-compose`, desktop host
- Depends on: RFC 0002, RFC 0003
- Platform targets: macos, windows, linux
- Electron migration surface: `BrowserWindow` parent/child/modal/fullscreen/kiosk/closable/movable/minimizable/maximizable/alwaysOnTop APIs
- Target mapping: `REWRITE`

## Objective and scope

Extend `kweb-service-window-controls` from state and basic mutations to one
complete caller-owned window-management contract. Compose remains responsible
for creating and disposing every top-level window; the service attaches typed
relationships and controls an already-created native window. The service never
creates a hidden owner, a replacement window, an overlay, a second browser
surface, or an alternate renderer.

The objective covers one focused vertical slice:

1. immutable parent ownership and window/application modality for caller-owned
   Compose windows;
2. native focus, z-order, owner disable/reenable, attention, and deterministic
   child-before-parent teardown;
3. distinct windowed, fullscreen, and kiosk modes with exact prior-bounds
   restoration across monitor and DPI changes;
4. observed constraints and capability state for movable, minimizable,
   maximizable, closable, resizable, always-on-top, and bounds operations;
5. bounded host close negotiation with renderer request-only access and an
   application-forced terminal path; and
6. migration mapping, real CEF child-parentage verification, platform evidence,
   and the final acceptance matrix.

Transparent frameless hit testing, custom title bars, menus, trays,
BrowserView/WebContentsView identity, and window creation remain separate
contracts. Browser document fullscreen is not this window fullscreen contract.

## Implementation contract

### 1. Typed identity, registration, and state

The existing service is upgraded to descriptor version `2.0.0`; this is an
intentional pre-1.0 breaking change. Existing `KWebWindowState` and
`KWebWindowPlacement.FULLSCREEN` shapes are replaced by the v2 model below.
The service key remains `window-controls`, so there is one installed owner per
caller-owned window and no parallel registry.

```kotlin
public typealias KWebWindowId = String

public enum class KWebWindowModality {
    NONE,
    WINDOW_MODAL,
    APPLICATION_MODAL,
}

public enum class KWebWindowFullscreenMode {
    WINDOWED,
    FULLSCREEN,
    KIOSK,
}

public enum class KWebWindowPlacement {
    FLOATING,
    MAXIMIZED,
    MINIMIZED,
}

public enum class KWebWindowAttention {
    NONE,
    REQUESTED,
}

public data class KWebWindowConstraints(
    public val minimumWidth: Int = 1,
    public val minimumHeight: Int = 1,
    public val maximumWidth: Int? = null,
    public val maximumHeight: Int? = null,
)

public data class KWebWindowRegistration(
    public val id: KWebWindowId,
    public val parentId: KWebWindowId? = null,
    public val modality: KWebWindowModality = KWebWindowModality.NONE,
    public val initialConstraints: KWebWindowConstraints = KWebWindowConstraints(),
)

public data class KWebWindowState(
    public val id: KWebWindowId,
    public val parentId: KWebWindowId?,
    public val modality: KWebWindowModality,
    public val title: String,
    public val bounds: KWebWindowBounds,
    public val restoredBounds: KWebWindowBounds?,
    public val placement: KWebWindowPlacement,
    public val fullscreen: KWebWindowFullscreenMode,
    public val visible: Boolean,
    public val focused: Boolean,
    public val movable: Boolean,
    public val minimizable: Boolean,
    public val maximizable: Boolean,
    public val closable: Boolean,
    public val resizable: Boolean,
    public val alwaysOnTop: Boolean,
    public val constraints: KWebWindowConstraints,
    public val attention: KWebWindowAttention,
    public val displayId: String?,
    public val displayScale: Double?,
)
```

`id` is non-empty, Unicode-safe, at most 256 UTF-8 bytes, and unique within
the application owner. `parentId` and `modality` are immutable after the
window is attached. A parent must be registered before its child. A child
cannot be reparented; changing ownership requires disposing the caller-owned
window and creating a new one in the caller's normal Compose flow.

Bounds use signed coordinates in `[-1_000_000, 1_000_000]` and dimensions in
`[1, 16_384]`. Constraint dimensions use the same bounds. A maximum smaller
than its minimum, a missing parent, a duplicate id, a parent cycle, an invalid
display scale, or an unknown display id fails with a typed configuration error
before native mutation. The title remains bounded at 4096 Unicode code units
and rejects NUL characters.

The service publishes a monotonic `KWebWindowEvent.sequence`, starting at 1,
with a 64-event replay buffer. A full event buffer is a terminal typed service
failure; events are never silently dropped or coalesced across state changes.

### 2. Operations and lifecycle

```kotlin
public interface KWebWindowControls : KWebNativeService {
    public val registration: KWebWindowRegistration
    public val state: StateFlow<KWebWindowState>
    public val events: Flow<KWebWindowEvent>
    public val closeRequests: Flow<KWebWindowCloseRequest>

    public suspend fun snapshot(): KWebWindowState
    public suspend fun setTitle(title: String): KWebWindowState
    public suspend fun setBounds(bounds: KWebWindowBounds): KWebWindowState
    public suspend fun setConstraints(constraints: KWebWindowConstraints): KWebWindowState
    public suspend fun setVisible(visible: Boolean): KWebWindowState
    public suspend fun focus(): KWebWindowState
    public suspend fun minimize(): KWebWindowState
    public suspend fun restore(): KWebWindowState
    public suspend fun setMaximized(maximized: Boolean): KWebWindowState
    public suspend fun setFullscreen(mode: KWebWindowFullscreenMode): KWebWindowState
    public suspend fun setMovable(movable: Boolean): KWebWindowState
    public suspend fun setMinimizable(minimizable: Boolean): KWebWindowState
    public suspend fun setMaximizable(maximizable: Boolean): KWebWindowState
    public suspend fun setClosable(closable: Boolean): KWebWindowState
    public suspend fun setAlwaysOnTop(alwaysOnTop: Boolean): KWebWindowState
    public suspend fun setResizable(resizable: Boolean): KWebWindowState
    public suspend fun requestAttention(): KWebWindowState
    public suspend fun clearAttention(): KWebWindowState
    public suspend fun requestClose(): KWebWindowCloseResult
    public suspend fun respondToClose(
        requestId: Long,
        decision: KWebWindowCloseDecision,
    ): KWebWindowCloseResult
    public suspend fun forceClose(reason: KWebWindowForceCloseReason): KWebWindowCloseResult
}
```

All operations are serialized per window and execute native mutations on the
platform event thread. They return the observed native state, not the
requested state. `setBounds` is valid only for a restored, windowed window;
the native provider clamps or rejects constraints explicitly and the returned
state records the result. Unsupported operations return
`service.operation-unavailable` with target/provider details.

The lifecycle is:

```text
NEW -> ATTACHED -> VISIBLE -> QUIESCING -> CLOSED
                  \-> HIDDEN
ATTACHED/VISIBLE/HIDDEN/QUIESCING -> FAILED
```

`close()` is idempotent. After `CLOSED` or `FAILED`, every operation fails
with `service.owner-closed` or the recorded terminal failure. Disposing the
caller-owned window closes the service; the service never calls `dispose()` on
that external window.

### 3. Parent, modality, focus, and teardown

`NONE` leaves the owner enabled. `WINDOW_MODAL` disables only the registered
parent while the child is visible. `APPLICATION_MODAL` disables every other
registered application window. A modal child is raised above its owner,
focus requests raise the owner chain first, and child close reenables the
exact windows that were disabled by that child. Disabled state is observed in
the report and is not inferred from the request.

Parent closure enters `QUIESCING`, requests forced close for descendants in
reverse registration order, waits for each terminal native result, and only
then releases the parent's native relationship. A child that outlives its
parent is a provider failure. No hidden top-level window is created to retain
ownership. The maximum hierarchy depth is 64 and the maximum registered
application windows is 256.

### 4. Fullscreen and kiosk

`FULLSCREEN` and `KIOSK` are separate modes. Fullscreen hides native
decorations and permits a host exit. Kiosk additionally makes the window
non-movable, non-resizable, non-minimizable, and non-closable through user or
renderer operations; only an application-forced exit can leave kiosk mode.
The host can still close the window through `forceClose`.

Before entering either mode, the provider stores the exact logical bounds,
placement, constraints, display identity, and display scale. It applies the
native monitor transition, waits for the observed fullscreen state, and emits
one ordered state event. Exiting restores the stored logical bounds and
placement after recalculating against the current DPI on the same display. A
display removal, unavailable monitor, or native transition timeout is a typed
failure and does not select another display or backend. A monitor/DPI change
must leave the window with the exact stored logical bounds and restored
placement when the display remains available.

### 5. Constraints and native capability state

The provider reports effective state for `movable`, `minimizable`,
`maximizable`, `closable`, `resizable`, and `alwaysOnTop`. A capability that
the current window manager cannot enforce fails explicitly; it is never
reported as supported merely because a setter was called. Maximize, minimize,
fullscreen, and kiosk are mutually ordered transitions. A request arriving
while another transition is pending returns `service.operation-unavailable`
with `transition-pending` rather than racing native state.

Attention is a bounded native request. `requestAttention` may result in
`REQUESTED` or a typed `service.operation-unavailable`; `clearAttention` must
return `NONE`. Attention does not create a new window or alter focus policy.

### 6. Close negotiation

```kotlin
public enum class KWebWindowCloseSource { USER, OS, RENDERER, APPLICATION }
public enum class KWebWindowCloseDecision { ALLOW, DENY }
public enum class KWebWindowCloseOutcome {
    PENDING,
    ALLOWED,
    DENIED,
    TIMED_OUT,
    FORCED,
    OWNER_CLOSED,
}
public enum class KWebWindowForceCloseReason { APPLICATION_SHUTDOWN, PARENT_CLOSED, TEST }

public data class KWebWindowCloseRequest(
    public val requestId: Long,
    public val source: KWebWindowCloseSource,
    public val deadlineMillis: Long,
)

public data class KWebWindowCloseResult(
    public val requestId: Long,
    public val outcome: KWebWindowCloseOutcome,
    public val state: KWebWindowState,
)
```

There is at most one pending close request per window. Repeated user/OS close
signals coalesce to that request. The initial operation result is `PENDING`
and the `closeRequests` flow carries the request for host resolution. A pending
request has a 5-second deadline;
`DENY` before the deadline keeps the window open and emits the resulting state.
If no decision arrives, the provider closes the window and returns `TIMED_OUT`.
`ALLOW` closes it and returns `ALLOWED`. An application `forceClose` wins over
any pending request, returns `FORCED`, invalidates the request, and cannot be
vetoed. A response after resolution returns
`service.close-request-resolved` and never reopens the window. Close results
are terminal and are emitted once.

The renderer bridge exposes only `request-close`, which creates a
`RENDERER` request after exact-origin, main-frame, owner, permission, and
single-use user-gesture checks. It cannot respond to a request, change
hierarchy, enter kiosk, force close, or mutate native capabilities. Navigation,
page close, origin change, or gesture expiry invalidates an uncommitted
renderer request with a typed cancellation result.

### 7. Native platform contract

| Target | Required native mechanisms | Required failure behavior |
| --- | --- | --- |
| Windows x64/arm64 | `SetWindowLongPtrW` owner/style updates, `EnableWindow`, `SetWindowPos`, `MonitorFromWindow`/`GetMonitorInfoW`, `Get/SetWindowPlacement`, `FlashWindowEx`, and native close/focus messages. | Remote/invalid HWND, unsupported style transition, missing monitor, and failed observed state return typed errors. |
| macOS arm64/x64 | `NSWindow` owner/child relationships, sheet/modal presentation, `NSWindowDelegate` close decision, `styleMask`/collection behavior, screen frame/scale, `setLevel`, and `requestUserAttention`. | Wrong AppKit thread, stale `NSWindow`, unavailable screen, rejected presentation, and unobserved close/fullscreen state return typed errors. |
| Linux x64/arm64 (X11 session) | `WM_TRANSIENT_FOR`, `_NET_WM_STATE_MODAL`, `_NET_WM_STATE_FULLSCREEN`, `_NET_WM_STATE_ABOVE`, `_NET_ACTIVE_WINDOW`, X11 normal-size hints, urgency hints, and XDG session identity. | Missing X11/display/session, unsupported window-manager atom, stale XID, or unobserved state returns `service.platform-unavailable` or `service.operation-unavailable`; Wayland is not silently substituted. |

The platform binding is internal to the JVM FFM/native layer. No AWT, CEF,
Objective-C, Win32, or X11 type enters common Kotlin. The provider consumes
the caller's existing native top-level handle and keeps the CEF native-child
parent handle unchanged across every window transition.

### 8. Errors, limits, and policy

The implementation uses the existing typed service errors plus these stable
details/codes: `window.registration-invalid`, `window.parent-missing`,
`window.parent-cycle`, `window.relationship-mismatch`,
`window.transition-pending`, `window.close-request-pending`,
`service.close-request-resolved`, `service.platform-unavailable`,
`window.display-unavailable`, and `window.native-state-unobserved`.

No operation retries on another backend, display, or window. Every failure
identifies the target, provider, window id, and operation where available.

### 9. Migration contract

`kweb-electron-migration` maps a closed `BrowserWindow` declaration to:

| Electron declaration | KWebShell result |
| --- | --- |
| `parent` / `modal` | caller-created Compose parent plus immutable registration relationship |
| `fullscreen` / `kiosk` | host `setFullscreen(FULLSCREEN/KIOSK)` |
| `closable`, `movable`, `minimizable`, `maximizable`, `resizable`, `alwaysOnTop` | typed window-control mutations |
| `setBounds` / `getBounds` | bounded `KWebWindowBounds` and observed state |
| `close` / `beforeunload`-style host negotiation | typed close request/result; renderer cannot force close |

Dynamic construction, arbitrary reparenting, BrowserWindow object identity,
raw native handles, renderer kiosk/fullscreen escalation, and arbitrary close
listeners are blocking migration findings. The adapter emits typed Compose and
service ownership instructions, not a `BrowserWindow` facade.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / typed contract | v2 publishes typed registration, state, capabilities, fullscreen, hierarchy, bounds, events, close requests/results, fixed limits, and stable errors. | Unicode title; duplicate id; zero/negative bounds; invalid constraints; 64/65 event boundary; duplicate close response. | Common contract/schema tests and API review; hosted target compile. | `KWebWindowControls` v2 common contract, bridge schema, contract tests. | Planned `window-hierarchy-report.json` plus contract digest for the RFC/module/bridge/native inputs. | `NOT_RUN` before implementation. |
| A2 / parent ownership | Caller-created parent/child windows retain immutable parent identity, child-before-parent teardown, and no hidden or service-created top-level windows. | Missing parent; cycle; reparent attempt; parent close with 1/64/256 descendants; owner disposal. | Real ComposeWindow parent/child fixture on macOS, Windows, Linux/X11. | JVM registration provider and hierarchy registry; real UI fixture. | Per-target hierarchy report with parent graph, teardown order, visible-window count, and native handles redacted. | `NOT_RUN` before implementation. |
| A3 / modality and focus | Window/application modal child disables the correct owner set, preserves z-order/focus, and reenables exactly those owners on close. | Focus before show; repeated focus; two modal children; denied/forced close; owner disposal. | Native UI automation and observed state transcript on all hosted targets. | Platform providers and ComposeWindow integration test. | Modal/focus transcript and final enabled/visible/focused counts. | `NOT_RUN` before implementation. |
| A4 / fullscreen and kiosk | Fullscreen and kiosk are distinct; monitor/DPI changes restore exact prior logical bounds and placement. | Enter/exit twice; maximize->fullscreen; fullscreen->kiosk denied; monitor change; missing display; timeout. | Real native monitor/DPI fixture on macOS, Windows, Linux/X11. | Platform fullscreen provider and state restoration tests. | Report stores pre/post bounds, placement, display identity/scale, and observed transitions. | `NOT_RUN` before implementation. |
| A5 / capabilities and constraints | Movable/minimizable/maximizable/closable/resizable/always-on-top/attention and bounds return observed native state. | Unsupported manager; clamped bounds; pending transition; kiosk mutation; external native change. | Direct Kotlin and real native UI tests on all hosted targets. | Extended window-controls provider and platform state reader. | Capability/state transcript with requested versus observed values and typed failures. | `NOT_RUN` before implementation. |
| A6 / close negotiation | Close requests are bounded, coalesced, single-resolution, renderer-request-only, and application force-close wins. | Allow, deny, timeout, duplicate response, navigation invalidation, parent close, application shutdown. | Host UI, renderer exact-origin bridge, and RFC 0006 application shutdown integration on all targets. | Close request state machine, bridge policy, lifecycle participant integration. | `window-close-transcript.json` with request source, deadline, result, and terminal counts. | `NOT_RUN` before implementation. |
| A7 / CEF parentage | The native child browser remains attached to the original caller-owned parent through hierarchy, modal, fullscreen, DPI, and close transitions. | Repeated transitions; parent/child close race; browser resize during transition; 256-cycle stress. | Real CEF native-child integration on macOS, Windows, Linux/X11. | CEF browser-surface parent handle inspection and lifecycle fixture. | CEF parent-stability digest, live native owner counts, and shutdown artifact. | `NOT_RUN` before implementation. |
| A8 / security and policy | Renderer cannot create/dispose windows, reparent, kiosk, force-close, bypass close policy, or access another window. | Forged sender; child frame; cross-origin; missing grant/gesture; stale request; raw handle input. | Generated bridge inspection, negative renderer fixtures, policy audit on all targets. | Exact-origin route and operation grants; no renderer native handle. | Redacted security transcript and bridge generated bytes. | `NOT_RUN` before implementation. |
| A9 / platform feasibility | Declared Win32/AppKit/X11 providers use exact APIs and fail explicitly when unavailable; no Wayland/backend fallback. | Missing display/session; wrong thread; invalid native handle; unsupported WM atom; rejected AppKit presentation. | Native ABI/provider probes and hosted UI artifacts on all targets. | Internal FFM/native bindings and platform provider tests. | Provider identity, ABI fingerprint, platform status, and failure codes. | `NOT_RUN` before implementation. |
| A10 / migration/docs/evidence | Migration, capability metadata, user docs, tests, evidence bindings, and RFC status are updated together. | Dynamic BrowserWindow construction; stale digest; missing target artifact; undocumented unsupported field. | Migration fixture, governance check, docs check, full PR diff review. | RFC, `docs/application-lifecycle.md`/window-controls docs, migration mapper, CI aggregation. | Manifest records and retained hierarchy/close artifacts for all required targets. | `NOT_RUN` before implementation. |
| A11 / universal completion | Every applicable requirement passes on every advertised target and final review binds the exact tested revision. | Any skipped native test, stale artifact, changed contract after review, or unsupported promised target blocks merge. | `:kweb-rfc-governance:check`, `git diff --check`, hosted matrix, final row-by-row review. | Complete PR diff, implementation, evidence import, and final acceptance review. | Final contract/evidence digests, run/attempt, artifact hashes, and reviewed revision. | `NOT_RUN` before implementation. |

## Readiness review

- Reviewed revision: `e6917b6` (`main` after RFC 0006 squash merge). The
  existing `kweb-service-window-controls` contract, Compose native-parent
  proof, RFC 0002 provider SDK, RFC 0003 policy engine, and RFC 0006 shutdown
  participant were inspected.
- Review pass: Codex implementation-readiness review, same contributor as the
  eventual implementation; this is not an independent-person approval.
- Date: 2026-09-22.
- Decisions: RFC 0007 extends `kweb-service-window-controls` to descriptor
  v2; it does not create a second window service or a hidden window backend.
  Parent and modality are immutable registration facts; Compose remains the
  creator/disposer; close negotiation is bounded to five seconds; application
  force-close wins; fullscreen and kiosk are separate; Linux support is the
  X11 hosted contract and Wayland has an explicit typed-unavailable result.
- Feasibility: existing hosted ComposeWindow/window-controls integration in
  the RFC 0006 validation run proves the caller-owned native parent and AWT
  lifecycle on macOS arm64, Windows x64, and Linux x64. The implementation
  will retain platform probes for Win32 owner/style/monitor APIs, AppKit
  child/sheet/fullscreen APIs, and X11 EWMH/transient/urgency APIs before
  publishing v2 support. These probes are falsifiable acceptance artifacts,
  not mock substitutes.
- Findings and disposition: the original proposal lacked public signatures,
  immutable relationship rules, modal owner semantics, fullscreen restoration,
  close terminal precedence, renderer restrictions, numeric limits, platform
  session scope, and evidence outputs. This revision settles each item and
  adds A1-A11 mapping.
- Decision: `READY`.

## Evidence lifecycle

RFC 0007 evidence will live under
`docs/rfcs/evidence/artifacts/0007/<sourceRevision>/<target>/` and will bind
the RFC 0007 contract, the v2 window-controls module and bridge schema, the
platform provider/native bindings, the RFC 0006 shutdown contract, and the
runtime CEF identity. Each hosted target retains a hierarchy/fullscreen report,
a close-negotiation transcript, and a CEF parent-stability/shutdown report.
Any change to those inputs invalidates the affected records and requires a
fresh three-target aggregation before the RFC can move to `Implemented`.

## Non-goals

No window creation or disposal API, hidden owner window, alternate renderer,
Wayland fallback, BrowserWindow object identity, raw native handle bridge,
transparent frameless hit testing, custom title-bar implementation, native
menus, tray ownership, or renderer-controlled force close is part of RFC 0007.
