# RFC 0015: External URL, reveal, trash, and shell integration

- Status: Accepted
- Priority: P0
- Owners: new `kweb-service-shell`, `kweb-service-files`, `kweb-bridge`, `kweb-electron-migration`
- Depends on: RFC 0003, RFC 0013
- Electron migration surface: `shell.openExternal`, `openPath`, `showItemInFolder`, `trashItem`
- Target mapping: `REWRITE`

## Objective

Publish one typed `kweb-service-shell` PAGE-scoped service for four narrowly
scoped desktop actions:

1. open an explicitly allowed external URI through the OS default handler;
2. open an RFC 0013 resource handle through its default file/directory handler;
3. reveal an RFC 0013 resource handle in the OS file manager; and
4. move an RFC 0013 file handle to the OS trash/recycle facility.

The service is an OS-shell adapter, never a generic process launcher. Renderer
code receives neither an absolute path nor an executable/command-line string.
Every renderer operation is exact-origin, main-frame, grant, current native
gesture, owner, and lifecycle checked through RFC 0003. Host code may use the
same typed operations, but it never bypasses native availability, file-handle
ownership, or trash verification.

This implementation objective deliberately excludes shortcut/link metadata,
shortcut creation, arbitrary executable launch, terminal launch, permanent
delete, unrestricted `file://` opening, and arbitrary absolute-path renderer
requests. Shortcut metadata remains a future complete capability objective.

## Non-goals and applicability

- No shell command, `cmd.exe`, PowerShell, terminal, `fork`/`exec`, or generic
  process API is exposed. Native providers receive structured URI/path values
  through their declared OS API only.
- No renderer operation accepts a raw path. `open-resource`, `reveal-resource`,
  and `trash-resource` accept only opaque RFC 0013 handle tokens routed through
  an internal files-owner resolver.
- No external `file:` URI is accepted by `open-external`; local resources use
  RFC 0013 handles so the files service retains root, link, owner, and grant
  authority.
- No permanent-delete fallback exists. If the OS trash facility is absent,
  rejects the item, or cannot prove the original resource is no longer at its
  old identity, the operation returns a typed failure and does not delete.
- No shortcut/link metadata or creation is advertised in this objective.
  Electron shortcut APIs remain an explicit migration blocker until a separate
  contract defines metadata, atomic publication, target validation, and tests.
- No silent folder-open substitution for reveal. Linux must use the declared
  file-manager reveal contract or return `shell.reveal-unavailable`.
- Headless sessions, missing desktop handlers, missing session D-Bus, or denied
  OS facilities return typed platform/unavailability errors; no alternate
  backend is selected.

## Implementation contract

Contract revision: `2026-10-01.1`.

### Common KMP and data contract

`KWebShell` is a PAGE-scoped service. The page owner supplies an internal
resolver that validates RFC 0013 handle ownership and grants before the native
provider sees a canonical resource. The resolver is not a service locator and
does not expose the path to common Kotlin or renderer JSON.

```kotlin
public enum class KWebShellResourceKind { FILE, DIRECTORY }

public enum class KWebShellAction {
    OPEN_EXTERNAL, OPEN_RESOURCE, REVEAL_RESOURCE, TRASH_RESOURCE,
}

public enum class KWebShellActionOutcome {
    HANDLER_ACCEPTED, MOVED_TO_TRASH,
}

public data class KWebShellConfiguration(
    public val allowedExternalSchemes: Set<String> = setOf("http", "https", "mailto"),
    public val allowDirectoryTrash: Boolean = false,
)

public data class KWebShellExternalUriRequest(public val uri: String)

public class KWebShellResourceHandle internal constructor(
    public val token: String,
) {
    public companion object {
        internal fun fromBridge(token: String): KWebShellResourceHandle =
            KWebShellResourceHandle(token)
    }
}

public data class KWebShellActionResult(
    public val action: KWebShellAction,
    public val outcome: KWebShellActionOutcome,
    public val resourceKind: KWebShellResourceKind?,
)

public interface KWebShell : KWebNativeService {
    public suspend fun openExternal(request: KWebShellExternalUriRequest): KWebShellActionResult
    public suspend fun openResource(handle: KWebShellResourceHandle): KWebShellActionResult
    public suspend fun revealResource(handle: KWebShellResourceHandle): KWebShellActionResult
    public suspend fun trashResource(handle: KWebShellResourceHandle): KWebShellActionResult
}
```

