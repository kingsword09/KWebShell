# RFC 0016: Native notifications and activation

- Status: Implemented
- Priority: P0
- Owners: new `kweb-service-notifications` KMP service and desktop host
- Depends on: RFC 0003, RFC 0004, RFC 0006, RFC 0030
- Electron migration surface: `Notification`; `dialog.showMessageBox` remains
  outside this objective and is assigned to the menu/dialog work.
- Target mapping: `REWRITE`

## Objective and scope

Publish one application-scoped native notification service for Windows x64,
macOS arm64, and Linux x64. The service owns permission state, bounded content,
replacement, action/reply capabilities, explicit close, ordered lifecycle
events, and application activation routing. It never renders a hidden Compose
window or silently changes to an in-page notification.

One implementation objective delivers:

1. the common typed notification contract, generated bridge, policy checks, and
   bounded event stream;
2. one explicit provider per advertised target using Windows toast/WinRT,
   `UNUserNotificationCenter`, and the session D-Bus
   `org.freedesktop.Notifications` contract;
3. packaged application identity from RFC 0030 and a host-only activation sink
   that routes warm and cold activations into RFC 0006;
4. action/reply capability reporting, typed rejection of unsupported fields,
   replacement, close, owner shutdown, permission and rate-limit errors; and
5. the closed Electron `Notification` migration mapping, real three-target
   evidence, documentation, and final acceptance review.

The v1 icon field is `APPLICATION`: it resolves only to the verified packaged
application icon in RFC 0030. Renderer bytes, URLs, filesystem paths, custom
formats, and arbitrary image decoding are not notification inputs. Custom icon
values become available only after RFC 0027 publishes its independent image
contract.

## Common implementation contract

The service is `APPLICATION` scoped. Kotlin owns the lifecycle, validation,
policy, event ordering, replacement table, and timeout scheduler. Platform
providers own native identifiers, callbacks, permission APIs, and native
objects behind a small C ABI/FFM boundary.

```kotlin
public interface KWebNotifications : KWebNativeService {
    public val descriptor: KWebServiceDescriptor
    public val lifecycle: StateFlow<KWebLifecycleState>
    public val events: Flow<KWebNotificationEvent>

    public suspend fun permission(): KWebNotificationPermission
    public suspend fun requestPermission(): KWebNotificationPermission
    public suspend fun capabilities(): KWebNotificationCapabilities
    public suspend fun show(request: KWebNotificationRequest): KWebNotificationShowResult
    public suspend fun close(id: KWebNotificationId): KWebNotificationCloseResult
}
```

The v1 descriptor is version `1.0.0`, service id `notifications`, and publishes
these operations: `permission`, `request-permission`, `capabilities`, `show`,
`close`, and host-only `events`. Renderer operations use the exact service
grant `native.notifications.<operation>`. `request-permission` requires a
current native-verified gesture and OS consent; `show` and `close` require OS
consent but not a renderer gesture. Host calls bypass renderer grant checks,
never OS consent or lifecycle checks.

### Values and bounds

All string limits are UTF-8 byte limits. Constructors reject controls, invalid
Unicode scalar values, leading/trailing invisible identity characters, and
unknown enum values before provider dispatch.

| Value | v1 rule |
|---|---|
| notification id | required opaque ASCII `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; unique while active |
| replacement tag | optional ASCII `[A-Za-z0-9][A-Za-z0-9._-]{0,127}`; one active notification per `(applicationId, tag)` |
| title | required, 1–256 UTF-8 bytes |
| body | required, 1–4096 UTF-8 bytes |
| icon | exactly `APPLICATION`; no renderer bytes or path |
| urgency | `LOW`, `NORMAL`, or `HIGH` |
| timeout | `SYSTEM`, `SHORT` (5 seconds), `LONG` (30 seconds), or `PERSISTENT` |
| actions | zero through three unique action ids |
| action id | ASCII `[A-Za-z][A-Za-z0-9._-]{0,63}` |
| action title | 1–128 UTF-8 bytes |
| reply placeholder | optional, 1–128 UTF-8 bytes; only for one `REPLY` action |
| reply result | at most 1024 UTF-8 bytes; controls rejected |
| active notifications | at most 32 per service owner |
| show admission | at most 16 accepted `show` calls in any 60-second window |
| event replay | exactly 64 events; sequence starts at 1 and never repeats |

`KWebNotificationRequest` contains `id`, optional `tag`, `title`, `body`,
`icon`, `urgency`, `timeout`, and an ordered action list. An action is either
`BUTTON` or `REPLY`; a reply action includes its placeholder. A request with a
reply action is invalid when it contains another reply action. A request with
actions or reply is accepted only when the current provider capability reports
that field as supported; unsupported fields fail with a typed error and are
never silently dropped.

The complete v1 value declarations are:

```kotlin
@JvmInline
public value class KWebNotificationId(public val value: String)

