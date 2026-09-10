# RFC 0010: Profile network request policy, proxy, and observation

- Status: Proposed
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, CEF/Chromium network adapter
- Depends on: RFC 0004, RFC 0009
- Electron migration surface: `session.webRequest`, `setProxy`, `resolveProxy`, `net`, `netLog`
- Target mapping: `REWRITE`

## Objective

Expose typed, Profile-scoped Chromium networking policy without reproducing
Electron's mutable callback bags or Node-style request API.

## Contract

Publish immutable request observation events, declarative request rules,
explicit proxy configuration, proxy resolution, user-agent/language policy, and
network-emulation controls required by tests. Rules declare URL patterns,
resource types, methods, header mutations, redirect/block actions, priority, and
version. Imperative callbacks on Chromium IO threads are prohibited.

## Security and lifecycle

Policies are installed atomically per Profile, validated before activation, and
removed at Profile close. Forbidden headers, credential exfiltration, redirect
loops, localhost/private-network widening, and extension-rule conflicts have
explicit outcomes. Event bodies are absent unless a separate bounded diagnostic
grant exists.

## Acceptance

1. A real HTTPS/proxy fixture proves block, redirect, header add/remove,
   precedence, proxy bypass, PAC/static modes, and atomic replacement.
2. Concurrent Profiles use different policies without cross-observation.
3. Invalid patterns, forbidden headers, redirect loops, unavailable proxy,
   authentication, and shutdown races return typed errors.
4. High-volume event delivery obeys RFC 0004 limits and does not block Chromium
   network threads.
5. Migration inventory maps each `webRequest` phase; unsupported imperative
   mutation patterns remain blockers with a rewrite recipe.
6. `netLog` is owned by RFC 0032; this RFC emits no unredacted diagnostic file.

## Non-goals

No general Kotlin HTTP client, renderer fetch bypass, VPN, packet capture, or
silent system-proxy fallback when an explicit proxy was requested.
