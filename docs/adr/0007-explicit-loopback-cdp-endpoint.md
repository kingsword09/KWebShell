# ADR 0007: Expose CDP only through an explicit loopback endpoint

- Status: Accepted
- Date: 2026-08-13

## Context

DevTools/CDP is required for inspection and automation, but enabling Chromium
remote debugging implicitly can expose every page and host bridge to the local
network. CEF accepts a fixed `remote_debugging_port`, while its endpoint
address may be represented as IPv4 or IPv6 depending on the platform and
runtime configuration.

## Decision

The internal engine configuration contains one `remote_debugging_port` field.
The only valid values are:

- `0`: remote debugging is disabled and no listener is expected.
- `1024..65535`: remote debugging is enabled on that fixed port.

The native browser process appends `remote-debugging-address=127.0.0.1` and
never chooses an ephemeral port or a public interface. Before `CefInitialize`,
the native engine probes both IPv4 and IPv6 loopback bindability. An occupied
fixed port returns `remote-debugging-port-unavailable`; startup does not retry a
collision with another port. The endpoint is considered valid only when all
HTTP and WebSocket URLs resolve to `127.0.0.1` or `::1`.

The real integration test discovers `/json/version` and `/json/list`, selects
the page by its exact URL, and executes `Runtime.evaluate` over the target's
WebSocket. The endpoint remains internal until a public DevTools/Compose host
owns its complete lifecycle and security policy.

## Consequences

Applications must opt in with a fixed local port and must handle a typed
startup failure when it is occupied. This prevents accidental network exposure
and makes CI and diagnostics reproducible. A later authenticated remote/CDP
transport requires a separate capability and threat-model objective; it cannot
reuse this endpoint by silently widening the bind address.

## Verification

ABI/C++/Kotlin configuration tests reject invalid ports. macOS integration
proves real HTTP discovery, loopback-only WebSocket URLs, Unicode `Runtime.evaluate`,
and endpoint shutdown alongside the native Alloy browser lifecycle. The same
contract is executed by the Windows and Linux CI matrix.
