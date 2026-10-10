# KWebShell Electron Migration Kit

`kweb-electron-migration` is an opt-in source-migration tool for a declared
Electron renderer surface. It does not run an Electron main process, bundle
Node.js, install a universal `ipcRenderer`, or silently emulate unsupported
APIs.

The version-2 fixture maps the common `app.getPath(name)` preload call to the
typed `KWebAppPaths` service and pins the Electron major its API surface was
surveyed against (`electronFixtureMajor: 44`, surveyed 2026-09-10). The manifest
is the single source of truth for Electron imports, preload methods, channel
schemas, required service versions, renderer digest, and the pinned Electron
fixture major. The generator emits:

- `KWebElectronPreload.ts` for strict TypeScript builds;
- `KWebElectronPreload.d.ts` for renderer declarations; and
- `KWebElectronPreload.js` for the exact-origin browser bridge; and
- `KWebElectronHostChecklist.md` for explicit Kotlin host ownership.

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

Manifest v2 window declarations map the closed BrowserWindow shape to
caller-created Compose ownership and typed RFC 0007 controls. `parentWindowId`
and `isModal` become immutable registration facts; fullscreen, kiosk, bounds,
and the movable/minimizable/maximizable/closable/resizable/always-on-top flags
become host-side typed mutations. Renderer code never receives a raw window
handle or a force-close path. Dynamic BrowserWindow construction, arbitrary
reparenting, and arbitrary close listeners remain blocking inventory findings.

RFC 0008 maps navigation, reload, before-unload, and renderer lifecycle through
typed `KWebPage` operations and events. Popup handling requires a rewrite:
CEF cancels `window.open` immediately, and an allowed host decision creates a
separate caller-owned page without preserving `window.opener`.

RFC 0009 maps Electron Profile data surfaces individually: cookies, cache
clearing, origin storage clearing and usage, spellcheck preferences, and flush
are direct typed `KWebProfile` operations. Each operation requires a real page
from the same persistent Profile; a generic Electron `Session`, guessed
partition string, origin/time selector for profile-wide HTTP cache, and direct
database access remain rewrite-required or unsupported.

RFC 0010 classifies the network surfaces explicitly: `session.webRequest` is a
rewrite to declarative `KWebProfile.configureNetworkPolicy` rules plus bounded
`networkEvents`. Imperative callback bags and response-body interception remain
outside the published contract. `session.setProxy` and `session.resolveProxy`
are explicitly deferred until a separately reviewed Profile NetworkContext
proxy contract exists; the migration kit does not emulate them with a
process-wide switch, system proxy, or Kotlin resolver.

RFC 0012 classifies `will-download` and `DownloadItem` as a typed rewrite to
`KWebProfile.downloads`. Progress and terminal state remain Profile-scoped,
while pause/resume/cancel are host operations and completed bytes are exposed
only through a bounded file capability. `setSavePath`, renderer paths,
automatic opening, and arbitrary download callbacks remain blocked.

RFC 0014 classifies Electron clipboard workflows as a typed rewrite to
`KWebClipboard` and the generated `ClipboardBridge`. The fixture covers
SYSTEM text/plain round trips through bounded lazy payload handles and named
write/clear operations. HTML, RTF, and URI-list are part of the service
contract; Linux PRIMARY, images, custom native formats, synchronous/direct
renderer clipboard use, and arbitrary polling remain blocked.

RFC 0015 classifies Electron shell workflows as named `ShellBridge` operations:
allowlisted `openExternal`, RFC 0013 handle-based open/reveal, and verified OS
trash/recycle. The real CEF fixture covers native gesture enforcement,
exact-origin isolation, resource-handle ownership, directory-trash policy, and
post-trash source absence. Raw renderer paths, commands, permanent deletion,
and shortcut metadata remain explicit migration blockers.

RFC 0027 classifies Electron `nativeImage` construction and export as a bounded
rewrite to `KWebNativeImage` and the generated `ImageBridge`. PNG/JPEG bytes and
package resource IDs with exact SHA-256 digests are supported by the declared
fixture; URL/path loading, SVG, mutation identity, platform handles, and
unknown image methods remain explicit blockers.

