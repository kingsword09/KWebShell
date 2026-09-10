# RFC 0009: Profile cookies, cache, storage, spellcheck, and session state

- Status: Proposed
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, Chromium Profile adapter
- Depends on: RFC 0002, RFC 0003
- Electron migration surface: `session.fromPartition`, cookies, cache/storage clearing, spellChecker APIs
- Target mapping: `DIRECT` or `REWRITE`

## Objective

Complete the persistent `KWebProfile` data-management contract through
Chromium-owned services. Applications need typed cookie access, bounded
cache/storage clearing, storage-usage facts, spellcheck language configuration,
and explicit flush without a generic Electron Session object.

## Contract

Cookie models preserve Chromium semantics for domain, path, secure, HTTP-only,
SameSite, partitioning, expiry, and source scheme/port. Clear operations require
typed origin/time/data-kind filters and return measured results. Profile state is
never addressable by Electron partition strings; applications hold a
`KWebProfile`.

## Acceptance

1. Real Chromium tests cover cookie set/get/change/remove, SameSite, partitioned
   cookies, expiry, redirects, HTTP-only isolation, and restart persistence.
2. Two Profiles prove cookie, cache, storage, dictionary, and permission
   isolation across three processes.
3. Clear operations affect only declared origins, data kinds, and time ranges;
   cache network evidence proves removed versus retained entries.
4. Flush waits for Chromium completion before Profile shutdown.
5. Spellcheck reports actual platform/Chromium language availability and never
   downloads a dictionary implicitly.
6. Exact-origin renderer exposure is absent by default; migration adapters
   expose only application-declared cookie/storage operations with sensitive
   values redacted from reports.

## Non-goals

No global/default Session singleton, guessed partition compatibility, direct
SQLite access, or Kotlin reimplementation of Chromium cookie/storage engines.
