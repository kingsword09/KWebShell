# RFC 0020: Screen and display topology

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-screen` KMP service
- Depends on: RFC 0004
- Electron migration surface: `screen`
- Target mapping: `ADAPTER`

## Objective

Provide stable multi-display topology, work areas, scale, rotation, color depth,
refresh rate, cursor position, and coordinate conversion required by desktop
window placement.

## Common KMP contract

Display IDs are stable only for one service lifetime. Models distinguish logical
Compose coordinates, native pixels, and physical metrics. Ordered topology
events contain added/removed/changed snapshots; APIs resolve nearest/matching
display without guessing after removal.

## Platform provider contract

Use Windows monitor/DPI APIs, macOS NSScreen/CoreGraphics, and Linux
X11/RandR or an explicitly declared Wayland portal/compositor contract. A
provider advertises only the session type it actually implements.

## Acceptance

1. Hosted/native lab tests attach or emulate two displays with different scale,
   origin, work area, rotation, and refresh rate and verify conversions.
2. Add/remove/reconfigure while windows exist produces ordered topology events
   and deterministic window placement behavior.
3. Negative coordinates, mirrored displays, primary change, fractional scale,
   sleep/wake, and stale IDs are covered.
4. Cursor APIs require OS availability and do not synthesize a location.
5. Renderer adapter exposes only declared read/event methods; display captures
   remain RFC 0025.
6. Evidence records native and logical topology plus screenshots without
   retaining unrelated desktop pixels.

## Non-goals

No display configuration mutation, virtual display creation, brightness control,
or fallback from Wayland to an unrelated X11 server.
