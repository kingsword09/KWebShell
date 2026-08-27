# KMP Native Services And Electron Migration

## 1. Decision

KWebShell has three product layers with separate ownership and compatibility
claims:

1. **KWebShell WebView** embeds the pinned CEF/Chromium runtime in a Compose
   native child and owns Engine, Profile, Page, DevTools/CDP, local protocols,
   and the Manifest V3 runtime.
2. **KMP Native Services** expose explicitly installed host capabilities through
   typed common Kotlin contracts and generated TypeScript clients.
3. **Electron Migration Kit** is an opt-in application dependency and a required
   project deliverable. It maps a declared Electron or application-specific
   preload surface onto those services.

The WebView and native-service layers define the runtime product. The migration
kit is a required project deliverable that depends on them; they must never
depend on Electron names, channel conventions, Node.js types, or Electron
lifecycle objects.

This architecture targets Electron-class application capability, not API
identity. A migrated application may preserve much of its renderer and its
preload-facing TypeScript contract, but its Electron main process is replaced by
Kotlin/Compose application code. KWebShell does not execute an Electron binary
or promise that an arbitrary `app.asar` can run unchanged.

## 2. Product Boundaries

### 2.1 KWebShell WebView

The WebView layer remains the only browser layer. It supplies:

- The hardware-accelerated CEF native child embedded in `ComposeWindow`.
- Persistent, isolated Chromium Profiles and Profile-scoped `app://` origins.
- Page navigation, native bounds, focus and input ownership, DevTools, and CDP.
- An exact-origin typed Kotlin/JavaScript transport.
- Chromium-owned Manifest V3 extension services and explicitly hosted extension
  surfaces.

Native services do not become browser-engine methods. Conversely, browser
features such as cookies, request interception, downloads, permissions, and MV3
remain Profile/Page responsibilities and are not duplicated as generic host
services.

The existing public facade proves that a `KWebPage` can own a CEF native child
under a visible `ComposeWindow`; it is not yet the final Compose component. The
product-level layer must also publish a `KWebView` composable that:

- Retains one explicit Page/controller across recomposition and never creates a
  browser because an unrelated state value changed.
- Tracks its axis-aligned position, size, density, window visibility, focus, and
  disposal into the real native child on all three desktop platforms.
- Makes Page ownership explicit, cancels creation races, and cannot deliver a
  callback to a forgotten composition owner.
- States the native-surface composition limits. Arbitrary Compose transforms,
  rounded clipping, and Compose content painted above a platform child are not
  silently approximated.
- Rejects unsupported placement with a typed error. It never substitutes OSR,
  an overlay top-level window, or a system WebView.

The composable is a host for the same `KWebPage`, not a second browser API or
engine backend.

### 2.2 KMP Native Services

KMP means that application code consumes common Kotlin contracts. It does not
require every desktop implementation to be compiled by Kotlin/Native. The
Compose Desktop product may implement a service with Kotlin/JVM, a platform SDK,
or JDK 25 FFM over a small versioned C ABI, while preserving the same common
contract.

Services are installed explicitly. Installation does not expose a service to
page JavaScript. A page receives only an exact, versioned operation set granted
to its configured origin. A missing service, unsupported operation, unavailable
OS facility, permission denial, or version mismatch is a typed terminal result;
the runtime never chooses an alternate service or guessed implementation.

### 2.3 Electron Migration Kit

The migration kit is a source-migration aid. It may provide:

- Generated preload-compatible TypeScript facades.
- Explicit mappings from declared Electron IPC channels to typed native service
  operations.
- Kotlin recipes for replacing Electron main-process ownership.
- A versioned matrix that distinguishes direct mappings, adapters, required
  rewrites, and unsupported APIs.

It must not provide a universal `ipcRenderer`, install `window.electron` by
default, expose arbitrary channel names, emulate Node.js, or report an
unsupported Electron API as successful.

## 3. Dependency Direction

