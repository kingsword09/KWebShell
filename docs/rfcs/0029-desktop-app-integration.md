# RFC 0029: Dock, taskbar, badges, progress, recent items, and autostart

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-app-integration` KMP service
- Depends on: RFC 0006, RFC 0007, RFC 0017, RFC 0027, RFC 0030
- Electron migration surface: app badge/recent documents/login items, dock APIs, BrowserWindow progress/overlay, Windows user tasks/Jump Lists
- Target mapping: `REWRITE` with platform capability facts

## Objective

Expose coherent application-shell integration through common operations where
semantics match and explicitly named platform extensions where they do not.

## Common KMP contract

Common operations cover badge text/count, window progress state, attention
request, recent document handles, and autostart status/configuration where all
advertised providers have equivalent ownership. Dock menus, overlay icons, user
tasks, Jump Lists, and bounce modes use platform-specific keys.

## Acceptance

1. Packaged native tests set/clear every common state and inspect the actual
   taskbar/dock/desktop integration.
2. Recent items use scoped file identity and clear deterministically; no private
   path reaches renderers.
3. Autostart registers the signed packaged identity, survives restart, reports
   OS/user disablement, and removes itself on close/uninstall tests.
4. Unsupported platform-specific operations are absent from capability facts,
   never no-op successes.
5. Migration fixtures classify every used Electron app/dock/taskbar method and
   generate Kotlin replacement recipes.
6. CI retains screenshots/system registration facts and cleans all test entries.

## Non-goals

No attempt to force one common abstraction over semantically different Dock,
Jump List, and Linux desktop features; no startup registration for unpackaged
development binaries.
