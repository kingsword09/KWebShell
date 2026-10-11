# KWebShell Screen Service

`kweb-service-screen` implements the RFC 0020 typed screen and display-topology
contract for Windows, macOS, and Linux. Kotlin owns the atomic snapshot,
capability reporting, resolution, conversions, and change ordering; each
advertised target and session contributes one native provider behind a versioned
C ABI and the JDK 25 FFM binding.

The published v1 surface contains:

- one atomic topology of displays with identifiers stable for one service
  lifetime, their logical and native rectangles, and the work area, scale,
  rotation, refresh rate, color depth, and physical size each session provides;
- declared capability and space facts, so a fact a session does not publish is
  absent instead of defaulted;
- resolution that never guesses: `displayAt` returns nothing outside every
  display, `nearest` returns the closest rectangle, and a removed identifier fails
  typed;
- exact conversions between the logical, native, and physical spaces of one
  display, with the declared rounding; and
- an ordered, exactly-once topology change stream carrying the added, removed, and
  changed identifiers.

## Providers

| Target and session | Provider | Published facts |
|---|---|---|
| Windows x64 | `screen.windows.monitor-dpi` | monitors and work areas, effective DPI scale, refresh rate, color depth, rotation, cursor |
| macOS arm64 | `screen.macos.screen-cg` | screens, visible frames, backing scale, refresh rate, rotation, color depth, physical size, cursor |
| Linux x64, X11 | `screen.linux.x11-randr` | RandR 1.5 monitors, session work area, refresh rate and rotation where the outputs agree, cursor |
| Linux x64, Wayland | `screen.linux.wayland-gdk` | compositor monitors, work areas, scale, primary monitor |

A session never falls back to another display server: an X11 server without RandR
1.5 monitors, a Wayland session without a compositor facility, and a cursor
request on a session that publishes none all fail typed.

## Renderer surface

A page reads and subscribes through the policy-gated bridge, and receives only the
display count and the primary display's geometry and scale — what `window.screen`
and `devicePixelRatio` already expose. Identifiers, other displays, the work area,
and the cursor are never renderer-visible, and the renderer has no operation that
mutates the desktop.

## Placement

`KWebScreenPlacer` in `kweb-desktop` resolves every window rectangle from one
topology read, so a display removed while a window was open is never used again:
the request moves to the nearest display of its seed point or to the primary
display, reports the identifier it moved from, and always keeps the bounds inside
the chosen display's work area.

```kotlin
import java.nio.file.Path

val screens = JvmKWebScreens.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_screen"),
)
engine.nativeServices.install(KWebScreens.Key, screens)

val placement = KWebScreenPlacer(screens).place(displayId = null, width = 1280, height = 800)
```

## Verification

```shell
./gradlew :kweb-service-screen:check
```

The task runs the common contract tests, the native C ABI and provider structure
tests of the current target, the bridge contract tests, the packaged provider ZIP
check, and the real provider integration fixture.
