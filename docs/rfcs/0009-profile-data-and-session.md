# RFC 0009: Profile cookies, cache, storage, spellcheck, and session state

- Status: Accepted
- Priority: P0
- Owners: kweb-core, kweb-desktop, Chromium Profile adapter
- Depends on: RFC 0002, RFC 0003, RFC 0008
- Electron migration surface: session.fromPartition, cookies, cache/storage clearing, spellChecker APIs
- Target mapping: DIRECT for profile data; REWRITE for Electron Session identity

## Objective

Complete the persistent KWebProfile data-management contract through
Chromium-owned services. Applications receive typed cookie access, bounded
cache/storage clearing, storage-usage facts, spellcheck language
configuration, and an explicit flush operation without receiving a generic
Electron Session object or a guessed partition string.

This is one complete vertical slice for the existing desktop Profile owner. It
includes the common Kotlin contract, the desktop FFM/C ABI, the CEF/Chromium
adapter, real page-targeted DevTools operations, native integration fixtures,
Electron migration classification, retained evidence, and documentation.

Supported targets are macOS arm64, Windows x64, and Linux x64/X11. The
implementation uses the existing persistent CEF 151 Alloy browser and its
request context. It does not add a second renderer, hidden browser, OSR
fallback, direct SQLite reader, or global Session singleton.

## Contract

### Profile and operation target

KWebProfile remains the owner of all data. Data operations that need a
Chromium browser-process or DevTools target receive an explicit KWebPage from
the same Profile:

    suspend fun listCookies(target: KWebPage, filter: KWebCookieFilter = KWebCookieFilter()): List<KWebCookie>
    suspend fun setCookie(target: KWebPage, cookie: KWebCookieSpec): KWebCookieMutationResult
    suspend fun deleteCookies(target: KWebPage, filter: KWebCookieFilter): KWebCookieMutationResult
    suspend fun clearData(target: KWebPage, filter: KWebProfileDataFilter): KWebProfileDataClearResult
    suspend fun storageUsage(target: KWebPage, origin: String): KWebStorageUsage
    suspend fun configureSpellcheck(target: KWebPage, configuration: KWebSpellcheckConfiguration): KWebSpellcheckState
    suspend fun flush(target: KWebPage): KWebProfileFlushResult

The target page must be open, must belong to the receiver Profile, and must
use the receiver's persistent request context. A closed, foreign, or
renderer-terminated target fails before native dispatch. KWebShell never
creates a hidden or off-screen page solely to service a Profile operation.
This rule keeps storage operations bound to the real Alloy page and makes
cross-Profile access impossible to infer from a caller-supplied path.

Only one Profile data operation is active at a time. A second operation fails
with profile.data-operation-pending rather than being reordered or silently
merged. Operations are serialized on the CEF UI thread and their result is
delivered through the owning page's FFM callback owner before the next
operation starts.

### Cookie model

The common contract separates a cookie value from the URL used to set it:

    public data class KWebCookieSpec(
        val url: String,
        val name: String,
        val value: String,
        val domain: String? = null,
        val path: String = "/",
        val secure: Boolean = false,
        val httpOnly: Boolean = false,
        val sameSite: KWebCookieSameSite = KWebCookieSameSite.UNSPECIFIED,
        val priority: KWebCookiePriority = KWebCookiePriority.MEDIUM,
        val expiresEpochMillis: Long? = null,
        val sourceScheme: KWebCookieSourceScheme? = null,
        val sourcePort: Int? = null,
        val partitionKey: String? = null,
    )

    public data class KWebCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val secure: Boolean,
        val httpOnly: Boolean,
        val sameSite: KWebCookieSameSite,
        val priority: KWebCookiePriority,
        val creationEpochMillis: Long?,
        val lastAccessEpochMillis: Long?,
        val expiresEpochMillis: Long?,
        val sourceScheme: KWebCookieSourceScheme?,
        val sourcePort: Int?,
        val partitionKey: String?,
    )

    public enum class KWebCookieSameSite { UNSPECIFIED, NONE, LAX, STRICT }
    public enum class KWebCookiePriority { LOW, MEDIUM, HIGH }
    public enum class KWebCookieSourceScheme { HTTP, HTTPS, UNKNOWN }

Cookie list/set/delete uses Chromium's Network DevTools domain through
CefBrowserHost::ExecuteDevToolsMethod and
CefDevToolsMessageObserver. This is an internal CEF protocol call and does not
enable remote debugging or expose arbitrary script evaluation. It preserves
partitionKey, sourceScheme, sourcePort, SameSite, expiry, HTTP-only, Secure,
domain, path, and priority exactly as Chromium reports them.

    public data class KWebCookieFilter(
        val origin: String? = null,
        val name: String? = null,
        val domain: String? = null,
        val path: String? = null,
        val partitionKey: String? = null,
        val includeHttpOnly: Boolean = true,
        val timeRange: KWebProfileTimeRange = KWebProfileTimeRange(),
    )

