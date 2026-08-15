# KWebShell

KWebShell is a Kotlin Multiplatform browser shell for Compose and Chromium. The project targets native, hardware-accelerated browser surfaces, persistent profiles, DevTools/CDP, typed host bridges, and Manifest V3 extensions on Windows, macOS, and Linux.

## Current Status

The repository contains the multiplatform build foundation, a native CEF host vertical slice, the verified CEF runtime catalog, persistent Chromium Profiles, and an internal in-process JVM/CEF browser session. The host uses the Chrome bootstrap with an explicit Alloy, windowed native child and reports fatal capability errors instead of selecting a fallback backend. Each browser receives an explicitly initialized disk-backed request context; Profile paths that are not direct children of the CEF root cache are rejected because Chromium would otherwise create an OffTheRecord Profile. The CEF browser process runs inside the JVM so a real native child belongs to the Compose/AWT window hierarchy. Objective 3.3 intentionally deletes the Phase 2 echo session and its request-only events; this is a breaking internal contract change, not a compatibility layer. No public `KWebEngine`, `KWebPage`, or Compose API is exposed until the real browser contract is complete.

Current verification evidence is intentionally platform-specific:

- macOS arm64: local Apple M2 and hosted Apple Paravirtual ANGLE/Metal WebGL runs pass the real CEF, Alloy child, focus, mouse, native wheel, keyboard, resize, renderer/GPU failure, lifecycle, and bounded shutdown tests.
- Linux arm64 and x64: GCC 13 builds and links the real CEF runtime; GTK/X11 parent validation, root screen bounds, renderer/GPU failure, bounded shutdown, and the strict no-GPU contract pass on both architectures, including the hosted x64 Actions job. The available runners have no `/dev/dri`; positive Linux hardware rendering remains unverified and fails with `native.gpu.hardware-acceleration-unavailable`.
- Windows x64: MSVC 19.44 builds and links the complete real CEF host and all four hosted CTest contracts pass, including Win32 focus, DPI, screen bounds, input routing, renderer/GPU failure, and bounded shutdown. The hosted Microsoft Basic Render Driver is rejected explicitly; the `Hardware GPU Validation` workflow is the positive D3D11 gate for a self-hosted physical-GPU runner.

The earlier Phase 2 boundary was verified locally on macOS arm64 through a pure C consumer, native concurrency/lifetime tests, and a JVM that loaded the real JNI shared library by absolute path. Objective 3.3 deliberately replaces that echo boundary; current verification covers the real browser ABI, native surface, JNI lifecycle, and isolated JVM/CEF integration. Windows and Linux support is accepted only when the same sources and real shared libraries pass the hosted matrix; there is no mock or alternate native backend in those jobs.

The Phase 3 Profile contract runs three real CEF processes against a controlled HTTPS origin. It proves that `localStorage` and a session cookie survive restart in Profile A, remain invisible to Profile B, flush cookies before browser close, and create Chromium Preferences, Cookies, and Local Storage files under each declared Profile after shutdown. The same CTest contract is part of the macOS, Windows, and Linux verification matrix.

The in-process engine has a separate opaque C ABI and loads only explicitly supplied absolute paths for the engine library, CEF runtime, subprocess, resources, locales, root cache, and log. Kotlin creates and closes it on the AWT event-dispatch thread. macOS initializes and pumps CEF on AppKit, requests shutdown asynchronously, and confirms completion only after the real AppKit `CefShutdown` returns; Windows and Linux use CEF's windowed multi-threaded loop, with Linux `XInitThreads` running before AWT starts. A close rejected by a live browser preserves the engine handle and OPEN state for a deliberate retry after browser destruction. Dedicated JVM integration processes require the real `OnContextInitialized` callback, clean `CefShutdown`, typed failure behavior, zero live engines and browsers, and terminal rejection of a second lifecycle.

The first Phase 4 slice adds an explicit CDP port to the internal engine configuration. `0` keeps remote debugging disabled; `1024..65535` enables a fixed endpoint constrained to IPv4/IPv6 loopback. The real integration discovers `/json/version` and `/json/list` and executes `Runtime.evaluate` over WebSocket. A port collision or non-loopback endpoint is a typed failure; no ephemeral-port, public-interface, OS debugger, or alternate transport fallback is used.

