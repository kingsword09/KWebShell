# RFC 0030: Reproducible application identity, signing, installers, and associations

- Status: Implemented
- Priority: P0
- Owners: `kweb-runtime-pack`, release packaging, desktop host
- Depends on: RFC 0001
- Electron migration surface: `app.setAppUserModelId`, default protocol client, file associations, packaged state
- Target mapping: `REWRITE`

## Objective and scope

Publish one closed application manifest and one deterministic packaging pipeline
for KWebShell. The pipeline binds the application identity, the CEF runtime
payload, helper ownership, platform registration metadata, declared capabilities,
and the selected installer format. A package is publishable only after its
payload, manifest, signatures, and association metadata have all been verified.

This RFC owns the packaging boundary required by RFC 0006. It does not own
runtime activation routing, single-instance leases, or the application lifecycle
service itself.

## Implementation contract

### A. Closed application manifest

The source manifest is `runtime/application-manifest.json`. It is strict UTF-8
JSON, schema version `1`, with unknown fields rejected and one trailing LF. The
canonical field order is:

```json
{
  "schemaVersion": 1,
  "applicationId": "io.github.kingsword09.kwebshell",
  "displayName": "KWebShell",
  "productVersion": "0.1.0",
  "publisher": "KWebShell Authors",
  "mainExecutable": "KWebShell",
  "helperExecutables": ["KWebShell Helper"],
  "icons": [{"path": "resources/icons/kwebshell.png", "kind": "application"}],
  "protocols": [{"scheme": "kweb", "role": "viewer"}],
  "fileTypes": [{"extension": ".kweb", "mimeType": "application/x-kwebshell", "description": "KWebShell document"}],
  "targets": {
    "macos-arm64": {"minimumOs": "13.0", "format": "macos-app-zip", "bundleId": "io.github.kingsword09.kwebshell"},
    "windows-x64": {"minimumOs": "10.0.17763", "format": "windows-msix", "aumid": "io.github.kwebshell"},
    "linux-x64": {"minimumOs": "glibc-2.35", "format": "linux-deb", "desktopId": "io.github.kwebshell.desktop"}
  },
  "capabilities": [],
  "providerResources": [],
  "update": {"channel": "stable", "keyId": ""}
}
```

`applicationId`, executable names, bundle/AUMID/desktop identifiers, protocol
schemes, extensions, MIME types, and paths have bounded portable grammars.
Every hosted target must have exactly one target entry. The builder rejects a
target omission, duplicate declaration, unknown capability/provider resource,
path traversal, mutable absolute path, or unsupported package format.

`isPackaged` is a generated build fact. The package contains
`application/packaged-state.json` with the manifest digest, target, product
version, and `isPackaged: true`; no runtime environment variable can change it.

### B. Deterministic package boundary

`KWebApplicationPackageAssembler` accepts only an absolute verified RFC 0001
application manifest, an independently verified signed runtime release pack,
and one explicit target. It produces a target package with the following
canonical records:

| Record | Purpose |
| --- | --- |
| `application/manifest.json` | canonical source manifest copy |
| `application/packaged-state.json` | immutable packaged-state fact |
| `application/registration.json` | protocol/file association declarations |
| `application/capabilities.json` | declared service/provider capability audit |
| `application/sbom.json` | payload and license inventory |
| `runtime/release.pack.zip` | byte-for-byte signed RFC 0001 runtime release |
| `signatures/platform.json` | external platform signature/notarization facts |

The package archive uses fixed timestamps, UTF-8 lexical entry ordering, fixed
permissions, no absolute paths, no symlinks crossing roots, and atomic
publication. The package digest covers the exact archive bytes. Rebuilding from
the same manifest, runtime release, target, and signing facts is byte-for-byte
stable.

### C. Platform package providers

One explicit target selects exactly one provider; there is no format detection or
fallback:

| Target | Format | Required platform facts |
| --- | --- | --- |
| macOS arm64 | `.app` inside a signed ZIP | bundle identifier, URL/file document declarations, `codesign --verify`, and notarization ticket/staple result when distribution mode is selected |
| Windows x64 | MSIX | AUMID, protocol/file associations, Authenticode/AppX signature identity, and package identity |
| Linux x64 | Debian package | desktop entry, MIME XML, AppStream metainfo, package architecture, and detached release signature |

The provider fails before publication when the declared signer/tool output is
missing, the observed identity differs from the manifest, the registration
metadata is incomplete, or the package contains an undeclared capability. Test
mode may use ephemeral keys/certificates, but it still verifies the same
structure and signature boundary; test artifacts cannot be marked as release
artifacts.

### D. Association metadata boundary

The package contains canonical target-specific association declarations. This
RFC does not install those declarations into a user OS or claim that a package
has received an activation. RFC 0006 owns the application lifecycle service and
the real install/activation/uninstall path over these declarations:

- macOS Launch Services for the `kweb:` scheme and `.kweb` document type;
- Windows per-user package registration for the protocol and file extension;
- Linux desktop-entry/MIME registration in an isolated user data directory.

The package verifier checks that each declaration is present, identity-bound, and
covered by the package signature statement. RFC 0006 must retain the observed
application identity, activation payload, and cleanup result when it exercises
the OS registration mechanism.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / closed manifest | The strict manifest validates and unknown/omitted/duplicate fields fail before packaging. | Valid canonical manifest; unknown key; missing target; duplicate protocol; absolute/traversal path. The validator also rejects invalid identity, version, association and provider-resource fields. | [`KWebApplicationManifestTest`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationManifestTest.kt); `:kweb-runtime-pack:verifyApplicationManifest`; the same JVM test suite on all three hosted targets. | `repositoryManifestIsCanonicalAndTargetComplete`, `unknownFieldsAreRejected`, `missingTargetIsRejectedBeforePackaging`, `absoluteProviderResourcePathIsRejected`; [`KWebApplicationManifestContract`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationManifest.kt) performs the complete closed-schema validation. | The RFC 0030 record in [`evidence/manifest.json`](evidence/manifest.json) binds the final contract digest and each hosted application-package report. | `PASS`: canonical source, unknown-field rejection, target completeness and unsafe-path rejection passed; hosted verification used the same checked-in manifest. |
| A2 / identity | All generated package identities derive from the one application ID and target entry. | macOS bundle identity, Windows AUMID and Linux desktop identity match; a conflicting platform identity fails before output. | [`KWebApplicationPackageTest`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackageTest.kt); independent package verification on macOS arm64, Windows x64 and Linux x64. | `packageRoundTripIsDeterministicAndVerifiesNestedRelease`, `wrongPlatformIdentityFailsBeforeWritingOutput`, `targetProvidersIncludeTheirRegistrationArtifacts`; [`platformIdentity`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt) and target metadata providers. | Three hosted `application-package-report.json` records under `docs/rfcs/evidence/artifacts/0030/<sourceRevision>/<target>/`, with package and manifest digests. | `PASS`: all three hosted providers were built from the one manifest identity; the conflicting macOS identity was rejected without creating output. |
| A3 / deterministic package | Same inputs and signature facts produce identical package bytes and manifest digest. | Rebuild with the same inputs; fixed archive timestamps/order/modes; nested release verification; output is published atomically. | [`KWebApplicationPackageTest`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackageTest.kt); real host-payload package integration on all three hosted targets. | `packageRoundTripIsDeterministicAndVerifiesNestedRelease`; [`KWebApplicationPackageAssembler`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt) canonical archive writer and atomic publisher; `KWebApplicationPackageIntegrationMainKt` builds and independently reopens the package. | Each hosted report retains `packageSha256`, `manifestSha256` and `runtimeReleaseSha256`; the final evidence manifest records their SHA-256 values and tested source revision. | `PASS`: repeated fixture builds were byte-identical and all hosted real-payload package builds reopened with matching digests. |
| A4 / platform providers | The selected macOS/Windows/Linux provider emits the declared package format and rejects missing/incorrect signing facts. | macOS ZIP-compatible app container, Windows MSIX container and Linux Debian `ar` package; wrong identity/signature facts fail before publication; no format fallback. | [`KWebApplicationPackageTest`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackageTest.kt); `:kweb-runtime-pack:applicationPackageIntegrationTest` on macOS arm64, Windows x64 and Linux x64. | `targetProvidersIncludeTheirRegistrationArtifacts`, `wrongPlatformIdentityFailsBeforeWritingOutput`, independent `KWebApplicationPackageVerifier.verify` in [`KWebApplicationPackageIntegrationMain.kt`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackageIntegrationMain.kt); platform metadata providers in [`KWebApplicationPackage.kt`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt). | Hosted application-package artifacts for `macos-arm64`, `windows-x64` and `linux-x64`; each report records the declared format, package digest and `status: PASS`. | `PASS`: each explicit provider emitted its declared format and the independent verifier accepted only matching signing/identity facts. |
| A5 / associations | The package contains identity-bound protocol/file association declarations for the next lifecycle objective. | `kweb:` URI; Unicode `.kweb`; multiple file types; missing declaration; wrong identity. | Package metadata/signature verification on all three hosted targets; OS registration is RFC 0006. | `KWebApplicationRegistration`, target metadata providers, package signature verifier | application-package report | `NOT_APPLICABLE` to RFC 0030 OS installation; reviewed scope boundary above |
| A6 / uninstall | Package output is atomically publishable and contains no implicit OS registration side effect. | Atomic publication; interrupted package write; package removal leaves no package-owned registration mutation. | Package assembler/verifier tests; OS uninstall is RFC 0006. | `KWebApplicationPackageAssembler`, atomic publication tests | application-package report | `NOT_APPLICABLE` to RFC 0030 OS uninstall; reviewed scope boundary above |
| A7 / tamper boundary | Any payload/resource/helper/schema/runtime/signature mutation fails before launch. | Mutated package bytes, package signature statement, nested runtime release or canonical record cannot pass independent verification. | `tamperedPackageCannotPassVerification`; independent verifier in the hosted package integration task on all three targets. | [`KWebApplicationPackageVerifier`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt) checks exact entry set, canonical metadata, package Ed25519 statement, and nested RFC 0001 release signature before returning success. | Three hosted package reports and package digests retained under the final evidence source revision. | `PASS`: the tampered package fixture failed verification, and hosted packages were independently reopened after construction. |
| A8 / capability audit | Package capabilities equal the closed manifest and provider resources. | Capability/provider records are canonical and cannot drift from the source manifest; SBOM runtime/license facts match the nested release. | Package round-trip verifier and real-payload integration on all three hosted targets. | Capability equality and SBOM equality checks in [`KWebApplicationPackageVerifier.verify`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt); `packageRoundTripIsDeterministicAndVerifiesNestedRelease`; package records are emitted by `KWebApplicationPackageAssembler`. | Hosted package reports retain package and nested-runtime digests; checked-in source manifest and final evidence manifest retain the contract and artifact hashes. | `PASS`: verifier equality checks passed for every hosted package; undeclared capability/resource data has no accepted package path. |
| A9 / packaged state | `isPackaged` is immutable package data and cannot be changed by runtime input. | Package state is generated as `true`, bound to manifest digest/target/version, and mismatched or malformed state fails verification; no environment input is read. | Package round-trip verifier and real-payload integration on all three hosted targets. | `KWebApplicationPackagedState` emission and equality check in [`KWebApplicationPackage.kt`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt); `packageRoundTripIsDeterministicAndVerifiesNestedRelease`; package format documentation. | All hosted application-package reports are bound to the final manifest digest and package digest. | `PASS`: the state record is package-generated and verifier-bound to the manifest, target and version; runtime environment is not an input. |
| A10 / migration | Electron builder/forge metadata maps only to declared fields and unsupported hooks block generation. | Valid closed mapping; unsupported hook; invalid target; non-canonical metadata. | [`KWebElectronPackagingContractTest`](../../kweb-electron-migration/src/commonTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronPackagingContractTest.kt) in `:kweb-electron-migration:jvmTest`. | `mapsClosedBuilderMetadataToTheApplicationPackagingReport`, `unsupportedHooksBecomeBlockingFindings`, `invalidTargetAndNonCanonicalMetadataAreRejected`; [`KWebElectronPackagingMapper`](../../kweb-electron-migration/src/commonMain/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronPackagingContract.kt). | Hosted verification runs execute the migration module check; the migration contract is included in the RFC 0030 contract binding. | `PASS`: supported declarations map to the closed report and unsupported hooks/targets/non-canonical input block generation. |
| A11 / universal completion | Tests, packaging, documentation, capability matrix, evidence and reviewed revision are complete. | Missing artifact, stale contract digest, skipped hosted target or changed contract after review blocks acceptance. | `:kweb-rfc-governance:check`, `git diff --check`, full PR diff review, and the hosted RFC evidence aggregation job. | [`DESIGN_PLAN.md`](../../DESIGN_PLAN.md), [`docs/application-package-format.md`](../application-package-format.md), RFC 0030 matrix, [`contracts.json`](evidence/contracts.json), and [`evidence/manifest.json`](evidence/manifest.json); final acceptance review is the Codex review pass by the same contributor as implementation. | The final RFC 0030 records in `evidence/manifest.json` must show three READY records with one contract digest and retained application-package report SHA-256 values; the exact tested source revision is recorded in each record's `run.sourceRevision`. | `PASS` after the final hosted aggregation and strict governance check: all applicable rows are PASS, the two lifecycle exclusions remain explicitly assigned to RFC 0006, and the complete PR diff is reviewed. |

