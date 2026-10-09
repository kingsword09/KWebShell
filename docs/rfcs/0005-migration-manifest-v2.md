# RFC 0005: Electron migration manifest v2, inventory, and codemod plan

- Status: Implementing
- Priority: P0
- Owners: `kweb-electron-migration`
- Depends on: RFC 0001, RFC 0004
- Electron migration surface: imports, preload globals, IPC, Node built-ins, native addons
- Target mapping: inventory infrastructure

## Objective

Scale the migration kit from one preload method to complete applications.
Version 2 records process ownership, lifecycle events, windows, Profiles,
preload methods/events/streams, IPC direction, user gestures, services,
packaging assumptions, Node/native dependencies, and exact RFC/matrix evidence.

## Manifest and tooling

The schema MUST be closed and versioned. Inventory parses TypeScript/JavaScript
ASTs and package metadata; regex-only classification is insufficient. Dynamic
channel names, computed Electron imports, `eval`, runtime `require`, unknown
preload exports, and native addons become explicit blockers.

Codemods MAY rewrite only deterministic host ownership and generated client
imports. Each edit emits a reviewable patch and never changes application source
in place without an output directory. Renderer preload shape is preserved only
for schema-declared methods/events.

## Acceptance

1. Fixtures cover ESM/CommonJS, aliases, re-exports, monorepos, preload
   declarations, bidirectional channels, dynamic use, Node built-ins, and native
   addons with exact file/line evidence.
2. v1 manifests migrate deterministically through a standalone command; runtime
   dual parsing is prohibited.
3. Generated Kotlin host checklist and TypeScript facade are byte-for-byte
   reproducible across Windows, macOS, and Linux paths.
4. A compatibility report binds source, lockfile, manifest, generated output,
   RFC evidence, services, Electron major, and CEF runtime digests.
5. Any unclassified or non-implemented RFC row exits nonzero before packaging.
6. The CLI can merge reports from multiple renderer entry points without
   weakening individual origin or permission policies.

## Non-goals

No Electron main-process runtime, universal preload polyfill, automatic semantic
rewrite of arbitrary JavaScript, or claim that compilation equals migration.


## Implementation verification

Manifest/report v2 enforce explicit renderer profile/origin ownership and closed
metadata. Inventory uses the separately pinned TypeScript AST API, conservatively
blocks unresolved packages/native addons and preserves source coordinates.
Aggregates retain complete entry reports and reject incompatible shared
contracts. The conformance fixtures cover shadowing, aliases, relative workspace
re-exports, dynamic execution, dependency evidence, stale inventory, policies,
nonzero CLI exits and shared generated golden files. The implementation remains
`Implementing` until the full RFC acceptance matrix, application-level contract,
and required hosted evidence are complete. A green repair slice does not certify
the whole RFC.


## Review workflow adoption

