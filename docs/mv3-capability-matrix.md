# Manifest V3 capability matrix

- Matrix version: 0.3
- Runtime baseline: CEF `151.3.16+gbe1e15d`, Chromium `151.0.7922.109`
- Architecture: Chrome bootstrap with an Alloy native child
- Published conformance objectives: 5.2, 6.1, 6.2

This matrix is a product contract, not a list of manifest fields that happen to
parse. `RUNTIME_VERIFIED` means the pinned Chromium runtime performed the exact
operation in `kweb_mv3_core_conformance_test` on macOS, Linux, and Windows.
`PACKAGE_VERIFIED` means Objective 5.1 admits and authenticates the package but
makes no runtime claim. `UNPUBLISHED` means KWebShell exposes no public contract
for the capability in this matrix version.

## Published baseline

| Capability | Status | Conformance evidence |
| --- | --- | --- |
| Strict Manifest V3 parsing and resource validation | `PACKAGE_VERIFIED` | The shared fixture passes `JvmKWebExtensionPackageVerifier.verifyUnpacked`. |
| Public-key extension ID derivation | `PACKAGE_VERIFIED` | The fixture derives `dhhnhmffjehhodphofnkingncijnaona` from its checked-in SPKI public key. |
| Static `document_start` content script | `RUNTIME_VERIFIED` | Chromium injects `content.js` into the controlled HTTPS origin. |
| Content-script isolated world | `RUNTIME_VERIFIED` | The content script reads the shared DOM but cannot observe a page-world JavaScript marker; the page world has no `chrome.runtime`. |
| `chrome.runtime.id` | `RUNTIME_VERIFIED` | Both Service Worker responses contain the fixed extension ID. |
| `chrome.runtime.getManifest()` | `RUNTIME_VERIFIED` | Both responses contain the exact manifest name. |
| `chrome.runtime.sendMessage()` from a content script | `RUNTIME_VERIFIED` | Chromium dispatches two request/response messages to the MV3 Service Worker. |
| MV3 Service Worker wake and event handling | `RUNTIME_VERIFIED` | The worker starts on the first message and returns an asynchronous response. |
| MV3 Service Worker idle suspension and wake-up | `RUNTIME_VERIFIED` | After 40 seconds idle, the next message is handled by a different worker instance ID. |
| `chrome.storage.local.get()` and `.set()` | `RUNTIME_VERIFIED` | Message counts advance exactly once per worker response. |
| Extension state across complete CEF restart | `RUNTIME_VERIFIED` | Profile `alpha` advances from counts `1/2` to `3/4` after shutdown and restart. |
| Extension state isolation between Profiles | `RUNTIME_VERIFIED` | Profile `beta` starts independently at counts `1/2`. |
| Chromium extension and Service Worker disk state | `RUNTIME_VERIFIED` | Non-empty Profile databases are required after every run. |
| Direct `options_ui` page navigation in the existing Alloy native child | `RUNTIME_VERIFIED` | After the core Service Worker sequence completes, the same native child loads the exact `chrome-extension://dhhnhmffjehhodphofnkingncijnaona/options.html` URL. The page proves its origin, `chrome.runtime.id`, manifest identity, and the persisted `storage.local` count on macOS arm64, Linux x64, and Windows x64. |
| Direct `action.default_popup` page navigation and global `chrome.action` state in the existing Alloy native child | `RUNTIME_VERIFIED` | After the core Service Worker sequence completes, the same native child loads the exact `chrome-extension://dhhnhmffjehhodphofnkingncijnaona/popup.html` URL and reads the exact persisted global badge and title state on macOS arm64, Linux x64, and Windows x64. |

`RUNTIME_VERIFIED` applies only to the exact methods and event path above. It is
not a blanket claim for every member of `chrome.runtime`, `chrome.storage`, or
the content-script platform.

## Unpublished surface

The following capabilities have no public KWebShell runtime contract in matrix
version 0.3:

| Area | Capabilities held back from the public API |
| --- | --- |
| Package lifecycle | Profile installation, CRX3 installation, atomic update, reload, disable/enable, uninstall |
| Worker events | Installation/update events, alarms, notifications, browser lifecycle events |
| Script APIs | `chrome.scripting`, dynamic content scripts, user scripts |
| Browser model | `chrome.tabs`, `chrome.windows`, tab groups, sessions |
| Extension UI | action icon host, user-gesture popup behavior, toolbar placement, tab-scoped action state, Chrome-driven `openOptionsPage()`, product lifecycle-based options hosting, context menus, commands |
| Network | `declarativeNetRequest` rule evaluation and feedback |
| Extension surfaces | DevTools pages, offscreen documents, side panels |
| Elevated integration | native messaging, debugger, management, proxy, private/incognito access |

Some names in this table are package-admissible under Objective 5.1 so their
manifests can be reviewed deterministically. Package admission never upgrades a
name to runtime support. Each area moves out of `UNPUBLISHED` only with a real
Chromium conformance fixture, typed failure behavior, and green tests on all
three desktop targets.

## Options page boundary

Objective 6.1 proves only one exact navigation path: the native conformance
host loads an installed test extension's declared `options_ui.page` into the
same existing Alloy native child after the worker has persisted state. The host
accepts no caller-provided extension URL and reports a typed error for an
unexpected URL, failed load, mismatched page result, duplicate terminal event,
or timeout.

This is not a claim that KWebShell has a public `openOptionsPage()` API,
Chrome's tab-management behavior, an action popup, or an install-and-open
product flow. Those surfaces remain `UNPUBLISHED` until the profile-scoped
custom runtime artifact gate and their own lifecycle conformance objectives
pass on macOS, Windows, and Linux.

The direct-navigation fixture passed the same pinned stock-CEF conformance test
on macOS arm64, Linux x64, and Windows x64. This narrowly scoped evidence does
not publish a product options-page API or expand the unrelated extension UI
capabilities listed above.

## Action popup boundary

Objective 6.2 proves only one exact action path: after its Service Worker has
persisted the second core message count, it writes and reads the global
`chrome.action` badge and title, then the native conformance host loads the
installed test extension's declared `action.default_popup` into the same
existing Alloy native child. The host accepts no caller-provided extension URL
and fails with a typed error for an unexpected surface, URL, page result,
duplicate terminal event, failed load, or timeout.

This is not a claim that KWebShell renders an action icon, places it in an OS
toolbar, opens a popup from a user gesture, supports tab-scoped action state,
or exposes a public action API. Those surfaces remain `UNPUBLISHED` until the
profile-scoped custom runtime artifact gate and their own lifecycle objectives
pass on macOS, Windows, and Linux.

The direct popup fixture passed the same pinned stock-CEF conformance test on
macOS arm64, Linux x64, and Windows x64. This narrowly scoped evidence does
not publish a product action host or expand the unrelated extension UI
capabilities listed above.

## Context-menu boundary

Objective 6.3 proves one internal, fixed page-context command path. In the
strict test mode, the Service Worker registers exactly one `contextMenus` item;
the existing windowed Alloy native child receives a real right-button event;
Chromium builds the extension-aware `RenderViewContextMenu`; and CEF observes
the real command ID before returning `false` for Chromium's default dispatch.
The worker then verifies the item and page URL, persists the click, and the
content script reports the exact result before the Profile flushes and CEF
shuts down.

Stock CEF deliberately remains a negative gate for this path: it must complete
the same renderer/core/right-click setup and fail with exactly zero matching
extension items, without host-injected items, synthetic DOM input, JavaScript
listener invocation, or a fabricated selection. The source patch is enabled
only by the private `kweb-chrome-context-menu` switch in this test mode; all
other Alloy browsers retain the upstream CEF model.

