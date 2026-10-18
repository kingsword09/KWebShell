# RFC 0044: Page find sessions and page zoom

- Status: Implemented
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium Page adapter
- Depends on: RFC 0004, RFC 0008
- Electron migration surface: `webContents.findInPage`, `webContents.stopFindInPage`, `webContents.setZoomFactor` / `getZoomFactor`, `webContents.setZoomLevel` / `getZoomLevel`
- Target mapping: `DIRECT`

## Objective and scope

Publish one page-scoped find and zoom contract backed by Chromium itself, on
all three advertised targets, and expose it through the existing native child
engine:

1. one owned find session per page, with ordered match updates, forward and
   backward navigation, a case policy, an explicit stop, and typed refusal of a
   continuation that has no live session;
2. one page zoom level applied to Chromium and read back from Chromium, with
   level/factor conversion that refuses out-of-range input instead of clamping
   it, and the per-profile/per-host storage Chromium actually implements.

This RFC is one objective carved out of [RFC 0028](0028-page-utilities.md).
RFC 0028 remains `Proposed` for viewport capture, save/export, source and DOM
diagnostics, and visibility/throttling facts; its readiness review records the
split. Nothing from those areas is published here.

## Non-goals and applicability

- No viewport or page capture, save/export, MHTML, page source, DOM transport,
  or renderer code execution; those stay with RFC 0028.
- No zoom persistence across engine restarts and no application-managed zoom
  store. Chromium keeps the level per profile and page host for the lifetime of
  the profile context; making it durable is RFC 0028's own objective.
- No smooth zoom animation, no per-element zoom, and no renderer-side CSS zoom
  control.
- No fallback: a platform that cannot reach `CefBrowserHost::Find`, `StopFinding`,
  `SetZoomLevel`, or `GetZoomLevel` fails with a typed native error instead of
  emulating find in Kotlin or scaling the surface.

Universal completion areas map as follows: common contract and bounds A1-A3;
lifecycle and ordering A4-A6; zoom semantics A7; security/authority A8;
migration A9; platform providers A10; evidence A11; packaging/docs A12; the
full-PR and clean-worktree rule A13.

## Implementation contract

### Common KMP contract

`kweb-core` publishes the types in
`KWebPageUtilitiesContract.kt` and adds `KWebPage.utilities`:

```kotlin
public interface KWebPageUtilities {
    public val activeFind: StateFlow<KWebFindSession?>
    public val findResults: Flow<KWebFindResult>
    public suspend fun find(request: KWebFindRequest): KWebFindResult
    public suspend fun findNext(): KWebFindResult
    public suspend fun stopFinding(keepSelection: Boolean = true): KWebFindStopResult
    public suspend fun zoom(): KWebZoomState
    public suspend fun setZoomLevel(level: Double): KWebZoomState
    public suspend fun setZoomFactor(factor: Double): KWebZoomState
}
```

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `KWebFindRequest.text` | 1-512 UTF-8 bytes, no NUL, no lone surrogate | required; empty or invalid text fails before provider dispatch | `page.find-query-invalid` | A1, A2 |
| `KWebFindRequest.direction` | `FORWARD` or `BACKWARD` | defaults to `FORWARD` | selects the scan direction | A2 |
| `KWebFindRequest.matchCase` | Boolean | defaults to `false` | case-sensitive scan when true | A2 |
| `KWebFindSession.id` | positive, monotonic per page | assigned by the page; never reused | identifies one session | A1, A5 |
| `KWebFindResult.matches` | `>= 0` | final update for the request | Chromium's own match count | A2 |
| `KWebFindResult.activeMatchOrdinal` | `null`, or `0..matches-1` | `null` when Chromium has not selected a match for the request | zero-based active position | A2, A4 |
| `KWebFindResult.finalUpdate` | Boolean | exactly one final update per request | marks the terminal update | A4 |
| `KWebFindResult.selection` | `KWebFindSelection` in device-independent view coordinates | present on every update; Chromium reports an empty rectangle until a match is selected | active match location | A2, A4 |
| `KWebFindStopResult.outcome` | `STOPPED` or `NO_ACTIVE_SESSION` | `NO_ACTIVE_SESSION` when no session is active | terminal stop outcome | A5 |
| `KWebZoomState.level` | finite; factor inside `[0.25, 5.0]` | returned from the engine read-back | Chromium's applied level | A7 |
| `KWebZoomState.factor` | `1.2^level` in double precision | derived, never requested back | observed factor | A7 |

`KWebZoomState.MINIMUM_FACTOR`, `MAXIMUM_FACTOR`, and `FACTOR_STEP` are the
published Chromium constants; `kWebZoomLevelForFactor`, `kWebZoomFactorForLevel`,
and `kWebValidatedZoomLevel` are the only conversion entry points.

