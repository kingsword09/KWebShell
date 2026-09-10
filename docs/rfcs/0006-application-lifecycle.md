# RFC 0006: Application lifecycle, single instance, deep links, and file open

- Status: Proposed
- Priority: P0
- Owners: new application-scoped KMP service, desktop host
- Depends on: RFC 0002, RFC 0003, RFC 0030
- Electron migration surface: `app` readiness/activation, single-instance lock, `open-url`, `open-file`, relaunch, quit
- Target mapping: `REWRITE` with optional typed preload events

## Objective

Give Kotlin/Compose one deterministic application owner for process activation,
second-instance arguments, deep links, file-open requests, orderly quit, and
explicit relaunch. Replace Electron main-process lifecycle without reproducing
its JavaScript object model.

## Common KMP contract

Publish an application-scoped service with immutable activation events, a
single-instance lease, canonical URI/file-association payloads, quit reasons,
close negotiation, and relaunch requests. Events are a replay-bounded ordered
`Flow`; readiness is lifecycle state, not a callback registry.

Only Kotlin host code may acquire the process lease or request relaunch/quit.
Renderer exposure is limited to application-declared activation data after
policy filtering.

## Platform provider contract

- Windows: named kernel object plus authenticated local activation transport,
  registered protocol/file activation, and packaged/unpackaged process identity.
- macOS: `NSApplicationDelegate` activation/open URLs/open files and bundle
  relaunch semantics on the AppKit main thread.
- Linux: D-Bus application ownership/activation and desktop-entry URI/file
  handling under the declared desktop session.

## Acceptance

1. Two real packaged processes race for one identity; exactly one owns it and the
   winner receives one ordered, authenticated activation.
2. Unicode arguments, multiple files, malformed URIs, stale senders, crash
   recovery, and simultaneous quit/activation have deterministic results.
3. Deep links and file opens are tested through OS registration on all targets,
   not by directly calling the handler.
4. Engine/Profile/Page shutdown completes before a normal quit; forced OS
   termination is reported separately and never claimed graceful.
5. The migration fixture replaces `app.whenReady`, `requestSingleInstanceLock`,
   `second-instance`, `open-url`, and `open-file` with Kotlin ownership.
6. No renderer can request arbitrary process arguments, executable paths, or
   relaunch commands.

## Evidence

Retain two-process transcripts, activation ordering, registration manifests,
shutdown timing, and zero live native/application owners.

## Non-goals

No Electron event-emitter identity, renderer-controlled quit/relaunch, process
argument disclosure, implicit single-instance fallback, or unregistered scheme
handling.
