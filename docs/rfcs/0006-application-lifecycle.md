# RFC 0006: Application lifecycle, single instance, deep links, and file open

- Status: Implementing
- Priority: P0
- Owners: `kweb-service-application-lifecycle`, desktop host
- Depends on: RFC 0002, RFC 0003, RFC 0030
- Electron migration surface: `app` readiness/activation, single-instance lock, `second-instance`, `open-url`, `open-file`, relaunch, quit
- Target mapping: `REWRITE` with optional typed preload events

## Objective and scope

Give Kotlin/Compose one deterministic application owner for process activation,
second-instance arguments, deep links, file-open requests, orderly quit, and
explicit relaunch. Replace Electron main-process lifecycle with a typed,
application-scoped KMP service. The service owns the process lease and OS
activation boundary; it does not expose Electron event-emitter identity.

One implementation objective delivers:

1. the common lifecycle contract and bounded ordered activation stream;
2. one explicit provider for Windows, macOS, and Linux desktop targets;
3. real package registration and two-process activation tests using the RFC 0030
   package association declarations;
4. shutdown/relaunch integration with the existing `KWebDesktopEngine`,
   Profile, Page, and native-service owners; and
5. the closed Electron lifecycle migration mapper and retained hosted evidence.

The service is host-only. Renderer code receives filtered activation events only
when the host explicitly installs a typed bridge route. Renderer code never
acquires the lease, reads raw process arguments, chooses an executable, or
requests quit/relaunch.

## Implementation contract

### A. Common typed model

The new `kweb-service-application-lifecycle` module publishes the following
common contract. The exact names are part of the v1 contract; implementations
may not replace them with maps, arbitrary JSON, or string event names.

```kotlin
public interface KWebApplicationLifecycle : KWebNativeService {
    public val state: StateFlow<KWebApplicationLifecycleState>
    public val events: SharedFlow<KWebApplicationEvent>

    public suspend fun start(initial: KWebActivationBatch): KWebApplicationStartResult
    public suspend fun requestQuit(reason: KWebQuitReason): KWebQuitResult
    public suspend fun requestRelaunch(): KWebRelaunchResult
    public fun registerShutdownParticipant(
        participant: KWebApplicationShutdownParticipant,
    ): KWebApplicationShutdownRegistration
}

public interface KWebApplicationShutdownParticipant {
    public val id: String
    public val order: Int
    public suspend fun requestClose(reason: KWebQuitReason): KWebShutdownVote
    public suspend fun close(reason: KWebQuitReason)
}
```

`KWebApplicationLifecycleConfiguration` contains the RFC 0030 application ID,
the explicit `KWebTarget`, the canonical package identity, the declared
protocol/file association set, the verified relaunch executable relative to
the package root, and these fixed limits:

| Field | v1 rule |
| --- | --- |
| activation replay capacity | exactly 64 accepted events |
| URI UTF-8 size | at most 4096 bytes per URI |
| file count per activation | at most 32 files |
| file path UTF-8 size | at most 4096 bytes per path |
| activation transport frame | at most 256 KiB encoded bytes |
| shutdown deadline | 30 seconds for the complete participant sequence |
| event sequence | unsigned 64-bit, starts at 1, never repeats within one lease |

The configuration rejects an application ID, package identity, target, or
association set that does not exactly match the verified RFC 0030 manifest. It
also rejects an absolute or unverified relaunch executable, a zero/negative
limit, and a second configuration after startup.

`KWebActivationBatch` contains only canonical data:

```kotlin
public data class KWebActivationBatch(
    public val source: KWebActivationSource,
    public val uris: List<String>,
    public val files: List<KWebOpenedFile>,
)

public data class KWebOpenedFile(
    public val absolutePath: String,
    public val exists: Boolean,
)
```

The service never publishes the original command-line array, executable path,
environment, current directory, sender PID, authentication secret, or native
handles. URI canonicalization accepts only the schemes declared by RFC 0030,
rejects NUL/control characters, malformed percent escapes, user-info, and
unregistered schemes, and preserves Unicode after strict UTF-8 validation.
File paths must be absolute, normalized, NUL-free, and within the OS path
grammar; an existing path is resolved without exposing symlink metadata and a
missing path is retained with `exists == false` for deterministic error
handling by the application.

### B. State, ordering, and terminal results

The lifecycle state machine is:

```text
NEW -> STARTING -> PRIMARY_READY -> QUIESCING -> CLOSED
                  \\-> SECONDARY_FORWARDED -> CLOSED
STARTING/PRIMARY_READY/QUIESCING -> FAILED
```