### Engine ABI

The native child engine gains one browser event and four browser commands:

| ABI item | Shape | Behavior | Acceptance IDs |
|---|---|---|---|
| `KWEB_BROWSER_EVENT_FIND_RESULT` (29) | `request_id` = session id, `details` JSON `{version,matches,active,final,selection{x,y,width,height}}` | one event per Chromium find notification for the active session | A2, A4 |
| `kweb_browser_find` | `(browser, session_id, text, direction, match_case, mode)` | starts or continues the session; refuses empty/oversized text, unknown direction/mode, stale continuation | A1, A2, A5 |
| `kweb_browser_stop_finding` | `(browser, keep_selection)` | ends the session; idempotent at the native boundary | A5 |
| `kweb_browser_set_zoom_level` | `(browser, level, double *level_out)` | applies on the UI thread and returns `GetZoomLevel()` | A7 |
| `kweb_browser_zoom_level` | `(browser, double *level_out)` | returns the level Chromium currently observes | A7 |

The ABI version moves from 19 to 20. `KWEB_STATUS_FIND_SESSION_STALE` (93) and
`KWEB_STATUS_ZOOM_LEVEL_INVALID` (94) are the new typed statuses.

### Lifecycle, concurrency, and limits

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| No session | `find` | page open, request valid | session active; one event stream per update | monotonic session id | A1, A4 |
| Active session | `find` | page open | previous session replaced; its later updates are dropped by the engine | one waiter released | A5 |
| Active session | `findNext` | session id matches the active one | same session, next match | none | A2, A4 |
| No session | `findNext` | page open | `page.find-session-stale` | none | A5 |
| Active session | `stopFinding` | page open | `STOPPED` with the last count | pending waiter cleared | A5 |
| No session | `stopFinding` | page open | `NO_ACTIVE_SESSION` | none | A5 |
| Document navigation commits | automatic | UI thread | session cleared; `findNext` then fails `page.find-session-stale` | none | A5 |
| Page or engine closes | automatic | any | in-flight find fails `page.closed`; `activeFind` becomes null | waiters cleared | A6 |
| Find request outstanding | 5 s elapse | IO | `page.find-timeout`; the session stays active for an explicit stop | waiter dropped | A6 |
| Zoom request | `setZoomLevel` / `setZoomFactor` | page open | serialized per page; engine read-back returned | none | A7 |

All page utility calls are serialized by one coroutine mutex per page. Native
find notifications arrive on the browser process UI thread and are published in
arrival order; `KWebFindResult.sequence` is the engine event sequence and is
strictly increasing per page.

### Errors, renderer, and migration policy

| Condition | Stable error / outcome | Acceptance IDs |
|---|---|---|
| Empty, NUL, oversized, or invalid-Unicode query | `page.find-query-invalid` | A1 |
| Continuation without an active session | `page.find-session-stale` | A5 |
| No final update within the deadline | `page.find-timeout` | A6 |
| Non-finite or out-of-range level/factor | `page.zoom-level-invalid` / `page.zoom-out-of-range` | A7 |
| Page or engine already closed | `page.closed` | A6 |
| Any other native status | `native.abi.<status>` with the native status id | A10 |

Find and zoom are host-only operations: they are not exposed on the generated
renderer bridge, require no renderer grant, and grant the renderer nothing. A
page therefore cannot start, stop, or rescale searches except through its own
owner. The migration matrix classifies `webContents.findInPage` +
`stopFindInPage` as `web-contents-find` and the four zoom methods as
`web-contents-zoom`, both `DIRECT`. The static inventory keeps classifying the
imported `webContents` binding to its module row, because Electron instance
methods are not resolvable as module bindings in the declared analysis; that
boundary is stated in the matrix note instead of being simulated.

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result | Acceptance IDs |
|---|---|---|---|---|
| macOS arm64 | `CefFindHandler`, `CefBrowserHost::Find/StopFinding/SetZoomLevel/GetZoomLevel` from the pinned CEF 151 distribution | find notifications on the browser-process UI thread; zoom applied and read on the UI thread | local `page-utilities` probe on CEF 151.3.16: 3/4/5 match counts, ordinals, zoom 2.0 read back | A2, A7, A10 |
| Windows x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A7, A10 |
| Linux x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A7, A10 |

Feasibility facts established by reading the pinned CEF sources and the real
probe:

- `CefClient::GetFindHandler` is honored for the native child; `CefBrowserHostBase::Find`
  posts to the UI thread itself.
