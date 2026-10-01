# RFC 0014: Typed system clipboard

- Status: Implemented
- Priority: P0
- Owners: `kweb-service-clipboard`, `kweb-services-core`, `kweb-bridge`, `kweb-electron-migration`
- Depends on: RFC 0003, RFC 0004
- Electron migration surface: `clipboard`, `ClipboardItem`
- Target mapping: `REWRITE`

## Objective

Publish one typed `kweb-service-clipboard` service for the desktop `SYSTEM`
clipboard. This objective delivers bounded plain text, HTML, RTF, and URI-list
items, serialized complete multi-format writes, lazy bounded reads, ordered change metadata,
and explicit ownership/lifecycle outcomes on Windows, macOS, and Linux.

Renderer access is an optional exact-origin bridge operation governed by RFC
0003. It is not an implicit web Clipboard API permission bypass. The common API
contains no platform clipboard object, native format name, OS handle, or
ambient data source.

This objective deliberately excludes Linux `PRIMARY`, arbitrary/custom native
formats, image payloads, and RFC 0027 image values. Those capabilities require
separate contracts and are not reported as supported by this RFC.

## Non-goals and applicability

- No Linux `PRIMARY` selection. Only `SYSTEM` is published; `PRIMARY` is a
  future platform-specific capability and never aliases `SYSTEM`.
- No arbitrary native format names. A future custom-format RFC must define an
  application allowlist, renderer exposure rules, and per-platform mappings.
- No image, bitmap, alpha, color-space, or native-image payload. Image support
  belongs to RFC 0027 and is not a dependency or a hidden partial feature here.
- No clipboard history, polling API, keylogging-like observation, or content
  retention after the owning service closes.
- No shell command, system WebView, X11-only fallback, or second IPC backend.
  A missing desktop clipboard facility fails with a typed platform error.
- No implicit HTML/RTF script, object, file, external-resource, or active-link
  execution. Unsafe payloads are rejected before a native write.
- No renderer access from child frames, cross-origin pages, or an unconfigured
  Page. Host calls do not bypass native availability, policy, or lifecycle.

## Implementation contract

Contract revision: `2026-09-30.3`.

### Common KMP and data contract

`KWebClipboard` is an `APPLICATION`-scoped service. The host installs exactly
one provider. The provider is explicit; environment discovery and replacement
with another backend are prohibited.

    public enum class KWebClipboardSelection { SYSTEM }

    public enum class KWebClipboardFormat {
        TEXT_PLAIN, TEXT_HTML, TEXT_RTF, URI_LIST,
    }

    public enum class KWebClipboardPayloadEncoding { UTF8, RTF_BYTES }

    public enum class KWebClipboardOwnership {
        OWNED, FOREIGN, EMPTY, UNAVAILABLE,
    }

    public data class KWebClipboardReadRequest(
        public val selection: KWebClipboardSelection,
        public val formats: List<KWebClipboardFormat>,
    )

    public data class KWebClipboardPayloadDescriptor(
        public val handle: KWebClipboardPayloadHandle,
        public val format: KWebClipboardFormat,
        public val encoding: KWebClipboardPayloadEncoding,
        public val sizeBytes: Long,
    )

    public data class KWebClipboardReadResult(
        public val sequence: Long,
        public val available: List<KWebClipboardPayloadDescriptor>,
    )

    public data class KWebClipboardReadChunk(
        public val bytes: ByteArray,
        public val eof: Boolean,
    )

    public data class KWebClipboardWriteItem(
        public val format: KWebClipboardFormat,
        public val encoding: KWebClipboardPayloadEncoding,
        public val bytes: ByteArray,
    )

    public data class KWebClipboardWriteRequest(
        public val selection: KWebClipboardSelection,
        public val items: List<KWebClipboardWriteItem>,
    )

    public data class KWebClipboardWriteResult(public val sequence: Long)

    public data class KWebClipboardChange(
        public val sequence: Long,
        public val selection: KWebClipboardSelection,
        public val formats: List<KWebClipboardFormat>,
        public val ownership: KWebClipboardOwnership,
    )

    public class KWebClipboardPayloadHandle internal constructor(
        public val token: String,
    )

    public interface KWebClipboard : KWebNativeService {
        public suspend fun read(request: KWebClipboardReadRequest): KWebClipboardReadResult
        public suspend fun readPayload(
            handle: KWebClipboardPayloadHandle,
            offset: Long,
            length: Int,
        ): KWebClipboardReadChunk
        public suspend fun write(request: KWebClipboardWriteRequest): KWebClipboardWriteResult
        public suspend fun clear(selection: KWebClipboardSelection): Long
        public fun changes(): Flow<KWebClipboardChange>
        public suspend fun closePayload(handle: KWebClipboardPayloadHandle)
    }

