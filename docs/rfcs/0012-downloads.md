# RFC 0012: Profile downloads and scoped results

- Status: Accepted
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, Chromium download adapter
- Depends on: RFC 0003, RFC 0004, RFC 0009
- Electron migration surface: `will-download`, `DownloadItem`
- Target mapping: `REWRITE`
- Platform targets: macOS, Windows, Linux

## Objective

Publish Chromium-owned downloads as Profile-scoped typed objects with explicit
host download policy, monotonic progress, pause/resume/cancel controls,
integrity facts, and a non-serializable completed-file capability. A renderer
never receives an ambient host path or a Chromium download object.

This objective uses stock CEF 151. The CEF `OnBeforeDownload` callback has no
asynchronous cancel operation: it can continue with a path or return to Alloy
default handling, which cancels the download. Therefore destination policy is
declared when the desktop Engine is created. A future dialog integration may
select a policy before navigation, but this RFC does not invent a blocking or
fake asynchronous Save As operation.

RFC 0013 is not an implementation dependency. This RFC publishes the smaller
Profile-owned completed-file capability needed by downloads; RFC 0013 may later
generalize it without exposing the download's absolute path.

## Non-goals and applicability

- No Kotlin download engine, second HTTP stack, browser-backend fallback, or
  system WebView substitution.
- No automatic execution, opening, upload, or renderer path API.
- No unrestricted `setSavePath`, arbitrary destination supplied by a renderer,
  or implicit default download directory.
- No retained partial-file capability. Canceled, interrupted, rejected, and
  integrity-failed staging files are deleted before the terminal state is
  published.
- Native dialogs are not required for this vertical slice. The desktop host
  supplies a trusted Kotlin/JVM `Path` in `KWebDesktopDownloadPolicy`; the
  path is never placed in a renderer bridge or public download state.
- The common contract, desktop provider, native CEF adapter, migration matrix,
  tests, hosted evidence, packaging, and documentation are applicable. The
  renderer adapter is a typed migration rewrite, not a direct Electron object.

## Implementation contract

Contract revision: `2026-09-29.1`.

### Common KMP and data contract

`KWebProfile.downloads` is a cold view over a bounded Profile stream. Each
emitted object is one Chromium download and retains its immutable snapshots in
`state`. The object is not serializable and is not accepted by any renderer
bridge.

```kotlin
public enum class KWebDownloadStatus {
    STARTING, IN_PROGRESS, PAUSED, COMPLETE, CANCELED, INTERRUPTED, DENIED,
}

public enum class KWebDownloadCollisionPolicy {
    FAIL, RENAME_UNIQUE, REPLACE_EXISTING,
}

public enum class KWebDownloadInterruptReason {
    NONE, FILE, NETWORK, SERVER, USER_CANCELED, OWNER_CLOSED,
    DESTINATION_INVALID, FILE_EXISTS, INTEGRITY_MISMATCH, UNKNOWN,
}

public data class KWebDownloadState(
    public val id: Long,
    public val profileId: String,
    public val pageId: String?,
    public val originalUrl: String,
    public val url: String,
    public val suggestedFileName: String,
    public val fileName: String?,
    public val contentDisposition: String?,
    public val mimeType: String?,
    public val receivedBytes: Long,
    public val totalBytes: Long?,
    public val currentSpeedBytesPerSecond: Long,
    public val status: KWebDownloadStatus,
    public val interruptReason: KWebDownloadInterruptReason,
    public val sha256: String?,
    public val file: KWebDownloadFile?,
)

public interface KWebDownloadFile : AutoCloseable {
    public val name: String
    public val sizeBytes: Long
    public val sha256: String?
    public suspend fun read(offset: Long, length: Int): KWebDownloadReadResult
    override fun close()
}

public data class KWebDownloadReadResult(
    public val bytes: ByteArray,
    public val eof: Boolean,
)

public interface KWebDownload : AutoCloseable {
    public val id: Long
    public val profileId: String
    public val pageId: String?
    public val state: kotlinx.coroutines.flow.StateFlow<KWebDownloadState>
    public suspend fun pause(): KWebDownloadControlResult
    public suspend fun resume(): KWebDownloadControlResult
    public suspend fun cancel(): KWebDownloadControlResult
    override fun close()
}

public enum class KWebDownloadControlOutcome {
    ACCEPTED, ALREADY_TERMINAL, OWNER_CLOSED,
}

public data class KWebDownloadControlResult(
    public val id: Long,
    public val outcome: KWebDownloadControlOutcome,
)
```

