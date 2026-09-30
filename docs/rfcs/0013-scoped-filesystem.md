# RFC 0013: Capability-based files, directories, and workspace access

- Status: Implemented
- Priority: P0
- Owners: `kweb-service-files`, `kweb-bridge`, `kweb-electron-migration`
- Depends on: RFC 0002, RFC 0003, RFC 0004
- Electron migration surface: Node `fs/promises`, `path`, renderer `webUtils.getPathForFile`
- Target mapping: `REWRITE`

## Objective

Publish one explicit `kweb-service-files` capability service for bounded file
and directory access inside host-declared logical workspaces. A workspace is
configured by trusted Kotlin/JVM host code with an absolute root, an allowlist
of grants, and an owner scope. Renderer code receives only opaque capability
descriptors and normalized relative names; it never receives an absolute path,
an ambient path resolver, or a native platform handle.

This objective extends the capability model needed by migrated asynchronous
Node `fs/promises` workflows. The existing `KWebDialogs` service remains the
native picker boundary and retains its already-published picker/file-handle
contract. RFC 0013 does not alias a dialog token into a second service or copy
the picker implementation. A future focused objective may connect a picker
selection to a declared workspace; until then, applications explicitly
configure the workspace root in host code.

The implementation uses stock JDK 25 `java.nio.file` providers on macOS,
Windows, and Linux. It does not require a custom CEF patch, a second bridge,
or a second network/filesystem process.

## Non-goals and applicability

- No arbitrary absolute-path, `path.resolve`-style ambient authority, shell
  glob language, virtual Node filesystem, or synchronous renderer I/O.
- No transparent symlink/reparse traversal. A link or reparse target is
  rejected unless a future reviewed contract explicitly adds a bounded,
  verified target operation.
- No persistent grant or restart-restoration API in this objective. macOS
  security-scoped bookmarks and Linux XDG document-portal persistent grants
  are therefore not advertised; an unsandboxed host must configure roots again
  after restart. A sandbox or unavailable provider fails with a typed error and
  never falls back to a different root or backend.
- No native picker is added here. `KWebDialogs` continues to own picker UI and
  its existing bounded file handles; `KWebFiles` owns workspace capabilities.
- No raw `Path`, `FileDescriptor`, `HANDLE`, `NSURL`, portal document ID, or
  platform object enters common KMP code or renderer JSON.
- No cross-workspace `move` fallback. If an atomic same-filesystem move cannot
  be established, the operation fails; applications may explicitly request a
  bounded copy followed by a separate delete in a later operation.
- The common contract, JVM provider, generated exact-origin bridge, migration
  rewrite, tests, packaging metadata, documentation, and real hosted evidence
  are applicable. Direct Electron objects and Node `Buffer` are unsupported.

## Implementation contract

Contract revision: `2026-09-30.1`.

### Common KMP and data contract

`KWebFiles` is a PAGE-scoped service instance. The host creates one instance
for one `(engineId, profileId, pageId, committedOrigin, navigationId)` scope
and closes it on page close, renderer termination, navigation invalidation, or
owner shutdown. `KWebFileHandle` is an opaque non-serializable common value;
only the generated bridge's string descriptor is renderer-visible.

