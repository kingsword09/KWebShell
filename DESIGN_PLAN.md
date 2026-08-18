# KWebShell Design Plan

## 1. Project Positioning

KWebShell is a Kotlin Multiplatform browser shell for Compose and Chromium. It is not a thin HTML widget. The target product combines:

- A hardware-accelerated native Chromium surface.
- Compose Desktop integration on Windows, macOS, and Linux.
- Persistent, isolated browser Profiles.
- Navigation, cookies, storage, downloads, permissions, and custom protocols.
- DevTools and CDP for inspection and automation.
- A typed Kotlin/JavaScript bridge.
- A real Manifest V3 extension runtime with explicit capability reporting.

The public product name is **KWebShell**. The implementation must not expose CEF as the public API because the engine is an implementation boundary and may evolve independently.

## 2. Hard Product Decisions

### 2.1 Engine

CEF backed by a pinned Chromium release is the desktop engine. The project uses the Chrome bootstrap process model and an explicit Alloy-style native child for the primary Compose-embedded page on every platform. This is required because CEF forces Alloy style for an external macOS parent view. A version-pinned CEF/Chromium patch connects that embedded `WebContents` to Chromium's real Profile and extension services; the removed CEF Alloy extension API is never used.

CEF's public extension API is not a sufficient MV3 product surface. The native layer must add a small, version-pinned adapter to Chromium's extension service. Chromium must own Service Workers, extension permissions, content scripts, isolated worlds, extension origins, and network rule evaluation.

### 2.2 No backend fallback

KWebShell does not silently fall back to WebView2, WKWebView, WebKitGTK, OSR, or a reduced extension runtime. A declared capability is served by its declared backend or returns a typed `KWebError` with platform, capability, and remediation details.

The system WebView may exist as a separately named future product, but it is not a backend of KWebShell and cannot share this API contract.

### 2.3 Breaking API evolution

The project is pre-1.0. Breaking changes are preferred over compatibility shims when a contract is incorrect. There are no deprecated aliases, silent migrations, or dual behavior paths unless a future design explicitly approves one.

### 2.4 Complete public features only

A capability is absent from the public API until its native implementation, Kotlin API, packaging, and tests are complete on every platform that advertises it. No stub, fake success, placeholder callback, or deferred implementation is acceptable.

## 3. Layered Architecture

```text
Compose Desktop JVM
        |
        v
 kweb-compose / kweb-desktop
        |
        v
  kweb-core contracts
        |
 JDK 25 FFM + C ABI
        |
        v
  kweb-cef-native (C++)
        |
        v
 CEF Chrome bootstrap / patched Chromium Profile
        |
        +-- Browser / GPU / Network / CDP
        +-- Chromium ExtensionService (MV3)
        +-- Native child and popup window hosts
```

### 3.1 Modules

| Module | Responsibility | Must not own |
|---|---|---|
| `kweb-core` | Common Kotlin lifecycle, navigation, Profile, capability, and typed error contracts | CEF objects, Compose, platform windows |
| `kweb-compose` | Compose wrappers, layout, toolbar, popup placement, state rendering | Chromium lifecycle and native callbacks |
| `kweb-desktop` | Desktop session, window, page, DevTools, CDP, and Profile orchestration | C++ implementation details |
| `kweb-bridge` | Typed Kotlin/JavaScript RPC and generated schemas | Arbitrary renderer evaluation as a transport |
| `kweb-extensions` | Manifest parsing, package model, policy model, capability matrix, conformance fixtures | Reimplementing Chromium's extension runtime |
| `kweb-cef-native` | CEF initialization, browser hosts, native surfaces, C ABI, extension adapter | Kotlin business state |
| `kweb-runtime-pack` | Reproducible CEF binaries, resources, locales, licenses, and platform packaging | Runtime version selection at application startup |

## 4. Public Contracts

The common API must use immutable value types, `suspend` operations, `Flow`/`StateFlow` for lifecycle state, and typed errors. Resource ownership must be explicit with `close`/`use` semantics.

Required common concepts:

```text
KWebEngine
KWebProfile
KWebPage
KWebWindow
KWebDevTools
KWebBridge
KWebExtensionManager
KWebCapability
KWebError
```

The common module describes behavior, not the implementation mechanism. CEF handles and callbacks remain behind opaque native handles; FFM types stay internal to the desktop binding layer.

## 5. Rendering and Windowing

### 5.1 Native child surface

This is the default rendering mode. Chromium renders to an explicit Alloy-style native child surface under the Chrome bootstrap with GPU acceleration. Compose owns the surrounding layout and overlays. The style is identical on all three platforms; KWebShell does not silently create a Chrome-style child where one platform happens to allow it. This path is required for production performance and extension-capable pages backed by the patched Chromium Profile integration.

Acceptance criteria:

- No per-frame CPU pixel copy for the normal page surface.
- Resize, focus, DPI, IME, keyboard, mouse, touchpad, drag/drop, and accessibility events work on all three desktop platforms.
- Native child lifetime is tied to `KWebPage` and Profile shutdown.
- A destroyed Compose node cannot receive native callbacks.

### 5.2 Off-screen rendering

OSR is an explicit capability for special composition cases. It is not selected implicitly and it is not the extension-compatible path. Its API must report its CPU/GPU tradeoffs and reject extension surfaces that require Chrome-style browser UI unless a complete native host exists.

### 5.3 Extension UI surfaces

Action popups, options pages, DevTools, context menus, offscreen documents, and side panels are browser surfaces, not ordinary page content. Each advertised surface requires:

- A native host/window strategy.
- Bounds, focus, close, and owner handling.
- Permission and Profile routing.
- A cross-platform test.

## 6. Profiles and Lifecycle

Each `KWebProfile` maps to one persistent CEF request context and one Chromium Profile. Extension state, cookies, cache, storage, permissions, and service-worker registrations are Profile-scoped.

Required lifecycle:

```text
Engine.create
  -> Profile.open
  -> ExtensionManager.restore
  -> Page.create
  -> Navigation / DevTools / Bridge
  -> Page.close
  -> Profile.flush
  -> Engine.shutdown
```

The implementation must reject use-after-close, cross-Profile browser targeting, and operations submitted after engine shutdown with typed errors. Shutdown must wait for native callbacks and child processes to exit.

## 7. DevTools and CDP

DevTools is a first-class feature, not a debugging-only convenience.

Required capabilities:

- Open DevTools in a native window tied to a page.
- Expose a configurable CDP endpoint or pipe.
- Provide page/session target discovery.
- Support navigation, Runtime, Network, Page, DOM, Log, Performance, and Target domains needed by the test suite.
- Allow DevTools extensions only through the same declared extension capability matrix.
- Keep remote debugging disabled unless explicitly enabled by configuration; bind and authenticate it safely.

## 8. Typed Host Bridge

The bridge is a versioned RPC protocol between page JavaScript and Kotlin. It must provide:

- KSP-generated method schemas and TypeScript declarations.
- Request IDs, cancellation, timeouts, structured errors, and backpressure.
- Origin/page/Profile validation before dispatch.
- No arbitrary native pointer or CEF object exposure.
- Deterministic behavior when a page navigates or a bridge owner closes.

The bridge must not be used as a substitute for `chrome.*` extension APIs. Extensions run through Chromium's extension runtime and permissions.

## 9. Manifest V3 Extension System

### 9.1 Package and security pipeline

Supported sources are an unpacked directory and CRX3. ZIP can be accepted only as a local transport format and must be unpacked into a managed directory before installation.

The pipeline is:

```text
read -> validate manifest -> verify package/ID -> permission review
     -> copy to immutable Profile store -> Chromium install
     -> observe lifecycle -> expose action/UI state
```

Required security properties:

- CRX3 publisher signature verification.
- Extension ID derived from the verified public key.
- Path traversal and symlink checks while unpacking.
- Atomic install/update and crash-safe rollback-free replacement.
- Host permission and optional permission review before activation.
- Explicit policy for `nativeMessaging`, `debugger`, `proxy`, `management`, and incognito access.
- No remote code execution or unreviewed native host invocation.

### 9.2 Runtime responsibilities

Chromium's internal extension runtime must provide:

- MV3 Service Worker startup, event dispatch, idle suspension, wake-up, and messaging.
- Content scripts and isolated worlds.
- Extension-origin resource loading.
- Permission enforcement.
- `chrome.runtime`, `storage.local`, `scripting`, `tabs`, `windows`, `action`, context menus, and `declarativeNetRequest` as advertised.

The adapter must attach these Chromium-owned services to the primary Alloy-style embedded `WebContents`. It must not depend on CEF's removed Alloy extension API or report stock CEF behavior as MV3 support.

Kotlin/C++ code supplies the embedder-specific browser model and UI hosts:

- Tab/window mapping for `chrome.tabs` and `chrome.windows`.
- Action icon, badge, tooltip, and popup placement.
- Extension context-menu routing.
- Options, DevTools, offscreen, and side-panel windows.
- Native messaging policy and process lifecycle.

### 9.3 Capability matrix

The matrix is versioned with the engine and exposed by the API. Initial rows are:

