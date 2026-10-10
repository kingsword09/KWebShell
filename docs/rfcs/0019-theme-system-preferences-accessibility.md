# RFC 0019: Native theme, system preferences, and accessibility state

- Status: Accepted
- Priority: P0
- Owners: new `kweb-service-system-preferences` KMP service
- Depends on: RFC 0003, RFC 0004
- Electron migration surface: `nativeTheme`, selected `systemPreferences`
- Target mapping: `ADAPTER` for `nativeTheme` reads, host-only `REWRITE` for appearance requests

## Objective and scope

Publish one application-scoped system-preferences service for Windows x64, macOS
arm64, and Linux x64 that owns the observable OS appearance and accessibility
facts, one ordered change stream, and the narrowly scoped application appearance
request. One provider per target reads the platform facility, and the same state
drives the Compose adapter and the embedded Chromium appearance so page media
queries and application theme never disagree.

One implementation objective delivers:

1. the typed snapshot, declared capability facts, ordered change stream, and the
   bounded appearance request with stable errors;
2. one explicit provider per target: AppKit appearance and accessibility APIs,
   Win32 `SystemParametersInfo`/DWM and declared preference reads, and the XDG
   settings portal plus the declared accessibility and desktop interfaces;
3. engine ABI v18 appearance support: the declared appearance and startup
   preference set applied to Chromium, one runtime appearance request export, and
   a real-CEF probe that proves the page media queries agree with service state;
4. one Compose adapter that derives appearance state from this service instead of
   introducing a second source of truth;
5. the closed Electron `nativeTheme` and selected `systemPreferences` migration
   mapping, real three-target evidence, documentation, and the final acceptance
   review.

## Common implementation contract

```kotlin
public interface KWebSystemPreferences : KWebNativeService {
    public val changes: Flow<KWebSystemPreferencesEvent>
    public suspend fun capabilities(): KWebSystemPreferenceCapabilities
    public suspend fun snapshot(): KWebSystemPreferenceSnapshot
    public suspend fun requestAppearance(source: KWebAppearanceSource): KWebAppearanceResult
}
```

The v1 descriptor is version `1.0.0`, service id `system-preferences`, scope
`APPLICATION`, and publishes `capabilities`, `snapshot`, `request-appearance`,
and `changes` as host operations. The renderer surface is a separate RFC 0003
policy-gated bridge described below; a page can read and subscribe, and only the
declared override can be requested from an exact-origin main frame.

### Values and bounds

Every fact is optional. A fact the platform does not publish is absent from the
snapshot and from the capability set; it is never defaulted to a guessed value.

| Value | v1 rule |
|---|---|
| color scheme | `LIGHT` or `DARK`; absent when the platform publishes no preference |
| contrast | `NONE`, `MORE`, or `FORCED_COLORS`; absent when unpublished |
| reduced motion | boolean; absent when unpublished |
| reduced transparency | boolean; absent when unpublished |
| differentiate without color | boolean; absent when unpublished |
| invert colors | boolean; absent when unpublished |
| accent color | bounded sRGB `#RRGGBB` plus the provider's declared source; absent when unpublished |
| text scale | integer percent in 50..500; absent when unpublished |
| screen reader | boolean, only through the declared sensitive key below |
| appearance source | `SYSTEM`, `LIGHT`, or `DARK`; the effective source is always reported |
| sequence | strictly increasing per owner, starting at 1 |
| change stream | ordered, exactly-once delivery per native change; 64 retained events |

### Lifecycle, concurrency, and limits

- Providers that own a native observer deliver changes on one provider thread;
  Kotlin owns ordering, de-duplication of identical snapshots, and the sequence.
- `snapshot` is atomic: it returns one consistent set of facts, never a mixture
  of two reads.
- `requestAppearance` records the application source and reports the effective
  source. `SYSTEM` restores the platform preference; the request rejects an
  unsupported source with a typed error instead of ignoring it.
- `close` unregisters every OS observer, releases the native state, and delivers
  no late event; a provider failure while closing is reported once as a sticky
  failure.

### Errors and platform policy

- Every rejection has a stable `preferences.*` code; there is no hidden fallback.
- Environment variables are never runtime truth when the native facility is
  required: a session without the declared facility reports the typed
  `preferences.platform-unavailable` instead of reading `GTK_THEME`,
  `AppleInterfaceStyle`, or an equivalent guess.