All public methods and native callbacks are serialized through one lifecycle
actor. Native callbacks never wait for Kotlin event collectors or shutdown
participants. Accepted activations are assigned sequence numbers before they
enter the replay-bounded stream. A full queue returns
`application.activation.queue-full`; it never drops, reorders, or silently
truncates an activation.

`start` is single-use. A primary process returns `PRIMARY` and emits `READY`;
a secondary process authenticates one activation frame, returns
`SECONDARY_FORWARDED`, emits no renderer-visible event, and must exit without
starting CEF. A stale, unauthenticated, oversized, malformed, or wrong-identity
frame returns a typed failure and never changes the primary state.

When `requestQuit` wins the close race, the service enters `QUIESCING`, rejects
new activation frames with `application.lifecycle.closing`, drains already
accepted activation events in sequence order, asks participants for votes in
descending `order`, and then calls `close` in the same order. A `DENY` vote
returns `VETOED` and restores `PRIMARY_READY` only when no participant has
started closing. Once a participant close starts, the result is terminal;
partial close is `FAILED` and is never reported as graceful. Concurrent quit
requests coalesce to the first reason and return the same terminal result.

The host records `GRACEFUL` only after the engine, every Profile, every Page,
the native-service registry, and the lifecycle lease have closed. OS kill,
watchdog expiry, native crash, and process loss are recorded as `FORCED` or
`FAILED`; none is reported as a graceful quit.

### C. Lease and authenticated activation transport

The primary lease is tied to the RFC 0030 application identity and current user.
The activation envelope contains schema version, application identity, a
per-launch nonce, sequence number, canonical URI/file payload, and an
authentication proof. The proof is never exposed to Kotlin renderer code.

The platform providers are explicit and fail fast; no provider detection or
fallback is permitted:

| Target | Lease | Activation and registration boundary |
| --- | --- | --- |
| Windows x64 | `CreateMutexW` in the current-user namespace | Authenticated named pipe with `PIPE_REJECT_REMOTE_CLIENTS`, same-user token validation, and RFC 0030 per-user protocol/file registration; packaged and unpackaged identities are distinct and explicit. |
| macOS arm64 | bundle-identity lease owned by the `NSApplication` instance | `NSApplicationDelegate` `openURLs`, `openFiles`, and reopen callbacks on the AppKit main thread; `NSRunningApplication` activation and Launch Services registration use the RFC 0030 bundle ID. |
| Linux x64 | D-Bus well-known application name ownership | `org.freedesktop.Application.Activate`/Open-style method on the session bus; sender UID/PID is verified through D-Bus credentials, and desktop/MIME registration uses the RFC 0030 desktop ID. |

The native boundary is a small versioned C ABI consumed only by the internal
JDK 25 FFM binding. CEF C++ types do not cross it. The ABI reports provider
identity, lease state, transport status, registration status, and terminal
close status through typed numeric results and UTF-8 records. It must reject a
missing OS session, wrong identity, unsupported package mode, remote sender,
or unavailable registration API before claiming success.

### D. Registration, relaunch, and shutdown integration

`installAssociations` and `removeAssociations` are explicit host operations;
starting the lifecycle service does not silently mutate the OS. Installation
uses only the identity-bound declarations emitted by RFC 0030. The provider
returns an observed registration digest and target identity. Removal returns a
cleanup digest and proves that the provider-owned registration marker is absent.

`requestRelaunch` has no executable or argument parameter. It launches only the
verified RFC 0030 main executable, preserves no raw process arguments, and
accepts only a boolean `preservePendingActivation` option fixed by the host
configuration. It fails with `application.relaunch.unavailable` when the
package is unpackaged, the executable is not verified, or the provider cannot
launch the exact target. Relaunch is allowed only from Kotlin host code.

The desktop host registers one shutdown participant that closes Page, Profile,
Engine, and installed native services in their existing reverse ownership
order. The participant reports each close stage and a monotonic elapsed time;
the lifecycle service does not claim success until all stages report closed.

### E. Stable errors and policy

The closed error identifiers are:

| Code | Meaning |
| --- | --- |
| `application.lifecycle.configuration-invalid` | Identity, target, limits, package, or registration configuration is invalid. |
| `application.lifecycle.already-started` | The lifecycle object was started twice. |
| `application.lifecycle.secondary` | This process is not the primary owner; details identify `SECONDARY_FORWARDED`. |
| `application.lifecycle.closing` | The lifecycle is quiescing or terminal. |
| `application.lifecycle.lease-unavailable` | The selected platform lease could not be acquired. |
| `application.activation.invalid` | URI/file payload violates the canonical activation contract. |
| `application.activation.unregistered-scheme` | The URI scheme is not in the RFC 0030 association set. |
| `application.activation.auth-failed` | The local sender failed identity or authentication checks. |
| `application.activation.queue-full` | The bounded activation queue cannot accept another batch. |
| `application.activation.transport-failed` | The selected OS activation transport failed. |
| `application.registration.failed` | OS association install/remove failed with target status. |
| `application.quit.vetoed` | A shutdown participant rejected a normal quit. |
| `application.quit.timeout` | The complete shutdown sequence exceeded 30 seconds. |
| `application.quit.forced` | The OS terminated the process without graceful ownership closure. |
| `application.relaunch.unavailable` | The exact verified relaunch target is unavailable. |
| `application.native.abi-mismatch` | The native lifecycle provider ABI is not the pinned version. |
| `application.native.unavailable` | The required platform facility is unavailable; no fallback is selected. |

Renderer policy is narrower than host policy: no lifecycle operation is
renderer-callable. A typed, application-declared activation event route may
expose only canonical URI/file payloads after the host has granted that route;
raw command-line and process identity data remain unavailable.

### F. Migration contract

`KWebElectronApplicationLifecycleMapper` accepts only a closed declaration of
the following Electron concepts: `whenReady`, `requestSingleInstanceLock`,
`second-instance`, `open-url`, `open-file`, `quit`, and `relaunch`. It maps them
to the typed lifecycle owner and records unsupported dynamic listeners,
arbitrary executable relaunch, raw argument access, and renderer quit requests
as blocking findings. It never executes Electron callbacks or creates an event
emitter.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / common contract | The lifecycle API exposes typed identity, state, activation, quit, relaunch, participant, and error contracts with fixed limits. | Valid Unicode activation; invalid identity; zero limits; duplicate start; no raw argument field. | Common contract tests; schema/ABI review; all advertised targets. | `KWebApplicationLifecycle*` common contract and test suite. | Lifecycle contract digest and common test reports. | `NOT_RUN` before implementation. |
| A2 / single instance | Exactly one real process owns the identity lease; a secondary forwards one authenticated activation and exits without CEF startup. | Two-process race; stale lease; crash recovery; wrong identity; stale nonce; simultaneous startup. | Two real packaged processes and native provider transcript on Windows, macOS, Linux. | Platform lease provider, process harness, native C ABI integration. | Two-process transcript, lease owner identity, startup/exit codes. | `NOT_RUN` before implementation. |
| A3 / activation ordering | Canonical URI/file activations are authenticated, bounded, ordered, replay-bounded, and never silently dropped. | Unicode URI/file names; malformed URI; unregistered scheme; 64/65 event boundary; oversized frame; simultaneous quit/activation. | Common property tests plus native transport tests on all three targets. | Activation canonicalizer, actor/queue, transport provider. | Ordered activation transcript and sequence digest. | `NOT_RUN` before implementation. |
| A4 / OS registration | Deep links and file opens arrive through RFC 0030 OS registration and retain observed identity/cleanup evidence. | Install, activate URI, activate Unicode file, multiple files, wrong package identity, remove and verify cleanup. | Real packaged registration/activation harness on Windows, macOS, Linux. | Registration provider and RFC 0030 association reader. | Registration manifest, activation transcript, cleanup digest. | `NOT_RUN` before implementation. |
| A5 / shutdown | Normal quit closes Page/Profile/Engine/services before the lease and records graceful completion; forced termination is distinct. | Normal close; participant veto; participant failure; timeout; native crash/OS kill; close called twice. | Desktop integration with real CEF on all three hosted targets; shutdown timing transcript. | Desktop lifecycle participant and `KWebApplicationLifecycle.requestQuit`. | Shutdown stage transcript, elapsed times, terminal outcome, live-owner counts. | `NOT_RUN` before implementation. |
| A6 / relaunch | Host-only relaunch starts exactly the verified RFC 0030 executable without exposing or accepting arbitrary arguments. | Packaged relaunch; unpackaged failure; wrong executable; concurrent relaunch; preserve/discard activation choice. | Process harness and package identity verification on all three targets. | Relaunch provider and migration mapper. | Relaunch parent/child transcript and identity digest. | `NOT_RUN` before implementation. |
| A7 / security and policy | Renderer cannot acquire lease, read raw process data, request quit/relaunch, or bypass authentication. | Generated renderer route inspection; forged sender; cross-user/remote sender; malformed payload; arbitrary executable request. | Common negative tests, native sender-auth tests, generated bridge inspection. | Host-only API boundary, native authentication, migration negative fixtures. | Redacted security transcript; no secret/native pointer/argv payloads. | `NOT_RUN` before implementation. |
| A8 / platform feasibility | Windows, macOS, and Linux use their declared native APIs and fail explicitly when the session/facility is unavailable. | No D-Bus session; AppKit callback on wrong thread; Windows pipe remote sender; unsupported package mode. | Native provider probes and real hosted runtime artifacts on all three targets. | Versioned lifecycle C ABI and FFM binding. | Provider identity/status records and ABI fingerprint. | `NOT_RUN` before implementation. |
| A9 / migration | Closed Electron lifecycle declarations map to typed Kotlin ownership; unsupported dynamic behavior blocks generation. | Valid mapping; dynamic listener; raw argv; arbitrary relaunch path; renderer quit. | JVM migration fixture tests and generated report. | `KWebElectronApplicationLifecycleMapper`. | Migration report and generated digest. | `NOT_RUN` before implementation. |
| A10 / documentation and evidence | Contract, package registration, user-facing lifecycle guidance, capability metadata, tests, and evidence are updated together. | Missing docs, stale association digest, skipped target, stale provider/ABI revision. | Governance check, documentation check, complete PR diff review. | RFC, lifecycle format doc, module docs, evidence contracts/aggregator. | Evidence manifest and retained artifacts. | `NOT_RUN` before implementation. |
| A11 / universal completion | All applicable requirements are PASS on every advertised target and final review records the exact merged revision. | Any missing artifact, stale digest, failed target, skipped native verification, or changed contract after review blocks merge. | `:kweb-rfc-governance:check`, `git diff --check`, hosted aggregation, final row-by-row review. | Full PR implementation and acceptance review. | Final contract/evidence digests and merged revision. | `NOT_RUN` before implementation. |

