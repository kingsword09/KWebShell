# ADR 0005: Run one CEF engine lifecycle inside the JVM process

- Status: Accepted
- Date: 2026-08-12

## Context

The Compose-owned window and Chromium's windowed browser surface must belong to
the same native window hierarchy. A browser process hosted in a separate
executable cannot contribute an `NSView`, `HWND`, or X11 child that is owned by
the JVM process. IPC can coordinate two top-level windows, but cannot repair
focus, clipping, accessibility, workspace, or lifetime ownership enough to make
that arrangement a real native child.

CEF also has process-wide constraints. `CefInitialize` and `CefShutdown` form
one lifecycle per process and must run on the same initialization thread. CEF's
supported windowed integration uses a multi-threaded message loop on Windows
and Linux, but macOS requires integration with the AppKit main loop. Java AWT
adds a second threading constraint: Kotlin must orchestrate native startup and
shutdown on its event-dispatch thread, while the macOS calls themselves must
execute on AppKit's main thread.

Loading CEF by library name or discovering resources relative to the current
working directory would make the active Chromium build environment-dependent.
It could load a mismatched installation or silently fall back to default cache
and resource locations.

## Decision

KWebShell runs the CEF browser process in the JVM process. Renderer, GPU,
network, and utility processes remain normal Chromium subprocesses. This
objective establishes only the internal engine lifecycle; it creates no browser
and exposes no public `KWebEngine`.

### Binary boundary and loading

- The engine is a separate shared library with a versioned C header and opaque
  `uint64_t` engine/browser handles. Its exported surface contains the complete
  engine and browser operation set plus live counts. CEF, C++, FFM, AWT, and
  platform types do not cross the C ABI.
- The old Phase 2 session library and its echo event ABI are removed. The JDK
  25 FFM layer loads the exact engine library by absolute path and resolves the
  complete versioned engine and browser symbol set. There is no library-name
  search.
- Windows uses FFM `LoadLibraryExW` with
  `LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_DEFAULT_DIRS` to
  preload the exact CEF and engine DLLs before their Arena-scoped symbol
  lookups. This resolves adjacent CEF dependencies without changing `PATH` or
  the process-wide DLL directory; temporary preload references are released
  only after the scoped lookups own both modules.
- The caller supplies absolute paths for the CEF binary or framework,
  subprocess executable, resources, locales, existing root cache, and log.
  Kotlin and C++ both validate the platform layout and required resource files.
  On macOS resources and locales must be the declared framework Resources
  directory. On Windows and Linux they must belong to the directory containing
  the declared `libcef` binary.
- The runtime full version and CEF API hash must match the headers used to build
  the engine. A different loaded runtime is an explicit typed failure.
- After the first initialization attempt the validated runtime remains loaded
  for the rest of the JVM process. It is not unloaded and replaced after
  terminal shutdown; this keeps CEF wrapper and JVM native-library lifetimes
  aligned with the one-lifecycle rule. On macOS the engine dylib also remains
  loaded after its first load because its Objective-C category has installed
  methods in `NSApplication`; unloading their implementation would leave an
  invalid method table.

### Threading and message loops

- Kotlin performs create and close on the AWT event-dispatch thread and waits
  for serialized internal callbacks on a separate engine-owned dispatcher. A
  displayable but never-visible AWT peer remains owned for the whole engine
  lifecycle so AWT AutoShutdown cannot replace the dispatch thread or stop the
  AppKit event loop while no Compose window exists. The peer is disposed only
  after native shutdown and callback draining complete.
- Linux platform startup invokes `XInitThreads` while loading the engine,
  before `EventQueue` is touched. Failure is terminal; the implementation does
  not switch to a single-threaded or off-screen backend.
- Windows and Linux set `multi_threaded_message_loop = true` for the windowed
  backend. CEF owns its UI thread while initialization and shutdown are called
  from the same AWT thread.
- macOS explicitly loads the configured CEF framework, extends AWT's
  `NSApplication` with `CefAppProtocol`, and wraps `sendEvent`. It synchronously
  initializes CEF on AppKit's main thread with a stable program name. External
  command-line arguments are disabled; the browser-process callback installs
  only the fixed `disable-in-process-stack-traces` and `use-mock-keychain`
  switches. A JVM executable has no stable application Keychain identity, so
  allowing Chromium OSCrypt to query the login Keychain can block a worker in
  Security.framework and prevent `CefShutdown` from joining it. System
  Keychain-backed credential storage is not advertised by this objective and
  would require a separate security and migration contract. Shutdown is an
  asynchronous AppKit request so neither the initiating AWT event-dispatch
  thread nor AppKit's caller is synchronously blocked across the CEF teardown.
  Kotlin uses an AWT secondary loop when `close` itself runs on the
  event-dispatch thread.