The following rules are part of the schema: IDs are positive and Profile
unique for the Engine lifetime; URL values are absolute and contain no user
information; names are one portable filename component, at most 255 UTF-8
bytes, and reject traversal, separators, control characters, trailing dot or
space, and reserved device names; byte counters are non-negative; unknown
length is `null`; `sha256` is either `null` or exactly 64 lowercase hex
characters; `file` is non-null only in `COMPLETE`; and terminal states never
transition again. `KWebDownloadFile` is an owner-scoped capability and cannot
be converted to a path or sent through JSON.

The desktop-only policy is:

```kotlin
public data class KWebDesktopDownloadPolicy(
    public val directory: java.nio.file.Path,
    public val collision: KWebDownloadCollisionPolicy =
        KWebDownloadCollisionPolicy.RENAME_UNIQUE,
    public val computeSha256: Boolean = true,
    public val expectedSha256ByUrl: Map<String, String> = emptyMap(),
)
```

It is supplied as `KWebDesktopEngineConfiguration.downloadPolicy`. The
directory must be absolute, an existing non-symlink directory, and writable.
The expected-hash map is host-only, bounded, keyed by absolute URL, and is
valid only when `computeSha256` is true. A null policy means downloads are
explicitly denied and the `DOWNLOADS` capability is absent.

The native event JSON carried only inside the host ABI is version 1 and has
this shape; 64-bit values are decimal strings to avoid JavaScript precision
loss:

```json
{
  "version": 1,
  "downloadId": "4294967297",
  "status": "in-progress",
  "originalUrl": "https://example.test/file",
  "url": "https://cdn.example.test/file",
  "suggestedFileName": "file.bin",
  "contentDisposition": "attachment; filename=file.bin",
  "mimeType": "application/octet-stream",
  "receivedBytes": "4096",
  "totalBytes": "8192",
  "currentSpeedBytesPerSecond": "4096",
  "interruptReason": 0,
  "stagingPath": "/profile/.kwebshell-downloads/1/1-file.bin"
}
```

`stagingPath` is ABI-internal input to the desktop provider. It is removed
before a `KWebDownloadState` is published and is never included in renderer
messages, migration output, logs, or retained evidence.

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `KWebProfile.downloads` | `Flow<KWebDownload>`, Profile scope | Cold, no replay; 64 buffered entries | One object per admitted Chromium download | A1, A4 |
| `KWebDownload.state` | `StateFlow<KWebDownloadState>` | Initial `STARTING`; one ordered sequence of immutable snapshots | Monotonic bytes and one terminal state | A2, A4 |
| `pause` / `resume` / `cancel` | Host-only suspend operations | Only live downloads; no renderer grant | Typed accepted/already-terminal result or stable error | A3, A5 |
| `KWebDownloadFile.read` | `offset >= 0`, `1..1_048_576` bytes | Complete state and open handle only | Bounded bytes and EOF | A6, A7 |
| `KWebDesktopDownloadPolicy` | Trusted JVM-only `Path`, closed enum policy | Required to enable downloads | Staging, atomic finalize, hash and collision behavior | A1, A6, A8 |

### Lifecycle, concurrency and limits

- CEF invokes `CefDownloadHandler` on the browser-process UI thread. The
  desktop callback executor parses events and serializes each download's
  state transitions. Control requests marshal back to the CEF UI thread.
- A Profile admits at most 64 live downloads and the host stream buffers 64
  download objects. If the stream cannot accept a new object, the native
  download is denied/canceled and the host records
  `download.event-backpressure`; it is never silently accepted.
- A download first enters `STARTING`, then `IN_PROGRESS` or `PAUSED`, and
  ends exactly once in `COMPLETE`, `CANCELED`, `INTERRUPTED`, or
  `DENIED`. Chromium's canceled result wins over a host cancel race; owner
  close maps to `CANCELED` with `OWNER_CLOSED`; integrity mismatch maps a
  Chromium complete result to `INTERRUPTED` after staging cleanup.
- Progress bytes are monotonic per object. Unknown total remains unknown;
  `currentSpeedBytesPerSecond` is clamped to zero or greater. Duplicate or
  regressing native updates are rejected as `download.event.invalid`.
- CEF writes only into a Profile-owned staging directory. The desktop provider
  verifies the staging file, computes the optional SHA-256, atomically moves
  it into the declared destination, and then publishes `COMPLETE`. Partial
  files are deleted on every non-complete terminal path and on owner close.