An origin is a canonical http or https origin without a path, query, or
fragment. Cookie filters match every supplied field. The default filter lists
the complete persistent cookie jar for the Profile. Cookie values are returned
to the application API, but retained evidence and diagnostics redact values.
Cookie names, domains, paths, partition keys, and timestamps remain observable
in typed results.

Set rejects invalid URLs, empty names, control characters, invalid SameSite or
source-port combinations, invalid partition keys, and values exceeding
1 MiB UTF-8. Delete returns the number of cookies actually removed. Expired
cookies are not reported as successful writes.

### Data clearing

    public enum class KWebProfileDataKind {
        COOKIES,
        HTTP_CACHE,
        LOCAL_STORAGE,
        INDEXED_DB,
        CACHE_STORAGE,
        SERVICE_WORKERS,
        WEB_SQL,
        FILE_SYSTEMS,
        SHARED_STORAGE,
    }

    public data class KWebProfileTimeRange(
        val sinceEpochMillis: Long? = null,
        val untilEpochMillis: Long? = null,
    )

    public data class KWebProfileDataFilter(
        val kinds: Set<KWebProfileDataKind>,
        val origin: String? = null,
        val timeRange: KWebProfileTimeRange = KWebProfileTimeRange(),
    )

Cookies are filtered by origin and time range in the Kotlin/native operation
before each exact Network.deleteCookies call. Origin storage kinds are cleared
with Storage.clearDataForOrigin for exactly the declared origin. HTTP_CACHE is
the one profile-wide kind because Chromium's CEF API exposes
CefRequestContext::ClearHttpCache without an origin or time-range selector.
The contract therefore rejects an origin or bounded time range when
HTTP_CACHE is requested; it never claims a narrower clear than Chromium can
prove. CACHE_STORAGE is origin-scoped through Storage.clearDataForOrigin.

The operation rejects an empty kind set, an origin for HTTP_CACHE, a missing
origin for origin-scoped kinds, reversed or out-of-range timestamps, and
bounded time ranges for non-cookie kinds with typed errors. A clear result
reports requested kinds, completed kinds, origin, cookie count, and exact
start/end timestamps. Partial native completion is a failure and is not
reported as success.

### Storage usage

    public data class KWebStorageUsageEntry(
        val storageType: String,
        val bytes: Long,
    )

    public data class KWebStorageUsage(
        val origin: String,
        val usageBytes: Long,
        val quotaBytes: Long,
        val breakdown: List<KWebStorageUsageEntry>,
    )

Storage usage uses Storage.getUsageAndQuota for the exact canonical origin.
Unknown Chromium breakdown names are retained as strings rather than mapped
to a guessed enum. Negative byte counts, a mismatched origin, or a quota
smaller than usage fail the operation.

### Spellcheck and flush

    public data class KWebSpellcheckConfiguration(
        val enabled: Boolean,
        val languages: List<String>,
    )

    public data class KWebSpellcheckState(
        val enabled: Boolean,
        val languages: List<String>,
    )

Spellcheck configuration is applied through the persistent request context's
Chromium preferences spellcheck.enabled and spellcheck.dictionaries. The
result is read back from Chromium after the write; the reported language list
is exactly the normalized list retained by Chromium. KWebShell never downloads
a dictionary or silently adds a platform language. Empty language lists are
valid only when enabled is false. Language tags must be valid BCP-47-like
tokens and duplicates are rejected.

flush waits for CefCookieManager::FlushStore and the CEF UI quiescence
callback associated with the target Profile before returning. Profile close
still requires all pages to be closed, and browser close performs the same
cookie-store flush before native destruction. A flush timeout is a typed
failure and does not claim that persistent state is durable.

### Lifecycle, errors, and limits

The stable error identifiers are:

- profile.closed
- profile.data-target-invalid
- profile.data-target-cross-profile
- profile.data-target-terminated
- profile.data-operation-pending
- profile.data-origin-invalid
- profile.data-kind-invalid
- profile.data-time-range-invalid
- profile.data-time-range-unsupported
- profile.cookie-invalid
- profile.cookie-set-failed
- profile.cookie-delete-failed
- profile.storage-usage-failed
- profile.data-clear-failed
- profile.spellcheck-invalid
- profile.spellcheck-set-failed
- profile.flush-timeout
- profile.native-operation-failed

Input JSON crossing the C ABI is capped at 1 MiB and output JSON is capped at
4 MiB. A cookie listing is capped at 4096 cookies. The C ABI owns and copies
all transient strings before returning from a callback. Native callbacks
after page close are rejected and cannot mutate Profile state. Profile close
waits for the active operation to finish or fails with the declared timeout;
it does not silently cancel a Chromium mutation.