The service descriptor is `shell` version `1.0.0`, scope `PAGE`, and publishes
`open-external`, `open-resource`, `reveal-resource`, and `trash-resource`.
Each operation has schema version 1, renderer permissions
`native.shell.<operation>`, and `requiresUserGesture = true`. All three
desktop targets are advertised. A successful external/open/reveal result means
the declared OS handler accepted the request; it does not claim that an
external application rendered a page or completed a file operation.

The following bounds and normalization rules are normative:

| Field | Constraint and result |
|---|---|
| URI bytes | UTF-8, 1..8,192 bytes, no NUL, C0 controls, CR/LF, backslash, or invalid UTF-8 |
| URI scheme | ASCII scheme grammar, normalized lowercase, exact membership in the host allowlist |
| HTTP(S) | Absolute authority with a non-empty host; no user-info, empty host, encoded controls, or dangerous nested scheme |
| mailto | Non-empty RFC 6068-style address payload; no authority, controls, or nested dangerous scheme |
| custom scheme | Accepted only when explicitly present in `allowedExternalSchemes`; no default custom schemes and no command-like scheme names |
| external denylist | `file`, `javascript`, `data`, `vbscript`, `about`, `blob`, `filesystem`, `command`, `shell`, `chrome`, `devtools`, and platform command/settings schemes are rejected even if malformed input attempts to disguise them |
| handle token | Opaque RFC 0013 token, non-empty, bounded to 512 UTF-8 bytes; validated by the page's files-owner resolver |
| pending actions | At most 8 actions per page owner; excess admission fails `shell.busy` and is never queued without bound |
| directory trash | Disabled by default; an enabled host policy is required before a directory can reach the OS trash provider |

URI parsing rejects Unicode-confusable scheme/delimiter characters and percent
encoded controls before scheme dispatch. Unicode in permitted path/query data
is retained only after strict UTF-8 and URI parsing. `file:` is never converted
to a path, and a URI-looking value embedded in a rejected dangerous scheme does
not become a second dispatch attempt.

The internal RFC 0013 resolver contract is:

- `open-resource` and `reveal-resource` require an owner-valid handle with the
  RFC 0013 `METADATA` grant;
- `trash-resource` requires an owner-valid handle with both `METADATA` and
  `WRITE` grants;
- the resolver revalidates owner, page, origin, navigation, node kind, root,
  no-link/reparse identity, and grant immediately before the native call;
- for trash it retains the resolver's operation lock through the native call
  and post-call identity/absence verification; and
- no resolver failure exposes a path, native handle, token internals, or file
  contents in a bridge error, log, or retained evidence.

### Lifecycle, concurrency, and result precedence

The page owner serializes admission, resolver access, native action initiation,
and terminal publication. Provider work runs on bounded `Dispatchers.IO`/native
workers; AppKit main-thread, Windows COM/STA, and Linux GLib/D-Bus calls are
marshalled to their required platform contexts. CEF UI and renderer callbacks
never block on a native shell call.

An action has the states `ADMITTED`, `RESOLVING`, `NATIVE_PENDING`, `ACCEPTED`,
`TRASH_VERIFIED`, `FAILED`, and `CANCELLED`. Close/navigation/renderer
termination does the following:

- actions before the native boundary are cancelled with
  `service.owner-closed`/`service.cancelled`;
- once the OS API has accepted an open/reveal request, the accepted result is
  retained even if close begins immediately afterward;
- once trash mutation begins, the provider must report either
  `MOVED_TO_TRASH` after verification or `shell.trash-verification-failed`;
  close never changes either outcome into success or permanent deletion; and
- owner close releases every native owner and pending action exactly once.

`trash-resource` is successful only when the declared platform API reports
success and the original path identity is absent or the platform completion
callback reports the equivalent recycle/trash result. A pre-mutation failure
leaves the source untouched. A post-mutation verification failure is a typed
unknown outcome and never triggers a second deletion attempt.

### Errors, renderer policy, and migration

Stable shell errors are:

`shell.uri-invalid`, `shell.scheme-denied`, `shell.uri-too-large`,
`shell.handle-invalid`, `shell.handle-not-found`, `shell.handle-grant`,
`shell.handle-kind`, `shell.directory-not-allowed`, `shell.busy`,
`shell.handler-rejected`, `shell.reveal-unavailable`, `shell.trash-failed`,
`shell.trash-verification-failed`, `shell.native-unavailable`,
`shell.platform-unavailable`, and `shell.operation-cancelled`, together with
RFC 0003 policy/lifecycle errors.

Every bridge request requires the exact committed origin, main frame, live page
owner, declared operation grant, and one current native-verified gesture. A
navigation, origin change, renderer termination, policy revocation, or owner
close invalidates pending actions and prevents a copied handle token from being
used in a new page. Host calls bypass only renderer grant/frame checks; they do
not bypass handle ownership, URI policy, OS availability, or trash policy.

