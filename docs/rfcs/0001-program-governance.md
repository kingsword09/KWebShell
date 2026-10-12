# RFC 0001: RFC governance and capability evidence manifest

- Status: Implemented
- Priority: P0
- Owners: repository governance, CI, documentation
- Depends on: existing Phase 11 prerequisites
- Electron migration surface: all inventoried Electron and Node usage
- Target mapping: infrastructure, not an application adapter

## Objective

Make every future capability independently implementable and auditable. Publish
the RFC state machine, a machine-readable capability evidence manifest, and CI
validation that prevents documentation, runtime descriptors, migration rows, and
hosted evidence from disagreeing.

## Decisions

The evidence manifest MUST identify RFC ID/status, service and schema versions,
target triple, provider ID, CEF/Chromium identity, Electron fixture major,
artifact digests, test run IDs, and compatibility status. `Implemented` requires
three current hosted target records unless the RFC declares a platform-specific
key. Evidence expires when any bound contract or runtime digest changes.

Evidence binds a repository revision, so the contract digest covers the files
that revision tracks under the declared contract paths. Untracked local output —
a native or JVM build directory under a bound path — is not part of the revision
and MUST NOT invalidate a record, while changing a tracked bound file MUST. A
root without a revision, a bound path the revision does not track, or a tracked
bound file missing from the working tree fails closed with a structured reason.

The migration matrix remains conservative runtime code. The RFC catalog is the
planning source; CI MUST reject a supported matrix row that lacks an
`Implemented` RFC and matching evidence.

## Non-goals

This RFC does not publish an OS capability, infer support from source files,
waive platform tests, or let a stale successful run validate changed artifacts.

## Required deliverables

- Versioned JSON schema and deterministic validator for capability evidence.
- Gradle documentation check joining RFC metadata, descriptors, matrix rows,
  packaged schemas, and retained evidence.
- A command that reports `ready`, `blocked`, `stale`, or `platform-specific`
  with structured reasons.
- Contributor documentation and the template in this directory.

## Acceptance

1. Fixtures prove unknown fields, duplicate RFC IDs, missing targets, digest
   changes, stale runtime versions, and unsupported-to-supported promotion fail.
2. A delivered Phase 11 capability can be represented without hand-edited test
   output, and regeneration is byte-for-byte deterministic.
3. CI verifies documentation-only RFC proposals with `git diff --check` and the
   schema validator without running the native matrix.
4. Moving an RFC to `Implemented` without all required native evidence fails.
5. Reports contain no credentials, private paths, runner tokens, or native
   pointers.
6. The recorder accepts artifact files rather than caller-supplied digests,
   copies their exact bytes into the checked-in evidence store, derives run
   identity and target from GitHub Actions, and rejects local or unsupported
   runner environments.
7. Every evidence record binds the deterministic digest of the files the
   repository revision tracks under the RFC's declared contract paths; changing a
   tracked bound file makes the record stale, untracked local build output does
   not, and a root without a revision fails closed.

## Evidence

CI retains the validated manifest, deterministic digest, joined matrix report,
and explicit stale/blocking diagnostics.

## Batch recording contract v1

This focused CI objective adds `recordBatch <manifest.json> <output.json>
--catalog <dir> --runtime <runtime.json> --contracts <contracts.json>
--repository-root <dir> --batch <batch.json>`. The existing single-record command
remains available for recording an individual capability. Both use the same
record validation, canonical serialization, contract hashing and hosted identity.
The batch is one process with a single writer; concurrent writers to its output
or retained artifact paths are outside the contract and must not be scheduled.

The strict JSON input has `schemaVersion: 1` and a non-empty `records` array
of at most 256 entries. Each entry requires string `rfcId`, `providerId`, `target`,
integer `electronFixtureMajor`, and `artifacts` (an array of objects containing
string `name` and repository-relative `path`). Optional fields are nullable
`serviceId`, `compatibilityStatus`, `compatibilityReportPath` (default null), and
`matrixRowIds` (default empty array). Unknown fields, wrong types, unsupported
versions, duplicate `(rfcId, target, providerId)` entries, and missing required
options fail. Input paths may contain spaces; absolute paths, traversal and
symlink paths are rejected. Targets remain the existing hosted triple.

```json
{"schemaVersion":1,"records":[{"rfcId":"0001","providerId":"governance.hosted","target":"linux-x64","electronFixtureMajor":44,"artifacts":[{"name":"governance-contract-tests","path":"build/rfc-evidence/downloaded/governance/tests.xml"}]}]}
```