## Platform feasibility and implementation boundary

The pinned CEF 151 runtime supplies the required persistent
CefRequestContext, CefCookieManager, CefPreferenceManager,
CefRequestContext::ClearHttpCache, and browser-host DevTools methods on
macOS, Windows, and Linux. CEF's CefCookie struct does not expose every
Chromium cookie field needed by this RFC, so Network.getAllCookies,
Network.setCookie, and Network.deleteCookies are used through the internal
DevTools agent. This agent works without a DevTools window and without a
remote-debugging port.

The risky runtime assumptions are falsifiable by a native profile-data
fixture: the exact DevTools method result, profile path, request-context
identity, cookie partition/source fields, origin usage, clear scope, and
flush ordering are recorded for every target. If a pinned target lacks one
of these methods, that target fails with a typed capability error and the
advertised API is not promoted.

No direct SQLite access, Chromium private database mutation, global profile,
OS-specific storage fallback, hidden page, off-screen renderer, remote
debugging requirement, or arbitrary JavaScript evaluation is part of this
contract.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / Profile target and ownership | Every operation is bound to an open page in the receiver Profile and never crosses a request context. | Valid target; foreign Profile; closed page; renderer-terminated page; Profile close race. | Common contract tests, desktop ownership tests, real CEF target identity on macOS, Windows, Linux/X11. | KWebProfile contract; KWebDesktopProfile target validation; native request-context identity checks. | NOT_RUN before implementation. | NOT_RUN |
| A2 / Cookie model | Cookie list/set/delete preserves domain, path, Secure, HTTP-only, SameSite, priority, expiry, partition key, source scheme, and source port. | Host/domain cookie; all SameSite values; partitioned cookie; invalid source port; 1 MiB boundary; oversized value. | Kotlin model tests, ABI JSON tests, real Network.* CEF fixture on all targets. | KWebCookie models; profile data ABI; DevTools observer and cookie mapping tests. | NOT_RUN before implementation. | NOT_RUN |
| A3 / Cookie lifecycle and persistence | Cookie mutations report measured results, enforce filters, and survive clean Profile restart. | Set/get/change/delete; expiry; redirect origin; HTTP-only visibility; stale delete; restart. | Three-process real CEF profile fixture and retained cookie transcript on all targets. | Native cookie operation coordinator; profile restart fixture; cookie persistence report. | NOT_RUN before implementation. | NOT_RUN |
| A4 / Clear scope | Clear operations affect only declared kinds/origins/time ranges, with profile-wide HTTP cache limitations explicit. | Retained second origin; cookie time range; origin storage; HTTP cache profile clear; invalid scope/range; partial failure. | Storage.clearDataForOrigin and ClearHttpCache runtime fixture with network cache evidence on all targets. | KWebProfileDataFilter validation; native clear coordinator; network fixture. | NOT_RUN before implementation. | NOT_RUN |
| A5 / Storage usage | Usage and quota report exact canonical origin and non-negative Chromium breakdown bytes. | Empty origin; path/query origin rejection; populated local/IndexedDB/Cache Storage; unknown breakdown; quota boundary. | Real Storage.getUsageAndQuota fixture on all targets. | KWebStorageUsage; DevTools result parser; usage fixture. | NOT_RUN before implementation. | NOT_RUN |
| A6 / Spellcheck | Chromium accepts and persists only the requested spellcheck state; no dictionary is downloaded implicitly. | Enable/disable; duplicate language; invalid tag; restart; missing language resource; network unavailable. | Preference read-back and resource/network audit on all hosted targets. | Spellcheck preference adapter; language validation; no-download assertion. | NOT_RUN before implementation. | NOT_RUN |
| A7 / Flush and shutdown | Explicit flush completes before success and Profile/page close never reports durable state before Chromium completion. | Normal flush; concurrent operation; flush during page close; timeout; restart read-back. | Native callback ordering plus three-process restart fixture on all targets. | Flush coordinator; close ordering; lifecycle tests. | NOT_RUN before implementation. | NOT_RUN |
| A8 / Profile isolation | Two persistent Profiles cannot observe each other's cookies, cache, storage, spellcheck state, or permission-related storage. | Same origin in Profile A/B; symlink/case alias; restart each independently; cross-target path attempt. | Real two-Profile, three-process hosted fixture on macOS, Windows, Linux/X11. | Physical profile identity checks; isolation fixture; disk evidence. | NOT_RUN before implementation. | NOT_RUN |
| A9 / Concurrency and security | Bounded operations serialize, stale targets fail, sensitive cookie values are absent from diagnostics, and CEF UI is not blocked by subscribers. | Concurrent list/set/clear/flush; close race; forged request id; slow collector; output cap. | Kotlin concurrency tests, native callback stress, redacted evidence inspection on all targets. | Per-Profile operation gate; typed errors; redacted recorder; bounded JSON tests. | NOT_RUN before implementation. | NOT_RUN |
| A10 / Migration and documentation | Electron session surfaces are individually classified and docs/capability metadata match the implemented contract. | fromPartition; cookies; clear cache/storage; spellChecker; unsupported generic Session and partition strings. | Migration golden tests, README/capability matrix, complete PR review. | KWebElectronCapabilityMatrix; migration golden files; RFC and design plan. | NOT_RUN before implementation. | NOT_RUN |
| A11 / Native ABI and packaging | C header, FFM layouts, exported symbols, runtime packaging, and all advertised targets agree on one versioned profile-data ABI. | Wrong struct size/version; missing callback; invalid JSON; missing export; line-ending/digest drift. | Native C tests, FFM ABI tests, packaging checks, hosted three-target verify. | engine_abi.h; FfmAbi/FfmLayouts; export maps; C ABI contract tests. | NOT_RUN before implementation. | NOT_RUN |
| A12 / Universal completion | Every applicable row passes on every advertised target and the final review binds the exact implementation/evidence revision. | Missing target, stale digest, skipped runtime test, changed contract after recording, unsupported advertised state. | Governance check, git diff --check, hosted macOS/Windows/Linux matrix, final row review. | Complete PR diff, evidence manifest/artifacts, final acceptance review. | NOT_RUN before implementation. | NOT_RUN |