- `KWebDownload.close()` cancels a live download, closes a completed file
  capability, and is idempotent. Profile close is allowed only after Pages
  follow the existing owner-close contract; Engine close cancels all remaining
  downloads before releasing CEF callback owners.

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| `STARTING` | CEF accepts path | UI callback, valid policy and safe name | `IN_PROGRESS` | Retain only CEF callback and staging record | A1, A2 |
| `IN_PROGRESS` | pause/resume/cancel | Host operation, marshalled to UI | `PAUSED`, `IN_PROGRESS`, or terminal | Callback invoked once per accepted control | A3 |
| live | renderer navigation/crash | Native Browser remains owner | Continue, interrupt, or cancel per Chromium | No cross-Page ownership | A4 |
| live | Page/Profile/Engine close | Owner close wins over new controls | `CANCELED(OWNER_CLOSED)` | Cancel callback, delete partial, release refs | A5, A7 |
| live | Chromium complete | File exists in staging | `COMPLETE` or `INTERRUPTED(INTEGRITY_MISMATCH)` | Hash/finalize or delete staging | A6, A8 |
| terminal | duplicate update/control/close | Any caller thread | No transition; typed stale/terminal error | No second callback or file mutation | A3, A5 |

### Errors, renderer and migration policy

Stable host errors are `download.policy.invalid`,
`download.destination.invalid`, `download.filename.invalid`,
`download.collision`, `download.not-found`, `download.already-terminal`,
`download.profile-closing`, `download.event-invalid`,
`download.event-backpressure`, `download.file-not-ready`,
`download.file-closed`, `download.file-read-bounds`,
`download.integrity-mismatch`, and `download.native-capability-missing`.
Every unsupported operation fails with one of these or a typed native error;
there is no default directory or fallback backend.

No renderer operation, grant, user gesture, or renderer-origin exception is
defined. The migration adapter may expose bounded progress snapshots only when
the host explicitly maps the named operation; it never exposes a path,
`KWebDownloadFile`, pause/resume/cancel native object, or arbitrary callback
channel. Electron `will-download`/`DownloadItem` is therefore `REWRITE`,
and unclassified download operations remain blocked.

| Condition | Stable error / outcome | CLI exit / retained output, if applicable | Acceptance IDs |
|---|---|---|---|
| No policy or invalid policy | `download.policy.invalid` / `DENIED` | None; no file | A1, A8 |
| Unsafe name, symlink staging, or invalid destination | `download.filename.invalid` or `download.destination.invalid` | None; cleanup report | A1, A6 |
| Existing destination under `FAIL` | `download.collision` / `INTERRUPTED(FILE_EXISTS)` | None; no overwrite | A6 |
| Unknown/terminal control | `download.not-found` or `download.already-terminal` | None | A3, A5 |
| Hash differs from expected | `download.integrity-mismatch` / `INTERRUPTED` | None; staging deleted | A6 |
| Renderer asks for path or arbitrary mutation | Migration validation blocker | Nonzero migration validation | A8 |

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result, or reason none is needed | Acceptance IDs |
|---|---|---|---|---|
| All CEF targets | stock CEF 151 `CefClient::GetDownloadHandler`, `CefDownloadHandler::OnBeforeDownload`, `OnDownloadUpdated`, `CefDownloadItem`, `CefBeforeDownloadCallback`, `CefDownloadItemCallback` | Browser-process UI thread; callback refs owned only by the BrowserSession download registry | Header probe completed against pinned `151.3.16 / Chromium 151.0.7922.109`; pause/resume APIs are present (`IsPaused` added in 14400) | A1-A5 |
| macOS | JDK 25 `java.nio.file`, POSIX atomic move where available, stock CEF Alloy child | Kotlin owns destination and file capability; no Keychain or security-scoped path is required for the trusted host path | Existing JVM filesystem provider is sufficient; no OS-specific dialog promise is made | A6, A7 |
| Windows | JDK 25 `java.nio.file`, `ATOMIC_MOVE`/no-follow checks where supported, stock CEF Alloy child | Reject reparse/symlink destination and fail closed if atomic/no-follow move cannot be established | Existing Windows hosted native suite plus filesystem security tests; no Win32 fallback is needed | A6, A7 |
| Linux | JDK 25 `java.nio.file`, POSIX no-follow and atomic move, stock CEF Alloy child | Reject symlink staging/destination and clean staging on shutdown | Existing Linux/X11 hosted native suite plus filesystem security tests | A6, A7 |

The critical unknowns are limited to the stock CEF callback order and
cross-platform filesystem move semantics. The pinned header probe settles the
former; the JVM provider tests execute real temporary directories and reject
any platform that cannot satisfy the no-follow/atomic contract. No custom CEF
patch, NetworkContext, self-hosted runner, or alternate renderer is required.

