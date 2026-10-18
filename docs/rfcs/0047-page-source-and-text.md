# RFC 0047: Page source and rendered text export

- Status: Implemented
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium frame adapter
- Depends on: RFC 0004, RFC 0008
- Electron migration surface: `webContents.savePage` (`HTMLOnly`), renderer `webFrame.executeJavaScript` used for `document.documentElement.outerHTML` and `document.body.innerText`
- Target mapping: `REWRITE`

## Objective and scope

Publish one page-scoped content read backed by Chromium's own frame, on macOS
arm64, Windows x64, and Linux x64:

1. two declared document views — the page's serialized HTML `SOURCE` and its
display `TEXT` — read from the main frame of the page the application owns;
2. an explicit transfer bound: the caller declares how many bytes it accepts, the
engine delivers that prefix, and a shortened document reports both `truncated`
and the full size Chromium produced, so a caller can never mistake a prefix for
the whole document;
3. UTF-8 correctness at the cut: a bound that lands inside a multi-byte character
is backed off to the character boundary instead of splitting the sequence;
4. a host-only read that executes no script, returns no DOM objects, and never
reaches a subframe or another document.

This RFC is one objective carved out of [RFC 0028](0028-page-utilities.md).
RFC 0028 remains `Proposed` for viewport capture, save/export, DOM diagnostics,
visibility/throttling facts, and durable zoom persistence; the split record there
names this objective. Nothing from those areas is published here.

## Non-goals and applicability

- No viewport or page capture, no save/export, no MHTML, no image encoding, and
  no artifact written to disk: the document is returned as text and discarded
  when the caller releases it.
- No DOM object transport and no renderer code execution. `SOURCE` and `TEXT` are
  Chromium's own document views, not the result of an injected script, so an
  application cannot run arbitrary JavaScript through this contract.
- No subframe read and no cross-document read: only the main frame of the page the
  application owns is readable. A cross-origin subframe's content and text stay
  outside both views.
- No streaming and no partial re-read: Chromium serializes the whole document
  before the engine can observe it, so the declared bound bounds the transfer and
  the application's copy, not Chromium's allocation. The contract states that
  limit instead of implying a streaming API.
- No fallback: a frame that cannot be read fails with a typed error; no second
  renderer, no CDP shortcut, and no local reconstruction of the document.

Universal completion areas map as follows: common contract and bounds A1; source
fidelity A2; text fidelity A3; bounded transfer and truncation A4; empty and
closed documents A5; authority and isolation A6; lifecycle and concurrency A7;
errors A8; migration A9; platform providers A10; evidence A11; documentation and
packaging A12; completion A13.

## Implementation contract

### Common KMP contract

`kweb-core` publishes `KWebPageContentContract.kt` and adds one operation to
`KWebPageUtilities`:

```kotlin
public suspend fun content(request: KWebPageContentRequest): KWebPageContent
```

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `KWebPageContentRequest.kind` | `SOURCE` or `TEXT` | defaults to `SOURCE` | which document view is read | A1, A2, A3 |
| `KWebPageContentRequest.maximumBytes` | 1 .. 1 MiB | defaults to 256 KiB | the largest delivered prefix | A1, A4 |
| `KWebPageContent.kind` | the requested `SOURCE` or `TEXT` | required | the delivered view; a mismatch is refused | A1, A3 |
| `KWebPageContent.url` | the frame URL Chromium reported with the document | required, may be empty when the frame reports none | the document the view describes | A2, A6 |
| `KWebPageContent.text` | UTF-8 text, at most `maximumBytes`, cut at a character boundary | required; an empty document is a complete empty read | the document prefix | A1, A4, A5 |
| `KWebPageContent.totalBytes` | `>=` the delivered UTF-8 size, `>= 0` | required | the size of the whole document Chromium produced | A4 |
| `KWebPageContent.truncated` | `delivered < totalBytes` | required; never defaulted | whether the document was shortened | A4 |

The contract refuses a value that contradicts itself: a delivered size greater
than `totalBytes`, a `truncated` flag that disagrees with the two sizes, a
document above the 1 MiB transfer ceiling, and lone surrogates (a replacement
encoding would misreport the delivered size).

### Engine ABI

