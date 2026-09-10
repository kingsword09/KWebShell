# RFC 0017: Application, window, and context menus

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-menus` KMP service, Compose host
- Depends on: RFC 0003, RFC 0004, RFC 0027
- Electron migration surface: `Menu`, `MenuItem`, application menu, popup/context menu
- Target mapping: `REWRITE` with generated command adapter

## Objective

Create native menus from immutable typed command trees while Kotlin owns command
handling and window association. Reuse the same command model for application,
window, context, and tray menus.

## Common KMP contract

Menu nodes have stable command IDs, labels, role, enabled/visible/checked state,
kind, accelerator reference, icon, submenu, and platform placement hints.
Updates replace a versioned tree atomically. Invocation events carry tree
version and owner; arbitrary callbacks are not serialized.

## Platform provider contract

Use Win32 menus/application command routing, AppKit NSMenu roles and main-menu
ownership, and Linux desktop native menu contracts selected for the declared
host. Browser context-menu integration must preserve Chromium edit/navigation
roles while applying explicit application policy.

## Acceptance

1. Native UI tests cover nested menus, roles, separators, radio/check state,
   disabled/hidden items, Unicode/mnemonics, icons, accelerators, and atomic
   updates.
2. Commands route once to the correct window/Page/application owner through menu
   close and replacement races.
3. Application and context menus follow each platform's expected role placement
   without silently dropping unsupported hints.
4. Exact-origin renderer requests may ask Kotlin to show only a predeclared menu
   ID; renderers cannot submit arbitrary native menu templates.
5. Migration transforms Electron templates into the typed model and rejects
   custom click closures or unknown roles until rewritten.
6. Close releases every native menu/item and does not retain Page callbacks.

## Non-goals

No Electron `Menu` object identity, JavaScript function serialization, HTML
fallback menu, or implicit global accelerator registration.