| Electron use | Required rewrite |
|---|---|
| `createFromBuffer`, `createFromDataURL` | Validate PNG/JPEG bytes and call the asynchronous `nativeImage.decode` channel with `NATIVE_IMAGE_OPERATION`. |
| `toPNG` | Call the declared `nativeImage.encodePng` channel with the immutable image metadata. |
| package images | Declare an exact resource ID and SHA-256 digest for `decode`. |
| `toJPEG`, `resize`, `isEmpty`, mutation, native handles, path loaders, unknown methods | Blocked; no object-identity or unbounded Electron emulation is generated. |

The generated facade forwards the named request, response, timeout and abort
signal to `ImageBridge`. Input and output are each limited to 512 KiB of encoded
bytes. Direct Electron calls remain inventory blockers until the application
rewrites them to these declared typed channels.

RFC 0017 classifies Electron `Menu`/`MenuItem` templates as a bounded rewrite to
`KWebMenus` and the generated `MenusBridge`. Immutable typed command trees map to
declared menu operations, and the renderer facade can only pop up a menu the host
already declared:

| Electron use | Required rewrite |
|---|---|
| `Menu.buildFromTemplate`, `Menu.setApplicationMenu` | Build an immutable typed command tree and apply it through the host `KWebMenus` contract with `MENU_OPERATION`. |
| `Menu.getApplicationMenu`, `menu.popup` | Present a declared menu ID through the single renderer channel `menus.showDeclaredPopup`. |
| `MenuItem` role/submenu/type/icon/accelerator | Declared role, submenu, kind, package-resource icon and typed accelerator values. |
| custom `click` closures, unknown roles, unrepresentable accelerator strings, `Menu` object identity/mutation, renderer `remote`/IPC template construction | Blocked; no function serialization or object-identity emulation is generated. |

The generated facade forwards the bounded declared-menu popup request, response,
timeout and abort signal to `MenusBridge`. It never accepts a native template or a
function. Direct Electron menu calls remain inventory blockers until the
application rewrites them to the declared typed channel.

RFC 0018 classifies Electron `Tray` as a host-only rewrite to `KWebTrays`. A
renderer never owns or constructs a tray item, so no renderer adapter or
generated channel is introduced; the mapping is entirely host code:

| Electron use | Required rewrite |
|---|---|
| `new Tray(icon)`, `Tray` object identity/mutation | Host code creates one declared immutable item through `KWebTrays`; renderer construction and object identity remain blocked. |
| `setImage`, `setToolTip`, `setTitle`, `setIgnoreDoubleClickEvents` | Declared icon variant, tooltip, and title with a typed activation policy. |
| `setContextMenu`, `popUpContextMenu` | The versioned RFC 0017 menu tree owned by the item; no renderer template or `Function` is accepted. |
| `on(event)`, balloon methods | The ordered `KWebTrays` event flow; unknown Tray event methods stay explicit blockers. |
| arbitrary image paths | Package-resource image variants only; no path or URL loading. |

Direct Electron tray calls remain inventory blockers until the application
rewrites them to the host `KWebTrays` contract.

## Manifest v2 and inventory prerequisites

Manifest v2 requires an exact `rendererOrigin`, an explicit `rendererProfile`,
and isolated, relative POSIX `storagePath` values for persistent profiles. Window
references and modal ownership must be valid and acyclic. Node dependencies use
`builtin`, `native-addon`, or `package`; unimplemented dependencies and lifecycle
mappings remain explicit blockers. A declared replacement service does not claim
an implemented adapter.

The inventory tool requires Node.js and the separately pinned TypeScript 6.0.2
AST package (`typescript-ast` in `package.json`). TypeScript 7.0.2 remains the
facade build compiler. Run `npm ci`; when invoking the JVM CLI outside the build,
set `-Dkweb.migration.typescript=/absolute/path/to/node_modules/typescript-ast`.
Set `-Dkweb.migration.node=/absolute/path/to/node` if needed. The parser is packaged
in the migration JAR; a missing executable, parser, or wrong version is a typed
`migration.parser.unavailable` error. Node is a tooling prerequisite and is never
an application runtime dependency.

