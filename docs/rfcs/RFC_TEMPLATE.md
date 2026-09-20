# RFC NNNN: Capability name

- Status: Proposed
- Priority: P0, P1, or P2
- Owners: module families, not people
- Depends on: implemented RFC identifiers
- Electron migration surface: exact Electron concepts being classified
- Target mapping: intended `DIRECT`, `ADAPTER`, `REWRITE`, or `UNSUPPORTED`
- Platform targets: omit for all desktop targets; otherwise `macos`, `windows`, and/or `linux`

## Objective

State one complete, publishable capability and its observable result. Name the
owning modules and dependencies. Split independently publishable objectives
before implementation; every resulting objective must deliver its own complete
vertical slice.

## Non-goals and applicability

Define adjacent behavior excluded from this RFC. Account for each universal
completion area: common contract, platform providers, renderer/migration,
security, packaging, evidence and documentation. Link applicable areas to
acceptance IDs; justify exclusions without excluding a promised capability or
platform. Prohibit fallback, ambient authority, generic IPC and placeholder APIs.

## Implementation contract

Follow [REVIEW.md](REVIEW.md#check-1-implementation-readiness). Settle externally
observable decisions here before requesting `Accepted`; name internal design
choices that remain free to the implementer.

### Common KMP and data contract

Provide concrete Kotlin signatures and/or JSON schema, service keys, scope,
request/result/event models and resource handles. Define each required/default/
null rule, valid and invalid examples, and relationships between manifest,
protocol, schema and service versions. Platform handles and Electron-shaped
operations stay out of the common service API.

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|

### Lifecycle, concurrency and limits

Define owners, permitted threads, event ordering, numeric ceilings,
backpressure/overflow behavior, cancellation and shutdown. Specify which terminal
result wins when failures, cancellation or owner close race, and how resources
are released exactly once.

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|

### Errors, renderer and migration policy

List stable errors and exact renderer operations, grants, native gestures, OS
consent, frame/origin boundaries and revocation behavior. Pin the Electron major
and declared preload shape/migration status. For CLI operations, define exit
codes and the output retained on failure. If renderer or CLI access is absent,
record the applicability decision.

| Condition | Stable error / outcome | CLI exit / retained output, if applicable | Acceptance IDs |
|---|---|---|---|

### Platform implementation and feasibility

Name the exact APIs on each advertised platform and relevant CEF/FFM boundaries.
Document affinity, handles, permissions, availability and cleanup. Link actual
probe results for critical unknown mechanisms before accepting the design.
Required probes without results block readiness.

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result, or reason none is needed | Acceptance IDs |
|---|---|---|---|---|

### Evidence lifecycle and delivery

Name the contract files/directories to bind and justify their breadth. Define
source/runtime/schema/fixture/artifact digest inputs and invalidation conditions.
Specify trusted collection, the initial run, refresh after a contract change,
and how the same PR imports evidence and reaches its final strict checks.
List code, schemas, generated output, native packages, documentation and matrix
rows that must be delivered together. Internal prototypes do not publish support.

## Acceptance matrix

Assign stable IDs to every requirement, including applicable universal
completion rules. Split independently falsifiable assertions. Fill observable
outcomes, normal/negative/boundary scenarios, commands and required targets
before coding; fill actual references and results during implementation.

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|

Results are `NOT_RUN`, `PASS`, `FAIL`, `BLOCKED`, or `NOT_APPLICABLE`.
Explain and review every exclusion. Keep required native and CEF tests on every
advertised target. Tests must reject plausible incorrect implementations; a
passing build or an existing output file alone is not proof of the requirement.

## Contract review record

Record the reviewed revision, reviewer or separate review-pass identity, date,
contract/matrix references, findings and dispositions, probe evidence, and the
explicit decision `READY` or `NOT_READY`. A draft must not claim a review occurred.
Unresolved required contract decisions or feasibility findings block `Accepted`
and dependent implementation. Re-review changed contracts.

## Merge acceptance record

Record the final reviewed revision, reviewer/review-pass identity, date, full-PR
diff review, row-by-row coverage, actual CI/artifact references, findings and
`PASS` or `BLOCKED`. Follow [Check 2](REVIEW.md#check-2-acceptance-before-merge).
Required rows must pass; document justified exclusions. Confirm that later
commits have not invalidated the reviewed code or evidence.

Name the final documentation, packaging, migration and capability-matrix changes.
Publish `Implemented` only with the complete accepted objective and its current
real evidence. Keep implementation and evidence updates in the same focused PR.