| ABI item | Shape | Behavior | Acceptance IDs |
|---|---|---|---|
| `kweb_browser_page_content` | `(browser, request_id, kind, maximum_bytes)` | validates the request id, the kind, and the bound, then asks Chromium for that main-frame view from the browser-process UI thread | A2, A3, A6, A8 |
| `KWEB_BROWSER_EVENT_PAGE_CONTENT` (31) | `request_id`; `text` = `{version,kind,totalBytes,truncated,url}`; `details` = the delivered bytes unchanged | one terminal observation per read; the document is carried in `details` so no JSON escaping re-encodes it | A2, A3, A4 |
| `KWEB_PAGE_CONTENT_SOURCE` / `KWEB_PAGE_CONTENT_TEXT` | `1` / `2` | identifies the requested document view | A1, A3 |
| `KWEB_PAGE_CONTENT_MAXIMUM_BYTES` | 1 MiB | the ceiling one event can carry; a larger request is refused | A1, A4 |
| `KWEB_STATUS_PAGE_CONTENT_INVALID` (99) | request refused | `page.content-request-invalid` | A8 |

The ABI version moves from 21 to 22; the export table gains one symbol (37 total).

### Lifecycle, concurrency, and limits

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| Page open | `content` | request valid | one read in flight; suspend returns the document when its observation arrives | one bounded copy per read | A2, A4 |
| Read in flight | a second `content` | page open | serialized per page by one mutex; both reads complete in order | one bounded copy each | A7 |
| Read in flight | document navigation commits | UI thread | the observation describes whatever Chromium serialized for that request; the contract reports the frame URL with it and claims no document identity | none | A2 |
| Read in flight | page or engine closes | any | waiter fails `page.closed`; a late observation for an ended request finds no waiter and is dropped | waiters cleared | A5, A7 |
| Read in flight | 10 s elapse | IO | `page.content-timeout`; the request is removed from the page's waits | waiter dropped | A7 |
| Read in flight | renderer never answers | any | same deadline; no synthetic document is produced | waiter dropped | A7 |
| Page closed | `content` | any | `page.closed` before a native read starts | none | A5 |

### Errors, renderer, and migration policy

| Condition | Stable error / outcome | Acceptance IDs |
|---|---|---|
| Bound outside 1 .. 1 MiB | `page.content-request-invalid` before native dispatch | A1, A8 |
| Malformed or self-contradicting engine payload | `page.content-contract-invalid` | A8 |
| No terminal observation within 10 s | `page.content-timeout` | A7 |
| Page or engine already closed | `page.closed` | A5 |
| Any other native status | `native.abi.<status>` with the native status id | A8 |

The read is host-only: it is not exposed on the generated renderer bridge,
requires no renderer grant, and grants the renderer nothing. The migration matrix
classifies `webContents.savePage(HTMLOnly)` and the `executeJavaScript`
HTML-source extraction as `web-contents-source` (`REWRITE`) and
`executeJavaScript('document.body.innerText')` as `web-contents-text`
(`REWRITE`), because the typed read replaces both the shell-generated script
string and the caller-chosen destination. HTMLComplete/MHTML save, file writing,
and arbitrary `executeJavaScript` remain blocked.

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result | Acceptance IDs |
|---|---|---|---|---|
| macOS arm64 | `CefFrame::GetSource` / `CefFrame::GetText` with a `CefStringVisitor` from the pinned CEF 151 distribution | the read is dispatched on the browser-process UI thread and the visitor is invoked there; the main frame is the only readable document | local `page-content` probe on CEF 151.3.16: 138-byte source starting with its doctype, 32-byte display text matching the renderer's `innerText`, a 64-byte bound over a 137-byte document, a 44-byte bound backed off to the 43-byte character boundary | A2, A3, A4, A10 |
| Windows x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A3, A10 |
| Linux x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A3, A10 |

Feasibility facts established by reading the pinned CEF headers and by the real
probe:

- `CefFrame::GetSource` and `CefFrame::GetText` take a `CefStringVisitor` and
  deliver one complete `CefString`; there is no streaming or chunked variant, so
  the bound applies to what crosses the ABI.
- The visitor runs on the browser-process UI thread, so the engine applies the
  bound, the character-boundary backoff, and the event publication on that thread
  without a second hop.
- Both views read the live document: a `textContent` mutation performed through
  CDP before the read is present in the delivered source and text, and the text
  read matches the renderer's own `document.body.innerText`.
- A cross-origin subframe's document does not leak into either view: the source
  carries the `<iframe>` element and none of the subframe's content.
- An empty document is a complete empty read (`totalBytes == 0`, not truncated),
  not a failure.

### Evidence lifecycle and delivery

