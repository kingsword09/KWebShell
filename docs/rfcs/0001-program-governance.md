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
| CI-B1 / deterministic recording | Batch output equals sequential recording and preserves unrelated records | Three targets, shuffled entries, repeated identical input, exact binary/CRLF bytes | Governance contract tests on macOS arm64, Windows x64 and Linux x64 | `KWebRfcEvidenceBatchTest.batchEqualsSequentialRecordingAcrossTargetsAndKeepsHistoricalRecords`; `batchRetainsCompatibilityBytesAndRejectsWrongTargetOrRuntime` | NOT_RUN | NOT_RUN |
| CI-B2 / strict input | Invalid batch cannot claim support | Empty/257 entries, unknown fields, wrong types/version, duplicate identity, missing artifact, non-Implemented RFC, invalid report/runtime, unsafe path | Batch and real CLI subprocess tests on all three targets | `strictSchemaRejectsUnknownFieldsWrongTypesVersionsAndSizeBoundaries`; `lastRecordFailureDoesNotPublishEarlierRecordsOrArtifacts`; `unsafePathsAndInvalidSourceRevisionNeverEscapeStaging` | NOT_RUN | NOT_RUN |
| CI-B3 / publication | No partial manifest or overwritten existing evidence | Last entry fails; pre-existing identical/different destination; publish failure; cleanup | Real temporary filesystem tests on all three targets | `existingArtifactsCannotBeOverwrittenAndIdenticalRerunIsSafe`; `twoProvidersCannotPublishConflictingBytesAtTheSameRetainedPath`; `outputDirectoryFailsWithStructuredPublishErrorAndPreservesManifest`; `ioFailureAfterPublishingAnArtifactKeepsManifestAndAllowsRetry`; `linkedSourceDirectoryIsRejectedOnEveryHost` | NOT_RUN | NOT_RUN |
| CI-B4 / provenance | Same hosted provenance and target rules as individual recording | Local invocation rejected; missing provenance rejected; target outside triple rejected; supplied JSON provenance rejected | CLI subprocess and existing provenance tests on all three targets | `actualCliRecordsFromHostedEnvironmentAndRejectsLocalAndInvalidOptions`; existing `KWebRfcGovernanceCliTest` | NOT_RUN | NOT_RUN |
| CI-B5 / orchestration | One Gradle launch records the same full hosted set | Exactly 60 unique records; all artifact roles/compatibility/service bindings retained; missing late artifact and notification/image/MSIX failures block | Python collector tests on Linux plus actual hosted aggregation | `.github/scripts/tests/test_aggregate_rfc_evidence.py` (single launch, complete identity set, artifact roles, late failure, paths with spaces); existing notification/image/MSIX tests | NOT_RUN | NOT_RUN |
| CI-B6 / preserved guarantees | Real native and packaging proof remains complete | Current full runtimeCheck on three targets; actual batch collector and strict governance; imported artifact digests | CI verify, rfc-evidence and checked-in evidence jobs | Existing full suite and new batch recording | NOT_RUN | NOT_RUN |
| CI-B7 / universal completion | Documentation, packaged schema, timing and full-diff acceptance agree | Packaged schema matches input; document review; actual readiness/final review; no public OS API/support changes | Resource/schema tests, actionlint, diff review, hosted timing | `packagedSchemaMatchesBatchFieldsAndBoundaries`; built JAR inspected; EVIDENCE.md and this matrix | NOT_RUN | NOT_RUN |

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