This is a retrospective coverage review of the delivered repair slice, not a
claim that a pre-implementation review occurred. Review date: 2026-09-20.
Reviewer: Codex, a separate contract/document review pass by the same contributor.
Baseline: `64e945e7da06a251a19ab1dbb2038acf5ba96829` (the squash result of
[PR #54](https://github.com/kingsword09/KWebShell/pull/54)). Its tracked tree
matches the tested PR head `aa4e8bc2a56d932b766b2c0dc1e2983cc50caf7e`.
[Hosted run 35509896760](https://github.com/kingsword09/KWebShell/actions/runs/35509896760)
passed all three runtime targets and both evidence checks. The review below
identifies what those checks actually establish for this RFC.

The later [main run 35511032275](https://github.com/kingsword09/KWebShell/actions/runs/35511032275)
failed on Windows in `applicationBenchmarkIntegrationTest` with
`Chromium compositor frame timestamps are not strictly increasing.` Linux,
macOS and strict governance passed; evidence aggregation was skipped. Retain
this as an unresolved baseline runtime failure, not a passing whole-repository
run. The PASS rows below cite the earlier successful, explicitly identified
runs and assertions; the full-RFC/U1 acceptance remains blocked. This
Markdown-only review does not change or rerun the benchmark implementation.

### Implementation contract findings

The current [manifest model][manifest-model] records renderer origin/profile,
windows, lifecycle declarations, channels, methods, streams and dependencies.
Its full-application objective also promises process ownership, preload events,
IPC direction and packaging assumptions; those concepts do not yet have explicit
complete models in this manifest. The next implementation must first define them
or propose a reviewed split of complete objectives. Existing typed request and
named-stream adapters remain the delivered slice.

| Finding | Required decision before further implementation | Readiness effect |
|---|---|---|
| C1 | Define the complete manifest schema for every concept promised by Objective, version relationships and invalid combinations; split independently deliverable objectives if necessary | NOT_READY |
| C2 | Enumerate the accepted source/package forms, module-resolution and ownership rules, static/dynamic classification, and exact location expectations for the full corpus | NOT_READY |
| C3 | Define the complete application-source and lockfile set and the evidence trust boundary; distinguish bound runtime metadata from independently verified runtime artifacts and list each invalidation rule | NOT_READY |
| C4 | Complete the requirement-to-test/evidence mapping below, including real CLI migration paths and assertions on the relevant detected usage rather than merely any blocking finding | NOT_READY |

The [tool README][tool-readme] and [evidence guide](EVIDENCE.md) document the
current implementations and evidence-refresh sequence. They are inputs to this
contract review, not automatic approval of the missing full-application contract.
No automatic semantic codemod is claimed: the codemod paragraph is optional and
the current host checklist is a review artifact.

### Acceptance matrix

IDs below map to Objective (`A0`) and the original numbered Acceptance clauses
(`A1`–`A6`). PASS rows are limited to the stated assertions at the reviewed
baseline. BLOCKED rows have incomplete contract/coverage; they do not establish
that every existing implementation path is incorrect. References to the hosted
run above apply to its three runtime targets, not an invented RFC 0005 evidence
record.

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A0 / Objective | Complete application concepts have explicit closed models and ownership rules | Process roles, preload events, IPC direction and packaging assumptions, including invalid combinations | Schema/contract review and common tests; all advertised targets | [Manifest model][manifest-model] | Baseline code review | BLOCKED: settle C1 before further implementation |
| A1.1 / Acceptance 1 | Comments/strings and shadowed bindings are not executed; package findings retain source coordinates | Comment/string eval, local require shadowing, literal require whitespace, package line/column | `:kweb-electron-migration:jvmTest`; all three hosted targets | [Inventory regression tests][inventory-tests]: `commentsStringsAndShadowedBindingsAreNotExecutableElectronUsage`, `staticRequireWithWhitespaceIsNotDynamic`, `packageDependenciesHaveExactCoordinatesAndNeverDisappear` | PR #54 hosted run / baseline above | PASS for these explicit assertions |
| A1.2 / Acceptance 1 | The complete declared source corpus has exact, correctly classified findings | ESM/CommonJS aliases, re-exports, workspace resolution, preload declarations, both channel directions, built-ins and binary/native-addon metadata | Define the corpus under C2 and add per-finding assertions; all three targets | [Inventory regression tests][inventory-tests] and [inventory contract tests][inventory-contract-tests] cover a subset | Existing subset tests passed in PR #54 | BLOCKED: full corpus and assertion coverage are not yet mapped; an unrelated blocking import must not mask a missed call |
| A2.1 / Acceptance 2 | The standalone migration library produces stable v2 bytes and rejects unknown old channel revisions | Valid v1 fixture, repeat serialization, invalid channel revision | `:kweb-electron-migration:jvmTest`; all three targets | [Manifest tests][manifest-tests]: `v1ManifestMigratesDeterministicallyToV2` | PR #54 hosted run / baseline above | PASS for library behavior |
| A2.2 / Acceptance 2 | The actual migrate CLI validates arguments and writes deterministic output; runtime v1 decoding is rejected | Successful subprocess invocation, invalid arguments/revisions, repeated output, direct runtime decode of v1 | Add explicit subprocess/runtime-decode coverage linked to the approved version contract | [CLI tests][cli-tests] currently exercise inventory; A2.1 covers the migration library | No complete dedicated run identified in this review | NOT_RUN for the full CLI/runtime assertion |
| A3 / Acceptance 3 | Generated TypeScript, declarations, JavaScript and host checklist match shared bytes on each platform | Same declared POSIX-path fixture on Windows, macOS and Linux | `:kweb-electron-migration:jvmTest` and generated TS/JS checks; all three targets | [Generator tests][generator-tests]: `generatedArtifactsMatchSharedCrossPlatformGoldenBytes`; [golden files][goldens] | PR #54 hosted run / baseline above | PASS for the declared fixture and its canonical path contract |
| A4.1 / Acceptance 4 | Source and lockfile changes invalidate an existing inventory and change report provenance | Modify preload source outside renderer and add/change lockfile after scanning | `:kweb-electron-migration:jvmTest`; all three targets | [Report regression tests][report-tests]: `lockfileAndNonRendererSourceChangesInvalidateInventory` | PR #54 hosted run / baseline above | PASS for the tested source/lockfile changes |
| A4.2 / Acceptance 4 | Every promised provenance input has a defined binding and invalidation rule | Manifest, output, RFC catalog/evidence, services, Electron major and CEF identities/artifacts; missing or changed inputs | Settle C3 and map each rule to tests or a specific provenance review | [Report builder][report-builder] binds these fields; existing report tests cover a subset | Baseline field/implementation review | BLOCKED: complete input/trust contract and per-rule verification remain to be recorded |
| A5.1 / Acceptance 5 | Unsupported dependency declarations and blocked CLI results cannot report success | Generation/reporting with UNSUPPORTED dependency; blocked inventory/aggregate output and exit code | Common/JVM and real CLI subprocess tests; all three targets | [Manifest tests][manifest-tests], [report tests][report-tests], [CLI tests][cli-tests] | PR #54 hosted run / baseline above | PASS for the named regression cases |
| A5.2 / Acceptance 5 | Every unclassified usage or non-implemented RFC mapping blocks before packaging | Full C2 corpus, undeclared preload exports and every declaration-to-RFC mapping | Complete C2/C4 mapping and run its negative cases before package acceptance | Existing conservative scanner and generator are the delivered subset | No full-corpus acceptance record | BLOCKED: partial regression coverage is not full RFC acceptance |
| A6.1 / Acceptance 6 | Incompatible shared contracts fail with the declared conflict result | Different targets and service versions; inspect runtime, matrix, RFC and Electron identity guards | JVM conflict tests and specific guard review; all three targets | [Builder tests][builder-tests]: `mergeReportsCombinesBlockedReasonsAndHashes`; [merge implementation][report-builder] | PR #54 hosted run plus baseline guard review | PASS for the current shared-contract guards |
| A6.2 / Acceptance 6 | Each entry retains its complete origin, profile and policy record | Merge distinct policies and compare the retained entry to its input | JVM policy-preservation test; all three targets | [Report tests][report-tests]: `aggregatePreservesEntryPoliciesAndIsOrderIndependent` | PR #54 hosted run / baseline above | PASS for schema-v2 entry reports |
| A6.3 / Acceptance 6 | A blocked entry yields a saved BLOCKED aggregate and exit 2 | Run actual merge CLI with one ready and one blocked entry | Subprocess exit/output assertions; all three targets | [Report tests][report-tests]: `blockedMergeWritesReportAndExitsTwo` | PR #54 hosted run / baseline above | PASS for the tested blocked aggregate |
| A6.4 / Acceptance 6 | Input-order changes preserve aggregate content; entry-policy reassignment changes its digest | Reverse input reports and swap policies between entries | JVM aggregate/digest assertions; all three targets | [Report tests][report-tests]: `aggregatePreservesEntryPoliciesAndIsOrderIndependent` | PR #54 hosted run / baseline above | PASS for the tested ordering and association invariants |
| U1 / Universal completion | Layering, runtime prerequisites, packaging, security, documentation and evidence are accounted for across the full RFC | Check each universal area against the approved complete scope, with reviewed exclusions | Full scope/application review and required native/packaging evidence | PR #54 verifies the existing repair slice and real CEF fixture | Existing repair evidence remains valid | BLOCKED for full RFC: finish applicability and scope mapping under C1/C4 |

### Review decisions and next action

Contract readiness for further implementation: **NOT_READY**, due to C1–C4.
Full-RFC merge acceptance: **BLOCKED**, due to A0, A1.2, A2.2, A4.2, A5.2 and U1.
The existing `Implementing` front matter is retained. This record does not revoke
the verified repair slice or promote the RFC to `Implemented`.

Before the next implementation objective, settle the full contract or propose
reviewed, independently complete RFC splits; finish planned verification for the
uncovered rows; then record a new readiness decision against that revision.
Track subsequent implementation and proof in the same matrix and one focused PR
per complete objective. Do not replace these decisions with PASS merely because
the metadata checker reports READY.

## Focused input-provenance objective (2026-10-08)

This is an independently complete repair of the existing report/inventory
guarantee under C3 and A4.1, not implementation of the unresolved application
model under C1/C2/C4. It publishes no new service, manifest concept or adapter.
The full RFC remains `Implementing` and `NOT_READY` for that broader scope.

### Contract amendment

`sourceSha256` binds every regular application-root file, including the manifest,
nested package metadata, configuration, extensionless resources and binary
assets, except lockfiles and the explicitly excluded directory subtrees named
`node_modules`, `.git`, and `.gradle`. Exclusions apply at any depth and are not
traversed. `lockfileSha256` independently binds every nested lockfile named
`package-lock.json`, `npm-shrinkwrap.json`, `pnpm-lock.yaml`, `yarn.lock`,
`bun.lock`, or `bun.lockb`. AST parsing and `filesScanned` still describe only the
existing supported source-format corpus; hashing an asset is not classifying
its contents as executable or granting it a migration mapping.

Entries are sorted by exact relative POSIX path. Each entry binds its path and
the SHA-256 of its unmodified bytes using the existing NUL/newline framing.
Included path components containing control characters are rejected, so a path
cannot inject a digest record. Ordering and absolute checkout location do not
affect the source/lockfile digests; line endings and all other file bytes do.

Included file, directory, dangling and root symbolic links, and non-regular
files are rejected with `migration.inventory.blocked` before parsing or READY
publication. No link is followed and no alternate root is selected. A missing
inventory root remains `migration.parser.unavailable`; a missing renderer or
generated tree remains an empty digest plus the existing blocking report
findings. Read/walk failures become typed `migration.inventory.blocked` errors
with the affected root and original cause, not an incomplete successful scan.
The trusted build must keep inputs unchanged during collection; this offline
tool does not claim filesystem snapshot isolation against concurrent mutation.

Any included file addition, removal, rename or byte change invalidates the
previous inventory. The report recomputes both trees and retains BLOCKED output
with `inventory-source-digest-mismatch` and/or
`inventory-lockfile-digest-mismatch`. Previously emitted schema-v2 digests must
be regenerated; there is no legacy-digest recognition or dual hashing. Keep
generated/report outputs outside the application input tree to avoid binding
the tool's own outputs. Runtime and RFC fields bind declared metadata, not proof
of the downloaded CEF artifact or substitute hosted runtime acceptance.

### Focused acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| P5.1 / C3, A4.1 | All regular application inputs and every nested lockfile participate in the declared independent digests. | Binary/extensionless/config changes; nested lock variants; add/remove/rename; empty tree. | JVM filesystem and report tests; Windows x64, macOS arm64, Linux x64. | [Filesystem tests][p5-files]: `everyRegularInputIsBoundIndependentlyOfParsedExtensions`, `everyNestedLockVariantHasAnIndependentDigest`, `fileRootsAreRejectedAndMissingOrEmptyTreesHaveTheEmptyDigest`; [report tests][report-tests]: `nonCodeAdditionRenameModificationAndRemovalInvalidateInventory`; `applicationInputs` shared by scanner/report. | [Three-target XML][p5-evidence], hosted run 37738042619 / tested merge 3a84e072; code revision 224a339. | PASS: real byte and path changes affect the required digest independently. |
| P5.2 / C3, A4.1 | Links and special files cannot disappear from an included input tree or escape it. | File/directory/root/dangling links, unsupported-format link, typed failures. | JVM filesystem tests; all three targets; FIFO negative case on POSIX targets. | [Filesystem tests][p5-files]: `linksAreRejectedRegardlessOfTargetKindOrSourceExtension`, `filesystemReadFailureIsTypedAndCannotReturnPartialEntries`, `fifoIsRejectedBeforeReadingOnPosixTargets`; [report tests][report-tests]: `generatedAndApplicationLinksCannotPublishReadyReports`; no-follow visitor and root inspection. | Same [three-target XML][p5-evidence], 16 test cases per target with no skipped tests. | PASS: actual OS-created links fail in scanner/report/filesystem paths. POSIX FIFO creation is NOT_APPLICABLE on Windows, reviewed below. |
| P5.3 / C3, A3.1 | Input ordering, absolute checkout location and excluded dependencies do not change digests; path framing is unambiguous. | Reversed creation order, relocated roots, excluded subtree mutations, control-character paths where OS allows. | JVM byte/path assertions; all three targets. | [Filesystem tests][p5-files]: `relocationAndCreationOrderPreserveDigestsButPathsAndBytesMatter`, `excludedDirectorySubtreesAreNotTraversedOrBound`, `ambiguousPathFramingIsRejectedRatherThanNormalized`; sorted POSIX-relative entries. | Same [three-target XML][p5-evidence] at tested merge 3a84e072. | PASS: exact bytes/path names matter; excluded links are not traversed. Windows rejects control-character path construction; POSIX rejects included names during traversal. |
| P5.4 / C3, A4.1, A5.1 | Stale inventory fails closed for non-code inputs; fresh inventory can restore readiness without ignoring changes. | Asset/config byte edits, addition/removal/rename; independently stale lockfiles; rescan. | Actual scanner/report and CLI exit/output tests; all three targets. | [Report tests][report-tests]: `nonCodeAdditionRenameModificationAndRemovalInvalidateInventory`, `lockfileAndNonRendererSourceChangesInvalidateInventory`, `actualReportCliRetainsBlockedOutputAndExitsTwoForAssetChanges`; source and lock digest mismatch findings. | Same [three-target XML][p5-evidence]; actual child JVM CLI output/exit assertions, not mocked commands. | PASS: stale assets yield retained BLOCKED JSON and exit 2; a fresh inventory restores readiness. |
| P5.5 / U1 | Existing native runtime, generated adapters, packaging and strict evidence gates remain valid. | Existing real CEF migration fixture, all advertised native providers and package checks. | Full hosted CI matrix and imported evidence; all three targets. | `.github/workflows/ci.yml` runtimeCheck, evidence recorder and strict checked-in governance; generated TypeScript/JavaScript and real CEF migration fixture. | Run 37738042619: all three verify jobs, recorder and strict governance PASS; [manifest][p5-manifest] imports 48 actual records from tested merge 3a84e072. | PASS: required native/package/runtime evidence imported byte-for-byte and strict local `:kweb-rfc-governance:check` passes. |
| P5.6 / U1 | Breaking digest semantics and the limited repair scope are explicit; no incomplete RFC support is published. | Review full diff, docs, unchanged RFC status and broader blocked rows. | Separate contract and final acceptance review, `git diff --check`, governance. | This amendment, design-plan objective, tool README and final review below; public schemas/adapters unchanged. | Reviewed code 224a339 / identical tested merge tree; follow-up changes are only this review and actual evidence import. | PASS for this focused repair; full RFC remains Implementing and BLOCKED. |

### Focused readiness review

- Date: 2026-10-08; reviewer: Codex, separate same-contributor review pass.
- Revision: base `cfc048d` plus the contract amendment and P5.1-P5.6 above,
  before dependent code changes.
- Findings: the extension allowlist omitted non-code application inputs;
  `Files.walk` did not descend directory links but also did not reject them;
  dangling links were filtered out before validation. The amended traversal and
  digest contract settles these observable cases without needing the unresolved
  application-process/preload-event model.
- Feasibility: JDK 25 filesystem traversal, byte hashing and link inspection are
  existing JVM tooling mechanisms; target-specific link/FIFO behavior must be
  proved by the required hosted tests. No new native runtime claim is made.
- Decision: `READY` for this complete input-provenance repair only. C1, C2, C4
  and full-RFC acceptance remain blocking; no retrospective approval is invented.

### Focused final acceptance review

- Date: 2026-10-08; reviewer: Codex, a separate same-contributor final review
  pass, not independent-person approval.
- Code revision: `224a33917c5ebf7e367c0e0100d56f2d2901bf1f`. The actual hosted
  pull-request merge revision is
  `3a84e07250fd12f738ce371648a423aafc2fd635`; both Git tree IDs are
  `b30de0b526890c886f13bc8070fea79f6a3f4d30`. This was checked by fetching
  `refs/pull/71/merge`, not inferred from a green badge.
- Reviewed the complete nine-file implementation diff and all 103 newly retained
  artifacts: 97 files referenced by the 48 freshly recorded support records,
  plus the six focused JVM XML files. The imported manifest and artifact bytes
  match hosted run `37738042619`; historical evidence remains unchanged. The
  recorder still creates no RFC 0005 or RFC 0016 support records.
- All three targets ran 61 migration JVM tests without failure/error/skip. The
  retained focused suites contain nine filesystem and seven report tests per
  target. P5.1-P5.4 map to the exact named normal/negative/boundary cases in the
  matrix; the CLI test checks the specific stale-source reason, saved BLOCKED
  JSON, original renderer policy and exit 2.
- P5.2 applicability: POSIX FIFO creation is `NOT_APPLICABLE` on the declared
  Windows filesystem target, not a successful native FIFO simulation. Windows
  still runs file/directory/root/dangling link and read-failure tests. For P5.3,
  Windows path construction itself rejects control characters; macOS/Linux
  create those names and prove typed traversal rejection. Neither branch skips
  a test or weakens digest-framing safety.
- Universal completion applicability: no common service API, native ABI,
  platform provider, permission/gesture rule or renderer adapter is added. New
  lifecycle/stream/cancellation/native-layout obligations are therefore
  `NOT_APPLICABLE` to this offline tooling repair; existing contracts are
  regression-tested by the real three-target runtime/CEF/package matrix. The
  parser's existing 60-second bound and owner/process behavior are unchanged.
  Filesystem confused-deputy/link escape, omitted inputs, stale evidence and
  digest-record injection are covered by P5.1-P5.4. Snapshot isolation against
  concurrently mutated inputs is explicitly not claimed.
- P5.5: hosted Windows/macOS/Linux runtime, native, unit and packaging jobs,
  evidence recording and strict checked-in governance all passed. Local real
  macOS CEF migration, native image/notification tests and verification of the
  503-entry host runtime payload also passed. Post-import local strict governance
  passes with zero findings. Metadata READY is not full-RFC acceptance.
- P5.6: inspected the RFC, design plan, tool README, test additions, traversal,
  scanner/report integration, and XML-retention workflow change. Breaking digest
  regeneration is documented with no dual behavior. Follow-up differences are
  documentation/review and byte-exact evidence only; none changes the executable
  contract or its test proof. Retained compatibility reports are snapshots of
  the tested revision, not reusable reports for subsequently changed metadata;
  packaging must regenerate its report against current inputs.
- Decision: **PASS for P5.1-P5.6 and the focused PR #71 objective**. C1/C2/C4
  and the broader full-application RFC acceptance remain **BLOCKED**. No full
  RFC status promotion or retrospective full-RFC approval is made.

[p5-files]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronFilesTest.kt
[p5-evidence]: evidence/artifacts/0005/3a84e07250fd12f738ce371648a423aafc2fd635
[p5-manifest]: evidence/manifest.json

### Post-merge formatting finding and focused correction

On 2026-10-08, main revision `74561bc` passed migration JVM tests and strict
governance, but `git diff cfc048d 74561bc --check` reported the retained Windows
MSIX JSON report's CRLF as trailing whitespace. The pre-merge diff check covered
tracked modifications, not the then-untracked imported artifacts. The initial
P5.6 complete-diff formatting claim was therefore incomplete; this finding is
recorded rather than retrospectively hidden. P5.1-P5.5 runtime/byte evidence and
all native verification remain valid.

The focused follow-up under the existing design-plan P5.6 objective must:

- W72.1: preserve every imported artifact byte/digest; do not rewrite Windows
  JSON line endings or update its recorded checksum to mask normalization.
- W72.2: recognize CRLF only for retained `docs/rfcs/evidence/artifacts/**/*.json`
  using a path-scoped Git whitespace attribute, retaining real trailing-space
  failures and the normal whitespace rules outside retained evidence.
- W72.3: exercise actual Git diff/staging operations and the real retained
  Windows report; pass full historical-objective and follow-up diff checks,
  script unit tests, strict governance and the documentation review gate.

Readiness review: Codex, separate same-contributor pass, 2026-10-08, base
`74561bc` and W72.1-W72.3 above; `READY` for this repository-metadata repair.
The existing XML attribute already establishes exact-byte CRLF retention; JSON
needs the same scoped rule. No service/API/provider, native artifact or bound
runtime contract changes, so repeating the CEF matrix is `NOT_APPLICABLE`.
Required verification is actual Git behavior, raw-byte checks, existing script
tests and strict governance. Final follow-up acceptance is `NOT_RUN` until those
checks and the complete diff review pass.

Follow-up implementation review: Codex, separate same-contributor pass,
2026-10-08, base `74561bc` plus the three-file attribute/test/review patch.
The JSON-only retained-evidence rule changes whitespace reporting, not stored
bytes or normal JSON rules. Four real-Git tests prove scoped CRLF acceptance,
continued rejection of actual trailing spaces, exact staging bytes and the
recorded digest of the real Windows MSIX report. All 35 script tests, local
strict governance, `git diff cfc048d 74561bc --check` with the scoped attribute,
and the complete follow-up diff check pass. W72.1/W72.2 are `PASS`; W72.3's
hosted documentation gate is still `NOT_RUN`. No evidence file or manifest was
edited. Full final acceptance waits for that gate rather than treating local
success as a hosted result.

Metadata-correction final acceptance: Codex, separate same-contributor pass,
2026-10-08, reviewed implementation `fd3fe42d1b55b17d145e3bf6b6c73eb4b90a1060`.
Hosted documentation/governance run `37746280328` passed at that revision.
Reviewed the complete three-file diff, scoped attribute, four actual-Git tests,
unchanged 97 recorded artifact digests and the preserved Windows report bytes.
All 35 local script tests, strict governance, full original-objective diff check
and follow-up diff check pass. W72.1-W72.3: **PASS**; this closes P5.6's discovered
formatting gap without rewriting historical evidence or the earlier finding.
The final record-only follow-up changes no attribute/test/native bytes. PR #72
was squash-merged as `62ca0b6b1d2e2386f6ecdfe1a891715d3a801343`, matching
current `main`; its commit used `[skip ci]`, and `gh pr checks 72` reported no
checks. The following review closes that missing documentation gate without
repeating the native matrix.

### Final documentation gate for PR #72

- Reviewed baseline: squash result `62ca0b6b1d2e2386f6ecdfe1a891715d3a801343`,
  with topic branch `rfc-evidence-json-line-endings` deleted.
- Local verification on that exact `main` tree: `git diff --check cfc048d 62ca0b6`;
  `python3 -m unittest discover -s .github/scripts/tests -p 'test_*.py' -v`
  (35 passed); and `./gradlew --no-daemon
  :kweb-rfc-governance:rfcGovernanceCheck` (passed).
- W72.1/W72.2 remain `PASS` from the reviewed #72 diff and retained-byte checks.
- W72.3 hosted documentation gate: **PASS**. Documentation workflow run
  `37753338921` passed in 41 seconds on PR #73 head
  `85b846e7b9024c8aa08b7f47a90a202331f43b44`. The final PR-head run must also
  pass before squash; the only follow-up change is this review result, which
  does not alter the check or any executable contract.
- Reviewer: Codex, separate same-contributor final review pass, 2026-10-08.
  The complete PR diff is this review record; no evidence bytes, attributes,
  tests, runtime contracts or native code change.
- Final decision: **PASS** for W72.1-W72.3, subject to the final PR-head
  Documentation workflow remaining green.
- Full RFC 0005 remains `Implementing`; broader contract/acceptance remains
  `BLOCKED`.

### Focused v1 migration CLI objective (2026-10-08)

This is a separately publishable conformance objective for Acceptance A2.2. It
completes the existing standalone `migrate` command against the already-defined
v1 and v2 manifest schemas. It does not claim readiness for the unresolved
full-application model, source-corpus inventory, or full RFC 0005.

#### Contract

The command is
`migrate <input-v1.json> <output-v2.json> <exact-renderer-origin>`. The input is
UTF-8 strict JSON with `schemaVersion: 1` and only channel `schemaVersion: 1`;
the origin must pass the existing v2 exact-origin validator. Runtime manifest
decoding continues to accept v2 only. The CLI does not silently upgrade input
during `manifest`, `generate`, `inventory`, or `report` commands.

Success writes the canonical compact v2 JSON plus one LF. It preserves the v1
application, import, channel, method, stream, and service declarations; changes
the manifest and channel revisions to 2; writes the exact supplied renderer
origin; and supplies the migrator's declared persistent `default` Profile and
single `main` window. It creates missing output parent directories. Identical
input bytes and origin produce identical output bytes on each target. Success
returns exit 0, prints exactly `Electron migration manifest migrated to v2.`
followed by LF, and writes nothing to stderr.

For this command, invalid argument count returns exit 2 with one JSON error
object on stderr (`code`, `message`, `details`) and no stdout. Its stable code
is `migration.command.invalid-arguments`. Invalid source JSON, unsupported
manifest/channel revisions, and invalid origins use their existing typed
manifest error codes. Argument, source, revision and origin validation
completes before output creation or mutation; an existing destination remains
unchanged when one of those checks fails. After validation succeeds, a
filesystem write failure is reported but may leave a partial destination.
Input and output paths that resolve to the same normalized path are rejected with
`migration.manifest.input-output-conflict`; the input is never rewritten in
place. This contract does not claim crash-atomic writes or filesystem snapshot
isolation while another process mutates the files.

#### Focused acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| P6.1 / A2.2 | The actual child-JVM `migrate` command publishes the complete canonical v2 result and exact success output. | Valid v1 fixture; verify preserved fields, exact origin, v2 channel revisions, default Profile/window, stdout, empty stderr, exit 0, and a newly created output parent. | `:kweb-electron-migration:jvmTest`; macOS arm64, Windows x64, Linux x64. | [CLI test][cli-tests] `migrateCommandWritesDeterministicV2WithoutChangingInput`; `KWebElectronMigrationCli`. | CI run [37759212962](https://github.com/kingsword09/KWebShell/actions/runs/37759212962), source revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`: 4 CLI tests per target, 0 failures/errors/skips. Retained [Windows][p6-win-cli-evidence], [macOS][p6-mac-cli-evidence], and [Linux][p6-linux-cli-evidence] JUnit XML; original hosted artifacts [11543150839](https://github.com/kingsword09/KWebShell/actions/runs/37759212962/artifacts/11543150839), [11542362828](https://github.com/kingsword09/KWebShell/actions/runs/37759212962/artifacts/11542362828), [11542217251](https://github.com/kingsword09/KWebShell/actions/runs/37759212962/artifacts/11542217251). PR code head: `4a2f4ef3179384d2d3e06ad71dea3cc50be41a4f`. | `PASS`; output fields/bytes, streams and exit code matched the contract on all three targets. |
| P6.2 / A2.2 | Invalid arguments and migration inputs fail with the typed JSON envelope before changing the destination. | Missing/extra arguments; malformed JSON; unknown manifest/channel revision; invalid exact origin; preserve a pre-existing output sentinel byte-for-byte. | Actual CLI subprocess and common manifest decoder tests; all three hosted targets. | [CLI test][cli-tests] `migrateCommandReturnsTypedErrorsBeforeChangingDestination`; [manifest test][manifest-tests] `v1ManifestMigratesDeterministicallyToV2`. | Same three retained CLI reports and hosted artifacts as P6.1. The CLI suites report 4 tests and the manifest suites 12 tests per target, all with 0 failures/errors/skips; run `37759212962`, revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`. Retained [Windows][p6-win-manifest-evidence], [macOS][p6-mac-manifest-evidence], and [Linux][p6-linux-manifest-evidence] manifest XML. | `PASS`; invalid requests returned the declared codes and exit 2, wrote no stdout, and left destinations unchanged. |
| P6.3 / A2.2 | Migration output is deterministic and runtime decoding does not accept v1. | Migrate identical input twice to separate files and compare bytes; pass v1 directly to the runtime decoder. | JVM CLI subprocess and common contract tests; all three hosted targets. | [CLI test][cli-tests] `migrateCommandWritesDeterministicV2WithoutChangingInput`; [manifest test][manifest-tests] `v1ManifestMigratesDeterministicallyToV2`. | Three retained CLI and manifest reports linked under P6.1-P6.2; all 4 CLI and 12 manifest tests passed on each target in run `37759212962`, tested revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`. | `PASS`; repeated bytes were identical, and the runtime decoder rejected v1. |
| P6.4 / A2.2 | The migrate command cannot overwrite its input through identical normalized paths. | Same input/output path; verify the original bytes remain unchanged and a typed error exits 2. | Actual CLI subprocess test; all three hosted targets. | [CLI test][cli-tests] `migrateCommandRejectsInputOutputCollisionWithoutModifyingInput`; `migration.manifest.input-output-conflict`. | Three retained CLI reports and hosted artifacts linked under P6.1; 4 tests per target, 0 failures/errors/skips in run `37759212962`, tested revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`. | `PASS`; the alias was rejected with exit 2 and input bytes remained unchanged. |
| P6.5 / A2.2, U1 | The focused tooling change is documented and verified on every hosted desktop target without claiming full RFC support. | Review the complete PR diff; verify migration tests and existing runtime, integration and package gates on every target; confirm full RFC remains `Implementing`. | Full CI runtime matrix, Documentation workflow, final code/document review; all three targets. | Migration README, RFC/design plan, CI verification, local `jvmTest`, TypeScript/JavaScript generation checks, governance `contractTest`, and `git diff --check`. | CI run `37759212962`: Linux job `113251241018`, macOS job `113251241219`, and Windows job `113251241844` passed runtime/native/unit/package verification. Documentation run `37759213109`, job `113251240626`, passed. Local focused tasks passed. Local aggregate module `check` could not configure CEF because this host has no extracted CEF (`-PcefRoot`); hosted runtime/package gates passed. | `PASS`; all three hosted targets and documentation governance passed; full RFC 0005 remains `Implementing` and `NOT_READY`. |
| P6.6 / U1 applicability | Native service, CEF ABI, renderer bridge, platform-provider, permission and browser-runtime behavior are outside this offline CLI objective. | Review the complete PR diff, including evidence refresh and retained test artifacts, for native/runtime/renderer changes. | Explicit code/document review; P6.1-P6.5 platform checks remain required. | No native, browser, renderer, service, permission or CEF source changed; exclusion is grounded in the standalone file-migration command's scope. | Reviewed complete PR diff at implementation head `4a2f4ef3179384d2d3e06ad71dea3cc50be41a4f`, including six retained target JUnit reports and 97 imported RFC evidence artifacts; final review recorded below. | `NOT_APPLICABLE` for native/browser behavior; no promised platform behavior is excluded. |
| P6.7 / evidence lifecycle | The PR refreshes every evidence record invalidated by its design-plan/RFC contract edits from hosted artifacts and restores strict governance without manual digest edits. | Hosted native/runtime verification on every target; aggregate and import fresh contract-bound records/artifacts; pass strict checked-in governance against those bytes. | CI verification, evidence aggregation, and strict governance; macOS arm64, Windows x64, Linux x64. | `docs/rfcs/evidence/contracts.json`, `.github/scripts/aggregate-rfc-evidence.sh`, [`manifest.json`](evidence/manifest.json), and [`artifacts/`](evidence/artifacts/). | Run `37759212962`, aggregator job `113261114170`, source revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`; strict-governance job `113264621600` passed. Imported manifest SHA-256 `689b3d95a691cd6de24a6d3841e3942daf5ac3204ab7123beba78234f59b6c45`: 48 records and 97 referenced artifact files, all byte/digest checks passed. It refreshes RFCs 0006-0009, 0012, 0027 and 0030. | `PASS`; manifest and all 986 retained artifact files were imported from the actual hosted artifacts, historical files remain, and no digests were edited manually. |

#### Focused readiness review

- Date: 2026-10-08; reviewer: Codex, separate same-contributor review pass.
- Reviewed revision: contract-only commit
  `aad138deed2eca9083ad561fc5e2304b4f68a05b`, before implementation.
- Findings: the v1/v2 schemas, channel-version relationship, origin validator,
  migration defaults and strict v2 runtime decoder are already explicit in the
  common contract. The existing command invokes the same migrator but has no
  subprocess success-path proof, uses a generic argument error, and permits an
  input/output path collision. P6 fixes those specific gaps without depending
  on unresolved full-application schema or source-corpus decisions.
- Evidence lifecycle: `DESIGN_PLAN.md` is bound by RFCs 0006-0009, 0012, 0027,
  and 0030. This planning edit makes their current three-target evidence stale
  until the implementation PR's hosted aggregation imports fresh artifacts;
  the local pre-refresh governance report also lists the matrix rows those
  records back as unbacked. The same PR must refresh them through CI and pass
  strict governance. No evidence digest may be edited manually.
- Feasibility: the existing JVM CLI test already launches the real command in a
  child Java process; the same mechanism covers success and failure on all
  hosted targets. No native or CEF feasibility probe is required for this
  offline command objective.
- Decision: `READY` for P6.1-P6.7 only. Full RFC 0005 remains `NOT_READY` for
  C1, C2, C4 and full A1.2/A4.2/A5.2/U1 coverage; this review does not approve
  or publish those capabilities.

#### First hosted implementation finding (2026-10-08)

- Run `37755970685` tested head `54d061475a9887d60d30e858e9ea516c77ca7b35`.
- Linux x64 passed the full hosted verification job. Windows x64 failed P6.1:
  `KWebElectronMigrationCliTest.migrateCommandWritesDeterministicV2WithoutChangingInput`
  at the exact stdout assertion. The CLI used `println`, which emits the host
  line separator; the approved command contract requires LF bytes on every
  target. The follow-up writes an explicit `\n`; the migration JVM suite passes
  locally after this correction. Windows hosted verification remains required.
- macOS arm64 failed the existing
  `:kweb-service-window-controls:windowControlsIntegrationTest` at
  `maximized-fullscreen`, reporting that the Compose window did not reach the
  requested state in time. No file in that provider or test changed in this
  PR. This remains a required platform blocker until a later complete hosted
  run passes; it is not excluded from P6.5.
- The failed Windows/macOS verification prevented `rfc-evidence` and strict
  governance from running in that first attempt. No hosted evidence was
  imported or inferred from the Linux-only pass.
- Reviewer: Codex, separate same-contributor review pass, 2026-10-08. The
  failure review inspected the actual Windows failing test symbol, macOS
  runtime exception and complete changed-file scope. The explicit-LF fix is
  limited to the P6 success message and preserves the pre-reviewed contract.

#### Corrected hosted run and focused final acceptance review

- Corrected CI run: [`37759212962`](https://github.com/kingsword09/KWebShell/actions/runs/37759212962), PR code head `4a2f4ef3179384d2d3e06ad71dea3cc50be41a4f`, hosted PR merge revision `105cb8d8813c8aec8d16ec3e46a29829b9572d82`. Linux x64, macOS arm64, and Windows x64 runtime/native/unit/package jobs all passed. Documentation/governance run [`37759213109`](https://github.com/kingsword09/KWebShell/actions/runs/37759213109) passed.
- Corrected target JUnit reports show 4 CLI tests and 12 manifest tests per target, with no failures, errors, or skips. The reports are retained under [`0005-migrate-cli-evidence/p6/37759212962/`](0005-migrate-cli-evidence/p6/37759212962/); their exact bytes were copied from the hosted `electron-migration-*` artifacts and checked against the downloads.
- Evidence aggregation job `113261114170` and strict checked-in evidence job `113264621600` passed. The fresh manifest and artifact tree were downloaded from run `37759212962`; all 48 records and 97 referenced artifact digests were checked before import. The initial `DESIGN_PLAN.md` edit's 33 local governance findings and unbacked rows are resolved by the imported records.
- Initial reviewer: Codex, separate same-contributor review pass, 2026-10-08. Reviewed implementation head `4a2f4ef3179384d2d3e06ad71dea3cc50be41a4f`, hosted artifacts, negative assertions and scope. The subsequent complete staged-diff check on 2026-10-09 found that the new Windows CLI evidence directory is outside the retained-XML whitespace attribute. P6 runtime results and exact artifact bytes remain valid, but final P6.5 formatting acceptance is `BLOCKED` until the scoped correction and checks below pass. Full RFC 0005 remains `Implementing` and `NOT_READY` for its unresolved broader contract and source corpus.

#### Retained CLI evidence formatting correction

- Readiness review: Codex, separate same-contributor pass, 2026-10-09, code head `4a2f4ef3179384d2d3e06ad71dea3cc50be41a4f` plus the staged hosted import. Decision: `READY` for this P6.5 completion correction. No executable migration, native or browser contract changes.
- Acceptance: preserve all six target JUnit files and every historical/native evidence byte; scope CRLF acceptance to `docs/rfcs/0005-migrate-cli-evidence/**/*.xml`; continue rejecting ordinary XML CRLF and actual trailing spaces in retained XML; prove byte-exact staging with actual Git operations. The existing Windows digest test must inspect the current index so newly imported evidence is verifiable before commit rather than requiring its path to exist in `HEAD`.
- Required checks: attribute/script unit tests, complete staged and PR-base diff checks, migration JVM/TypeScript/JavaScript tests, strict local governance, and green final PR-head hosted gates. A final row-by-row review must cover the attribute/test correction and imported artifacts before squash; the earlier green implementation run alone is insufficient.
- Correction review: Codex, separate same-contributor pass, 2026-10-09. Inspected the complete staged/import and PR-base diff, the path-scoped attribute and all seven attribute tests. All 38 script tests pass, including real Git checks of both Windows XML files, ordinary-XML rejection, trailing-space rejection and exact staging. Migration JVM, TypeScript/JavaScript and strict governance checks pass (`READY`, zero findings); complete staged and PR-base whitespace checks pass. All four imported ZIPs pass archive integrity checks. The six target JUnit reports match hosted bytes, all 97 referenced artifact hashes match, all 986 imported artifact files match the download, and all 889 historical files remain byte-exact.
- Row-by-row decision: P6.1 success/defaults, P6.2 typed failures, P6.3 determinism/v2-only decoding, P6.4 collision prevention, P6.5 documentation/platform/formatting completion, and P6.7 hosted evidence lifecycle are `PASS` against the retained run and correction checks. P6.6 remains reviewed `NOT_APPLICABLE` for native/browser behavior only. The correction changes no executable migration/runtime contract or evidence identity, so the retained three-target proof remains valid. Final integration is gated on green checks for the final PR head; record its exact revision and gate results in the PR before squash. This review does not promote full RFC 0005.

[manifest-model]: ../../kweb-electron-migration/src/commonMain/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronMigrationContract.kt
[manifest-tests]: ../../kweb-electron-migration/src/commonTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronManifestTest.kt
[inventory-tests]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronInventoryRegressionTest.kt
[inventory-contract-tests]: ../../kweb-electron-migration/src/commonTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronInventoryContractTest.kt
[cli-tests]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kwebshell/electron/migration/KWebElectronMigrationCliTest.kt
[generator-tests]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronPreloadGeneratorTest.kt
[goldens]: ../../kweb-electron-migration/src/jvmTest/resources/migration-golden
[report-tests]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronReportRegressionTest.kt
[builder-tests]: ../../kweb-electron-migration/src/jvmTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronCompatibilityReportBuilderTest.kt
[report-builder]: ../../kweb-electron-migration/src/jvmMain/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronCompatibilityReportBuilder.kt
[p6-win-cli-evidence]: 0005-migrate-cli-evidence/p6/37759212962/windows-x64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCliTest.xml
[p6-mac-cli-evidence]: 0005-migrate-cli-evidence/p6/37759212962/macos-arm64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCliTest.xml
[p6-linux-cli-evidence]: 0005-migrate-cli-evidence/p6/37759212962/linux-x64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronMigrationCliTest.xml
[p6-win-manifest-evidence]: 0005-migrate-cli-evidence/p6/37759212962/windows-x64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronManifestTest.xml
[p6-mac-manifest-evidence]: 0005-migrate-cli-evidence/p6/37759212962/macos-arm64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronManifestTest.xml
[p6-linux-manifest-evidence]: 0005-migrate-cli-evidence/p6/37759212962/linux-x64/TEST-io.github.kingsword09.kwebshell.electron.migration.KWebElectronManifestTest.xml
[tool-readme]: ../../kweb-electron-migration/README.md