Electron major 44 migration rows are explicit:

| Electron operation | KWebShell mapping | Boundary |
|---|---|---|
| `shell.openExternal(uri)` | `open-external` | URI must pass the configured allowlist and native gesture policy |
| `shell.openPath(path)` | `open-resource` | Only an application adapter that already owns an RFC 0013 handle may rewrite it; raw renderer paths are blocked |
| `shell.showItemInFolder(path)` | `reveal-resource` | Same scoped-handle rule; no folder-open substitution |
| `shell.trashItem(path)` | `trash-resource` | Same scoped-handle rule, `WRITE` grant, and verified OS trash result |
| shortcut/link metadata | `BLOCKED` | No shortcut capability is advertised by this objective |
| arbitrary command/process/synchronous shell use | `BLOCKED` | No generic channel, executable, or command-line adapter exists |

The generated preload exports only named async shell operations and no Electron
`shell` object, `ipcRenderer`, path resolver, command channel, or native format.

### Platform implementation and feasibility

The shell provider uses one versioned C ABI behind an internal JDK 25 FFM
binding. Native objects, paths, and platform callbacks remain behind the ABI.
The C ABI accepts only validated UTF-8 URI/path buffers, fixed action enums,
opaque operation handles, bounded status records, and close/poll functions.

| Target | Exact provider/API | Required behavior and typed absence |
|---|---|---|
| Windows x64 | `ShellExecuteExW` for external/open, `SHOpenFolderAndSelectItems` for reveal, and `SHFileOperationW` with `FOF_ALLOWUNDO` for recycle-bin trash | Validate UTF-8/UTF-16 conversion, same-process shell acceptance, PIDL reveal, recycle-bin result, and post-trash absence; shell32/COM absence or rejection is typed and no command-line fallback is used |
| macOS arm64 | AppKit `NSWorkspace` URL/open APIs, `activateFileViewerSelectingURLs:`, and `recycleURLs:completionHandler:` | Calls are serialized on the AppKit main queue; completion/boolean status and post-trash file identity are required; missing workspace/session is typed |
| Linux x64 | GLib/GIO `g_app_info_launch_default_for_uri`/file launch, session D-Bus `org.freedesktop.FileManager1.ShowItems`, and `g_file_trash` | The active XDG desktop handler and session D-Bus are required; reveal never degrades to opening the parent directory; GIO trash result and absence are verified |

The existing native build boundary already compiles GLib/GIO providers for
Linux and Objective-C++ AppKit providers for macOS. Windows uses the same
MSVC/shell32/COM toolchain as the existing dialogs provider. Readiness probes
must be retained as native ABI tests and hosted independent-process/runtime
fixtures on all three targets before support is published. A headless or
handler-less runner is a tested typed-unavailability case, not a fallback.

### Evidence lifecycle and delivery

Evidence binds this RFC, the common contract, URI policy corpus, resolver
adapter, C ABI/header, platform native sources, FFM layouts, generated bridge,
Electron fixture, provider identity, OS-session facts, and package scan.
Retained records contain only action IDs, scheme IDs, target kind, bounded
status/outcome, provider identity, collision/absence facts, and fixture hashes;
they contain no URIs, paths, handles, command lines, or user data.