```text
core:      runtime, storage.local, scripting, content_scripts, isolated worlds
browser:   tabs, windows, action, contextMenus
network:   declarativeNetRequest, explicitly scoped webRequest
ui:        action popup, options, DevTools, offscreen, sidePanel
native:    nativeMessaging, debugger, proxy, management (policy-controlled)
```

An extension that requests a capability outside the matrix receives an installation or API error. It must not be silently downgraded.

## 10. Kotlin Toolchain / Amper Role

Kotlin Toolchain can be used to orchestrate KMP modules, Compose configuration, Kotlin/Native targets, cinterop definitions, resource packaging, and runtime artifact provisioning. It cannot replace CEF's C++/Chromium build, GN/Ninja, CMake, platform SDKs, or CEF patch maintenance.

The first desktop vertical slice should use Compose Desktop JVM plus JNI. Kotlin/Native C ABI targets are added after the native ABI and lifecycle contracts stabilize. Windows CEF binaries and Kotlin/Native MinGW must be treated as separate toolchains; do not assume direct MSVC library linkage works.

CEF runtime artifacts must be pinned by Chromium milestone, architecture, checksum, license manifest, and resource layout. They must be built or downloaded outside the Kotlin compiler and then consumed as a versioned runtime pack.

## 11. Phased Delivery Plan

Every phase below is a separate objective and a separate commit. The next phase cannot begin with failing tests from the previous phase.

### Phase 0: Repository and build foundation

Deliver:

- Project metadata and module layout.
- Reproducible Kotlin/Gradle or Kotlin Toolchain build decision.
- CEF version/architecture manifest.
- CI matrix for Windows, macOS, and Linux.
- Test and packaging conventions.

Acceptance:

- Clean checkout builds all advertised Kotlin modules.
- CI reports platform-specific prerequisites instead of skipping silently.
- Runtime artifact checksums are verified.

### Phase 1: Native CEF host

Deliver:

- CEF initialization and shutdown.
- Browser process, renderer process, GPU process, and subprocess packaging.
- Chrome bootstrap and explicit Alloy-style native child browser.
- Navigation, resize, focus, input, DPI, and lifecycle callbacks.

Acceptance:

- A real page renders with GPU acceleration on all three platforms.
- Runtime inspection proves the primary embedded browser is Alloy style under the Chrome bootstrap, with windowless rendering disabled.
- Process failures and shutdown are observable typed errors.
- Native surface tests pass without OSR pixel copying.

### Phase 2: Kotlin/JNI contract (historical, superseded by Objective 3.3)

Deliver:

- A versioned C ABI with integer opaque handles, sized configuration/event
  structures, stable status codes, and no exported C++ or CEF type.
- A native-owned asynchronous session queue that accepts navigation requests,
  viewport changes, and close commands without claiming browser completion.
- A dynamically registered JNI adapter with exact UTF-8/UTF-16 conversion,
  JVM thread attachment, and bounded global-reference ownership.
- Kotlin lifecycle/error types, `StateFlow` state observation, one serialized
  callback dispatcher per session, and idempotent `close` semantics.

The former Phase 2 session was an internal transport and ownership contract. It
emitted `navigation_requested`, not `navigation_committed`, and did not publish
`KWebEngine` or `KWebPage`. Objective 3.3 deletes that contract and supplies the
complete real Chromium vertical slice below.

Acceptance:

- No CEF C++ types appear in common Kotlin.
- The C ABI and JNI shared libraries are built and loaded as real native
  artifacts on Windows, macOS, and Linux; JVM tests do not replace them with a
  mock implementation.
- At least 256 repeated create/request-navigation/resize/close cycles leave the
  native live-session count at zero.
- Concurrent command/close races return only declared status codes and never
  leak, deadlock, reuse a stale handle, or cross session ownership.
- JNI callbacks arrive on the declared Kotlin dispatcher, preserve non-ASCII
  text exactly, and no callback can begin after `close` returns.
- Use-after-close fails immediately with a typed `KWebError`; a repeated Kotlin
  `close` is idempotent and never calls native code with a stale handle.

### Phase 3: Profiles and web platform features

Deliver:

- Persistent Profile creation/open/flush.
- Cookies, storage, downloads, permissions, custom schemes, and request interception.

Acceptance:

- Data survives restart in the same Profile.
- Different Profiles cannot see each other's data.
- Custom protocol and permission tests run on all platforms.

#### Objective 3.1: Native persistent Profile and storage lifecycle

This objective establishes the real CEF/Chromium Profile boundary before a
public `KWebProfile` is exposed. The global CEF context owns only an explicit
`root_cache_path`; each browser receives an asynchronously initialized,
non-global `CefRequestContext` whose cache path is an absolute direct child of
that root. Nested Profile paths are rejected because the Chrome bootstrap
otherwise creates a unique OffTheRecord Profile instead of the requested
disk-backed Profile. The Chromium-owned `Default` Profile name is also reserved
case-insensitively to prevent database ownership conflicts on macOS and Windows.

Acceptance:

- The browser is created only after its Profile request context initializes,
  and runtime inspection proves that it owns that exact context.
- Session-cookie persistence is enabled explicitly, and the cookie store flush
  completes before browser close begins.
- Three separate real CEF processes execute `Profile A write -> Profile B
  expect-absent -> Profile A read` against a controlled HTTPS origin.
- Profile A retains both `localStorage` and a session cookie across restart;
  Profile B observes neither value.
- After clean Chromium shutdown, each tested Profile contains its declared
  Preferences, Cookies, and Local Storage state on disk. A missing artifact,
  mismatched context, rejected flush, timeout, or invalid path fails the test.
- The same contract runs on macOS, Windows, and Linux. The public
  `KWebProfile` remains absent until the native Profile lifecycle is connected
  through the real JNI/browser session boundary.

#### Objective 3.2: In-process JVM/CEF engine lifecycle

This objective replaces no Phase 2 browser semantics and exposes no public
`KWebEngine`. It establishes the internal process-wide CEF engine that a later
real browser session will own. CEF runs in the Compose/JVM process so native
child views can belong to the same Windows, AppKit, or X11 window hierarchy;
an out-of-process browser host is not used as an embedding fallback.

Acceptance:

- A small versioned C ABI creates and closes exactly one CEF engine lifecycle
  per process without exporting CEF, C++, JNI, AWT, or platform-window types.
- Kotlin supplies explicit absolute paths for the pinned CEF runtime,
  subprocess executable, resources, locales, root cache, and log. Missing,
  mismatched, relative, or unsupported paths fail before CEF initialization;
  no loader search or system Chromium fallback is used.
- Native initialization and shutdown are orchestrated from the AWT
  event-dispatch thread. macOS synchronously initializes CEF on AppKit, then
  requests shutdown asynchronously so the AWT event-dispatch thread remains
  available until AppKit reports the real terminal callback; the external
  message pump runs on AppKit throughout. The macOS browser process uses a
  fixed embedder command-line policy with mock Keychain storage so a JVM that
  has no application Keychain identity cannot leave OSCrypt blocked during
  shutdown. Before loading CEF, macOS also installs Chromium's explicit
  unsigned-embedder policy by setting the upstream
  `MACH_PORT_RENDEZVOUS_PEER_VALDATION=0` contract exactly once. The JVM host
  and CEF helper do not share one code-signing identity, so enforcing a
  same-identity peer requirement is not a valid embedder policy. Every build
  still recreates the versioned CEF framework links from a clean directory and
  rejects recursive or non-canonical links before runtime tests. Distribution
  code signing remains a release-boundary operation and is not performed by the
  native compiler task. The browser-process command line also disables Chromium's
  `MachPortRendezvousValidatePeerRequirements` and
  `MachPortRendezvousEnforcePeerRequirements` features while preserving every
  existing disabled feature; the environment value alone only covers child
  processes before FeatureList initialization. It also disables Chromium's
  diagnostic-only `GatherProcessRequirementMetrics` task. That best-effort task
  performs Security.framework validation against the JVM executable and is
  declared `CONTINUE_ON_SHUTDOWN`, so it is invalid work for this unsigned
  embedder and can otherwise block `CefShutdown`. Failure to install any part of
  the policy is terminal; runtime checksum, absolute helper paths, and fixed
  arguments remain mandatory, and no retry or alternate backend is used.
  Arbitrary host arguments remain disabled.
  Windows and Linux use CEF's supported multi-threaded windowed message loop.
- The engine emits one ordered `opened` event only after
  `CefBrowserProcessHandler::OnContextInitialized`, and one terminal `closed`
  event only after `CefShutdown` returns. No callback begins after Kotlin
  `close` completes.
- Duplicate create, wrong-thread close, callback failure, initialization
  failure, stale handle, and post-close operations return declared typed
  errors. Reinitialization after terminal shutdown is rejected because CEF
  supports one lifecycle per process.
- A dedicated JVM integration process loads the real native libraries and
  pinned CEF runtime, opens the engine, observes the real context callback,
  closes it cleanly directly from the AWT event-dispatch thread, and leaves the
  native live-engine count at zero on macOS, Windows, and Linux. On macOS the
  process also proves that all four macOS process-policy markers are emitted
  exactly once before the context callback. A timeout captures a JVM thread
  dump and, on macOS, a native process sample before terminating the child.
  Linux runs under an explicit Xvfb launcher.