## Readiness review

- Reviewed revision: `6df5dbb` (the pre-implementation RFC 0030 revision).
- Review pass: Codex contract review pass, same contributor as implementation;
  this is not an independent-person approval.
- Date: 2026-09-21.
- Decisions: the manifest is closed and target-complete; RFC 0001's signed
  runtime release is the only accepted runtime input; platform formats are
  explicit; signing facts are mandatory; registration/uninstall evidence is
  retained per target; `isPackaged` is generated data rather than mutable
  runtime state.
- Findings: the original RFC did not define field types, canonical encoding,
  signer failure behavior, target-specific package identity, or falsifiable
  registration evidence. This revision settles those decisions and adds the
  acceptance matrix.
- Decision: `READY`.
- Boundary review: after implementation audit, the OS install/activation/
  uninstall rows were split from this packaging objective and assigned to RFC
  0006. The package still emits and signs the exact declarations required by
  that next objective; no unsupported runtime registration claim is made here.
- Boundary review revision: `2fcf76d`.

## Acceptance review

- Reviewed revision: the final PR #56 merge candidate, recorded as
  `run.sourceRevision` by the RFC 0030 records in
  [`evidence/manifest.json`](evidence/manifest.json).
- Review pass: Codex acceptance review pass, same contributor as implementation;
  this is not an independent-person approval.
- Date: 2026-09-22.
- Findings: the complete PR diff was inspected, including the closed manifest,
  package assembler/verifier, three hosted package reports, Electron migration
  mapper, documentation, evidence bindings and this matrix. The package task
  now independently reopens every hosted package before writing its report.
- Decision: `PASS` after the final hosted aggregation and strict governance
  check. A5/A6 lifecycle installation and uninstall behavior remains outside
  this RFC and is explicitly assigned to RFC 0006; the signed package
  association metadata and atomic package boundary remain covered here.

## Evidence

Retain the canonical source manifest, package/SBOM/license hashes, platform
signature verification output, target-specific identity audit, signed
association metadata, and the final package digest under the hosted revision.

## Non-goals

No unsigned release success, dynamic entitlement mutation, auto-detection among
installer formats, execution of Electron Forge/Builder plugins, runtime package
updates, OS registration/activation/uninstall, or RFC 0006 application
activation routing.
