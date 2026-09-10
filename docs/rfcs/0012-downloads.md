# RFC 0012: Profile downloads and scoped results

- Status: Proposed
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, Chromium download adapter
- Depends on: RFC 0003, RFC 0004, RFC 0009
- Electron migration surface: `will-download`, `DownloadItem`
- Target mapping: `REWRITE`

## Objective

Publish Chromium-owned downloads with explicit destination policy, progress,
pause/resume/cancel, integrity facts, and scoped completed-file access. A
renderer never receives an ambient host path.

## Contract

`KWebDownload` is Profile-scoped and closeable. Immutable state includes ID,
source/final URL, suggested name, MIME type, received/total bytes, speed,
interrupt reason, content hash when requested, and terminal scoped file handle.
Destination is selected by Kotlin policy or `KWebDialogs`; collisions and
overwrite are explicit.

## Acceptance

1. Real HTTP fixtures cover known/unknown length, redirects, content
   disposition, Unicode names, range resume, no-range restart rejection,
   interruption, cancellation, checksum mismatch, and concurrent downloads.
2. Safe filename/canonicalization tests reject traversal, symlink replacement,
   device names, and destination races on every platform filesystem.
3. Progress is monotonic and backpressured; exactly one terminal state is
   emitted through renderer crash, navigation, Profile close, and application
   shutdown.
4. Completed bytes are accessible only through a scoped handle compatible with
   RFC 0013; partial files follow an explicit cleanup policy.
5. Migration fixture rewrites `DownloadItem` callbacks to typed state and blocks
   unsupported path mutation.
6. Hosted evidence includes real bytes, hash, resume requests, terminal state,
   and zero live Chromium download/handle owners.

## Non-goals

No Kotlin download engine, browser-backend fallback, automatic execution/open,
or unrestricted `setSavePath`.