```text
Compose application code
    |                         Migrated renderer
    | direct typed calls             |
    v                                v
KMP Native Service contracts   generated TypeScript client
    ^                                |
    |                                v
desktop service providers      exact-origin KWebBridge
    |                                |
    +---------------+----------------+
                    v
          service registry and policy
                    |
          JDK 25 FFM / platform SDK
                    |
       Win32/COM | Cocoa | XDG/desktop API

Optional Electron Migration Kit
    -> generated TypeScript client
    -> KMP Native Service contracts

KWebShell WebView
    -> CEF/Chromium, Profile, Page, DevTools, CDP, MV3
```

The renderer dispatcher may depend on the existing typed bridge transport. The
registry and service contracts do not depend on that transport or on CEF. The
Electron adapter depends on generated service clients and never participates in
native service dispatch.

## 4. Contract Model

Each service contract has one stable identifier and one semantic contract
version. It declares only fully implemented operations and data types. The
minimum descriptor contains:

- Service ID and contract version.
- Operation IDs and their request/result schema revisions.
- Lifecycle scope: application, Profile, or Page.
- Required KWebShell capabilities and OS facilities.
- Renderer permissions and user-gesture requirements per operation.
- Supported target triples and runtime availability evidence.

Descriptors describe facts; they do not negotiate a weaker implementation. An
application requests an exact compatible contract range and startup fails when
that range is unavailable.

### 4.1 Typed Kotlin API

The common API uses dedicated service interfaces and immutable request/result
types. A generic string-to-JSON map is not a public native API. The exact names
remain unpublished until the first implementation objective, but the intended
shape is:

```kotlin
public interface KWebAppPaths {
    public val descriptor: KWebServiceDescriptor

    public suspend fun resolve(kind: KWebAppPathKind): KWebResolvedPath
}

val appPaths = engine.nativeServices.require(KWebAppPaths.Key)
val downloads = appPaths.resolve(KWebAppPathKind.DOWNLOADS)
```

`require` is typed by the service key. Duplicate installation, an unknown key,
an incompatible version, and use after owner close fail before an operation is
dispatched. Services use `suspend` for operations and `Flow` only for genuine
event streams. Synchronous wrappers are not added merely to resemble Electron.

### 4.2 Generated renderer API

One strict service schema is the source for Kotlin transport models, dispatcher
code, TypeScript declarations, and browser-ready JavaScript. This extends the
existing deterministic bridge generator instead of creating a second RPC
protocol.

The generated client uses a KWebShell namespace by default, for example:

```ts
const downloads = await kweb.native.appPaths.resolve("downloads");
```

Only the operations granted in the page configuration are installed. The
generated transport retains the bridge's existing request ID, timeout,
`AbortSignal`, navigation cancellation, and Page-close cancellation semantics.
It does not use arbitrary script evaluation.

### 4.3 Typed failures

Every service maps failures into the shared `KWebException` model with stable
codes and structured details. The initial closed categories are:

| Category | Meaning |
|---|---|
| `service.not-installed` | The application did not install the requested service. |
| `service.version-incompatible` | The installed contract cannot satisfy the exact requested range. |
| `service.operation-unavailable` | The installed target cannot provide the declared OS facility. |
| `service.permission-denied` | The Profile/Page policy does not grant the operation. |
| `service.user-gesture-required` | A renderer call lacks a verified current gesture. |
| `service.request-invalid` | The typed request violates the service contract. |
| `service.owner-closed` | The application, Profile, Page, or service owner is terminal. |
| `service.cancelled` | The exact operation lost a declared cancellation race. |
| `service.native-failed` | The selected native backend failed and supplied platform evidence. |

An unavailable Linux desktop portal, denied macOS permission, or failed Windows
COM initialization is not permission to invoke another backend. The error must
identify the service, operation, target, native status, and remediation without
leaking private paths or native pointers to an untrusted renderer.

## 5. Ownership And Lifecycle

Service lifetimes follow their declared owner:

```text
Engine create
  -> install and validate application services
  -> Profile open
      -> create Profile-scoped service owners and permission policy
      -> Page open
          -> bind exact-origin grants and Page-scoped owners
          -> dispatch/cancel renderer calls
      -> Page close and join Page calls
  -> Profile close and flush Profile services
-> stop application services in reverse dependency order
-> Engine shutdown
```

No callback may begin after its owner reports terminal completion. Navigation
outside the granted origin removes the renderer client and cancels that Page's
pending calls. Closing an Engine with live service operations follows the same
explicit ownership rule as live pages: it either performs the declared bounded
shutdown or rejects close with a typed error; it never abandons native work.

The initial registry is flat because the first service has no service
dependencies. Providers receive their explicit owner environment and cannot use
the registry as a service locator. Typed dependency ordering is added only when
a complete service demonstrates that requirement.

## 6. Security Model

Native services cross a stronger trust boundary than ordinary web content. The
following rules are mandatory:

- Renderer access requires one canonical HTTP(S) or Profile-scoped `app://`
  origin. Wildcards and child-frame inheritance are prohibited.
- Grants name individual service operations, not an entire native namespace.
- Every request is bound to the current Engine, Profile, Page, main frame, and
  committed origin before dispatch.
- Operations such as dialogs, clipboard reads, external URL launches, capture,
  and shortcuts may require a native-verified user gesture.
- Filesystem access uses explicit path or handle contracts and canonicalization;
  a service cannot turn a renderer string into unrestricted host access.
- Native callbacks are bounded, cancellable, and marshalled away from CEF UI,
  FFM upcall, AppKit main, Win32 window, and Linux desktop event threads as each
  platform contract requires.
- Service schemas, grants, descriptors, and generated output are versioned and
  included in release provenance.

Trusted Kotlin application code may call an installed service directly without
a renderer-origin grant. It remains subject to lifecycle, contract version, OS
availability, and native permission checks.

## 7. Desktop Implementation Boundary

Common Kotlin owns service semantics, models, lifecycle, policy, and errors.
Desktop providers select one declared implementation per supported target:

- Windows uses the required Win32, COM, WinRT, or Windows App SDK contract.
- macOS uses the required Cocoa, Foundation, Core Services, or other documented
  Apple framework contract.
- Linux declares its exact environment, such as XDG Desktop Portal over D-Bus
  or the existing X11/GTK host contract. It does not guess a home-directory
  convention when the declared facility is unavailable.

JDK 25 FFM is the default boundary when a service needs platform-native calls.
FFM layouts and downcalls remain internal Java implementation details because
Java currently supplies the stable JVM FFM API. Kotlin still owns the public API
and calls those internal bindings normally. A native helper is used only when a
direct platform ABI is not stable or lifecycle-safe; it exports a small
versioned C ABI and no C++, Objective-C, Win32, or CEF type.

CEF is not the implementation of general OS services. A browser-owned feature
uses CEF only when Chromium semantics are the product requirement, such as page
permissions, downloads, Profile networking, or extension APIs.

## 8. Module Ownership

The target module boundaries are:

| Module family | Responsibility |
|---|---|
| `kweb-services-core` | KMP service keys, descriptors, registry contracts, scopes, policy, and shared errors. |
| `kweb-service-<name>` | One cohesive KMP service contract and its complete advertised desktop providers. |
| `kweb-bridge-codegen` | Deterministic Kotlin dispatcher and TypeScript client generation from the service schema. |
| `kweb-desktop` | Engine/Profile/Page integration, provider ownership, FFM access, and renderer dispatch. |
| `kweb-electron-migration` | Optional preload facades, channel mappings, and the compatibility matrix. |

These are ownership boundaries, not instructions to create empty Gradle modules.
A module is added only with a complete vertical slice. Shared native plumbing is
extracted only after a second real service demonstrates duplication.

