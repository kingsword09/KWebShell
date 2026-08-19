# Example Applications And Benchmark Plan

Phase 10 turns the public desktop facade into two evidence-producing example
applications. The examples are product-facing test clients, not alternate
backends and not demo-only mock pages. Both applications must use the public
`kweb-core` and `kweb-desktop` contracts; they must not reach into CEF, FFM, or
private native handles.

## Example 1: HTML5 Capability Lab

Planned module: `kweb-example-html5-lab`

The lab loads a versioned, locally packaged test page and produces a
machine-readable report. The page must be served from a deterministic local
origin so that service workers, storage, workers, and fetch behavior are tested
under the same origin rules as a real application. A missing asset, failed
test, or unavailable required API is a test failure; the runner must never
replace it with a browser-specific fallback or a synthetic success.

The test manifest classifies each probe as `required`, `optional`, or
`diagnostic` for the pinned CEF/Chromium version. Optional capabilities are
reported as explicitly unavailable, with the browser version and reason; they
are not silently omitted. The initial manifest covers:

- ES modules, dynamic import, top-level await, WebAssembly, and SIMD/threads
  where the runtime advertises them.
- DOM, CSS layout, fonts, ResizeObserver, IntersectionObserver, shadow DOM,
  accessibility tree exposure, and input/event routing.
- Canvas 2D, WebGL2, WebGL extensions, GPU vendor/renderer evidence, and
  WebGPU only when the pinned runtime exposes it.
- Fetch, streams, WebSocket, WebCrypto, CORS, URL/blob handling, and
  compression/decompression APIs.
- IndexedDB, Cache Storage, `localStorage`, cookies, service workers, shared
  workers, dedicated workers, and cross-context messaging.
- Audio/video element behavior, Media Source/Encrypted Media discovery, and
  WebCodecs discovery without claiming a codec that the runtime did not
  actually decode.
- Permissions, clipboard, fullscreen, drag and drop, file selection, and
  browser lifecycle events where the host policy permits them.

Each probe records start/end timestamps, API/version evidence, pass/fail
reason, and a stable test identifier. The host collects the page report through
the public event stream and explicitly configured CDP endpoint. The artifact
set contains the JSON report, a rendered HTML report, a screenshot, the
Chromium version, the CEF runtime digest, and the host platform/architecture.

Acceptance criteria:

1. The example starts with a documented command on macOS, Windows, and Linux,
   creates a real native child under a visible `ComposeWindow`, and uses a
   persistent Profile.
2. A real CEF integration test runs the complete required manifest, verifies
   the report schema and origin, and fails when a required probe is missing or
   the report contains fabricated/empty evidence.
3. A restart test proves which storage and service-worker observations persist
   and which are intentionally session-scoped.
4. The report distinguishes engine capability from host permission policy and
   never labels an unimplemented feature as supported.

## Example 2: Application-Scale Workload Benchmark

Planned module: `kweb-example-app-benchmark`

This example loads a pinned, reproducible application workload with the
complexity of a LobeHub-class interface: route changes, markdown and code
rendering, virtualized lists, image decoding, streaming updates, WebSocket
traffic, workers, IndexedDB state, and long-lived Profile data. The first
fixture may be an official LobeHub web build only after its source revision,
license, build command, and SHA-256 artifact digest are recorded. A compatible
in-repository workload can be used when it exercises the same surface, but it
must be identified as a workload fixture rather than presented as LobeHub.

The workload is selected by an explicit lock file containing the source URL or
local artifact path, commit/release identifier, digest, license notice, and
expected entry point. Fetching or unpacking a missing or mismatched artifact
fails the task. There is no remote-to-local, alternate-app, OS-WebView, or
reduced-feature fallback.

The benchmark runs a fixed scenario set:

- cold engine/profile/page startup and first usable content;
- warm restart with the same Profile;
- navigation across representative routes and back/forward history;
- streamed message/render updates, markdown/code highlighting, and list
  virtualization under synthetic but deterministic input;
- image/font/media decode and cache-hit versus cache-miss loads;
- worker/WebSocket/IndexedDB activity during a sustained session;
- DevTools/CDP observation overhead and public event-stream latency;
- orderly page, Profile, and Engine shutdown with native-owner counts at zero.

Every run writes raw samples before aggregation. Browser-side measurements use
`PerformanceObserver` and the Navigation/Resource/Long Task/Element Timing
APIs. Host-side measurements record engine startup, page events, CDP command
latency, process RSS/private memory, CPU time, thread count, native child
frame pacing, GPU renderer evidence, Profile disk growth, and shutdown time.
The report includes median, p95, and worst-case values for each scenario,
sample count, warmup count, runtime digest, OS, architecture, display scale,
GPU identity, and benchmark git revision. A single composite score is not
published.

Acceptance criteria:

1. The workload artifact is reproducible from its lock file and is verified
   before launch; a missing or changed digest fails loudly.
2. At least ten measured repetitions follow a declared warmup phase, with raw
   JSON retained for independent re-analysis. The runner rejects incomplete
   samples and never fills missing metrics with zero or estimated values.
3. A CDP/performance conformance test checks FCP, LCP, CLS, INP (when exposed),
   long tasks, resource timing, JavaScript heap evidence, DOM size, and worker
   activity against the raw page observations.
4. macOS is the first release gate. Windows and Linux must run the same locked
   workload and schema before the example is called cross-platform.
5. Regression thresholds are versioned per runtime and machine class; changes
   to the CEF/Chromium digest require a new baseline rather than silently
   reusing old numbers.

## Shared delivery rules

The examples are separate objectives after the public facade objective. Each
has its own unit tests, real-CEF integration test, rendered report fixture, and
focused commit. Documentation-only planning changes do not trigger the three
platform CEF matrix. Once implementation starts, a green macOS run is required
before enabling the Windows and Linux jobs, and all published results must
include the exact command and artifact digests used to obtain them.
