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
| A1 / closed manifest | The strict manifest validates and unknown/omitted/duplicate fields fail before packaging. | Valid Unicode metadata; unknown key; missing target; duplicate protocol; traversal path; invalid ID/version. | `KWebApplicationManifestTest`; governance/schema check; macOS, Windows, Linux. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A2 / identity | All generated package identities derive from the one application ID and target entry. | Bundle ID, AUMID, desktop ID and helper identity match; conflicting identity fails. | Package verifier and identity audit on all targets. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A3 / deterministic package | Same inputs and signature facts produce identical package bytes and manifest digest. | Rebuild; changed payload; changed manifest; nondeterministic mtime; atomic output failure. | `KWebApplicationPackageTest`; target package builds. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A4 / platform providers | The selected macOS/Windows/Linux provider emits the declared package format and rejects missing/incorrect signing facts. | Valid provider; wrong signer; missing tool output; unsupported format; undeclared capability. | Native/package verification jobs on all three targets. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A5 / associations | The package contains identity-bound protocol/file association declarations for the next lifecycle objective. | `kweb:` URI; Unicode `.kweb`; multiple file types; missing declaration; wrong identity. | Package metadata/signature verification on all three hosted targets; OS registration is RFC 0006. | `KWebApplicationRegistration`, target metadata providers, package signature verifier | application-package report | `NOT_APPLICABLE` to RFC 0030 OS installation; reviewed scope boundary above |
| A6 / uninstall | Package output is atomically publishable and contains no implicit OS registration side effect. | Atomic publication; interrupted package write; package removal leaves no package-owned registration mutation. | Package assembler/verifier tests; OS uninstall is RFC 0006. | `KWebApplicationPackageAssembler`, atomic publication tests | application-package report | `NOT_APPLICABLE` to RFC 0030 OS uninstall; reviewed scope boundary above |
| A7 / tamper boundary | Any payload/resource/helper/schema/runtime/signature mutation fails before launch. | One-byte payload change; manifest digest change; helper replacement; signature mismatch; stale runtime. | Independent verifier tests and launch gate on all targets. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A8 / capability audit | Package capabilities equal the closed manifest and provider resources. | Missing declared resource; undeclared resource; entitlement drift; duplicate provider. | Capability/SBOM audit tests and retained reports. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A9 / packaged state | `isPackaged` is immutable package data and cannot be changed by runtime input. | Packaged state true; environment override; malformed state; target mismatch. | Manifest/package verifier tests on all targets. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A10 / migration | Electron builder/forge metadata maps only to declared fields and unsupported hooks block generation. | Valid mapping; plugin hook; dynamic identity; unsupported installer; missing association. | Migration fixture and generated report tests. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |
| A11 / universal completion | Tests, packaging, documentation, capability matrix, evidence and reviewed revision are complete. | Missing artifact, stale digest, skipped target, changed contract after review. | RFC governance, `git diff --check`, hosted evidence aggregation. | `NOT_RUN` | `NOT_RUN` | `NOT_RUN` |

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

## Evidence

Retain the canonical source manifest, package/SBOM/license hashes, platform
signature verification output, target-specific identity audit, signed
association metadata, and the final package digest under the hosted revision.

## Non-goals

No unsigned release success, dynamic entitlement mutation, auto-detection among
installer formats, execution of Electron Forge/Builder plugins, runtime package
updates, OS registration/activation/uninstall, or RFC 0006 application
activation routing.
