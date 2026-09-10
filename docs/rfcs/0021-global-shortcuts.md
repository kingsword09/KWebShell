# RFC 0021: Owned global shortcuts and media keys

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-shortcuts` KMP service
- Depends on: RFC 0003, RFC 0004
- Electron migration surface: `globalShortcut`
- Target mapping: `ADAPTER`

## Objective

Register application-owned global shortcuts with normalized key semantics,
conflict reporting, permission status, ordered invocation, and deterministic
unregistration.

## Common KMP contract

Define physical/logical key descriptors, modifiers, media keys, registration
IDs, conflict results, and invocation events. Registration is explicit and
closeable; bulk replacement is atomic. Platform-only keys are capability facts.

## Platform provider contract

Use RegisterHotKey/low-level facilities allowed by policy on Windows, Carbon/
AppKit media/accessibility contracts on macOS, and X11 or declared desktop portal
global-shortcut APIs on Linux. Wayland support is advertised only with a tested
portal/compositor contract.

## Acceptance

1. Real key injection invokes registered shortcuts once while the app is
   focused, unfocused, minimized, and restored.
2. Conflicts with another process, reserved keys, keyboard-layout changes,
   media-key permission, and session lock are explicit.
3. Registration replacement and application close leave no active hook/hotkey.
4. Renderer requests require gesture/consent and may select only predeclared
   command IDs, not arbitrary accelerators.
5. Migration fixtures preserve declared register/unregister/isRegistered
   behavior and block unsupported accelerators.
6. CI retains invocation transcripts and native registration counts, not typed
   user keystrokes outside registered combinations.

## Non-goals

No keylogger, arbitrary low-level event stream, accessibility permission bypass,
or silent in-window shortcut fallback.
