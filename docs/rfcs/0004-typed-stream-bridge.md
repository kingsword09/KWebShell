# RFC 0004: Typed event, binary stream, and message-port bridge

- Status: Implemented
- Priority: P0
- Owners: `kweb-bridge`, `kweb-bridge-codegen`, `kweb-desktop`
- Depends on: RFC 0001, RFC 0003
- Electron migration surface: `MessageChannelMain`, `MessagePortMain`, IPC events, progress streams
- Target mapping: `ADAPTER`

## Objective

Extend request/response dispatch with generated, bounded, cancellable streams
needed by downloads, process output, notifications, devices, and large binary
transfers. Preserve one transport and exact-origin policy rather than adding an
Electron event emitter or generic message channel.

## Contract

Schemas declare server event streams, client acknowledgements, binary chunk
types, queue capacity, overflow policy, sequence numbers, terminal frames, and
maximum aggregate size. The generated Kotlin API uses `Flow`; TypeScript uses
`AsyncIterable` plus explicit `close()`/`AbortSignal`.

Every stream is bound to one Page main frame, committed origin, service
operation, owner, and schema version. Navigation, permission revocation, owner
close, timeout, or renderer disconnect emits one declared terminal result.
Backpressure is mandatory; dropping, unbounded buffering, and silent truncation
are prohibited.

## Acceptance

1. Deterministic generator tests cover events-only, bytes-only, duplex control,
   schema incompatibility, and generated TypeScript strictness.
2. Stress tests transfer bounded binary chunks and one million sequenced small
   events without reordering, unbounded memory, or request starvation.
3. CEF tests cover slow consumers, abort, navigation, renderer crash, service
   close, malformed acknowledgements, child frames, and cross-origin access.
4. JVM/native thread tests prove no blocking wait occurs on CEF UI, AWT, AppKit,
   Win32, D-Bus, or FFM upcall threads.
5. The migration adapter exposes named application streams only; arbitrary
   `postMessage`, transferable object identity, and Node `Buffer` are absent.

## Evidence

Retain throughput, peak queue/memory, sequence/terminal transcript, generated
digests, and CEF process-exit evidence on all hosted targets.

## PR #70 terminal response repair review

Codex reviewed revision `9866841` and hosted failure `37563468017` on 2026-10-07
in a separate pass by the same contributor. The existing sink contract requires
`send` to return false when its owner/query is gone. Native session removal can
precede dispatch of the queued Kotlin cancellation callback, so a final response
may return `INVALID_HANDLE`. That status is an expected cancellation only after
the native terminal callback has arrived or explicit owner close has begun;
invalid handles on live owners and other native failures remain observable errors.
Mark terminal receipt without running listeners or waiting on the FFM upcall
thread. Preserve event order and let the existing dispatcher cancel the stream.

Decision: **READY** for E70.5 in `DESIGN_PLAN.md`. Add a deterministic real CEF
probe that holds an in-flight frame across renderer termination and native close,
then requires false delivery, stream cancellation, no callback failure and zero
owners. Fresh Windows x64, macOS arm64 and Linux x64 evidence remains **BLOCKED**
until that probe passes. This repairs the existing terminal contract and does not
retroactively approve earlier evidence.

## Terminal response repair acceptance

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A3.1 / acceptance 3, E70.5 | An in-flight stream response after confirmed native teardown cancels without a callback failure. | Deliver the first frame; hold the next across a real renderer crash/native CLOSED; invalid live owners and other native errors still fail. | Desktop unit tests and real CEF on Windows x64, macOS arm64, Linux x64. | `isExpectedStreamTerminationStatus`; `NativeStatusContractTest.streamTeardownDoesNotHideNativeErrorsOrInvalidLiveOwners`; `NativeEngineIntegrationMain.runRendererCrashLifecycle`. | Local real CEF probe failed before repair and passed after; hosted `renderer-lifecycle-evidence.json` must retain `in-flight-frame-after-native-close-is-cancelled`. | `BLOCKED` — local regression passed; fresh three-target evidence pending. |

## Non-goals

No generic message port, event-name string bus, synchronous stream read,
unbounded queue, silent event loss, Node `Buffer`, or second bridge transport.
