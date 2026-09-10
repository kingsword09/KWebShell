# RFC 0041: Native window decoration, materials, and hit testing

- Status: Proposed
- Priority: P1
- Owners: `kweb-service-window-controls`, Compose host, platform window providers
- Depends on: RFC 0007, RFC 0017, RFC 0019, RFC 0020, RFC 0027
- Electron migration surface: custom title bars, traffic lights, window controls overlay, vibrancy/background material, window shape and mouse hit testing
- Target mapping: `REWRITE` with target-capability queries

## Objective

Allow Compose to own a custom or system title-bar layout while preserving native
move, resize, system menu, caption controls, accessibility, DPI, and material
behavior. Platform-only effects are explicit capabilities rather than a lowest
common denominator or visual fallback.

## Common KMP contract

Extend the window service with immutable decoration mode, caption-button policy,
typed drag/no-drag and resize regions in logical coordinates, optional icon,
platform-neutral material intent, and ordered decoration state. A capability
record states exact supported modes and platform-specific extension keys.
Region updates are atomic and versioned against current window bounds/DPI.

## Platform provider contract

- Windows: DWM non-client frame integration, `WM_NCHITTEST`, system menu, caption
  button metrics, and explicitly supported backdrop attributes.
- macOS: `NSWindow` title visibility/style masks, full-size content view,
  standard window buttons, movable regions, and explicitly supported
  `NSVisualEffectView` materials.
- Linux: declared GTK4/XDG desktop decoration mode with compositor capability
  detection and native move/resize initiation. X11 and Wayland support are
  separate evidence rows when their contracts differ.

Unsupported material, shape, compositor, or decoration requests fail with the
reported capability key; providers never substitute another visual effect.

## Acceptance

1. Geometry tests cover scale changes, display movement, maximized/fullscreen
   transitions, menu areas, overlapping region rejection, and stale versions.
2. Real UI automation proves move, every resize edge, double-click maximize,
   right-click system menu, caption actions, focus, keyboard access, and browser
   input outside draggable regions on each advertised environment.
3. Native accessibility inspection exposes standard window controls and menu
   roles; a custom Compose control must invoke the same typed window operation.
4. Material and decoration screenshots are retained per OS/version with exact
   capability metadata. An unsupported request is tested as a typed failure.
5. Renderer code may request only host-declared decoration commands; region and
   material ownership remains in Kotlin/Compose.
6. Repeated recreation, display/DPI changes, fullscreen, and close leave no
   native hooks, views, callbacks, or stale hit-test regions.

## Non-goals

No pixel-identical cross-platform chrome, undocumented private OS APIs, automatic
Wayland/X11 switching, arbitrary renderer hit-test rectangles, invisible click
interception, or silent substitution of a different backdrop.