- The Phase 2 echo session is intentionally deleted by Objective 3.3. Any
  consumer of its request-only events must migrate to the real browser event
  contract in the same breaking change; no compatibility alias remains.

#### Objective 3.3: In-process Chromium browser session in an AWT surface

This objective makes the intentional breaking replacement of the Phase 2 echo
session. A JVM session owns one real CEF browser, one persistent Profile request
context, and one Alloy-style native child attached to a displayable AWT parent.
Navigation, loading, failure, resize, and close observations come from Chromium
and the platform window hierarchy; request-echo and simulated viewport events
are deleted. The contract remains internal, and no public `KWebEngine`,
`KWebPage`, or Compose API is exposed by this objective.

Acceptance:

- The obsolete standalone session shared library, fake worker thread, and
  `navigation_requested`/`viewport_changed` events are removed. Browser
  operations extend the versioned engine C ABI and use the same native engine
  instance and JNI ownership path; there is no compatibility alias, fallback
  browser, or second session implementation.
- Browser creation requires a live engine, a displayable AWT parent, an
  absolute direct-child Profile path under the configured root cache, a valid
  initial URL, and positive bounds. It creates a non-global
  `CefRequestContext` with persistent session cookies, waits for context
  initialization, and verifies that the created browser owns that exact
  context.
- The embedded browser is a windowed, hardware-accelerated native child with
  explicit Alloy runtime style. Windows owns a dedicated child-container
  `HWND` beneath the Compose window and parents Chromium to that container,
  Linux uses the Canvas X11 drawable, and macOS resolves the AWT top-level peer
  to its AppKit `NSWindow` and owns a dedicated intermediate `NSView`. An unavailable or
  incompatible native peer fails with a typed status; OSR or a hidden top-level
  Chromium window is never substituted.
- Ordered events report browser-created, main-frame navigation-started,
  address-committed, loading-state changes, load completion or failure,
  resize-applied, fatal renderer failure, and terminal browser-closed directly
  from CEF or verified platform operations. UTF-8 payloads, HTTP/error codes,
  and navigation flags cross the C ABI and JNI boundary without loss.
- Navigation and resize commands are serialized onto the CEF UI thread.
  Resizing changes the real native child bounds and a platform query proves the
  applied size. Commands racing with close return only declared terminal or
  closing statuses and never target a released browser.
- Close flushes the Profile cookie store before requesting native destruction.
  Windows and Linux initiate close through `CefBrowserHost`. Windows moves
  KWebShell's complete child container beneath a dedicated hidden top-level
  shutdown root from `DoClose` while Chromium remains beneath the same
  immediate container, then returns `false`; CEF retains its accepted
  destruction state and posts `WM_CLOSE` to the isolated root, whose WndProc
  destroys the root and lets Win32 recursively deliver the browser child's
  `WM_NCDESTROY`. Moving or directly closing the Chromium HWND, forwarding
  `WM_CLOSE` to that child, and synchronously destroying it from `DoClose` are
  prohibited because they race Chromium/Aura teardown. macOS removes the CEF `NSView` from its
  AppKit hierarchy, which causes CEF to deliver `OnBeforeClose`. The callback
  clears browser ownership. The three-task quiescence barrier starts only after
  CEF releases its final `SessionClient` owner, which occurs after browser-host
  and platform-delegate teardown. Platform surface release, registry removal,
  and the single terminal event are deferred across that barrier so Chromium
  finishes its current destruction stack and posted Widget cleanup before Kotlin
  can start another lifecycle. The FFM callback owner is released only after
  that event has returned. Engine close is rejected
  while any browser is live; the Kotlin engine retains its handle and remains OPEN so the
  caller can close the browser and retry. Clean browser then engine shutdown
  leaves both live counts at zero.
- A dedicated JVM integration process creates a real visible AWT host and real
  CEF browser, loads a controlled page, observes navigation and load callbacks,
  navigates to a second non-ASCII URL, resizes the native child, closes it, and
  then shuts down CEF. The test rejects callback reordering, Profile mismatch,
  non-Alloy/windowless rendering, missing native parentage, post-close callback,
  leaked handles, and persistence artifacts missing after shutdown.
- The real integration contract passes locally on macOS with the pinned
  Temurin and CEF artifacts and runs as mandatory GitHub Actions acceptance on
  macOS arm64, Windows x64, and Linux x64 under Xvfb. C header conformance,
  exported-symbol checks, native unit tests, Kotlin tests, formatting, and
  packaging checks remain green.

### Phase 4: DevTools, CDP, and typed bridge

Deliver:

- DevTools native window.
- CDP endpoint/pipe.
- Generated Kotlin/TypeScript bridge.

Acceptance:

- CDP can discover a page and execute the required domains.
- A generated bridge method round-trips typed values, errors, cancellation, and timeout.
- Remote debugging security tests pass.

#### Objective 4.1: Explicit loopback CDP endpoint

This objective adds the first real DevTools/CDP vertical slice without
advertising a public browser API. The engine ABI gains one breaking
configuration field for a fixed remote-debugging port. Port `0` disables CDP;
`1024..65535` enables one fixed endpoint. The native engine always constrains
the endpoint to loopback and never selects an ephemeral port, opens a public
interface, or falls back to another debugging transport.

Acceptance:

- ABI, JNI, Kotlin configuration, status mapping, and native layout tests agree
  on the explicit port field and reject every value outside `0` or
  `1024..65535`.
- When enabled, the real CEF browser process exposes `/json/version` and
  `/json/list` on the configured port. Every advertised HTTP and WebSocket URL
  is IPv4 or IPv6 loopback only; a routable address is a test failure.
- A real browser target is discovered by URL and a WebSocket CDP session
  executes `Runtime.evaluate`, returning an exact Unicode page title. The test
  runs against the same in-process Alloy child and persistent Profile as the
  Phase 3 contract.
- When disabled, no CDP listener is created. Fixed-port collision is a typed
  startup failure; the implementation does not silently choose another port.
- The endpoint is closed before engine terminal completion. macOS, Windows, and
  Linux use the same ABI and security contract; Linux integration remains under
  Xvfb. No public `KWebDevTools` or bridge API is exposed until the next
  objective implements its complete lifecycle.

#### Objective 4.2: Native DevTools window host

This objective adds the first real DevTools window lifecycle for an existing
embedded browser. The page remains an Alloy, windowed native child. CEF 151's
DevTools front-end is a separate Chrome-style native window; forcing it to
Alloy is rejected by CEF and is therefore forbidden. The host keeps the
DevTools browser private behind the browser handle and exposes only typed
open/close operations and lifecycle events.

Acceptance:

- ABI v4, JNI, Kotlin status mapping, and exported symbols agree on explicit
  `browserOpenDevTools` and `browserCloseDevTools` operations.
- Opening a ready page creates a real CEF DevTools native window and emits one
  `DEVTOOLS_OPENED` event. The front-end is a Chrome-style window because that
  is the CEF-supported DevTools implementation; the primary page remains Alloy
  and windowed.
- A duplicate open fails with `devtools-already-open`; closing a missing target
  fails with `devtools-not-open`; no operation silently falls back to an
  embedded page, OS debugger, or second browser backend.
- With CDP enabled, `/json/list` exposes a `devtools://` target while the
  window is open and removes it after `browserCloseDevTools`.
- Closing the page while DevTools is open closes the DevTools browser first,
  emits `DEVTOOLS_CLOSED`, and only then emits the page `CLOSED` terminal event.
- Native CTest, real macOS integration, Linux/Xvfb integration, and Windows
  compilation validate the same ABI and lifecycle contract.

#### Objective 4.3: Origin-scoped generated typed bridge

This objective adds the first complete typed Kotlin/JavaScript bridge without
publishing the unfinished Compose page API. The transport uses CEF's browser- and
renderer-side message routers over a dedicated ABI v5 event channel. Bridge
activation requires an explicit Kotlin dispatcher and one exact normalized HTTP
or HTTPS origin; it is never inferred from the initial URL and never falls back
to arbitrary renderer evaluation.

Acceptance:

- `kweb-bridge` owns a closed version-1 JSON request and typed failure protocol
  independent of CEF. `kweb-bridge-codegen` strictly validates its schema and
  deterministically emits serializable Kotlin models, handler/dispatcher source,
  a TypeScript client, and browser-ready JavaScript.
- Generated Kotlin is compiled with the serialization plugin. Generated
  TypeScript compiles with the pinned TypeScript 7 compiler under
  `--strict --noEmit`; Kotlin keywords, built-in/generated type collisions,
  duplicate names, unknown fields, and unknown types fail generation.
- ABI v5, exported symbols, JNI bindings, native status mapping, and Kotlin
  ownership agree on a separate bridge event sink and one-shot success/failure
  operations. Incomplete configuration, malformed origins, invalid JSON,
  duplicate responses, and late responses fail with exact typed statuses.