Initial service families may eventually include application paths, windows,
dialogs, filesystem handles, clipboard, notifications, menus/tray, theme,
screen, shortcuts, power, process execution, and updates. This list is a
prioritization backlog, not a compatibility claim. Each family enters the
public matrix only after all advertised operations pass its platform gates.

## 9. Electron Migration Contract

### 9.1 What can migrate with limited renderer change

Electron applications that already use `contextIsolation: true`, disable
`nodeIntegration`, and expose a narrow typed preload API are the best fit. Their
renderer can retain that application-specific API while a generated adapter
maps each allowed method or channel to a KWebShell service.

For example, an application may retain this renderer call:

```ts
await window.desktop.selectFile();
```

Its KWebShell build supplies a generated `desktop.selectFile` facade backed by a
declared dialog service operation. It does not install Electron's complete
`ipcRenderer` object or accept an undeclared channel.

### 9.2 What must be rewritten

The Electron main process and preload implementation are host code. They move to
Kotlin/Compose configuration, KWebShell Profiles/Pages, native service
implementations, and generated adapters. Applications using Node.js directly in
the renderer must first isolate that access behind typed services. Native Node
addons must be replaced with Kotlin/JVM code, direct FFM, or a dedicated native
service provider.

Synchronous IPC is not reproduced. Main-process object identity, JavaScript
prototype behavior, undocumented Electron internals, and unrestricted access to
Node packages are not portable contracts.

### 9.3 Mapping matrix

The migration kit publishes a versioned matrix with four statuses:

| Status | Meaning |
|---|---|
| `DIRECT` | The KWebShell core contract has equivalent owned behavior. |
| `ADAPTER` | An optional tested facade preserves the declared renderer shape. |
| `REWRITE` | A documented Kotlin/service replacement exists but source changes are required. |
| `UNSUPPORTED` | No compatible implementation is published. |

The initial mapping direction is:

| Electron concept | KWebShell direction |
|---|---|
| `BrowserWindow` | Compose `Window`/`ComposeWindow` plus `KWebPage`; Kotlin owns window lifecycle. |
| `webContents` | `KWebPage`, its event flow, and explicitly configured CDP. |
| `session.fromPartition` | Explicit persistent `KWebProfile`. |
| `protocol.handle` | Profile-scoped verified `app://` protocol origins. |
| `ipcMain.handle` + `ipcRenderer.invoke` | Typed service implementation plus generated exact-origin client. |
| `contextBridge.exposeInMainWorld` | Optional generated application-specific preload facade. |
| `dialog`, `clipboard`, `shell`, `nativeTheme` | Separate native service families after conformance publication. |
| `screen`, `globalShortcut`, `Menu`, `Tray`, notifications | Separate UI/system service families after native lifecycle tests. |
| `utilityProcess`, `child_process` | Explicit policy-controlled process service; never implicit Node execution. |
| `autoUpdater` | Future signed update service built on verified KWebShell release metadata. |
| Node `fs`, `path`, `os` | Kotlin host code or narrowly granted file/system services. |
| Native Node addons | Rewrite as a provider behind the service contract. |

Each row is documentation until a release matrix names the exact supported
methods and test evidence. Similar names alone do not qualify as compatibility.

### 9.4 Migration workflow

An application migration proceeds in explicit stages:

1. Inventory Electron imports, main-process ownership, preload globals, IPC
   channels, Node use, native addons, custom protocols, and updater behavior.
2. Classify each item against the versioned matrix. Any `UNSUPPORTED` item is a
   migration blocker until the application changes or a complete service lands.
3. Replace `app` and `BrowserWindow` startup with Kotlin/Compose Engine, Profile,
   Page, and native service installation.
4. Define schemas for the application's renderer-facing operations and generate
   Kotlin dispatch plus TypeScript clients.
5. Preserve an existing preload global only through an explicit adapter whose
   method set is byte-for-byte covered by generated schema tests.
6. Run the application's correctness suite, package verification, and the same
   Chromium-version performance scenario against Electron and KWebShell.

