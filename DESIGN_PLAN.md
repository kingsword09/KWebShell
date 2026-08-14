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
     JNI + C ABI
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
| `kweb-cef-native` | CEF initialization, browser hosts, native surfaces, C ABI/JNI, extension adapter | Kotlin business state |
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

The common module describes behavior, not the implementation mechanism. CEF handles and CEF callbacks remain behind opaque native handles or JNI objects.

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
  shutdown; arbitrary host arguments remain disabled. Windows and Linux use
  CEF's supported multi-threaded windowed message loop.
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
  process also proves that the fixed browser policy is installed exactly once
  before the context callback. A timeout captures a JVM thread dump and, on
  macOS, a native process sample before terminating the child. Linux runs under
  an explicit Xvfb launcher.
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
  explicit Alloy runtime style. Windows uses the Canvas `HWND`, Linux uses its
  X11 drawable, and macOS resolves the AWT top-level peer to its AppKit
  `NSWindow` and owns a dedicated intermediate `NSView`. An unavailable or
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
  Windows destroys the child `HWND`, Linux requests forced CEF close, and
  macOS removes the CEF `NSView` from its AppKit hierarchy, which is the
  windowed Alloy operation that causes CEF to deliver `OnBeforeClose`. The
  platform child is released only after that callback, exactly one terminal
  event is emitted, and the JNI global reference is released only after that
  event has returned. Engine close is rejected while any browser is live; the
  Kotlin engine retains its handle and remains OPEN so the caller can close
  the browser and retry. Clean browser then engine shutdown leaves both live
  counts at zero.
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

### Phase 6: Extension browser UI

Deliver:

- Options pages, context menus, DevTools pages, offscreen documents, side panels, and native messaging policy where supported.

Acceptance:

- Each surface has a real native host and complete lifecycle tests.
- No surface is advertised before Windows, macOS, and Linux validation.

### Phase 7: Distribution and hardening

Deliver:

- Signed runtime packs and update metadata.
- Crash reporting, diagnostics, license notices, sandbox policy, and reproducible packaging.

Acceptance:

- Clean machines install and launch the sample application on all targets.
- Binary/resource checksums and SBOM/license output are reproducible.
- Upgrade, corruption, and failed-start diagnostics are actionable.

## 12. Test Strategy

Tests are part of each phase, not a final cleanup task.

Required layers:

- Common Kotlin unit tests for state machines, policies, errors, and package metadata.
- C++ unit tests for CEF adapter, package verification, thread/lifecycle invariants, and native window routing.
- JNI/C ABI integration tests for ownership, callbacks, strings, and failures.
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