- The renderer installs CEF query functions only for an explicitly enabled
  browser's main frame at the exact configured origin. Child frames, DevTools,
  standalone hosts, cross-origin main pages, and unconfigured browsers never
  receive the bridge. Every routed V8 context is released exactly once.
- Kotlin handlers run asynchronously in a browser-owned coroutine scope, never
  on the CEF UI or JNI callback thread. Timeout, `AbortSignal`, navigation,
  renderer-context destruction, and browser close cancel the exact request;
  response versus cancellation is an atomic one-winner race.
- Real macOS CEF integration proves structured Unicode round trip, typed business
  error, sanitized unexpected error, unknown method, timeout, abort, navigation
  cancellation, page-close cancellation, frame/origin isolation, and zero leaked
  browser/handler ownership. The identical root `check` contract runs on
  Linux/Xvfb and Windows through GitHub Actions.
- The bridge remains host RPC and is not used to emulate Manifest V3 `chrome.*`.
  No public Compose browser API is advertised by this internal attachment slice.

### Phase 5: MV3 core runtime

Deliver:

- Chromium extension-service adapter and runtime install path.
- Manifest validation, CRX3 verification, Profile persistence, permission policy.
- Service Worker, runtime, storage, scripting, content scripts, action, popup, and DNR.

Acceptance:

- A minimal MV3 Service Worker installs, wakes, handles events, sends messages, suspends, and restores.
- Real extensions exercise every published core capability.
- Restart, update, uninstall, Profile isolation, and denied-permission tests pass.

#### Objective 5.1: Manifest V3 package verification and permission review

This objective creates the trusted input boundary for the extension runtime. It
does not install an extension into Chromium, start a Service Worker, or emulate
`chrome.*`. An unpacked directory or CRX3 file is accepted only after its
manifest, resources, public key, package identity, archive structure, and
requested permissions have passed the complete validator. Every unsupported or
denied capability fails with a typed error; no path-based ID, unsigned package,
partial manifest, or policy downgrade is accepted.

Acceptance:

- A new `kweb-extensions` KMP/JVM module exposes the closed package model,
  strict Manifest V3 parser, published permission review, and typed verification
  errors without exposing CEF or filesystem implementation types to common code.
- Manifest validation rejects non-v3 versions, malformed Chrome versions,
  invalid resource paths, invalid host patterns, incomplete Service Worker and
  content-script declarations, malformed action/options/DNR metadata, duplicate
  or invalid extension IDs, unknown JSON fields, and all unsupported MV2
  background fields.
- Unpacked packages require a regular directory, a regular `manifest.json`, a
  non-empty base64 SubjectPublicKeyInfo `key` containing an RSA key of at least
  2048 bits or a P-256 key, and an extension ID derived from the first 16 bytes
  of SHA-256(public-key-DER) using Chromium's `a`-through-`p` alphabet. Any
  symlink, traversal path, non-regular resource, duplicate entry, or manifest
  outside the package root fails before acceptance.
- CRX3 verification parses the bounded little-endian container and protobuf
  header, rejects Chromium-forbidden EOCD/Zip64 header tokens, validates the
  signed-data CRX ID, verifies every RSA PKCS#1 v1.5 SHA-256 or
  ECDSA-P256-SHA256 proof over Chromium's exact `CRX3 SignedData` context and
  archive bytes, rejects malformed/duplicate/unknown critical fields, and
  validates every contained ZIP entry without extracting untrusted files.
- Permission review distinguishes package-admissible API permission names from
  host access; it rejects the policy-controlled `debugger`, `management`,
  `nativeMessaging`, `proxy`, and `webRequestBlocking` permissions, and universal
  host wildcards, `<all_urls>`, or `file://` unless the caller opts into those
  policies. Unknown permissions never disappear from the result.
  `API_PERMISSION` means only that the package is admissible for the Objective
  5.1 boundary; it does not report a running `chrome.*` implementation. Runtime
  capability is published only by the Objective 5.2 conformance matrix.
- Unit tests cover valid and invalid manifests, ID derivation vectors, traversal
  and symlink attacks, CRX3 RSA/ECDSA signatures, tampered headers/archive,
  malformed protobuf/ZIP/keys, permission denial, and bounded package limits.
  The same JVM test contract runs on macOS, Linux, and Windows CI; no Chromium
  install or runtime capability is advertised until Objective 5.2 adds its real
  integration conformance.

#### Objective 5.2: Alloy Manifest V3 core runtime conformance

This objective proves the minimum real Chromium extension-runtime path before a
product installation API is designed. A checked-in Manifest V3 fixture runs in
the pinned CEF 151 Chrome bootstrap and the same Alloy-style native child used by
the embedded browser. Chromium owns content-script injection, the isolated
world, runtime messaging, the Service Worker lifecycle, and `storage.local`.
Kotlin and the host page do not emulate any `chrome.*` API.

The native host's `--kweb-mv3-core-self-test` and
`--kweb-mv3-extension-path` arguments are private conformance controls. Their
use of Chromium's `--load-extension` switch is limited to the test fixture and
is not an install, update, reload, persistence, or product fallback contract.
Those lifecycle operations remain absent from the public API until the Profile
extension-service adapter is complete.

Acceptance:

- The shared unpacked fixture passes the Objective 5.1 package verifier and
  derives the fixed Chromium extension ID
  `dhhnhmffjehhodphofnkingncijnaona`; no private signing key is stored in the
  repository.
- MV3 self-test modes are mutually exclusive with all other host self-tests.
  They require an absolute, canonical fixture directory containing regular
  `manifest.json`, `worker.js`, and `content.js` files, reject Chromium's comma
  path separator, and fail with an observable typed startup error.
- The browser process removes inherited extension-loading switches and supplies
  only the validated conformance fixture. Background networking and component
  updates are disabled and machine proxy settings are bypassed so the test is
  hermetic. The production JNI engine continues to disable inherited Chromium
  command-line arguments and exposes no 5.2 extension installation API.
- A static `document_start` content script runs in Chromium's isolated world on
  `https://kwebshell.test/`, observes no page-world JavaScript marker, and uses
  the real `chrome.runtime.id`, `chrome.runtime.getManifest()`, and
  `chrome.runtime.sendMessage()` APIs.
- A real MV3 Service Worker handles the message, reads and writes
  `chrome.storage.local`, becomes idle, and is restarted with a different
  in-memory instance ID before handling the next message. No synthetic worker
  lifecycle or Kotlin bridge participates.
- Profile `alpha` records message counts `1/2`, then restores the same extension
  state after a complete CEF restart and records `3/4`. Profile `beta` begins at
  `1/2`, proving that extension state is isolated by Profile.
- After each run, Chromium has persisted non-empty Preferences, Extension State,
  Extension Scripts, Local Extension Settings, and Service Worker database
  files. Browser, native window, Profile flush, CEF loop, and shutdown events
  remain ordered and complete.
- `kweb_mv3_core_conformance_test` runs against a real renderer and native Alloy
  child on macOS locally and on macOS, Linux/Xvfb, and Windows in GitHub Actions.
  The versioned `docs/mv3-capability-matrix.md` advertises only the exact API
  surface demonstrated by that test.

#### Objective 5.3: Profile-scoped immutable extension package store

This objective creates the crash-safe filesystem transaction boundary required
by the Chromium lifecycle adapter. It does not load an extension, mutate
Chromium preferences, or expose a public install API. A verified source becomes
an immutable, content-addressed object inside one explicit Profile store. A
journal records the intended install, update, reload, or uninstall; the active
pointer changes only after a later runtime adapter reports real Chromium
success.

Acceptance:

- The JVM store accepts only an absolute dedicated Profile store root and uses
  a cross-process file lock for every read or mutation. Symlink roots, malformed
  layouts, concurrent writers, unknown metadata, and unavailable atomic moves
  fail with exact typed errors; there is no non-atomic move fallback.
- Unpacked input is copied without following links into a same-filesystem
  staging directory, then re-verified through Objective 5.1. Source mutation
  after provisioning cannot change the managed object.
- CRX3 input is signature-verified before extraction. The exact verified ZIP
  payload is extracted with bounded paths into staging, and its signing public
  key is written into the managed manifest so Chromium derives the verified ID
  when loading the managed directory. The extracted snapshot passes the same
  unpacked verifier before it can be committed.
- Managed objects use a deterministic SHA-256 tree digest and the path
  `objects/<extension-id>/<version>/<digest>`. An existing object is reused only
  after its package identity and complete tree digest match; object contents
  are never edited in place.
- Preparing a package creates one atomic transaction record per extension.
  `INSTALL`, `UPDATE`, and `RELOAD` are derived from the current active object.
  Downgrades and same-version/different-content replacements fail. Preparing
  uninstall leaves the active pointer intact until Chromium confirms removal.
- Commit atomically writes or deletes the active record and then closes the
  journal. Abort preserves the previous active record. Reopening the store
  removes incomplete staging artifacts, finalizes journals whose active state
  already proves commit, and retains ambiguous journals for explicit runtime
  reconciliation instead of guessing success or rollback.
