# RFC 0027: Bounded native image and icon value model

- Status: Implemented
- Priority: P0
- Owners: new `kweb-service-image` KMP value service and desktop bindings
- Depends on: RFC 0002
- Electron migration surface: `nativeImage`
- Target mapping: `REWRITE`

## Objective

Create one immutable image/icon contract reused by notifications, menus, tray,
clipboard, capture, and application packaging without exposing Electron
`NativeImage` identity or unbounded pixel buffers.

## Common KMP contract

Define encoded image sources, validated dimensions, density/scale variants,
template/monochrome intent, color space, alpha mode, byte/pixel limits, and
deterministic PNG output. Application resources reference package IDs/digests;
renderer-provided bytes require an explicit size-limited operation.

## Platform provider contract

Convert to Win32 HBITMAP, CoreGraphics CGImage, and Linux GdkPixbuf inside
providers with explicit ownership and color management. Platform handles never
leave internal code; HICON and NSImage conversion are not published by v1.

## Acceptance

1. Corpus tests cover PNG/JPEG/WebP where advertised, malformed/truncated/
   decompression-bomb inputs, alpha, ICC profiles, EXIF orientation, scale
   variants, and deterministic re-encoding.
2. Native round-trips preserve dimensions/alpha and release all image handles on
   every target.
3. Package resource IDs reject traversal and digest mismatch.
4. Renderer decoding occurs off CEF UI with strict encoded and decoded limits.
5. Migration inventory maps each used `nativeImage` operation; mutation and
   platform-handle APIs remain rewrite blockers.
6. Evidence records hashes and controlled fixture pixels only.

## Non-goals

No general graphics library, mutable bitmap API, SVG script execution, URL
fetching, or silent format substitution.

## Implementation contract

Contract revision: `2026-10-07.1`.

This objective publishes one bounded `native-image` application service in the
`kweb-service-image` module. The service is an image value and codec boundary,
not a general graphics API. It accepts only application-declared package
resources or bounded renderer bytes and returns immutable values. Native handles
are created and destroyed inside the desktop provider; no HICON, NSImage,
CGImage, GdkPixbuf, or Java AWT object crosses the common or bridge boundary.

### Common values and limits

```kotlin
public enum class KWebImageFormat { PNG, JPEG }
public enum class KWebImageAlphaMode { OPAQUE, STRAIGHT, PREMULTIPLIED }
public enum class KWebImageColorSpace { SRGB }
public enum class KWebImageIntent { NORMAL, TEMPLATE, MONOCHROME }

public class KWebImageEncoded(
    public val format: KWebImageFormat,
    bytes: ByteArray,
)

public class KWebImage(
    public val width: Int,
    public val height: Int,
    public val alphaMode: KWebImageAlphaMode,
    public val colorSpace: KWebImageColorSpace,
    public val intent: KWebImageIntent,
    public val png: KWebImageEncoded,
)

public sealed interface KWebImageSource {
    public class Encoded(public val value: KWebImageEncoded) : KWebImageSource
    public class PackageResource(
        public val id: KWebImageResourceId,
        public val sha256: String,
    ) : KWebImageSource
}

public interface KWebImageCodec {
    public suspend fun decode(source: KWebImageSource, intent: KWebImageIntent): KWebImage
    public suspend fun encodePng(image: KWebImage): KWebImageEncoded
}

public interface KWebNativeImageHandle : AutoCloseable {
    public val providerId: String
    public val isClosed: Boolean
}

public interface KWebNativeImage : KWebNativeService, KWebImageCodec {
    public suspend fun createNativeHandle(image: KWebImage): KWebNativeImageHandle
}
```

The service descriptor is `native-image` version `1.0.0`, scope `APPLICATION`,
and publishes renderer `decode` and `encode-png` plus host-only
`create-native-handle`. Renderer calls require the exact
`native.native-image.<operation>` grant and main-frame policy. Host calls still
observe provider and lifecycle state. Encoded bytes are copied on input and
output; getters return copies. The limits are fixed for v1:

| Value | Rule |
|---|---|
| encoded input | 16 MiB maximum, non-empty, PNG or JPEG magic must match the declared format |
| renderer encoded input/output | 512 KiB each, within the existing 1 MiB JSON bridge envelope; excess image bytes fail with `image.payload-too-large` |
| dimensions | width and height from 1 through 16,384; at most 6 million pixels |
| decoded storage | at most 256 MiB across reader, normalized pixels, row buffer, and encoder input |
| PNG metadata | at most 4,096 chunks; an inflated ICC profile is limited to 1 MiB |
| output | canonical PNG, RGBA8, sRGB, deterministic chunk order and compression |
| variants | at most 16 scale variants; scale is a positive integer from 1 through 8 |
| native handles | at most 256 live handles per loaded provider; excess allocation fails with `image.handle-limit` |
| intent | `NORMAL`, `TEMPLATE`, or `MONOCHROME`; intent never changes source pixels silently |
| package ID | one to 128 characters in portable components, no absolute/rooted path, `.` or `..`, or backslash |
| resource digest | lowercase SHA-256 of the exact encoded bytes; mismatch fails before decode |
| handle ownership | one owner, explicit close, and a provider live-count of zero after service close |

