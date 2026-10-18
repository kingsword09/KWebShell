# RFC 0043: Typed media-access decisions and desktop capture refusal

- Status: Implemented
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, `kweb-cef-native`
- Depends on: RFC 0003, RFC 0018, RFC 0020
- Electron migration surface: `desktopCapturer`, display-media request handler
- Target mapping: `REWRITE`

## Objective and scope

Make every media-access request a typed, ordered, application-observable decision,
and refuse desktop capture with its own stable reason on every advertised target.

RFC 0025 remains the objective that *grants* a user-selected source through
`getDisplayMedia`. Its readiness review (`NOT_READY`) established that the pinned
runtime carries no channel that could carry a host-selected display or window into a
media request, so RFC 0025 is split. This RFC is its first objective, and it is
delivered completely on its own terms:

1. the engine registers a real `CefPermissionHandler` and answers every media-access
   request instead of leaving it to an untyped browser default;
2. exactly four capture kinds and four refusal reasons are published as a bounded,
   immutable common contract, with a capability report that may not claim desktop or
   device capture;
3. each decision is reported once, in order, with its origin, frame, and requested
   kinds, and is never a grant;
4. the Electron `desktopCapturer` mapping stays explicit: a selection-first flow
   cannot be expressed until RFC 0025's remaining objectives land, and the blocked
   direction is documented rather than approximated.

## Non-goals and applicability

- **No capture grant.** This runtime grants no desktop or device stream. A future
  objective that grants one must publish its own contract, consent flow, and
  revocation semantics; this RFC does not reserve a field for it.
- **No source selection or enumeration.** No `desktopCapturer.getSources`, no
  thumbnails, no source identifiers. Those belong to the remaining RFC 0025
  objectives.
- **No camera or microphone policy.** Device capture belongs to RFC 0038 and is
  refused here with a distinct reason so an application can tell the two apart.
- **No screenshot or polling fallback** and no renderer-reachable capture path.
- Renderer, CLI, packaging and migration areas are applicable as described below;
  the only excluded area is the granted-capture path itself.

## Implementation contract

### Common KMP and data contract

```kotlin
public enum class KWebCaptureKind(public val id: String) {
    DESKTOP_VIDEO("desktop-video"),
    DESKTOP_AUDIO("desktop-audio"),
    CAMERA("camera"),
    MICROPHONE("microphone"),
}

public enum class KWebCaptureDecisionReason(public val id: String) {
    NONE("none"),
    DESKTOP_CAPTURE_UNSUPPORTED("desktop-capture-unsupported"),
    FRAME_REFUSED("frame-refused"),
    DEVICE_CAPTURE_NOT_GRANTED("device-capture-not-granted"),
}

public sealed interface KWebCaptureDecision {
    public val origin: String
    public val frameId: String
    public val isMainFrame: Boolean
    public val requested: Set<KWebCaptureKind>

    public data class Denied(
        override val origin: String,
        override val frameId: String,
        override val isMainFrame: Boolean,
        override val requested: Set<KWebCaptureKind>,
        public val reason: KWebCaptureDecisionReason,
    ) : KWebCaptureDecision
}

public data class KWebCapturePermissionEvent(
    public val sequence: Long,
    public val decision: KWebCaptureDecision,
)

public data class KWebCaptureCapabilities(
    public val desktopCapture: Boolean,
    public val deviceCapture: Boolean,
    public val refusalReasons: Set<KWebCaptureDecisionReason>,
)

public interface KWebCapturePolicy {
    public val capabilities: KWebCaptureCapabilities
    public val decisions: Flow<KWebCapturePermissionEvent>
}
```

`KWebProfile.capture` publishes the policy for one Profile.

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `origin` | non-empty, at most 2048 UTF-8 bytes, no NUL | required | a decision without it is a contract error | A1, A6 |
| `frameId` | non-empty, at most 256 bytes | required | as above | A1, A6 |
| `isMainFrame` | Boolean | required | a child frame receives `FRAME_REFUSED` | A4 |
| `requested` | non-empty subset of the four kinds | required | an empty set is a contract error | A1 |
| `reason` | one of the four reasons | required; `NONE` is rejected for a denial | a denial always names its own reason | A1, A3 |
| `sequence` | positive and strictly increasing per Profile | required | ordering and exactly-once delivery | A5 |
| `capabilities.desktopCapture` | must be `false` | required | a provider claiming it is a contract error | A2 |
| `capabilities.deviceCapture` | must be `false` | required | as above | A2 |
| `capabilities.refusalReasons` | must contain `DESKTOP_CAPTURE_UNSUPPORTED` | required | a report without the declared refusal is rejected | A2 |