- Before CEF is loaded, macOS sets Chromium's upstream
  `MACH_PORT_RENDEZVOUS_PEER_VALDATION=0` embedder policy exactly once. The
  spelling is Chromium's published environment contract. A JVM launcher and
  the pinned CEF helper do not share one code-signing identity, so KWebShell
  does not request same-identity peer enforcement. The build recreates the CEF
  framework hierarchy from a clean directory and verifies every canonical
  relative link. Distribution code signing belongs to the release boundary;
  running it inside the native compiler task would mutate the packaged runtime
  and does not address the JVM browser process. Because the environment contract only covers
  child rendezvous before FeatureList initialization, the browser process also
  disables `MachPortRendezvousValidatePeerRequirements` and
  `MachPortRendezvousEnforcePeerRequirements` in its fixed feature list. It
  disables `GatherProcessRequirementMetrics` as well: that UMA-only task asks
  Security.framework to validate the JVM executable on a best-effort worker
  marked `CONTINUE_ON_SHUTDOWN`, which can otherwise delay or prevent clean CEF
  shutdown. Failure to set any part is terminal. This does not relax KWebShell's exact
  runtime checksum, absolute helper path, fixed command-line, or single-runtime
  rules, and no alternate startup path exists.
- A cancellable `NSTimer` external pump implements CEF's delay replacement and
  reentrancy rules in the default and event-tracking run-loop modes. The
  999-millisecond shutdown grace period runs on a detached native thread so
  AppKit continues draining pending CEF work. That thread then posts shutdown
  as an asynchronous AppKit run-loop source rather than entering CEF from a
  timer callback. The external pump remains active until `CefShutdown`
  returns. Scheme-handler factories are cleared immediately before the
  process-wide shutdown. Autorelease-pool drains on AppKit are suppressed only
  while `CefShutdown` is active, matching the JVM/CEF teardown requirement.
  Pending pump callbacks retain safe ownership and become no-ops after
  invalidation.

### Ownership and events

- The native registry permits only one engine. A second live create fails, and
  every create after terminal shutdown fails because CEF cannot be restarted in
  that process. Initialization failure also makes the process terminal.
- `opened` is emitted only from
  `CefBrowserProcessHandler::OnContextInitialized`. `closed` is emitted only
  after `CefShutdown` returns. Both carry one engine handle and a contiguous
  sequence.
- Close on a thread other than the initialization thread fails without changing
  ownership. Kotlin close is idempotent; it does not release the FFM callback
  owner or close its shared Arena until native close and the terminal upcall
  complete.
- `OnAlreadyRunningAppRelaunch` is consumed without creating a Chrome window.
  Engine-only startup never turns a process-singleton notification into a
  browser or `chrome://newtab` fallback.

The integration contract runs in dedicated JVM processes because one process
cannot test both a successful lifecycle and a fresh lifecycle after shutdown.
It verifies success, duplicate create, wrong-thread and stale-handle close,
listener and FFM upcall failure, restart rejection, real process-singleton
initialization failure, direct event-dispatch-thread close, and zero live
engines. On macOS it also requires the peer environment, peer features,
process-requirement metrics, and fixed browser-policy markers exactly once
before `OnContextInitialized`, plus a native bundle contract that rejects
recursive, missing, and non-canonical framework links. A timed-out child is
diagnosed with `jcmd Thread.print -l` and a macOS `sample` before termination.
Linux uses an explicit Xvfb launcher.

## Consequences

- A later browser session can attach a real Chromium native child to a
  Compose/JVM parent without an overlay or cross-process window trick.
- Engine, browser, Profile, and Compose surface responsibilities remain
  separate. Objective 3.3 intentionally replaces the Phase 2 request echo with
  real browser callbacks; consumers migrate in one breaking change.
- Applications must provision one exact platform runtime layout and must plan
  for a single terminal engine lifecycle per JVM process.
- macOS shutdown cost belongs to the real CEF lifecycle and remains observable;
  `closed` cannot be sent early to hide it.

## Rejected alternatives

### Keep the browser process in the existing host executable

Rejected because the resulting native view belongs to another process and
cannot be a true child of the Compose/JVM window.

### Use off-screen rendering as the JVM integration path

Rejected because it changes the declared native-child rendering contract and
adds CPU/GPU pixel transfer. It remains a separately advertised future mode.

### Reuse the Phase 2 session ABI for engine events

Rejected because an engine is process-wide while a session is per browser.
Reusing the session handle and event fields would conflate ownership and make
the later real browser contract harder to reason about.

### Let CEF infer paths or search for an installed Chromium

Rejected because runtime provenance, resources, locales, subprocesses, and
storage would no longer be reproducible. A missing or mismatched declared path
must fail before browser semantics are exposed.