The service descriptor is `clipboard` version `1.0.0`, scope `APPLICATION`, and
publishes `read`, `read-payload`, `write`, `clear`, `changes`, and
`close-payload`. Every operation has schema version 1 and renderer permission
`native.clipboard.<operation>`; reads/writes/clear require a native-verified
gesture. Supported targets are all three desktop targets.

The following bounds and normalization rules are normative:

| Field | Constraint and result |
|---|---|
| selection | `SYSTEM` only; other selections fail `clipboard.selection-unsupported` |
| formats | One to four unique published formats; canonical enum order |
| item size | At most 4 MiB; one write at most 8 MiB; validate before calling the OS provider |
| read chunk | `offset >= 0`, length 0..256 KiB, no integer overflow |
| payload token | Random 256-bit URL-safe token, provider/sequence/format bound; maximum 16 live descriptors and 8 MiB aggregate snapshot bytes |
| sequence | Starts at 1 and strictly increases for accepted writes, clear, and ownership changes |
| text | UTF-8, no NUL/surrogates, CRLF/CR normalized to LF; empty plain text is valid |
| HTML | UTF-8 fragment; allowlisted structural/inline tags and safe attributes only |
| RTF | Bounded bytes with valid `\rtf` header; no object, picture, field, file, or external destination |
| URI list | LF-separated absolute `http`, `https`, or `file` URIs; no user-info, controls, comments, or fragments; 1,024 URI maximum |

`read` returns metadata and opaque lazy payload descriptors. It does not return
clipboard bytes. `readPayload` returns one bounded chunk and explicit EOF.
Descriptors are immutable provider-owned snapshots and expire when their
observed sequence changes, on explicit close, or on service shutdown. The
provider permits at most 16 live descriptors and 8 MiB aggregate snapshot
bytes. An unavailable requested format produces no descriptor and is not
itself an error. Invalid native data is rejected and never exposed.

`write` requires one plain-text item and permits at most one item of each other
format. All items and native buffers are validated/prepared before the provider
mutates the system clipboard. A successful result means the platform accepted
every declared format under one serialized ownership transaction. The Windows
clipboard API has no general rollback primitive; if a native failure occurs
after the old contents have been cleared, the provider returns
`clipboard.write-outcome-unknown`, emits the observed ownership/format state,
and never reports success or claims that the old clipboard was restored.
Failures before the native mutation leave the previous contents unchanged.
`clear` publishes an empty state or returns a typed error. No raw native format
string enters common code or renderer JSON.

### Lifecycle, concurrency and limits

Native transfer is delegated to the versioned native C ABI through the internal
JDK 25 FFM binding; Kotlin provider calls use bounded `Dispatchers.IO` workers
and never block CEF UI or renderer callback threads. Change delivery is ordered and bounded to 64
metadata events. It reports writes/clears made through this provider, observed
format-availability transitions, and loss of ownership after this provider's
own write. The native provider's sequence/change counter is used for ownership
transitions; foreign-to-foreign content changes are not claimed as observable
unless the platform reports a changed counter. `changes` is therefore a
metadata/availability stream, not a clipboard-history or every-write monitor.
Overflow emits one `clipboard.change-overflow` terminal error; events within
the stated observable contract are never dropped silently and queues never
grow without a limit.

