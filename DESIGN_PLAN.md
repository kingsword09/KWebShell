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

CEF backed by a pinned Chromium release is the desktop engine. The project must use the Chrome runtime and Chrome-style browser hosts for extension-capable pages. A native child surface is the required performance path.

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
 CEF Chrome runtime / Chromium Profile
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

This is the default rendering mode. Chromium renders to its native child surface with GPU acceleration. Compose owns the surrounding layout and overlays. This path is required for production performance and extension pages.

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
- Chrome runtime and Chrome-style native child browser.
- Navigation, resize, focus, input, DPI, and lifecycle callbacks.

Acceptance:

- A real page renders with GPU acceleration on all three platforms.
- Process failures and shutdown are observable typed errors.
- Native surface tests pass without OSR pixel copying.

### Phase 2: Kotlin/JNI contract

Deliver:

- Opaque native handles.
- Kotlin lifecycle and error types.
- JNI callbacks and thread dispatch.
- Resource ownership and close semantics.

Acceptance:

- No CEF C++ types appear in common Kotlin.
- Repeated create/navigate/resize/close cycles pass leak and race tests.
- Callback-after-close is rejected or safely ignored according to the contract.

### Phase 3: Profiles and web platform features

Deliver:

- Persistent Profile creation/open/flush.
- Cookies, storage, downloads, permissions, custom schemes, and request interception.

Acceptance:

- Data survives restart in the same Profile.
- Different Profiles cannot see each other's data.
- Custom protocol and permission tests run on all platforms.

### Phase 4: DevTools, CDP, and typed bridge

Deliver:

- DevTools native window.
- CDP endpoint/pipe.
- Generated Kotlin/TypeScript bridge.

Acceptance:

- CDP can discover a page and execute the required domains.
- A generated bridge method round-trips typed values, errors, cancellation, and timeout.
- Remote debugging security tests pass.

### Phase 5: MV3 core runtime

Deliver:

- Chromium extension-service adapter and runtime install path.
- Manifest validation, CRX3 verification, Profile persistence, permission policy.
- Service Worker, runtime, storage, scripting, content scripts, action, popup, and DNR.

Acceptance:

- A minimal MV3 Service Worker installs, wakes, handles events, sends messages, suspends, and restores.
- Real extensions exercise every published core capability.
- Restart, update, uninstall, Profile isolation, and denied-permission tests pass.

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
