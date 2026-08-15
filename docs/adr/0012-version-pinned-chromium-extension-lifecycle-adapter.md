# ADR 0012: Patch pinned CEF with a Profile-scoped Chromium extension adapter

- Status: Accepted
- Date: 2026-08-14

## Context

Objective 5.3 owns a verified, immutable package store and crash-safe journal,
but it deliberately does not mutate Chromium. Store commit is valid only after
the real Profile extension system has completed the requested lifecycle change.

The CEF 151 public binary exports no `UnpackedInstaller`, `ExtensionRegistrar`,
`ExtensionService`, or `ExtensionRegistry` symbols. Its removed Alloy extension
API cannot be restored as an MV3 implementation. Chromium 151 has an
experimental CDP `Extensions` domain, but its privileged load and uninstall
commands run only on a browser target, select
`DevToolsBrowserContextManager::GetDefaultBrowserContext()`, and provide no
reload command. It therefore cannot address KWebShell's explicit concurrent
Profiles or prove reload completion. Editing Chromium Preferences would bypass
permission, registry, renderer, Service Worker, DNR, and cleanup lifecycles.

CEF does expose the mapping KWebShell needs inside `libcef`:
`CefBrowserContext::FromCachePath()` locates an initialized request context,
`AsProfile()` exposes its real Chromium Profile, and `GetAnyRequestContext()`
can keep that context alive during asynchronous work. The Chromium APIs used by
Chrome itself already implement installation, update, reload, uninstall, and
registry observation.

## Decision

KWebShell maintains a small source patch against CEF commit
`be1e15d8892c064f0299ba18350236a9b272ce7f` and Chromium
`151.0.7922.109`. The patch adds a KWebShell-private `cef_kweb_*` C ABI to
`libcef`; it does not add a CEF C++ extension object model or modify Chromium's
extension implementation.

The adapter has these boundaries:

- The caller supplies an operation ID, operation kind, canonical Profile cache
  path, extension ID, expected version, managed directory, and one C callback.
  The adapter copies all input before returning from start.
- Every entry point requires the CEF UI thread. The KWebShell engine owns
  cross-thread posting and never exposes that requirement to Kotlin.
- The adapter resolves only an initialized, persistent, non-OffTheRecord
  `CefBrowserContext` at the exact supplied cache path. It retains one associated
  request context until completion or cancellation and waits for
  `ExtensionSystem::ready()` before accessing the registry.
- Install and update use `UnpackedInstaller::Load()`. Reload uses
  `ExtensionRegistrar::ReloadExtension()` with `ExtensionRegistryObserver` and
  `LoadErrorReporter::Observer`; successful install, update, and reload also
  observe the matching `ExtensionUserScriptLoader` and registry ready signal,
  so content-script injection is complete before the native operation is
  released. For extensions with a lazy background context it also uses
  the current activation-token-scoped `LazyContextTaskQueue` to queue a no-op
  task; Chromium's service-worker implementation invokes that callback only
  after the current worker global script has run and its listeners are
  registered. Before queueing the task after an update or reload, the adapter
  observes the Profile's `ProcessManager` until all prior workers for that
  extension have stopped, and validates the callback's process/version/thread
  identity against its currently tracked worker. A worker left over from an
  earlier activation cannot satisfy the callback or remove the new activation's
  listener during a late teardown. The observer is
  registered before checking an already-complete loader, closing both the
  activation-versus-script-loading and first-message wake races without a fixed
  delay. Uninstall uses
  `ExtensionRegistrar::UninstallExtension()` and completes only from its cleanup
  callback. Query reads `ExtensionRegistry`; no operation edits Preferences.
- One Profile and extension ID may have only one live mutating operation.
  Operation IDs are process-unique. Cancellation returns control to the caller
  immediately, but a dispatched mutation keeps its callback, observers, request
  context, and duplicate-operation guard until Chromium emits a terminal event.
  That callback reports `AMBIGUOUS`, so cancellation never claims rollback and a
  later reload cannot consume the cancelled operation's completion event.
