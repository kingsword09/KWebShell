# RFC 0036: Real application migration conformance and release matrix

- Status: Proposed
- Priority: P0
- Owners: `kweb-electron-migration`, examples, CI
- Depends on: RFC 0005
- Electron migration surface: one complete pinned Electron application
- Target mapping: measured application result

## Objective

Prove the migration system against a real maintained Electron application,
initially a pinned LobeHub desktop revision or another explicitly approved
candidate. RFC 0005 is the hard prerequisite; the complete set of required
capability RFCs is derived from the pinned application inventory and must be
Implemented before implementation begins.
Publish correctness, source-change, package, startup, memory, and
workload evidence without claiming universal Electron compatibility.

## Selection and scope

The fixture pins source commit, lockfile, Electron major, build configuration,
test account/data policy, renderer entry points, and workloads. Inventory must be
complete before implementation. Every used API maps to an `Implemented` RFC or
remains a visible blocker; benchmark results are forbidden while blocked.

## Acceptance

1. Baseline Electron and KWebShell builds use the same application source
   revision, renderer assets, data fixtures, Chromium-compatible workload, and
   machine class where comparison is meaningful.
2. The report lists every changed file/line category: Kotlin host replacement,
   generated adapter, deterministic codemod, and unavoidable renderer rewrite.
3. All application correctness tests and critical user journeys pass on
   Windows, macOS, and Linux packages.
4. Cold/warm startup, steady memory, process count, frame responsiveness, package
   size, and workload timing retain raw samples and statistical summaries; no
   cherry-picked run is published.
5. Security inspection proves no Electron/Node binary, unknown IPC, unsupported
   import, unrestricted path, or migration shim remains.
6. Install/update/uninstall, crash recovery, Profile persistence, offline mode,
   and owner shutdown pass with real artifacts.
7. The release matrix binds source/manifest/generated/service/runtime/package/
   evidence digests and names every non-equivalent behavior.

## Completion rule

This RFC is `Implemented` only for the pinned application revision and matrix
version. A newer application/Electron major requires a new evidence revision and
may return the row to `stale`; it never broadens into “all Electron apps”.

## Non-goals

No universal Electron compatibility claim, benchmark from a blocked inventory,
different-source comparison, hidden renderer rewrite, unpinned application
upgrade, or performance claim without retained raw evidence.