- CEF forwards a find reply only when it differs from the previous one, so a
  restart with an unchanged count would be invisible. A new search therefore
  calls `StopFinding(false)` before `Find(...)`, which makes the new scan
  observable without inventing a result.
- Chromium clears the reported rectangle on the final reply whose active match
  ordinal is 0, and uses 1-based ordinals afterwards; the published contract
  normalizes that to `null`/zero-based.
- Chromium keys page zoom by profile and host (`HostZoomMap`): another host and
  another profile are unaffected, while pages of the same host in one profile
  observe the same level.

### Evidence lifecycle and delivery

Bound contract paths: `kweb-core` contract and tests, `kweb-desktop` native
bindings and page utilities, `kweb-cef-native` ABI and session implementation,
`kweb-electron-migration` matrix, the aggregate script, and this RFC. The
recorded artifact is `engine-integration/page-utilities-evidence.json` from the
three-target verification jobs plus the migration compatibility report.

Any change to the common fields/bounds/enums, event ordering, native ABI,
zoom semantics, or the evidence producer invalidates the affected target
records. The sequence is: common/provider unit tests, native ABI tests, the
real-CEF probe on each target, migration/golden tests, evidence aggregation,
strict governance, and the final row-by-row review.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common contract | `KWebPage.utilities` publishes the typed find and zoom surface with the declared bounds. | Empty/max query, NUL, lone surrogate, unknown enum, session id 0. | Common contract tests; all targets compile. | `KWebPageUtilitiesContract.kt`, `KWebPageUtilitiesContractTest.kt` | Contract digest in the recorded evidence | `PASS` — 7 contract tests cover the query bounds, lone surrogates, ordinal bounds, session id, and the zoom conversions; all three targets compiled and recorded the same contract digest. |
| A2 / find scan | Chromium's own match count, case policy, direction, and match selection reach the application. | Case-sensitive 3 vs case-insensitive 4 matches, forward and backward, selection rectangle. | Real-CEF `page-utilities` probe on all three targets. | `runPageUtilitiesIntegration`, `KWEB_BROWSER_EVENT_FIND_RESULT` | `page-utilities-evidence.json` per target | `PASS` — every target recorded `caseSensitiveMatches=3`, `caseInsensitiveMatches=4`, a selected-match width of 43, and the empty rectangle Chromium reports before it selects a match. |
| A3 / query policy | Invalid queries are refused before any native mutation. | Oversized, empty, NUL, invalid UTF-8 at the ABI; Kotlin rejects before dispatch. | Common tests and native ABI negative tests. | `KWebFindRequest` init, `kweb_browser_find` guards | Test symbols retained in the artifacts | `PASS` — the contract test rejects empty, NUL, lone surrogate, and 513-byte queries; the native ABI contract test rejects a missing session id, empty text, and an unknown mode before browser lookup. |
| A4 / ordered updates | Every update is published in arrival order with exactly one final update per request. | Strictly increasing sequences, one final per request, last update belongs to the last session; progressive update count differs per target. | Real-CEF probe plus flow assertions. | `KWebDesktopPageUtilities.onFindResult`, probe ordering assertions | `orderedUpdates`/`finalUpdates` in evidence | `PASS` — every target recorded 6 finals for 6 requests and strictly increasing sequences (12 progressive updates on Windows and Linux, 13 on macOS, so the probe asserts the invariant, not the count). |
| A5 / session ownership | A new search replaces the session; continuation, stop, and stale continuation are typed. | Replacement, `findNext`, `stopFinding` twice, `findNext` after stop, navigation commit. | Real-CEF probe and native ABI status tests. | `FindInPage`/`StopFinding`, `ResetFindSession`, `FIND_SESSION_STALE` | Evidence `staleSessionRejected`, `stopMatches` | `PASS` — all targets recorded `stopMatches=5`, a `STOPPED` then `NO_ACTIVE_SESSION` stop, and a typed `page.find-session-stale` continuation after the stop. |
| A6 / lifecycle | Page close and deadlines terminate owned waits exactly once. | Close during a find, 5 s deadline, closed page. | Unit tests plus the probe's drained native counts. | `onPageClosed`, `withTimeoutOrNull`, `page.close` | Probe requires zero live browsers/engines | `PASS` — the probe closes four pages across two profiles and then requires zero live native browsers and engines on every target. |
| A7 / zoom semantics | Zoom is applied to Chromium and read back; the level is per profile and host; out-of-range input is refused. | Level 2.0 read back, factor 1.5 round-trip, same-host sharing, other host 0.0, other profile 0.0, reset 1.0, factor 9.0, level 100.0. | Real-CEF probe on all three targets plus common conversion tests. | `ApplyZoomLevel`, `ZoomOnUiThread`, `kWebValidatedZoomLevel` | Evidence zoom fields | `PASS` — every target recorded level 2.0 read back as factor 1.44, a shared 2.0 on the same-host page, 0.0 on another host and another profile, 0.0 after the factor reset, and typed refusals for factor 9.0 and level 100.0. |
| A8 / authority | Find and zoom are host-only; the renderer bridge exposes neither. | Bridge surface review; no renderer grant is required or granted. | Code review of the generated bridge surface and the page contract. | `KWebPageUtilities` not present in any generated schema | Review note in the merge record | `PASS` — the generated bridge schemas and preload outputs contain no find or zoom operation, and neither service grant is declared. |
| A9 / migration | The two Electron surfaces are classified separately and remain `DIRECT`. | Matrix row review, migration check, real migration fixture. | Migration unit tests and `electronMigrationIntegrationTest`. | `KWebElectronCapabilityMatrix` rows `web-contents-find`, `web-contents-zoom` | `compatibility.json` per target | `PASS` — both rows are backed by this RFC in the recorded matrix and each target retained its migration compatibility report. |
| A10 / providers | The declared CEF APIs are used without fallback on every target. | Missing handler, UI-thread failure, Windows/Linux execution. | Native ABI tests and three-target probe. | `browser_session.cc` find/zoom, `engine_abi.cc` | Provider evidence per target | `PASS` — the same pinned CEF 151 `CefFindHandler` and browser-host calls produced identical observations on macOS arm64, Windows x64, and Linux x64. |
| A11 / evidence | The recorder binds contract, runtime, artifact, and matrix facts for all three targets. | Missing artifact, stale digest, unsupported row claim. | Aggregation tests, hosted recording, strict governance. | `aggregate-rfc-evidence.sh`, `contracts.json`, `suites.json` | Manifest records for 0044 | `PASS` — run 39089585063 recorded three `READY` records with both matrix rows and both artifacts; the 71 aggregation tests pass. |
| A12 / docs and matrix | RFC, roadmap, matrix rows, and capability reporting agree. | Governance check, documentation workflow. | `:kweb-rfc-governance:check`, docs workflow. | This RFC, `README.md`, capability matrix | Governance report | `PASS` — the documentation workflow passed for this revision and the governance report resolves both rows to RFC 0044 once the evidence above is imported. |
| A13 / completion | One focused squash PR with a clean worktree and the reviewed revision. | Diff review, commit list, worktree state. | Final PR review. | This PR | Squash commit | `PASS` — the PR contains only the RFC 0044 objective; the worktree is clean and the reviewed revision is the PR head. |