The second Phase 4 slice adds the first native DevTools host. A browser can open and close the CEF DevTools front-end as a separate Chrome-style native window while the embedded page remains the required Alloy child. The lifecycle is typed, duplicate opens fail, CDP exposes the `devtools://` target while the window is open, and closing the page closes DevTools before the page terminal event.

The third Phase 4 slice adds the generated typed host bridge. A strict schema produces Kotlin models and dispatcher code, a TypeScript client, and browser-ready JavaScript. CEF installs the transport only for an explicitly enabled browser's main frame at one exact HTTP(S) origin; child frames, cross-origin pages, DevTools, and unconfigured browsers receive no bridge. Calls run in Kotlin coroutines off the CEF UI thread and have exact timeout, abort, navigation, and close cancellation. This is host RPC, not a `chrome.*` emulation layer, and the unfinished public Compose page API remains unexposed.

Phase 5.1 adds the trusted Manifest V3 package boundary. `kweb-extensions` strictly validates its closed MV3 manifest schema, public-key-derived IDs, unpacked directory safety, CRX3 signatures, bounded ZIP contents, and permission policy. Unsupported manifest keys fail instead of being silently ignored. Package admission does not install an extension or imply runtime API support.

Phase 5.2 adds the first real Chromium MV3 runtime baseline. A package-verified fixture is injected into the pinned CEF Chrome bootstrap and an Alloy native child; the conformance test proves static content scripts, isolated worlds, exact `chrome.runtime` messaging methods, Service Worker idle suspension and wake-up, `storage.local` restart persistence, and Profile isolation. The command-line extension load is private test bootstrap only, not a product installation path. Installation, update, uninstall, UI surfaces, scripting, tabs/windows, and DNR remain outside the public contract until their own complete objectives pass. The exact published subset is defined by the [Manifest V3 capability matrix](docs/mv3-capability-matrix.md).

Phase 5.3 adds the internal Profile-scoped immutable package store required by the future Chromium lifecycle adapter. Unpacked input is copied and re-verified; signed CRX3 payloads are extracted with their verified public key; both become deterministic content-addressed objects. A cross-process lock and atomic journal separate `INSTALL`, `UPDATE`, `RELOAD`, and `UNINSTALL` preparation from runtime truth. The active record changes only after explicit runtime confirmation, ambiguous crash state remains pending for reconciliation, and garbage collection cannot run across an unresolved transaction. This is package provisioning, not Chromium installation, and no public lifecycle API is exposed.

Objective 5.4 adds the internal Profile-scoped Chromium lifecycle adapter. A pinned source patch extends `libcef` with four private `cef_kweb_*` C exports; the JNI engine discovers and fingerprints them dynamically, and stock CEF fails with `native.abi.extension-runtime-abi-missing`. The coordinator now drives real install, update, reload, query, uninstall, cancellation, and restart reconciliation through Chromium's extension service. Reload readiness waits for prior Profile worker teardown before it starts the current activation, rather than relying on a delay. On macOS arm64, the source-built runtime has passed the full lifecycle fixture, including a replaced Service Worker, retained `storage.local`, two-Profile isolation, forced process loss, five cancelled-reload reconciliation cycles, duplicate-operation rejection, and uninstall persistence. Package lifecycle remains `UNPUBLISHED`: Windows x64 and Linux x64 custom artifacts have not yet passed the same gate, and the runtime manifest intentionally contains no publication entries.