| State | Trigger | Result and resource effect | IDs |
|---|---|---|---|
| open | valid read | register bounded payload descriptors | A1, A4 |
| open | valid write/clear | one sequence and one metadata change | A3, A5 |
| open | observable foreign flavor/ownership change | `FOREIGN`/`EMPTY`, expire prior-sequence handles, emit metadata change | A5, A6 |
| payload live | bounded read | chunk/EOF; retain only snapshot bytes | A1, A4 |
| any open state | close/navigation/renderer termination | CLOSING then CLOSED; close native owner, handles, and flows once | A6, A8 |
| change flow | slow consumer | terminal overflow and subscription release | A5, A6 |
| any state | platform loss/native error | typed failure; no fallback; post-mutation failures report outcome unknown | A3, A7 |

Close wins after an operation reaches the native boundary. A committed write
still reports its sequence/change before close completes. Operations admitted
after `CLOSING` return `service.owner-closed` and produce no change.

### Errors, renderer and migration policy

Stable service errors are `clipboard.selection-unsupported`,
`clipboard.format-unsupported`, `clipboard.payload-invalid`,
`clipboard.payload-too-large`, `clipboard.payload-not-found`,
`clipboard.payload-expired`, `clipboard.read-unavailable`,
`clipboard.write-unavailable`, `clipboard.write-outcome-unknown`, `clipboard.ownership-lost`,
`clipboard.native-unavailable`, `clipboard.platform-unavailable`,
`clipboard.html-policy`, `clipboard.rtf-policy`, and
`clipboard.uri-policy`, plus RFC 0003 policy and lifecycle errors.

Error details include only service, operation, platform, and bounded native
status. Clipboard bytes, paths, window handles, secrets, and native format
names are never logged or returned.

Renderer operations require an exact committed origin, main frame, live Page
owner, host grant, and one native-verified gesture. `changes` contains only
sequence, selection, format IDs, and ownership. Navigation, origin change,
renderer termination, policy revocation, or owner close terminates subscriptions
and expires payload handles.

The migration fixture pins Electron major 44. `clipboard.readText`,
`clipboard.writeText`, declared text/HTML/RTF/URI `ClipboardItem` workflows,
and policy-compatible `clipboard.clear` rewrite to named typed operations.
Image, custom, synchronous, direct renderer, native-format, and arbitrary
polling use remain migration blockers. The generated preload exports no Electron
object, `ipcRenderer`, generic channel, or native format name.

### Platform implementation and feasibility

The desktop provider uses one versioned C ABI behind an internal JDK 25 FFM
binding. Kotlin never sees a native clipboard object or platform format name.
The ABI exposes only bounded byte buffers, fixed format enum values, a snapshot
record, serialized write/clear operations, a poll operation, and close. The
native implementation owns platform data objects and uses the real Win32,
AppKit, or GTK desktop clipboard on its target. FFM layouts, ownership,
status mapping, and thread/lifecycle rules remain internal to the JVM provider.

| Target | Exact provider/API and constraints | Readiness probe and required result |
|---|---|---|
| Windows x64 | Win32 `OpenClipboard`/`GetClipboardData`/`EmptyClipboard`/`SetClipboardData`, fixed `CF_UNICODETEXT`, registered CF_HTML/RTF/URI-list formats, and `GetClipboardSequenceNumber`. Clipboard access is serialized by the provider lock. | Native ABI and hosted independent Win32 process prove fixed-format round-trip, sequence/ownership loss, Unicode, clear, and process exit. |
| macOS arm64 | AppKit `NSPasteboard.generalPasteboard`, fixed string/HTML/RTF/URL type mappings, `changeCount`, and bounded NSData/NSString conversion. Calls are serialized in the provider and wrapped in autorelease pools. | Objective-C++ ABI and hosted independent AppKit process prove fixed-format round-trip, change-count ownership loss, Unicode, clear, and process exit. |
| Linux x64 | GTK 3 `GtkClipboard`/`GtkSelectionData` on the active GDK desktop backend, `CLIPBOARD` only, fixed target atoms, and GLib context pumping for owner callbacks. `PRIMARY` is never queried or written. | GTK ABI and hosted Xvfb/X11 independent process prove fixed-format round-trip, target availability, ownership transitions, Unicode, clear, and process exit. |