The macOS arm64 checksum-pinned custom source build has passed the positive
native-child sequence. Windows x64 and Linux x64 positive custom-runtime runs
are gated by the self-hosted source-build workflow and must pass before this
capability can move into the published table. Until all three custom runtimes
pass, context menus remain `UNPUBLISHED` and KWebShell exposes no public menu,
command, toolbar, or product-surface API.

The current conformance scope intentionally excludes multiple or nested items,
checked/radio state, editable/link/media contexts, dynamic updates, OS-native
menu rendering, and arbitrary host item composition. Those behaviors require
separate objectives and cannot be inferred from this fixed-item result.

## DevTools extension boundary

Objective 6.4 proves one exact DevTools extension lifecycle, but does not add a
public DevTools-extension API. After the core MV3 Service Worker sequence, the
native conformance host opens the real CEF Chrome-style DevTools window for the
same Alloy page and Profile. Chromium's own `DevToolsUIBindings` discovers the
installed `devtools_page` through the Profile `ExtensionRegistry`, creates its
hidden extension frame, and exposes the real `chrome.devtools` bindings. The
fixture then verifies all of the following from the extension context:

- the fixed extension origin, ID, and `devtools_page` path;
- `chrome.devtools.inspectedWindow.eval` returns the marker from the inspected
  Alloy page; and
- `chrome.devtools.panels.create` invokes a non-null panel callback with the
  exact requested panel metadata, after which one atomic `storage.local`
  record is published to the inspected page's content script.

The host validates the DevTools popup's Chrome runtime style, native window,
windowed rendering, and Profile identity, records the real `devtools://`
frontend load, and requires DevTools close before Profile flush and inspected
browser shutdown. Direct navigation to `chrome-extension://.../devtools.html`,
normal extension-page substitution, CDP/JavaScript listener invocation, and
emulated `chrome.devtools` objects are forbidden.

The exact sequence passed the full native suite on macOS arm64 with both the
pinned stock CEF runtime and the checksum-pinned custom runtime. GitHub Actions
run `31955852372` also passed the same positive stock-CEF sequence on hosted
macOS arm64, Windows x64, and Linux x64. This fixed conformance remains
`UNPUBLISHED` as a public DevTools-extension API; panel visibility/selection,
sidebars, recorder integrations, and broader DevTools-extension APIs require
separate objectives.

## Offscreen document boundary

Objective 6.5 proves one exact MV3 offscreen-document lifecycle, but does not
add a public offscreen API. The installed fixture's Service Worker uses the
real `chrome.offscreen.hasDocument`, `createDocument`, and `closeDocument`
methods with the fixed `DOM_PARSER` reason and justification. Chromium creates
the declared `offscreen.html` document in the same Profile; the host never
navigates the Alloy child to the extension URL and never creates a hidden CEF
browser as a substitute.

The worker verifies the manifest identity and exact permission set before
creation. The offscreen document verifies its own `chrome-extension://`
origin, path, runtime ID, `runtime.getURL()` result, and a real `DOMParser`
result, then sends one ready message. Chromium intentionally does not expose
`runtime.getManifest()` to an `offscreen_extension` context. The worker accepts
the message only from the exact extension sender, requires `hasDocument`
transitions `false -> true -> false`, persists one atomic `storage.local`
record, and validates that record on the next Service-Worker wake without
recreating the document. Wrong senders, duplicate messages, invalid
transitions, API errors, and timeouts are typed failures.

This fixed conformance does not claim audio or media playback, user/media
capture, workers, clipboard, WebRTC, arbitrary reasons or URLs, concurrent
documents, or a public host-side offscreen API. Those surfaces remain
`UNPUBLISHED` until their own lifecycle objectives pass on macOS, Windows, and
Linux.

## Internal package-store boundary

Objective 5.3 adds no published runtime capability. It verifies the internal
filesystem boundary that Objective 5.4
will connect to the pinned Chromium Profile `ExtensionService`:

- unpacked input is copied without following links and re-verified after the
  snapshot is complete;
- the exact signature-verified CRX3 ZIP payload is extracted and receives its
  verified public key in the managed manifest;
- immutable objects use a portable SHA-256 tree digest under one explicit
  Profile store;
- install, update, reload, and uninstall intent is journaled under a real
  cross-process lock, while active state changes only after runtime success;
- crash recovery retains ambiguous journals, and garbage collection refuses to
  run while any transaction is pending.

These properties are exercised by `JvmKWebExtensionProfileStoreTest` on every
desktop Actions runner. They prove deterministic package provisioning and
transaction recovery only. They do not prove that Chromium loaded, updated,
reloaded, or uninstalled an extension, so package lifecycle remains
`UNPUBLISHED`.

## Internal lifecycle-adapter boundary

Objective 5.4 connects the store journal to Chromium's real Profile-scoped
extension service through a version-pinned custom CEF C ABI. The production
coordinator supports `INSTALL`, `UPDATE`, `RELOAD`, `QUERY`, and `UNINSTALL`,
including explicit ambiguous outcomes, cancellation, duplicate-operation
rejection, and startup reconciliation. Stock CEF fails before dispatch with
`native.abi.extension-runtime-abi-missing`; it is never substituted for the
custom runtime.

The macOS arm64 custom runtime has passed the complete lifecycle fixture. The
test observed a new Service Worker after update and reload, retained
`storage.local`, isolated two Profiles, recovered a journal after the parent
forcibly terminated a child process, rejected a duplicate live mutation,
reconciled five consecutive cancelled reloads, and proved uninstall across restart. This local
evidence does not publish the feature. `customRuntimeArtifacts` remains empty,
and package lifecycle stays `UNPUBLISHED` until checksum-pinned Windows x64 and
Linux x64 artifacts pass the same source-build and hosted acceptance workflows.

## Test-only bootstrap boundary

Objective 5.2 uses `--load-extension` and `--disable-extensions-except` only
inside the native conformance host. The host accepts an absolute fixture path,
canonicalizes and validates it, removes inherited extension switches, and loads
only that fixture with background networking, component updates, and proxy use
disabled. This proves Chromium capability without external network dependence
but deliberately does not define product installation semantics.

The production FFM-backed engine sets `command_line_args_disabled` and does not expose
these self-test arguments. KWebShell does not fall back to command-line loading,
a system WebView, an emulated `chrome.*` object, or another Profile when a
published extension capability is unavailable.

## Platform gate

The published baselines for Objectives 5.2, 6.1, and 6.2 require
`kweb_mv3_core_conformance_test` on all three targets, including the direct
options-page, action-popup, and global action-state sequences. Objective 5.4
publication
additionally requires `extensionLifecycleIntegrationTest` against the matching
custom runtime:

| Target | Custom lifecycle evidence | Publication state |
| --- | --- | --- |
| macOS arm64 | Local source build and complete lifecycle pass | `UNPUBLISHED` pending three-target artifact set |
| Linux x64 | Self-hosted source build, then hosted Xvfb acceptance required | `UNPUBLISHED` |
| Windows x64 | Self-hosted source build, then hosted Win32 acceptance required | `UNPUBLISHED` |

A CEF/Chromium upgrade invalidates runtime evidence until this suite passes on
all three targets again. The matrix version must change when any published
capability or its semantics change.

## Upstream basis

- [CEF issue 3859: extension support with Alloy-style browsers](https://github.com/chromiumembedded/cef/issues/3859)
- [CEF commit `be1e15d8892c064f0299ba18350236a9b272ce7f`](https://github.com/chromiumembedded/cef/commit/be1e15d8892c064f0299ba18350236a9b272ce7f)
- [Chrome Manifest V3 documentation](https://developer.chrome.com/docs/extensions/develop/migrate/what-is-mv3)
