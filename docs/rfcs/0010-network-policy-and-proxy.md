# RFC 0010: Profile network request policy and observation

- Status: Implemented
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, CEF/Chromium request adapter
- Depends on: RFC 0004, RFC 0009
- Electron migration surface: `session.webRequest`
- Target mapping: `REWRITE`

## Objective

Expose a complete, typed, Profile-scoped request-policy and network-observation
slice that works with the pinned stock CEF 151 runtime. The implementation
uses CEF's `CefResourceRequestHandler` and the existing native callback
dispatcher. It does not add a second HTTP client, renderer fetch bypass, or
Electron-style mutable callback bag.

This RFC deliberately contains only behavior available through stock CEF:

- declarative allow, block, and redirect rules;
- request-method and resource-type matching;
- deterministic priority and declaration-order matching;
- bounded request-header add/remove mutations;
- bounded, body-free, credential-redacted request observation;
- atomic policy replacement;
- explicit Profile ownership, isolation, close, and reopen behavior.

Profile proxy configuration and proxy resolution, Profile User-Agent and
Accept-Language configuration, and the private Chromium NetworkContext ABI are
deferred to later RFCs. They are not public API in this revision.

Contract revision: `2026-09-28.5`

## Implementation contract

### Public Kotlin contract

`kweb-core` publishes these value types and `KWebProfile` operations:

```kotlin
public enum class KWebNetworkRuleAction { ALLOW, BLOCK, REDIRECT }
public enum class KWebNetworkRequestPhase { BEFORE_REQUEST, COMPLETE }
public enum class KWebNetworkCompletionStatus {
    UNKNOWN, SUCCESS, PENDING, CANCELED, FAILED
}
public enum class KWebNetworkResourceType {
    MAIN_FRAME, SUB_FRAME, STYLESHEET, SCRIPT, IMAGE, FONT, OBJECT,
    MEDIA, WORKER, XHR, PING, CSP_REPORT, OTHER
}

public data class KWebNetworkHeaderMutation(
    public val name: String,
    public val value: String?, // null removes the header
)

public data class KWebNetworkRule(
    public val id: String,
    public val urlPattern: String,
    public val resourceTypes: Set<KWebNetworkResourceType> = emptySet(),
    public val methods: Set<String> = emptySet(),
    public val priority: Int = 0,
    public val action: KWebNetworkRuleAction = KWebNetworkRuleAction.ALLOW,
    public val redirectUrl: String? = null,
    public val headerMutations: List<KWebNetworkHeaderMutation> = emptyList(),
)

public data class KWebNetworkPolicy(
    public val version: Int = 1,
    public val rules: List<KWebNetworkRule> = emptyList(),
)

public data class KWebNetworkRequestEvent(
    public val requestId: Long,
    public val phase: KWebNetworkRequestPhase,
    public val url: String,
    public val method: String,
    public val resourceType: KWebNetworkResourceType,
    public val action: KWebNetworkRuleAction,
    public val statusCode: Int? = null,
    public val errorId: String? = null,
    public val completionStatus: KWebNetworkCompletionStatus? = null,
    public val redirectedUrl: String? = null,
    public val policyVersion: Int,
)
```

`KWebProfile.configureNetworkPolicy(policy)` validates the complete policy
off the CEF UI thread and installs one immutable Profile snapshot on the CEF UI
thread. A replacement is published only after native validation succeeds.
`KWebProfile.networkEvents` is a broadcast view shared by the Profile's
pages. It has no replay: a collector receives only events emitted while it is
subscribed.

Observation starts with the Profile's first Page request even when no policy
has been configured. The implicit initial policy is version 1 with an empty
rule list. The first matching rule after sorting by descending `priority`
and then declaration order determines the request action.

The private Kotlin/native wire payload is versioned JSON:

```json
{"version":1,"rules":[]}
```

It is an internal ABI payload, not a public arbitrary-string IPC API. Native
validates the schema again before activating it.

### Limits and validation

| Field | Limit |
| --- | ---: |
| policy version | exactly `1` |
| rules per policy | 256 |
| rule id / URL pattern | 128 / 2048 UTF-8 bytes |
| header mutations per rule | 32 |
| header name / value | 128 / 8192 UTF-8 bytes |
| redirect target URL | 8192 UTF-8 bytes |
| total policy JSON | 256 KiB |
| redirect depth per request | 5 |
| observation event payload | 16 KiB |
| buffered observation events | 256 per Profile |