## Readiness review

- Reviewed revision: main baseline 8bcfd34 plus this RFC 0009 contract revision
  before implementation.
- Review pass: Codex implementation-readiness review by the same contributor
  who will implement the objective; this is not an independent-person
  approval.
- Date: 2026-09-25.
- Findings from the 43-line proposal: public signatures, cookie field
  coverage, target-page ownership, origin and time-range rules, CEF limits,
  spellcheck availability semantics, flush ordering, error identifiers,
  concurrency ceilings, evidence inputs, and cross-platform probes were
  unspecified.
- Decisions: use explicit same-Profile page targets; use the internal CEF
  DevTools agent for complete cookie and origin-storage semantics; keep
  HTTP_CACHE profile-wide because CEF exposes no origin/time selector; allow
  bounded time filtering only for cookies; read back Chromium spellcheck
  preferences without downloading dictionaries; fail closed for unsupported
  scopes; and retain real three-target evidence before promotion.
- Feasibility disposition: CEF 151 exposes the required request-context,
  cookie-manager, preference-manager, HTTP-cache, and browser-host DevTools
  surfaces. RFC 0008 already proves the real Alloy browser target and
  callback owner on all advertised targets. The profile-data fixture will
  falsify the remaining runtime assumptions before merge.
- Decision: READY. Coding may begin against this contract; any change to
  target ownership, storage scope, cookie fields, spellcheck semantics, or
  flush ordering requires a new review pass.

## Evidence lifecycle

RFC 0009 evidence is retained under
docs/rfcs/evidence/artifacts/0009/<sourceRevision>/<target>/.
Each target retains a redacted profile-data transcript, cookie/storage
results, clear-scope network report, spellcheck preference report, and
restart/flush report. Cookie values and other sensitive data are removed before
retention. The contract digest covers the common Profile API, desktop adapter,
native C ABI, CEF operation coordinator, migration matrix, fixtures, docs, and
aggregation workflow. Any change to those inputs invalidates the affected
records and requires a fresh three-target recording.

## Migration contract

| Electron surface | KWebShell result |
|---|---|
| session.fromPartition | Rewrite required: applications hold an explicit KWebProfile; partition strings are not accepted. |
| session.cookies.get/set/remove | Direct typed KWebProfile cookie operations with a same-Profile page target. |
| session.clearCache | Direct profile-wide HTTP_CACHE clear; origin/time selectors are rejected rather than guessed. |
| session.clearStorageData | Direct typed origin-scoped storage clear for the declared data kinds. |
| session.getCacheSize / storage usage | Direct origin-scoped KWebStorageUsage; aggregate Electron Session object is not mapped. |
| session.setSpellCheckerLanguages / spellChecker | Direct Chromium preference configuration with read-back and no implicit dictionary download. |
| session.flushStorageData | Direct KWebProfile.flush with Chromium completion ordering. |
| generic Session identity, arbitrary partition, direct database access | Unsupported; explicit typed errors and migration rewrite are required. |

## Non-goals

No global/default Session singleton, guessed partition compatibility, direct
SQLite access, direct cache/database filesystem mutation, hidden or off-screen
browser, generic Electron object identity, unrestricted JavaScript execution,
implicit dictionary download, unbounded cookie values, or a platform-specific
fallback data store is part of RFC 0009.