public enum class KWebNotificationIcon { APPLICATION }
public enum class KWebNotificationUrgency { LOW, NORMAL, HIGH }
public enum class KWebNotificationTimeout { SYSTEM, SHORT, LONG, PERSISTENT }
public enum class KWebNotificationActionKind { BUTTON, REPLY }

public data class KWebNotificationAction(
    public val id: String,
    public val title: String,
    public val kind: KWebNotificationActionKind,
    public val replyPlaceholder: String? = null,
)

public data class KWebNotificationRequest(
    public val id: KWebNotificationId,
    public val tag: String? = null,
    public val title: String,
    public val body: String,
    public val icon: KWebNotificationIcon = KWebNotificationIcon.APPLICATION,
    public val urgency: KWebNotificationUrgency = KWebNotificationUrgency.NORMAL,
    public val timeout: KWebNotificationTimeout = KWebNotificationTimeout.SYSTEM,
    public val actions: List<KWebNotificationAction> = emptyList(),
)

public enum class KWebNotificationShowOutcome { SHOWN, REPLACED }
public data class KWebNotificationShowResult(
    public val id: KWebNotificationId,
    public val outcome: KWebNotificationShowOutcome,
    public val replacedId: KWebNotificationId?,
    public val sequence: ULong,
)

public data class KWebNotificationCloseResult(
    public val id: KWebNotificationId,
    public val sequence: ULong,
)
```

`show` returns `SHOWN` or `REPLACED`, the requested id, the replaced id when
present, and the service event sequence assigned to `SHOWN`. `close` returns
`CLOSED` and its event sequence, or fails `notifications.not-found` when the
id is not active. Closing an already withdrawn native notification is mapped to
one deterministic `CLOSED` event, not a second native close attempt.

### Permission and capability model

```kotlin
public enum class KWebNotificationPermissionStatus {
    NOT_DETERMINED, GRANTED, DENIED, NOT_APPLICABLE, UNAVAILABLE,
}

public data class KWebNotificationPermission(
    public val status: KWebNotificationPermissionStatus,
    public val provider: String,
)

public data class KWebNotificationCapabilities(
    public val actions: Boolean,
    public val replies: Boolean,
    public val replacement: Boolean,
    public val timeout: Boolean,
    public val activation: Boolean,
)
```

`NOT_APPLICABLE` is used only where the declared OS facility has no permission
store (Linux `org.freedesktop.Notifications`). Provider availability remains a
separate fact; a missing session daemon is `UNAVAILABLE`, never an implicit
permission grant. `requestPermission` never flips state in Kotlin. It invokes
the declared OS permission API, waits for its result, and returns the observed
state. A denied or unavailable result is stable and actionable.

### Event and activation contract

Events are closed-shape values and contain no title, body, icon bytes, paths,
native handles, or command lines:

```kotlin
public sealed interface KWebNotificationEvent {
    public val sequence: ULong
    public val id: KWebNotificationId

    public data class Shown(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val tag: String?,
        public val replacedId: KWebNotificationId?,
    ): KWebNotificationEvent

    public data class Action(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val actionId: String,
        public val reply: String?,
    ): KWebNotificationEvent

    public data class Closed(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val reason: KWebNotificationCloseReason,
    ): KWebNotificationEvent

    public data class Failed(
        override val sequence: ULong,
        override val id: KWebNotificationId,
        public val code: String,
    ): KWebNotificationEvent
}

