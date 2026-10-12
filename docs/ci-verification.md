# Change-aware CI verification

`CI` runs on every PR and `main` push. Documentation-only events bypass platform
jobs and run strict governance. For implementation, three lightweight graph jobs export the
actual Gradle project/task graph with `--dry-run`; they do not run CEF or native
tests. The planner unions platform edges and uses the Git event baseline,
reverse consumers and existing RFC contract/runtime digests to produce one
retained execution plan. Every selection records its reason. Unknown code paths,
missing module rules, missing producers or missing platform tasks fail closed.

## Adding a module

Add the real module to `settings.gradle.kts` and register its complete task roots
in `.github/ci/suites.json`, for example `":kweb-service-new":
[":kweb-service-new:check"]`. This is verification metadata, not an application
API or a substitute for RFC acceptance. Pure additions preserve existing rules
and select the new module and its actual consumers. Changing existing settings,
toolchains, runtime pins or CI policy deliberately selects full verification.

The module's check must include its required common/JVM/native/package tests.
Standalone integration roots not included by check must be registered too.
Gradle project dependencies and cross-project task dependencies determine
consumer impact, including generated bindings, tests and native payloads.
Keep ordering-only dependencies separate from data dependencies. A new module
without a rule, or a rule pointing to a nonexistent task, blocks CI rather than
quietly selecting an empty suite. Required native prerequisites must appear in
the selected task graph; introduce a reviewed setup feature if a new provider
needs a facility beyond the current declared CEF/native/Node/fixture set.

## Adding or refreshing an RFC

Register an Implemented RFC's producer modules and raw artifact families in
`suites.json`, and implement the actual recorder/preflight contract in
`aggregate-rfc-evidence.sh`. Missing mappings block planning or recording.
Existing RFC contract bindings are unchanged: do not remove a shared binding
just to make a small PR faster. A changed service may therefore select the
shared Electron fixture/CEF when its RFC still requires that conformance proof.

Only missing or stale required records refresh in an incremental implementation
plan. The collector downloads planned families, validates relevant notification,
image and Windows MSIX proofs, then calls `recordBatch` once for selected RFCs
and their three targets. Unaffected records keep their original provenance and
artifact bytes. Fresh output does not replace validation of checked-in evidence.
Import the real hosted manifest and retained tree in the same objective PR.

A current evidence-import tail avoids another native cycle only when every
earlier implementation path is bound to a registered RFC, all required digests
are current, and no later implementation change exists. Unbound code remains
selected. Documentation-only changes never run the native matrix; a stale
document-only contract blocks strict governance instead of trusting old proof.

## Gates and retained proof

`CI Gate` is the stable required check. It validates the plan and the actual job
outcomes: selected platform, aggregation and governance jobs must succeed;
unexpected skipping, cancellation or failure is blocking. Plan failures cannot
produce a passing gate. Configure repository protection to require this check;
the workflow does not modify GitHub branch-protection settings automatically.

Artifacts `ci-plan-*` and `ci-audit-*` retain changed paths, reasons, roots,
complete task closures and setup flags for 14 days. Refreshed tests execute with
up-to-date and build-cache reuse disabled by `fresh-verification.gradle`;
compilation can still be cached, but caches do not authorize native proofs.
Full runs also execute a synthetic current-evidence tray selection against the
real native provider before CEF provisioning. This demonstrates task isolation,
not a replacement for the RFC's Electron migration conformance.

Manual dispatch and the weekly schedule run full verification. Workload
benchmark and live HTML5 checks retain their Windows-hosted requirement.
Superseding PR runs cancel older runs; another PR cannot cancel a main run.

The normative contract and acceptance matrix are in
[RFC 0001](rfcs/0001-program-governance.md#change-aware-ci-contract-v1).