Missing display, required native format, owner callback, or change observation
is a typed failure. Native providers do not promise rollback after the OS has
accepted a write; post-mutation errors use `clipboard.write-outcome-unknown`.
No alternate toolkit, shell command, or clipboard backend is selected.

### Evidence lifecycle and delivery

Evidence binds this RFC, common contract, native ABI/header and platform
sources, FFM layouts, generated bridge schema/output, migration fixture,
format-policy corpus, provider identity, and package scan. Records retain only
format IDs, sizes/counts, sequence/ownership transitions, fixture hashes,
provider identity, and native status; no clipboard values, handles, paths, or
secrets are retained.

Any common signature, policy, ABI, mapping, generated output, migration
fixture, provider/runtime identity, or package-layout change invalidates the
affected target record. The required sequence is common/provider tests, native
ABI tests, real inter-process fixtures, exact-origin CEF/migration tests,
evidence aggregation, strict checked-in governance, and final row-by-row review.
The same focused PR must contain implementation, tests, migration, docs,
evidence import, and the final `Implemented` state.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative, boundary scenarios | Planned verification / targets | Implementation/evidence | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|
| A1 / common contract | Four typed formats, SYSTEM selection, bounded models, canonical encodings, no platform handles/names. | Empty/max values, duplicate/invalid formats, 4 MiB/8 MiB limits, invalid offsets/tokens. | Common/provider tests and generated schema tests. | `KWebClipboard.kt`, `KWebClipboardContractTest`, `JvmKWebClipboard`, `clipboard-bridge.json`. | Three `READY` RFC 0014 manifest records and `clipboard-evidence.json` bind contract revision `2026-09-30.3`; all targets report the four format IDs, bounded round-trip sizes, empty text, and no retained paths. | `PASS` — common models, bounds, normalization, and generated schema passed locally and on all hosted targets. |
| A2 / policy boundary | Renderer access is exact-origin, main-frame, grant, gesture, owner, and revocation checked. | Missing grant, synthetic/replayed gesture, child frame, cross-origin commit, navigation, host call. | RFC 0003 tests and real CEF fixture on all three targets. | `ClipboardPolicy.kt`, `KWebClipboardBridge.kt`, `ClipboardIntegrationMain`, RFC 0003 policy fixtures. | All three `migration-clipboard-evidence.json` records report exact-origin main-frame access, absent child/cross-origin transport, native-gesture denial, permission denial, and owner close. | `PASS` — renderer authority and lifecycle boundaries are verified on Linux, macOS, and Windows. |
| A3 / serialized write | Success means all declared formats were accepted under one serialized owner transaction; pre-mutation failure preserves old state, post-mutation failure is typed outcome-unknown and never fake success. | Text-only/all formats, invalid secondary format, clipboard busy before mutation, injected failure after clear, concurrent writer, process exit. | Native two-process fixture and fault-injection ABI tests on all three targets. | `JvmKWebClipboard`, `ClipboardFfm`, native ABI transaction path, native/provider tests, observed-state checks. | Hosted clipboard records retain all four format round-trips, ownership transition and clear result for each declared provider; local failure-path tests cover pre-mutation and outcome-unknown mapping. | `PASS` — serialized write semantics and typed failure behavior passed without false-success or fallback behavior. |
| A4 / format policy | Plain text/HTML/RTF/URI validation rejects unsafe/oversized data before native mutation and accepted data round-trips. | Unsafe markup/RTF, URI credentials/control/unsupported scheme, Unicode, empty values. | Common corpus and native fixture on all three targets. | `KWebClipboard`, `ClipboardPolicyTest`, native format validators, four-format integration fixture. | Each hosted clipboard record reports the four fixed format IDs, deterministic bounded sizes/hashes, Unicode/empty-text coverage, and successful round-trip. | `PASS` — format policy, canonical encoding, Unicode, empty text, and bounded round-trip behavior passed on all targets. |
| A5 / ownership/events | Writes, clear, foreign ownership, and empty transitions produce ordered bounded metadata. | Concurrent reader/writer, ownership loss, process exit, 64/65 event boundary, duplicate notifications. | Native watcher, Flow/backpressure, two-process tests on all targets. | `JvmKWebClipboard` change queue, native poll/sequence path, ownership and bounded-flow tests. | Hosted clipboard records report `ownershipTransition: foreign`, `emptyAfterClear: true`, four independent readers, and expired prior payloads; local change-stream tests cover ordering and overflow. | `PASS` — ownership, clear, change metadata, and bounded delivery passed for all three providers. |
| A6 / lazy/lifecycle | Chunks are bounded; stale handles fail; close/navigation/termination release every resource once. | 0/256 KiB, EOF, stale sequence, close race, renderer crash. | JVM lifecycle and real CEF fixture on all targets. | `JvmKWebClipboard` payload registry, `readPayload`/`closePayload`, lifecycle tests, CEF migration fixture. | Clipboard evidence reports payload expiry after a write; migration evidence reports lazy payload read/close and owner close on every target. | `PASS` — bounded lazy payloads, stale-handle invalidation, and owner shutdown cleanup passed on all targets. |
| A7 / platform providers | The internal FFM binding maps the four fixed formats to the declared Win32/AppKit/GTK provider, reports missing facilities, and never selects a different toolkit/backend. | Native ABI on each OS, foreign owner, unsupported format, no display, provider loss. | Native ABI plus JDK 25 FFM runtime fixtures on Windows, macOS, and Linux. | `ClipboardFfm.java`, `native/include/kweb_clipboard.h`, `clipboard_win.cc`, `clipboard_mac.mm`, `clipboard_linux.cc`. | Hosted provider identities are `windows.Win32.clipboard`, `macos.AppKit.NSPasteboard`, and `linux.GTK.CLIPBOARD`; all report runtime `JDK-25` and contract revision `2026-09-30.3`. | `PASS` — the declared native provider is selected on every advertised target and no fallback backend is used. |
| A8 / inter-process evidence | Independent processes round-trip advertised formats, ownership loss, Unicode/empty values, readers, and exit cleanup. | Missing target/facility, concurrent readers, application exit. | Hosted macOS arm64, Windows x64, Linux x64 fixtures. | `ClipboardIntegrationMain`, native clipboard test process, `clipboard-evidence.json`, aggregate manifest. | The three `READY` manifest records retain provider identity, format sizes/hashes, four independent readers, foreign ownership, clear-to-empty, payload expiry, and `absolutePathsRetained:false`. | `PASS` — fresh hosted independent-process evidence covers all advertised targets. |
| A9 / migration | Generated bridge has named async operations only; declared Electron workflows rewrite and undeclared/direct use blocks. | Main/child/cross-origin, malformed payload, abort/close, image/custom/sync/direct use. | TS/JS generation, migration golden fixture, CEF fixture. | `clipboard-bridge.json`, generated preload bindings, `KWebElectronCapabilityMatrix`, migration inventory and CEF fixture. | Hosted migration records and compatibility reports are `READY` for Electron 44/stock CEF 151; all targets report named clipboard operations, exact-origin policy, lazy payload close, and no direct/unconfigured transport. | `PASS` — the declared read/write/clear/readPayload/closePayload rewrite is verified and unsupported paths remain blocked. |
| A10 / packaging/docs | Catalog, capability matrix, docs, generated/native packages and exclusions agree. | Stale schema/version, test roots packaged, PRIMARY/custom/image accidentally promoted. | Governance, package scan, docs and full diff review. | `KWebRfcServiceCatalog`, service README, migration matrix, RFC, native/package metadata, checked-in evidence governance. | Documentation/governance and checked-in-evidence jobs passed; the imported manifest binds the final hosted contract/runtime records and retains the explicit exclusion set. | `PASS` — catalog, schema, package layout, documentation, and exclusions are aligned. |
| A11 / universal completion | Complete implementation, tests, evidence, reviewed revision and clean worktree land in one squash PR. | Skipped target/test, stale evidence, dirty worktree, partial API. | Local gates, full PR diff, all required hosted jobs. | Focused PR #66, complete implementation/test/evidence diff, local validation, final row-by-row review. | PR #66 is CLEAN with successful macOS arm64, Windows x64, Linux x64, hosted evidence, documentation/governance, and checked-in-evidence checks; fresh artifacts are imported on the reviewed branch. | `PASS` — RFC 0014 is complete for the declared SYSTEM clipboard scope; squash merge is permitted after final diff review. |
| A12 / exclusions | PRIMARY, arbitrary custom formats, and image/RFC 0027 behavior remain absent and unsupported. | Unsupported selection, custom/native request, image migration fixture. | Code/catalog/matrix review and negative migration tests. | Common enum, schema, capability matrix, migration blockers, and review. | Contract/code review and hosted compatibility records retain the exclusion set; no unsupported capability is advertised. | `PASS` — explicitly excluded from this objective. |

