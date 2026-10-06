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

Convert to HICON/HBITMAP, NSImage/CGImage, and Linux desktop icon/pixbuf formats
inside providers with explicit ownership and color management. Platform handles
never leave internal code.

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

Contract revision: `2026-10-06.1`.

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
| dimensions | width and height from 1 through 16,384; at most 64 million pixels |
| decoded storage | at most 256 MiB across reader, normalized pixels, row buffer, and encoder input |
| output | canonical PNG, RGBA8, sRGB, deterministic chunk order and compression |
| variants | at most 16 scale variants; scale is a positive integer from 1 through 8 |
| intent | `NORMAL`, `TEMPLATE`, or `MONOCHROME`; intent never changes source pixels silently |
| package ID | one to 128 portable components, no absolute/rooted path, `.` or `..`, or backslash |
| resource digest | lowercase SHA-256 of the exact encoded bytes; mismatch fails before decode |
| native handles | one owner, explicit close, and a provider live-count of zero after service close |

Only PNG and JPEG are advertised in v1. WebP, GIF, SVG, ICO, animated images,
and unknown formats fail with `image.format-unsupported`; they are not decoded
by another library. JPEG EXIF orientation values other than `1` fail with
`image.orientation-unsupported` until an orientation-preserving contract is
published. Embedded ICC profiles are normalized to sRGB. Alpha is preserved as
straight RGBA; opaque inputs are reported as `OPAQUE`. A malformed, truncated,
or decompression-bomb input fails before a native handle is created.

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
`image.owner-closed`, and `image.cancelled`.

No error or event contains source bytes, a filesystem path, a native pointer, or
an application command line. The generated bridge transports encoded bytes as
base64 strings with the same 16 MiB bound; it exposes named `decode` and
`encodePng` methods only. The bridge never exposes a native image object or a
generic channel.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Values are immutable, bounded, format-closed, and preserve declared dimensions, alpha, color space, intent, and variants. | Empty/max bytes, duplicate variants, zero/overflow dimensions, unknown enum, mutable input/output arrays. | Common contract tests and generated schema tests on all targets. | `kweb-service-image` common contract and tests. | Target reports bind the contract digest and model bounds. | `NOT_RUN` before implementation. |
| A2 / decoding | PNG/JPEG decode validates magic, dimensions, pixels, decoded bytes, alpha, ICC normalization, and EXIF policy before native dispatch. | Valid corpus, malformed/truncated data, decompression bomb, EXIF orientation, ICC profile, opaque/alpha, unsupported WebP/GIF/SVG/ICO. | JVM codec corpus and native negative ABI tests on Windows, macOS, and Linux. | `JvmKWebImageCodec`, corpus fixtures, codec tests. | Hashes, dimensions, format/status only; no source bytes. | `NOT_RUN` before implementation. |
| A3 / deterministic output | Every accepted image re-encodes to the same canonical PNG bytes for the same pixels and metadata. | Repeat encode, alpha/opaque pixels, row boundaries, large bounded image, corrupt output rejection. | Deterministic encoder tests and package scan on all targets. | Canonical PNG encoder and tests. | Output SHA-256, dimensions, and byte count. | `NOT_RUN` before implementation. |
| A4 / package resources | IDs are path-safe and exact digest matching occurs before decode. | Traversal, absolute/rooted IDs, backslashes, missing IDs, changed bytes, valid digest. | Common store tests plus packaged resource verification. | `KWebImageResourceStore`, package fixture. | Resource ID and digest only. | `NOT_RUN` before implementation. |
| A5 / native providers | The declared Win32, CoreGraphics, and GdkPixbuf providers create and release opaque handles. | Valid RGBA, invalid dimensions, allocation failure, provider absence, ABI mismatch, host handle close. | Native CTest and FFM integration on each advertised target. | Versioned C ABI, platform sources, `NativeImageFfm`, `KWebNativeImageHandle`. | Provider ID, status, live-count, and dimensions. | `NOT_RUN` before implementation. |
| A6 / lifecycle | Owner close, cancellation, and release races are deterministic and leave zero live native handles. | Close before/after dispatch, concurrent close, double close, release failure, service reuse. | JVM lifecycle tests and native live-count tests on all targets. | `JvmKWebNativeImage`, handle registry, lifecycle tests. | Terminal status and live-count only. | `NOT_RUN` before implementation. |
| A7 / renderer authority | Renderer decode/encode uses exact origin, main-frame, grant, owner, and bounded base64 transport. | Missing grant, child frame, cross-origin navigation, malformed base64, oversized payload, owner close. | RFC 0003 policy tests, generated bridge, real CEF fixture on all targets. | `KWebNativeImageBridge`, migration fixture. | Boolean authority outcomes and sizes only. | `NOT_RUN` before implementation. |
| A8 / migration | Declared Electron `nativeImage` construction and export methods rewrite to named typed operations; identity/mutation/platform APIs block. | `createFromBuffer`, `createFromDataURL`, `toPNG`, `toJPEG`, `resize`, `isEmpty`, unknown/custom methods, path/URL input. | Electron 44 fixture, inventory, generated facade, compatibility report. | Capability matrix and migration golden fixture. | Compatibility status and operation IDs. | `NOT_RUN` before implementation. |
| A9 / security | No URL fetching, filesystem path access, SVG execution, arbitrary native format, or pointer disclosure exists. | URL/path/SVG/script payload, native handle serialization, log/error inspection. | Source/package scan, negative tests, and code review. | Service API, bridge, native ABI, redacted errors. | Redaction and policy facts only. | `NOT_RUN` before implementation. |
| A10 / packaging | Native libraries and GdkPixbuf/CoreGraphics/Win32 link declarations are reproducible and test binaries stay out of runtime payloads. | Missing library, wrong ABI, architecture mismatch, test artifact scan. | CMake, Gradle package scan, all three hosted targets. | Module build and package assertions. | Provider/runtime identity and package digest. | `NOT_RUN` before implementation. |
| U1 / universal completion | Implementation, tests, evidence, documentation, matrix state, reviewed revision, and one focused squash PR agree. | Skipped target, stale digest, dirty worktree, unsupported field advertised. | Full local and hosted gates, final diff review. | RFC, module, governance, migration, evidence records. | Reviewed source revision and retained artifacts. | `NOT_RUN` before implementation. |

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

`NOT_RUN` — complete implementation, fresh three-target provider evidence,
strict governance, migration compatibility, documentation, and final row-by-row
review are required before changing the RFC to `Implemented` and squash merging
the objective PR.
