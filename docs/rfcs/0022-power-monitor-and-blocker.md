# RFC 0022: Power monitor and bounded sleep blockers

- Status: Accepted
- Priority: P1
- Owners: new `kweb-service-power` KMP service
- Depends on: RFC 0002, RFC 0004
- Electron migration surface: `powerMonitor`, `powerSaveBlocker`
- Target mapping: `ADAPTER`

## Objective and scope

Publish one application-scoped power service for Windows x64, macOS arm64, and
Linux x64 that observes suspend, resume, session lock, unlock, power-source, and
thermal changes where the session publishes them, and that owns explicit,
time-bounded sleep-blocker leases.

One implementation objective delivers:

1. the typed event, state, capability, lease, and error contract with bounded
   values, ordered events, and a per-session capability report;
2. exactly one provider per target: Win32 power requests on a notification
   window, macOS `IOPMAssertion` plus `NSWorkspace`/IOKit power sources, and the
   Linux logind/UPower contract over the system bus;
3. blocker leases with a required maximum duration, renewal, automatic expiry,
   deterministic composition of concurrent leases, and release on owner close;
4. the policy-gated renderer surface that may only acquire an
   application-declared lease profile with a gesture and consent, while event
   observation can never create a lease;
5. the closed Electron `powerMonitor`/`powerSaveBlocker` mapping, real target
   evidence, documentation, and the final acceptance review.

## Non-goals

No power-plan or sleep-policy mutation, no shutdown prevention without a deadline,
no battery-runtime estimate fabrication, no alternate polling backend, and no
fallback from a missing D-Bus facility to polling or to another bus.

## Common implementation contract

```kotlin
public interface KWebPower : KWebNativeService {
    public val events: Flow<KWebPowerEvent>
    public suspend fun capabilities(): KWebPowerCapabilities
    public suspend fun state(): KWebPowerState
    public suspend fun acquire(request: KWebPowerLeaseRequest): KWebPowerLease
    public suspend fun renew(id: KWebPowerLeaseId): KWebPowerLease
    public suspend fun release(id: KWebPowerLeaseId): Boolean
    public suspend fun leases(): List<KWebPowerLease>
}
```

The v1 descriptor is version `1.0.0`, service id `power`, scope `APPLICATION`, and
publishes `capabilities`, `state`, `acquire`, `renew`, `release`, `leases`, and
`events` as host operations plus the policy-gated renderer operations below.

### Values and bounds

