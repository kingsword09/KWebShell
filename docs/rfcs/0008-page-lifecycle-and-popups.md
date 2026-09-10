# RFC 0008: Page lifecycle, navigation events, popup policy, and renderer failure

- Status: Proposed
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, CEF native browser session
- Depends on: RFC 0004, RFC 0007
- Electron migration surface: `webContents` lifecycle/events, `setWindowOpenHandler`, renderer-process failure
- Target mapping: `DIRECT` and `REWRITE`

## Objective

Publish the `webContents`-class behavior applications need as typed `KWebPage`
contracts: navigation phases, title/favicon/loading state, popup requests,
renderer responsiveness/failure, before-unload outcome, and controlled reload.

## Contract

Events MUST carry Page/Profile identity, monotonic sequence, frame scope,
committed origin, and typed reason. Popup requests are suspended host decisions
with a deadline and immutable requested features; allowed popups receive an
explicit Compose/native owner. Main-frame and subframe events are different
types.

Arbitrary renderer evaluation is not a general application API. Typed bridge and
explicit CDP remain the only automation paths.

## Acceptance

1. A controlled HTTPS/app-origin server drives redirects, failures, history,
   reload, download conversion, same-document navigation, before-unload, and
   popup requests with exact event order.
2. Renderer crash, kill, hang/recovery, browser close, and Profile close each
   produce one terminal sequence and no callbacks after Page close.
3. Popup allow/deny/timeout and owner creation are tested with real native child
   surfaces on all platforms; no unmanaged Chrome top-level appears.
4. Favicons and titles are bounded, origin-associated, and cleared on navigation.
5. Migration fixtures classify supported `webContents` events individually;
   unknown event names block packaging.
6. Event streams survive slow subscribers according to RFC 0004 backpressure
   without blocking CEF UI.

## Evidence

Retain event transcripts, popup window hierarchy, crash/hang process evidence,
and live Page/native-surface counters.

## Non-goals

No generic Electron `webContents` object, unrestricted script evaluation,
unowned popup, string event subscription, hidden top-level Chrome window, or
renderer-selected popup policy.