## Contract review record

- Reviewed revision: `2026-09-30.3` on branch
  `rfc/0014-typed-clipboard`, before implementation code.
- Review pass: Codex readiness review, 2026-09-30. The same contributor may
  implement the objective; this is a separate contract review pass, not an
  independent-person approval.
- Findings and dispositions:
  - RFC 0027 is still Proposed, so image support was removed from this
    objective and is explicitly covered by A12.
  - Linux PRIMARY and arbitrary native formats need separate selection,
    allowlist, permission, and evidence contracts and are not aliased to
    SYSTEM.
  - A strict all-or-nothing promise is not available from Win32 clipboard APIs.
    The contract now distinguishes pre-mutation failure from the typed
    `clipboard.write-outcome-unknown` after an irreversible mutation point;
    implementation must never report false success.
  - JDK `FlavorListener` cannot promise notification for foreign-to-foreign
    replacement with an unchanged flavor set. The stream contract is restricted
    to provider writes, observed availability transitions, and loss of our own
    clipboard ownership; it is not a global write monitor.
  - The four fixed formats now have bounded encodings, policy, native mappings,
    lifecycle rules, and falsifiable tests.
  - The JDK AWT provider was probed on macOS and could not reliably complete
    an external ownership handoff with a custom multi-format Transferable.
    It is therefore not a valid implementation boundary for A8.
  - The contract now uses the project's permitted small C ABI and internal JDK
    25 FFM binding. Native objects stay behind the ABI; platform providers are
    explicit and a missing facility fails typed.
