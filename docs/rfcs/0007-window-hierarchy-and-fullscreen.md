# RFC 0007: Window hierarchy, modal ownership, fullscreen, and close negotiation

- Status: Proposed
- Priority: P0
- Owners: `kweb-service-window-controls`, `kweb-compose`, desktop host
- Depends on: RFC 0002, RFC 0003
- Electron migration surface: `BrowserWindow` parent/child/modal/fullscreen/kiosk/closable/movable APIs
- Target mapping: `REWRITE`

## Objective

Extend the existing caller-owned window service with explicit parent/modal
relationships, fullscreen state, decorations, close negotiation, attention, and
minimum/maximum bounds while Compose remains the window creator and owner.

## Common KMP contract

Add typed placement and capability fields only for implemented operations:
parent key, modality, fullscreen mode, constraints, close request/result, and
ordered state transitions. Fullscreen and kiosk are distinct contracts. A close
request is bounded and resolves once; renderers cannot indefinitely veto host or
OS shutdown.

## Platform provider contract

Use Win32 owner/style/fullscreen monitor contracts, AppKit child/sheet and
fullscreen presentation APIs, and XDG desktop/X11 window-manager contracts.
Unsupported desktop-session operations return exact errors. No overlay window or
OSR surface substitutes for native-child composition.

## Acceptance

1. Real UI tests create caller-owned parent, child, and modal Compose windows and
   prove focus, z-order, disable/reenable, owner disposal, and no hidden windows.
2. Fullscreen enter/exit survives monitor/DPI change and restores exact prior
   bounds on all targets.
3. Constraint, movable, minimizable, closable, always-on-top, and close races
   report resulting native state rather than requested state.
4. CEF integration proves native child parentage remains valid through every
   window transition.
5. Renderer tests cover allowed close request, timeout, navigation, denial, and
   application-forced shutdown.
6. Migration guidance maps construction to Compose and controls to typed
   services; it does not expose a `BrowserWindow` object.

## Non-goals

Transparent frameless hit testing, custom title bars, menus, and tray ownership
remain separate RFCs. BrowserView/WebContentsView API identity is unsupported.