Bound contract paths: the `kweb-core` contract and tests, the desktop page
utilities, native bindings and the integration probe, the CEF ABI and session
implementation, the migration matrix, the aggregate script, the CI family
registration, and this RFC. The recorded artifact is
`engine-integration/page-content-evidence.json` from the three-target verification
jobs plus the migration compatibility report.

Any change to the request bounds, the delivered fields, the truncation rule, the
native ABI, the error mapping, or the evidence producer invalidates the affected
target records. The sequence is: common and desktop unit tests, native ABI tests,
the real-CEF probe on every target, migration tests, evidence aggregation, strict
governance, and the final row-by-row review.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common contract | The request and result publish the declared fields, bounds, and self-consistency rules. | Bound 0, negative, above 1 MiB; delivered above `totalBytes`; `truncated` disagreeing with the sizes; lone surrogate; multi-byte measurement. | Common contract tests on every target. | `KWebPageContentContract.kt`, `KWebPageContentContractTest.kt` | Contract digest in each record | `PASS` — seven contract tests cover the bounds, the three self-consistency failures, the transfer ceiling, lone surrogates, and UTF-8 measurement; all three targets bind the same contract digest. |
| A2 / source fidelity | `SOURCE` is the document's serialized HTML of the live document. | Doctype, markup, a DOM mutation performed through CDP before the read, and the URL reported with it. | Real-CEF `page-content` probe on all three targets. | `runPageContentIntegration`, `browser_session.cc` `PageContent`/`PageContentReady` | `page-content-evidence.json` per target | `PASS` — the probe observes a 138-byte source that starts with `<!DOCTYPE html>`, carries its markup, contains the mutation made through CDP, no longer contains the replaced text, and reports the fixture URL. |
| A3 / text fidelity | `TEXT` is Chromium's display text for the same document. | Markup absent, mutation observed, comparison with the renderer's own `innerText`. | Real-CEF probe on all three targets. | `runPageContentIntegration`, `KWebPageContentKind.TEXT` | `textBytes`, `textMatchesInnerText` | `PASS` — the probe observes 32 bytes of display text without markup, containing the mutation, equal to the renderer's `document.body.innerText` after whitespace normalization. |
| A4 / bounded transfer | A declared bound shortens the document and reports the full size; the cut never splits a UTF-8 character. | 64-byte bound over a 137-byte document; a bound that lands inside a multi-byte character; prefix equality; `maximumBytes` above the ceiling. | Real-CEF probe on all three targets plus contract tests. | `PageContentReady` truncation and backoff, `KWebPageContentRequest.MAXIMUM_BYTES` | `truncatedBytes`, `truncatedTotalBytes`, `unicodeDeliveredBytes` | `PASS` — the probe delivers 64 of 137 bytes with `truncated=true` and a true prefix, and a 44-byte bound over a 73-byte document is backed off to the 43-byte character boundary instead of emitting a broken sequence. |
| A5 / empty and closed documents | An empty document is a complete empty read; a closed page refuses the read. | Empty body, read after `page.close()`. | Real-CEF probe on all three targets. | `runPageContentIntegration`, `KWebDesktopPageUtilities.onPageClosed` | `emptyTextBytes`, `emptyTruncated`, `closedPageRefused` | `PASS` — the probe observes a 0-byte, non-truncated text read for an empty document and a typed `native.browser.closed` refusal after the page closed. |
| A6 / authority and isolation | Only the application's own main frame is readable, only as text, with no renderer surface and no disk artifact. | Cross-origin subframe read, bridge surface review, profile artifact review. | Real-CEF probe on all three targets plus code review of the generated bridge and the session. | `browser_session.cc` `PageContent`, generated bridge schemas | `subframeSourceLeaked`, `subframeTextLeaked` | `PASS` — the probe's cross-origin subframe marker appears in neither view, and the review confirms the descriptor adds no renderer operation, executes no script, and writes no file. |
| A7 / lifecycle and concurrency | Reads are serialized per page, both complete, and an ended request is discarded. | Two concurrent reads of different views; the 10 s deadline; a late observation for an ended request. | Real-CEF probe plus desktop unit tests. | `contentMutex`, `KWebContentWaiters`, `KWebPageContentErrorCode.TIMEOUT` | `concurrentReads`, `KWebDesktopPageUtilitiesTest` | `PASS` — both concurrent reads return their own document view (`concurrentReads=2`), and the unit test fails two owned waits together on a terminal page state while a request that already ended finds no waiter. |
| A8 / errors | Every refusal is typed before a document is produced. | Bound outside the declared range, unknown kind, zero request id, malformed and self-contradicting payloads, closed page, native status mapping. | Native ABI contract tests, contract tests, desktop unit tests. | `kweb_browser_page_content` guards, `PageContentSession`, `KWebDesktopPageContentJson`, `pageUtilityStatusException` | Retained test artifacts | `PASS` — the native ABI test refuses a zero request id, an unknown kind, a zero bound, and a bound above the ceiling before browser lookup, and the desktop test rejects six malformed or self-contradicting payloads. |
| A9 / migration | Both Electron surfaces are classified, remain `REWRITE`, and the migration check passes. | Matrix row review, migration unit test, real migration fixture. | Migration tests and `electronMigrationIntegrationTest`. | `KWebElectronCapabilityMatrix` rows `web-contents-source`, `web-contents-text` | `compatibility.json` per target | `PASS` — both rows are backed by this RFC, the matrix test pins their `REWRITE` status and rewrite targets, and each target retained its migration compatibility report. |
| A10 / providers | The declared CEF APIs are used without fallback on every target. | Missing main frame, UI-thread dispatch, Windows/Linux execution. | Native ABI tests and the three-target probe. | `browser_session.cc` `GetSource`/`GetText`, `engine_abi.cc` | Provider evidence per target | `PASS` — the same pinned CEF 151 `CefFrame::GetSource`/`GetText` path produced identical observations on macOS arm64, Windows x64, and Linux x64. |
| A11 / evidence | The recorder binds contract, runtime, artifact, and matrix facts for all three targets. | Missing artifact, stale digest, unsupported row claim. | Aggregation tests, hosted recording, strict governance. | `aggregate-rfc-evidence.sh`, `contracts.json`, `suites.json` | Manifest records for 0047 | `PASS` — the three-target run recorded `READY` records with the probe artifact, the migration report, and both matrix rows. |
| A12 / docs and matrix | RFC, split record, roadmap, matrix rows, and capability reporting agree. | Governance check, documentation workflow. | `:kweb-rfc-governance:check`, docs workflow. | This RFC, `0028-page-utilities.md`, `README.md` | Governance report | `PASS` — the documentation workflow passed for this revision and the governance report resolves both rows to RFC 0047. |
| A13 / completion | One focused squash PR with a clean worktree and the reviewed revision. | Diff review, commit list, worktree state. | Final PR review. | This PR | Squash commit | `PASS` — the PR contains only the RFC 0047 objective; the worktree is clean and the reviewed revision is the PR head. |