Run provenance is read once from the hosted environment, never from batch JSON.
The collector retains its actual repository catalog, complete 20-RFC/three-target
set and notification/image/MSIX preflight. Accepted RFC 0016 remains diagnostic
only. Batch ordering cannot alter output bytes. Unselected existing records keep
their actual provenance and bytes; this command does not assert their freshness.
The independent strict governance checks still validate the complete result.

All records and artifact bytes are staged and validated before publication.
Invalid input leaves the output manifest and existing retained artifacts
unchanged. An existing destination with different bytes is a collision and
fails before publication; identical retained files are reused. New immutable
artifact files are published before an atomic replacement of the output
manifest on the same filesystem. The output manifest must be a regular file
or an absent path outside the retained artifact tree. An I/O failure may leave unreferenced new
artifacts, but must not replace an existing retained file or publish a partial
manifest. Process/OS crash recovery is rerunning after inspecting unreferenced
artifacts; this is not a multi-filesystem transaction. Unsupported atomic move
fails explicitly. Staging is removed after success or failure.

Errors use CLI exit 2 and structured JSON. Batch-specific codes are
`rfc.record.batch-invalid`, `rfc.record.batch-duplicate`,
`rfc.record.batch-path`, `rfc.record.batch-collision`, and
`rfc.record.batch-publish`. Existing recorder errors remain unchanged for record
validation and hosted provenance. No errors print artifact contents or credentials.

### Batch acceptance matrix

