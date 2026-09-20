# RFC contract review and acceptance

This guide defines two required review checks: implementation readiness before
coding, and requirement acceptance before merge. Keep the implementation
contract, acceptance matrix and review records in the RFC body. The existing
front-matter schema is unchanged. Use [RFC_TEMPLATE.md](RFC_TEMPLATE.md) and the
[PR template](../../.github/PULL_REQUEST_TEMPLATE.md).

A review is a separate pass over the contract and evidence, not a repetition of
the implementation summary. Record the actual reviewer or review-pass reference,
reviewed revision, findings and decision. A separate review pass by the same
contributor is permitted; do not describe it as an independent-person approval.
An agent must perform the review rather than request user confirmation for every
routine implementation choice. Scope or externally observable contract changes
must be documented and reviewed before dependent implementation continues.

## Check 1: implementation readiness

Run this check before `Proposed -> Accepted`, before starting an existing backlog
RFC, and when an accepted contract changes. `Accepted` means the required
externally observable decisions are settled and their validation is feasible.

| Area | Required decision or evidence | Blocks readiness when |
|---|---|---|
| Objective and scope | One complete deliverable, modules, supported targets, explicit non-goals and dependencies | Delivery requires several independently publishable objectives or an unfinished dependency |
| Inputs and outputs | Kotlin signatures or JSON schema, field types, required/default/null rules, schema/service/protocol version relationships, valid and invalid examples | Implementers must guess accepted input or externally visible output |
| Lifecycle and concurrency | Owners, state transitions, allowed threads, cancellation/close races, terminal-result precedence, ordering and numeric resource ceilings | A failure, concurrent close or overload has no defined result |
| Errors and policy | Stable error identifiers, permission/gesture/consent rules, origin/frame scope, CLI exit codes and retained outputs | A bypass, fallback or ambiguous success path remains possible |
| Platforms and feasibility | Exact OS/CEF APIs, platform limitations and required real-runtime probe results for risky assumptions | A critical mechanism is unverified or the proposed target lacks its declared implementation |
| Acceptance | Stable requirement IDs, observable outcomes, normal/negative/boundary scenarios, verification commands, targets and artifact expectations | A requirement cannot be translated into a falsifiable test or documented review assertion |
| Evidence lifecycle | Contract files/directories, why each belongs, digest inputs, invalidation conditions, trusted collection and first-run/refresh/merge sequence | Old evidence is trusted after relevant changes, unrelated files cause unexplained invalidation, or verification and recording depend circularly on each other |

Use concrete signatures, tables and examples where they settle a decision.
Internal class organization and algorithms remain implementation choices unless
they affect a declared invariant. High-risk probes may be isolated experiments;
they must retain real results and must not publish placeholder APIs.