- Garbage collection refuses to run while any transaction is pending and
  deletes only verified objects not referenced by an active record. It never
  follows links or removes data outside the dedicated store root.
- JVM tests cover unpacked and CRX3 provisioning, deterministic reuse, install,
  update, reload, uninstall, abort, downgrade/version conflict, corruption,
  symlink escape, stale staging recovery, journal recovery, lock contention,
  garbage collection, and two-Profile isolation on macOS, Linux, and Windows.
- The store types remain internal until Objective 5.4 connects their transaction
  state to the real Profile `ExtensionService`; the capability matrix continues
  to mark package lifecycle as `UNPUBLISHED`.

#### Objective 5.4: Profile-scoped Chromium extension lifecycle adapter

This objective connects the immutable package store to the real Chromium 151
extension lifecycle. It uses a maintained source patch against the pinned CEF
commit, not the removed CEF extension API, CDP's default-Profile-only extension
commands, preference-file mutation, or a JavaScript emulation. The patched
`libcef` resolves the exact initialized `CefBrowserContext` by its canonical
Profile cache path and invokes Chromium's `UnpackedInstaller`,
`ExtensionRegistrar`, `ExtensionRegistry`, and unload cleanup on the CEF UI
thread. The engine consumes only a narrow versioned C ABI discovered at runtime.

Acceptance:

- A checked-in patch manifest pins CEF commit
  `be1e15d8892c064f0299ba18350236a9b272ce7f`, Chromium
  `151.0.7922.109`, every source preimage, the patch digest, the adapter ABI
  fingerprint, and the exact custom runtime artifacts. Patch verification
  rejects a dirty source tree, an upstream mismatch, an offset/fuzzy apply, an
  unexpected generated diff, or an artifact without the adapter exports.
- The patched `libcef` exports only a C-compatible `cef_kweb_*` lifecycle ABI.
  It exports no Chromium, CEF C++, STL, allocator, Profile pointer, or callback
  object. Struct sizes, integer values, UTF-8 spans, calling convention,
  operation ownership, callback lifetime, cancellation, and the ABI fingerprint
  are contract-tested from both C and C++.
- `INSTALL` and `UPDATE` call `UnpackedInstaller::Load()` for the exact managed
  object directory and complete only after Chromium reports the resulting ID,
  version, path, and enabled registry state. `RELOAD` calls
  `ExtensionRegistrar::ReloadExtension()` and observes the matching registry
  load or load-error event, then waits for the matching
  `ExtensionUserScriptLoader` to report its complete content-script set and for
  the registry ready signal before declaring success. For extensions with a
  lazy background context, it also queues a no-op task through Chromium's
  activation-token-scoped `LazyContextTaskQueue`; its service-worker callback
  fires only after the current worker global script has executed and registered
  its listeners. Before queueing that task after an update or reload, the
  adapter observes the Profile's `ProcessManager` until every prior worker for
  the extension has stopped, then validates the callback's worker identity
  against the currently tracked instance. This closes both the Chromium
  activation-versus-script-loading race and the late prior-worker teardown
  race without a fixed delay. `UNINSTALL` calls
  `ExtensionRegistrar::UninstallExtension()` and waits for its asynchronous
  cleanup callback. No operation writes Chromium Preferences directly.
- Every accepted asynchronous operation completes exactly once at the native
  boundary, including after caller cancellation; a cancelled coroutine ignores
  that terminal callback while native ownership remains alive until Chromium
  settles. Results distinguish `SUCCESS`, `REJECTED`, and
  `AMBIGUOUS`: only proven Chromium success may commit the store; only a proven
  unchanged Chromium state may abort it; timeout, cancellation after dispatch,
  context shutdown, callback loss, and a failed reload after the old extension
  was disabled retain the journal for reconciliation.
- Profile lookup accepts only the canonical cache path of an initialized,
  persistent, non-OffTheRecord `CefBrowserContext`. The adapter retains an
  associated request context for the operation lifetime, waits for the
  extension system readiness signal, rejects duplicate operations for one
  extension, and never substitutes the default or last-used Profile.
- `QUERY` reports the exact installed state, ID, manifest version, canonical
  path, and enabled/disabled/terminated/blocklisted/blocked state for one
  Profile. Startup reconciliation commits a matching install/update or absent
  uninstall, aborts only a state proven identical to the previous active
  object, retries reload because static state cannot prove it ran, and fails
  closed on every conflicting state.
- The native engine and JNI bridge validate the adapter fingerprint before the
  first lifecycle operation, preserve callback and handle ownership across CEF
  threads, expose typed status/result values to Kotlin, reject late or duplicate
  callbacks, and cancel live operations before browser or engine teardown. A
  stock CEF runtime fails immediately with
  `extension-runtime-abi-missing`; there is no command-line, CDP, system-WebView,
  or preference-edit fallback.
- The JVM lifecycle coordinator serializes one operation per extension and
  performs `prepare -> runtime -> commit/abort/retain` without holding the
  filesystem lock across Chromium work. It validates runtime identity against
  the prepared managed object and keeps ambiguous journals observable for an
  explicit later reconcile call.
- Native unit tests cover ABI validation, UTF-8 and path bounds, wrong thread,
  duplicate/cancel/late completion, outcome mapping, Profile mismatch, and
  teardown. JVM tests cover install, update, reload, uninstall, rejection,
  ambiguity, timeout, cancellation, restart reconciliation, corruption, and
  two-Profile isolation without treating a mock as runtime evidence.
- A real lifecycle conformance extension is installed, wakes its MV3 Service
  Worker, updates without losing `storage.local`, reloads with a new worker
  instance, survives a complete process restart, and is fully uninstalled.
  The test also proves Profile isolation and crash recovery against custom CEF
  runtime artifacts on macOS arm64, Windows x64, and Linux x64 in mandatory
  GitHub Actions jobs. Package lifecycle remains `UNPUBLISHED` until all three
  jobs pass against artifacts whose checksums appear in the runtime manifest.

Implementation evidence as of 2026-08-15:

- The pinned patch, ABI v1 adapter, native dynamic loader, JNI ownership,
  package-store coordinator, source-tree verifier, custom-artifact verifier,
  and cross-platform source builder are implemented without a stock-CEF or CDP
  fallback.
- A source-built macOS arm64 `libcef` passes exact export and fingerprint
  inspection plus the complete real lifecycle test: install, update, reload,
  restart, `storage.local`, two Profiles, forced process loss, duplicate and
  cancelled operations, reconciliation, and uninstall.
- `customRuntimeArtifacts` remains empty and package lifecycle remains
  `UNPUBLISHED`. The dedicated source-build and published-artifact Actions
  workflows must produce and accept Windows x64 and Linux x64 evidence before
  all three artifact records may be added and the publication gate enabled.

### Phase 6: Extension browser UI

Deliver:

- Options pages, context menus, DevTools pages, offscreen documents, side panels, and native messaging policy where supported.

#### Objective 6.1: Options page native-child surface conformance

This objective proves the narrow, real Chromium operation that later product
options-page hosting will depend on: an MV3 extension's declared `options_ui`
document loads in the existing Alloy native-child browser for the exact
persistent Profile that loaded the extension. It does not expose a product
install-and-open API before Objective 5.4 has checksum-pinned custom runtime
artifacts on every advertised target.

Acceptance:

- The checked-in MV3 conformance extension declares one strict
  `options_ui.page`, includes that regular file in the package snapshot, and
  its options document proves its extension origin, `chrome.runtime.id`,
  `chrome.runtime.getManifest()`, and the `storage.local` state written by the
  Service Worker during the same run.
- A test-only host mode first completes the existing content-script and
  Service Worker conformance sequence, then navigates the *same* Alloy native
  child directly to the exact `chrome-extension://<derived-id>/options.html`
  URL. It verifies the expected extension ID, manifest name, persisted storage
  value, path, request context, successful main-frame load, and ordered clean
  shutdown.
- The test mode accepts no arbitrary extension page URL, does not create an
  overlay, a hidden top-level browser, an OSR surface, a synthetic
  `chrome.runtime` object, a CDP tab, or an `openOptionsPage()` fallback. A
  navigation failure, wrong title/result, duplicate terminal event, or timeout
  is a typed test failure.
- Package-boundary tests verify the fixture's `options_ui` metadata and asset
  snapshot. Native unit tests cover strict mode parsing and fixture validation.
  The real native-child conformance test runs on macOS arm64, Windows x64, and
  Linux x64 with the pinned stock CEF runtime.
- The capability matrix records only this direct test-bootstrap navigation as
  runtime evidence. Action popups, Chrome-driven `openOptionsPage()`, and the
  public lifecycle-based options host remain `UNPUBLISHED` until their own
  complete custom-runtime conformance objectives pass.

Implementation evidence as of 2026-08-16:

- The fixture declares `options_ui.page=options.html` and package-boundary
  validation proves the declared options page is a regular package resource.
- The native `options` self-test completes the Service Worker idle/restart
  sequence, then the same Alloy native child loads the exact extension options
  URL. It records and verifies navigation, load, extension identity, manifest
  identity, persisted `storage.local` state, and clean shutdown.