- Probe references: native SDK/GTK compile gates and hosted independent-process
  fixtures are required before support is published. The failed AWT probe is
  retained as a readiness finding that caused this contract revision, not as
  support evidence.
- Decision: **READY**. The objective is bounded, one focused deliverable, and
  every advertised guarantee maps to a falsifiable acceptance row.

## Merge acceptance record

Final acceptance review: 2026-10-01, Codex acceptance audit (same contributor,
separate review pass). Reviewed contract revision `2026-09-30.3`, the three
`READY` RFC 0014 records from hosted run `36812725294` (attempt 2, source
revision `264125cebc957b522ecb0a241ab19e5db1701b2a`), imported target
artifacts, and the complete PR #66 diff including the native Win32/AppKit/GTK
providers, JDK 25 FFM binding, Kotlin service and bridge, Electron migration
fixture, documentation, acceptance matrix, and retained evidence. Local
clipboard, migration, governance, JSON, and diff checks passed. CI passed the
macOS arm64, Windows x64, Linux x64, hosted evidence recording, documentation
and checked-in-evidence verification jobs. Row-by-row decision: A1–A12
**PASS**. Decision: **READY / ACCEPTED**; RFC 0014 remains `Implemented` for
the declared SYSTEM text/plain, HTML, RTF, and URI-list scope, with Linux
PRIMARY, images, arbitrary custom formats, and direct renderer access excluded.