See [DESIGN_PLAN.md](DESIGN_PLAN.md) for architecture and delivery phases, the [Manifest V3 capability matrix](docs/mv3-capability-matrix.md) for the exact runtime surface, [ADR 0003](docs/adr/0003-versioned-native-session-contract.md) for the native ownership contract, [ADR 0004](docs/adr/0004-persistent-chromium-profile-context.md) for the Profile path and persistence contract, [ADR 0005](docs/adr/0005-in-process-jvm-cef-engine.md) for the JVM/CEF engine lifecycle, [ADR 0006](docs/adr/0006-real-awt-chromium-browser-session.md) for the real Alloy browser surface, [ADR 0007](docs/adr/0007-explicit-loopback-cdp-endpoint.md) for the secured CDP endpoint, [ADR 0008](docs/adr/0008-native-devtools-window-host.md) for the native DevTools lifecycle, [ADR 0009](docs/adr/0009-origin-scoped-generated-typed-bridge.md) for bridge isolation and cancellation, [ADR 0010](docs/adr/0010-manifest-v3-package-verification.md) for extension package security, [ADR 0011](docs/adr/0011-profile-scoped-extension-package-store.md) for managed extension objects and journals, [ADR 0012](docs/adr/0012-version-pinned-chromium-extension-lifecycle-adapter.md) for the custom CEF lifecycle boundary, and [AGENTS.md](AGENTS.md) for the non-fallback implementation rules.

## Requirements

- JDK 21
- Node.js 24 LTS and `npm ci` for the pinned TypeScript bridge compiler
- The checked-in Gradle wrapper

Native CEF development additionally requires CMake, Ninja, and the platform C++ toolchain described by the design plan.

## Verification

```shell
npm ci
./gradlew check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal
```

The root `check` task compiles every included module, strictly compiles the generated TypeScript client, runs Kotlin tests, native unit/GUI tests, and the isolated real JVM/CEF engine integration contract, and validates the pinned CEF runtime manifest. On Linux the engine contract is launched through explicit `xvfb-run`; absence of that launcher fails configuration. `cefRoot` must point to an extracted, checksum-verified CEF distribution for the current host; it is never inferred from another platform or replaced by a system WebView.

On a runner known to have no hardware GPU, use the explicit negative capability contract:

```shell
./gradlew check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary \
  -PkwebExpectHardwareGpuUnavailable=true
```

This replaces only the positive hardware-rendering self-test with a strict test that requires exit code `71`, `native.gpu.hardware-acceleration-unavailable`, an Alloy native child, browser/renderer/GPU/utility processes, and complete shutdown. It fails if hardware rendering unexpectedly succeeds and is not evidence of GPU support.

The normal GitHub Actions matrix runs macOS arm64 with the positive Metal contract and runs Linux/Windows x64 with the explicit software-GPU rejection contract. Positive Linux and Windows hardware evidence must be run through [.github/workflows/gpu-validation.yml](.github/workflows/gpu-validation.yml) on self-hosted runners labeled `kwebshell-gpu`, with a Linux `/dev/dri/renderD128` node or a physical Windows GPU. A hosted Windows WARP device and a hosted Linux Xvfb display are intentionally never counted as hardware-rendering evidence.

Custom CEF lifecycle verification has two explicit Actions workflows. [Custom CEF Source Build](.github/workflows/custom-cef-source-build.yml) runs on dedicated self-hosted hosts labeled `kwebshell-cef-build`, builds the exact pinned CEF/Chromium/depot_tools revisions, validates its exports and ABI fingerprint, and runs the real MV3 lifecycle before uploading the ZIP and provenance metadata. [Published Custom CEF Acceptance](.github/workflows/custom-cef-acceptance.yml) runs on hosted macOS, Windows, and Linux runners, downloads only manifest-declared HTTPS artifacts, verifies their size and SHA-256 evidence, and runs the complete native and lifecycle suite. It fails with `runtime.custom-runtime.publication-incomplete` until all three artifacts are present; there is no stock-runtime substitution.

The source build uses external empty directories because a Chromium checkout and output are intentionally excluded from the repository:

```shell
python3 runtime/cef/build-custom-runtime.py \
  --work-dir=/absolute/empty/kwebshell-cef-work \
  --output-dir=/absolute/empty/kwebshell-cef-output \
  --target=macos-arm64
```

To verify a downloaded CEF archive against its pinned size and SHA-1, provide both inputs explicitly:

```shell
./gradlew :kweb-runtime-pack:verifyCefRuntimeArtifact \
  -PkwebTarget=macos-arm64 \
  -PcefRuntimeArchive=/absolute/path/to/cef_binary.tar.bz2
```

The task fails on a missing file, target mismatch, size mismatch, checksum mismatch, or unsupported checksum algorithm. It never selects another runtime artifact.
