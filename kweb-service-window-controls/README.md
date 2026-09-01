# KWebShell Window Controls Service

`kweb-service-window-controls` binds one caller-owned Compose Desktop
`ComposeWindow` to a typed KMP native-service contract. It does not create,
replace, or dispose that window, and it does not add a second window backend.

The published version-1 surface contains:

- state snapshots and ordered state events;
- title and positive screen bounds;
- show/hide and focus requests;
- minimize/restore and maximize/restore;
- always-on-top and resizable state; and
- an exact-origin generated renderer bridge with operation-level grants.

Fullscreen control, native menu/tray ownership, custom title-bar hit testing,
and window creation are absent. They are not implemented as fallbacks or fake
success responses.

```kotlin
val controls = JvmKWebWindowControls.open(composeWindow)
engine.nativeServices.install(KWebWindowControls.Key, controls)

controls.setTitle("KWebShell")
controls.setBounds(KWebWindowBounds(120, 120, 1280, 800))
controls.setMaximized(true)
```

All mutations are dispatched to the AWT event thread. Window-manager state
transitions are awaited with a bounded poll and fail with typed service errors
when the requested state is not observed.

## Verification

```shell
npm ci
./gradlew :kweb-service-window-controls:check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal
```

The task runs common and JVM contract tests, strict generated TypeScript, and a
real ComposeWindow/CEF fixture. The fixture validates direct Kotlin controls,
renderer calls, exact-origin policy, permission denial, child-frame transport
isolation, cross-origin and unconfigured Pages, contiguous state events,
external window ownership, and deterministic Engine/CDP shutdown. Linux uses
the explicit Xvfb path; no system WebView or alternate window implementation is
selected.