| ID / source | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| CI-B1 / deterministic recording | Batch output equals sequential recording and preserves unrelated records | Three targets, shuffled entries, repeated identical input, exact binary/CRLF bytes | Governance contract tests on macOS arm64, Windows x64 and Linux x64 | `KWebRfcEvidenceBatchTest.batchEqualsSequentialRecordingAcrossTargetsAndKeepsHistoricalRecords`; `batchRetainsCompatibilityBytesAndRejectsWrongTargetOrRuntime` | [Hosted batch evidence](#batch-final-acceptance-review), `8952e7b` / `2eeb6c7` | PASS: equivalence and retained-byte assertions passed on every target |
| CI-B2 / strict input | Invalid batch cannot claim support | Empty/257 entries, unknown fields, wrong types/version, duplicate identity, missing artifact, non-Implemented RFC, invalid report/runtime, unsafe path | Batch and real CLI subprocess tests on all three targets | `strictSchemaRejectsUnknownFieldsWrongTypesVersionsAndSizeBoundaries`; `lastRecordFailureDoesNotPublishEarlierRecordsOrArtifacts`; `unsafePathsAndInvalidSourceRevisionNeverEscapeStaging` | [Hosted batch evidence](#batch-final-acceptance-review), `8952e7b` / `2eeb6c7` | PASS: negative and boundary assertions passed on every target |
| CI-B3 / publication | No partial manifest or overwritten existing evidence | Last entry fails; pre-existing identical/different destination; publish failure; cleanup | Real temporary filesystem tests on all three targets | `existingArtifactsCannotBeOverwrittenAndIdenticalRerunIsSafe`; `twoProvidersCannotPublishConflictingBytesAtTheSameRetainedPath`; `outputDirectoryFailsWithStructuredPublishErrorAndPreservesManifest`; `ioFailureAfterPublishingAnArtifactKeepsManifestAndAllowsRetry`; `linkedSourceDirectoryIsRejectedOnEveryHost` | [Hosted batch evidence](#batch-final-acceptance-review), `8952e7b` / `2eeb6c7` | PASS: real I/O failure/retry and redirect rejection passed without skipped tests |
| CI-B4 / provenance | Same hosted provenance and target rules as individual recording | Local invocation rejected; missing provenance rejected; target outside triple rejected; supplied JSON provenance rejected | CLI subprocess and existing provenance tests on all three targets | `actualCliRecordsFromHostedEnvironmentAndRejectsLocalAndInvalidOptions`; existing `KWebRfcGovernanceCliTest` | [Hosted batch evidence](#batch-final-acceptance-review), `8952e7b` / `2eeb6c7` | PASS: real subprocess failures and original hosted provenance preserved |
| CI-B5 / orchestration | One Gradle launch records the same full hosted set | Exactly 60 unique records; all artifact roles/compatibility/service bindings retained; missing late artifact and notification/image/MSIX failures block | Python collector tests on Linux plus actual hosted aggregation | `.github/scripts/tests/test_aggregate_rfc_evidence.py` (single launch, complete identity set, artifact roles, late failure, paths with spaces); existing notification/image/MSIX tests | [Hosted batch evidence](#batch-final-acceptance-review), request and run 38198668788 | PASS: 40 script tests, one launch, 60 unique identities and all required roles |
| CI-B6 / preserved guarantees | Real native and packaging proof remains complete | Current full runtimeCheck on three targets; actual batch collector and strict governance; imported artifact digests | CI verify, rfc-evidence and checked-in evidence jobs | Existing full suite and new batch recording | [Hosted batch evidence](#batch-final-acceptance-review), complete manifest and retained artifacts | PASS: three full target jobs and fresh validation succeeded; imported strict check has zero findings |
| CI-B7 / universal completion | Documentation, packaged schema, timing and full-diff acceptance agree | Packaged schema matches input; document review; actual readiness/final review; no public OS API/support changes | Resource/schema tests, actionlint, diff review, hosted timing | `packagedSchemaMatchesBatchFieldsAndBoundaries`; built JAR inspected; EVIDENCE.md and this matrix | [Final review](#batch-final-acceptance-review), run 38198668788 / baseline 38189044137 | PASS: packaged schema, full-diff/artifact review and measured timing; no capability promotion |

### Batch readiness review

2026-10-12, Codex separate same-contributor contract review, on base `6c28579`
plus the contract v1 and CI-B1--CI-B7 matrix above: **READY**. Reviewed the real
collector, recorder, CLI, existing validation and repository-evidence tests.
The process boundary can be batched without changing record serialization or
the native suite. Staging and same-filesystem `ATOMIC_MOVE` use JDK 25 on all
three advertised hosts; real filesystem/subprocess tests and hosted aggregation
are required before acceptance. Existing artifacts are immutable during batch
publication, making a failed manifest move leave only unreferenced new files.
No support or platform scope changes are authorized. Historical RFC acceptance
is preserved; this is not an independent-person or retrospective approval.

### Batch implementation review before hosted verification

2026-10-12, Codex separate same-contributor review of the working diff against
`6c28579`, including the new batch source, tests and packaged schema. Confirmed
all 20 RFC/three-target entries, services, matrix rows and raw artifact roles
are preserved; RFC 0001 additionally retains batch tests and the actual request.
No workflow/native suite or capability status is changed. The compatibility
report fixture is shared with the single-record tests to exercise both paths.

Local macOS arm64 / Temurin 25: governance `contractTest` passed 85 tests with
zero failures or skips; `jar` contains the exact batch schema and recorder;
40 Python evidence tests passed; Bash syntax, actionlint and `git diff --check`
passed. The strict-number test found implicit JSON string-to-integer coercion;
explicit JSON integer validation fixed it and the full contract suite passed.
A further review added a real mid-publication I/O failure and successful retry,
plus missing/duplicated/malformed CLI-option cases; the full suite passed again.
These are local results, not hosted evidence. CI-B1--CI-B7 remain NOT_RUN for
final acceptance until real three-target verification, evidence import and final
row-by-row review are complete.

### Batch final acceptance review

2026-10-12, Codex separate same-contributor review of PR #80, implementation
head `8952e7baf02af5366bf0b683359ef00621700273` against base `6c28579`, plus
the evidence-import and acceptance-documentation diff. This is not an
independent-person approval. Reviewed every changed source/test/schema/script
and documentation file, including added files and every imported referenced
artifact's bytes and digest. No application API or capability status changes.

Hosted [run 38198668788](https://github.com/kingsword09/KWebShell/actions/runs/38198668788)
tested PR merge revision `2eeb6c7aec17627be57fbc8ab3bc52d05cd68a3a`.
The macOS arm64, Windows x64 and Linux x64 full verification jobs passed,
including native runtime, platform sessions and packaging. Each target passed
85 governance contract tests, including all 12 batch tests, with zero failures,
errors or skipped tests. The aggregation job passed all 40 script tests, recorded
60 unique identities and passed strict governance on the fresh manifest.

Retained RFC 0001 evidence is under
[`evidence/artifacts/0001/2eeb6c7aec17627be57fbc8ab3bc52d05cd68a3a/`](evidence/artifacts/0001/2eeb6c7aec17627be57fbc8ab3bc52d05cd68a3a/).
Each target retains `governance-batch-tests/TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcEvidenceBatchTest.xml`,
`governance-contract-tests/TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml`
and `batch-plan/batch.json`. All 60 records preserve the actual hosted run and
merge revision; all referenced artifact hashes were checked before import.
Historical artifact bytes were compared and preserved, not re-recorded locally.

Review finding: adding post-run timing to `DESIGN_PLAN.md` and `EVIDENCE.md`
would invalidate other RFCs that bind those documents. Those additions were
removed, and the timing/review is recorded here instead. No contract binding
was weakened or deleted to make evidence pass.

The original checked-in-evidence job failed because the implementation changed
bound contracts before the new hosted manifest was imported. This failure is
retained honestly, not described as a green run. After importing the manifest
and exact artifact bytes, local `:kweb-rfc-governance:check` passed with zero
findings across 42 RFCs. The evidence-import commit must also pass its hosted
checked-in-evidence gate before merge.

Recording ran from 03:36:43 to 03:37:52 UTC (69 seconds); the aggregation job
ran from 03:35:22 to 03:38:32 UTC (190 seconds). Baseline run 38189044137 used
916 seconds for recording and 1050 seconds for the job. The comparison is
recording overhead only; no required native verification was removed.

Final row-by-row decision: CI-B1, CI-B2, CI-B3, CI-B4, CI-B5, CI-B6 and CI-B7
are **PASS**, subject to the imported commit's required hosted gate. No row is
NOT_APPLICABLE, and no skipped required scenario is accepted. Merge remains
blocked until that gate succeeds. Change-aware module selection is a separate
objective and is not claimed by this batch change.

## Change-aware CI contract v1

One complete objective replaces unconditional runtime verification with a
deterministic plan; it does not weaken contract bindings or capability claims.
`.github/ci/suites.json` registers every included Gradle module's non-empty
verification roots and every Implemented RFC's raw-evidence producers. A module
or Implemented RFC without a rule is an error, including new modules. Global
toolchain/build/runtime/CI-policy changes select full verification deliberately,
not as a silent fallback for unknown files. Ordinary Markdown and retained
evidence run only lightweight checks. A stale document-only contract fails
strict governance instead of starting the native matrix or trusting old proof.

The planner accepts `--repository-root`, `--graph`, `--changes`, `--output`
and optional `--full`. Its version-1 JSON output names the source revision,
changed paths, affected modules, selected suite roots, complete task closure,
reasons, RFC refresh IDs, raw artifact families and required setup features.
Input and output JSON reject unknown fields and unsupported versions. Paths are
repository-relative and reject traversal, backslashes, symlinks and private
absolute paths. Collection is bounded to 20,000 changed paths, 256 modules,
50,000 graph tasks and 256 refresh RFCs. Failures emit structured `ci.*` JSON
and exit 2, never an apparently successful empty plan. No credentials or
artifact contents are printed. There is one writer per plan/manifest.

The pinned Gradle's `--dry-run --no-configuration-cache` graph export runs no
tests or native builds. It reads actual `ProjectDependency` declarations and
`TaskExecutionGraph.getDependencies`, including generated bindings and native
packaging edges. Reverse dependencies include test/codegen and cross-project
task consumers; ordering-only edges do not imply consumption. Every platform
exports its own graph and audits its selected task closure before execution.
Required test actions disable up-to-date reuse and build-cache reads/writes
through `fresh-verification.gradle`; compilation/build caching remains enabled.
Missing roots, unresolved dependencies or graph drift block
the run. Existing serial desktop-session ordering remains intact.

PR changes use the merge-base of the event base and source head, with Git
NUL-delimited `--no-renames` paths so renames include both removed and new paths.
The checkout/tested merge revision remains the evidence source. Push changes
use `before`/`after`; an initial push, scheduled run and manual full request are
explicit full plans. Evidence-only imports additionally compare against the
last implementation commit: a valid import with no subsequent bound changes
runs strict governance without another CEF cycle. Corrupt/missing evidence
fails strict validation; it is not trusted or synthesized by the planner.

Current digests are the existing tracked-file SHA-256 algorithm. Missing,
stale-runtime or stale-contract Implemented records in implementation/full plans select all required hosted
targets and their raw producers; still-current unrelated records remain
byte-for-byte unchanged. Fresh raw evidence is downloaded only for planned
families. Notifications, image and Windows MSIX preflight remain required when
their producer/record is selected. Partial refresh validates every required
target before one `recordBatch` publication. No-refresh plans do not invoke the
recorder. Catalog support status is never changed by planning or aggregation.

One stable `CI Gate` runs with `always()` and succeeds only when planning,
lightweight validation and every selected platform, aggregation and strict
governance job succeeded. Legal non-selection is distinct from a skipped,
failed or cancelled required job. Plan artifacts and per-target graph/task
audits are retained for 14 days. Same-PR superseding runs cancel older runs;
main and scheduled runs are not cancelled by a different PR. Native setup,
Node and CEF provisioning are conditional on actual selected tasks, not just
module names. Platform availability is never inferred from another host.
The workload benchmark and live HTML5 checks retain their existing Windows-only
hosted requirement; per-target audits omit these roots on macOS and Linux.
An evidence-only PR tail avoids a repeated native cycle only when all required
digests are current, every earlier implementation path is covered by a
registered RFC binding, and the tail actually imports retained evidence.
Unbound code, stale digests or another implementation edit retain full PR scope.
RFC 0001 additionally binds the new planner, suite registry, exporter and tests;
these files govern completeness and must invalidate their own evidence.

### Change-aware acceptance matrix

| ID / source | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| CI-N1 / Git input | Correct PR/push scope, deletes and renames | Merge-base divergence, initial push, spaces, deleted module, invalid base/path | Python real-Git fixtures and hosted event plan | Planned change collector tests | NOT_RUN | NOT_RUN |
| CI-N2 / dependency selection | Changed modules and actual reverse consumers run | Independent tray, native ABI, codegen/test edges, cycles, new module missing roots | Python fixtures and real Gradle graph on all targets | Planned planner and graph-audit tests | NOT_RUN | NOT_RUN |
| CI-N3 / fail-closed input | Unknown inputs or incomplete registration cannot skip tests | Invalid/extra JSON fields, limits, unknown code path, missing task/RFC rule | Python boundary/negative tests and actual CLI | Planned strict planner tests | NOT_RUN | NOT_RUN |
| CI-N4 / lifecycle and execution | Only selected tasks/setup run; complete tests remain fresh | No CEF in tray closure, conditional fixtures, benchmark selection, failed/cancelled mandatory job | Native tray-only probe, task audit and gate tests on all targets | Planned runner/audit/gate tests | NOT_RUN | NOT_RUN |
| CI-N5 / evidence freshness | Only stale/missing required records refresh | Runtime change, bound Markdown, current history, three targets, malformed manifest | Planner/collector tests and strict hosted governance | Planned digest and partial-record tests | NOT_RUN | NOT_RUN |
| CI-N6 / incremental publication | No skipped preflight, missing artifact or partial manifest | Single RFC refresh, no refresh, absent last target, late invalid proof | Real collector tests plus existing batch filesystem tests | Planned partial collector tests | NOT_RUN | NOT_RUN |
| CI-N7 / native/platform proof | Graph and selected native verification work on every target | Real tray-only suite on three hosts; full workflow-change validation; packaging/Wayland/notifications preserved | Full hosted matrix and retained targeted plan/audit/output | Planned hosted conformance steps | NOT_RUN | NOT_RUN |
| CI-N8 / CI integration | Fixed gate, scheduled full, cancellation and cache obey plan | Documentation/evidence only, required skipped/failed jobs, real event baselines | Workflow validation, gate unit tests and hosted outcomes | Planned actionlint/workflow tests | NOT_RUN | NOT_RUN |
| CI-N9 / universal completion | Docs, exact evidence import and full-diff review agree | No changed support status, schema/config and retained artifact inspection | All tests, packaging, hosted import and final review | This matrix, design objective and contributor docs | NOT_RUN | NOT_RUN |

### Change-aware readiness review

2026-10-12, Codex separate same-contributor contract review on `9d99ae8`
plus this contract and CI-N1--CI-N9 matrix: **READY**. The actual pinned Gradle
9.7 task graph was exported on macOS using `--dry-run`; 523 tasks and all 29
modules were inspected without building CEF or running native tests. Earlier
probes that queried task dependencies after configuration failed Gradle's
exclusive-lock checks; the settled implementation uses the public scheduled
graph API instead. Cross-platform graph differences are audited on each target,
and all required native probes remain blocking. Existing `recordBatch` provides
the required atomic manifest boundary for partial refreshes. Reviewed all
current producers, preflight scripts, contract bindings and fixture setup.
No unresolved external behavior is delegated to inference or Copilot, no
binding is removed, and no application API is changed. This is not an
independent-person approval. Final implementation and hosted acceptance remain
NOT_RUN.

Readiness addendum, 2026-10-12, same-contributor review: **READY** for the
three-platform graph union. Lightweight graph jobs export each actual host
without running tests, and the planner unions their dependency edges before
selection. This removes the need to guess Windows/macOS-specific consumer edges
from a Linux graph. Per-target audits still reject undeclared setup or missing
verification roots. Newly registered modules are included in export even before
being added to the historical `runtimeCheck` aggregate. The stable gate also
depends on the graph jobs; a missing platform graph prevents planning.

Graph/platform selection is bypassed entirely for pure documentation/evidence
events; strict governance remains required and stale evidence blocks them.
The verification init script disables cached verification actions while leaving
compilation cacheable. Contract-policy changes select governance plus whichever
existing digests actually become stale; RFC 0001 binds `contracts.json` itself
so changing evidence policy cannot reuse its old approval. No existing binding
is removed. These refinements were separately reviewed as **READY** by Codex,
the same contributor, on 2026-10-12 before dependent execution.

Readiness addendum, 2026-10-12, same-contributor review: **READY** for pure
module registration and documentation isolation. Adding only Gradle `include`
entries plus corresponding new suite rules does not invalidate existing module
verification rules; the new module and actual consumers are selected without
declaring a global toolchain change. Changes to existing registrations or other
settings logic remain full. Documentation-only stale contracts block strict
governance without executing native tests, as required by repository policy.

### Change-aware implementation review before hosted verification

2026-10-12, Codex separate same-contributor pass on the working diff against
`9d99ae8`: all nine acceptance requirements are represented by the planner,
strict input/Git tests, collector tests, setup/audit workflow and contributor
guide. Confirmed no historical binding was removed and no supported application
API/matrix row changed. Full selection preserves every historical runtimeCheck
root plus registered new-module roots. Pure docs skip platform graph jobs and
use strict governance; evidence imports cannot hide unbound or later code.
Verification actions disable cache/up-to-date reuse; compilation remains cached.

Local macOS arm64 / Temurin 25: 67 Python script tests passed; real exported
Gradle graph validated all 29 registrations; the native tray and governance
contract roots passed without CEF/Node setup, with fresh verification actions
and cached compilation. The governance contract suite passed 85 tests without
failure or skipping. The full selected plan has 36 roots and 20 refresh RFCs,
and its macOS audit passed. Actionlint, Bash syntax and `git diff --check`
passed. These are local results, not hosted support evidence. CI-N1--CI-N9
remain NOT_RUN for final acceptance until hosted platform, exact import and
full-diff/artifact review complete.

Review refinement: RFC 0001's selected records also retain the actual CI plan,
policy-test transcript and per-platform task audit; full plans retain the native
isolation plan/transcript. Missing required proof blocks recording. The first
hosted Windows graph export found native jq CRLF on task-name lines; all task
and feature reads strip that transport carriage return, not retained evidence
bytes. The failed run is retained honestly and the new revision requires fresh
hosted verification. Local policy/evidence tests now total 68, all passing.

Readiness addendum, 2026-10-12, separate same-contributor review: **READY**
for retaining exact native selection transcripts. Run `38210599624` passed all
three platform suites and recording, but import review found that Windows
Gradle transcripts contain both CRLF and trailing spaces emitted by Gradle.
These bytes must not be rewritten to satisfy source formatting rules. Exempt
only RFC 0001's retained `ci-native-selection-log/*.log` from end-of-line and
end-of-file whitespace checks; retain space-before-tab checks and ordinary
source/other-log checks. Allow those retained transcripts through `.gitignore`
so that normal evidence staging cannot omit a manifest dependency. Bind both
Git policies and their regression tests to RFC 0001 so that the exceptions
themselves require fresh reviewed evidence. Verify
exact staged bytes, isolation of the exception and a new complete hosted run
before importing or accepting this objective. The earlier run's checked-in
governance failure remains a real failure, not an approval.

Readiness refinement, 2026-10-12, separate same-contributor review: **READY**
to register fresh-verification policy after project evaluation. Reviewing the
actual Linux transcript showed `verifyNativeTrayPackage` reused the earlier
probe result within the same run: its group was assigned after the init
script's early configuration callback. The policy must inspect final task
metadata while remaining configuration-cache compatible. Validate two real
tray/governance invocations, including configuration-cache reuse, and require
the package/test actions to execute in both without disabling compilation
caching. Supersede the in-flight policy run before accepting hosted evidence.
