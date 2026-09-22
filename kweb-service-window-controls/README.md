# KWebShell Window Controls Service

`kweb-service-window-controls` binds one caller-owned Compose Desktop
`ComposeWindow` to the RFC 0007 v2 typed KMP native-service contract. It does
not create, replace, or dispose that window, and it does not add a second
window backend.

The published v2 surface contains:

- immutable parent and modality registration;
- state snapshots and ordered state events;
- title and positive screen bounds;
- show/hide and focus requests;
- minimize/restore and maximize/restore;
- fullscreen and kiosk transitions with restored bounds;
- observed constraints, capabilities, attention, and bounded close negotiation;
- always-on-top and resizable state; and
- an exact-origin generated renderer bridge whose only renderer operation is
  `requestClose`; hierarchy, fullscreen/kiosk, capabilities, and force-close
  remain host-only.

The JVM provider requires the packaged platform provider library explicitly;
there is no implicit AWT/WebView fallback. The library is the small JDK 25 FFM
bridge over the Win32, AppKit, or X11 provider selected for the current target.

Linux support is the hosted X11 contract; Wayland and unsupported window-manager
operations return typed failures. Native menu/tray ownership, custom title-bar
hit testing, and window creation remain separate contracts.

```kotlin
import java.nio.file.Path

val controls = JvmKWebWindowControls.open(
    composeWindow,
    KWebWindowRegistration(id = "main-window"),
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_window_controls"),
)
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
renderer request-only close access, exact-origin policy, permission denial,
child-frame transport isolation, cross-origin and unconfigured Pages,
hierarchy/modal teardown, fullscreen/kiosk restoration, contiguous state
events, external window ownership, CEF parent stability, and deterministic
Engine/CDP shutdown. Linux uses the explicit Xvfb path; no system WebView or
alternate window implementation is selected.