Changing any public signature, URI denylist/allowlist, handle/grant rule, native
mapping, generated schema/output, migration fixture, provider/runtime identity,
or package layout invalidates the affected target record. The sequence is:
common/provider tests; native ABI tests; real desktop handler/reveal/trash
fixtures; exact-origin CEF and migration tests; evidence aggregation; strict
checked-in governance; and final row-by-row review. The implementation,
documentation, evidence import, matrix, and final `Implemented` state land in
one focused PR.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common contract | Typed PAGE service publishes exactly four named async actions, bounded request/result models, and no path/command/native object in common or bridge output. | Empty/maximum URI, duplicate/unknown operation, malformed token, missing result fields, close during request. | Common contract tests, generated schema tests, package scan, all three targets. | `KWebShell.kt`, common contract tests, shell bridge schema. | Planned `shell-evidence.json`, generated output digest, contract revision `2026-10-01.1`. | `NOT_RUN` — readiness contract only. |
| A2 / URI policy | Only valid configured HTTP(S), mailto, or explicitly allowlisted custom schemes reach the OS handler; dangerous/nested/file/script/command values fail before native mutation. | HTTPS, mailto, custom allowlist, file, javascript, data, command, malformed URI, Unicode-confusable scheme, controls, oversized value, nested dangerous scheme. | Common URI corpus and JVM policy tests; native negative ABI tests; all three targets. | URI parser/policy, denylist tests, bridge request validation. | Planned redacted policy transcript and hashes; no URI values retained. | `NOT_RUN` — readiness contract only. |
| A3 / renderer authority | Renderer shell operations require exact origin, main frame, grant, current native gesture, live page owner, and valid navigation. | Missing grant, replayed gesture, child frame, cross-origin commit, navigation, copied token, owner close. | RFC 0003 policy tests and real CEF fixture on macOS arm64, Windows x64, Linux x64. | Page owner/bridge dispatcher, policy fixtures, native gesture path. | Planned migration evidence with authority booleans and no payload data. | `NOT_RUN` — readiness contract only. |
| A4 / RFC 0013 handle isolation | Resource actions resolve only owner-valid RFC 0013 handles with the required `METADATA`/`WRITE` grants and reject stale, cross-owner, link-swapped, wrong-kind, or directory-policy cases. | Stale handle, other page/origin, navigation, symlink/reparse swap, missing grant, file/directory mismatch, directory trash disabled. | Files-owner resolver tests plus shell/files integration on all three targets. | Internal resolver adapter, handle/grant checks, no-path review. | Planned resolver evidence records kind/grant/outcome only. | `NOT_RUN` — readiness contract only. |
| A5 / open external | OS default handler acceptance is returned only after the declared provider accepts a policy-approved URI; app completion is not falsely claimed. | Allowed URI, no handler, handler rejection, headless session, concurrent request, close race. | Real native provider fixture on all three targets. | Shell C ABI open-external path, platform provider tests. | Planned provider/outcome facts and runtime identity for each target. | `NOT_RUN` — readiness contract only. |
| A6 / open and reveal | Open/reveal use the exact OS file-manager contract for the resolved resource; reveal never substitutes parent-folder open. | File, directory, missing handler, stale resource, PIDL/URL conversion failure, headless session. | Real Windows shell32, macOS NSWorkspace, Linux FileManager1 fixtures. | Native providers, resolver lock, independent-process shell fixture. | Planned `open`/`reveal` outcome records with target kind and provider ID. | `NOT_RUN` — readiness contract only. |
| A7 / trash semantics | Trash uses the OS recycle/trash facility, verifies the result, and never permanently deletes or retries after an unknown outcome. | File success, directory policy deny/allow, collision, missing trash, permission denial, source mutation, post-call absence failure. | Real OS trash fixture and resolver integration on all three targets. | Native trash providers, post-operation identity check, fault/status mapping. | Planned trash outcome, collision, absence, and no-fallback facts with no paths. | `NOT_RUN` — readiness contract only. |
| A8 / lifecycle and bounds | Admission is bounded to eight actions, native calls are serialized per page, close/navigation cancels pre-boundary work, and terminal results are ordered exactly once. | 8/9 actions, slow handler, concurrent close, cancellation before/after native boundary, renderer termination. | JVM lifecycle tests, native live-count tests, CEF close/navigation fixture on all targets. | Page owner action registry, C ABI poll/close, lifecycle tests. | Planned live-count/terminal-state evidence and reviewed revision. | `NOT_RUN` — readiness contract only. |
| A9 / platform providers | JDK 25 FFM maps fixed actions to Win32, AppKit, and GLib/GIO/FileManager1 providers without command or backend fallback. | Native ABI mismatch, unavailable desktop, invalid provider status, missing session D-Bus, platform loss. | ABI tests and real provider runtime on Windows, macOS, Linux. | C header, FFM binding, `shell_win.cc`, `shell_mac.mm`, `shell_linux.cc`. | Planned provider IDs, runtime versions, native status names, and target records. | `NOT_RUN` — readiness contract only. |
| A10 / migration | Electron shell methods classify independently into named typed rewrites or explicit blockers; generated preload has no generic shell object/channel. | `openExternal`, `openPath`, `showItemInFolder`, `trashItem`, shortcut APIs, raw paths, sync/direct use, malformed payload. | Migration matrix, TS/JS generation, Electron 44 fixture, real CEF bridge on all targets. | Capability matrix, migration manifest, generated preload and integration fixture. | Planned compatibility reports and migration evidence bound to contract revision. | `NOT_RUN` — readiness contract only. |
| A11 / security/no disclosure | No renderer-visible path, executable, command line, URI payload, native handle, or user data appears in errors, logs, evidence, or generated output. | Injection characters, path traversal token, command string, URI secret, failure diagnostics, package scan. | Code/document review, redaction tests, package scan, generated-output tests. | Error mapper, evidence recorder, package/governance checks. | Planned redaction assertions and package report. | `NOT_RUN` — readiness contract only. |
| A12 / real evidence | Fresh independent-process/provider evidence proves policy, open/reveal/trash outcomes, no fallback, lifecycle cleanup, and provider identity on all advertised targets. | Missing target, stale runtime/contract, skipped fixture, headless typed-unavailability mismatch. | Hosted macOS arm64, Windows x64, Linux x64; aggregate and strict checked-in verification. | Native fixture/evidence recorder, aggregate manifest/contracts. | Planned three `READY` records and retained target artifacts. | `NOT_RUN` — readiness contract only. |
| A13 / packaging/docs | Service catalog, capability matrix, RFC, README, generated/native packages, exclusions, licenses, and evidence bindings agree. | Stale schema, test roots packaged, shortcut capability accidentally advertised, absolute path in package. | Governance, package scan, docs, generated output, `git diff --check`. | Service catalog, module metadata, docs, migration matrix, package report. | Planned package/governance artifact and reviewed diff. | `NOT_RUN` — readiness contract only. |
| A14 / universal completion | Complete implementation, tests, native/runtime evidence, migration, docs, evidence import, reviewed revision, clean worktree, and one squash PR. | Skipped target/test, stale evidence, dirty worktree, partial API, changed contract without refresh. | Full PR diff review, local gates, all required hosted jobs and evidence checks. | Focused RFC 0015 PR and final acceptance record. | Planned final PR, manifest, artifacts, and merge revision. | `NOT_RUN` — readiness contract only. |
| A15 / shortcut exclusion | Shortcut metadata/creation remains absent and unsupported in this objective. | Shortcut path, `.lnk`/alias/desktop-entry request, arbitrary link target, migration use. | Code/catalog/matrix review and negative migration tests. | No shortcut operation/schema; explicit migration blocker. | No shortcut provider/evidence record is permitted. | `NOT_APPLICABLE` — explicitly excluded; reviewed in the readiness record. |