## Readiness review

- Reviewed revision: `90f79fd` (`main` after RFC 0030 squash merge), with the
  dependency contracts from RFC 0002, RFC 0003, and RFC 0030 inspected.
- Review pass: Codex implementation-readiness review, same contributor as the
  eventual implementation; this is not an independent-person approval.
- Date: 2026-09-22.
- Decisions: the deliverable is one application-scoped service module plus
  explicit Windows/macOS/Linux providers; activation data is canonical and
  bounded; the process lease and native transport are host-only; shutdown
  completion is a prerequisite for graceful quit; relaunch is bound to the
  verified RFC 0030 executable; OS registration is explicit and reversible; no
  fallback backend or renderer lifecycle operation exists.
- Feasibility decisions: Windows uses a named mutex and authenticated named
  pipe; macOS uses `NSApplicationDelegate`, `NSRunningApplication`, and Launch
  Services; Linux uses a session D-Bus well-known name and credential-checked
  activation method. The small native ABI is consumed through the existing JDK
  25 FFM boundary and does not expose CEF types.
- Findings and disposition: the original RFC lacked concrete data types,
  lifecycle states, queue/size limits, authentication rules, terminal-result
  precedence, stable errors, registration cleanup semantics, and falsifiable
  evidence. This revision settles each item and adds A1-A11 mapping.
- Decision: `READY`.

## Evidence lifecycle

Hosted lifecycle records live under
`docs/rfcs/evidence/artifacts/0006/<sourceRevision>/<target>/application-lifecycle/`.
The retained report binds the RFC 0006 contract digest, RFC 0030 manifest and
package identity digest, native ABI fingerprint, target provider identity,
two-process transcript digest, registration/cleanup digest, shutdown transcript,
and final owner counts. The hosted lifecycle artifact is accompanied by the
real CEF `application-shutdown.json` artifact from `kweb-desktop`; the latter
records the native engine, native services, and Profile close stages and zero
live CEF owners. Any change to this RFC, the lifecycle module/provider,
RFC 0030 association metadata, native ABI, or hosted workflow invalidates the
affected record and requires a fresh three-target aggregation.

## Non-goals

No Electron event-emitter identity, renderer-controlled quit/relaunch, process
argument disclosure, implicit single-instance fallback, unregistered scheme
handling, arbitrary executable selection, CEF type leakage through the native
ABI, or graceful-success claim after forced termination.
