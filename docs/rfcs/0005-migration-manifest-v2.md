# RFC 0005: Electron migration manifest v2, inventory, and codemod plan

- Status: Proposed
- Priority: P0
- Owners: `kweb-electron-migration`
- Depends on: RFC 0001, RFC 0004
- Electron migration surface: imports, preload globals, IPC, Node built-ins, native addons
- Target mapping: inventory infrastructure

## Objective

Scale the migration kit from one preload method to complete applications.
Version 2 records process ownership, lifecycle events, windows, Profiles,
preload methods/events/streams, IPC direction, user gestures, services,
packaging assumptions, Node/native dependencies, and exact RFC/matrix evidence.

## Manifest and tooling

The schema MUST be closed and versioned. Inventory parses TypeScript/JavaScript
ASTs and package metadata; regex-only classification is insufficient. Dynamic
channel names, computed Electron imports, `eval`, runtime `require`, unknown
preload exports, and native addons become explicit blockers.

Codemods MAY rewrite only deterministic host ownership and generated client
imports. Each edit emits a reviewable patch and never changes application source
in place without an output directory. Renderer preload shape is preserved only
for schema-declared methods/events.

## Acceptance

1. Fixtures cover ESM/CommonJS, aliases, re-exports, monorepos, preload
   declarations, bidirectional channels, dynamic use, Node built-ins, and native
   addons with exact file/line evidence.
2. v1 manifests migrate deterministically through a standalone command; runtime
   dual parsing is prohibited.
3. Generated Kotlin host checklist and TypeScript facade are byte-for-byte
   reproducible across Windows, macOS, and Linux paths.
4. A compatibility report binds source, lockfile, manifest, generated output,
   RFC evidence, services, Electron major, and CEF runtime digests.
5. Any unclassified or non-implemented RFC row exits nonzero before packaging.
6. The CLI can merge reports from multiple renderer entry points without
   weakening individual origin or permission policies.

## Non-goals

No Electron main-process runtime, universal preload polyfill, automatic semantic
rewrite of arbitrary JavaScript, or claim that compilation equals migration.