- Native CTest passed the full real Chromium sequence locally on macOS arm64
  and in GitHub Actions on macOS arm64, Windows x64, and Linux x64. This
  objective is complete; its narrow runtime evidence does not publish a
  product options-page API.

#### Objective 6.2: Action popup document and action-state native-child conformance

This objective proves the narrow Chromium operation that a future action-popup
host will require: an MV3 extension's declared `action.default_popup` document
loads in the existing Alloy native child after the same Profile's Service
Worker has written global `chrome.action` state. It does not claim that
KWebShell renders a Chrome toolbar button, processes a user gesture, or exposes
a product action-popup API before the profile-scoped custom runtime artifact
gate is complete on every advertised target.

Acceptance:

- The checked-in MV3 conformance extension declares one strict
  `action.default_popup` and default title. Its Service Worker updates the
  global action badge text and title only after the corresponding
  `storage.local` write completes; its popup proves extension origin,
  `chrome.runtime.id`, manifest action metadata, persisted storage value, and
  the exact global `chrome.action.getBadgeText()` and `getTitle()` values.
- A test-only `action-popup` host mode completes the existing content-script
  and Service Worker idle/restart sequence, then navigates the *same* Alloy
  native child directly to the exact fixed popup URL. It verifies fixed
  extension identity, manifest identity, action state, path, successful
  main-frame load, ordered terminal events, and clean shutdown.
- The internal native state machine has one explicit extension-page lifecycle
  for the options and action-popup modes, with no caller-supplied extension
  URL. Wrong URLs, wrong page results, wrong surface identity, duplicate
  navigation or terminal events, failed loads, and timeouts are typed test
  failures. It creates no toolbar window, overlay, separate browser, OSR
  surface, synthetic `chrome.action`, CDP tab, or fallback implementation.
- Package-boundary and native unit tests validate the action fixture metadata,
  required regular assets, and strict mode parsing. The real native-child
  conformance test runs on macOS arm64, Windows x64, and Linux x64 with the
  pinned stock CEF runtime.
- The capability matrix may record only the fixed direct popup document and
  global action-state sequence as runtime evidence after all three targets
  pass. Action icons, user-gesture popup behavior, toolbar placement, tab-
  scoped action state, and public action APIs remain `UNPUBLISHED` pending
  their own complete custom-runtime objectives.

Implementation evidence as of 2026-08-16:

- The fixture declares `action.default_popup=popup.html` and a default title.
  Its Service Worker persists each message count, writes and reads the matching
  global action badge and title, and its popup independently verifies the
  manifest declaration, action state, extension identity, and storage state.
- The native `action-popup` self-test completes the Service Worker
  idle/restart sequence, loads the exact popup URL in the same Alloy native
  child, and verifies ordered navigation, load, terminal result, flush, and
  shutdown events through one extension-page lifecycle state machine.
- Full `check` passed against the pinned real CEF runtime locally on macOS
  arm64 and in GitHub Actions on macOS arm64, Windows x64, and Linux x64. This
  objective is complete; its narrow runtime evidence does not publish an action
  toolbar or popup-host API.

#### Objective 6.3: Context-menu model and command-dispatch native-child conformance

This objective proves the real Chromium/CEF dispatch path that a future
Compose context-menu host will consume: an MV3 Service Worker registers one
`chrome.contextMenus` item, a right-button event in the existing Alloy native
child produces a CEF menu model containing that exact item, and selecting the
model command reaches the extension's `onClicked` listener. It does not claim a
product menu renderer, arbitrary host item composition, or OS-native visual
integration before those have their own lifecycle and UI tests.

Pinned CEF `151.3.16` routes every Alloy context-menu request directly to
`CefMenuManager::CreateDefaultModel()` before Chrome creates a
`RenderViewContextMenu`. That CEF-only model contains navigation/editing items
but never Chromium's Profile-scoped extension items. Objective 6.3 therefore
extends the existing checksum-pinned custom CEF patch: only an explicit
`kweb-chrome-context-menu` browser-process switch lets the same Alloy
`WebContents` continue through Chrome's real menu builder and CEF observer.
Stock CEF must fail the capability gate; injecting the missing item in the
host is not an admissible substitute.

Acceptance:

- The checked-in MV3 conformance extension requests `contextMenus`, removes
  stale test items, creates one fixed page-context item with a fixed ID, title,
  and document URL pattern, and awaits successful registration before the core
  Service Worker response completes. Registration is lazy and occurs only when
  the controlled page identifies `context-menu` mode, so unrelated core,
  options, and action tests never touch an unpublished API. Its `onClicked`
  listener verifies the exact item and controlled page URL, atomically
  increments a storage-backed click count, and publishes the exact result
  through `storage.onChanged` to the content script.
- A strict test-only `context-menu` host mode first completes the existing
  content-script and Service Worker idle/restart sequence, then sends a real
  CEF right-button down/up pair at a fixed page coordinate in the same windowed
  Alloy native child. A synthetic DOM `contextmenu` event, OSR input, JavaScript
  invocation of the listener, and CDP input are forbidden substitutes.
- `CefContextMenuHandler` recursively inspects the actual menu model, requires
  exactly one enabled and visible item with the fixed title, records its real
  command ID and page coordinates, and completes the CEF run-menu callback with
  that command. `OnContextMenuCommand` must observe the same command and return
  `false` so Chromium's default extension-command dispatcher handles it; the
  worker click, storage update, content-script result, menu dismissal, and
  ordered shutdown must all complete exactly once.
- Missing or duplicate items, wrong page URLs, invalid command IDs, duplicate
  menu callbacks, command mismatch, selection rejection, missing dismissal,
  wrong extension result, renderer failure, and timeout are typed failures.
  The test creates no product menu window and does not silently select another
  menu item.
- The source patch manifest pins the exact upstream context-menu preimage and
  patch digest. Without the private switch, custom CEF retains upstream Alloy
  behavior. The conformance host requests the switch only in strict
  `context-menu` mode and records that backend choice.
- Package-boundary and native unit tests validate the permission metadata and
  strict mode parsing. Stock CEF on every hosted target must complete the real
  core/idle/right-click sequence, then fail with exactly zero matching items
  and no model-selection or dispatch event. The positive sequence must pass a
  checksum-pinned custom CEF runtime on macOS arm64, Windows x64, and Linux x64
  before any capability status changes.
- The capability matrix may record only the fixed page-context item model and
  command-dispatch sequence. Product menu rendering, multiple/nested extension
  items, checked/radio state, editable/link/media contexts, dynamic updates,
  and public host APIs remain `UNPUBLISHED` until their own objectives pass.

#### Objective 6.4: DevTools extension page and panel lifecycle conformance

This objective proves that a declared MV3 `devtools_page` runs inside the real
Chrome-style DevTools window that CEF opens for the inspected Alloy page. It
must use Chromium's Profile-scoped ExtensionRegistry and DevTools frontend
plumbing; navigating an extension URL directly, injecting a normal extension
page, or emulating `chrome.devtools.*` is not an admissible substitute.

Acceptance:

- The checked-in MV3 conformance extension declares one fixed
  `devtools_page`. Its page must execute in the hidden DevTools extension
  frame, verify its extension origin and `chrome.runtime.id`, call the real
  `chrome.devtools.inspectedWindow.eval` against a marker in the inspected
  Alloy page, and create one fixed panel through the real
  `chrome.devtools.panels.create` callback. The callback result, inspected
  value, and panel metadata are written atomically to `storage.local`; the
  existing content script verifies the exact record and publishes one fixed
  terminal result.
- A strict test-only `devtools` host mode completes the existing MV3 core
  Service Worker idle/restart sequence, opens a real CEF DevTools top-level
  window for the same browser, waits for the content-script result produced by
  the DevTools extension page, closes that DevTools window, and only then
  flushes the Profile and closes the inspected Alloy browser. The DevTools
  browser must be a CEF popup with Chrome runtime style, a native window, and
  windowed rendering; the inspected browser remains Alloy and is never
  replaced.
- The native state machine rejects missing or duplicate DevTools creation,
  wrong Profile/origin, wrong inspected value, invalid panel callback data,
  duplicate storage terminal events, DevTools close before the result, late
  DevTools callbacks, renderer failures, and timeout with typed terminal
  errors. It creates no second Alloy browser, direct extension-page
  navigation, CDP/JavaScript listener invocation, synthetic success, or
  fallback surface.
- Package-boundary and native unit tests validate the `devtools_page` fixture
  metadata and strict mode parsing. The real native-child conformance test
  runs against stock CEF as a positive DevTools-extension gate and against the
  checksum-pinned custom CEF artifact when enabled. The capability remains
  `UNPUBLISHED` until macOS arm64, Windows x64, and Linux x64 complete the
  exact positive lifecycle.
- The capability matrix may record only the fixed `devtools_page` execution,
  `inspectedWindow.eval`, and `panels.create` callback sequence. Arbitrary
  panel UI, panel selection/visibility, sidebars, recorder integrations,
  DevTools protocol domains, and public DevTools-extension APIs remain
  `UNPUBLISHED` pending their own complete objectives.

