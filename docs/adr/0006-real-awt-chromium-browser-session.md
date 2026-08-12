# ADR 0006: Embed a real Alloy browser in a displayable AWT parent

- Status: Accepted
- Date: 2026-08-12

## Context

The Phase 2 session only echoed requests and could not prove that Chromium
loaded, rendered, resized, or persisted a page. Electron-like behavior requires
the actual CEF browser process in the JVM, a native child in the host window,
and callbacks sourced from CEF rather than simulated events.

## Decision

Objective 3.3 replaces the echo ABI with browser operations on the engine ABI.
One browser owns one non-global `CefRequestContext`, one `SessionClient`, and
one platform surface. Profile paths are absolute direct children of the engine
root cache; invalid paths, URLs, dimensions, or AWT peers fail immediately.

The browser is always windowed Alloy with windowless rendering disabled:

- Windows uses the AWT Canvas `HWND` and destroys the native child `HWND`.
- Linux uses the Canvas X11 drawable and requests forced CEF close.
- macOS resolves the AWT JAWT surface layer to its exact AppKit `NSWindow`,
  inserts a dedicated intermediate `NSView`, and removes the CEF view from that
  hierarchy to start native destruction.

CEF callbacks produce ordered created, navigation, address, loading, title,
load, resize, fatal, and closed events. Resize is accepted only after a native
platform query reports the requested dimensions. Close flushes cookies first,
then waits for `OnBeforeClose`; the terminal event removes the browser registry
entry and JNI global callback reference exactly once. Engine close while a
browser is live returns `engine-has-live-browsers` without changing the Kotlin
engine handle or OPEN state, so shutdown can be retried after browser close.

## Consequences

The embedded page is a real Chromium surface with native input, focus, GPU
compositing, Profile storage, and Unicode navigation. The API is intentionally
breaking and internal until DevTools/CDP and Compose ownership are connected.
Every advertised desktop platform must build and run the same ABI, JNI, and
real-browser integration contract; there is no OS WebView, OSR, or hidden-window
fallback.

## Verification

The C header, browser status, exact export set, native CTest suite, Kotlin
contract tests, and isolated JVM integration run in CI on macOS arm64, Windows
x64, and Linux x64 (Linux under Xvfb). The macOS integration additionally
asserts AppKit parentage, Alloy/windowed mode, Unicode titles and URLs, native
resize, cookie/local-storage persistence, live-count zero, and terminal event
ordering.