### Evidence lifecycle and delivery

RFC 0012 evidence binds the RFC file, `KWebDownloadContract.kt`, desktop
policy/object/provider files, native CEF download adapter, C ABI/FFM layout and
status tests, real HTTP fixture server, migration fixture, and the exact stock
CEF runtime identity. Changes to any of these inputs invalidate the RFC 0012
records. The first hosted run occurs only after unit, native, and local real
HTTP integration tests pass; the same PR imports Linux x64, macOS arm64, and
Windows x64 download records, then runs strict checked-in-evidence governance.

The PR must deliver the common contract, desktop policy and file capability,
native CEF handler and control ABI, filesystem/hash tests, real HTTP fixture
coverage, migration matrix/golden output, documentation, capability metadata,
evidence manifest/artifacts, and final acceptance record together. A green
compile without real bytes, progress, cancellation, hash, cleanup, and target
evidence is not support.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / policy and scope | Downloads require explicit trusted policy, are Profile-scoped, and use stock CEF 151 without fallback. | Null policy; valid policy; wrong Profile; stock headers; no renderer path. | Core/desktop policy tests; ABI/native contract tests; all three CEF targets. | `KWebDownloadContract.kt`, `KWebDesktopDownloadPolicy`, `KWebDesktopEngine.kt`, native `CanDownload`/`CefDownloadHandler`, `KWebDesktopDownloadTest`. | Local macOS integration passed; hosted records pending. | NOT_RUN — hosted target evidence is pending. |
| A2 / state and metadata | Every admitted download publishes bounded URL/name/MIME/disposition metadata and monotonic byte progress. | Known/unknown length; redirect; content disposition; Unicode name; 0/large bytes; regressing update. | Kotlin parser/state tests and real HTTP fixture on macOS, Windows, Linux. | `KWebDesktopDownloadJson`, `KWebDesktopDownload`, `KWebDownloadContractTest`, downloads integration. | Local parser and real HTTP bytes passed; hosted records pending. | NOT_RUN — hosted target evidence is pending. |
| A3 / controls | Pause, resume, and cancel are typed, Profile-scoped, one-shot where terminal, and preserve CEF ownership. | Pause/resume; duplicate control; control after complete; concurrent cancel/close. | Native control ABI, desktop object tests, real slow HTTP fixture on all targets. | `kweb_browser_download_control`, `KWebDesktopDownload`, FFM bindings, slow-download integration. | Local cancel and terminal ownership passed; hosted records pending. | NOT_RUN — hosted target evidence is pending. |
| A4 / lifecycle | Renderer navigation/crash, Page close, Profile close, and Engine shutdown produce exactly one terminal state and release callback owners. | Navigation during download; renderer crash; close race; shutdown race; no live native refs. | Real CEF lifecycle fixture and callback-owner counters on all targets. | BrowserSession `CancelDownloads`, Profile/Page owner maps, native owner counters, downloads integration. | Local engine/browser counters reached zero; full lifecycle hosted evidence pending. | NOT_RUN — hosted target evidence is pending. |
| A5 / bounded delivery | Live downloads and event buffering are bounded; overflow denies/cancels rather than drops or accepts silently. | 64/65 concurrent downloads; no collector; slow collector; duplicate IDs. | Native burst fixture and bounded Flow tests on all targets. | `KWebDesktopDownloadStream`, `KWebDesktopDownloadTest`, native 64-live admission limit. | Local no-subscriber backpressure and bounded admission code passed; hosted burst evidence pending. | NOT_RUN — hosted target evidence is pending. |
| A6 / destination security | Safe filename, staging, collision, symlink/reparse and atomic-finalize rules are enforced. | Traversal; separators; reserved device names; Unicode; symlink replacement; `FAIL`, rename, replace; destination race. | JVM filesystem tests on all targets plus real downloaded bytes. | `safeDownloadName`, `moveIntoDestination`, staging checks, file/collision tests. | Local traversal, reserved-name, collision, staging and byte finalization passed; hosted filesystem evidence pending. | NOT_RUN — hosted target evidence is pending. |
| A7 / scoped file capability | Only completed downloads expose a bounded non-serializable file handle; partial files are deleted. | Read ranges/EOF; closed handle; non-complete access; path serialization attempt; partial interruption. | Core/desktop file tests and real complete/interrupted fixtures on all targets. | `KWebDownloadFile`, `KWebDesktopDownloadFile`, bounded-read tests, path-disclosure fields. | Local bounded reads, close, and no-path evidence fields passed; hosted records pending. | NOT_RUN — hosted target evidence is pending. |
| A8 / integrity | Optional SHA-256 is computed from final bytes and expected URL hashes reject mismatches without exposing the file. | Hash enabled/disabled; correct hash; wrong hash; empty file; large file. | Real bytes and retained hash records on all targets. | `KWebDesktopDownloadPolicy`, `sha256`, `finalizeDownload`, downloads evidence. | Local real hash and retained digest passed; hosted records pending. | NOT_RUN — hosted target evidence is pending. |
| A9 / resume and interruption | Chromium-owned pause/resume and range/no-range interruption behavior is surfaced without Kotlin reimplementation. | Range resume; no-range restart rejection; network timeout; disk/full-like failure; server errors. | Slow/range HTTP fixture and real CEF download interrupt evidence on all targets. | Native item controls/status mapping and slow HTTP fixture; resume coverage remains hosted pending. | Local cancellation/interruption passed; resume/range evidence pending. | NOT_RUN — required resume evidence is pending. |
| A10 / migration | Electron download APIs are rewritten to named typed state; path mutation and arbitrary callbacks are blocked. | `will-download`; `DownloadItem` progress; `setSavePath`; `open`; unknown API. | Migration matrix, validator and golden fixture tests. | `KWebElectronCapabilityMatrix`, migration README, matrix contract tests. | Local migration matrix tests passed; hosted migration records pending. | NOT_RUN — hosted target evidence is pending. |
| A11 / packaging and docs | Public capability, docs, packaging and schema agree; no staging/test workspace is packaged. | Capability absent with null policy; package scan; stale docs/matrix. | Docs, package, capability and `git diff --check` gates. | README, DESIGN_PLAN, RFC, capability metadata, evidence contracts, diff check. | Local docs/schema checks passed; hosted package/governance refresh pending. | NOT_RUN — hosted target evidence is pending. |
| A12 / hosted evidence | Evidence contains real bytes, hash, progress, resume/terminal facts and zero live native owners for macOS arm64, Windows x64 and Linux x64. | Missing target; stale source/runtime; private absolute path; skipped fixture. | Hosted `runtimeCheck`, evidence recorder, strict governance. | `downloads-evidence.json`, aggregate recorder `0012` spec, contracts binding. | Local macOS evidence exists; Windows/Linux records are pending. | NOT_RUN — three-target hosted evidence is pending. |
| A13 / universal completion | Implementation, tests, native packaging, migration, evidence, reviewed revision and clean worktree are complete in one focused PR. | Any skipped required target/test or changed contract without refresh. | Full PR diff review and all required CI jobs. | Complete focused diff, local test gates, PR evidence/governance refresh. | Local gates passed except expected stale pre-refresh governance; hosted refresh pending. | NOT_RUN — merge acceptance remains pending. |

