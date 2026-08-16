# KWebShell Engineering Rules

## Project Objective

KWebShell is a Kotlin Multiplatform browser shell for Compose and Chromium. It is intended to provide a native, hardware-accelerated desktop browser surface with DevTools/CDP, persistent profiles, typed host bridges, custom protocols, and a real Manifest V3 extension runtime on Windows, macOS, and Linux.

The browser engine is a product boundary, not an implementation detail. The desktop Chromium backend, native window host, Kotlin API, Compose integration, and extension runtime must be designed and tested as separate layers with explicit contracts.

## Non-Negotiable Delivery Rules

### Breaking changes are allowed and expected

- This project is pre-1.0 and must prefer a correct, coherent API over compatibility with an earlier draft.
- Do not add compatibility shims, deprecated aliases, silent migration code, or dual behavior merely to avoid a breaking change.
- When a contract is wrong, change it decisively, update all consumers, tests, and documentation in the same change, and record the break in the change log or commit message.

### No fallback behavior

- Do not silently switch from Chromium to a system WebView, OSR to another renderer, a different profile, a different permission policy, or a reduced extension runtime.
- Do not hide unsupported platform or capability behavior behind a fallback backend.
- A required capability must either work through its declared implementation or fail immediately with a typed, actionable error that identifies the missing capability and platform.
- Failing fast is acceptable; silently degrading behavior is not.

### No placeholder implementations

- Do not add `TODO`, `FIXME`, `NotImplementedException`, `UnsupportedOperationException`, empty callbacks, fake success responses, hard-coded demo data, or comments that defer required behavior.
- Do not merge a public API until its complete behavior exists on every platform that the API advertises.
- A feature may be absent from the public API until it is fully implemented. It must not be exposed as a stub.
- Every error path must be deliberate, observable, and covered by a test.

### Complete implementation and verification

- Implement objectives one at a time. Define the acceptance criteria before editing code.
- Each objective must include unit tests, native integration tests, and cross-platform tests proportional to its risk.
- Chromium, CEF, JNI/C ABI, windowing, rendering, profile, DevTools, and Manifest V3 behavior must be tested with real runtime artifacts, not mocks alone.
- A change is complete only after its tests pass, packaging is verified, and the relevant documentation is updated.
- Never claim support for an API or Manifest V3 capability without a conformance test demonstrating it.

### Commit discipline

- Make one focused implementation objective per commit.
- Keep each pull request scoped to one focused implementation objective.
- Commit only after the objective's tests and validation pass.
- Do not combine unrelated refactors, generated metadata, dependency upgrades, or formatting churn with an objective.
- Commit messages must state the completed objective and verification, for example: `cef: load MV3 extension service workers`.
- If a change is intentionally breaking, state that in the commit body and list the migration required for the next objective.
- Keep the worktree clean between objectives. Do not move on to the next objective with failing or skipped tests.
- Documentation-only pull requests must not run the Windows, macOS, and Linux CEF matrix. Use a lightweight documentation check when one exists; otherwise `git diff --check` is sufficient before review and merge.
- Pull-request titles, descriptions, and comments must use rendered Markdown with real line breaks. Never publish literal escape sequences such as `\n`; inspect the rendered pull-request body immediately after creating or editing it.
- Integrate pull requests with squash merge only. Do not create merge commits or use GitHub's merge-commit strategy.
- Delete the merged topic branch and verify the squash result on `main` before starting the next objective.

## Architecture Rules

