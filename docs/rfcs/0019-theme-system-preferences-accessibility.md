# RFC 0019: Native theme, system preferences, and accessibility state

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-system-preferences` KMP service
- Depends on: RFC 0003, RFC 0004
- Electron migration surface: `nativeTheme`, selected `systemPreferences`
- Target mapping: `ADAPTER` or platform-specific `REWRITE`

## Objective

Expose observable OS appearance and accessibility facts plus narrowly scoped
application appearance requests. Keep browser media queries and Compose theme
state synchronized from one provider.

## Common KMP contract

Define color scheme, high contrast, reduced motion, reduced transparency, accent
color, text scale, screen-reader state where available, and ordered changes.
Application theme source is `SYSTEM`, `LIGHT`, or `DARK`; unsupported overrides
fail. Sensitive system preferences require separate platform-specific keys.

## Platform provider contract

Use Windows UISettings/SystemParameters, macOS NSWorkspace/NSAppearance and
accessibility APIs, and Linux Portal Settings plus declared desktop interfaces.
Environment variables are not runtime truth when the native facility is
required.

## Acceptance

1. Native tests change every controllable setting in an isolated desktop session
   and observe one ordered state update.
2. Compose and Chromium `prefers-color-scheme`, contrast, and reduced-motion
   observations agree with service state.
3. Unsupported fields are absent from capability facts rather than defaulted.
4. Renderer adapter exposes read/subscribe and declared theme override only;
   child/cross-origin Pages cannot mutate appearance.
5. Migration inventory classifies individual `systemPreferences` methods;
   platform-only methods require platform-specific RFC evidence.
6. Shutdown unregisters all OS observers and emits no late event.

## Non-goals

No registry/defaults database editor, accessibility-permission bypass, guessed
Linux desktop theme, or Electron-wide `systemPreferences` clone.
