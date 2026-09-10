# RFC 0030: Reproducible application identity, signing, installers, and associations

- Status: Proposed
- Priority: P0
- Owners: runtime packaging, release engineering, desktop host
- Depends on: RFC 0001
- Electron migration surface: `app.setAppUserModelId`, default protocol client, file associations, packaged state
- Target mapping: `REWRITE`

## Objective

Turn the runtime pack into a signed, reproducible application package with stable
identity, helper ownership, sandbox/permission declarations, installers, deep
links, file associations, and uninstall cleanup on all targets.

## Contract

A versioned application manifest defines application ID, display metadata,
versions, executable/helper identities, icons, protocols, file types,
entitlements/capabilities, service/provider resources, update identity, and
minimum OS. Unknown fields and target omissions fail packaging.

## Platform provider contract

Produce signed/notarized macOS bundles and installer artifact, signed Windows
MSIX or explicitly selected installer with AUMID, and signed Linux package format
with desktop/metainfo/MIME data. One release configuration selects exact formats;
there is no runtime format fallback.

## Acceptance

1. Clean builds on all targets reproduce payload/resource manifests; expected
   platform signatures/notarization metadata are separately verifiable.
2. Installed apps launch browser/helper processes, retain one identity, receive
   protocol/file activation, and uninstall all registered test state.
3. Tampered binary/resource/helper/schema/runtime packs fail before launch.
4. Entitlement/capability audits prove only declared service needs are packaged.
5. `isPackaged` becomes a build fact, not mutable runtime behavior.
6. Migration report maps Electron builder/forge metadata to the closed
   application manifest and blocks unsupported hooks.

## Evidence

Retain package SBOM/licenses, payload hashes, signature/notarization verification,
installation/activation/uninstall transcripts, and application manifest digest.

## Non-goals

No unsigned release success, dynamic entitlement mutation, auto-detection among
installer formats, or execution of Electron Forge/Builder plugins.
