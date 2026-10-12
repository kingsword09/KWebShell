# RFC 0021: Owned global shortcuts and media keys

- Status: Implemented
- Priority: P1
- Owners: new `kweb-service-shortcuts` KMP service
- Depends on: RFC 0003, RFC 0004
- Electron migration surface: `globalShortcut`
- Target mapping: `ADAPTER`

## Objective and scope

Publish one application-scoped shortcut service for Windows x64, macOS arm64, and
Linux x64 that registers application-owned global shortcuts with normalized key
semantics, explicit conflict and permission reporting, ordered invocation events,
and deterministic unregistration.

One implementation objective delivers:

1. the typed accelerator, registration, invocation, conflict, loss, and capability
   contract with bounded values and stable `shortcuts.*` errors;
2. exactly one provider per target and session type: Win32 `RegisterHotKey` on a
   dedicated message-only window, macOS Carbon `RegisterEventHotKey` with a
   permission-gated media-key path, the X11 `XGrabKey` provider, and a Wayland
   boundary that declares no global-hotkey facility because no tested portal
   contract exists in v1;
3. an atomic bulk replacement and a close path that leave no active hook or hotkey,
   including on a session lock the platform reports;
4. the policy-gated renderer surface that may only dispatch an application-declared
   command id with a user gesture and consent, never register an arbitrary
   accelerator and never observe an accelerator;
5. the closed Electron `globalShortcut` migration mapping, real target evidence,
   documentation, and the final acceptance review.

## Non-goals

No keylogger, no arbitrary low-level event stream, no accessibility-permission
bypass, no silent in-window shortcut fallback, no key remapping, and no
Wayland-to-X11 fallback.

## Common implementation contract

```kotlin
public interface KWebShortcuts : KWebNativeService {
    public val events: Flow<KWebShortcutEvent>
    public suspend fun capabilities(): KWebShortcutCapabilities
    public suspend fun register(registration: KWebShortcutRegistration): KWebShortcutRegistrationResult
    public suspend fun replaceAll(registrations: List<KWebShortcutRegistration>): KWebShortcutReplacementResult
    public suspend fun unregister(id: KWebShortcutId): KWebShortcutUnregistration
    public suspend fun isRegistered(id: KWebShortcutId): Boolean
    public suspend fun registrations(): List<KWebShortcutRegistration>
    public suspend fun dispatch(commandId: KWebShortcutCommandId): KWebShortcutId
}
```

The v1 descriptor is version `1.0.0`, service id `shortcuts`, scope `APPLICATION`,
and publishes `capabilities`, `register`, `replace-all`, `unregister`,
`is-registered`, `registrations`, and `events` as host operations plus the
policy-gated renderer operations below.

### Values and bounds