## Contract review record

- Reviewed revision: the RFC 0044 contract commit on this branch.
- Review pass: same-contributor contract review performed against the pinned
  CEF 151 headers and the local real-CEF probe; this is not an independent
  person approval.
- Date: 2026-10-18.
- Findings and dispositions:
  - The original RFC 0028 objective bundled find, zoom, capture, save, source,
    diagnostics, and throttling facts. Those cannot be delivered as one
    publishable objective, so RFC 0028 records the split and this RFC owns only
    find and zoom.
  - Chromium's find result reporting is not a stable zero-based contract: it
    reports 0 before selecting a match and 1-based positions afterwards, clears
    the rectangle on the ordinal-0 reply, and suppresses an identical reply
    after a restart. The contract normalizes the ordinal, documents the
    rectangle, and restarts the search before a new scan.
  - Page zoom is not page-local in Chromium: it is stored per profile and host.
    The contract states that fact, the probe asserts same-host sharing and
    other-host/other-profile isolation, and restart persistence is left to
    RFC 0028 rather than implied.
- Decision: `READY`.

## Merge acceptance record

- Reviewed revision: the final commit of this PR.
- Review pass: same-contributor acceptance review of the complete PR diff
  against the matrix above.
- Date: 2026-10-18.
- Local verification: `:kweb-core:check`, `:kweb-desktop:check` (unit tests),
  `:kweb-cef-native:buildNative` + contract tests, and the real-CEF
  `page-utilities` probe passed on macos-arm64 (3/4/5 matches, ordinals,
  13 ordered updates, zoom 2.0 read back, host/profile isolation, typed
  refusals).
- Hosted verification: run [39089585063](https://github.com/kingsword09/KWebShell/actions/runs/39089585063) recorded three `READY` records for RFC 0044 (macos-arm64, windows-x64, linux-x64) with both matrix rows and both retained artifacts; the three verification jobs and the documentation workflow passed.
- Result: every row is `PASS`; the evidence is imported into this PR and the checked-in manifest/catalog are governed together.