URL patterns are absolute `http` or `https` URL globs. `*` matches any
sequence of bytes and matching is anchored to the complete URL. Patterns and
redirect targets may not contain URL user-info. Empty `resourceTypes` and
`methods` match all values. Methods use `[A-Za-z]{1,16}`. Duplicate rule
IDs, malformed patterns, invalid methods, unsupported resource types, invalid
redirect targets, duplicate header mutations, and exceeded limits fail before
activation.

Header names are ASCII case-insensitive. The following names cannot be added,
removed, or overwritten: `Host`, `Content-Length`, `Cookie`,
`Set-Cookie`, `Authorization`, `Proxy-Authorization`, `Origin`, and
`Referer`. Header values reject control characters other than HTAB. Native
repeats these checks even when called without the Kotlin validator.

A `BLOCK` rule cancels the request. A `REDIRECT` rule changes the request
URL to its absolute HTTP(S) target. Redirect depth is tracked by the native
Profile policy state; exceeding five redirects emits
`network.redirect.loop` and cancels the request.

### Observation and lifecycle

Each request captures the immutable policy snapshot at the start of
`OnBeforeResourceLoad`. The before and complete events use that same policy
version and initial action even if a replacement occurs while the request is in
flight. Events contain metadata only: no response body, request body, cookies,
authorization values, or arbitrary header map crosses the callback boundary.
URL user-info is removed before native emission and Kotlin rejects any
credential-bearing event URL.

`BEFORE_REQUEST` events carry the decision and optional redirect target.
`COMPLETE` events carry the HTTP status and CEF completion status. The
completion status is absent from before events. Callbacks are posted through
the existing native engine dispatcher before Kotlin delivery.

The event stream uses a 256-event bounded buffer with overflow terminal
semantics. Overflow fails all active and future collectors for that Profile
with `network.observation-backpressure`; no event is silently dropped.
Profile close fails active and future collectors with
`network.profile-closing`. A policy replacement already in progress is
rejected with `network.operation-pending`. A policy call on the CEF UI thread
is rejected with `network.operation-wrong-thread`.

Profiles are keyed by their canonical persistent path. Their policy state and
event stream are never shared with another Profile. Closing a Profile clears
its policy state after its Pages are closed and releases the cached CEF
RequestContext. Reopening the same path creates a fresh empty policy state.
`openProfile` waits for persistent CEF RequestContext initialization, does not
create a Page, and returns `profile.context-initialization-failed` if CEF
cannot initialize the context. This operation does not claim that Chromium's
NetworkContext has a configurable Profile proxy.

### Stable errors

The applicable RFC 0010 errors are:

- `network.policy.invalid`
- `network.policy.limit-exceeded`
- `network.header.forbidden`
- `network.redirect.invalid`
- `network.redirect.loop`
- `network.profile-closing`
- `network.operation-pending`
- `network.operation-wrong-thread`
- `network.observation-backpressure`
- `profile.context-initialization-failed`

There is no system-WebView fallback, process-wide proxy fallback, renderer
network bypass, Kotlin HTTP replacement, unredacted observation path, or fake
success for a deferred capability.

### Deferred scope

The following surfaces are intentionally excluded from this RFC and must not
be reintroduced as partial or fallback behavior:

| Deferred surface | Reason and future owner |
| --- | --- |
| Profile fixed/PAC/direct proxy configuration | Stock CEF 151 has no Profile-scoped proxy setter; future custom-CEF feasibility RFC |
| `KWebProfile.resolveProxy` / `session.resolveProxy` | Requires the same future Profile NetworkContext proxy contract; no synthetic PAC resolver |
| Profile User-Agent override | Requires NetworkContext creation-time control unavailable in stock CEF; future custom-CEF RFC |
| Profile Accept-Language override | Requires Profile NetworkContext mutation unavailable in the stock contract; future custom-CEF RFC |
| TLS, authentication, downloads, `netLog`, network emulation | Separate RFC ownership |

