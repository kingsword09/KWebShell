# RFC 0008: Page lifecycle, navigation events, popup policy, and renderer failure

- Status: Accepted
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, CEF native browser session
- Depends on: RFC 0004, RFC 0007
- Electron migration surface: `webContents` lifecycle/events, `setWindowOpenHandler`, renderer-process failure
- Target mapping: `DIRECT` and `REWRITE`

## Objective

Publish the page behavior required by a desktop browser host as typed
`KWebPage` contracts: navigation phases, title/favicon/loading state, popup
requests, before-unload decisions, controlled reload, renderer responsiveness
and failure, and terminal page ownership. The implementation is one vertical
slice over the existing Alloy native child and the caller-owned Compose window
host on macOS, Windows, and Linux/X11.

Browser downloads are not owned by this RFC. Download selection and results
remain in RFC 0012; RFC 0008 does not publish a download event or operation.

## Public contract

The contract is version `kweb.page/2`. Breaking changes from the current draft
`KWebPageEvent` are intentional and all consumers must move in this RFC.

### Page identity and event envelope

Every event carries:

| Field | Type | Rule |
|---|---|---|
| `pageId` | `String` | The stable `KWebPage.id`; never reused by a Profile. |
| `profileId` | `String` | The explicit Profile name that owns the page. |
| `sequence` | `Long` | Starts at `1`, increments by one, and is never reused. |
| `type` | `KWebPageEventType` | Closed enum; unknown native values are a typed failure. |
| `frameId` | `String` | The opaque, globally unique CEF frame identifier; `"0"` means CEF has not created a frame yet. |
| `frameScope` | `KWebPageFrameScope` | `MAIN` or `SUBFRAME`; main-frame events use the page's main frame. |
| `origin` | `String?` | Canonical origin of the observed frame, or null before a committed origin exists. |
| `url` | `String?` | Canonical URL for navigation/address events; never a redacted display label. |
| `title` | `String?` | Bounded UTF-8 title, or null when the event has no title. An embedded NUL is replaced with U+FFFD; an empty title event clears the current title. |
| `faviconUrls` | `List<String>` | At most eight canonical URLs; empty on clear. |
| `bounds` | `KWebBounds?` | Present only for `RESIZED`. |
| `reason` | `KWebPageEventReason` | Stable reason for failures, cancellation, terminal state, or policy decisions. |
| `rendererFailureReason` | `KWebRendererFailureReason?` | Present only on `RENDERER_TERMINATED`; the native termination code remains in `statusCode`. |
| `statusCode` | `Int` | HTTP or native status when applicable; otherwise `0`. |
| `flags` | `Set<KWebPageEventFlag>` | Loading, history, redirect, and native-gesture facts. |

The event types are `CREATED`, `NAVIGATION_STARTED`, `NAVIGATION_COMMITTED`,
`SAME_DOCUMENT_NAVIGATION`, `ADDRESS_CHANGED`, `LOADING_STATE_CHANGED`,
`LOAD_ENDED`, `LOAD_FAILED`, `TITLE_CHANGED`, `FAVICON_CHANGED`,
`BEFORE_UNLOAD_REQUESTED`, `POPUP_REQUESTED`, `RENDERER_UNRESPONSIVE`,
`RENDERER_RESPONSIVE`, `RENDERER_TERMINATED`, `RESIZED`, `FATAL_ERROR`,
`CLOSED`, `DEVTOOLS_OPENED`, `DEVTOOLS_CLOSED`, and `DEVTOOLS_FAILED`.

`INPUT_GESTURE` remains internal. It can mint a scoped gesture token but is
never exposed as a public page event. A slow subscriber is handled by the RFC
0004 bounded event stream; it cannot block the CEF UI thread.

### Navigation and reload

`KWebPage.navigate(url)` accepts only an absolute `http`, `https`, `app`, or
`chrome-extension` URL that passes the existing engine/profile policy. The
host emits, in order, `NAVIGATION_STARTED`, `NAVIGATION_COMMITTED` or
`LOAD_FAILED`, and a terminal `LOAD_ENDED` when CEF reports completion. A
redirect is represented by a new started/committed pair with the `REDIRECT`
flag. Subframe navigation never becomes a page-level main-frame event.

Same-document history/hash changes emit `SAME_DOCUMENT_NAVIGATION` and
`ADDRESS_CHANGED` without a second page load. `LOAD_FAILED` includes the
typed `KWebPageEventReason` and CEF error code; `ERR_ABORTED` caused by a
superseding navigation is not reported as a failure.

The public reload operation is:

```kotlin
suspend fun reload(mode: KWebReloadMode = KWebReloadMode.NORMAL): KWebReloadResult
```

