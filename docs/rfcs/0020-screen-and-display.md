# RFC 0020: Screen and display topology

- Status: Accepted
- Priority: P0
- Owners: new `kweb-service-screen` KMP service
- Depends on: RFC 0004
- Electron migration surface: `screen`
- Target mapping: host-only `REWRITE` (the stub's `ADAPTER` assumed a renderer surface; the reviewed contract keeps renderer reads policy-gated and declares no renderer mutation)

## Objective and scope

Publish one application-scoped screen service for Windows x64, macOS arm64, and
Linux x64 that owns multi-display topology, work areas, scale, rotation, color
depth, refresh rate, cursor position, and coordinate conversion for desktop window
placement. Display identifiers are stable for one service lifetime only, and every
model distinguishes the three coordinate spaces: logical Compose coordinates,
native pixels, and physical millimetres.

One implementation objective delivers:

1. the typed topology, conversion, resolution, cursor, and ordered-change
   contract with bounded values and stable errors;
2. one explicit provider per target and session type: Win32 monitor and DPI APIs,
   AppKit `NSScreen` with CoreGraphics display modes, the X11 RandR provider, and
   the Wayland provider through the compositor-reported GDK monitors — each
   advertising only the session type and facts it actually implements;
3. the resolved window-placement contract: nearest and matching display
   resolution never guesses after a removal, and a stale identifier fails typed;
4. the policy-gated renderer surface that exposes declared reads and change
   notifications only, while display capture stays RFC 0025;
5. the closed Electron `screen` migration mapping, real target evidence,
   documentation, and the final acceptance review.

## Common implementation contract

```kotlin
public interface KWebScreens : KWebNativeService {
    public val events: Flow<KWebScreenEvent>
    public suspend fun capabilities(): KWebScreenCapabilities
    public suspend fun topology(): KWebScreenTopology
    public suspend fun cursor(): KWebScreenCursor
    public suspend fun displayAt(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId?
    public suspend fun nearest(point: KWebScreenPoint, space: KWebScreenSpace): KWebScreenId?
    public suspend fun convert(request: KWebScreenConversion): KWebScreenPoint
}
```

The v1 descriptor is version `1.0.0`, service id `screen`, scope `APPLICATION`,
and publishes `capabilities`, `topology`, `cursor`, `display-at`, `nearest`,
`convert`, and `events` as host operations plus the policy-gated renderer
operations described below.

### Values and bounds

| Value | v1 rule |
|---|---|
| display id | opaque ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}`, stable for one service lifetime |
| geometry | int32 coordinates in -32768..32767, positive extent up to 32767 |
| scale | integer permille in 1000..8000, so fractional scales stay exact |
| rotation | `NONE`, `QUAD_1`, `QUAD_2`, `QUAD_3` (90-degree steps the platform publishes) |
| color depth | bits per pixel in 1..64 |
| refresh rate | whole hertz in 1..1000 |
| session | `WINDOWS`, `MACOS`, `X11`, `WAYLAND` |
| coordinate space | `LOGICAL`, `NATIVE`, `PHYSICAL` |
| topology sequence | strictly increasing per owner, starting at 1; 64 retained events |

A fact the session does not publish is absent from the display model and from the
capability report; it is never defaulted. X11 publishes no scale factor and no
work area without a window manager, a synthetic RandR monitor has no refresh rate
or rotation, and Wayland publishes no global cursor position: each of those is a
typed absence on that session instead of a guessed value.

### Lifecycle, concurrency, and limits

- The provider reads the topology atomically: one snapshot never mixes two reads.
- Topology changes are ordered and exactly once. The service re-reads after every
  native notification, diffs against the previous snapshot, and emits one
  `TopologyChanged` event carrying the added, removed, and changed identifiers.
- Identifiers are stable while a display stays connected, so an application can
  hold one across events. After a removal, using the identifier fails typed
  (`screen.display-unknown`) instead of resolving to a different display.
- `close` unregisters every native observer, releases the platform connection, and
  delivers no late event; a provider failure while closing is reported once as a
  sticky failure.

### Errors and platform policy

- Every rejection has a stable `screen.*` code; there is no hidden fallback.
- A session whose declared facility is missing fails immediately: an X11 provider
  on a server without RandR 1.5 monitors reports `screen.topology-unavailable`, a
  Wayland session without a compositor-reported monitor reports
  `screen.topology-unavailable`, and a cursor request on a session that does not
  publish one reports `screen.cursor-unavailable`. The service never synthesizes a
  cursor position, and it never falls back from Wayland to X11 or from X11 to an
  unrelated display server.

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| Windows x64 | `screen.windows.monitor-dpi` | `EnumDisplayMonitors` and `GetMonitorInfoW` for monitor and work-area rectangles, `GetDpiForMonitor` for effective scale, `EnumDisplaySettingsW` for refresh rate, color depth, and rotation, `GetCursorPos` with `MonitorFromPoint` for the cursor, and `WM_DISPLAYCHANGE`/`WM_DPICHANGED` for ordered changes |
| macOS arm64 | `screen.macos.screen-cg` | `NSScreen.screens` frames and `visibleFrame` work areas, `backingScaleFactor` scale, `localizedName` and `NSScreenNumber` identity, `CGDisplayCopyDisplayMode` for refresh rate and pixel size, `CGDisplayRotation` for rotation, `NSEvent.mouseLocation` for the cursor, and `NSApplicationDidChangeScreenParametersNotification` plus `CGDisplayRegisterReconfigurationCallback` for ordered changes |
| Linux x64, X11 session | `screen.linux.x11-randr` | RandR 1.5 monitor objects from `XRRGetMonitors` as the topology (the provider requires 1.5 instead of substituting RandR outputs), `XRRGetOutputInfo`/`XRRGetCrtcInfo` for refresh rate and rotation of monitors that own outputs, `XQueryPointer` for the cursor, `_NET_WORKAREA` for the window-manager work area when the session publishes it, and `XRRSelectInput` notifications for ordered changes |
| Linux x64, Wayland session | `screen.linux.wayland-gdk` | Compositor-reported monitors through GDK: geometry, work area, scale, and primary display, with `GdkDisplay::monitor-added` and `monitor-removed` for ordered changes; refresh rate, rotation, and the global cursor position stay absent because the compositor publishes none |

macOS CoreGraphics display modes and Windows `EnumDisplaySettingsW` publish
refresh rate, color depth, and rotation for physical displays; a mirror group or a
synthetic RandR monitor does not, so those facts stay absent there.

### Renderer surface

The bridge exposes exactly two policy-gated operations for an exact-origin main
frame with the declared grant: read the renderer-visible summary (display count,
the primary display's logical geometry and scale) and subscribe to topology change
notifications that carry only the display count. Display identifiers, other
displays' geometry, and the cursor are never renderer-visible, so a page cannot
enumerate or fingerprint the desktop beyond what `window.screen` and
`devicePixelRatio` already expose.

### Evidence lifecycle

The RFC binds `kweb-service-screen/src`, `kweb-service-screen/native`,
`kweb-service-screen/build.gradle.kts`, the Compose placement consumer, the
governance service catalog, the migration kit, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version,
schema, C ABI, provider identity, or the pinned CEF/Chromium identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | A topology snapshot is atomic and every display publishes only the facts its session provides. | Single display, two displays with different scale and origin, negative coordinates, fractional scale, unpublished refresh/rotation/work area. | Common contract tests plus provider structure tests. | `KWebScreens.kt`; `KWebScreensContractTest`. | Local and hosted records. | `NOT_RUN` before implementation. |
| A2 / platform providers | One provider per target and session type advertises exactly the session it implements. | Windows, macOS, X11 with RandR 1.5, X11 without it, Wayland. | Provider structure tests on all targets plus the declared session in the capability report. | Provider snapshot builders and per-target structure tests. | Provider transcripts. | `NOT_RUN` before implementation. |
| A3 / change ordering | Each native reconfiguration emits exactly one ordered topology event with the added, removed, and changed identifiers, and identical snapshots are not re-emitted. | Monitor added, removed, geometry change, primary change, repeated notification, no-op notification. | Provider observer tests with emulated topology changes. | Sequence and diff code plus provider observer tests. | Observer transcripts. | `NOT_RUN` before implementation. |
| A4 / resolution without guessing | `displayAt` and `nearest` resolve a point to a display without synthesizing one, and a stale identifier fails typed. | Point inside one display, point between displays, point outside every display, mirrored displays, removed identifier. | Common tests plus provider structure tests. | Resolution code and its tests. | Resolution transcripts. | `NOT_RUN` before implementation. |
| A5 / coordinate conversion | Every declared conversion between logical, native, and physical spaces is exact for the declared scale, and an undeclared space fails typed. | Identity, scale 1.5 and 2.0, negative origin, physical conversion with a published size, space the session does not publish. | Common conversion tests plus provider values. | Conversion code and tests. | Conversion transcripts. | `NOT_RUN` before implementation. |
| A6 / cursor policy | The cursor position is published where the session provides it and fails typed where it does not. | X11 cursor, Windows cursor, macOS cursor, Wayland absence, cursor outside every display. | Provider tests on all targets. | `cursor` and provider implementations. | Cursor transcripts. | `NOT_RUN` before implementation. |
| A7 / multi-display emulation | The native test emulates two displays with different origin, scale, and work area and verifies conversions on the target that can. | RandR monitor create/remove under Xvfb, single-display sessions on Windows and macOS, conversion logic fixtures for all targets. | Native integration test plus shared conversion fixtures. | Native structure test with RandR monitor emulation. | Emulation transcript. | `NOT_RUN` before implementation. |
| A8 / window placement | The desktop placement consumer follows the service topology and never places a window on a removed display. | Placement on the primary display, nearest display, removed display, changed work area. | Consumer tests plus the engine fixture. | Compose placement consumer and its tests. | Placement transcript. | `NOT_RUN` before implementation. |
| A9 / renderer authority | The renderer reads only the declared summary and receives change notifications without identifiers, and a child or cross-origin frame is denied. | Granted read, missing grant, child frame, cursor request, unlisted fact request. | Bridge contract tests. | Renderer bridge and its tests. | Bridge transcripts. | `NOT_RUN` before implementation. |
| A10 / errors and no fallback | A session without the declared facility fails typed, and no path silently substitutes another display server or a guessed value. | X11 without RandR 1.5, Wayland without monitors, cursor on Wayland, stale identifier. | Provider tests with the facility removed. | Typed error mapping and provider unavailability paths. | Provider transcripts. | `NOT_RUN` before implementation. |
| A11 / migration | The Electron `screen` mapping rewrites the declared reads and blocks everything the host service does not expose. | Primary display, all displays, cursor point, nearest point, display-added/removed listeners, unknown method. | Inventory, golden, and migration tests. | Migration matrix row and focused tests. | Migration compatibility report. | `NOT_RUN` before implementation. |
| A12 / packaging and docs | Native libraries, schemas, generated output, and documentation agree. | Native package contents, packaged resource scan. | Gradle package verification plus governance. | Native runtime ZIP and package verification tasks. | Package digest report. | `NOT_RUN` before implementation. |
| A13 / sleep and wake | A session resume re-reads the topology and emits the change it actually observes instead of assuming the previous layout. | Sleep/wake with an unchanged layout, sleep/wake with a changed layout, notification storm. | Provider observer tests plus the desktop integration fixture. | Re-read and diff code. | Sleep/wake transcript. | `NOT_RUN` before implementation. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `NOT_RUN` before implementation. |
| A15 / deferred items | No display configuration mutation, no virtual display creation, no brightness control, and no Wayland-to-X11 fallback. | Absent capability on the session, typed failure on use. | Capability review and provider declarations. | Capability facts and provider declarations. | Capability transcript. | `NOT_RUN` before implementation. |

## Contract review record

- Reviewed contract revision: `dd6f08b` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation:
  - macOS: `NSScreen.screens`, `mainScreen` frame, `visibleFrame`,
    `backingScaleFactor`, `localizedName`, `deviceDescription["NSScreenNumber"]`,
    `NSApplicationDidChangeScreenParametersNotification`, `NSEvent.mouseLocation`,
    `CGMainDisplayID`, `CGDisplayRotation`, and `CGDisplayCopyDisplayMode` with
    refresh rate and pixel size all compile and run against the pinned toolchain.
  - X11: `Xvfb` advertises RandR 1.6 and accepts `XRRSetMonitor`/`XRRDeleteMonitor`,
    so a native test can emulate a second display with its own origin, extent, and
    physical size, and `XRRGetMonitors` reports it (verified: 1 -> 2 -> 1 monitors).
  - GDK reports one monitor through the X11 backend with geometry, work area,
    scale, and a primary display, but it does **not** observe RandR 1.5 monitor
    objects. The X11 provider therefore reads RandR directly instead of GDK, while
    the Wayland provider uses GDK's compositor-reported monitors; each publishes
    its own provider identity and session.
  - GDK also confirms `GDK_IS_X11_DISPLAY`/`GDK_IS_WAYLAND_DISPLAY` session
    detection is available with the pinned GTK 3 headers, which is how the Linux
    provider declares the session it implements.
- Open questions settled:
  - X11 publishes no scale factor and no work area without a window manager, so
    those facts are absent there rather than assumed.
  - A synthetic RandR monitor owns no output, so refresh rate and rotation stay
    absent for it while real monitors publish them.
  - The renderer summary is limited to the display count and the primary display's
    logical geometry and scale, which a page can already observe through
    `window.screen` and `devicePixelRatio`.
- Decision: `READY`.

## Non-goals

No display configuration mutation, virtual display creation, brightness control,
renderer-visible display enumeration, display capture (RFC 0025), or fallback from
Wayland to an unrelated X11 server.
