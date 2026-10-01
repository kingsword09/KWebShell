# KWebShell Clipboard Service

`kweb-service-clipboard` exposes the desktop `SYSTEM` clipboard through a
bounded typed KMP contract. It supports plain text, HTML, RTF, and URI-list
items, lazy payload descriptors, serialized multi-format writes, change
metadata, and explicit lifecycle/ownership errors.

The JVM provider uses JDK 25 FFM over the small native ABI in `native/`.
Windows uses Win32 clipboard formats and sequence numbers, macOS uses
`NSPasteboard` and `changeCount`, and Linux uses the active GTK
`CLIPBOARD` selection. Linux `PRIMARY`, arbitrary native formats, images,
and hidden toolkit/backend fallbacks are not published.

`ClipboardBridge` is generated from `src/mainBridge/clipboard-bridge.json`.
Renderer operations remain exact-origin, main-frame, permission, and gesture
checked by the existing policy engine.

The verification task runs common contract tests, the generated bridge compiler,
native ABI tests, and a real platform integration fixture. The integration
retains only redacted format IDs, sizes, hashes, ownership transitions, and
lifecycle facts in `build/clipboard-integration/clipboard-evidence.json`.