The Electron migration matrix therefore maps `session.webRequest` to this
typed policy and observation contract, while `session.setProxy` and
`session.resolveProxy` remain `UNSUPPORTED`/deferred.

### Native boundary

The native implementation uses stock CEF's real
`CefResourceRequestHandler` for matching, mutation, redirect, cancellation,
and metadata observation. CEF/Chromium C++ types remain behind the existing
native layer and opaque C ABI. No custom CEF patch, private NetworkContext
symbol, or runtime capability gate is required for this RFC.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / typed contract | The v1 policy, rule, event, and error schemas are bounded and versioned. | Valid v1; unknown version; empty policy; every size boundary; malformed JSON. | Kotlin contract tests and native validation tests on macOS arm64, Windows x64, and Linux x64. | `KWebNetworkContract.kt`; `KWebDesktopNetworkTest`; native `NetworkPolicyState::Prepare`. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — bounded policy serialization, native revalidation, and all three hosted targets passed. |
| A2 / matching | URL, method, resource type, priority, and declaration order select exactly one rule. | Overlapping priorities; equal-priority declaration order; filters; no match; malformed/user-info patterns; duplicate IDs. | Kotlin validator tests and real CEF request fixture on all three targets. | `NetworkPolicyState::Prepare`; `Match`; `networkPolicyIntegrationTest`. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — real stock CEF matching scenarios passed on all three hosted targets. |
| A3 / header policy | Allowed add/remove mutations reach the real request; forbidden names and unsafe values reject activation. | Add and remove; duplicate names; forbidden headers; CR/LF/DEL values; native revalidation. | Kotlin negative tests, native validation, and real CEF/CDP fixture on all three targets. | `KWebDesktopNetworkTest`; `NetworkPolicyState::Prepare`; integration Header scenario. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — allowed mutations and native forbidden-header rejection passed on all three hosted targets. |
| A4 / block and redirect | Block cancels, redirect rewrites, and redirect loops terminate with a typed error. | HTTP/HTTPS request interception; one redirect; six-step loop; invalid scheme/target. | Real stock CEF fixture and negative validation on all three targets. | `NetworkPolicyRequestHandler`; integration block/redirect/loop scenarios. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — block, redirect, and loop termination passed on all three hosted targets. |
| A5 / observation | Before/complete metadata is ordered, bounded, body-free, redacted, and tied to the captured snapshot. | Default observation; no replay; completion pairing; credential URL; 256-event burst; overflow; active/future collectors. | Stream tests and real stock CEF burst on all three targets. | `KWebDesktopNetworkEventStream`; `JsonEvent`; integration default/credential/burst scenarios. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — metadata-only observation, redaction, and overflow terminal behavior passed on all three hosted targets. |
| A6 / proxy modes (previous revision) | Profile proxy configuration is not part of this RFC. | Proxy API is absent; no process/system proxy fallback; migration row is deferred. | API/source review and migration matrix test. | `KWebNetworkContract.kt`; `KWebElectronCapabilityMatrix.kt`. | Reviewed scope amendment `.5`. | `NOT_APPLICABLE` — explicitly deferred because stock CEF lacks the required Profile contract. |
| A7 / proxy resolution (previous revision) | Profile proxy resolution is not part of this RFC. | `resolveProxy` is absent; no synthetic PAC result. | API/source review and migration matrix test. | `KWebPageContract.kt`; migration matrix and README. | Reviewed scope amendment `.5`. | `NOT_APPLICABLE` — explicitly deferred to the future proxy contract. |
| A8 / UA/language (previous revision) | Profile User-Agent and Accept-Language overrides are not part of this RFC. | No public fields; no silent process-wide or renderer override. | API/source review and migration/documentation check. | `KWebNetworkContract.kt`; RFC deferred-scope table. | Reviewed scope amendment `.5`. | `NOT_APPLICABLE` — explicitly deferred because stock CEF cannot provide the declared Profile behavior. |
| A9 / atomicity and Profile isolation | A rejected replacement leaves the previous request snapshot active; Profile states do not leak across Profiles; close/reopen starts empty. | Native forbidden-header replacement; two Profiles with different block rules; close/reopen; no Page before close. | Real stock CEF two-Profile and close/reopen fixture on all three targets. | `PreparedNetworkPolicy`; `NetworkPolicyState::Install/Clear`; integration atomic/isolation/reopen scenarios. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — atomic replacement, isolation, and empty-policy reopen passed on all three hosted targets. |
| A10 / security boundary | Credentials and bodies never cross the observation boundary, and forbidden headers/private-network policy are not widened. | URL user-info redaction; unknown body/header event fields; forbidden header; Chromium-owned auth/private-network behavior. | Kotlin/native tests, real CEF credential case, and applicability review on all three targets. | `JsonEvent`; `parseEvent`; validator; RFC 0011/extension-policy ownership references. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — credential and body/header-map boundaries passed; private-network policy remains Chromium-owned. |
| A11 / stock CEF feasibility | The advertised slice works without the private NetworkContext ABI or custom CEF runtime. | Stock runtime opens Profile, installs policy, observes requests, and reports no runtime-capability gate. | Ordinary three-platform CEF CI integration and source review. | `EnsureProfileContext`; `SetProfileNetworkPolicy`; stock `networkPolicyIntegrationTest`. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — stock CEF passed on macOS arm64, Windows x64, and Linux x64 without the private ABI. |
| A12 / lifecycle | Profile open/close and observation collectors have deterministic terminal outcomes. | Context init failure; close with live Page; close after policy; active/future collectors; late callbacks. | Native lifecycle tests and real stock CEF close/reopen runs on all three targets. | Profile context registry; `KWebDesktopProfile.close`; stream close test. | `network-policy-evidence.json` from CI run; reviewed contract `.5`. | `PASS` — close/reopen, collector overflow, and terminal cleanup passed on all three hosted targets. |
| A13 / Electron migration | `session.webRequest` maps to typed policy/observation; proxy and resolver rows are explicit blockers. | Imperative callback bag; response-body access; setProxy; resolveProxy; supported declarative rewrite. | Migration matrix tests and reviewed migration documents. | `KWebElectronCapabilityMatrixTest`; migration README; matrix documentation. | Hosted RFC governance contract-test artifacts and reviewed source; contract `.5`. | `PASS` — webRequest is REWRITE; proxy and resolver remain explicit UNSUPPORTED/deferred rows. |
| A14 / universal completion | Implementation, tests, documentation, matrix, evidence, reviewed revision, packaging, and clean worktree agree. | Missing target, stale evidence, undocumented breaking API, skipped required test, or dirty worktree blocks merge. | Final row-by-row review, ordinary three-platform CI, and retained evidence review. | RFC review record, complete PR diff, CI artifacts, `git diff --check`. | Pending PR revision and hosted evidence. | `NOT_RUN` — final acceptance review required before merge. |