The scanner uses syntax trees and lexical bindings for ESM/CommonJS, relative
re-exports, aliases, calls, and preload object exports. All external packages
without a published migration mapping are blocked; this includes native addons.
Vue/Svelte source and binary addons are explicitly unclassified/blocked. Comments
and string contents are never interpreted as executable calls. Inventory binds
all regular application inputs and nested lockfiles in independent digests;
reports reject an inventory after either changes. Input provenance is not limited
to parsed code: configuration, package metadata, the manifest, extensionless
resources and binary assets are included. Only the directory subtrees named
`node_modules`, `.git`, and `.gradle` are excluded at any depth. They are never
traversed. Included symbolic links (including directory and dangling links),
special files, and control-character paths fail with
`migration.inventory.blocked`; read failures cannot produce a partial READY
inventory. Root symlinks are also rejected. Keep generated files and reports
outside the application tree and hold inputs unchanged during collection.

Breaking change (2026-10-08): existing schema-v2 inventories/reports must be
regenerated because `sourceSha256` now covers all non-lockfile inputs, rather
than only the parser's source extensions. No old-digest compatibility mode is
provided. Hashing a resource is not AST classification or a runtime capability
claim. Source paths use sorted relative POSIX names; bytes, including line
endings, are hashed without normalization. CEF/runtime and RFC digests bind
declared metadata; strict governance and real runtime evidence remain separate
packaging requirements.

The standalone command is:

```text
migrate <v1-manifest.json> <v2-manifest.json> <exact-renderer-origin>
```

It accepts only a strict v1 manifest whose channels are also v1 and rejects
unknown revisions before writing output. It emits canonical compact v2 JSON
with one trailing LF, creates missing output directories, and leaves the input
unchanged. The output declares the exact supplied renderer origin, migrated
primary window, and persistent `profiles/default` storage; review these
declarations before building the host. Repeating a migration with the same
input bytes and origin produces identical output bytes.

`migrate` rejects identical normalized input/output paths with
`migration.manifest.input-output-conflict`. Invalid argument count returns exit
2 with `migration.command.invalid-arguments`; migration failures return exit 2
with a JSON `{code, message, details}` object on stderr. Argument, source,
revision, and origin validation happens before output mutation, so those
failures leave an existing destination unchanged. A filesystem write failure
can leave a partial destination; crash-atomic output is not promised. Success
returns exit 0 with the confirmation line on stdout and empty stderr. Runtime
manifest decoding accepts v2 only; use the standalone `migrate` command for v1
instead of expecting `manifest`, `generate`, `inventory`, or `report` to upgrade
it implicitly.

## Report provenance and aggregation

The `report` command requires these JVM properties, supplied by Gradle tasks:

- `kweb.migration.runtime.manifest`: pinned CEF runtime descriptor and checksums;
- `kweb.migration.rfc.evidence`: the RFC evidence manifest;
- `kweb.migration.rfc.catalog`: the RFC catalog directory;
- `kweb.migration.target`: the exact desktop target.

Compatibility report schema v2 binds source, lockfiles, manifest, generated
output, inventory, matrix, RFC catalog/evidence, Electron major, service versions,
and the complete runtime descriptor plus the selected CEF artifact. It retains
the exact renderer origin/profile and each channel/stream policy. Report READY
classifies the declared migration; strict RFC governance is additionally required
before packaging a release, including current hosted evidence.

`merge-reports <output.json> <report1.json> <report2.json> ...` writes a separate
schema-v1 aggregate envelope containing the complete sorted entry reports and a
SHA-256 of that array. It rejects duplicate entries and conflicting application,
platform, runtime, service, matrix, RFC or Electron identities. Each entry's
origin and permissions remain distinct. Any blocked input produces a retained
BLOCKED aggregate and exit code 2; conflicts produce `migration.report.conflict`.

The host checklist is a review artifact, not an automatic codemod. Generated
TypeScript, declarations, JavaScript and checklist are verified against shared
golden files on every hosted platform. No application source is edited in place.