Number all acceptance requirements, including applicable universal completion
requirements. Use stable IDs such as `A1` and `A1.1`; do not renumber existing IDs
to hide a removed requirement. Split compound requirements when one outcome
could pass while another fails. Map each original acceptance clause and each
[universal definition-of-done](README.md#universal-definition-of-done) area to its
rows or to an explicit applicability decision.

Readiness requires a review record containing:

- the RFC revision or commit and links to its implementation contract and matrix;
- the reviewer/review-pass identity and date;
- decisions made, probe references, and any findings with their disposition;
- `READY` or `NOT_READY`, with reasons.

Any unresolved required external behavior or feasibility finding makes the
result `NOT_READY`. Correct the contract or split the objective before coding.
A successful format check alone does not produce a readiness decision.

## One acceptance matrix through delivery

Each RFC uses this matrix shape. Populate planned scenarios and verification
before coding; add implementation, actual test/evidence references and results
as the same PR progresses. An empty results column is not a pass.

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|

Use `NOT_RUN`, `PASS`, `FAIL`, `BLOCKED`, or `NOT_APPLICABLE` for results.
`BLOCKED` means required behavior, verification or evidence is unavailable;
it does not mean that support exists. `NOT_APPLICABLE` requires a reason grounded
in the RFC's declared scope and a review reference. It cannot exclude a promised
platform, required security behavior, or a failing test. A planned exclusion
that changes scope must amend the contract before implementation.

A `PASS` row needs a concrete test symbol or named manual review, the expected
and observed result, and the appropriate retained evidence. Link real test paths,
commands and CI jobs/artifacts; a generic "CI green" link is insufficient if the
job never exercised that requirement. Native/CEF/FFM claims require real runtime
evidence on every declared target. Use code/document review for requirements
such as layer ownership or public API absence, and record what was inspected.

## Check 2: acceptance before merge

The author completes the matrix; a review pass checks it against the final PR.
Review the diff from the PR base to its head, including added files and retained
artifacts, rather than only unstaged edits in the worktree.

1. Account for every requirement and universal completion area. Check that the
   final implementation preserves the approved scope and all public contracts.
2. Inspect assertions and negative scenarios. Tests must reject a plausible
   incorrect implementation; object construction or file existence alone does
   not prove the promised behavior. Unsupported and unclassified use must fail
   through the declared error/exit path.
3. Follow each result to the actual test run or review record. Confirm required
   tests ran, required targets passed, and fixtures use the real native runtime
   wherever the capability requires it. A skipped required test blocks merge.
4. Verify source, fixture, schema, runtime and artifact identities. If a later
   change invalidates a row, rerun the affected verification and refresh its
   evidence. Unrelated changes need not invalidate a still-valid proof.
5. Inspect generated output, packaging, licenses/metadata, user documentation,
   migration instructions, capability matrix and RFC status. Verify retained
   evidence byte-for-byte; preserve runner line endings when they are part of
   the recorded digest.
6. Record the final reviewed revision, requirement coverage, findings and the
   decision `PASS` or `BLOCKED`. Every applicable row must be `PASS`, every
   exclusion justified, and every required CI gate green before capability
   publication or merge of its implementation objective.

A review-record-only commit may refer to the code revision it reviewed. Compare
it with the merge candidate and confirm that subsequent differences are only
the review record, documentation or evidence that do not invalidate the proof.
Re-review any changed contract or implementation. The reviewer must not invent
an approval identity, native result, CI URL, digest or completion state.

Multiple commits inside one focused PR are normal. Keep implementation, hosted
evidence import, documentation and the final supported-state change in that PR;
squash the green result once the objective is complete. Do not merge an
incomplete capability merely to obtain its first successful hosted run. See
[EVIDENCE.md](EVIDENCE.md) for the runtime-test/recording/strict-check sequence.

## Applying the checks to the existing catalog

New proposals use the revised template. Existing Proposed/Accepted RFCs must
supply a readiness record before their next implementation starts; their current
text is planning input, not automatic approval under this guide.

RFCs 0001-0004 retain their historical status and real hosted evidence. This
workflow does not invent retrospective review approvals. Changes to their
contracts require a new review and acceptance rows for the affected behavior,
with regression evidence for preserved guarantees.

RFC 0005 is already `Implementing`. Its adoption review identifies the
verified repair slice and remaining RFC-level work; it does not turn a green
repair PR into proof of the full RFC. Complete readiness review of its remaining
scope before another implementation objective starts. Do not mechanically add
empty sections to all backlog RFCs or mark them ready without reviewing them.

## Example: RFC 0005 report aggregation

This example illustrates the required specificity; it is not a new support claim.

| ID | Observable requirement | Verification that can disprove it |
|---|---|---|
| A6.1 | Different targets or conflicting versions of the same service produce the declared conflict error | Supply two otherwise valid reports differing in each conflicting field and assert the error |
| A6.2 | Each entry retains its exact origin, profile and channel/stream policy | Merge two entries with distinct policies and compare their complete retained records |
| A6.3 | Any blocked entry produces a saved BLOCKED aggregate and CLI exit code 2 | Run the actual CLI as a subprocess and inspect both exit status and output |
| A6.4 | Reversing input order leaves generated aggregate bytes unchanged | Serialize both orders and compare bytes; swapping policies between entries must change the digest |

Actual coverage and remaining gaps belong in the
[RFC 0005 adoption record](0005-migration-manifest-v2.md#review-workflow-adoption).