`NORMAL` and `IGNORE_CACHE` map directly to CEF reload operations. Reload is
rejected with `page.closed`, `page.renderer-terminated`, or
`page.operation-pending` when the page cannot accept a new navigation.

### Before-unload negotiation

When the page has a before-unload handler, the native JS-dialog callback emits
one `BEFORE_UNLOAD_REQUESTED` event with a monotonically allocated request id.
The host resolves it with:

```kotlin
suspend fun respondToBeforeUnload(
    requestId: Long,
    decision: KWebBeforeUnloadDecision,
): KWebBeforeUnloadResult
```

The only decisions are `PROCEED` and `CANCEL`. The request has a five-second
deadline. A duplicate, stale, or cross-page response fails with a typed error;
timeout is `CANCEL` and returns a result with `timedOut = true`. Closing a page
or Profile cancels every pending request before native teardown. No renderer
script can resolve the request.

### Popup negotiation

CEF 151's `OnBeforePopup` is synchronous and has no continuation callback. It
cannot suspend the renderer's `window.open` operation while Kotlin waits for a
decision. KWebShell therefore emits a `KWebPopupRequest` and immediately
cancels that CEF popup. The event contains:

- opener page/profile/frame identity and exact committed origin;
- target URL, frame name, and user-gesture fact;
- CEF-provided `x`, `y`, `width`, `height`, and `isPopup` values. CEF does not
  provide redirect, resizable, fullscreen, menu-bar, tool-bar, status-bar, or
  always-on-top values in this callback, so those fields are not inferred.

The request is resolved by:

```kotlin
suspend fun respondToPopup(
    requestId: Long,
    decision: KWebPopupDecision,
): KWebPopupResult
```

`DENY` and `ALLOW(owner, bounds)` are the only decisions. `owner` must be an
explicit caller-created `KWebComposeWindowHost`; the host extracts and checks
its current native parent on the AWT event thread. KWebShell never creates a
hidden or unmanaged top-level owner. `ALLOW` creates a separate normal Alloy
native child attached to that owner and returns the new `KWebPage.id` only
after its `CREATED` event. It does not restore the renderer's canceled popup
window proxy or `window.opener` relationship; applications that depend on
those semantics must rewrite that flow. Reparenting, renderer-selected
owners, raw handles, and Chrome top-level popup surfaces are rejected.

Popup requests have a five-second deadline and default to `DENY`. Profile/page
close, duplicate resolution, invalid owners/bounds, policy denial, and native
child creation failure each have distinct typed errors. Feature positions are
clamped to the current viewport; unset or invalid dimensions are omitted. The
requested features are advisory data; the caller's bounds and capabilities
remain authoritative.

### Renderer state and terminal ownership

`KWebPageEventType.RENDERER_UNRESPONSIVE` and `RENDERER_RESPONSIVE` reflect
CEF's real responsiveness callbacks. `RENDERER_TERMINATED` is terminal and
contains `KWebRendererFailureReason` (`CRASH`, `KILLED`, `OOM`, or `UNKNOWN`)
plus the native termination code. It is emitted once, followed
by one `CLOSED` event after the native child is closed. Browser close, Profile
close, and engine close each use the same terminal ordering; no event or
popup/before-unload callback may be delivered after `CLOSED`.

The test-only native renderer crash hook remains internal and is used only to
prove the real CEF termination path. There is no public kill or arbitrary
renderer-evaluation API.

## Policy and errors

- Main-frame navigation is allowed only by the existing Profile/engine URL
  policy. Cross-origin navigation clears the origin-scoped bridge and pending
  gesture tokens before `NAVIGATION_COMMITTED`.
- Popup policy is host-owned and request-only. Missing response is denial;
  there is no fallback owner, backend, or renderer-created window.
- Before-unload is host-owned. Timeout and owner close cancel safely; they do
  not silently continue navigation.
- Titles are capped at 4096 UTF-8 bytes. Favicon lists are capped at eight
  URLs of 2048 UTF-8 bytes each. Popup feature values are bounded to the page
  viewport and reject negative dimensions or overflow.
- Stable errors include `page.closed`, `page.operation-pending`,
  `page.renderer-terminated`, `page.navigation-invalid`,
  `page.before-unload-stale`, `page.before-unload-timeout`,
  `page.popup-stale`, `page.popup-timeout`, `page.popup-owner-invalid`,
  `page.popup-bounds-invalid`,
  `page.popup-policy-denied`, `page.event-backpressure`, and
  `native.renderer-failure`.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / typed page envelope | Events expose page/profile/frame/origin identity, typed reason, bounded title/favicon data, and contiguous sequence. | Main/subframe events; embedded NUL replacement and oversized title; eight/9 canonical favicons; sequence starts at 1 and has no gaps. | Common contract tests, generated/native ABI tests, real hosted page fixture on macOS, Windows, Linux/X11. | `KWebPageContract.kt`, `KWebPageEventStream`, C ABI event schema, public contract tests. | NOT_RUN before implementation. | NOT_RUN |