Only PNG and JPEG are advertised in v1. WebP, GIF, SVG, ICO, animated images,
and unknown formats fail with `image.format-unsupported`; they are not decoded
by another library. JPEG EXIF orientation values other than `1` fail with
`image.orientation-unsupported` until an orientation-preserving contract is
published. Embedded ICC profiles are normalized to sRGB. Alpha is preserved as
straight RGBA; opaque inputs are reported as `OPAQUE`. A malformed, truncated,
or decompression-bomb input fails before a native handle is created. The 6
million pixel cap bounds the current multi-buffer ImageIO normalization path.
The encoder streams rows into bounded IDAT chunks and rejects excess output
before growing its buffer beyond the encoded ceiling.

`KWebImageResourceId` is an opaque package identifier, not a filesystem path.
`KWebImageResourceStore` resolves only an exact ID and expected digest from the
host package manifest. Resource bytes are never resolved from a URL, current
working directory, or ambient filesystem path.

### Desktop providers and native boundary

The JVM provider uses the JDK image reader only for PNG/JPEG parsing and performs
the bounded decode on an IO worker. It then sends normalized RGBA to the
versioned C ABI. The platform implementations are explicit:

| Target | Native object | Provider ID | Missing facility result |
|---|---|---|---|
| Windows x64 | `HBITMAP` created by `CreateDIBSection` | `windows.Win32.HBITMAP` | `image.platform-unavailable` with native status |
| macOS arm64 | retained `CGImageRef` | `macos.CoreGraphics.CGImage` | `image.platform-unavailable` with native status |
| Linux x64 | `GdkPixbuf` RGBA image | `linux.GdkPixbuf` | `image.platform-unavailable` with native status |

The C ABI contains a version and struct-size field, status names, provider ID,
create/release, and live-count operations. It validates dimensions and buffer
size again at the boundary, uses platform ownership rules, and releases failed
partial allocations. The FFM binding is internal to `jvmMain`; its only public
result is an opaque closeable `KWebNativeImageHandle`.

The provider library remains loaded for the process lifetime. In particular,
GdkPixbuf's process-global GType registrations retain code from that library;
unloading it when an individual owner closes is invalid. Owner close releases
all owned images, while subsequent owners reuse the resident provider.

### Lifecycle, errors, and policy

Decode and encode calls are serialized per service owner and run off CEF UI,
AWT, AppKit, Win32, and D-Bus callback threads. `close()` rejects new work,
waits for a call that crossed the native boundary, releases every live native
handle exactly once, and reports `image.owner-closed` for work that did not
cross the boundary. A release failure is `image.outcome-unknown`; the service
never retries through another provider. Stable errors are:

`image.format-unsupported`, `image.payload-invalid`, `image.payload-too-large`,
`image.dimensions-invalid`, `image.pixel-limit-exceeded`,
`image.decoded-limit-exceeded`, `image.orientation-unsupported`,
`image.resource-id-invalid`, `image.resource-not-found`,
`image.resource-digest-mismatch`, `image.intent-invalid`,
`image.platform-unavailable`, `image.native-failed`, `image.outcome-unknown`,
`image.owner-closed`, `image.cancelled`, `image.alpha-mode-unsupported`,
`image.variant-invalid`, and `image.handle-limit`. Premultiplied input metadata
is rejected until an explicit conversion contract exists.

