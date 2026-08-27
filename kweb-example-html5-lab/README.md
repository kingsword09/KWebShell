# KWebShell HTML5test Example and Capability Probe

The user-facing example loads the real
[`https://html5test.com/`](https://html5test.com/) page in KWebShell's native
Chromium child. It requires the canonical HTTPS URL and title, reads the score
from `#score .pointsPanel h2` through CDP, verifies the public page event stream
contains an HTTP 200 load, and writes:

- `html5test-report.json`: strict score, maximum score, URL, title, Chromium/CDP
  identity, runtime digest, host identity, public events, and screenshot digest;
- `html5test-report.html`: a rendered evidence summary;
- `html5test.png`: a nonblank CDP page-target PNG from the page hosted by the
  verified visible, windowed native child.

The report fixes `screenshotSource` to `cdp-page-target`. The runner still
requires `native-child`, `cdp`, a displayable `ComposeWindow`, and a non-zero
native handle before capture. The PNG records the Chromium target surface; it
is not described as operating-system display scanout.

The site currently identifies itself as an archived test that has not been
updated since 2016. Its score is useful as evidence that the embedded browser
can execute that exact page, not as proof of complete or current Web-platform
compatibility. If the site is unreachable or its expected DOM changes, the
task fails with no local or alternate-site fallback.

The module also retains a separate deterministic conformance probe. That probe
serves one locked local origin, launches cold and warm runs in separate JDK 25
processes against the same persistent Profile, and writes:

- `capability-lab-report.json`: strict schema, raw probe evidence, public page
  event sequences, CDP browser identity, and accessibility-tree evidence;
- `capability-lab-report.html`: a rendered human-readable report;
- `capability-lab-cold.png` and `capability-lab-warm.png`: real visible
  `ComposeWindow` screenshots.

The local probe exercises ES modules, dynamic import, top-level await, WebAssembly,
SIMD and shared memory, DOM/CSS/fonts/observers/shadow DOM, trusted CDP-routed
input, Canvas, WebGL2, WebGPU, fetch streams, CORS, WebSocket, WebCrypto,
compression, blob URLs, localStorage, cookies, IndexedDB, Cache Storage,
dedicated/shared/Service Workers, media discovery and decoding, permissions,
and lifecycle state. Required probe failures, optional probe execution errors,
empty evidence, schema drift, a non-loopback CDP endpoint, a missing AX region,
or broken cold-to-warm persistence fail the task.

## Run

To run the real HTML5test example against the extracted pinned CEF runtime:

```shell
JAVA_HOME=/absolute/path/to/jdk-25 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :kweb-example-html5-lab:html5TestSiteRun \
  --no-daemon \
  -PcefRoot="$PWD/.cef/source/<pinned-cef-directory>"
```

The live-site artifacts are generated under
`kweb-example-html5-lab/build/reports/html5test-site/`.

For the deterministic local conformance probe, use:

```shell
JAVA_HOME=/absolute/path/to/jdk-25 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :kweb-example-html5-lab:capabilityLabIntegrationTest \
  --no-daemon \
  -PcefRoot="$PWD/.cef/source/<pinned-cef-directory>"
```

The deterministic probe report is generated under
`kweb-example-html5-lab/build/reports/capability-lab/`. The task uses the same
source and report schema on macOS, Windows, and Linux. Linux runs under Xvfb;
there is no OS WebView, alternate application, reduced probe set, or synthetic
success fallback. This local probe is not described as the HTML5test page and
is never substituted when the live-site task fails.