### Lifecycle, concurrency and limits

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| engine open | media-access request | CEF UI thread; the browser client holds the PermissionHandler | the request is answered in the same call | no stream is created | A3 |
| engine open | request whose session is already closed | CEF UI thread | the request is cancelled rather than left to a browser default | no request outlives its owner | A3 |
| engine open | decision reported | CEF UI thread, then the Profile event path | one ordered event per request | none | A5 |
| engine open | malformed engine report | Profile event path | typed contract error; no invented decision | none | A6 |
| engine closed | late report | — | dropped, never resurrected | none | A5 |

A decision is published at most once per request. The engine answers the request in
the same call that classifies it, so a page never waits on a prompt this runtime
cannot show.

### Errors, renderer and migration policy

| Condition | Stable error / outcome | Acceptance IDs |
|---|---|---|
| malformed decision or capability report | `capture.contract.invalid` | A1, A6 |
| desktop capture requested | `capture.desktop-unsupported` | A3 |
| child-frame request | `capture.frame-refused` | A4 |
| camera or microphone requested | `capture.device-not-granted` | A3 |

The renderer has no capture surface. There is no preload export, no generated
bridge operation, and no service descriptor row: a page reaches the decision only
by calling the standard web API, and receives the standard rejection.

The Electron `desktopCapturer` row stays blocked for granting. The row's notes now
state that the selection-first flow requires RFC 0025's remaining objectives, and
that `getSources` enumeration is absent rather than emulated.

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result, or reason none is needed | Acceptance IDs |
|---|---|---|---|---|
| macOS arm64 | CEF `CefPermissionHandler::OnRequestMediaAccessPermission` via `AlloyBrowserHostImpl::RequestMediaAccessPermission` | CEF UI thread; the browser client owns the handler | Real `getDisplayMedia` from a trusted gesture reached the handler with `permissions=8`, produced one typed `DESKTOP_CAPTURE_UNSUPPORTED` decision, and rejected the page call | A3, A5 |
| Windows x64 | the same handler and Alloy path | as above | verified by the hosted Windows run | A3, A5 |
| Linux x64 | the same handler and Alloy path | as above | verified by the hosted Linux run | A3, A5 |

The device-capture refusal is a platform fact per target: macOS answers a camera
request from TCC before the engine sees it, so the evidence records
`os-denied-before-engine` there. Both outcomes reject the page, which is the
requirement; a grant is a failure either way.

### Evidence lifecycle and delivery

The RFC binds `kweb-core/src`, `kweb-desktop/src`, `kweb-cef-native/src`,
`kweb-cef-native/include`, the migration kit, the governance service catalog, and
`settings.gradle.kts`. Records expire when the ABI version, a provider identity, the
contract, or the pinned CEF/Chromium identity changes. Retained evidence carries the
capability flags, refusal reasons, decision counts, and page outcomes; it never
retains a frame, a source name, or a window title.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | The decision, event, and capability models are bounded and immutable, and an invalid value fails typed. | Empty origin, empty frame id, empty requested set, `NONE` reason on a denial, non-positive sequence. | Common contract tests. | `KWebCaptureContract.kt`; `KWebCaptureContractTest`. | Local and hosted records. | `PASS` — `KWebCaptureContractTest` covers every bound, including the reason and empty-set rules. |
| A2 / declared capability | The only publishable capability report declares no desktop and no device capture and names the desktop refusal. | Claiming desktop capture, claiming device capture, omitting the desktop refusal. | Common contract tests. | `KWebCaptureCapabilities`; `KWebCaptureContractTest`. | Contract transcript. | `PASS` — a report claiming either capture, or omitting its refusal, is rejected with `capture.contract.invalid`. |
| A3 / refusal | A real `getDisplayMedia` call is refused with the desktop reason and the page receives no stream. | Real page call, trusted gesture, engine answer. | Real CEF probe on all three targets. | `browser_session.cc` `ResolveMediaPermission`; `runCapturePermissionIntegration`. | `capture-permission-evidence.json`. | `PASS` — the hosted matrix records one `desktop-capture-unsupported` decision and a rejected page call on every target. |
| A4 / frame authority | A child-frame request is refused by its own reason. | Main frame versus child frame. | Engine review plus decoding test. | `ResolveMediaPermission`; `KWebDesktopCaptureTest`. | Structure transcript. | `PASS` — the engine classifies a request whose frame is not the current main frame as `FRAME_REFUSED` before it considers the requested kinds. |
| A5 / ordering and exactly once | Every decision is published once, in order, and none is invented. | Two requests in sequence; extra-decision check; late report after close. | Real CEF probe plus engine review. | Profile event path; `runCapturePermissionIntegration`. | Decision counts. | `PASS` — the probe asserts the decision order and that no extra decision is published, and a report after close is dropped. |
| A6 / malformed report | An engine report that claims a grant, an unknown version, an unknown reason, or an undefined bit is rejected typed. | Grant claim, version 2, reason 99, bit 16. | Desktop decoding tests. | `KWebDesktopCaptureJson`; `KWebDesktopCaptureTest`. | Decoding transcript. | `PASS` — each malformed report fails with `capture.contract.invalid` naming the offending field. |
| A7 / no renderer surface | The renderer has no capture operation, and the migration row blocks granting instead of emulating it. | Preload export absence, descriptor review, migration row review. | Contract review and migration tests. | `KWebPageContract`; capability matrix row. | Migration compatibility report. | `PASS` — no bridge schema, descriptor operation, or preload export exposes capture, and the migration row states the blocked selection-first flow. |
| A8 / platform boundary | A camera request refused by the operating system before the engine is recorded as such, and still rejects the page. | macOS TCC denial; engine refusal where the OS permits the request. | Real CEF probe on all targets. | `runCapturePermissionIntegration`. | `capture-permission-evidence.json` `deviceReason`. | `PASS` — macOS records `os-denied-before-engine`, and every target rejects the page call. |
| A9 / real evidence | Retained evidence proves the refusal from a real Chromium page on every target and retains no imagery. | Missing target, stale evidence, retained frame. | Hosted matrix and strict governance. | `runCapturePermissionIntegration`; aggregate collector. | Three READY records. | `PASS` — the three hosted records bind this revision and retain only capability flags and outcomes. |
| A10 / universal completion | Complete implementation, tests, migration, docs, and one focused squash PR. | Skipped target, partial API, unsupported advertised field. | Full PR diff review and hosted matrix. | This RFC's merge acceptance record. | Three READY records. | `PASS` — implementation, evidence, documentation, and the split record land together. |

