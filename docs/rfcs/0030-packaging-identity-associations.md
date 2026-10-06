# RFC 0030: Reproducible application identity, signing, installers, and associations

- Status: Implemented
- Priority: P0
- Owners: `kweb-runtime-pack`, release packaging, desktop host
- Depends on: RFC 0001
- Electron migration surface: `app.setAppUserModelId`, default protocol client, file associations, packaged state
- Target mapping: `REWRITE`

The `Implemented` catalog status records the earlier metadata-package objective.
The Windows x64 MSIX amendment below is still incomplete until its new required
hosted rows are `PASS` and its evidence is recorded; do not treat the historical
status or ZIP metadata tests as proof of an installable MSIX.

## Objective and scope

Publish one closed application manifest and one deterministic packaging pipeline
for KWebShell. The pipeline binds the application identity, the CEF runtime
payload, helper ownership, platform registration metadata, declared capabilities,
and the selected installer format. A package is publishable only after its
payload, manifest, signatures, and association metadata have all been verified.

This RFC owns the platform package boundary and the normal installed
application entry point required by RFC 0006. The Windows x64 amendment in this
change is limited to an installable MSIX containing the JVM/Compose launcher,
the pinned Temurin JRE, and the independently verified CEF/native payload.
Notification activation, Windows App SDK restore/runtime deployment, lifecycle
lease routing, and warm/cold notification dispatch are not part of this
amendment. Notification behavior is owned by RFC 0016 and application lifecycle
routing by RFC 0006. This MSIX must not declare an unbundled Windows App SDK
prerequisite.

## Implementation contract

### A. Closed application manifest

