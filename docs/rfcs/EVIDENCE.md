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
| Pinned CEF/Chromium identity | `runtime/cef-runtime.json` |
| Electron capability matrix | `KWebElectronCapabilityMatrix` (conservative runtime code) |
| Published service descriptors | `KWebAppPaths`, `KWebWindowControls`, `KWebDialogs` |
| Packaged schema | `rfc-evidence-manifest.schema.json` in the module resources |

## Evidence manifest format

The manifest is versioned JSON (`schemaVersion: 1`) with a `recordsSha256` digest
over the canonical serialization of the sorted records. Every record is one
hosted-target support claim and must identify:

- RFC id and status (`rfcId`, `rfcStatus`; only `Implemented` is valid),
- service and schema versions (`serviceId`, `serviceVersion`, `schemaVersion`),
- the hosted target triple (`macos-arm64`, `windows-x64`, or `linux-x64`),
- the provider id, the pinned CEF/Chromium identity, and the Electron fixture
  major used by the migration fixture,
- the test run id that produced the evidence,
- the compatibility status (`READY` or `BLOCKED`), the capability matrix rows the
  record backs, and the retained artifact digests.

Unknown fields, duplicate record identities, non-hosted targets, and hand-edited
digests fail validation. The manifest itself is never edited by hand: it is
regenerated with the `record` command below.

## Recording evidence

Evidence records are derived from structured test output, never typed from
memory. To upsert one record into the manifest from a migration compatibility
report (the Phase 11 flow):

```sh
./gradlew :kweb-rfc-governance:rfcEvidenceRecord --args='
  record docs/rfcs/evidence/manifest.json docs/rfcs/evidence/manifest.json
  --catalog docs/rfcs
  --runtime runtime/cef-runtime.json
  --rfc 0017 --provider menus.macos --target macos-arm64
  --test-run 2026-09-11-1234567890 --electron-major 37
  --matrix-row menu-tray
  --from-compatibility-report kweb-electron-migration/build/reports/electron-migration/compatibility.json'
```

For governance-only evidence (RFCs without a native service, such as RFC 0001),
declare the artifact digests explicitly instead of a compatibility report:

```sh
... --artifact governance-report=<sha256-of-retained-report>
```

Regeneration from the same inputs is byte-for-byte deterministic: records are
sorted canonically, the digest is recomputed, and the output is stable. Two runs
with the same inputs produce identical bytes; any manual edit of a record or its
digest fails validation.

## Staleness

Evidence expires when a bound contract changes. The check marks a record stale
when:

- its CEF or Chromium identity differs from `runtime/cef-runtime.json`, or
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

## CI behavior

- Documentation-only pull requests (markdown and `docs/rfcs/**`) run
  `git diff --check` and `rfcGovernanceCheck` only — never the native matrix.
- Every regular verification job runs `rfcGovernanceCheck` as part of `check` on
  all three hosted targets and uploads the joined report to
  `kweb-rfc-governance/build/reports/rfc-governance/` as retained evidence.
- An RFC moves to `Implemented` only after its records are in the manifest and
  the three hosted jobs are green on the same `main` commit.
