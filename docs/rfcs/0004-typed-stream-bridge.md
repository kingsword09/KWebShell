# RFC 0004: Typed event, binary stream, and message-port bridge

- Status: Proposed
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

## Non-goals

No generic message port, event-name string bus, synchronous stream read,
unbounded queue, silent event loss, Node `Buffer`, or second bridge transport.
