# KWebShell HTML5 Capability Lab

This module is a real Chromium evidence client for the public KWebShell desktop
facade. It serves one locked local origin, launches cold and warm runs in
separate JDK 25 processes against the same persistent Profile, and writes:

- `capability-lab-report.json`: strict schema, raw probe evidence, public page
  event sequences, CDP browser identity, and accessibility-tree evidence;
- `capability-lab-report.html`: a rendered human-readable report;
- `capability-lab-cold.png` and `capability-lab-warm.png`: real visible
  `ComposeWindow` screenshots.

The page exercises ES modules, dynamic import, top-level await, WebAssembly,
SIMD and shared memory, DOM/CSS/fonts/observers/shadow DOM, trusted CDP-routed
input, Canvas, WebGL2, WebGPU, fetch streams, CORS, WebSocket, WebCrypto,
compression, blob URLs, localStorage, cookies, IndexedDB, Cache Storage,
dedicated/shared/Service Workers, media discovery and decoding, permissions,
and lifecycle state. Required probe failures, optional probe execution errors,
empty evidence, schema drift, a non-loopback CDP endpoint, a missing AX region,
or broken cold-to-warm persistence fail the task.

## Run

Use JDK 25 and an extracted pinned CEF distribution for the host platform:

```shell
JAVA_HOME=/absolute/path/to/jdk-25 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :kweb-example-html5-lab:capabilityLabIntegrationTest \
  --no-daemon \
  -PcefRoot="$PWD/.cef/source/<pinned-cef-directory>"
```

The report is generated under
`kweb-example-html5-lab/build/reports/capability-lab/`. The task uses the same
source and report schema on macOS, Windows, and Linux. Linux runs under Xvfb;
there is no OS WebView, alternate application, remote page, reduced probe set,
or synthetic success fallback.
