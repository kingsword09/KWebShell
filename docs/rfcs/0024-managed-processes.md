# RFC 0024: Policy-controlled managed processes

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-processes` KMP service
- Depends on: RFC 0002, RFC 0003, RFC 0004, RFC 0013
- Electron migration surface: `utilityProcess`, Node `child_process`, `parentPort`, native addons
- Target mapping: `REWRITE`

## Objective

Run application-declared executables or JVM worker entry points under explicit
policy, resource limits, typed streams, and owner shutdown. KWebShell does not
ship Node.js or execute renderer-provided code.

## Common KMP contract

Define immutable process specifications referencing packaged executable IDs,
validated arguments, environment allowlists, scoped working directories,
stdin/stdout/stderr stream policies, time/memory limits, sandbox profile, and
terminal reason. `KWebManagedProcess` is application-scoped and closeable.

## Platform provider contract

Use CreateProcess/job objects and restricted tokens where declared on Windows,
posix_spawn/process groups/sandbox profiles on macOS, and posix_spawn plus
systemd scopes/namespaces/seccomp only when the packaged Linux provider declares
them. Missing isolation fails before launch.

## Acceptance

1. Real helper processes cover stdout/stderr backpressure, bounded stdin, Unicode
   arguments, environment filtering, normal exit, crash, timeout, kill tree, and
   owner shutdown.
2. Executable identity is bound to package digest/signature; renderer paths,
   shell strings, and PATH lookup are rejected.
3. CPU/memory/output quotas terminate with typed reasons and no orphan process.
4. RFC 0004 streams remain ordered and bounded under a noisy helper.
5. Migration fixtures replace utility-process request/response and selected
   child-process workflows; arbitrary Node modules/native addons remain blockers.
6. Hosted evidence proves zero child processes/handles and records only redacted
   command metadata.

## Non-goals

No shell, terminal emulator, Node runtime, npm package execution, arbitrary
dynamic library loading, or claim of identical Chromium utility-process
networking.