| Value | v1 rule |
|---|---|
| lease id | opaque ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}`, unique per application |
| lease profile id | the same form; an application-declared profile the renderer may select |
| blocker type | `SYSTEM_SLEEP` (Electron `prevent-app-suspension`) and `DISPLAY_SLEEP` (Electron `prevent-display-sleep`) |
| reason | non-blank, at most 96 UTF-8 bytes, no control characters |
| lease duration | whole seconds from 1 to 14400 (four hours); a request without a duration is rejected |
| live leases | at most 8, and at most 4 per type |
| thermal level | `NOMINAL`, `FAIR`, `SERIOUS`, `CRITICAL` |
| event sequence | strictly increasing per owner, starting at 1; 64 retained events |

A fact the session does not publish is absent from the state and from the
capability report; it is never defaulted. Windows publishes no thermal level, and
Linux publishes no thermal level through the declared facility, so those are typed
absences there instead of a guessed value.

### Lifecycle, concurrency, and limits

- `acquire` is atomic: it either installs the platform assertion or file
  descriptor and returns the lease, or fails typed and installs nothing.
- Every lease carries a required maximum duration. The service expires it exactly
  once, publishes one `LeaseExpired` event, and releases the native assertion even
  if the owner never calls `release`.
- `renew` extends a live lease no further than the declared ceiling from the
  moment of renewal; a renewal of an expired or unknown lease fails typed.
- Concurrent leases compose deterministically: each lease owns exactly one native
  assertion, releasing one lease never releases another, and the native assertion
  is released only when its own lease ends.
- `close` releases every live lease, publishes one `LeaseExpired` per live lease
  with the owner-closed reason, and leaves no assertion, file descriptor, or
  observer behind; a provider failure while closing is reported once as a sticky
  failure.

### Errors, renderer, and migration policy

- Every rejection has a stable `power.*` code; there is no hidden fallback.
- The renderer surface exposes exactly two policy-gated operations for an
  exact-origin main frame with the declared grant: `read` returns a bounded summary
  (whether the session is on battery and how many leases are live) without any
  lease identifier, reason, or assertion detail, and `acquire-profile` acquires one
  application-declared profile with a user gesture and consent for at most the
  profile's declared duration. Event observation never creates a lease, and no
  renderer path can name an arbitrary blocker type or duration.
- The Electron mapping is `ADAPTER`: `powerMonitor` events map onto the declared
  event set, `powerSaveBlocker.start` maps onto a lease acquired through a declared
  profile, and `stop`/`isStarted` map onto `release` and the live lease list.
  Indefinite blockers and unowned leases remain blocked because the service
  requires a bounded duration and an application-declared profile.

| Condition | Stable error |
|---|---|
| malformed identifier | `power.id-invalid` |
| malformed or empty reason | `power.reason-invalid` |
| missing, non-positive, or over-long duration | `power.duration-invalid` |
| duration beyond the declared ceiling | `power.duration-exceeded` |
| blocker type the session cannot assert | `power.type-unsupported` |
| more than the declared live-lease ceiling | `power.limit-exceeded` |
| unknown lease on a non-idempotent path | `power.lease-unknown` |
| renewal beyond the ceiling or of a dead lease | `power.renewal-exceeded` |
| renderer names an undeclared profile | `power.profile-unknown` |
| no declared facility for the session | `power.platform-unavailable` |
| the declared D-Bus service is missing | `power.service-unavailable` |
| renderer acquisition without a gesture | `power.gesture-required` |
| renderer acquisition without consent | `power.consent-denied` |
| renderer asks for a non-visible fact | `power.fact-not-renderer-visible` |
| native assertion failure | `power.native-failed` |
| use after close | `power.owner-closed` |

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| Windows x64 | `power.windows.power-request` | `PowerCreateRequest`/`PowerSetRequest`/`PowerClearRequest` with `PowerRequestSystemRequired` and `PowerRequestDisplayRequired` on a dedicated notification window; `WM_POWERBROADCAST` (`PBT_APMSUSPEND`, `PBT_APMRESUMEAUTOMATIC`, `PBT_APMPOWERSTATUSCHANGE`) for suspend, resume, and power-source changes; `WM_WTSSESSION_CHANGE` for lock and unlock; `GetSystemPowerStatus` for the AC/battery fact; thermal stays absent |
| macOS arm64 | `power.macos.workspace-iokit` | `IOPMAssertionCreateWithName` with `kIOPMAssertionTypePreventUserIdleSystemSleep` and `kIOPMAssertionTypePreventUserIdleDisplaySleep` and `IOPMAssertionRelease`, published as the live-assertion count so an assertion id never leaves trusted logs; `NSWorkspace` will-sleep/did-wake and session-resign/become-active notifications plus `NSWorkspaceWillPowerOffNotification` for suspend and lock; `IOPSCopyPowerSourcesInfo`/`IOPSGetProvidingPowerSourceType` for the AC/battery fact; `NSProcessInfo.thermalState` with `NSProcessInfoThermalStateDidChangeNotification` |
| Linux x64 | `power.linux.logind-upower` | `org.freedesktop.login1.Manager.Inhibit("sleep"|"idle", ...)` returning an fd released by closing it, `PrepareForSleep` and `Lock`/`Unlock` signals on the system bus, and `org.freedesktop.UPower.OnBattery` for the AC/battery fact; a missing system bus or D-Bus service fails with `power.service-unavailable` instead of polling; thermal stays absent |

The Linux provider speaks the system-bus contract and never inspects the session
type, so one provider serves both X11 and Wayland sessions.

### Evidence lifecycle

The RFC binds `kweb-service-power/src`, `kweb-service-power/native`,
`kweb-service-power/build.gradle.kts`, the migration kit, the governance service
catalog, the CI planner metadata, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version, the
C ABI, a provider identity, or the pinned CEF/Chromium identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Capabilities, state, leases, and events are bounded and immutable, and an invalid value fails typed before a provider sees it. | Empty and over-long identifiers, blank and over-long reasons, zero, negative, over-ceiling durations, absent facts. | Common contract tests. | `KWebPower.kt`; `KWebPowerContractTest`. | Local and hosted records. | NOT_RUN |
| A2 / platform providers | One provider per target publishes exactly the events and facts its session implements. | Windows, macOS, Linux. | Provider structure tests on all targets plus the capability report. | Provider snapshots and per-target structure tests. | Provider transcripts. | NOT_RUN |
| A3 / suspend and resume | A suspend is published once as `Suspended` and the following resume once as `Resumed`, in order. | Real macOS sleep/wake notification, Linux `PrepareForSleep(true/false)`, Windows `PBT_APMSUSPEND`/`PBT_APMRESUMEAUTOMATIC`, duplicate notification. | Provider observer tests. | Event sequencing and observer code. | Event transcripts. | NOT_RUN |
| A4 / lock and unlock | A session lock and unlock each publish one ordered event where the session signals them. | Real macOS session resign/become-active, Linux `Lock`/`Unlock`, Windows `WTS_SESSION_LOCK`/`UNLOCK`, session that publishes none. | Provider observer tests plus the capability report. | Lock observer code. | Lock transcripts. | NOT_RUN |
| A5 / power source and thermal | AC/battery changes and thermal changes are published where the session provides them and are a typed absence where it does not. | macOS power source and thermal change, Linux `OnBattery`, Windows `PBT_APMPOWERSTATUSCHANGE`, Windows/Linux thermal absence. | Provider tests plus the capability report. | Power-source and thermal readers. | State transcripts. | NOT_RUN |
| A6 / lease acquisition and release | A lease installs exactly one real native assertion, and releasing it removes exactly that assertion. | macOS `IOPMAssertion`, Windows power request, Linux inhibitor fd, release of an unknown lease is idempotent. | Provider tests plus the native assertion-count fixture. | Lease code and provider assertions. | Lease transcripts. | NOT_RUN |
| A7 / expiry and renewal | A lease expires exactly once at its deadline, and renewal extends it no further than the ceiling. | Expiry without an explicit release, renewal before and after expiry, renewal past the ceiling. | Common tests plus the provider fixture with a short duration. | Expiry timer and renewal code. | Expiry transcript. | NOT_RUN |
| A8 / composition and crash cleanup | Concurrent leases never release one another, and a crashed owner leaves no live assertion. | Two leases of one type released in either order, owner close with live leases, a child process that exits without releasing. | Provider tests including a real child-process crash on macOS. | Per-lease assertion ownership. | Composition transcript. | NOT_RUN |
| A9 / renderer authority | A page can only acquire an application-declared profile with a gesture and consent, and event observation never creates a lease. | Granted acquisition, missing profile, missing gesture, missing consent, child frame, read summary, read of a non-visible fact. | Bridge contract tests. | Renderer bridge and its tests. | Bridge transcript. | NOT_RUN |
| A10 / no fallback | A session without the declared facility fails typed and never falls back to polling or another bus. | No system bus, missing logind service, missing UPower, unsupported blocker type. | Provider tests with the facility removed. | Typed error mapping. | Provider transcripts. | NOT_RUN |
| A11 / migration | The Electron `powerMonitor`/`powerSaveBlocker` mapping adapts the declared operations and blocks indefinite or unowned blockers. | Each declared event, `start`/`stop`/`isStarted`, indefinite duration, unknown id. | Inventory, golden, and migration tests. | Matrix rows and focused tests. | Migration compatibility report. | NOT_RUN |
| A12 / packaging and docs | Native libraries, generated output, and documentation agree. | Native package contents and packaged resource scan. | Gradle package verification plus governance. | Native runtime ZIP and package verification tasks. | Package digest report. | NOT_RUN |
| A13 / evidence redaction | Retained evidence carries assertion counts, never assertion ids or raw native handles. | Fixture report contents, live count before and after close. | Evidence collector and retained artifacts. | `aggregate-rfc-evidence.sh` and the CI planner. | Retained transcripts. | NOT_RUN |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | NOT_RUN |
| A15 / deferred items | No power-plan mutation, no unbounded shutdown prevention, no battery estimate fabrication, no alternate polling backend. | Capability review and provider declarations. | Public API and provider review. | This RFC's non-goals and descriptor. | Capability transcript. | NOT_RUN |

## Contract review record

- Reviewed contract revision: `0667ab39` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation (real probes, not assumptions):
  - macOS arm64: `IOPMAssertionCreateWithName(kIOPMAssertionTypePreventUserIdleSystemSleep, ...)`
    returned `kIOReturnSuccess` with a live assertion id and `IOPMAssertionRelease`
    returned `kIOReturnSuccess`; `IOPSCopyPowerSourcesInfo`/`IOPSCopyPowerSourcesList`
    are reachable; `NSProcessInfo.thermalState` is reachable; and `NSWorkspace`'s
    notification center accepts a will-sleep observer. Verified with the pinned
    Command Line Tools toolchain.
  - Linux: without a system bus, `g_bus_get_sync(G_BUS_TYPE_SYSTEM)` fails, which is
    the typed `power.service-unavailable` boundary rather than a polling fallback;
    with `DBUS_SYSTEM_BUS_ADDRESS` pointed at a private bus the connection succeeds
    and a missing `org.freedesktop.login1` returns `ServiceUnknown` instead of
    blocking. A logind/UPower test double on that bus therefore exercises the real
    D-Bus provider path.
  - Windows: the declared facilities are `PowerCreateRequest`/`PowerSetRequest`/
    `PowerClearRequest` with `WM_POWERBROADCAST` and `WM_WTSSESSION_CHANGE`; there is
    no local Windows host, so the provider is verified by the hosted Windows run.
- Open questions settled:
  - Every lease requires a maximum duration, so Electron's indefinite blocker
    cannot be represented and is an explicit blocker rather than a silent
    unbounded assertion.
  - The renderer may only acquire an application-declared profile, so a page can
    never name a blocker type or an arbitrary duration.
  - Assertion identifiers stay inside the provider; the retained evidence reports
    counts only.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: to be recorded when the implementation PR is
  complete; this section is filled by the same pull request before merge.
- Review pass: Delta agent, same contributor as the implementation; recorded
  honestly and not an independent-person approval.
- Decision: `BLOCKED` until the implementation, real three-target evidence, and the
  row-by-row acceptance are complete in this focused pull request.