Implementation evidence as of 2026-08-16:

- The fixture declares `devtools_page=devtools.html`. Its real hidden DevTools
  extension frame verifies its origin and ID, evaluates the inspected Alloy
  page's fixed marker through `chrome.devtools.inspectedWindow.eval`, and
  receives a non-null `chrome.devtools.panels.create` callback with the exact
  panel event surface before one atomic `storage.local` publication.
- The strict native `devtools` mode opens the CEF Chrome-style popup, defers
  contract validation until the native handle is attached, verifies the same
  Profile, records the real `devtools://` frontend load, waits for the
  extension result, closes DevTools, then flushes the Profile and closes the
  inspected Alloy child. It creates no direct extension-page navigation or
  synthetic DevTools API.
- The full native CEF suite passed on macOS arm64 with the pinned stock runtime
  and the checksum-pinned custom runtime. GitHub Actions run `31955852372`
  passed the same positive stock-CEF sequence on hosted macOS arm64, Windows
  x64, and Linux x64. The checksum-pinned custom runtime has local macOS arm64
  evidence; hosted custom-runtime coverage remains separate until published
  artifacts exist.
- The repository-level Gradle `check` passed on macOS arm64 against the pinned
  stock runtime: all 41 actionable tasks completed, the native suite passed
  10/10, and the final real MV3 conformance run completed in 303.11 seconds.

#### Objective 6.5: Offscreen document lifecycle conformance

This objective proves the real MV3 `chrome.offscreen` document lifecycle in
Chromium's Profile-scoped extension service. The host must not emulate the API,
navigate the main Alloy child to `offscreen.html`, or create a second hidden
browser. The document is created and destroyed only by Chromium in response to
the extension Service Worker calling the real API.

Acceptance:

- The checked-in MV3 fixture declares the `offscreen` permission and one fixed
  `offscreen.html` document. The worker verifies the manifest identity and
  exact permissions before creation. The document verifies its exact extension
  origin, path, runtime ID, `runtime.getURL()` result, and a real `DOMParser`
  result for the fixed parser marker. It sends one fixed ready message from the
  offscreen context; the worker validates the sender URL/ID and returns an
  explicit acknowledgement. It does not call `runtime.getManifest()`, which
  Chromium deliberately excludes from `offscreen_extension` contexts.
- The strict test-only `offscreen` host mode runs the existing core Service
  Worker sequence. The first worker probe must observe
  `chrome.offscreen.hasDocument() == false`, call
  `chrome.offscreen.createDocument({url: "offscreen.html", reasons:
  ["DOM_PARSER"], justification: "fixed conformance justification"})`,
  observe the document while it is live, validate the ready message, call the
  real `closeDocument()`, and observe `hasDocument() == false` again. It then
  writes exactly one `storage.local` conformance record. A later probe after
  Service Worker idle suspension and wake-up must validate that persisted
  record and must not create the document again; the two responses identify
  their source as exactly `created` and `persisted`.
- The worker rejects duplicate or unexpected ready messages, wrong sender
  origin/ID/path, wrong reason or parser marker, duplicate creation, invalid
  `hasDocument` transitions, stale failure records, and every API or timeout
  error with a typed failure. Failure cleanup may close an actually-created
  document, but it may not report synthetic success or fall back to a hidden
  browser/page.
- The native state machine accepts exactly one offscreen terminal result,
  validates the fixed encoded record, and gates Profile cookie flushing on the
  order `mv3_core_self_test_passed < mv3_offscreen_page_passed <
  profile_cookie_flush_started`. No offscreen browser, direct extension-page
  navigation, JavaScript injection, or CDP listener is permitted.
- Package-boundary and native unit tests validate the `offscreen` permission,
  all fixture resources, and strict `offscreen` mode parsing. The native
  conformance script runs the positive sequence against stock CEF on macOS,
  Windows, and Linux and against the checksum-pinned custom runtime when
  enabled. The capability remains `UNPUBLISHED` until all three targets pass
  the exact lifecycle.
- The capability matrix may publish only this fixed document creation,
  DOM-parser, sender-validation, close, and persisted-record sequence. Audio,
  media, workers, clipboard, WebRTC, arbitrary offscreen URLs, concurrent
  documents, and public offscreen-host APIs remain `UNPUBLISHED` pending their
  own objectives.

Acceptance:

- Each surface has a real native host and complete lifecycle tests.
- No surface is advertised before Windows, macOS, and Linux validation.

### Phase 7: Distribution and hardening

Deliver:

- Signed runtime packs and update metadata.
- Crash reporting, diagnostics, license notices, sandbox policy, and reproducible packaging.

#### Objective 7.1: Reproducible verified runtime payload

This objective defines the unsigned content payload that a later signing and
update objective will authenticate. After Objective 8.2 it packages the real
native build output, the engine library closure, and the pinned CEF license notices. It is not a
release artifact and cannot be published or selected by an update client until
the signing objective exists.

Acceptance:

- The builder accepts explicit absolute paths for one extracted catalog-pinned
  CEF root, the matching native `Release` directory, the native contract
  directory, and the output archive. It requires the exact catalog directory
  name, non-empty regular `LICENSE.txt` and `CREDITS.html` files, a non-empty
  native runtime tree, and exactly the target's engine library closure.
  Missing, duplicate, special-file, path-escape, or mismatched-target input is
  a typed failure; no system CEF, alternate directory, or reduced payload is
  selected.
- The payload contains `runtime/`, `native/`, and `licenses/` entries plus one
  canonical `manifest.json`. It walks without following symbolic links,
  preserves safe relative symlinks and stable executable modes, rejects links
  that resolve outside their declared input root, and never records an
  absolute builder path or host-specific timestamp.
- `manifest.json` records the schema, KWebShell/CEF/Chromium versions, target,
  source artifact identity, every payload path in lexical order, entry type,
  normalized mode, size, SHA-256 digest, link target where applicable, and one
  deterministic tree SHA-256. The manifest is canonical UTF-8 JSON with one
  trailing newline.
- ZIP entries are lexical, unique, UTF-8, use one fixed timestamp and pinned
  compression settings, and carry normalized Unix mode metadata. Two builds
  from byte-identical input trees with different input mtimes must be
  byte-for-byte identical and have the same archive SHA-256.
- An independent verifier reopens the archive and rejects duplicate or unsafe
  names, unsupported entry types/modes/timestamps, missing notices/runtime or
  binding libraries, non-canonical manifest bytes, payload/manifest mismatch,
  SHA-256 or tree-digest mismatch, trailing data, and target/catalog mismatch
  with typed errors. Verification never trusts the builder's in-memory result.
- Unit tests cover all six target layouts with synthetic trees, macOS framework
  and engine symlinks, deterministic rebuilds, notice/binding omissions,
  escaping links, archive corruption, manifest tampering, payload tampering,
  duplicates, and unsafe paths. The hosted macOS arm64, Windows x64, and Linux
  x64 jobs each build and verify the payload from their real pinned CEF/native
  outputs before this objective merges.

Acceptance:

- Clean machines install and launch the sample application on all targets.
- Binary/resource checksums and SBOM/license output are reproducible.
- Upgrade, corruption, and failed-start diagnostics are actionable.

#### Objective 7.2: Signed runtime release pack and update record

This objective turns one independently verified Objective 7.1 payload into an
authenticated, reproducible release record. The release record is transportable
by a later update client, but it does not yet implement network discovery,
version selection, installation, rollback, or crash recovery.

Acceptance:

- The signer accepts only absolute normalized paths to an already verified
  payload, an Ed25519 PKCS#8 DER private key, a matching X.509 DER public key,
  and an output pack. It derives `keyId` from the exact public-key DER and
  rejects missing, special, symbolic-link, malformed, or mismatched inputs.
- The pack is a deterministic classic ZIP containing exactly `metadata.json`,
  the byte-identical nested `payload.zip`, and raw `signature.ed25519` in
  lexical order. All entries use fixed timestamps, mode `0644`, UTF-8 names,
  no extras/comments/encryption/data descriptors, and `STORED` compression.
- Canonical metadata records the schema, product/version, target, CEF and
  Chromium versions, nested payload size/SHA-256/tree digest, Ed25519
  algorithm, and derived key ID. The signature covers a versioned domain
  separator plus the exact metadata bytes. No timestamp, host path, or mutable
  URL is signed.
- The signer streams large payloads without loading them into memory, checks
  for source mutation while writing, independently verifies the temporary pack
  with the trusted public key, and publishes only through an atomic move.
- The verifier independently validates the ZIP envelope, canonical metadata,
  key ID, Ed25519 proof, nested payload digest, target/catalog/version, and the
  complete Objective 7.1 payload contract. It requires an explicit trusted
  public key and never downloads, guesses, or falls back to another key or
  algorithm.
- JVM tests cover all six target layouts, deterministic repeated signing,
  malformed and mismatched keys, metadata/signature/payload tampering, ZIP
  ordering/metadata/trailing-data attacks, target/version mismatch, source
  mutation, and atomic output preservation. The tests run in the existing
  macOS, Windows, and Linux CI matrix.