- `kweb-core`: platform-neutral lifecycle, navigation, profile, capability, and error contracts.
- `kweb-compose`: Compose UI and native-surface composition only; it must not own Chromium internals.
- `kweb-desktop`: desktop session, window, page, DevTools, CDP, and profile orchestration.
- `kweb-bridge`: typed Kotlin/JavaScript RPC and generated bindings. Keep the transport independent of CEF.
- `kweb-cef-native`: C++ CEF/Chromium host, C ABI/JNI boundary, native window integration, and extension adapter.
- `kweb-extensions`: Manifest V3 package validation, permission policy, lifecycle model, and capability reporting.
- Keep CEF C++ types behind an opaque C ABI or JNI boundary. Do not leak `CefRefPtr`, CEF callbacks, or Chromium classes into common Kotlin code.
- Reuse Chromium's extension service, Service Worker lifecycle, permission enforcement, content-script injection, and network rule engines. Do not reimplement them in Kotlin or JavaScript.
- Profiles are explicit and persistent when extensions are enabled. Never share extension state implicitly between profiles.
- Native child rendering is the required high-performance path. Off-screen rendering is a separately declared capability and must not be substituted silently.
- The primary embedded page uses the Chrome bootstrap with explicit Alloy runtime style on every desktop platform. Chrome-style top-level surfaces are separate contracts, not a substitute for the Compose native child.
- Manifest V3 support for the embedded Alloy `WebContents` requires the pinned Chromium extension-service patch series. Never use the removed CEF Alloy extension API or emulate `chrome.*` in Kotlin/JavaScript.
- DevTools and CDP are first-class contracts. Remote debugging must be explicitly configured and secured, not enabled accidentally.

## Manifest V3 Requirements

The extension subsystem must define and test, at minimum:

- Manifest V3 validation and version/ID derivation.
- Unpacked directory and CRX3 installation, signature verification, atomic updates, uninstall, reload, and persistence per profile.
- Service Worker startup, event dispatch, idle suspension, wake-up, messaging, alarms, and shutdown behavior.
- `chrome.runtime`, `chrome.storage.local`, `chrome.scripting`, content scripts, isolated worlds, `chrome.tabs`, `chrome.windows`, `chrome.action`, action popups, options pages, context menus, and `declarativeNetRequest` according to the published capability matrix.
- Permission and host-permission approval, optional permissions, private/incognito policy, native messaging policy, and extension-origin isolation.
- Extension action, popup, options, DevTools, offscreen, and side-panel host surfaces where advertised.
- Explicit errors for every API that is not implemented. Do not report a capability as supported when only its manifest key parses.

Do not promise compatibility with every Chrome Web Store extension. Publish a versioned capability matrix and run a conformance suite against representative real MV3 extensions before widening the supported set.

## Build and Platform Rules

- Kotlin Toolchain/Amper may orchestrate Kotlin Multiplatform modules, Kotlin/Native cinterop, resource packaging, and native artifact provisioning. It does not replace CEF's C++/Chromium build, GN/Ninja, CMake, or platform SDKs.
- Use JVM + JNI for the first Compose Desktop integration. Add Kotlin/Native bindings only after the C ABI and lifecycle contracts are stable.
- Kotlin/Native interop must consume a small, versioned C ABI. C++ must be compiled by the platform-compatible C++ toolchain and loaded as a native library or host process.
- Test Windows, macOS, and Linux separately. A successful build on one host does not imply support on another.
- CEF/Chromium version, binary provenance, runtime resources, locales, codecs, sandbox settings, and license notices must be pinned and reproducible.

## Quality Principles

- **KISS:** prefer a small number of explicit layers and typed errors over magic configuration.
- **YAGNI:** do not expose an extension API, backend, or UI surface before its complete implementation and tests exist.
- **SOLID:** keep profile, browser, renderer, bridge, extension, and window-host responsibilities isolated behind stable interfaces.
- **DRY:** centralize capability metadata, error mapping, package validation, and cross-platform test fixtures.

## Required Workflow

1. Write the objective and acceptance criteria in the design plan.
2. Add or update tests that express the criteria.
3. Implement the smallest complete vertical slice, including native and Kotlin sides.
4. Run the required tests on every advertised target, or record a blocking platform defect instead of pretending support.
5. Update documentation and the capability matrix.
6. Commit the objective as one focused commit and open a single-objective pull request.
7. Squash-merge the green pull request, delete its topic branch, and verify the resulting `main` commit.
8. Repeat for the next objective.
