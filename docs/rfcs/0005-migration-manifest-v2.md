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
[tool-readme]: ../../kweb-electron-migration/README.md