This allows incremental source migration without creating an unbounded runtime
compatibility layer.

### 9.5 Definition of easy migration

"Easy migration" has a measurable meaning in KWebShell. It does not mean that
an arbitrary Electron package runs unchanged. A migration is considered ready
when:

- Renderer calls covered by `DIRECT` or `ADAPTER` rows remain source-compatible
  with the generated preload facade.
- Main-process ownership, Profile/Page creation, permissions, and native service
  installation are explicit Kotlin/Compose code rather than hidden runtime
  behavior.
- One versioned schema is the source for the Kotlin dispatcher, TypeScript
  client, preload facade, and compatibility report.
- The build fails before packaging when an Electron import, Node dependency,
  preload export, or IPC request is unclassified or `UNSUPPORTED`.
- The KWebShell package contains no Electron binary, Node runtime, unrestricted
  IPC endpoint, or compatibility shim that pretends an unsupported operation
  succeeded.
- A pinned renderer digest, migration-manifest digest, service versions,
  Chromium/CEF identity, and target-specific test evidence are retained for
  review.

The first real application proof should use a pinned Electron application such
as the LobeHub desktop product after its startup-path services have complete
conformance. Until then, a report is explicitly migration-blocked; a synthetic
workload or a partial host surface cannot be presented as an application
migration or a performance result.

## 10. Manifest V3 Is Independent

Electron migration and Chrome extension compatibility solve different problems.
Renderer host services use the exact-origin typed bridge and KMP permission
policy. Manifest V3 extensions use Chromium's real extension service, Service
Worker lifecycle, isolated worlds, extension permissions, and `chrome.*` APIs.

The migration kit must never implement `chrome.*`, inject native services into
extension worlds, or use an Electron facade to fill a missing MV3 capability.
An extension API remains unavailable until the MV3 conformance matrix publishes
it.

## 11. Performance And Distribution

KWebShell cannot remove Chromium from an Electron-class compatibility target;
CEF remains the dominant runtime size. Native services and migration adapters
must avoid adding a bundled Node.js runtime, V8 instance, or second browser
engine. Only installed service modules and their required native libraries enter
the application package.

Kotlin-to-service calls remain in process. Renderer calls reuse Chromium/CEF's
existing process message path and the KWeb bridge; they do not create a second
general-purpose IPC daemon. Blocking platform work runs off the CEF UI and
Compose UI threads, and high-rate data uses a separately designed bounded stream
contract rather than repeated JSON RPC.

Size and speed claims require artifacts. Release evidence records the CEF and
Chromium revisions, enabled service modules, native dependency closure, package
size, startup samples, renderer readiness, CPU, memory, frame pacing, and
shutdown. An Electron comparison is publishable only when renderer revision,
origin, host contract, Chromium version, warmup, sample count, and machine class
are comparable.

## 12. Compose WebView Objective

Objective 11.1 publishes the first ergonomic Compose component over the already
verified Engine/Profile/Page facade. It does not change Chromium, add a renderer,
or own native services.

Acceptance criteria:

1. `kweb-compose` publishes one `KWebView` composable and the minimum stable
   state/controller contract. Engine and Profile remain explicit external
   owners; Page ownership and disposal are stated by the API and tested.
2. Recomposition, modifier changes, window movement, resize, density changes,
   minimize/restore, focus traversal, and disposal update one native child
   without duplicate browsers, transient top-level windows, or callbacks after
   the composition owner closes.
3. Position and visible rectangular clip are proven against platform window
   geometry. Unsupported transforms or clips fail with a typed placement error;
   OSR, a system WebView, and a tracked overlay window are never substituted.
4. Keyboard, IME, mouse, touchpad, accessibility, drag/drop, and popup ownership
   follow the declared native surface contract without blocking Compose window
   input outside the component.
5. A sample uses only the public composable and Page API. Real UI integration
   tests exercise two independently placed views, recomposition without Page
   recreation, focus transfer, clipping, and deterministic teardown on macOS,
   Windows, and Linux.