```kotlin
public enum class KWebFileGrant {
    READ, WRITE, CREATE, ENUMERATE, WATCH, METADATA, COPY, MOVE,
}

public enum class KWebFileNodeKind { FILE, DIRECTORY }

public enum class KWebFileConflictPolicy { FAIL, REPLACE }

public enum class KWebFileOpenMode { READ, WRITE, READ_WRITE }

public data class KWebFileOwnerScope(
    public val engineId: String,
    public val profileId: String,
    public val pageId: String,
    public val origin: String,
    public val navigationId: Long,
)

public data class KWebWorkspaceRequest(
    public val workspaceId: String,
    public val grants: Set<KWebFileGrant>,
)

public data class KWebFileOpenRequest(
    public val parent: KWebFileHandle,
    public val name: String,
    public val mode: KWebFileOpenMode,
    public val createIfMissing: Boolean = false,
)

public data class KWebDirectoryOpenRequest(
    public val parent: KWebFileHandle,
    public val name: String,
    public val createIfMissing: Boolean = false,
)

public class KWebFileHandle internal constructor(public val token: String) {
    public companion object {
        internal fun fromBridge(token: String): KWebFileHandle = KWebFileHandle(token)
    }
}

public data class KWebFileCapabilityDescriptor(
    public val handle: String,
    public val kind: KWebFileNodeKind,
    public val name: String,
    public val grants: Set<KWebFileGrant>,
)

public data class KWebFileReadResult(public val bytes: ByteArray, public val eof: Boolean)
public data class KWebFileWriteResult(public val written: Int)
public data class KWebFileMetadata(
    public val kind: KWebFileNodeKind,
    public val name: String,
    public val sizeBytes: Long?,
    public val lastModifiedEpochMillis: Long,
)
public data class KWebDirectoryEntry(
    public val name: String,
    public val kind: KWebFileNodeKind,
    public val sizeBytes: Long?,
    public val lastModifiedEpochMillis: Long,
)
public data class KWebDirectoryListing(public val entries: List<KWebDirectoryEntry>, public val truncated: Boolean)

public enum class KWebFileWatchKind { CREATED, MODIFIED, DELETED, OVERFLOW }
public data class KWebFileWatchEvent(
    public val sequence: Long,
    public val kind: KWebFileWatchKind,
    public val name: String?,
)

public interface KWebFiles : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>

    public suspend fun openWorkspace(request: KWebWorkspaceRequest): KWebFileCapabilityDescriptor
    public suspend fun openFile(request: KWebFileOpenRequest): KWebFileCapabilityDescriptor
    public suspend fun openDirectory(request: KWebDirectoryOpenRequest): KWebFileCapabilityDescriptor
    public suspend fun readFile(handle: KWebFileHandle, offset: Long, length: Int): KWebFileReadResult
    public suspend fun writeFile(handle: KWebFileHandle, offset: Long, bytes: ByteArray): KWebFileWriteResult
    public suspend fun truncateFile(handle: KWebFileHandle, sizeBytes: Long): Long
    public suspend fun listDirectory(handle: KWebFileHandle, limit: Int): KWebDirectoryListing
    public suspend fun metadata(handle: KWebFileHandle): KWebFileMetadata
    public suspend fun copyFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor
    public suspend fun moveFile(
        source: KWebFileHandle,
        targetDirectory: KWebFileHandle,
        targetName: String,
        conflict: KWebFileConflictPolicy,
    ): KWebFileCapabilityDescriptor
    public fun watchDirectory(handle: KWebFileHandle): Flow<KWebFileWatchEvent>
    public suspend fun closeHandle(handle: KWebFileHandle)

    public companion object {
        public val DESCRIPTOR: KWebServiceDescriptor = KWebServiceDescriptor(
            id = "files",
            version = KWebServiceVersion(1, 0, 0),
            scope = KWebServiceScope.PAGE,
            operations = setOf(
                operation("open-workspace", requiresUserGesture = true),
                operation("open-file"),
                operation("open-directory"),
                operation("read-file"),
                operation("write-file"),
                operation("truncate-file"),
                operation("list-directory"),
                operation("metadata"),
                operation("copy-file"),
                operation("move-file"),
                operation("watch-directory"),
                operation("close-handle"),
            ),
            requiredCapabilities = emptySet(),
            supportedTargets = KWebTarget.supported,
        )

        public val Key: KWebServiceKey<KWebFiles> = object : KWebServiceKey<KWebFiles> {
            override val id: String = DESCRIPTOR.id
            override val contract: KWebServiceVersionRange = KWebServiceVersionRange.exact(DESCRIPTOR.version)
        }

        private fun operation(id: String, requiresUserGesture: Boolean = false): KWebServiceOperationDescriptor =
            KWebServiceOperationDescriptor(
                id = id,
                schemaVersion = 1,
                rendererPermission = "native.files.$id",
                requiresUserGesture = requiresUserGesture,
            )
    }
}

public object KWebFilesErrorCode {
    public const val WORKSPACE_INVALID: String = "files.workspace-invalid"
    public const val WORKSPACE_NOT_ALLOWED: String = "files.workspace-not-allowed"
    public const val HANDLE_INVALID: String = "files.handle-invalid"
    public const val HANDLE_NOT_FOUND: String = "files.handle-not-found"
    public const val HANDLE_KIND: String = "files.handle-kind"
    public const val HANDLE_GRANT: String = "files.handle-grant"
    public const val NAME_INVALID: String = "files.name-invalid"
    public const val ROOT_ESCAPE: String = "files.root-escape"
    public const val IO_BOUNDS: String = "files.io-bounds"
    public const val ENUMERATION_LIMIT: String = "files.enumeration-limit"
    public const val CONFLICT: String = "files.conflict"
    public const val ATOMIC_MOVE_UNAVAILABLE: String = "files.atomic-move-unavailable"
    public const val WATCH_OVERFLOW: String = "files.watch-overflow"
    public const val PLATFORM_UNAVAILABLE: String = "files.platform-unavailable"
}
```

