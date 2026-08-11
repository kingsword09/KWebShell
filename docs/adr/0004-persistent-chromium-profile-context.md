# ADR 0004: Use explicit persistent Chromium Profile contexts

- Status: Accepted
- Date: 2026-08-11

## Context

KWebShell needs cookies, Web Storage, permissions, and later extension state to
belong to an explicit persistent Profile. A CEF request context with a nonempty
cache path appears to represent that Profile, but the Chrome bootstrap applies
a stricter rule: it creates or loads a disk-backed Chromium Profile only when
the cache path is the user-data directory itself or one of its direct children.
A deeper path logs an error and creates a unique OffTheRecord Profile.

Accepting a nested path would therefore be a silent persistence fallback. The
request context can still report the configured cache path, so comparing
`CefRequestContext::GetCachePath()` alone cannot prove that Chromium opened a
disk-backed Profile.

## Decision

The native host uses the global CEF `root_cache_path` as Chromium's user-data
directory and does not set a global `cache_path`. Every browser Profile is an
absolute, canonical, direct child of that root. The child name `Default` is
reserved using an ASCII case-insensitive comparison because Chromium owns that
primary Profile, and macOS and Windows commonly use case-insensitive filesystems.

- Configuration rejects missing, relative, nested, or lexically escaping
  Profile paths and the reserved `Default` name before CEF starts.
- The host creates and canonicalizes the root and Profile directories, then
  repeats the direct-child check. A symlink or filesystem alias cannot redirect
  the Profile outside the declared root.
- Each Profile uses `CefRequestContext::CreateContext` with its own cache path
  and `persist_session_cookies = true`.
- Browser creation waits for
  `CefRequestContextHandler::OnRequestContextInitialized`. A null or global
  context, a cache-path mismatch, or context creation failure is fatal.
- The exact initialized context is passed to `CefBrowserHost::CreateBrowser` on
  macOS, Windows, and Linux. `OnAfterCreated` verifies that the browser owns
  that same context.
- Every graceful close flushes the request context's cookie store and waits for
  its completion callback before requesting browser close. A rejected flush or
  a missing callback within 30 seconds is fatal. Forced close after a runtime
  failure remains immediate; request-context release and `CefShutdown` retain
  their existing bounded lifecycle.

Disk persistence is verified only after the CEF process has shut down. Chromium
may defer writing `Preferences` until Profile teardown, so checking that file
while the message loop is active creates a false failure. The integration test
requires Preferences, Cookies, and Local Storage LevelDB files at the declared
Profile path after shutdown; this also detects the OffTheRecord behavior caused
by an invalid Chrome bootstrap path.

The semantic contract uses three separate real CEF processes and one controlled
origin, `https://kwebshell.test/profile-self-test`:

```text
Profile A write
Profile B expect-absent
Profile A read
```

The first process writes `localStorage` and a session cookie. The second proves
Profile isolation by requiring both values to be absent. The third proves that
both values survived the first process shutdown and Profile A restart. Every
run also requires a real renderer, ordered lifecycle events, cookie flush, clean
browser destruction, and bounded CEF shutdown.

This objective remains internal to `kweb-cef-native`. It does not expose a
public `KWebProfile` until the same lifecycle is connected to the real JNI and
browser session contract.

## Consequences

- Profile identity and storage location are deterministic across all desktop
  platforms.
- Extension state can later attach to the same Chromium Profile without a
  second storage model.
- Applications must choose a single direct child name per Profile instead of a
  nested layout such as `root/profiles/name`, and must not use `Default` with
  any ASCII casing.
- The direct-child restriction is intentionally breaking. KWebShell does not
  translate an invalid path or select another directory.
- A Profile that cannot initialize, persist, flush, or shut down fails with an
  observable native error instead of continuing in memory.

## Rejected alternatives

### Use `root/profiles/name`

Rejected because the Chrome bootstrap does not create a disk-backed Profile at
that depth. It creates a unique OffTheRecord Profile instead.

### Use the global request context for every browser

Rejected because it implicitly shares cookies, storage, permissions, and future
extension state between callers.

### Treat a matching request-context cache path as persistence proof

Rejected because the configured path remains observable even when Chromium
falls back to an OffTheRecord Profile. Restart behavior and post-shutdown disk
artifacts are required evidence.

### Poll `Preferences` before browser close

Rejected because Chromium is allowed to defer that write until Profile
teardown. Verification belongs after clean process shutdown.
