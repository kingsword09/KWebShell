# ADR 0009: Origin-scoped generated typed bridge

- Status: Accepted
- Date: 2026-08-13

## Context

KWebShell needs typed communication between page JavaScript and Kotlin without
exposing CEF objects, executing handlers on the CEF UI thread, or installing a
privileged object into every renderer context. A bridge attached by URL pattern,
child-frame inheritance, or DevTools would widen the trust boundary. A custom
request router would also have to reproduce Chromium context cancellation and
renderer teardown behavior already implemented by CEF.

## Decision

ABI v5 adds a dedicated bridge event callback plus success and failure response
operations. Bridge activation requires both a Kotlin dispatcher and one exact,
normalized HTTP or HTTPS origin. The browser passes that decision to its renderer
through CEF `extra_info`. `CefMessageRouterRendererSide` installs query functions
only in the enabled browser's main-frame V8 context when the committed URL has
the declared origin. Child frames, cross-origin main pages, DevTools, and browsers
without bridge configuration receive no bridge functions.

`CefMessageRouterBrowserSide` owns renderer query cancellation. Native code maps
each live CEF query to a monotonically increasing request ID and atomically
consumes it on success, typed failure, renderer cancellation, navigation, or
browser close. Kotlin dispatches each request in a dedicated coroutine scope off
the CEF and FFM upcall threads. Page cancellation cancels the exact handler job;
browser close cancels and joins every remaining job before the JVM owner returns.

`kweb-bridge` defines the closed version-1 JSON envelope and typed failure format.
`kweb-bridge-codegen` consumes a strict JSON schema and deterministically emits
serializable Kotlin models, a handler interface and dispatcher, a TypeScript
client, and browser-ready JavaScript. Generated TypeScript must compile with the
pinned compiler in strict mode. Unexpected Kotlin exceptions return a fixed
`bridge.handler.failed` message so implementation details do not cross the trust
boundary.

## Consequences

The bridge is explicit per browser and per origin. There is no wildcard origin,
child-frame inheritance, runtime fallback transport, arbitrary renderer
evaluation, or compatibility shim. Navigation outside the allowed origin removes
the API and cancels pending calls; returning to the origin creates a fresh
context. This bridge is host RPC only and must never emulate Manifest V3
`chrome.*` APIs, whose permissions and lifecycle remain Chromium-owned.

The schema intentionally supports only the closed set of strings, 32-bit
integers, finite doubles, booleans, declared records, lists, and nullable fields.
Unsupported shapes and names that collide with Kotlin or generated declarations
fail generation instead of producing ambiguous source.

## Verification

Protocol and generator unit tests cover strict decoding, typed and sanitized
failures, identifier collisions, nullable lists, and byte-for-byte deterministic
output. The generated TypeScript conformance client compiles under TypeScript 7
with `--strict --noEmit`.

The isolated real CEF integration covers structured Unicode round trips, typed
business failures, unknown methods, timeout and `AbortSignal` cancellation,
navigation cancellation, page-close cancellation, same-origin child-frame
exclusion, cross-origin main-page exclusion, and off-CEF-thread handler execution.
A raw FFM/C ABI contract additionally verifies incomplete and malformed bridge
configuration, invalid response JSON, one-shot response ownership, duplicate
responses, and late responses after cancellation. The same root `check` contract
runs on macOS arm64, Linux x64 under Xvfb, and Windows x64 in GitHub Actions.