- The engine never guesses either: while the appearance capability is not
  declared by the application, Chromium keeps its own default behavior.

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| Windows x64 | Win32 | `SPI_GETHIGHCONTRAST` (contrast), `SPI_GETCLIENTAREAANIMATION` (reduced motion), `SPI_GETSCREENREADER` (screen reader), `DwmGetColorizationColor` (accent), declared preference reads for the application theme, transparency, and text scale; `WM_SETTINGCHANGE`, `WM_THEMECHANGED`, `WM_DWMCOLORIZATIONCOLORCHANGED`, and a registry-change notification deliver updates |
| macOS arm64 | AppKit | `NSApp.effectiveAppearance` matched against the Aqua and high-contrast appearance names, `NSWorkspace.accessibilityDisplayShouldReduceMotion`, `...ShouldReduceTransparency`, `...ShouldIncreaseContrast`, `...ShouldInvertColors`, `...ShouldDifferentiateWithoutColor`, `NSWorkspace.isVoiceOverEnabled`, `NSColor.controlAccentColor` converted to sRGB; appearance changes arrive through `effectiveAppearance` KVO and the distributed interface-theme notification, accessibility changes through `NSWorkspaceAccessibilityDisplayOptionsDidChangeNotification` |
| Linux x64 | session D-Bus | `org.freedesktop.portal.Settings` (`org.freedesktop.appearance`: `color-scheme`, `contrast`, `reduced-motion`, `accent-color`) with its `SettingChanged` signal, `org.a11y.Status` for the screen reader, and the declared desktop interface for text scale; a missing portal reports `preferences.platform-unavailable` |

macOS does not publish a global text scale, and the portal does not publish
reduced transparency or differentiate-without-color on every desktop: those
facts stay absent from the capability set on that target.

### Engine and Compose synchronization

One provider keeps the application appearance, the Compose adapter, and Chromium
in agreement:

- Engine ABI v18 adds `appearance_scheme` and `preference_bits` to
  `kweb_engine_config`. They are applied before CEF initialization because the
  corresponding Chromium preferences are startup-only.
- `KWEB_BROWSER_CONFIG_APPEARANCE_SYNC_ENABLED` opt-in makes the engine own the
  page appearance; without it Chromium keeps its own behavior.
- `kweb_engine_browser_set_appearance(browser, scheme)` applies a runtime
  appearance change through the browser's request context and returns a typed
  status; a rejected request changes nothing.
- Facts whose Chromium representation is startup-only (contrast, reduced motion)
  are declared as restart-required in the capability report instead of being
  silently ignored at runtime.
- The Compose adapter in `kweb-compose` derives its state from this service and
  updates from the same change stream.

### Renderer bridge

The bridge exposes exactly three RFC 0003 policy-gated operations: read the
renderer-visible snapshot, subscribe to changes, and request the declared
appearance override. Reads require the declared renderer grant, the override
additionally requires a user gesture, an exact origin, and the main frame. A
child or cross-origin frame receives a typed denial and can never change
appearance, and no sensitive fact (screen reader state) is renderer-visible.

### Sensitive preferences

Facts that describe a person's assistive-technology use are delivered only when
the host declares the matching platform key: `macos.voiceover` on macOS,
`windows.screen-reader` on Windows, and `linux.a11y` on Linux. Without the
declared key the fact is absent from the snapshot, the capability set, and the
renderer snapshot; there is no generic "read any system preference" surface.

### Evidence lifecycle

