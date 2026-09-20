# RFC capability evidence governance

This guide explains how the RFC program keeps documentation, runtime descriptors,
migration matrix rows, packaged schemas, and hosted evidence from disagreeing. It
implements [RFC 0001](0001-program-governance.md).

The enforcement engine lives in the `kweb-rfc-governance` module and runs as the
`rfcGovernanceCheck` Gradle task on every change. It is a pure JVM check: it never
builds CEF, never runs the native matrix, and never publishes a capability.

## Inputs the check joins

| Input | Source of truth |
|---|---|
| RFC catalog | `docs/rfcs/NNNN-*.md` front matter |
| Capability evidence manifest | `docs/rfcs/evidence/manifest.json` |
| RFC contract bindings | `docs/rfcs/evidence/contracts.json` |
| Pinned CEF/Chromium identity | `runtime/cef-runtime.json` |
| Electron capability matrix | `KWebElectronCapabilityMatrix` (conservative runtime code) |
| Published service descriptors | `KWebAppPaths`, `KWebWindowControls`, `KWebDialogs` |
| Packaged schema | `rfc-evidence-manifest.schema.json` in the module resources |

## Evidence manifest format

The manifest is versioned JSON (`schemaVersion: 2`) with a `recordsSha256` digest
over the canonical serialization of the sorted records. Every record is one
hosted-target support claim and must identify:

- RFC id and status (`rfcId`, `rfcStatus`; only `Implemented` is valid),
- service and schema versions (`serviceId`, `serviceVersion`, `schemaVersion`),
- the hosted target triple (`macos-arm64`, `windows-x64`, or `linux-x64`),
- the provider id, the pinned CEF/Chromium identity, and the Electron fixture
  major used by the migration fixture,
- immutable GitHub repository, workflow ref, run id/attempt, and source revision,
- a digest over every repository path declared for the RFC in `contracts.json`,
- the compatibility status (`READY` or `BLOCKED`), the capability matrix rows the
  record backs, and repository-relative paths plus digests for retained artifacts.

Unknown fields, duplicate record identities, non-hosted targets, and hand-edited
digests fail validation. The manifest itself is never edited by hand: it is
regenerated with the `record` command below.

## Recording evidence

Evidence records are derived from structured test output, never typed from
memory. To upsert one record into the manifest from a migration compatibility
report (the Phase 11 flow):

```sh
./gradlew :kweb-rfc-governance:rfcEvidenceRecord \
  -PrfcEvidenceArguments='record docs/rfcs/evidence/manifest.json docs/rfcs/evidence/manifest.json --catalog docs/rfcs --runtime runtime/cef-runtime.json --contracts docs/rfcs/evidence/contracts.json --repository-root . --rfc 0017 --provider menus.macos --electron-major 37 --matrix-row menu-tray --from-compatibility-report kweb-electron-migration/build/reports/electron-migration/compatibility.json'
```

The command only runs in GitHub Actions. It derives the target, repository,
workflow, run id/attempt, and tested revision from the runner environment. For
governance-only evidence (RFCs without a native service, such as RFC 0001), pass
the retained artifact file instead of a digest:

```sh
... --artifact governance-report=kweb-rfc-governance/build/reports/rfc-governance/status.json
```

The recorder copies each file under
`docs/rfcs/evidence/artifacts/<rfc>/<revision>/<target>/` and records that safe
repository-relative path. Governance checks re-hash those checked-in bytes; a
missing, replaced, or hand-edited retained artifact is stale evidence.

Regeneration from the same inputs is byte-for-byte deterministic: records are
sorted canonically, the digest is recomputed, and the output is stable. Two runs
with the same inputs produce identical bytes; any manual edit of a record or its
digest fails validation.

## Staleness

Evidence expires when a bound contract changes. The check marks a record stale
when:

- its CEF or Chromium identity differs from `runtime/cef-runtime.json`,
- any file or directory bound to that RFC by `contracts.json` has changed, or
- any retained artifact path is missing or no longer matches its digest, or
- its service version differs from the published service descriptor.