The service validates workspace IDs as lowercase logical identifiers, names as
one portable UTF-8 path component (no separator, `.`/`..`, control character,
trailing dot/space, or reserved Windows device name), and handles as random
256-bit URL-safe tokens. `READ`/`WRITE` chunks are limited to 1 MiB; directory
enumeration is limited to 4,096 entries; watch delivery has a 64-event credit
queue. `sizeBytes` is null for directories and `lastModifiedEpochMillis` is
non-negative. No descriptor contains a path or native handle.

The generated bridge uses decimal strings for 64-bit values and base64 strings
only for bounded binary chunks. `read-file` and `write-file` are bounded RPCs;
`watch-directory` is an RFC 0004 credit-gated stream. A future large-file
optimization must extend the stream schema, not raise the JSON chunk limit.

### Lifecycle, concurrency and limits

- The provider owns one serial capability registry per PAGE scope. Handle
  creation, grant checks, close, and owner shutdown are serialized; file I/O
  and directory enumeration run on `Dispatchers.IO` and never on CEF UI,
  Compose/AWT, or native upcall threads.
- A workspace handle is the only authority from which child handles can be
  opened. Child handles retain the workspace root identity, node kind, grants,
  and owner scope. A handle from another Page, origin, navigation, provider,
  or service instance is rejected even if its token text is copied.
- `open-workspace` grants only the intersection of the requested grants and
  the host-declared workspace grants. A request for an undeclared grant fails;
  it is never silently reduced to a weaker capability.
- All path resolution starts from a canonical non-link root and uses one
  portable component at a time. Existing nodes are opened with no-follow
  semantics and revalidated as regular files/directories. Any link/reparse,
  root escape, type mismatch, or permission change fails closed.
- A file handle is live until explicit close or owner close. Close is idempotent
  for the owning service and rejects copied/unknown tokens. Owner close closes
  every handle and terminates every watch exactly once; no operation started
  after CLOSING is admitted.
- Directory watch events preserve sequence order. OS overflow becomes one
  explicit `OVERFLOW` event and then a terminal `files.watch-overflow` result;
  events are never silently dropped. Slow renderer consumers exert RFC 0004
  backpressure and do not create an unbounded JVM queue.

| Current state | Trigger / race | Next state / result | Resource effects | Acceptance IDs |
|---|---|---|---|---|
| service open | valid workspace request | root capability | register canonical root and grants | A1, A2 |
| service open | copied token / wrong owner / wrong navigation | typed handle error | no filesystem access | A3, A10 |
| directory handle | open/list/watch child | child capability or bounded result | retain only validated node handle | A4, A5, A9 |
| file handle | read/write/truncate | bounded result or typed I/O failure | no path disclosure | A4, A6 |
| file handle | copy/move conflict | new target capability or conflict error | atomic target publication; no partial target | A7 |
| any open state | close/navigation/owner shutdown | CLOSING then CLOSED | close handles/watchers exactly once | A10 |
| watch active | OS overflow / renderer abort | terminal overflow/close | unregister key and release watcher | A9, A10 |