| A2 / navigation order | Redirect, HTTP failure, history, reload, same-document and cross-origin navigation produce the declared ordered phases. | Redirect chain; 404; abort superseded navigation; hash/history; subframe navigation excluded from main-frame phases. | Real HTTP/HTTPS fixture and CEF transcript on all hosted targets. | `SessionClient` navigation callbacks; `KWebDesktopPage`; navigation integration fixture. | NOT_RUN before implementation. | NOT_RUN |
| A3 / title and favicon | Title and favicon changes are bounded, origin-associated, and cleared at a new committed navigation. | Empty title; oversized title; multiple favicon URLs; cross-origin clear; subframe title ignored. | Real CEF display callbacks and event transcript on all hosted targets. | CEF display handler, page event mapping, title/favicon contract tests. | NOT_RUN before implementation. | NOT_RUN |
| A4 / before-unload | Before-unload requests are single-resolution, page-bound, five-second bounded, and default-cancel on timeout/close. | Proceed/cancel; duplicate/stale response; timeout; page/Profile close while pending; no renderer resolution. | Real JS before-unload fixture and native JS-dialog callback on all hosted targets. | `CefJSDialogHandler`, pending decision registry, `respondToBeforeUnload` tests. | NOT_RUN before implementation. | NOT_RUN |
| A5 / popup policy | CEF popup is canceled immediately; the host request defaults to denial and may create a separate Alloy child only in an explicit Compose owner. | Allow/deny/timeout; invalid owner; invalid bounds; cross-origin target; canceled JS popup proxy; no hidden top-level owner. | Real `window.open`/link fixture, event transcript, and native child hierarchy evidence on all hosted targets. | `OnBeforePopup`, popup decision registry, `respondToPopup`, RFC 0007 hierarchy integration. | NOT_RUN before implementation. | NOT_RUN |
| A6 / renderer responsiveness | Unresponsive/responsive/terminated transitions are real, typed, single-terminal, and ordered before page close. | Busy-loop hang/recovery; crash; external kill; Profile/Engine close during unresponsive; no callbacks after close. | Real CEF renderer hang/crash fixtures and retained process/lifecycle transcript on all hosted targets. | `OnRenderProcessUnresponsive/Responsive/Terminated`, `NativeBrowser`, renderer failure tests. | NOT_RUN before implementation. | NOT_RUN |
| A7 / reload and terminal ownership | Controlled reload returns typed outcome, honors before-unload, and terminal page ownership is closed exactly once. | Normal/ignore-cache reload; pending popup/before-unload; renderer terminated; page/Profile/Engine close; duplicate close. | Kotlin lifecycle tests and real CEF reload/close fixture on all hosted targets. | `KWebPage.reload`, native reload ABI, lifecycle state machine. | NOT_RUN before implementation. | NOT_RUN |
| A8 / backpressure and concurrency | Slow subscribers cannot block CEF UI; popup/before-unload responses remain bounded and ordered. | Slow collector; event capacity boundary; concurrent response; close during callback; native callback after owner release. | RFC 0004 stream tests plus hosted callback stress on all targets. | `KWebPageEventStream`, callback dispatcher, bounded decision registries. | NOT_RUN before implementation. | NOT_RUN |
| A9 / security and origin policy | No renderer can resolve popup/before-unload, create owners, access another page, or retain a bridge across cross-origin navigation. | Child frame; forged request id; cross-origin navigation; unconfigured page; raw native handle attempt. | Generated bridge inspection and real CEF negative fixtures on all hosted targets. | Desktop host policy, bridge origin reset, typed error tests. | NOT_RUN before implementation. | NOT_RUN |
| A10 / migration and docs | `webContents` events are classified individually; unsupported/unknown events block migration; docs/capability metadata match implementation. | Known supported events; rewrite-required events; unknown event name; popup policy mapping; renderer failure mapping. | Migration unit/golden tests, README, capability matrix, complete PR review. | Electron capability matrix and migration contract/golden files. | NOT_RUN before implementation. | NOT_RUN |
| A11 / universal completion | Every applicable row passes on every advertised target and final review binds the tested contract/evidence revision. | Missing target, stale digest, skipped native test, changed contract after evidence, or unsupported advertised platform blocks merge. | Governance check, git diff check, hosted macOS/Windows/Linux matrix, final row review. | Complete PR diff, evidence manifest/artifacts, final acceptance review. | NOT_RUN before implementation. | NOT_RUN |