No error or event contains source bytes, a filesystem path, a native pointer, or
an application command line. The generated bridge transports encoded bytes as
base64 strings with a 512 KiB decoded-byte bound in each direction; it exposes named `decode` and
`encodePng` methods only. The bridge never exposes a native image object or a
generic channel.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Values are immutable, bounded, format-closed, and preserve declared dimensions, alpha, color space, intent, and variants. | Empty/max bytes, duplicate variants, zero/overflow dimensions, unknown enum, mutable input/output arrays. | Common contract tests and generated schema tests on all targets. | `KWebImage.kt`; `KWebImageContractTest.encodedBytesAreCopiedAndOnlyPublishedFormatsAreAccepted`, `variantsRequireMatchingLogicalDimensionsAndUniqueScales`, `descriptorPublishesApplicationOperationsAndTargets`. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A2 / decoding | PNG/JPEG decode validates magic, dimensions, pixels, decoded bytes, alpha, ICC normalization, and EXIF policy before native dispatch. | Valid corpus, malformed/truncated data, decompression bomb, EXIF orientation, ICC profile, opaque/alpha, unsupported WebP/GIF/SVG/ICO. | JVM codec corpus and native negative ABI tests on Windows, macOS, and Linux. | `JvmKWebImageCodec`; `JvmKWebImageCodecTest` corpus, PNG CRC/APNG/truncation, 6M-pixel boundary, ICC conversion/bounds, JPEG EXIF/metadata tests. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A3 / deterministic output | Every accepted image re-encodes to the same canonical PNG bytes for the same pixels and metadata. | Repeat encode, alpha/opaque pixels, row boundaries, large bounded image, corrupt output rejection. | Deterministic encoder tests and package scan on all targets. | Row-wise canonical encoder; `pngDecodeNormalizesToDeterministicRgbaPng`, `canonicalPngPreservesPixelsAndMaximumWidthRows`, CEF image round trip. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A4 / package resources | IDs are path-safe and exact digest matching occurs before decode. | Traversal, absolute/rooted IDs, backslashes, missing IDs, changed bytes, valid digest. | Common store tests plus packaged resource verification. | `KWebImageResourceStore`; `resourceIdentifiersAndDigestsArePathSafe`, `resourceStoreRejectsDigestMismatchBeforeUse`, `packageDigestIsCheckedBeforeDecode`. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A5 / native providers | The declared Win32, CoreGraphics, and GdkPixbuf providers create and release opaque handles. | Valid RGBA, invalid dimensions, allocation failure, provider absence, ABI mismatch, host handle close. | Native CTest and FFM integration on each advertised target. | C ABI and all platform sources; native `CheckNativePixels`, ABI/size/dimension tests; `ImageIntegrationMain` real FFM create/release and provider reopening. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A6 / lifecycle | Owner close, cancellation, and release races are deterministic and leave zero live native handles. | Close before/after dispatch, concurrent close, double close, release failure, service reuse. | JVM lifecycle tests and native live-count tests on all targets. | `JvmKWebNativeImage`, `NativeImageFfm`; `ImageIntegrationMain` concurrent close, 256-handle exhaustion, cancellation at result delivery, owner-closed, raw-release failure and terminal failure reuse. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A7 / renderer authority | Renderer decode/encode uses exact origin, main-frame, grant, owner, and bounded base64 transport. | Missing grant, child frame, cross-origin navigation, malformed base64, oversized payload, owner close. | RFC 0003 policy tests, generated bridge, real CEF fixture on all targets. | `KWebNativeImageBridge`; `KWebElectronMigrationIntegrationMain` real CEF decode/encode, malformed base64, input/output bounds, SVG/path/URL denial, cancellation, grants, child/cross-origin and closed owner. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A8 / migration | Declared Electron `nativeImage` construction and export methods rewrite to named typed operations; identity/mutation/platform APIs block. | `createFromBuffer`, `createFromDataURL`, `toPNG`, `toJPEG`, `resize`, `isEmpty`, unknown/custom methods, path/URL input. | Electron 44 fixture, inventory, generated facade, compatibility report. | `NATIVE_IMAGE_OPERATION`, generator and AST inventory; `KWebElectronImageMigrationTest`, `imageConstructionExportsMutationAndUnknownCallsRemainExplicitRewriteBlockers`, shared golden bytes and JS runtime tests. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A9 / security | No URL fetching, filesystem path access, SVG execution, arbitrary native format, or pointer disclosure exists. | URL/path/SVG/script payload, native handle serialization, log/error inspection. | Source/package scan, negative tests, and code review. | API/bridge/native source review; codec format tests, resource-path tests, CEF negative image scenarios, exact package contents and redacted provider reports. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| A10 / packaging | Native libraries and GdkPixbuf/CoreGraphics/Win32 link declarations are reproducible and test binaries stay out of runtime payloads. | Missing library, wrong ABI, architecture mismatch, test artifact scan. | CMake, Gradle package scan, all three hosted targets. | `nativeImageRuntimeZip`, `verifyNativeImagePackage`; exact native library/header ZIP and native pixel tests; hosted `validate_image_evidence.py` verifies package/library digests. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |
| U1 / universal completion | Implementation, tests, evidence, documentation, matrix state, reviewed revision, and one focused squash PR agree. | Skipped target, stale digest, dirty worktree, unsupported field advertised. | Full local and hosted gates, final diff review. | PR #70 full-diff review; `validate_image_evidence.py`, aggregation regression tests, RFC contract bindings, documentation and strict governance after hosted import. | Local macOS checks, 2026-10-07; hosted provider/CEF/package and named JUnit/CTest artifacts must be retained for all targets. | `BLOCKED` — local verification passed; fresh three-target hosted evidence and final review pending. |

