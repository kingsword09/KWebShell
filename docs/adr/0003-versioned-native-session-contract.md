# ADR 0003: Use a versioned native session contract

- Status: Superseded by ADR 0006
- Date: 2026-08-11

## Context

KWebShell needs a stable boundary between Kotlin/JVM orchestration and the C++ Chromium host. CEF objects, callbacks, allocators, and standard-library types cannot safely cross a binary boundary compiled by different platform toolchains. JNI also has different text, thread, exception, and reference-lifetime rules from both Kotlin and C++.

Phase 2 proved the JNI ownership boundary without publishing a browser API before a real CEF page was connected. A queued navigation request was not evidence that Chromium committed or rendered the URL. Objective 3.3 deliberately removes this echo contract.

## Decision

Use a small C ABI as the native ownership boundary and place a separately built JNI adapter above it.

### ABI versioning

- ABI v1 exports C linkage functions and fixed-width integer status, event, and opaque handle values. It exports no C++, CEF, JNI, or platform-window type.
- Every input or callback structure begins with `struct_size` and `abi_version`. An undersized structure or unknown version fails with `KWEB_STATUS_ABI_MISMATCH`.
- Status and event numeric values are stable within an ABI version. Unknown status values remain observable instead of being mapped to success.
- Handles are positive `uint64_t` identifiers owned by the native registry. Zero is always invalid, and a released handle is never reused within the process.
- The shared C ABI library has major version 1. The JNI library links to that adjacent artifact. Kotlin derives the one platform-specific ABI filename from an explicitly configured absolute JNI path and loads both adjacent files by absolute path, ABI first.

This project is pre-1.0. An incompatible contract change increments the ABI version and updates the C header, native implementation, JNI adapter, Kotlin mapping, tests, packaging, and documentation together. It does not add a compatibility shim or silently load another library.

### Event and text lifetime

- A session owns one native worker queue. It emits callbacks serially with a contiguous sequence beginning at 1.
- `kweb_event` and its UTF-8 `text` pointer are borrowed and valid only for the duration of the callback. Consumers must copy data they retain.
- Navigation text is standard UTF-8 without embedded nulls. JNI performs explicit UTF-16 conversion, including supplementary code points; it does not use JNI modified UTF-8 helpers.
- JNI attaches a native callback thread when required, calls the session-owned global sink reference, clears and records Java callback failures, deletes local references, and detaches a thread it attached.
- Kotlin copies each event into an immutable value and serializes listener delivery through one session-owned callback executor. Listener failures become typed errors.

### Ownership and close

- `kweb_session_create` transfers ownership only when it returns `KWEB_STATUS_OK` and a nonzero handle. The registry owns the session until one successful close removes that handle.
- A session accepts navigation and resize commands until close wins arbitration. Close stops further acceptance, drains every previously accepted command, emits one terminal `session_closed` event, joins the worker, and releases registry ownership before returning success.
- A racing operation returns only its declared result: success, session closing, or invalid handle. A callback-thread close is rejected as reentrant because joining the current native worker would deadlock.
- JNI owns exactly one global sink reference per live handle. It deletes that reference only after native close has joined the worker, so native code cannot call a released JVM object.
- Kotlin close is concurrent-safe and idempotent. It clears its handle once, waits for native close and queued callback delivery, then requires the terminal closed state. No callback may begin after close completes.
- Commands after Kotlin close fail immediately with a typed error. C calls using a released handle return `KWEB_STATUS_INVALID_HANDLE`.

The Phase 2 events are internal transport evidence. `navigation_requested` confirms ordered delivery only; no `navigation_committed`, page, engine, DevTools, or rendering success is exposed.

## Consequences

- Kotlin lifecycle and error handling can evolve independently from CEF implementation types.
- The same pure C header can be consumed by JNI now and Kotlin/Native cinterop later.
- Every advertised desktop target must build and load two real shared libraries and pass the same lifetime tests.
- Close is intentionally blocking at this internal boundary so its ownership guarantee is unambiguous. A future public asynchronous API may orchestrate it off the UI thread without weakening the native contract.
- Phase 3 can connect real browser operations behind this boundary, but it must add result events backed by CEF callbacks and conformance tests before exposing them publicly.

## Rejected alternatives

### Pass CEF or C++ objects through JNI

Rejected because it couples Kotlin ownership to compiler-specific layouts, reference counting, and CEF threading rules.

### Use pointer-shaped public handles

Rejected because pointer values expose implementation details, make stale-handle diagnostics weaker, and complicate future out-of-process hosting.

### Load by library name or search for an available backend

Rejected because loader search order is environment-dependent and could select an incompatible or unintended artifact. KWebShell does not modify `PATH`, `java.library.path`, or another loader search path; a missing, misplaced, or incompatible adjacent artifact fails explicitly.

### Publish a browser session backed by command echo events

Rejected because accepting a request is not browser execution. The internal contract remains non-public until real Chromium callbacks establish browser semantics.
