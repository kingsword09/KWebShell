# ADR 0013: Composable exact bridge dispatch

- Status: Accepted
- Date: 2026-09-05

## Context

The exact-origin bridge is configured once for a browser, while native
services expose independent generated dispatchers. A host that installs more
than one complete service therefore needs a composition boundary. A handwritten
`when` over method names duplicates generated schemas, becomes stale when a
service changes, and makes ownership conflicts difficult to detect during
startup.

## Decision

`kweb-bridge` publishes `KWebBridgeRoute` and
`KWebBridgeDispatchers.exact`. Each route owns a non-empty set of valid method
identifiers and one `KWebBridgeDispatcher`. The factory copies route method
sets, rejects duplicate ownership, and builds an immutable exact method table.
At dispatch time it decodes the existing version-1 request envelope, selects
the declared owner by exact, case-sensitive method name, and forwards the
original JSON unchanged. An undeclared method produces the existing typed
`bridge.method.unknown` failure. No wildcard, prefix, fallback, or alternate
transport is introduced, and cancellation remains the selected dispatcher's
responsibility.

The route table is transport-only. Service permission checks, lifecycle, origin
activation, and generated request validation stay in the selected dispatcher
and in the existing CEF bridge boundary.

## Consequences

Hosts can combine complete service bridges without hand-maintained routing
logic. Invalid composition fails before a browser is created, and a route's
method declaration cannot be changed through its input set after construction.
The API deliberately requires explicit method declarations, so adding a
generated service operation requires updating the host's route definition and
its integration evidence.

## Verification

Common tests cover exact delegation, defensive copying, case sensitivity,
invalid and duplicate definitions, unknown-method isolation, and cancellation
propagation. The real desktop CEF integration composes the conformance and
`KWebAppPaths` dispatchers and continues to exercise origin, navigation,
timeout, cancellation, and permission behavior.