A stale record is never support: an `Implemented` RFC whose evidence is stale
reports the `stale` state with structured reasons and the matrix row it backed
becomes unbacked.

## RFC states and matrix backing

The check reports one state per RFC with structured reasons:

- `ready` — consistent; evidence is complete and current, or none is required.
- `blocked` — required evidence is missing, mismatched, or a supported matrix row
  has no backing. Blocking.
- `stale` — evidence exists but a bound contract or the runtime identity changed.
  Blocking.
- `platform-specific` — the RFC declares `Platform targets` in its front matter,
  so only the corresponding hosted targets must carry evidence.

Rules enforced across the catalog:

1. `Implemented` requires one current `READY` record per required hosted target
   (all three, unless the RFC declares platform-specific targets).
2. Evidence records may exist only for RFCs whose catalog status is
   `Implemented`.
3. Every supported matrix row (`DIRECT`, `ADAPTER`, `REWRITE`) must be backed by
   a delivered Phase 11 prerequisite (the allowlist in the checker mirrors
   [README.md](README.md#existing-prerequisites)) or by current evidence of an
   `Implemented` RFC that names the row.
4. A record that claims a row the runtime matrix still marks `UNSUPPORTED` fails;
   promote the row and land the evidence in the same change.
5. Two Implemented RFCs cannot claim the same matrix row.
6. `Implementing` and `Implemented` RFCs require their numeric dependencies to be
   `Implemented`.

## Redaction

Every manifest record, RFC front matter, and generated report is scanned for
runner tokens, cloud credentials, private keys, native pointers, and private
filesystem paths. A detection is a typed failure that names the field and the
pattern class; the sensitive value itself is never echoed.

## Hosted recording flow (RFC 0001-0004)

A single aggregation job records all twelve hosted records from the retained
artifacts of the same run's verification jobs:

1. Each verification target uploads its raw evidence: governance contract-test
   XML (`rfc-governance-*`), provider lifecycle output (`provider-lifecycle-*`),
   dialogs consent status (`native-dialogs-*`), and stream conformance output
   (`engine-integration-*`).
2. The `rfc-evidence` job checks the repository out (LF working tree), downloads
   those artifacts, and chains four upserts per target through
   `.github/scripts/aggregate-rfc-evidence.sh`, passing the target explicitly
   with `--target`; the recorder still requires the `Implemented` catalog, so
   the check-in commit lands the catalog flip together with the records.
3. The merged manifest plus the retained artifact tree are checked in together
   with the `Implemented` status flips. `rfcGovernanceCheck` then re-hashes the
   checked-in artifacts and contract paths on every later change; any mismatch
   marks the records stale.

## CI behavior

- Documentation-only pull requests (markdown and `docs/rfcs/**`) run
  `git diff --check` and `rfcGovernanceCheck` only — never the native matrix.
- Regular verification jobs run `runtimeCheck` on all three hosted targets.
  The independent checked-in evidence job runs full governance `check` and
  retains structured findings from `build/reports/rfc-governance/`.
- An RFC moves to `Implemented` only after its records are in the manifest and
  the three hosted jobs are green on the same `main` commit.


## Refreshing evidence after contract changes

CI runs `runtimeCheck` on all three hosted targets. This includes real native,
CEF, packaging and unit tests, including governance `contractTest`. The strict
checked-in repository-evidence test belongs to the separate governance stage;
`check` still requires both stages. This allows new evidence to be produced when
old source digests expire without accepting stale evidence for merge.

The recorder depends on successful runtime verification for every target. RFC
0001 retains the real governance contract-test XML; RFCs 0002–0004 retain their
provider, consent and stream outputs. After recording, the aggregation job runs
full governance tests and checks against the fresh manifest, then uploads the
manifest and retained artifact tree. Import those artifacts and rerun the strict
checked-in evidence gate before merge. A native/unit failure prevents recording;
`always()` is used only to retain diagnostics, never to authorize a support claim.