The RFC binds `kweb-service-system-preferences/src`,
`kweb-service-system-preferences/native`, `kweb-service-system-preferences/build.gradle.kts`,
the engine ABI and host sources, the Compose adapter, the governance service
catalog, the migration kit, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version,
schema, C ABI, engine ABI, provider identity, or the pinned CEF/Chromium
identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | A snapshot is immutable, atomic, and absent-safe: unpublished facts are missing instead of defaulted. | No facts, every fact, adjacent provider facts, unknown enum, out-of-range accent, text scale below and above the bound. | Common contract tests plus native snapshot tests. | `KWebSystemPreferences.kt`; `KWebSystemPreferencesContractTest`. | Local and hosted records. | `NOT_RUN` before implementation. |
| A2 / platform providers | One provider per target publishes exactly the facts its platform exposes. | macOS appearance and accessibility facts, Windows system-parameter facts, Linux portal facts, a fact the target does not publish. | Provider structure tests on all three targets. | Provider snapshot builders and per-target structure tests. | Provider transcripts. | `NOT_RUN` before implementation. |
| A3 / change ordering | Each native change delivers exactly one ordered update; identical snapshots are not re-emitted and rejected calls emit nothing. | One change per setting, duplicate native notification, unchanged snapshot, failed request. | Provider observer tests with injected native changes. | Sequence and de-duplication code plus provider observer tests. | Observer transcripts. | `NOT_RUN` before implementation. |
| A4 / appearance request | `SYSTEM`, `LIGHT`, and `DARK` are recorded and the effective source is reported; an unsupported source fails typed. | Each source, unsupported source, request after close, request without the platform facility. | JVM request tests plus provider assertions. | `requestAppearance` and provider appearance application. | Request transcripts. | `NOT_RUN` before implementation. |
| A5 / engine color scheme | With the opt-in flag, the engine applies the declared color scheme and the page observes the matching `prefers-color-scheme`, at startup and at runtime. | Light, dark, system, runtime switch, engine without the flag, runtime request on a closed browser. | Real-CEF probe on all three targets. | Engine ABI v18 fields, `kweb_engine_browser_set_appearance`, theme probe mode. | Probe transcripts. | `NOT_RUN` before implementation. |
| A6 / engine contrast and reduced motion | The declared startup preferences are applied to Chromium and proven by the probe; a runtime change is reported as restart-required instead of being ignored. | Reduced motion on and off, high contrast on, runtime change notification, probe reading of all three media features. | Real-CEF probe on all three targets. | Engine startup preference bits and probe mode. | Probe transcripts. | `NOT_RUN` before implementation. |
| A7 / Compose synchronization | The Compose adapter derives appearance from this service and follows the same change stream. | Initial state, change event, unavailable fact, service close. | Compose adapter tests plus the desktop session wiring test. | Compose adapter and session integration. | Adapter transcript. | `NOT_RUN` before implementation. |
| A8 / renderer authority | Renderer reads and subscriptions are grant-checked, the override needs a gesture, and child or cross-origin frames only receive denials. | Declared grant, missing grant, no gesture, subframe, cross-origin frame, sensitive fact request. | Bridge tests plus engine fixture. | Renderer bridge and its contract tests. | Bridge transcripts. | `NOT_RUN` before implementation. |
| A9 / sensitive preferences | Assistive-technology facts require the declared platform key and are otherwise absent everywhere. | Declared key, missing key, renderer request for a sensitive fact. | Capability tests plus bridge tests. | Sensitive-key policy and capability facts. | Capability transcript. | `NOT_RUN` before implementation. |
| A10 / errors and no fallback | A session without the native facility fails typed, and no environment variable is used as runtime truth. | Missing portal, missing status item, missing registry value, unavailable accessibility API. | Provider tests with the facility removed. | Typed error mapping and provider unavailability paths. | Provider transcripts. | `NOT_RUN` before implementation. |
| A11 / migration | Electron `nativeTheme` maps to declared operations and `systemPreferences` is classified per method with explicit blockers. | `nativeTheme` reads, `themeSource` request, per-method `systemPreferences` classification, unknown method. | Inventory, golden, and migration tests. | Migration matrix rows and focused tests. | Migration compatibility report. | `NOT_RUN` before implementation. |
| A12 / packaging and docs | Native libraries, schemas, generated output, and documentation agree. | Native package contents, packaged resource scan. | Gradle package verification plus governance. | Native runtime ZIP and package verification tasks. | Package digest report. | `NOT_RUN` before implementation. |
| A13 / restart-required facts | Facts that Chromium can only read at startup are declared restart-required and remain observable as service state. | Startup application, runtime change, capability report. | Engine probe plus capability tests. | Capability facts and engine startup bits. | Probe and capability transcripts. | `NOT_RUN` before implementation. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `NOT_RUN` before implementation. |
| A15 / deferred items | No registry editor, no accessibility-permission bypass, no guessed Linux desktop theme, and no Electron-wide `systemPreferences` clone. | Capability absent on the target, typed failure on use. | Capability review and provider declarations. | Capability facts and provider declarations. | Capability transcript. | `NOT_RUN` before implementation. |

## Contract review record

- Reviewed contract revision: `8cc24c2` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation:
  - The XDG settings portal publishes `org.freedesktop.appearance`
    `color-scheme` (`0` no preference, `1` dark, `2` light), `contrast` (`0`
    normal, `1` higher), `reduced-motion` (`0` no preference, `1` reduced), and
    `accent-color` as an sRGB `(ddd)` tuple, with `ReadOne` in interface version
    2 and the `SettingChanged` signal for updates.
  - The macOS facts used by the provider compile and run against the pinned
    toolchain: the `NSWorkspace` accessibility display properties,
    `isVoiceOverEnabled`, `NSColor.controlAccentColor` converted to sRGB, and
    `effectiveAppearance` matched with `bestMatchFromAppearancesWithNames:`. This
    SDK publishes no `NSApplicationDidChangeEffectiveAppearanceNotification`, so
    appearance changes are observed through `effectiveAppearance` KVO and the
    distributed interface-theme notification instead.
  - The pinned CEF 151 runtime exposes
    `CefRequestContext::SetChromeColorScheme(variant, user_color)`, documented as
    changing the underlying color mode for every browser that shares the request
    context, and its binary contains the `force-prefers-reduced-motion`,
    `force-prefers-no-reduced-motion`, `force-high-contrast`, and `forced-colors`
    switches. Color scheme is therefore runtime-controllable while contrast and
    reduced motion are startup-only, which A6 and A13 encode as a declared
    restart-required capability instead of a silent no-op.
- Open questions settled:
  - Text scale is not published on macOS, so the field is absent there rather
    than derived from a font metric.
  - The portal does not publish reduced transparency or
    differentiate-without-color, so those facts stay absent on Linux.
  - The engine probe, not the ABI documentation, decides A5 and A6: a media
    feature that the probe cannot observe on a target is removed from that
    target's engine capability set before merge.
- Decision: `READY`.

## Non-goals

No registry or defaults database editor, no accessibility-permission bypass, no
guessed Linux desktop theme, no Electron-wide `systemPreferences` clone, no
second appearance source beside this service, and no Compose theming framework
beyond the adapter that follows service state.