public enum class KWebNotificationCloseReason {
    PROGRAMMATIC, REPLACED, EXPIRED, USER_DISMISSED, OWNER_CLOSED, NATIVE,
}
```

The complete event reasons are `PROGRAMMATIC`, `REPLACED`, `EXPIRED`,
`USER_DISMISSED`, `OWNER_CLOSED`, and `NATIVE`. Native callbacks are deduplicated
by provider operation id. For each notification, `SHOWN` precedes any action or
close event; replacement emits `Closed(REPLACED)` for the old id before the new
`Shown` event. A failed show emits exactly one `Failed` event and no `Shown`.

The host supplies a required `KWebNotificationActivationRouter` when it creates
the provider:

```kotlin
public fun interface KWebNotificationActivationRouter {
    public suspend fun route(activation: KWebNotificationActivation)
}

public data class KWebNotificationActivation(
    public val applicationId: String,
    public val notificationId: KWebNotificationId,
    public val tag: String?,
    public val actionId: String?,
    public val reply: String?,
)
```

RFC 0006 receives the activation through this host-only sink extension:

```kotlin
public fun interface KWebApplicationActivationSink {
    public suspend fun acceptProtocolActivation(uri: String)
}
```

`KWebApplicationLifecycleController` implements the sink without adding a
renderer operation to the RFC 0006 descriptor. The notification host adapter
calls `acceptProtocolActivation` with one canonical `KWebActivationBatch` source
`PROTOCOL` and a `kweb:` URI
whose authority is `notification` and whose query contains only the validated
application id, notification id, action id, and bounded reply. Cold activation
therefore follows the existing RFC 0030 protocol registration and RFC 0006
single-instance transport; warm activation uses the same sink and is delivered
once. A provider may not invoke the router twice for one native response, and
an activation whose application id, notification id, or action id fails
validation is rejected as `notifications.activation-invalid`.

## Lifecycle, concurrency, and errors

The service states are `OPEN`, `CLOSING`, `CLOSED`, and `FAILED`. All provider
calls are serialized per application service. `close()` first stops new
admission, cancels work before native dispatch with `service.owner-closed`,
waits for calls that already crossed the native boundary, withdraws every
active notification once, and then closes the provider. A native action already
accepted retains its result even if owner close starts afterward.

Timeouts are service-owned scheduled closes. `SYSTEM` leaves dismissal to the
OS; `SHORT`, `LONG`, and `PERSISTENT` are exact service policies. If a provider
cannot withdraw an expired notification, the service emits
`notifications.outcome-unknown` and never retries with another backend.

Stable notification errors are:

`notifications.id-invalid`, `notifications.content-too-large`,
`notifications.action-invalid`, `notifications.icon-unsupported`,
`notifications.actions-unsupported`, `notifications.reply-unsupported`,
`notifications.timeout-unsupported`, `notifications.permission-undetermined`,
`notifications.permission-denied`, `notifications.platform-unavailable`,
`notifications.native-failed`, `notifications.not-found`,
`notifications.rate-limited`, `notifications.activation-invalid`,
`notifications.activation-route-failed`, `notifications.outcome-unknown`,
and `service.owner-closed`/`service.cancelled`.

No error exposes notification content, an application path, a native object,
or a provider command line.

## Platform providers and feasibility boundary

| Target | Exact provider | Advertised v1 capabilities | Required absence behavior |
|---|---|---|---|
| Windows x64 | WinRT toast notification manager with RFC 0030 AUMID and packaged identity | actions, reply, replacement, timeout, activation | missing package identity, toast manager, COM/WinRT apartment, or activation registration is typed unavailable |
| macOS arm64 | `UNUserNotificationCenter` and `UNNotificationCenterDelegate` on the AppKit main context | actions, reply, replacement, timeout, activation | denied authorization, missing bundle identity, delegate/center failure is typed permission/native failure |
| Linux x64 | session D-Bus `org.freedesktop.Notifications` (`Notify`, `CloseNotification`, `ActionInvoked`, `NotificationClosed`) | actions, replacement, system timeout, activation; reply is not advertised | missing session bus/daemon or unsupported action behavior is typed unavailable; no portal/GTK/in-page fallback |

The provider id and target capability record are retained with evidence. The
Linux provider uses the exact declared D-Bus name; a fake daemon is allowed
only as the hosted fixture's independent test double and is never a runtime
fallback. The Windows and macOS providers must run against the real platform
frameworks in hosted jobs.

## Electron migration boundary

Only the Electron `Notification` constructor and its declared `show`, `close`,
`click`, `close`, and action/reply mappings are classified. The generated
preload exposes named typed async methods and never publishes Electron's
`Notification` constructor identity, EventEmitter, arbitrary options object,
HTML, JavaScript, path, or generic IPC channel. `dialog.showMessageBox` is not
part of this objective and remains an explicit migration blocker until its own
menu/dialog contract exists.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common contract | The application-scoped service publishes typed permission, capability, show, close, and ordered event contracts with the fixed bounds above. | Empty/max fields, duplicate ids/tags/actions, unknown enum, 32/33 active notifications, 64/65 events. | Common contract tests, generated schema tests, package scan, all three targets. | `kweb-service-notifications` common contract and bridge tests. | Fresh target records bind the contract digest and generated outputs. | `NOT_RUN` before implementation. |
| A2 / permission | Permission request and observed status are exact per provider; denied/undetermined/not-applicable/unavailable are distinct. | Prompt grant/deny, repeated request, Linux daemon present/absent, owner close during prompt. | Real Windows, macOS, Linux providers and negative fixtures. | Permission provider tests and hosted permission transcript. | Redacted status/provider facts only. | `NOT_RUN` before implementation. |
| A3 / content policy | Titles, bodies, ids, tags, icon, urgency, timeout, actions and reply fields are bounded and rejected before native mutation. | UTF-8 boundary, controls, duplicate action, unsupported reply/icon, malformed identity. | Common corpus, bridge validation, native negative ABI tests on all targets. | Common validators, generated bridge, C ABI validation. | No notification body/content retained. | `NOT_RUN` before implementation. |
| A4 / show and replacement | Valid requests show exactly once and same-tag replacement has deterministic old-close/new-show ordering. | First show, duplicate id, tag collision, native rejection, rate limit, concurrent replacement. | Real runtime providers and event-order assertions on all targets. | Service coordinator and provider callbacks. | Provider/status/sequence facts only. | `NOT_RUN` before implementation. |
| A5 / actions and reply | Actions/reply are advertised per target and unsupported fields fail instead of disappearing. | Button activation, reply response, unknown action, duplicate native callback, Linux reply rejection. | OS-visible action fixtures on all advertised targets. | Capability matrix, provider callbacks, migration fixture. | Action id and bounded reply length, no body. | `NOT_RUN` before implementation. |
| A6 / explicit close and timeout | Close and service-owned timeout withdraw the declared notification once with exact reason/result. | Existing/missing id, short/long expiry, persistent close, close race, provider close failure. | Provider integration and native callback/order tests on all targets. | Lifecycle coordinator and native close ABI. | Outcome/reason facts only. | `NOT_RUN` before implementation. |
| A7 / activation | Warm and cold notification activation reaches the correct RFC 0006 application owner exactly once. | Primary/secondary process, wrong app id, unknown action, bounded reply, duplicate callback, owner close. | Packaged protocol activation and real OS notification response on all targets. | RFC 0006 activation sink, provider router, two-process fixture. | Redacted activation transcript and sequence facts. | `NOT_RUN` before implementation. |
| A8 / lifecycle and bounds | Admission is bounded, native calls are serialized, close cancels pre-boundary work, and terminal events are ordered once. | 32/33 active, 16/17 rate window, slow native call, owner close, renderer termination. | JVM lifecycle tests, C ABI live-count tests, CEF bridge fixture. | Coordinator, owner close, event sequence tests. | Counts, statuses and sequence facts only. | `NOT_RUN` before implementation. |
| A9 / platform providers | The declared WinRT, UNUserNotificationCenter, and D-Bus providers are used without backend or UI fallback. | Missing framework, missing session daemon, identity mismatch, native ABI mismatch. | Native ABI and independent real provider jobs on all targets. | Native sources, FFM binding, provider ids. | Provider identity and typed absence evidence. | `NOT_RUN` before implementation. |
| A10 / renderer authority | Renderer creation requires exact origin/main frame, service grant, and declared policy; activation routing is host-only. | Missing grant, child frame, cross-origin page, copied request, owner close. | RFC 0003 policy tests and real CEF fixture on all targets. | Bridge route, policy engine, migration fixture. | Boolean authority outcomes only. | `NOT_RUN` before implementation. |
| A11 / migration | Electron Notification maps only to named typed operations; generic EventEmitter/options/IPC are absent and message boxes remain blocked. | Constructor, show/close, action/reply, malformed options, arbitrary listener/channel. | Manifest, generator golden tests, real migration fixture. | Capability matrix, preload generator, fixture. | Compatibility reports and golden hashes. | `NOT_RUN` before implementation. |
| A12 / security and evidence | No body, title, reply, path, native handle, command or secret enters errors, logs, evidence, or generated output. | Sensitive text, injection characters, provider failure, package scan. | Redaction tests, package/governance scan, all target records. | Error mapper, evidence recorder, package review. | Redacted provider/status facts. | `NOT_RUN` before implementation. |
| A13 / packaging/docs | Service catalog, RFC, migration matrix, packaged identity, generated/native packages and evidence bindings agree. | Stale schema, wrong AUMID/bundle/desktop id, undeclared icon/provider. | Governance, package scan, docs, generated output. | Service catalog, RFC 0030 identity integration, docs. | Final manifest/contracts and target artifacts. | `NOT_RUN` before implementation. |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, reviewed revision, clean worktree and one squash PR. | Skipped target, stale evidence, dirty worktree, partial API, changed contract. | Full PR diff review, hosted matrix, strict governance. | Final RFC 0016 PR and acceptance review. | Three READY records and retained artifacts. | `NOT_RUN` before implementation. |
| A15 / deferred custom image | Custom icon bytes/URLs/path references remain absent until RFC 0027. | Renderer bytes, URL, path, unsupported image format. | Code/catalog/matrix review. | `APPLICATION` icon enum and explicit migration blocker. | No custom image provider/evidence. | `NOT_APPLICABLE` — explicitly deferred to RFC 0027 in this reviewed contract. |

## Readiness review

- Reviewed revision: `2310c32` (`docs: define RFC 0016 notification contract`).
- Review pass: Codex contract review pass, same contributor as the eventual
  implementation; this is not an independent-person approval.
- Date: 2026-10-02.
- Findings and dispositions:
  - The original proposal did not define public signatures, field bounds,
    stable errors, event precedence, rate limits, or evidence redaction. The
    contract above fixes those decisions and gives every acceptance row a
    falsifiable scenario.
  - RFC 0027 is not implemented yet. Custom image bytes, URLs, and paths are
    removed from v1; `APPLICATION` is the only icon value and A15 records the
    explicit boundary.
  - RFC 0006 had no notification activation input. The host-only
    `KWebApplicationActivationSink` extension and canonical `kweb:` protocol
    envelope are now part of this reviewed contract; the RFC 0006 descriptor
    and renderer surface remain unchanged. Its implementation and regression
    evidence are required in the same focused PR.
  - Linux freedesktop notifications has no portable permission store and no
    text-reply action. Those facts are represented as `NOT_APPLICABLE` and a
    false `replies` capability; they are never reported as granted or silently
    downgraded.
  - Windows and macOS require packaged identity for activation. Unpackaged or
    missing identity is typed unavailable, not a permission to use a hidden
    window, browser page, or alternate backend.
  - Local feasibility probes found the macOS UserNotifications SDK headers and
    Linux GIO/GLib build prerequisites. The first native implementation gate
    must compile and exercise the declared Windows WinRT/AUMID provider on the
    hosted Windows target before any supported-state promotion.
- Decision: `READY`.

## Evidence and invalidation

Retained evidence contains only contract/provider digests, permission state,
capability booleans, notification ids/tags, action ids, event sequences,
activation source/status, error codes, timeout/close reasons, provider identity,
and fixture hashes. It never retains title/body/reply text, icon bytes, paths,
native handles, commands, authentication material, or application secrets.

Any change to the common fields/bounds/enums, event ordering, provider mapping,
application identity, RFC 0006 activation sink, generated schema/output, native
ABI, migration fixture, or evidence paths invalidates all affected target
records. The sequence is common/provider tests; native ABI/FFM tests; real
permission/show/action/close/activation fixtures; migration/golden tests;
evidence aggregation; strict governance; final row-by-row review.

## Non-goals

No custom hidden toast window, in-page fallback, remote push transport (RFC
0034), custom image bytes (RFC 0027), arbitrary HTML/JavaScript, arbitrary
commands or paths, generic IPC, notification body persistence, or
`dialog.showMessageBox` implementation.