## 13. First Native Service Objective

Objective 11.2 delivers service infrastructure together with exactly one real
service: `KWebAppPaths`. It is selected because Electron applications commonly
depend on `app.getPath`, it exercises common Kotlin, generated JavaScript,
Profile ownership, FFM/native status mapping, and three different desktop OS
contracts without requiring destructive UI automation.

No other service interface or empty module is created by this objective.

Acceptance criteria:

1. `kweb-services-core` publishes the minimum typed key, descriptor, version,
   lifecycle, policy, registry, and error contracts required by this service.
   There is no reflection, global mutable registry, string service lookup, or
   alternate provider selection.
2. `KWebAppPaths` resolves only the published path kinds. macOS uses the exact
   Foundation directory contract, Windows uses Known Folder APIs, and Linux uses
   the declared XDG directory contract. A missing directory definition returns
   a typed error; `$HOME/Desktop`-style guessing is prohibited.
3. Profile/application data paths derive from explicit KWebShell configuration,
   are canonicalized, and cannot escape their owner. The API does not create an
   undeclared directory as a side effect of lookup.
4. One schema generates the common transport models, Kotlin dispatcher, strict
   TypeScript client, and browser JavaScript deterministically. Direct Kotlin
   and exact-origin renderer calls return the same normalized result.
5. A Page receives only the explicitly granted app-path operation. A child
   frame, cross-origin navigation, unconfigured Page, malformed request, closed
   owner, and undeclared path kind each produce the exact typed failure and no
   native call.
6. The project-supplied migration adapter maps only the proven Electron
   `app.getPath` names. Every other Electron application method is absent from
   the adapter and marked `UNSUPPORTED` in the first compatibility matrix. An
   application may omit the adapter module, but the project cannot omit its
   implementation and conformance tests.
7. Common unit tests, generator determinism tests, TypeScript strict compilation,
   C ABI/FFM layout tests, real OS path integration, real CEF bridge isolation,
   repeated lifecycle tests, and runtime packaging verification pass on macOS,
   Windows, and Linux. macOS is the first local implementation gate; hosted
   Windows and Linux run the same contract before cross-platform publication.

## 14. Later Objective Order

After Objective 11.2, priorities come from real migration inventories rather
than an Electron API checklist. Each item remains a separate complete objective:

1. Window state and controls needed by a migrated Compose/Page host.
2. Native open/save dialogs and scoped file handles.
3. Clipboard text/image formats with explicit renderer permissions.
4. External URL/file reveal operations with scheme and path policy.
5. Notifications, theme/screen observation, menus/tray, and shortcuts.
6. Policy-controlled process execution and native messaging.
7. Signed update discovery, installation, and recovery.

The order may change when a pinned application migration provides stronger
evidence. It cannot be changed by adding partial public APIs to several services
at once.

## 15. Verification Rules

Every service objective must include, proportional to its surface:

- Common Kotlin contract, lifecycle, permission, and failure tests.
- Schema validation, deterministic generation, and strict TypeScript compile.
- Native ABI layout/status tests and real platform SDK integration tests.
- Exact-origin, frame isolation, cancellation, and owner-close bridge tests.
- Compose lifecycle and packaging tests using the public API.
- A target-specific capability record for macOS, Windows, and Linux.
- Migration adapter conformance for every matrix row changed by the objective.

Mocks may test pure policy but cannot establish native support. A service is not
published on a target that has only compiled or mocked evidence.

## 16. Explicit Non-Goals

- Full Electron API or binary compatibility.
- Running an Electron main process or arbitrary Node.js package inside KWebShell.
- Exposing CEF, FFM, platform pointers, or generic native invocation publicly.
- Synchronous renderer-to-host IPC.
- A fallback system WebView, service provider, path convention, or permission
  bypass.
- Using native services to emulate Manifest V3 APIs.
- Creating service stubs in anticipation of future migration requests.