- The format and explicit non-goals are documented in
  `docs/runtime-release-format.md`; release signing is not wired into the
  normal root `check` without an explicitly supplied private key.

### Phase 8: Replace JNI with the Java Foreign Function and Memory API

This is a deliberate post-hardening, pre-1.0 breaking migration. It upgrades
the desktop runtime baseline from JDK 21 to JDK 25 LTS and replaces the JNI
binding library with direct FFM downcalls and upcalls over the existing
versioned C ABI. It is not a second backend: JNI and FFM are never selected by
a runtime property, shipped in parallel, or used as fallbacks for each other.

#### Objective 8.1: Freeze and prove the FFM boundary

This objective changes no production backend. It freezes the post-Phase-7 C
ABI and produces executable evidence that Objective 8.2 can replace JNI
without changing the native-child rendering contract.

Acceptance:

- ABI version 6 inventories exactly 18 exported engine functions, eight public
  structures, and four callback signatures. A native conformance target proves
  every size, alignment, field offset, symbol, and callback calling convention
  on each supported 64-bit CI target.
- A non-production Java 25 FFM probe resolves all 18 exports from exact
  absolute library paths, matches every native layout fact, and exercises
  downcalls, valid and malformed UTF-8 data through the 1 MiB boundary,
  shared-Arena upcalls from native threads, callback exception containment,
  and deterministic Arena closure. JDK 21 preview APIs are not used.
- Compose Desktop 1.11.1 supplies the parent through its documented public
  `ComposeWindow.windowHandle`. A real window test proves the returned value is
  a valid `HWND`, X11 `Window`, or `NSWindow`; reflection, private JDK APIs,
  JAWT calls owned by KWebShell, window-tree guessing, and overlay windows are
  prohibited.
- One benchmark harness invokes the same native test-library operations through
  JNI and FFM after warmup. It records median and p95 latency for integer
  startup and handle downcalls, strict Unicode payloads, fixed and variable
  callbacks, and complete owner lifecycles, plus JVM allocation and native
  live-byte observations. The initialization-only zero-argument query has an
  absolute latency gate; high-frequency operations retain a relative JNI gate.
- The macOS arm64, Windows x64, and Linux x64 jobs run the same conformance and
  benchmark acceptance thresholds. The measured evidence and an explicit
  go/no-go decision are recorded before Objective 8.2 starts.

#### Objective 8.2: Perform the breaking JDK 25 and FFM replacement

Deliver:

- A JDK 25 FFM binding layer for every exported engine and browser C function,
  using exact absolute-path library lookup, platform ABI layouts, typed status
  mapping, and session-scoped shared arenas.
- Native-to-JVM upcall ownership that keeps callback stubs and their Java
  owners alive until the terminal event returns, catches every callback
  exception before it reaches native code, and permits callbacks from CEF-owned
  threads without confined-arena violations.
- A stable raw native-parent contract for Compose Desktop on Windows, macOS,
  and Linux. The migration cannot delete JAWT/JNI until a supported mechanism
  supplies the exact `HWND`, AppKit parent, or X11 drawable without Java object
  references, reflection, private JDK APIs, window-tree guessing, or overlay
  windows.
- Removal of the JNI shared library, dynamically registered native methods,
  `JNIEnv` thread attachment, global references, JNI string conversion, and
  JAWT bridge after the replacement passes all acceptance tests.
- Reproducible binding generation or checked-in layouts pinned to the C ABI,
  with native `sizeof`, alignment, offset, calling-convention, and exported
  symbol conformance checks on every supported target.
- Benchmarks comparing JNI and FFM before deletion. The decision records
  measured startup, downcall, upcall, Unicode payload, memory, and browser-event
  results; no unverified performance multiplier is published.

Acceptance:

- The entire project compiles, tests, packages, and runs on JDK 25 LTS, and
  launchers explicitly grant native access to the named KWebShell module. A
  missing native-access grant fails at startup with a typed diagnostic.
- FFM drives the real CEF engine and Alloy browser through the same macOS,
  Windows, and Linux integration contract: Profile initialization, Unicode
  navigation, resize, callbacks, cookie flush, browser close, engine shutdown,
  and zero live counts all remain unchanged.
- The macOS application bundle is rebuilt idempotently with the canonical CEF
  framework links before integration starts. A recursive, missing, or
  non-canonical framework link is a test failure, never a runtime retry.
- A callback stress contract exercises CEF-owned threads, callback exceptions,
  concurrent command/close races, terminal-event ordering, arena closure, and
  use-after-close for at least 1,000 complete browser lifecycles without a
  crash, callback after close, leaked native memory, or stale upcall target.
  Every Windows lifecycle starts through `CefBrowserHost`, completes native
  hierarchy destruction through CEF's accepted standard-close path after the
  complete intermediate container moves beneath its shutdown root. It reaches
  `OnBeforeClose` without changing Chromium's immediate parent or closing
  Compose.
  After `OnBeforeClose`, CEF must release its final `SessionClient` owner before
  registry removal and the `CLOSED` upcall can occur across three CEF UI
  quiescence tasks; directly destroying a live window is not an accepted
  shortcut.
- The FFM layouts match the compiled C header on macOS arm64/x64, Windows x64,
  and Linux x64. Unsupported architectures fail during configuration; layout
  assumptions are never inferred from the current development host.
- No source or built artifact contains `native` JVM methods, `JNIEnv`,
  `JNI_OnLoad`, `RegisterNatives`, `AttachCurrentThread`, `NewGlobalRef`, JAWT,
  or the JNI shared library. There is no compatibility shim or JNI fallback.
- The production browser rendering path remains native-child GPU composition;
  the FFM migration does not introduce frame transfer, OSR, additional IPC, or
  a system WebView.

The feasibility analysis and migration gates are recorded in
[`docs/ffm-migration-analysis.md`](docs/ffm-migration-analysis.md). The raw
Compose parent gate is satisfied by the public `ComposeWindow.windowHandle`
contract on all three hosted targets. This remains a hard requirement: a future
platform without an equivalent supported handle is rejected rather than given
a hidden JNI fragment or a different rendering contract.

## 12. Test Strategy

Tests are part of each phase, not a final cleanup task.

Required layers:

- Common Kotlin unit tests for state machines, policies, errors, and package metadata.
- C++ unit tests for CEF adapter, package verification, thread/lifecycle invariants, and native window routing.
- FFM/C ABI integration tests for ownership, callbacks, strings, failures,
  layout, downcalls, upcalls, Arena lifetime, and native access.
- Real Chromium integration tests for navigation, CDP, DevTools, Profiles, and MV3.
- Cross-platform UI tests for native surface, DPI, focus, input, popup, and shutdown.
- Performance tests for startup, first contentful paint, navigation, GPU frame pacing, memory, and OSR comparison.
- Security tests for CRX3 signatures, path traversal, permissions, origins, remote debugging, and native messaging.

The MV3 conformance suite must include at least:

- A Service Worker messaging extension.
- A `chrome.scripting` content-script extension.
- A `storage.local` and options-page extension.
- A `declarativeNetRequest` extension.
- An action popup and badge extension.
- A context-menu extension.
- A DevTools extension.
- An extension that requests denied permissions and verifies the error.

## 13. Commit Roadmap

Use one commit per completed objective:

```text
docs: establish non-fallback engineering rules
docs: define KWebShell architecture and delivery plan
build: establish reproducible multiplatform foundation
cef: add native Chromium host and lifecycle
bridge: add versioned JNI and C ABI contract
profile: add persistent Profile and storage lifecycle
devtools: add CDP and native DevTools host
bridge: add generated typed JavaScript transport
extensions: add MV3 package validation and CRX3 verification
extensions: add MV3 Service Worker and core API host
extensions: add extension UI surfaces
release: add signed runtime packs and cross-platform packaging
interop: replace JNI with JDK 25 FFM bindings
```

Do not create a commit for a partial objective. Do not move a failing test to a later phase. Each commit must include the verification command or CI result in its body.

## 14. References

- [CEF branches and building](https://chromiumembedded.github.io/cef/branches_and_building)
- [CEF Chrome runtime migration, Issue #3685](https://github.com/chromiumembedded/cef/issues/3685)
- [CEF runtime extension loading request, Issue #3877](https://github.com/chromiumembedded/cef/issues/3877)
- [CEF Alloy/OSR extension limitation, Issue #3859](https://github.com/chromiumembedded/cef/issues/3859)
- [Chrome Manifest V3](https://developer.chrome.com/docs/extensions/develop/migrate/what-is-mv3)
- [Electron extension support limitations](https://www.electronjs.org/docs/latest/api/extensions)
- [JxBrowser extension guide](https://teamdev.com/jxbrowser/docs/guides/extensions/)
- [Kotlin Toolchain](https://github.com/JetBrains/kotlin-toolchain)
- [Kotlin Toolchain native interop](https://kotlin-toolchain.org/dev/user-guide/advanced/native-interop/)
- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472)
- [OpenJDK jextract early-access builds](https://jdk.java.net/jextract/)
