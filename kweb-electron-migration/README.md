# KWebShell Electron Migration Kit

`kweb-electron-migration` is an opt-in source-migration tool for a declared
Electron renderer surface. It does not run an Electron main process, bundle
Node.js, install a universal `ipcRenderer`, or silently emulate unsupported
APIs.

The version-1 fixture maps the common `app.getPath(name)` preload call to the
typed `KWebAppPaths` service. The manifest is the single source of truth for
Electron imports, preload methods, channel schemas, required service versions,
and renderer digest. The generator emits:

- `KWebElectronPreload.ts` for strict TypeScript builds;
- `KWebElectronPreload.d.ts` for renderer declarations; and
- `KWebElectronPreload.js` for the exact-origin browser bridge.

The inventory command scans JavaScript/TypeScript source and `package.json`,
records every Electron/Node use with path and line evidence, and exits with a
structured blocking error for unknown, rewrite, unsupported, dynamic, or
undeclared usage. A compatibility report binds renderer, manifest, generated
output, inventory, and capability-matrix digests to the service versions and
the exact CEF/Chromium target. Blocked reports cannot carry performance data.

## Verification

```shell
npm ci
./gradlew :kweb-electron-migration:check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal
```

The check runs common/JVM contract tests, deterministic generation, strict
TypeScript compilation, Node runtime execution, inventory/report validation,
and the real CEF migration fixture. The fixture owns its `ComposeWindow`,
`KWebDesktopEngine`, `KWebProfile`, `KWebPage`, and explicitly installed
`KWebAppPaths` service in Kotlin; the renderer keeps its
`window.desktop.getPath(...)` call shape. Windows and Linux use the same task
in hosted platform jobs before those targets are published as accepted.

The generated facade only exposes mappings declared by the manifest. To add a
different Electron API, publish a complete service and adapter objective with
its own schema, native implementation, policy, conformance tests, and matrix
row; do not add a compatibility alias or fallback branch. The
[`docs/rfcs` program](../docs/rfcs/README.md) is the dependency-ordered source of
truth for those objectives. A Proposed RFC is absent from generated facades and
does not imply compatibility.
