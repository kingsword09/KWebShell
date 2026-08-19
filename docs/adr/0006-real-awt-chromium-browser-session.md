# ADR 0006: Embed a real Alloy browser in a Compose native parent

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
root cache; invalid paths, URLs, dimensions, or Compose native parents fail
immediately.

The browser is always windowed Alloy with windowless rendering disabled:

- Windows consumes the public `ComposeWindow.windowHandle` as the top-level
  `HWND`, parents Chromium directly beneath it, and initiates forced close
  through `CefBrowserHost`. In `DoClose`, KWebShell returns `true` to accept its
  custom destruction path, clears pointer capture and hover tracking, disables
  and hides the child, synchronously confirms focus has left its
  `Chrome_WidgetWin` subtree, drains eight CEF UI queue turns, and then destroys
  the Chromium child. Returning `false` is forbidden because
  CEF 151 sends the standard close notification to the top-level Compose ancestor.
- Linux consumes the same property as the top-level X11 `Window` and requests
  forced CEF close for the child browser.
- macOS consumes the property as the exact AppKit `NSWindow`, inserts a
  dedicated intermediate `NSView`, and removes the CEF view from that hierarchy
  to start native destruction.

CEF callbacks produce ordered created, navigation, address, loading, title,
load, resize, fatal, and closed events. Resize is accepted only after a native
platform query reports the requested dimensions. Close flushes cookies first,
then waits for `OnBeforeClose`. Surface ownership is retained until three CEF
UI quiescence tasks have run after CEF releases its final `SessionClient`
owner, which follows browser-host and platform-delegate teardown. Registry
removal and the terminal callback are published after that barrier. A command
re-entered from that callback therefore receives `invalid-handle`, never `browser-closing`.
On Windows, `DoClose` returns `true`, clears pointer state, blocks new child input,
confirms focus loss, and drains eight CEF UI queue turns before destroying the child. Only after
CEF reaches `OnBeforeClose` and releases the final client owner may the now-empty
surface be released. The real Windows stress test checks after every close that
the same Compose `HWND` remains visible and accepts an OS-generated mouse click,
and rejects Aura destroyed-window diagnostics even when the child exits zero.
The deferred registry removal and `CLOSED` upcall form the terminal barrier, so
a subsequent lifecycle cannot race Chromium/Aura destruction from the prior one.
After the callback returns, the JVM closes the FFM callback owner and its
shared Arena. Engine close while a browser is live returns
`engine-has-live-browsers` without changing the Kotlin
engine handle or OPEN state, so shutdown can be retried after browser close.

## Consequences

The embedded page is a real Chromium surface with native input, focus, GPU
compositing, Profile storage, and Unicode navigation. The API is intentionally
breaking and internal until DevTools/CDP and Compose ownership are connected.
Every advertised desktop platform must build and run the same ABI, FFM, and
real-browser integration contract; there is no OS WebView, OSR, or hidden-window
fallback.

## Verification

The C header, browser status, exact export set, native CTest suite, Kotlin
contract tests, and isolated JVM integration run in CI on macOS arm64, Windows
x64, and Linux x64 (Linux under Xvfb). The macOS integration additionally
asserts AppKit parentage, Alloy/windowed mode, Unicode titles and URLs, native
resize, cookie/local-storage persistence, live-count zero, and terminal event
ordering.