- A result is `SUCCESS` only when the requested post-state is observable. It is
  `REJECTED` only when the pre-state is still provably unchanged. Every other
  result is `AMBIGUOUS`. In particular, reload failure is ambiguous after the
  registrar may have disabled the previous extension.
- The ABI has fixed-width values, sized structs, bounded UTF-8 spans, and an
  exact fingerprint derived from its schema and pinned upstream revisions.
  KWebShell discovers all symbols dynamically. Missing symbols or a mismatched
  fingerprint produce a typed native failure before an operation is dispatched.

The JVM coordinator is the transaction authority. It prepares a store journal,
releases the store lock, calls the runtime, then commits only a matching success
or aborts only a proven rejection. Ambiguous results and interrupted calls leave
the journal intact. On restart, query can prove install/update/uninstall state;
reload is retried because its before and after state may be identical.

The source and binary provenance boundary is also explicit. The checked-in
manifest fixes the CEF, Chromium, depot_tools, and Siso revisions, GN defines,
source preimages, patch digest, created-file postimages, exported symbols, and
ABI fingerprint. `build-custom-runtime.py` accepts only an exact host/target
pair and empty directories outside the repository. It checks out the pinned
revisions, verifies a clean CEF preimage, applies the patch without fuzz, builds
the platform-required targets, inspects the real PE/ELF/Mach-O exports, invokes
the fingerprint function, and emits a ZIP plus SHA-256 provenance metadata.

Artifact publication is a separate fail-closed gate. A declared artifact must
have the exact target-derived file name, HTTPS URL, byte size, archive SHA-256,
libcef SHA-256, ABI header digest, symbol evidence, and fingerprint evidence.
The publication task succeeds only when macOS arm64, Windows x64, and Linux x64
are all declared. The source-build Actions workflow builds and exercises each
runtime on a matching self-hosted machine; the published-runtime workflow then
downloads the checksum-pinned artifacts on GitHub-hosted machines and repeats
the full lifecycle suite.

## Consequences

- MV3 execution continues to belong to Chromium, including permissions,
  isolated worlds, Service Workers, content scripts, storage, and DNR.
- Multiple CEF request contexts can install the same extension ID independently
  without accidentally mutating the default Profile.
- Stock Spotify CEF artifacts remain valid for non-extension host tests, but an
  attempted product lifecycle operation fails with a typed ABI-missing error.
  They are never accepted as Objective 5.4 runtime evidence.
- Each CEF upgrade requires reviewing and rebuilding the patch, changing the ABI
  fingerprint when its contract changes, publishing checksum-pinned custom
  artifacts, and rerunning the full three-platform lifecycle suite.
- Cancellation and process loss may leave an intentional pending journal. This
  is an observable recovery state, not a silent success or rollback.
- The first macOS arm64 custom build proves install, update, reload, restart,
  Profile isolation, forced process-loss reconciliation, duplicate-operation
  rejection, cancellation recovery, and uninstall. This is implementation
  evidence, not publication: the capability remains `UNPUBLISHED` until the
  Windows and Linux artifacts pass the same checks and all three checksums are
  present in the manifest.

## Rejected alternatives

### Use CDP `Extensions.loadUnpacked`

Rejected because Chromium 151 restricts loading to the browser target, selects
the default BrowserContext instead of KWebShell's explicit Profile, and exposes
no registrar reload completion contract.

### Load hidden Chromium symbols from stock `libcef`

Rejected because the required symbols are not exported and their C++ ABI is not
stable across platform toolchains. Symbol interposition would also bypass CEF's
supported ownership boundary.

### Edit Preferences and copy extension files into the Profile

Rejected because Preferences are not the extension service transaction API.
This would race Chromium and skip live registry, permission, Service Worker,
renderer, and cleanup behavior.

### Treat timeout or cancellation as rejection

Rejected because Chromium may have changed state before the callback was lost.
The only safe result is an ambiguous journal followed by explicit query and
reconciliation.

### Reimplement `chrome.*` in Kotlin or JavaScript

Rejected because it would not provide Chromium's security model or compatibility
with current MV3 execution semantics.