## Contract review record

- Reviewed revision: working-tree RFC contract revision `2026-09-29.1` on the
  `rfc/0012-downloads` topic branch, before implementation edits.
- Review pass: Codex readiness review, 2026-09-29; same contributor as the
  eventual implementation, identified separately from implementation work.
- Findings and dispositions:
  - Stock CEF 151 provides the required handler/item/control hooks; accepted.
  - `OnBeforeDownload` cannot asynchronously cancel; destination policy is
    fixed at Engine creation and Save As is explicitly out of scope; accepted.
  - RFC 0013 is not required to publish the minimal download file capability;
    accepted with an explicit future-generalization note.
  - Absolute paths remain host-only; CEF writes to a Profile staging path and
    JVM finalization owns destination races; accepted.
  - All three advertised desktop targets use the same stock CEF/JDK boundary;
    accepted pending real hosted evidence.
- Probe references: pinned headers under `.cef-ci/source/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal/include/cef_download_handler.h`
  and `cef_download_item.h`; `CefDownloadItem::IsPaused` is available in CEF
  API 14400 and later.
- Decision: **READY**. The contract is complete and falsifiable; no unresolved
  required behavior or native feasibility finding blocks implementation.

## Merge acceptance record

To be completed in the implementation PR after the final hosted run. It must
record the final reviewed revision, separate acceptance review identity, every
applicable A1-A13 result, target artifacts, full diff review, and `PASS` before
the RFC moves to `Implemented`.

## Non-goals

No Kotlin download engine, browser-backend fallback, automatic execution/open,
unrestricted `setSavePath`, renderer path access, or direct Electron
`DownloadItem` object is published.