### Errors, renderer and migration policy

The stable errors are `files.workspace-invalid`,
`files.workspace-not-allowed`, `files.handle-invalid`,
`files.handle-not-found`, `files.handle-kind`, `files.handle-grant`,
`files.name-invalid`, `files.root-escape`, `files.io-bounds`,
`files.enumeration-limit`, `files.conflict`,
`files.atomic-move-unavailable`, `files.watch-overflow`,
`files.platform-unavailable`, `service.permission-denied`,
`service.user-gesture-required`, `service.owner-closed`, and
`service.cancelled`. Native `AccessDenied`, `NoSuchFile`, `InvalidPath`, and
provider errors map to these codes with redacted details; absolute paths are
never included in exceptions, logs, bridge errors, or evidence.

Every renderer operation is exact-origin and main-frame only through the
existing `KWebServicePolicyEngine`. `open-workspace` requires a native-verified
gesture and the host-declared renderer grant. All other operations require the
same page/origin service instance and the token's grant; a host call bypasses
only renderer permission/frame checks, never owner, root, or OS checks. A
navigation invalidates the page service and all handles.

Electron mappings are explicit: asynchronous `fs/promises` calls are
`REWRITE` to named files operations, `path` is replaced by normalized relative
names, `webUtils.getPathForFile` remains unsupported, synchronous `fs` calls,
arbitrary absolute paths, `Buffer`, and generic channels are blocked. The
migration fixture pins Electron major 44 and demonstrates open/read/write/list
and watch cancellation through the generated typed facade.

| Condition | Stable outcome | Retained output | Acceptance IDs |
|---|---|---|---|
| Unknown or undeclared workspace/grant | typed deny/error | no root handle | A2, A11 |
| Forged/cross-page/after-navigation handle | `files.handle-invalid` or `files.handle-not-found` | no path/token details | A3, A10 |
| Link/reparse/root escape | `files.root-escape` | no target path | A4, A8 |
| Bounds/limit violation | `files.io-bounds` or `files.enumeration-limit` | no partial result | A5, A6 |
| Existing target under `FAIL` | `files.conflict` | source and target unchanged | A7 |
| Atomic move unavailable | `files.atomic-move-unavailable` | no copy/delete fallback | A7 |
| Watch overflow or abort | terminal `files.watch-overflow` or `service.cancelled` | ordered prior events only | A9 |
| Node path/sync API/Buffer use | migration validation blocker | nonzero migration result | A11 |

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result, or reason none is needed | Acceptance IDs |
|---|---|---|---|---|
| All advertised targets | JDK 25 `java.nio.file.Files`, `Path`, `FileChannel`, `DirectoryStream`, `WatchService`, `LinkOption.NOFOLLOW_LINKS`, `StandardCopyOption.ATOMIC_MOVE` | Provider work runs on bounded JVM IO dispatchers; no platform handle enters common code | JDK provider APIs are the implementation boundary; local and hosted filesystem tests exercise the actual provider and fail closed when a required option is unavailable | A1-A10 |
| macOS | Unix/JDK provider, `SecureDirectoryStream` when offered, POSIX attributes | This RFC advertises unsandboxed host roots only; no security-scoped bookmark is promised | macOS hosted filesystem/security suite records provider name, link rejection, atomic move, permission failure, watch close, and no-path evidence; sandbox bookmark is explicitly out of scope | A4, A7, A8, A13 |
| Windows | Windows JDK provider, DOS attributes, no-follow open/revalidation, atomic move | Reparse/other nodes are rejected; if the provider cannot prove no-follow/atomic behavior, the operation fails typed | Windows hosted suite records reparse/link rejection, case behavior, conflict policy, atomic move result, permission change, and owner close | A4, A7, A8, A13 |
| Linux | Unix JDK provider, POSIX attributes, `SecureDirectoryStream` when offered, XDG filesystem | This RFC advertises unsandboxed roots; portal document persistence is not claimed | Linux hosted suite records symlink/root escape rejection, case/Unicode behavior, atomic move, WatchService overflow/close, and no-path evidence | A4, A7, A8, A9, A13 |