## Implementation-readiness review

- Reviewed revision: `2026-09-28.5`
- Review pass: Codex stock-CEF contract and feasibility review, 2026-09-28
  (same contributor as implementation; separate review pass).
- Decisions: the v1 public surface is limited to request rules, Header
  mutation, body-free observation, bounded backpressure, Profile isolation, and
  lifecycle. Policy validation is duplicated in Kotlin and native. The
  request-handler path uses stock CEF APIs; no private NetworkContext ABI is
  required. Proxy, resolver, User-Agent, and Accept-Language behavior is
  removed from the public contract and explicitly deferred.
- Feasibility finding: stock CEF 151 exposes the required
  `CefResourceRequestHandler` interception path and the existing Profile
  RequestContext lifecycle. It does not expose the Profile-scoped proxy and
  NetworkContext mutation contract previously required by RFC 0010. That
  missing capability is resolved by scope reduction, not by a fallback.
- Decision: **READY** for the narrowed stock-CEF objective. The decision is
  readiness only; real macOS arm64, Windows x64, and Linux x64 runtime
  evidence remains mandatory before merge.

## Non-goals

No general Kotlin HTTP client, renderer fetch bypass, VPN, packet capture,
TLS/certificate policy, download policy, network emulation, `netLog`,
unlimited observation retention, response-body capture, proxy credential store,
Profile proxy configuration, proxy resolution, Profile User-Agent or
Accept-Language overrides, or compatibility promise for Electron's imperative
callback API.
