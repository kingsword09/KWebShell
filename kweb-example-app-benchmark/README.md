# Application-Scale Workload Benchmark

This module is a repository-local, synthetic workload fixture with the
complexity of a LobeHub-class application shell. It is intentionally not a
copy of LobeHub and it does not publish LobeHub performance claims. The lock
file records the exact fixture revision, provenance, license notice, entry
point, per-file sizes, per-file SHA-256 values, and aggregate SHA-256.

The page is served from a loopback origin and exercises one coherent workflow:

- History API route changes plus back/forward traversal;
- streamed Markdown-like output and code rendering;
- a 5,000-row virtualized source list;
- WebSocket chunks, a Dedicated Worker, and IndexedDB persistence;
- image, font, and generated WAV audio decoding;
- PerformanceObserver paint, layout-shift, long-task, and interaction data;
- DevTools open/close, CDP command latency, native public events, screenshots,
  process-tree resource metrics, Profile growth, and shutdown timing.

Every cold/warm pair runs in isolated JDK 25 and CEF processes. One warmup pair
is discarded from aggregates; ten measured cold/warm pairs are required. Raw
JSON is retained under `build/reports/application-benchmark/raw/`, and the
report contains median, p95, and worst values without a composite score.
Missing required evidence, a changed resource digest, an incomplete pair, or
an unavailable required API fails the run. Optional INP evidence is represented
as an explicit unavailable reason when the runtime does not expose it.

## Local command

```shell
JAVA_HOME=/absolute/path/to/jdk-25 \
PATH="$JAVA_HOME/bin:$PATH" \
./gradlew :kweb-example-app-benchmark:applicationBenchmarkIntegrationTest \
  --no-daemon \
  -PcefRoot="$PWD/.cef/source/<pinned-cef-directory>" \
  -PkwebBenchmarkMachineClass=<stable-machine-class>
```

The task uses the same public `KWebEngine`/`KWebProfile`/`KWebPage` facade as an
application would. It does not access CEF handles, FFM bindings, or private
native classes. Linux runs under an explicit Xvfb display; no OS WebView or
reduced workload is selected.

The workload can be inspected without a native run by serving the locked
resource directory with a local HTTP server, but benchmark observations are
accepted only from the module's loopback server and verified lock file.

Use the preview task for visual inspection without claiming benchmark evidence:

```shell
./gradlew :kweb-example-app-benchmark:applicationBenchmarkPreview
```

The preview prints its loopback URL and serves only digest-verified resources.
The full run writes 22 raw sample files, the aggregate JSON report, and the
first measured cold/warm screenshots under
`build/reports/application-benchmark/`. Screenshot and raw-file SHA-256 values
are recorded in the report, and CI uploads the complete directory as one
platform-specific artifact.

Regression thresholds live in the versioned baseline catalog. Selection is
strictly keyed by runtime SHA-256, workload SHA-256, platform, architecture,
and the explicit machine class. A new Chromium runtime or unmatched machine
class fails instead of reusing another baseline.