No unresolved native mechanism blocks readiness: the service intentionally
uses the JDK filesystem provider rather than a new C ABI, CEF callback, portal
document token, or security-scoped bookmark. The risky behavior is the
provider's actual no-follow/atomic/watch semantics, and those are falsifiable
on every advertised target by the required real filesystem suite.

### Evidence lifecycle and delivery

Evidence binds this RFC, the `KWebFiles` common contract, the JVM provider and
configuration, generated bridge schema/output, migration manifest and fixture,
filesystem fixtures, provider/runtime identity, and the target package scan.
Changing any of those inputs invalidates the corresponding target record.
Absolute workspace roots are excluded from retained output; evidence records
only logical workspace IDs, redacted node names, outcomes, counts, hashes of
fixture bytes, and provider capability facts.

The first hosted run occurs only after common contract tests, provider tests,
generated TypeScript strictness, real CEF exact-origin bridge tests, and local
filesystem security tests pass. The same PR imports macOS arm64, Windows x64,
and Linux x64 records, updates the migration matrix and documentation, runs
strict checked-in governance, and reaches the final acceptance decision.

The PR must deliver the new KMP service, JVM provider, generated bridge and
stream schema, real filesystem fixtures/tests, migration fixture and matrix,
packaging/provider metadata, documentation, evidence records, and final RFC
acceptance record together. A mock-only filesystem test or a green compile is
not support.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common contract | Immutable typed workspace, handle, metadata, listing, watch, and bounded byte models validate all limits and have no platform path fields. | Valid/invalid IDs, names, grants, counters, bytes, empty/maximum values, forged token text. | Common contract tests; JVM provider tests; all hosted governance targets. | `KWebFiles.kt`, common contract tests. | `files.hosted` records for macOS arm64, Windows x64, and Linux x64; local `:kweb-service-files:check`. | `PASS` — contract and bounds are implemented and verified on all hosted targets. |
| A2 / explicit authority | Only host-declared logical workspaces and grant intersections can produce root handles; undeclared grants fail without reduction. | Valid workspace; unknown workspace; request extra grant; empty grant; renderer grant/gesture denied. | Common/provider/policy tests and exact-origin CEF fixture on all targets. | `JvmKWebFilesConfiguration`, `KWebFilesBridge`, policy tests, native files workflow. | `files-evidence.json` for all hosted targets; exact-origin bridge denial and grant checks. | `PASS` — exact grants and native-gesture denial are verified on all hosted targets. |
| A3 / handle isolation | Handles are random, owner/page/origin/navigation bound, non-serializable common values, and unusable across service instances or after close/navigation. | Copy token; wrong page/origin; stale navigation; duplicate close; unknown handle. | Common lifecycle tests and real CEF frame/navigation tests on all targets. | `JvmKWebFilesPageOwner`, `JvmKWebFilesTest`, native navigation fixture. | Hosted files evidence plus provider/owner lifecycle tests. | `PASS` — page, origin, navigation, and service-instance isolation are verified. |
| A4 / file operations | Read, bounded write, truncate, and open/create perform only the declared operation on one validated child component. | EOF; 0/1 MiB; negative/overflow offset; read-only write; create collision; file/directory mismatch. | JVM provider tests on macOS, Windows, Linux; exact-origin bridge tests. | `JvmKWebFiles`, `JvmKWebFilesTest`, native files workflow. | Hosted files evidence and real CEF files workflow on all targets. | `PASS` — normal read/write/EOF/create paths pass on all hosted targets. |
| A5 / directories | Enumeration is sorted, bounded, redacted to relative names, and reports truncation rather than allocating unbounded output. | Empty directory; 4,096/4,097 entries; Unicode names; invalid name; concurrent delete. | JVM provider tests on all targets; CEF bridge fixture. | directory listing provider, contract tests, native fixture. | Hosted files evidence, directory provider tests, and bridge fixture. | `PASS` — sorted bounded listings and invalid components pass on all hosted targets. |
| A6 / metadata and byte bounds | Metadata never exposes absolute paths/file keys; byte operations stay within 1 MiB and return deterministic EOF/size facts. | Large sparse file; changed size; permission change; closed handle; multi-gigabyte stream fixture. | JVM tests plus RFC 0004 binary stream conformance on all targets. | metadata/read models, provider tests, generated bridge, native fixture. | Hosted files evidence, metadata redaction, bounded payload, and EOF checks. | `PASS` — no absolute path is returned and byte bounds hold on all hosted targets. |
| A7 / copy and move | Copy/move honor `FAIL`/`REPLACE`; move is atomic or fails with no fallback; no partial target is published. | Same filesystem; cross-filesystem; target collision; source mutation; permission denied. | Real provider tests on all targets. | `JvmKWebFiles` atomic copy/move implementation and conflict fixture. | Hosted files evidence plus provider copy/move and conflict tests. | `PASS` — copy publication, conflict behavior, and atomic move policy pass on all hosted targets. |
| A8 / path security | Symlinks, Windows reparse/other nodes, traversal, root escape, and TOCTOU replacement are rejected or fail closed. | Link to inside/outside root; replacement during open; case collision; Unicode normalization; reserved names. | Real filesystem security suite on macOS arm64, Windows x64, Linux x64. | no-follow provider checks, symlink tests, name validation, native invalid-name fixture. | Hosted files evidence and target filesystem security suites. | `PASS` — links, reparse/other nodes, traversal, and root escape fail closed on all hosted targets. |
| A9 / watch | Watch events are ordered and bounded; OS overflow becomes explicit terminal overflow; abort/close releases the watcher. | Create/modify/delete; slow consumer; overflow; directory removal; owner close; abort signal. | RFC 0004 stream tests and real WatchService tests on all targets. | files bridge stream, `WatchService` provider, native and migration watch fixtures. | Hosted files evidence, migration watch fixture, and provider WatchService tests. | `PASS` — ordered watch delivery and cancellation pass on all hosted targets. |
| A10 / lifecycle | Page close, navigation invalidation, renderer crash, and service close terminate all operations/watchers and release live resources exactly once. | Concurrent close/read/watch; close during copy; operation after close. | CEF lifecycle fixture and provider live-count tests on all targets. | `JvmKWebFilesPageOwner`, owner tests, native navigation/close workflow. | Hosted files evidence, stale-navigation checks, and native live-count checks. | `PASS` — lifecycle cleanup passes on all hosted targets. |
| A11 / renderer and migration | Exact-origin generated bridge exposes only named async operations; Node async fs workflows rewrite; sync/path/Buffer/generic IPC block. | Main frame allowed; child frame; cross-origin; unknown method; malformed payload; navigation; undeclared Node use. | Generated Kotlin/TS tests, migration inventory/golden fixture, real CEF fixture on all targets. | generated files bridge, migration manifest/golden output, real migration fixture. | Hosted migration `compatibility.json`, files evidence, and strict generated TypeScript/JavaScript checks. | `PASS` — typed migration and exact-origin CEF paths pass on all hosted targets. |
| A12 / platform failure policy | Missing no-follow/atomic/watch capability, permission changes, and sandbox restrictions fail with stable typed errors; no fallback root/backend. | Provider option unsupported; access denied; sandboxed process; unavailable watcher. | Platform capability probes and hosted target tests. | provider error mapping and fail-closed capability paths. | Hosted files evidence and platform provider verification. | `PASS` — advertised-target capability failures are typed and no fallback is used. |
| A13 / real evidence | Retained records prove real bytes, limits, link/reparse rejection, copy/move, watch cancellation/overflow, cleanup, and absence of absolute paths for all three targets. | Missing target; stale identity; skipped fixture; absolute path in record; stale handle survives close. | Hosted runtime/evidence recorder and strict governance. | `files-evidence.json`, migration `compatibility.json`, manifest/contracts. | Checked-in hosted manifest and retained artifacts for macOS arm64, Windows x64, and Linux x64. | `PASS` — fresh hosted records bind the final runtime and contract revision. |
| A14 / packaging/docs | Service library, generated output, provider metadata, migration matrix, docs, and package scans agree; test roots and absolute paths are not packaged. | Missing provider; stale schema; undeclared service; package scan finds workspace. | Package/governance/docs checks on all advertised targets. | module metadata, README, RFC, contracts binding, generated golden files. | Hosted package scans plus strict governance and generated checks. | `PASS` — repository metadata, docs, generated output, and package scans agree. |
| A15 / universal completion | Full implementation, tests, native/provider verification, migration, evidence, reviewed revision, and clean worktree land in one focused PR. | Any skipped required target/test or changed contract without evidence refresh. | Full PR diff review, local gates, three hosted targets, evidence import. | this focused RFC 0013 branch and final PR review. | Full PR diff, green three-target CI, fresh evidence import, and checked-in governance verification. | `PASS` — RFC 0013 is complete; squash merge is permitted and RFC 0014 remains blocked until the merge is verified. |
| A16 / persistent OS grants | Restart restoration via macOS bookmarks or Linux document-portal grants. | Sandbox/persistent-grant request. | Contract/code review only; no public API or support claim. | Non-goal above; reviewed in readiness record. | `NOT_APPLICABLE`. | `NOT_APPLICABLE` — explicitly excluded from this objective; exposing it would require a separate complete provider contract. |