## Contract review record

- Reviewed revision: `2026-10-01.1` on branch
  `rfc/0015-shell-integration`, before implementation code.
- Review pass: Codex readiness review, 2026-10-01. The same contributor may
  implement the objective; this is a separate contract review pass, not an
  independent-person approval.
- Findings and dispositions:
  - The original proposal combined external opening, file-manager actions,
    trash, and shortcut metadata. This objective is one complete shell-action
    slice; shortcut metadata is explicitly excluded rather than published as a
    stub.
  - Raw renderer paths and Electron path strings are not an acceptable
    authority. The contract routes resource actions through an RFC 0013
    owner/grant resolver and keeps all native paths behind the JVM/native
    boundary.
  - URI schemes are host-allowlisted. `file`, script, data, command, browser
    internal, and platform command/settings schemes are denied before provider
    dispatch; custom schemes are never implicitly trusted.
  - Trash is irreversible at the application layer. The provider must use the
    OS recycle/trash API, verify the result, and report unknown outcome without
    permanent-delete or retry fallback.
  - Linux reveal cannot be implemented honestly as “open the parent folder.”
    The contract requires `org.freedesktop.FileManager1.ShowItems` and returns
    typed unavailability when no file-manager service exists.
  - Windows, macOS, and Linux use stable declared APIs already compatible with
    the repository's native boundary: shell32/COM, AppKit NSWorkspace, and
    GLib/GIO plus session D-Bus. Existing dialogs CMake gates demonstrate the
    required MSVC, Objective-C++, and GIO build boundaries; hosted real-runtime
    probes remain mandatory before support is promoted.
  - Directory trash is disabled by default and requires explicit host policy;
    this prevents a broad recursive operation from being inferred from a file
    grant or an Electron path string.
- Probe references: existing `kweb-service-dialogs` native CMake/provider
  boundaries, RFC 0013 handle lifecycle/security tests, and the planned shell
  ABI/provider fixture on all three targets. No command-line or fallback probe
  is accepted as support evidence.
- Decision: **READY**. The first objective is one bounded deliverable, every
  advertised behavior has an exact platform API or typed unavailability path,
  and every guarantee maps to a falsifiable matrix row. No implementation code
  has started on this revision.

## Merge acceptance record

`NOT_RUN` — complete only after implementation, fresh three-target evidence
import, final PR diff review, and green platform/governance checks.

## Explicit exclusions

Shortcut metadata and creation, arbitrary process launch, terminal launch,
permanent deletion, raw renderer paths, unrestricted `file://` opening, and
fallback shell/file-manager providers remain unsupported.