## Contract review record

- Reviewed revision: `2026-10-06.1` on branch `rfc/0027-native-image`, before
  implementation code.
- Review pass: Codex readiness review, 2026-10-06. The implementation and this
  review are separate passes by the same contributor; this is not an
  independent-person approval.
- Findings and dispositions:
  - The proposal is one bounded value/codec/provider objective. Menus, tray,
    capture, and page capture consume the published value later and are not
    bundled into this RFC.
  - PNG and JPEG are the only v1 formats. Unsupported formats fail explicitly;
    no decoder substitution, URL fetch, SVG execution, or path authority is
    introduced.
  - Resource IDs and digests are package facts. The service never opens an
    ambient path, and digest verification precedes decode and native allocation.
  - Native handles remain behind a versioned C ABI and internal JDK 25 FFM
binding. The exact provider and unavailable status are recorded per target.
  - EXIF orientation other than 1 is rejected until a contract can preserve it;
    ICC profiles are normalized to sRGB and alpha is represented explicitly.
- Feasibility probes: existing JDK ImageIO package validation, the repository's
  JDK 25 FFM bindings, Win32 `CreateDIBSection`, CoreGraphics `CGImage`, and the
  hosted GTK/GdkPixbuf CMake provider boundaries. These probes establish the
  declared API and build boundaries; runtime evidence remains required before
  support promotion.
- Decision: **READY**. The inputs, limits, ownership, errors, target providers,
  migration boundary, and falsifiable acceptance rows are settled before code.

## Merge acceptance record

### PR #70 contract amendment readiness

- Reviewed revision: contract `2026-10-07.1`, base code `5bf0b88`, and the
  related uncommitted codec/lifecycle corrections present at the start of repair.
- Review pass: Codex, 2026-10-07, separate review by the same contributor.
- Findings: the original 64-million-pixel ceiling does not bound the full codec
  allocation pipeline to 256 MiB; v1 now limits images to 6,000,000 pixels.
  Premultiplied metadata and excess native handles have explicit typed errors.
  The Linux failure in run `37492837039` proves that an owner-scoped library
  lifetime is incompatible with GdkPixbuf's global type registration. Libraries
  remain resident and image ownership remains scoped and fully releasable.
  Windows/macOS expose a report generation dependency cycle; provider facts must
  be observed, and compatibility must come from the real migration fixture.
  A follow-up source review confirmed the existing CEF/FFM JSON bridge ceiling
  is 1 MiB. The image bridge therefore limits encoded input and output to 512 KiB
  and rejects excess output before transport; the host codec retains its 16 MiB
  limit. The same pass reviewed the 4,096-chunk and 1 MiB ICC bounds and row-wise
  encoding needed to keep metadata and encoder allocations bounded.
- Decision: **READY** for the repair acceptance criteria E70.1–E70.4 in
  `DESIGN_PLAN.md`. This is a breaking pre-1.0 correction: callers must respect
  the lower pixel and live-handle ceilings. No historical runtime approval is
  inferred; all applicable target evidence still has to be refreshed.

`BLOCKED` — the implementation branch uses `Implemented` to let the existing
recorder bind the first three-target evidence in this same focused PR; this is
not merge acceptance. Local image unit tests (19), migration tests (48), RFC
contract tests (71), native CTest/FFM, real CEF image calls, generated TypeScript
and JavaScript, and native package verification have passed. Fresh Windows x64,
macOS arm64 and Linux x64 hosted artifacts, strict governance, exact evidence
import and the final row-by-row review remain required before integration.

### Evidence binding review

The RFC 0027 binding includes its design/contract, service sources and native
build, generated-bridge codegen and transport, migration validator/generator/AST
scanner/fixtures, governance descriptor, CI workflow, and aggregation validator
and tests. These paths implement A1–A10; changing their bytes invalidates the
corresponding support evidence. The validator requires actual named JUnit and
CTest cases with no failures or skips, observed provider/CEF outcomes, the real
migration compatibility policy, and a ZIP whose library digest matches the
executed provider. It never rewrites catalog status or manufactures reports.
All artifacts must originate from one successful hosted run; local reports are
verification only and cannot be imported as hosted support claims.