## Readiness review

- Reviewed revision: `7cfb3c1`, the contract and acceptance-matrix revision
  reviewed before implementation.
- Review pass: Codex implementation-readiness review, same contributor as the
  eventual implementation; this is not an independent-person approval.
- Date: 2026-09-24.
- Decisions: `KWebPage` moves to the versioned `kweb.page/2` event envelope;
  main/subframe identity is explicit; popup and before-unload decisions are
  host-owned bounded requests; renderer termination is terminal; reload is a
  typed operation; downloads remain RFC 0012; no generic Electron object or
  script-evaluation API is added.
- Feasibility: the existing pinned CEF 151 Alloy session already compiles the
  `CefClient`, `CefDisplayHandler`, `CefLoadHandler`, `CefRequestHandler`, and
  `CefLifeSpanHandler` surfaces used by the current browser session. RFC 0007
  proves caller-owned native child creation on macOS, Windows, and Linux/X11.
  The implementation will add the CEF popup, JS-dialog, favicon, and
  responsiveness callbacks to that same native session and retain a hosted
  compile/runtime probe before evidence recording. No new OS window primitive
  or alternate renderer is required.
- Findings and disposition: the proposal did not settle public signatures,
  frame/origin identity, bounded title/favicon data, popup owner semantics,
  before-unload terminal precedence, renderer failure ordering, or its
  relationship to RFC 0012 downloads. This revision settles each item and
  adds A1-A11 mapping, explicit limits, stable errors, and evidence inputs.
- Decision: `READY`.

### Contract re-review after CEF API inspection

- Reviewed revision: the implementation contract revision containing the CEF
  ABI and page API work in this PR, before continuing implementation.
- Review pass: Codex readiness re-review by the same contributor; it is not an
  independent-person approval.
- Date: 2026-09-24.
- Findings: the pinned CEF 151 `OnBeforePopup` callback is synchronous and
  exposes only position, size, and `isPopup`; it cannot preserve an asynchronous
  renderer popup while awaiting Kotlin. Its type also has no redirect or
  window-decoration fields. The previous draft promised behavior and fields
  this CEF callback cannot report.
- Disposition: revise popup intent to cancel the renderer popup immediately,
  then let an explicit host decision create a separate owner-bound Alloy page.
  Document that the new page has no `window.opener` proxy; classify this as a
  renderer rewrite in migration guidance. Remove unsupported feature fields.
  `KWebPageEvent` removes the untyped `text` payload in favor of its versioned
  typed fields and carries a dedicated renderer failure reason.
- Feasibility: CEF supplies the event, request ID, bounded callback data, and a
  real Alloy child creation path under the caller's native parent. The
  separate-page semantics are falsifiable in the real renderer fixture.
- Decision: `READY` for this revised contract.

## Evidence lifecycle

RFC 0008 evidence is retained under
`docs/rfcs/evidence/artifacts/0008/<sourceRevision>/<target>/`. Each target
retains a page event transcript, popup hierarchy/decision transcript, renderer
failure transcript, and the live page/native-owner counters. The RFC 0008
contract binding covers the common page API, desktop event/lifecycle adapter,
CEF browser session/client/ABI, migration matrix, docs, and the hosted
aggregation workflow. Any change to those inputs invalidates the affected
records and requires a fresh three-target aggregation before support is
published.

## Migration contract

| Electron surface | KWebShell result |
|---|---|
| `webContents` navigation/load/title/favicon events | Typed `KWebPageEvent` stream with main/subframe and origin identity. |
| `webContents.reload()` | `KWebPage.reload(NORMAL)` or `IGNORE_CACHE`. |
| `webContents.setWindowOpenHandler` | Rewrite required: CEF popup is canceled, then the host may create a separate owner-bound page; no `window.opener` proxy is preserved. |
| `before-unload` | Host-resolved `KWebBeforeUnloadRequest`; timeout cancels. |
| renderer-process-gone/unresponsive | Typed renderer state/failure events and terminal page lifecycle. |
| `executeJavaScript` and arbitrary event strings | Not mapped; explicit CDP remains the automation path. |
| unmanaged popup/top-level BrowserWindow | Not mapped; explicit caller-owned Compose owner is required. |

## Non-goals

No generic Electron `webContents` object, unrestricted script evaluation,
download manager, unowned popup, string event subscription, hidden top-level
Chrome window, renderer-selected popup policy, raw native handle, or renderer
kill API is part of RFC 0008. Download selection and scoped download results
remain RFC 0012.