## Contract review record

- Reviewed revision: contract revision `2026-09-30.1` on the
  `rfc/0013-scoped-filesystem` topic branch, before implementation code.
- Review pass: Codex readiness review, 2026-09-30; same contributor as the
  eventual implementation, identified separately from implementation work.
- Findings and dispositions:
  - Existing `KWebDialogs` already owns native picker UI and bounded picker
    handles. RFC 0013 therefore adds one `files` service and does not duplicate
    the picker or alias dialog tokens; accepted.
  - Ambient absolute paths, persistent bookmarks, Linux document grants,
    synchronous I/O, symlink traversal, and cross-device move fallback are
    excluded explicitly; accepted as scope boundaries rather than hidden
    behavior.
  - JDK 25 NIO is the only native boundary. Required no-follow, atomic-move,
    watcher, permission, and reparse/link behavior is falsifiable through the
    real provider suite on every advertised target; accepted pending evidence.
  - Renderer isolation is enforced twice: existing policy/bridge subject
    checks and an owner/navigation-bound in-memory capability registry;
    accepted.
- Probe references: pinned platform provider behavior is exercised by the
  existing JDK filesystem test boundary and by the RFC 0013 real-provider
  suite; XDG portal persistence and macOS security-scoped bookmark probes are
  intentionally not required because those APIs are not advertised.
- Decision: **READY**. The revised objective is complete, bounded, and
  falsifiable; the only excluded persistent-grant behavior is explicitly
  `NOT_APPLICABLE` and does not block implementation.

## Merge acceptance record

Final acceptance: A1–A15 are `PASS` against the reviewed PR revision and
fresh hosted evidence for macOS arm64, Windows x64, and Linux x64. A16 remains
`NOT_APPLICABLE` with the reviewed scope reason above. The PR is eligible for
squash merge; RFC 0014 must not start until the squash result is verified on
`main`.

## Non-goals

No ambient path API, system WebView fallback, generic IPC, Node runtime,
synchronous renderer filesystem API, persistent OS bookmark/document grant, or
transparent symlink/reparse traversal is published.