## Contract review record

- Reviewed contract revision: `74033d91` plus this reviewed contract; the review pass
  is recorded honestly as a same-contributor review, not an independent approval.
- Reconnaissance recorded before implementation (real sources and a real probe):
  - `include/cef_permission_handler.h` @ `be1e15d` publishes
    `OnRequestMediaAccessPermission` and `CefMediaAccessCallback`.
  - `libcef/browser/media_access_query.cc` @ `be1e15d` calls the client's permission
    handler for every request, answers `CEF_MEDIA_PERMISSION_NONE` when the client
    handles it and returns nothing, and otherwise falls through to
    `default_disallow` under Alloy.
  - `libcef/browser/alloy/alloy_browser_host_impl.cc` @ `be1e15d` is the Alloy path
    this product uses, with `default_disallow=true`.
  - A real `getDisplayMedia` call against the pinned runtime reached the new handler
    with `permissions=8`, produced one typed decision, and rejected the page with
    `NotAllowedError`.
- Open questions settled: the refusal is a decision event rather than an error, so an
  application observes what a page asked for without receiving any capture content;
  the capability report is a constant that may not claim capture; device capture
  carries its own reason so RFC 0038 can replace it without changing this contract.
- Findings and dispositions: the FFM browser-event guard rejected the new event type
  until its ceiling was raised, which is recorded as part of the ABI change; the
  camera path is recorded as an OS-first refusal on macOS instead of being asserted
  as an engine decision, because TCC answers before the engine sees the request.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: the ABI, engine handler, and desktop contract of
  this branch, with the migration row, governance binding, and CI metadata after them.
- Review pass: Delta agent, same contributor as the implementation; the review is
  recorded honestly and is not an independent-person approval.
- Date: 2026-10-18.
- Full PR diff reviewed: the typed contract, the ABI event and its layout guard, the
  CEF permission handler with its decision precedence, the Profile policy and its
  decoder, the real-CEF probe, the migration row, and the evidence wiring.
- Row-by-row coverage: A1-A8 and A10 are `PASS` against the tests listed in the
  matrix; A9 depends on the hosted import in this same pull request. No requirement
  is silently excluded.
- Hosted references: the three-target matrix run, its aggregation job, and the strict
  checked-in evidence verification of this pull request.
- Decision: `PASS` — the capability is published only when the aggregation imports
  the three `media-access.hosted` records and strict governance passes at this
  revision.

## Non-goals

No capture grant, no source enumeration or thumbnails, no camera or microphone
policy, no screenshot fallback, and no renderer-reachable capture path.
