# RFC NNNN: Capability name

- Status: Proposed
- Priority: P0, P1, or P2
- Owners: module families, not people
- Depends on: implemented RFC identifiers
- Electron migration surface: exact Electron concepts being classified
- Target mapping: intended `DIRECT`, `ADAPTER`, `REWRITE`, or `UNSUPPORTED`

## Objective

State one complete, publishable capability and the user-visible result. Explain
why it belongs in browser core, a KMP native service, or the opt-in migration
adapter.

## Non-goals

List adjacent behavior this RFC does not publish. The list must prevent fallback,
ambient authority, generic IPC, and accidental API expansion.

## Common KMP contract

Define proposed service key, scope, immutable models, `suspend` operations,
ordered flows, resource handles, limits, and stable error additions. Platform
types and Electron names are prohibited here.

## Platform provider contract

Name the exact Windows, macOS, and Linux APIs and thread/lifecycle rules. State
whether the RFC advertises all desktop targets or an explicitly named
platform-specific key.

## Renderer and migration contract

Name renderer operations, required grants/user gestures, preload adapter shape,
Electron fixture version, and the expected migration status. If renderer access
is intentionally absent, say so.

## Security and lifecycle invariants

Describe owner boundaries, canonicalization, permission/revocation, cancellation,
resource ceilings, event ordering, redaction, and shutdown behavior.

## Required deliverables

List the smallest code, schema, provider, test, packaging, documentation, and
matrix changes that form the vertical slice. Do not authorize an empty module or
placeholder API.

## Acceptance

Add capability-specific assertions under:

1. Common contract tests.
2. Real Windows native integration.
3. Real macOS native integration.
4. Real Linux native integration.
5. Exact-origin CEF/renderer integration.
6. Migration fixture and inventory.
7. Packaging/provenance and leak/resource evidence.

The RFC also inherits the universal definition of done in
[the RFC program](README.md#universal-definition-of-done).

## Evidence retained by CI

Name machine-readable reports, screenshots/recordings where UI exists, native
logs, lifecycle counters, package manifests, and digests. Evidence must identify
the target, service/schema version, CEF/Chromium version, and fixture revision.

## Documentation and matrix updates

Name the user guide, migration recipe, compatibility matrix rows, and RFC state
transition performed by the implementation pull request.
