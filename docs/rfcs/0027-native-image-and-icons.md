# RFC 0027: Bounded native image and icon value model

- Status: Proposed
- Priority: P0
- Owners: common KMP resource model, desktop bindings
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