The source manifest is `runtime/application-manifest.json`. It is strict UTF-8
JSON, schema version `1`, with unknown fields rejected and one trailing LF. The
following abbreviated sample shows field shape; the checked-in canonical
manifest declares all six `KWebTarget` IDs:

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
    "windows-x64": {"minimumOs": "10.0.17763", "format": "windows-msix", "packageIdentityName": "io.github.kingsword09.kwebshell", "browserSubprocessExecutable": "KWebShellCef"},
    "linux-x64": {"minimumOs": "glibc-2.35", "format": "linux-deb", "desktopId": "io.github.kwebshell.desktop"}
  },
  "capabilities": [],
  "providerResources": [],
  "update": {"channel": "stable", "keyId": ""}
}
```

`applicationId`, executable names, bundle/package/desktop identifiers, protocol
schemes, extensions, MIME types, and paths have bounded portable grammars.
Every hosted target must have exactly one target entry. The builder rejects a
target omission, duplicate declaration, unknown capability/provider resource,
path traversal, mutable absolute path, or unsupported package format.

Windows targets require one PNG for each icon kind below. Both dimensions are
validated before staging, and the package records bind the exact asset bytes.

| Icon kind | Package path | Width by height |
| --- | --- | --- |
| `windows-msix-square44` | `Assets/Square44x44Logo.png` | 44 by 44 |
| `windows-msix-square150` | `Assets/Square150x150Logo.png` | 150 by 150 |
| `windows-msix-square310` | `Assets/Square310x310Logo.png` | 310 by 310 |
| `windows-msix-wide310` | `Assets/Wide310x150Logo.png` | 310 by 150 |
| `windows-msix-store` | `Assets/StoreLogo.png` | 50 by 50 |

AppX `DefaultTile` must declare `Wide310x150Logo` whenever it declares
`Square310x310Logo`. A missing icon kind fails with
`application.manifest.windows-icon-missing`; invalid PNG bytes or dimensions
fail with `application.package.windows-icon-invalid`.

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

The metadata archive uses fixed timestamps, UTF-8 lexical entry ordering, fixed
permissions, no absolute paths, and atomic publication. For Windows it is an
internal `.zip` with signed package records and is never a distributable MSIX;
the ordinary verifier rejects it as a Windows package. The Windows x64 hosted
provider checks this signed record archive, stages it with the verified
jpackage app-image, and delegates actual block-map generation and package
signing to Windows SDK tools. The staging-tree digest binds the launcher, JRE,
CEF/native payload and assets; the final MSIX digest is retained per run. Its
signature bytes are intentionally run-specific because CI creates a temporary
test certificate.

### C. Platform package providers

One explicit target selects exactly one provider; there is no format detection or
fallback:

| Target | Format | Required platform facts |
| --- | --- | --- |
| macOS arm64 | `.app` inside a signed ZIP | bundle identifier, URL/file document declarations, `codesign --verify`, and notarization ticket/staple result when distribution mode is selected |
| Windows x64 | MSIX | Package identity bound to a temporary signing certificate whose subject exactly matches Publisher; jpackage JVM/Compose `KWebShell.exe`; pinned Temurin JRE; separate `KWebShellCef.exe`; verified CEF/native payload; protocol/file declarations; square/wide/store assets; SDK-generated block map and verified package signature; clean hosted install, start/close, and uninstall |
| Linux x64 | Debian package | desktop entry, MIME XML, AppStream metainfo, package architecture, and detached release signature |

The provider fails before publication when the declared signer/tool output is
missing, the observed identity differs from the manifest, the registration
metadata is incomplete, the app-image is missing required files, or the package
contains an undeclared capability. Test mode may use ephemeral
package-signing keys/certificates, but it still verifies the same structure and
signature boundary; test artifacts cannot be marked as release artifacts.
Windows ARM64 MSIX creation fails with a typed target error; no x64 launcher or
JRE is substituted.

The Windows launcher requires an absolute, nonblank `LOCALAPPDATA` and stores
its application state below `KWebShell` there. Missing/invalid configuration
fails with `launcher.state-root-invalid`; it must not select another profile
root. The launcher creates this selected directory and resolves its physical
path through a Win32 file handle before deriving transport and Profile paths.
This follows MSIX's existing file virtualization, so both JVM and CEF use the
same directory identity. It does not move data or disable virtualization.
Creation/resolution failures return `launcher.state-root-unavailable` with
the original cause. Failed startup closes Page/Profile owners before application shutdown,
drains the engine and disposes the window. Shutdown failure returns a nonzero
process exit and preserves the original startup error with cleanup failures.

The embedded Windows CEF browser keeps the application-owned JVM and its
existing token. Its browser command line sets `do-not-de-elevate` before
Chromium startup; Chromium's standalone-browser automatic relaunch cannot
replace the embedding JVM. This neither requests elevation nor changes child
sandbox settings. The callback leaves renderer/utility command lines unchanged.

### D. Association metadata boundary

The package contains canonical target-specific association declarations. The
Windows x64 amendment verifies MSIX installation, observed package identity,
ordinary launcher start/close, and uninstall on a clean hosted Windows account.
RFC 0006 owns lifecycle semantics and activation payload routing over these
declarations:

- macOS Launch Services for the `kweb:` scheme and `.kweb` document type;
- Windows per-user package registration for the protocol and file extension;
- Windows notification activation registration and Windows App SDK runtime
  deployment are outside this package amendment. RFC 0016 must add and verify
  the App SDK manifest/runtime contract together with notification event
  decoding; RFC 0006 owns the application lifecycle lease and dispatch to the
  JVM/Compose owner. This amendment emits no toast/COM activator CLSID;
- Linux desktop-entry/MIME registration in an isolated user data directory.

The package verifier checks that each declaration is present, identity-bound, and
covered by the package signature statement. RFC 0006 must retain the observed
application identity, activation payload, and cleanup result when it exercises
the OS registration mechanism.

## Evidence retention contract

The complete signed `.msix` remains in the hosted `application-package` artifact
with the workflow's declared 14-day retention. The repository retains a compact
`windows-msix-proof` ZIP produced from those exact bytes after native acceptance.
GitHub rejects Git files above 100 MiB, so an installer is not checked into Git.

The collector must require a successful Windows SDK/install/launch/uninstall/
cleanup report, the current hosted source revision, and an exact full-MSIX
SHA-256 match. The proof contains the original bytes of `AppxManifest.xml`,
`AppxBlockMap.xml`, `[Content_Types].xml`, `AppxSignature.p7x`, signed application
metadata, all five PNG assets, both required Temurin legal files, and the raw
Windows report and both launcher smoke summaries. The collector requires a
nonzero failed-start exit with the Profile error observed and all processes
drained, plus exit code zero from the actual window process and no remaining
installed-package processes before uninstall. A compact index records the package
digest/size, source revision and entry/summary digests. Paths normalize ZIP separators and reject traversal or
ambiguous names. Missing, altered or oversized inputs fail before recording.

The proof and report are retained permanently through the existing RFC artifact
recorder and its byte-for-byte governance checks. They are verification evidence;
the distributable remains the Windows SDK-produced MSIX. The supported claim is
the recorded native run, not indefinite availability of its test installer.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / closed manifest | The strict manifest validates and unknown/omitted/duplicate fields fail before packaging. | Valid canonical manifest; unknown key; missing target; duplicate protocol; absolute/traversal path; invalid identity/version/association/resource. | [`KWebApplicationManifestTest`](../../kweb-runtime-pack/src/test/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationManifestTest.kt); `:kweb-runtime-pack:verifyApplicationManifest`; same JVM tests on all three hosted targets. | Canonical manifest loader and closed-schema validator. | New RFC 0030 hosted records bind the amended contract and package reports. | `NOT_RUN` for amended hosted revision; local package/manifest tests and manifest CLI passed. |
| A2 / identity | The Windows package identity name, application Id, architecture, publisher and signing certificate agree; installed family/AUMID are observed from Windows. | Matching/mismatching certificate subject; changed identity/application Id/architecture; install and query family/full name/Start Apps AUMID; macOS/Linux identities remain unchanged. | `KWebApplicationPackageTest`; GitHub-hosted `windows-2022` `build-and-verify-windows-msix.ps1`; existing macOS/Linux package integration. | Root Appx identity emitter; generated certificate subject check; hosted install queries actual AppX and Start Apps registration. | RFC evidence retains the verified `windows-msix-proof` plus package report with family, full name, observed AUMID, publisher subject and digest. | `NOT_RUN` on Windows hosted runtime; local metadata/package unit tests pass. |
| A3 / reproducible package inputs | Repeated metadata builds from identical manifest, signed runtime, assets and target are byte-stable; app-image and combined staging digests bind actual launcher/JRE/CEF/native/assets; final signed MSIX digest is retained. | Repeat metadata build; mutate one asset/runtime byte; compare same and changed digests. Temporary test certificate and resulting package signature are run-specific. | `packageRoundTripIsDeterministicAndVerifiesNestedRelease`; `windowsMetadataIsDeterministicAndBindsAssetBytes`; GitHub-hosted Windows app-image/MSIX job; package integration on all three CI targets. | Canonical records and PNG validation; PowerShell computes `applicationImageTreeSha256` and `stagedPayloadTreeSha256`; SDK package verifier. | Windows evidence retains the compact proof and report with the full MSIX digest/size, metadata, image, staged-payload, runtime, icon, Temurin license, and final-package SHA-256 values. | `NOT_RUN` for hosted staging; unit determinism passes. |
| A4 / platform providers | macOS/Linux providers remain unchanged; Windows x64 emits a real installable MSIX with root AppxManifest, SDK-generated block map and verified package signature. | Missing launcher/JAR/JRE/CEF/native/CEF license/assets; malformed identity/XML; altered package; MakeAppx pack/unpack; AppX deployment signature verification; clean install, launcher/CEF start, normal close and uninstall. | Three-target `runtimeCheck`; Windows-only `.github/scripts/build-and-verify-windows-msix.ps1` on GitHub-hosted `windows-2022`. | Internal signed metadata ZIP + verified jpackage app-image staged into SDK `MakeAppx pack`; no hand-built block map; temporary publisher-bound test cert trusted in `LocalMachine\TrustedPeople`; native trust preflight verifies public bytes and cleanup. | RFC evidence retains the raw report and exact MSIX proof entries under Windows row artifacts; the full MSIX is a hosted artifact. | `NOT_RUN` on Windows hosted runner; local Kotlin tests pass and the ordinary verifier refuses a ZIP with `.msix` suffix. |
| A5 / associations | The signed package records bind the declared URI protocol/file type to the target identity; Windows manifest includes the corresponding OS declarations. | `kweb:` URI; `.kweb` file type; absent or wrong identity; generated XML contains matching entries and has one root/package identity. | `targetProvidersIncludeTheirRegistrationArtifacts`; Windows SDK manifest validation in hosted script; OS lifecycle dispatch remains RFC 0006. | Canonical `KWebApplicationRegistration`, Appx protocol/file-type extensions, signature statement, integration test. | Metadata archive digest and installed MSIX digest. | `NOT_RUN` hosted; XML and record assertions pass locally. |
| A6 / atomic publication and uninstall | Record archives publish atomically; MSIX install/uninstall is explicit and leaves neither AppX registration nor test-created KWebShell user data/policy behind. | Failed metadata publication; require clean ephemeral runner state; install/query; remove package; delete only the absent-before-test `%LOCALAPPDATA%\KWebShell` tree; restore exact AppModelUnlock value/key; verify cleanup of app data, policy, machine certificate trust and signing key. | Package assembler tests and Windows-hosted install/uninstall script, which refuses non-GitHub runners and pre-existing app data. | `KWebApplicationPackageAssembler`, `build-and-verify-windows-msix.ps1`. | Report retains install/uninstall, app-data cleanup, policy restoration and installed identity. | `NOT_RUN` on Windows hosted runner; not excluded from scope. |
| A7 / tamper boundary | Any payload/resource/helper/schema/runtime/signature mutation fails before launch. | Mutated metadata bytes/statement/runtime/canonical record fail independent verification; altered final MSIX fails AppX deployment verification. | `tamperedPackageCannotPassVerification`; actual final-package tamper check in the GitHub-hosted Windows package script. | [`KWebApplicationPackageVerifier`](../../kweb-runtime-pack/src/main/kotlin/io/github/kingsword09/kwebshell/runtime/KWebApplicationPackage.kt), MakeAppx, SignTool signing and AppX deployment verification. | Retain metadata package report and tampered-MSIX rejection in the Windows application-package report. | `NOT_RUN` for final Windows MSIX; the existing metadata tamper test passes locally. |
| A8 / capability audit | Package capabilities equal the closed manifest and provider resources. | Capability/provider records are canonical; SBOM runtime/license facts match the nested signed release. | Package round-trip verifier and real-payload integration on all three hosted targets. | Capability/SBOM equality checks in `KWebApplicationPackageVerifier.verify`. | Amended hosted application-package reports and package digests. | `NOT_RUN` for amended hosted revision; local package round-trip tests pass. |
| A9 / packaged state | `isPackaged` is immutable package data and cannot be changed by runtime input. | Generated true fact binds manifest digest/target/version; mismatch or malformed state fails; no environment input is read. | Package round-trip verifier and real-payload integration on all three hosted targets. | `KWebApplicationPackagedState` emission and verifier equality check. | Amended hosted application-package reports bind manifest and package digest. | `NOT_RUN` for amended hosted revision; local package round-trip tests pass. |
| A10 / migration | Electron builder/forge metadata maps only to declared fields and unsupported hooks block generation. | Valid mapping; unsupported hook; invalid target; non-canonical metadata. | [`KWebElectronPackagingContractTest`](../../kweb-electron-migration/src/commonTest/kotlin/io/github/kingsword09/kwebshell/electron/migration/KWebElectronPackagingContractTest.kt) in `:kweb-electron-migration:jvmTest`, all hosted targets. | Closed `KWebElectronPackagingMapper` contract. | New hosted records bind the amended contract digest. | `NOT_RUN` for amended hosted revision; historical tests passed and current hosted rerun is required. |
| A11 / universal completion | Tests, packaging, docs, matrix, hosted evidence and reviewed revision are complete. | Missing artifact, stale contract digest, skipped target or changed contract blocks acceptance. | `:kweb-rfc-governance:check`, `git diff --check`, full PR diff review, hosted evidence aggregation. | [`DESIGN_PLAN.md`](../../DESIGN_PLAN.md), [`docs/application-package-format.md`](../application-package-format.md), matrix, [`contracts.json`](evidence/contracts.json), [`evidence/manifest.json`](evidence/manifest.json). | Final three-target RFC 0030 READY records and Windows package report bind the reviewed source revision. | `NOT_RUN`; a hosted run and final acceptance review remain required. |
| A12 / notification activation | Windows App SDK notification activator registration and payload decoding are not required by this package slice. | Generated manifest has no toast activator CLSID, COM ExeServer or App SDK dependency; RFC 0016 must verify those when adding the provider. | Generated manifest contract test; downstream RFC 0016 hosted Windows evidence. | Root AppX manifest emitter. | Follow-up assigned to RFC 0016/0006. | `NOT_APPLICABLE`: notification activation is a separate runtime objective and is intentionally excluded from this package amendment. |
| A13 / Windows identity and visual assets | Package identity, application Id, publisher subject and actual square/wide/store bytes agree in the installed MSIX. | Missing/wrong-dimension, square/transposed wide tile, truncated or malformed PNG; publisher mismatch; changed identity; observed family/AUMID after install. | `windowsMsixMetadataRejectsArm64AndMalformedAssets`; `windowsWideTileRejectsMissingMalformedAndWrongSizeAssets`; `windowsTargetWithoutMsixIconIsRejected`; hosted MakeAppx validation/install. | Appx `DefaultTile` pairs large square/wide declarations; checked-in PNGs, `ImageIO` PNG decode/dimension checks and hosted identity verifier. | Retain all five asset SHA-256 values, certificate subject, installed IDs and MSIX SHA-256. | `NOT_RUN` for the repaired revision; Windows SDK and negative tests remain required. |
| A14 / Windows App SDK dependency | This package amendment declares no Windows App SDK framework dependency and adds no App SDK restore prerequisite. | Inspect generated AppX dependency graph; clean hosted install succeeds without separately installed App SDK packages. | AppX generated-manifest assertions and clean Windows install on GitHub-hosted `windows-2022`. | AppX dependency emitter; package test rejects SDK/notification dependency tokens; Windows hosted install script checks dependency inventory. | Dependency inventory retained in the package report. | `PASS` locally for no SDK/notification dependency; hosted install confirmation remains `NOT_RUN`. |
| A15 / normal Windows entry point | A clean MSIX install starts jpackage JVM/Compose `KWebShell.exe` with `app/` classpath and `runtime/` Temurin JRE, plus `cef/KWebShellCef.exe` and native CEF payload; normal close and failed startup drain all owned processes. | Visible application window and CEF child; missing runtime files fail before packaging; invalid/missing state root fails explicitly; a file blocking the Profile directory causes nonzero exit and no remaining JVM/CEF processes; normal close ends both processes. | `:kweb-application-launcher:check` on all hosted targets; `verifyWindowsFailedStartup` using the bundled JRE/classpath, followed by `runWindowsApplicationImageSmokeTest` and installed MSIX launch/close on Windows x64. | Launcher layout/state-root/target tests, ordered owner cleanup, app-image verifier/smoke task and Windows SDK package script. | Retain failed-start and normal smoke summaries, launcher PID/window, CEF observation, exit and runtime paths. | `NOT_RUN` for the repaired Windows startup path; local launcher contract tests pass. |
| A16 / Windows ARM64 package | Windows ARM64 package requests fail explicitly; no x64 payload is substituted. | Request `windows-arm64`; assert target-specific error before emitting metadata. | `windowsMsixMetadataRejectsArm64AndMalformedAssets`; Windows target guard. | `windowsMsixMetadataEntries` target check; manifest continues to enumerate KMP targets without advertising ARM64 package delivery. | Test result and target error code. | `PASS` locally: `application.package.windows-target-unsupported`; hosted Windows runtime not required for this negative path. |
| A17 / bundled JRE license material | The pinned Temurin JRE's module license and assembly exception remain present in both the app-image and installed MSIX, and the retained report binds their bytes. | Verify the pinned Windows archive contains both files; missing app-image file fails before MakeAppx; installed file missing or byte-changed fails after install; report includes both SHA-256 values and the staged tree digest. | `verifyWindowsApplicationImage`; Windows-hosted `.github/scripts/build-and-verify-windows-msix.ps1` install test. | Launcher app-image required-file check; PowerShell staging and installed-file checks. | `application-package-report.json` records `temurinLicenseSha256`, `temurinAssemblyExceptionSha256`, resolved installed paths, and `stagedPayloadTreeSha256`; exact legal bytes are retained in the compact proof and the full MSIX in hosted artifacts. | `NOT_RUN` for app-image/MSIX hosted execution. The pinned Temurin JRE archive hash was verified locally and its archive listing contains both required legal files; package propagation remains unverified until Windows CI. |
| A18 / A4 installed activation | Native installed-AUMID success with HRESULT/PID, real window/CEF and unchanged graceful shutdown. | Valid installed AUMID; unregistered AUMID fails; bootstrap/window PIDs may differ; no EXE fallback. | Real PowerShell native rejection test and local installed-MSIX probe; Windows hosted package integration. | `.github/scripts/windows-msix-activation.ps1`, package script and `tests/test_windows_msix_activation.ps1`. | Local positive `deployment-74e1be3fc17840259cea42b3b6fa14a3` binds base `058e8ee` plus helper SHA-256; native S_OK/PID 832, window PID 12760, CEF PID 13424, exit 0, drainage/uninstall/cleanup PASS. Windows run `37413731041` lacks native diagnostics. | Local `PASS`; amended hosted implementation `NOT_RUN`. Local feasibility is not hosted acceptance. |
| A19 / A7 failure observability | Bounded diagnostics precede owned cleanup and preserve the primary failure. | Missing window/CEF; exited bootstrap; unavailable event channel/log; diagnostic read/write failure; reparse/non-owned data; text/count/file bounds. | PowerShell diagnostics tests and Windows local/hosted failure capture; source review of cleanup ordering. | Activation helper, package-script finally integration and native/source-guard tests. | Local deliberate native failure `deployment-e3d4b0b034df42868b7e08e1218e37f6`: HRESULT `0x80070057`/PID 0 and pre-cleanup sidecar retained; exact package/trust/profile cleanup PASS. | Local native/helper/ordering tests `PASS`; hosted failure capture `NOT_RUN`. Window/CEF timeout is not claimed reproduced by a native rejection test. |
| A20 / A11 evidence integrity | Collector refuses absent, failed or malformed native activation evidence without relaxing existing gates. | Success; missing HRESULT/PID/PASS; failure HRESULT; zero, bool or non-integer PID. | Python collector/aggregation tests on every hosted target. | Collector, shared report fixture, collector negatives and aggregation rejection tests; RFC digest includes helper and tests. | Local script suite: 23 tests PASS with real Git Bash/jq orchestration, isolated recorder and UTF-8 Python environment. No support evidence generated. | Local `PASS`; changed-source three-target hosted evidence and final review `NOT_RUN`. |
| A21 / A4 Windows host ownership | Chromium startup preserves the embedding JVM and caller token instead of relaunching the executable to de-elevate. | Real CEF browser/renderer/utility command lines; repeat configuration; elevated installed AUMID startup with absent user data, Compose/CEF observation, exit zero and drainage. | Native CEF policy test and real local/hosted MSIX installation; three-target runtime regression. | `ConfigureEngineCommandLineOnPlatform`; `kweb_windows_engine_command_line_tests`; installed package integration. | P69.10/P69.11 local review in `DESIGN_PLAN.md`; `deployment-54d1744ce2eb417fb1be82a36e58c981` binds the package, engine and launcher hashes and records S_OK, window/CEF, exit 0 and full cleanup. | Local `PASS`; changed-source hosted verification `NOT_RUN`. No supported-state promotion until hosted evidence and final review pass. |
| A22 / A6-A7 virtualized state | JVM and CEF receive the physical identity of the selected application-data directory; failure is typed and never changes the Profile. | Existing/Unicode/missing native directory; file-blocked creation; missing/invalid environment; real MSIX with no pre-created unvirtualized data, normal close and cleanup. | Windows native FFM/path tests; shared launcher tests on all targets; real installed AUMID integration. | `WindowsStateDirectory.resolve`, `KWebApplicationLayout.prepareStateRoot`, `WindowsStateDirectoryTest` and the shared layout tests; Profile opens before window creation. | Same local installed proof as A21; nine launcher tests pass, including three real Win32 path tests. P69.11 records the reproduced path failure and exact passing package hash. | Local `PASS`; changed-source hosted verification `NOT_RUN`. No filesystem-virtualization or containment bypass. |

A21 readiness review: Codex, separate same-contributor pass, 2026-10-06,
revision `bdcd6dc` plus this amendment. P69.10 records the real reproduced
replacement process and the matching pinned Chromium implementation. Decision:
`READY` for repairing existing host ownership; final acceptance is `BLOCKED`
until the native policy and installed-package scenarios pass.

A22 readiness review: Codex, separate same-contributor pass, 2026-10-06,
revision `bdcd6dc` plus P69.10/P69.11. Actual Win32 handle resolution within
the installed package and the subsequent native Profile failure are recorded
in the design plan. Decision: `READY`; final acceptance remains `BLOCKED`
until the new native/path tests and full installed-package scenario pass.

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
- Historical decision: `READY` for the original package metadata contract only;
  it does not cover the Windows installability defects recorded below.
- Boundary review: after implementation audit, the OS install/activation/
  uninstall rows were split from this packaging objective and assigned to RFC
  0006. The package still emits and signs the exact declarations required by
  that next objective; no unsupported runtime registration claim is made here.
- Boundary review revision: `2fcf76d`.

## Contract amendment readiness review

- Reviewed revision: `d227aef` plus the RFC 0030 contract amendment in this
  change, before implementation.
- Review pass: Codex contract review pass, same contributor as implementation;
  this is not an independent-person approval.
- Date: 2026-10-03.
- Decision: `NOT_READY` for the superseded contract below; implementation did
  not rely on this preliminary hypothesis.
- Historical scope decision: the superseded review assigned an App SDK runtime
  dependency to RFC 0030. The revised contract below removes that dependency;
  RFC 0016 owns App SDK notification registration and event decoding, and RFC
  0006 owns cold process startup, lifecycle lease acquisition, validation, and
  routing to the JVM/Compose application owner.
- User-selected activation path: Windows App SDK `AppNotificationManager`, not
  a custom classic `INotificationActivationCallback` implementation. Microsoft
  documents paired toast/COM manifest registration and the
  `----AppNotificationActivated:` argument, followed by manager registration,
  `NotificationInvoked`, and early `AppInstance.GetActivatedEventArgs` reading.
- Candidate stable SDK pin: `Microsoft.WindowsAppSDK` 1.8.260921001, listed by
  NuGet on 2026-10-03. The exact framework/runtime dependency and deployment
  mode still require a Windows restore/install probe before acceptance.
- User-selected launcher decision: the Windows MSIX primary executable is a
  JVM/Compose launcher named `KWebShell.exe`; the native CEF browser subprocess
  is separately named `KWebShellCef.exe`. The MSIX carries the x64 Temurin JRE
  archive `OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip`, SHA-256
  `4c95451cea98556def2c54f7782933f52a26d4a36bd85e1d59f0364464828b07`, from
  Temurin release `25.0.4.1+1`. The bundled JRE is application payload, not a
  machine-wide or interactive installer prerequisite.
- Architecture boundary: Adoptium's current Temurin 25 Windows release exposes
  x64 but no Windows ARM64 binary. Windows ARM64 MSIX remains unavailable and
  must not be built with an x64 JRE or emulated package; the target will need a
  separate supported-state decision when a matching JDK/JRE release exists.
- Feasibility references: [App notifications quickstart](https://learn.microsoft.com/en-us/windows/apps/develop/notifications/app-notifications/app-notifications-quickstart), [packaged Windows App SDK deployment](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/deploy-packaged-apps), and [Windows App SDK 1.8 release notes](https://learn.microsoft.com/en-us/windows/apps/windows-app-sdk/release-notes/windows-app-sdk-1-8).

## Contract amendment feasibility audit

- Reviewed revision: this worktree after the Windows package-format and App SDK
  documentation probes, 2026-10-03.
- Review pass: Codex audit pass by the implementation contributor; not an
  independent-person approval.
- Findings: `writePackage` uses `ZipArchiveOutputStream` for Windows, stores the
  generated manifest under `platform/windows/`, and stores the actual runtime
  as a nested `runtime/release.pack.zip`. It does not place the executable at
  the package root, generate AppX block-map/signature files, run Windows SDK
  validation, or install a package. The current A4 PASS record therefore does
  not prove its declared Windows format and is corrected to FAIL above.
- Additional unresolved contract inputs: the manifest has no Windows image
  assets and records the reverse-DNS application ID where package-derived
  identity is required. The build has no pinned Windows App SDK restore,
  framework/runtime dependency, or install test. The proposed activation
  executable is `KWebShell.exe`, but that binary is the native CEF host; the
  project lifecycle owner and notification provider are JVM/Compose code. No
  bridge or startup integration currently registers `AppNotificationManager`,
  consumes the one-shot activated args, or routes `NotificationInvoked` to that
  owner. In addition, the packaged CEF host's `HostConfiguration::Parse`
  requires explicit absolute profile/cache arguments and a URL outside test
  mode; an MSIX activation cannot directly launch it as a configured Compose
  application. The repository currently defines no JVM application launcher or
  package-entry-point handoff. A package-only XML check cannot prove this
  cold-start path.
- GitHub-hosted `windows-2022` is already the project's CI target; nothing found
  requires a self-hosted runner. The job has not restored Windows App SDK or
  installed/tested an MSIX, so its current evidence cannot establish the new
  requirements.
- Historical decision: `NOT_READY`. Before implementation, the superseded
  contract would have needed to settle
  the true MSIX payload/entry point and owning process, App SDK deployment and
  version pin, publisher-bound visual assets, package-derived identity, and the
  JVM/Compose cold-launch handoff. Then a real hosted Windows install plus
  cold/warm OS notification activation must pass. The current Windows target
  must not be described as an installable MSIX until A2/A4/A12-A15 are proven;
  the prior XML-generation test proves none of these. This was the review for
  the superseded scope that included App SDK runtime and notification startup;
  its findings are historical and the revised review below narrows ownership.

## Revised contract readiness review

- Reviewed revision: RFC 0030 contract and matrix in this worktree, against
  `origin/main` at `d227aef`, 2026-10-03. This review occurred after local
  implementation work had already begun in an earlier turn; it is not a
  retrospective approval of that work. It governs the remaining implementation
  and acceptance work after the user selected the JVM/Compose launcher/bundled
  JRE path and assigned notification integration to RFC 0016/0006.
- Review pass: Codex readiness pass by the implementation contributor; not an
  independent-person approval.
- Decisions: RFC 0030 owns a real Windows x64 MSIX, the JVM/Compose
  `KWebShell.exe` entry point, bundled SHA-256-pinned Temurin JRE 25.0.4.1+1,
  distinct `KWebShellCef.exe`, package identity/publisher/assets, explicit
  protocol/file declarations, SDK block map/signature, install/start/close/
  uninstall verification and retained evidence. Windows ARM64 package
  generation fails explicitly until a matching pinned JRE exists. RFC 0016
  owns Windows App SDK notification dependencies and activator registration;
  RFC 0006 owns application lifecycle routing. No Windows App SDK prerequisite
  is introduced by this RFC 0030 amendment.
- Feasibility basis: the project already uses GitHub-hosted `windows-2022`
  runners; Temurin JRE x64 archive/version/hash are pinned; the launcher module
  uses JDK 25 `jpackage --type app-image`; Windows SDK MakeAppx and SignTool
  perform package/block-map/signature creation and verification. The macOS
  app-image probe established the launcher's `app/` and `runtime/` layout. The
  current Windows end-to-end workflow is the required acceptance probe and is
  still `NOT_RUN` on this non-Windows host; no Windows runtime result is claimed
  by this readiness review.
- Contract details settled: Windows metadata ZIP is an internal signed record
  archive only and cannot be output with `.msix` extension or accepted by the
  public Windows package verifier. Windows x64 CI combines its verified entries
  with the app-image, checks the signed-entry digests, calls MakeAppx/SignTool,
  installs and queries AppX identity, observes launcher plus CEF subprocess,
  verifies normal shutdown, uninstalls, and writes a report into the existing
  application-package evidence artifact.
- Findings: the old Windows ZIP output and stale A4 evidence were not real MSIX
  evidence; the implementation now makes that ZIP non-publishable and leaves
  all Windows hosted rows `NOT_RUN` until actual hosted execution. The old
  App-SDK-specific `NOT_READY` finding is outside the revised RFC 0030 scope.
- Decision: `READY` for the remaining implementation and Windows hosted
  acceptance under this revised contract. Windows hosted acceptance and
  evidence remain blocking before promoting the amended capability, marking
  RFC 0030 complete for this amendment, or merging.

### Windows tile repair readiness review

- Reviewed revision: rebased `37ae295` and the recovered asset changes,
  2026-10-05, after PR 68 merged at `0f9af2b`.
- Review pass: Codex, separate pass by the same implementation contributor.
- Findings: hosted run `37133703051` rejects the missing wide tile with
  MakeAppx `80080204`. The amended icon table and A13 require the real 310 by
  150 PNG, canonical manifest ordering, paired AppX declarations, invalid-asset
  tests and retained asset digest. Windows jpackage task configuration is
  explicitly excluded from caching for the full Windows verification command.
- Decision: `READY` for the P69.1-P69.3 repairs in `DESIGN_PLAN.md`; acceptance
  remains `NOT_RUN` until the repaired source passes its required real targets.
- Follow-up review: P69.4 in the same plan covers the remaining SDK command
  and cleanup defects found in the complete script diff. The documented
  [MakeAppx commands](https://learn.microsoft.com/en-us/windows/msix/package/create-app-package-with-makeappx-tool)
  perform semantic validation during `pack` without `/nv`; there is no
  `validate` subcommand. The documented
  [Add-AppxPackage syntax](https://learn.microsoft.com/en-us/powershell/module/appx/add-appxpackage?view=windowsserver2022-ps)
  has no `-PassThru` switch. Preflight failures must preserve existing data and
  packages, and only attempted installation may authorize package cleanup.
  Decision: `READY` for these A4/A6/A7 corrections, with actual PowerShell
  rejection tests and Windows SDK execution required for acceptance.
- Launcher follow-up at `040935c`: P69.5 repairs ignored shutdown failures and
  reversed error-path owner cleanup, and makes the Windows state root explicit.
  Decision: `READY`, with invalid-state unit tests and a real CEF startup
  failure probe followed by normal Windows app-image launch required.
- Evidence follow-up at `60c84be`: P69.6 replaces oversized installer retention
  in Git with the verified compact proof defined above. The full MSIX remains
  a hosted artifact; source revision, actual package hash and all native
  acceptance checks remain mandatory. Decision: `READY` for this retention
  correction, with collector negatives and real hosted import required.
- Final prerequisite review at `469c03b`: the real normal-smoke result has
  distinct bootstrap/window PIDs, so P69.5 observes the actual window exit code
  and all installed-package processes. CertEnroll also rejected the malformed
  EKU string after MakeAppx passed; P69.4 shares a code-signing certificate
  factory with the early Windows preflight. The fixture ordering and dual-stack
  port checks in P69.7 preserve the existing renderer/CDP guarantees. Decision:
  `READY` for these verification repairs; all real hosted results remain
  required before acceptance.

### Certificate-store repair readiness

- Revision: `d37e199`, 2026-10-06; Codex, separate review pass by the same
  implementation contributor.
- Run `37409668716` fails A2/A4 installation with `0x800B0109` after signing.
  Windows requires `LocalMachine\TrustedPeople`; the current user store does
  not satisfy AppX deployment trust. P69.8 in `DESIGN_PLAN.md` requires the
  shared public-certificate importer, native store readback and thumbprint-only
  cleanup, with full installation/tamper/process/uninstall verification.
- Decision: `READY` for this repair of A2/A4/A6/A7. No public API or signing
  policy changes; amended Windows acceptance remains `NOT_RUN` until the
  repaired revision produces real hosted evidence.

### Observable installed activation amendment

The Windows conformance fixture activates only the registered installed AUMID
through `IApplicationActivationManager.ActivateApplication` (Windows SDK
`ShObjIdl_core.h`). Use `CLSCTX_LOCAL_SERVER` and `AO_NOERRORUI`; retain the
native signed HRESULT as uppercase `0xXXXXXXXX` and its returned DWORD PID.
`activationHresult == "0x00000000"`, integer `activationProcessId > 0` and
`nativeActivation == "PASS"` are mandatory report/collector gates. This PID
is the jpackage bootstrap, not necessarily the Compose window owner. The
existing exact-title visible window, CEF child, exit-zero normal close,
process drainage, installation, signature and cleanup gates remain unchanged.
No direct executable or unpacked-image fallback is permitted.

Failure reports distinguish native activation from window/CEF observation and
normal shutdown. A report-adjacent `.activation.json` sidecar is written before
cleanup on installed-launch attempts, retaining up to 64 snapshots of 16
package processes, caller session/elevation/integrity information, up to 64
recent events per AppModel channel and up to four 64-KiB tails of available
test-owned CEF/JVM log files. Text is bounded; reparse points and non-owned
data are not read. Missing channels/logs or diagnostic failures are explicit
diagnostic errors, not successful runtime evidence, and cannot hide the primary
failure or stop owned-resource cleanup. AUMID activation cannot redirect the
GUI launcher's stdout/stderr; absent startup logs remain an observation limit.
The sidecar has a 2-MiB ceiling and is retained in the existing hosted
application-package artifact, not used to manufacture a successful RFC record.

Readiness review: 2026-10-06, Codex, separate same-contributor pass against
`058e8ee` and P69.9 in `DESIGN_PLAN.md`. Actual isolated SDK/COM feasibility
results are recorded there, including distinct bootstrap/window PIDs and full
owned cleanup. Scope is the internal Windows conformance fixture, not a public
API or a renderer-policy change. Missing hosted diagnostics are a verification
finding, not permission to weaken activation or visible-window requirements.
Decision: `READY` for implementation; final acceptance remains blocked until
the changed source passes Windows CI and the required evidence refresh/review.

Local implementation review, 2026-10-06, same contributor: the complete
worktree diff and new helper/native-test files were reviewed against A18-A20.
Final helper hash, real installed positive/negative probe paths and actual
test counts are recorded in P69.9 of `DESIGN_PLAN.md`. The final native
positive returned S_OK/bootstrap PID 13380, window PID 5956, CEF PID 8284,
exit 0 and full owned cleanup; its sidecar retains real logs and observations.
Collector/aggregation negatives, diagnostic bounds/ownership/error tests and
governance contract tests pass locally. Decision: local scenarios `PASS`,
changed-source hosted verification `NOT_RUN`; this is not final PR acceptance
and does not supersede the existing hosted-evidence blockers.

## Acceptance review

- Historical reviewed revision: the final PR #56 merge candidate, recorded as
  `run.sourceRevision` by the RFC 0030 records in
  [`evidence/manifest.json`](evidence/manifest.json).
- Review pass: Codex acceptance review pass, same contributor as implementation;
  this is not an independent-person approval.
- Date: 2026-09-22.
- Findings: the complete PR diff was inspected, including the closed manifest,
  package assembler/verifier, three hosted package reports, Electron migration
  mapper, documentation, evidence bindings and this matrix. The package task
  now independently reopens every hosted package before writing its report.
- Decision: `PASS` for that earlier package-metadata revision only. It is not
  acceptance of the 2026 Windows MSIX amendment or the revised matrix above.

## Evidence

Retain the canonical source manifest, package/SBOM/license hashes, platform
signature verification output, target-specific identity audit, signed
association metadata, and the final package digest under the hosted revision.

## Non-goals

No unsigned release success, dynamic entitlement mutation, auto-detection among
installer formats, execution of Electron Forge/Builder plugins, runtime package
updates, independent registration mutation outside MSIX package-manager
ownership, notification activation, or RFC 0006 application lifecycle routing.