## Contract review record

- Reviewed revision: the RFC 0047 contract commit on this branch.
- Review pass: same-contributor contract review against the pinned CEF 151
  headers and the local real-CEF probe; this is not an independent-person
  approval.
- Date: 2026-10-18.
- Findings and dispositions:
  - RFC 0028 bundled capture, save/export, source and DOM diagnostics,
    visibility facts, and zoom persistence. Capture and save/export have no CEF
    API in the Alloy window path, and DOM object transport would require a
    renderer-object contract, so the objective is split: this RFC publishes the
    two document views that Chromium genuinely exposes.
  - `CefStringVisitor` delivers one complete document, so no API can bound
    Chromium's own allocation. The contract states that the declared bound
    applies to the transfer and the application's copy instead of implying a
    streaming reader.
  - Chromium's frame read is not bound to a navigation: a navigation that
    commits while a read is in flight may produce the newer document. The
    contract reports the frame URL with the delivered bytes and claims no
    document identity.
  - A raw byte cut can split a UTF-8 sequence, which the FFM decoder refuses.
    The engine backs the cut off to the character boundary and reports the full
    size, which the probe verifies with a bound that lands inside a multi-byte
    character.
- Decision: `READY`.

## Merge acceptance record

- Reviewed revision: the final commit of this PR.
- Review pass: same-contributor acceptance review of the complete PR diff
  against the matrix above.
- Date: 2026-10-18.
- Local verification: `:kweb-core:check`, `:kweb-desktop:test`,
  `:kweb-electron-migration:check`, `:kweb-cef-native:check` (13 native
  binaries), and the real-CEF `page-content` probe passed on macos-arm64 with the
  observations recorded in the rows above.
- Hosted verification: the three-target verification jobs recorded `READY`
  records for RFC 0047; the exact run and revision are recorded in the imported
  manifest and in this PR.
- Result: every row is `PASS`; the evidence is imported into this PR and the
  checked-in manifest and catalog are governed together.
