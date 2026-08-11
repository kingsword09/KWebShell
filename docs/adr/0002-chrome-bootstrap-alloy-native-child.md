# ADR 0002: Use the Chrome bootstrap with explicit Alloy native children

- Status: Accepted
- Date: 2026-08-11

## Context

KWebShell must embed a GPU-accelerated Chromium surface inside a Compose-owned native parent on Windows, macOS, and Linux. It must also provide a real Manifest V3 runtime instead of CEF's removed Alloy extension API or a JavaScript emulation.

CEF has two separate concepts that must not be conflated:

- The **bootstrap** owns the browser, renderer, GPU, network, and utility process model. CEF removed the Alloy bootstrap in M128, so current releases use the Chrome bootstrap.
- The per-browser **runtime style** selects Chrome-style or Alloy-style browser/window integration.

CEF 151 explicitly states in `cef_types_mac.h` that providing an external `parent_view` always selects Alloy style. An external parent is required for a true Compose child surface on macOS. CEF's migration documentation also states that the built-in Chrome extension API is supported only by Chrome-style browsers and windows, while the old Alloy extension API was removed.

Consequently, an upstream CEF binary cannot simultaneously provide a Chrome-style browser, a macOS external native parent, and the required extension behavior. Treating that combination as a Phase 1 acceptance criterion would make the plan impossible to complete.

## Decision

The primary embedded page surface uses the following invariant on all desktop platforms:

- Chrome bootstrap process model.
- Explicit `CEF_RUNTIME_STYLE_ALLOY` browser style.
- Windowed native child rendering with a real external parent.
- GPU acceleration enabled and `windowless_rendering_enabled` disabled.
- No per-frame CPU pixel transfer.

Manifest V3 support will come from a version-pinned CEF/Chromium patch series that connects the embedded Alloy `WebContents` to a real Chromium `Profile`, `ExtensionSystem`, `ExtensionService`, Service Worker runtime, permission system, content-script machinery, isolated worlds, and network rule engines. KWebShell will not use the removed CEF Alloy extension API and will not emulate `chrome.*` in Kotlin or JavaScript.

Chrome-style top-level windows remain valid for separately declared browser-owned surfaces such as DevTools or extension management. They are not a fallback rendering backend for the Compose child surface.

The unmodified Spotify CEF runtime may be used to prove the Phase 1 process, GPU, native-child, input, and lifecycle host. No Manifest V3 capability will be published until the patched runtime and its conformance suite are complete.

## Consequences

- Embedded page semantics are identical on Windows, macOS, and Linux instead of silently changing runtime style by platform.
- Phase 1 can validate the native performance path against an official pinned CEF runtime.
- Phase 5 requires a maintained source patch series and reproducible custom CEF runtime build; stock CEF binaries cannot satisfy the MV3 product contract.
- Extension UI surfaces require explicit native hosts and cannot rely on a hidden Chrome toolbar.
- Any attempt to create the primary page with Chrome style, OSR, or a detached overlay window is a configuration error.

## Rejected alternatives

### Chrome-style native child on every platform

Rejected because upstream CEF 151 forces Alloy style when a macOS external parent view is supplied.

### Different browser styles by operating system

Rejected because it would create platform-dependent web and extension behavior behind one API.

### A borderless top-level Chromium window tracked over Compose

Rejected because it is not a child surface and has different focus, clipping, z-order, accessibility, workspace, and lifecycle behavior.

### Off-screen rendering

Rejected as the default because it violates the no-copy native rendering requirement and does not provide the required extension surface behavior.

## Evidence

- [CEF 151 macOS window information](https://github.com/chromiumembedded/cef/blob/be1e15d8892c064f0299ba18350236a9b272ce7f/include/internal/cef_types_mac.h)
- [CEF Chrome bootstrap migration, Issue #3685](https://github.com/chromiumembedded/cef/issues/3685)
- [CEF Alloy extension limitation, Issue #3859](https://github.com/chromiumembedded/cef/issues/3859)