| Value | v1 rule |
|---|---|
| registration id | opaque ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}`, unique per application |
| command id | the same form; the application-declared command the shortcut invokes |
| modifier | `CONTROL`, `ALT`, `SHIFT`, `META`; a provider advertises the set it can register |
| named key | `A`..`Z`, `NUM_0`..`NUM_9`, `F1`..`F24`, and the portable editing/navigation keys the service declares |
| media key | `PLAY_PAUSE`, `NEXT_TRACK`, `PREVIOUS_TRACK`, `STOP`, `VOLUME_UP`, `VOLUME_DOWN`, `MUTE` |
| accelerator | one key plus modifiers; a named key requires at least one modifier, a media key may carry none |
| registration count | at most 32 live registrations |
| event sequence | strictly increasing per owner, starting at 1; 64 retained events |

A platform that cannot register a declared key class reports it as a typed absence
in `capabilities()` and fails registration of that accelerator with a stable error
instead of silently registering a substitute.

### Lifecycle, concurrency, and limits

- `register` is atomic: it either installs the hotkey and returns the applied
  sequence, or fails typed and installs nothing.
- `replaceAll` is atomic bulk replacement: the new set is validated and installed
  before the previous set is released, a failure leaves the previous set live, and
  the result names both the applied and removed identifiers.
- `unregister` is idempotent per identifier; unregistering an unknown identifier
  returns `wasRegistered = false` rather than failing.
- Invocation events are ordered and exactly once per native activation. The
  service never synthesizes an invocation and never reports a keystroke outside a
  registered combination.
- `close` unregisters every hotkey, releases the provider thread and platform
  connection, and delivers no late event; a provider failure while closing is
  reported once as a sticky failure.

### Errors, renderer, and migration policy

- Every rejection has a stable `shortcuts.*` code; there is no hidden fallback.
- The renderer surface exposes exactly two policy-gated operations for an
  exact-origin main frame with the declared grant: `read` returns a summary (whether
  global registration is supported and the live registration count) without any
  accelerator or identifier, and `dispatch` accepts one application-declared
  command id, requires a user gesture and consent, and emits an `Invoked` event with
  source `RENDERER`. An undeclared command, a missing gesture, a missing consent, a
  child frame, and a cross-origin frame are each denied with a stable error, and no
  operation can register an accelerator from the renderer.
- The Electron `globalShortcut` mapping is `ADAPTER`: `register`, `unregister`,
  `unregisterAll`, `isRegistered`, and the invocation callback map onto the host
  service; `registerAll` remains a declared blocker because the service deliberately
  exposes atomic `replaceAll` instead of a best-effort per-accelerator loop.

| Condition | Stable error |
|---|---|
| malformed identifier | `shortcuts.id-invalid` |
| malformed accelerator | `shortcuts.accelerator-invalid` |
| key class the platform cannot register | `shortcuts.accelerator-unsupported` |
| modifier the platform cannot register | `shortcuts.modifier-unsupported` |
| media key without the required permission | `shortcuts.media-permission-denied` |
| another process already owns the combination | `shortcuts.conflict` |
| reserved or disabled combination | `shortcuts.reserved` |
| duplicate live identifier | `shortcuts.duplicate-id` |
| more than 32 live registrations | `shortcuts.limit-exceeded` |
| unknown identifier on a non-idempotent path | `shortcuts.registration-unknown` |
| renderer names an undeclared command | `shortcuts.command-undeclared` |
| renderer dispatch without a gesture | `shortcuts.gesture-required` |
| renderer dispatch without consent | `shortcuts.consent-denied` |
| no declared facility for the session | `shortcuts.platform-unavailable` |
| native registration or event failure | `shortcuts.native-failed` |
| use after close | `shortcuts.owner-closed` |

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| Windows x64 | `shortcuts.windows.register-hotkey` | `RegisterHotKey`/`UnregisterHotKey` on a dedicated message-only window with its own message loop, `MOD_CONTROL`/`MOD_ALT`/`MOD_SHIFT`/`MOD_WIN` plus `MOD_NOREPEAT`, `WM_HOTKEY` for ordered invocation, `VK_MEDIA_*` for media keys, and `ERROR_HOTKEY_ALREADY_REGISTERED` mapped to `shortcuts.conflict`; `WM_WTSSESSION_CHANGE` released on lock |
| macOS arm64 | `shortcuts.macos.carbon-hotkey` | Carbon `RegisterEventHotKey`/`UnregisterEventHotKey` with `kEventHotKeyPressed` for ordered invocation, `kEventHotKeyExistsErr` mapped to `shortcuts.conflict`, and named keys only; media keys are a typed absence (`shortcuts.media-permission-denied`) because the service does not assume the accessibility trust they need |
| Linux x64, X11 session | `shortcuts.linux.x11-grab` | `XGrabKey`/`XUngrabKey` on the root window per screen with `XSelectInput` key-press delivery, `XK_F1`.. for named keys, `XF86XK_Audio*` for media keys, and a `BadAccess` `XErrorEvent` mapped to `shortcuts.conflict`; a server that cannot grab fails typed |
| Linux x64, Wayland session | `shortcuts.linux.wayland-unavailable` | No global-hotkey facility is advertised because no tested portal or compositor contract exists in v1; every registration fails with `shortcuts.platform-unavailable` instead of falling back to X11 |

### Evidence lifecycle

The RFC binds `kweb-service-shortcuts/src`, `kweb-service-shortcuts/native`,
`kweb-service-shortcuts/build.gradle.kts`, the migration kit, the governance
service catalog, the CI planner metadata, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version, the
C ABI, a provider identity, or the pinned CEF/Chromium identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Accelerators, registrations, capabilities, and events are bounded and immutable, and an invalid value fails typed before a provider sees it. | Named key without a modifier, media key without one, duplicate identifier, unknown command form, empty modifier set, boundary ids. | Common contract tests. | `KWebShortcuts.kt`; `KWebShortcutsContractTest`. | Local and hosted records. | `PASS` — `KWebShortcutsContractTest` covers the identifier and accelerator bounds, the named-key modifier rule, the event identity, the results, and the descriptor shape. |
| A2 / platform providers | One provider per target and session advertises exactly the key classes and modifiers it implements. | Windows, macOS, X11, Wayland. | Provider structure tests on all targets plus the capability report. | Provider snapshots and per-target structure tests. | Provider transcripts. | `PASS` — the macOS, X11, and Wayland structure tests assert each provider's identity and declared facts; the Win32 provider is covered by the hosted Windows run. |
| A3 / invocation ordering | A native activation delivers exactly one ordered invocation to the bound command and reports the source. | Single activation, repeated activation, activation after replacement, activation after close. | Provider observer tests plus native registration counts. | Event sequencing code and provider observer tests. | Invocation transcripts. | `PASS` — a Carbon `kEventHotKeyPressed` and an X11 root `KeyPress` each deliver exactly one ordered `INVOKED` event with source `PLATFORM`; the ABI queue and sequence are asserted. |
| A4 / conflict and reserved | A combination another process owns, or a reserved/disabled combination, reports a stable conflict instead of a silent no-op. | Second process grab on X11, duplicate registration on macOS, reserved key, disabled combination. | Provider tests and the native conflict fixture. | Conflict mapping and provider tests. | Conflict transcripts. | `PASS` — a duplicate macOS registration, a second X client on X11 (`BadAccess`), and a second live registration of one combination each fail with `shortcuts.conflict`; the service owns the accelerator space. |
| A5 / media-key permission | Media keys are registered only where the session grants the facility and fail typed where it does not. | macOS declared absence, X11 XF86 keysym, Windows `VK_MEDIA_*`, Wayland absence. | Provider tests plus the capability report. | Media-key path and permission gate. | Media transcript. | `PASS` — the X11 and Win32 providers advertise and register the `XF86`/`VK_MEDIA_*` keys; the macOS provider advertises `media_key_bits = 0`, so a media registration fails with `shortcuts.media-permission-denied` instead of guessing a permission. |
| A6 / lifecycle and replacement | `replaceAll` is atomic and `close` leaves no active hotkey. | Replace success, replace failure keeps the previous set, unregister unknown is idempotent, close then register fails typed. | Common tests plus provider registration-count fixtures. | Replacement and close code and tests. | Lifecycle transcript. | `PASS` — the ABI tests reject a duplicate identifier, validate replacement before any native call, bound the count, keep unregister idempotent, and fail typed after close. |
| A7 / session lock | A locked session releases the hotkeys and re-registers them on unlock, reporting the loss it observes. | Lock, unlock, no-op lock notification. | Provider observer tests where the platform reports a lock. | Session-lock handling. | Lock transcript. | `PASS` — the Win32 provider releases every hotkey on `WTS_SESSION_LOCK` (one `LOST` event each) and restores them on unlock, and only advertises `SESSION_LOCK_RELEASE` when the session notification registered; macOS and X11 report the capability absent. |
| A8 / renderer authority | A page may only dispatch an application-declared command with a gesture and consent, and can never register an accelerator or observe one. | Granted dispatch, missing gesture, missing consent, undeclared command, child frame, cross-origin frame, read summary. | Bridge contract tests. | Renderer bridge and its tests. | Bridge transcript. | `PASS` — `KWebShortcutsBridgeTest` proves the granted read, the refusal of a non-visible fact, the missing-grant and child-frame denial, the gesture requirement, the declared-command dispatch, and the typed undeclared-command failure. |
| A9 / native activation | A native activation invokes the registered command exactly once and reports the platform source. | macOS Carbon hotkey event, X11 root KeyPress delivery, unregistered combination, Win32 `WM_HOTKEY`. | Provider structure tests on all targets. | Provider activation paths. | Activation transcripts. | `PASS` on macOS and X11 — the structure tests drive the real activation path (a Carbon `kEventHotKeyPressed` event and an X11 root `KeyPress`) and observe exactly one ordered `INVOKED` event with source `PLATFORM`; the Win32 path is covered by the hosted Windows run. |
| A10 / errors and no fallback | A session without the declared facility or with an unsupported key class fails typed, and no path substitutes another session or accelerator. | X11 without a grabbable key, Wayland, unsupported modifier, unsupported media key. | Provider tests with the facility removed. | Typed error mapping. | Provider transcripts. | `PASS` — a Wayland session, a macOS media key, and an unsupported key each fail with their own stable code, and no path substitutes another session or accelerator. |
| A11 / migration | The Electron `globalShortcut` mapping preserves register/unregister/unregisterAll/isRegistered and blocks `registerAll`. | Each declared method, `registerAll`, unknown method. | Inventory, golden, and migration tests. | Matrix row and focused tests. | Migration compatibility report. | `PASS` — the capability-matrix row is `ADAPTER` for `KWebShortcuts`, the inventory scanner classifies the `globalShortcut` symbol, and the migration compatibility report is recorded as this RFC's artifact. |
| A12 / packaging and docs | Native libraries, generated output, and documentation agree. | Native package contents and packaged resource scan. | Gradle package verification plus governance. | Native runtime ZIP and package verification tasks. | Package digest report. | `PASS` — `verifyNativeShortcutsPackage` checks the exact package contents and retains the digest, and the module README documents the providers and the renderer surface. |
| A13 / CI evidence breadth | CI retains invocation transcripts and native registration counts, never typed keystrokes outside registered combinations. | Transcript contents, registration counts before and after close. | Evidence collector and retained artifacts. | `aggregate-rfc-evidence.sh` and the CI planner. | Retained transcripts. | `PASS` — the change-aware CI records the shortcuts-integration family (invocation report, native and contract and bridge test XML, package digest, and provider ZIP) per target with `--matrix-row global-shortcut`; no keystroke outside a registered combination is retained. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `PASS` — the hosted three-target matrix and the aggregation job of this pull request import the three `shortcuts.hosted` records before merge. |
| A15 / deferred items | No keylogger, no low-level event stream, no accessibility bypass, no in-window fallback, no key remapping. | Capability review and provider declarations. | Public API and provider review. | This RFC's non-goals and descriptor. | Capability transcript. | `PASS` — the descriptor publishes no key-logging or low-level event operation, the macOS provider advertises no media facility without a trusted accessibility path, no provider falls back to an in-window shortcut, and the ABI has no key-remap operation. |

## Contract review record

- Reviewed contract revision: `72276e3` plus this reviewed contract; the review pass
  is recorded honestly as a same-contributor review, not an independent approval.
- Reconnaissance recorded before implementation (real probes, not assumptions):
  - macOS arm64: `RegisterEventHotKey(kVK_F13, cmdKey|optionKey, ...)` returns
    `noErr` and yields a live `EventHotKeyRef`; a second registration of the same
    combination returns `-9878` (`kEventHotKeyExistsErr`), and
    `UnregisterEventHotKey` returns `noErr`. `AXIsProcessTrusted` reports the
    current trust state used to gate media keys. Verified with the pinned
    Command Line Tools toolchain.
  - Linux X11: under `xvfb-run` on Ubuntu 24.04, a second X connection that grabs a
    combination already grabbed by the first receives `BadAccess` through the X
    error handler (the conflict signal), and `XF86XK_AudioPlay` maps to a real
    keycode, so both global hotkeys and media keys are testable.
  - Wayland: no compositor-independent global-hotkey API exists without a portal
    contract, so the v1 Wayland provider declares no facility and fails typed.
- Open questions settled:
  - A named key requires at least one modifier so the service cannot silently claim
    a bare key the session reserves.
  - `replaceAll` is the atomic bulk operation; Electron's best-effort `registerAll`
    is a declared blocker instead of an emulation.
  - The renderer surface dispatches declared commands only and never exposes an
    accelerator or identifier, so a page cannot enumerate the application's keys.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: the typed contract from `f0694d5`, the C ABI
  and providers from `ca17647`, the JVM service and renderer bridge from `45847ca`,
  and the governance catalog, capability-matrix row, and CI metadata from
  `53e60f7` and afterwards.
- Review pass: Delta agent, same contributor as the implementation; the review is
  recorded honestly and is not an independent-person approval.
- Date: 2026-10-12.
- Full PR diff reviewed: the typed contract and descriptor, the versioned C ABI
  with its ordered events and atomic replacement, the Win32/macOS/X11/Wayland
  providers and their native tests, the FFM binding with its explicit padding
  guard, the JVM service, the policy-gated renderer bridge, the migration
  capability-matrix row, and the change-aware CI planner, workflow, and evidence
  collector wiring.
- Row-by-row coverage: A1-A13 and A15 are `PASS` against the tests listed in the
  matrix; A14 depends on the hosted import in this same pull request. No
  requirement is silently excluded.
- Findings and dispositions: the FFM layout guard forced the C compiler's implicit
  padding to be explicit (two bytes before the registration ints, seven before the
  registration-result sequence, six trailing on the event); the X11 conflict path
  releases the lock-variant grabs it already made; the service enforces
  same-process accelerator uniqueness because X11 lets a client re-grab its own
  combination; and `kVK_ANSI_A` is `0`, so the macOS key mapping reports validity
  separately.
- Hosted references: the three-target matrix run, its aggregation job, and the
  strict checked-in evidence verification of this pull request.
- Decision: `PASS` — the capability is published only when the aggregation imports
  the three `shortcuts.hosted` records, backs the promoted `global-shortcut` row,
  and strict governance passes at this revision.
