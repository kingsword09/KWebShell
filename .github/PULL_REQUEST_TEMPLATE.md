## Outcome and scope

Describe the concrete problem, resulting behavior and one focused objective.
Link the RFC and design-plan acceptance criteria when applicable. Identify
breaking contracts and migration steps.

## Contract review

For an RFC implementation or contract change, link the RFC's implementation
contract, acceptance matrix and completed `READY` review record. State the
reviewed revision and any contract changes made after review.

For a proposal, documentation-only change or non-RFC objective, state which
review requirements apply and why the others do not. A template checkbox or a
green metadata check does not constitute contract review.

## Requirement acceptance

Link the completed RFC matrix, or provide the objective's requirement mapping
here. Account for all applicable requirements; justify exclusions explicitly.

| Requirement ID | Implementation / review reference | Actual tests, commands and required targets | CI artifacts / tested revision | Result and rationale |
|---|---|---|---|---|

While implementation is in progress, mark unverified rows `NOT_RUN` or `BLOCKED`.
Before merge, link the final acceptance review record and identify the reviewed
revision. Required tests may not be skipped; native claims require real runtime
artifacts. Confirm that changes after the reviewed revision preserve the proof.

## Validation and evidence

Report actual results for the complete PR diff, unit/integration tests,
packaging, generated output, documentation and capability-matrix updates.
For documentation-only changes, use the lightweight documentation/governance
checks and `git diff --check` against the PR base; do not run the CEF matrix.

For implementation changes, retain real hosted evidence and import required
records into this PR before final acceptance. State unresolved failures or
platform defects honestly. Keep supported-state promotion with the evidence.

See [the RFC review guide](https://github.com/kingsword09/KWebShell/blob/main/docs/rfcs/REVIEW.md) for review and evidence rules.
