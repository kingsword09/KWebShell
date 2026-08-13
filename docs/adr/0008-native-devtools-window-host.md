# ADR 0008: Native DevTools window host

- Status: Accepted
- Date: 2026-08-13

## Context

The embedded page uses the required Alloy windowed native-child path. CEF 151
does not support an Alloy DevTools front-end: its DevTools window is created by
the Chrome-style DevTools controller. Treating DevTools as another Alloy page
causes CEF to reject creation and leaves the browser lifecycle incomplete.

## Decision

`browserOpenDevTools` and `browserCloseDevTools` are breaking ABI v4
operations. The native browser session owns a private CEF DevTools client and
tracks the associated DevTools browser. The source page remains Alloy; only the
separate DevTools window uses the CEF-supported Chrome-style host.

The native session emits `DEVTOOLS_OPENED`, `DEVTOOLS_CLOSED`, or
`DEVTOOLS_FAILED`. Duplicate open and missing close requests return typed
statuses. A source-page close first requests DevTools close and delays the page
terminal event until the DevTools `OnBeforeClose` callback has completed.
If CEF does not create the DevTools browser within 30 seconds, the session
emits a typed `devtools-open-failed` timeout and closes any late browser.

## Consequences

DevTools is a real native top-level window with Chromium's own front-end and
not a fake page, OS debugger, or alternate renderer. Its Chrome-style status
must not be interpreted as a relaxation of the primary Alloy rendering rule.
The private handle contract can later support DevTools-specific commands
without exposing CEF pointers or window internals to Kotlin.

## Verification

The ABI/export contract tests cover the new operations and typed statuses.
Real macOS CEF integration opens the window, discovers its `devtools://`
target over the explicit loopback CDP endpoint, rejects duplicate open, closes
the target explicitly, reopens it, and verifies that page close emits
`DEVTOOLS_CLOSED` before `CLOSED`. The same sources compile and run through the
Linux/Xvfb and Windows CI jobs.
