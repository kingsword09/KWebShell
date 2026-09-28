# RFC 0010: Profile network request policy, proxy, and observation

- Status: Implementing
- Priority: P0
- Owners: `kweb-core`, `kweb-desktop`, CEF/Chromium network adapter
- Depends on: RFC 0004, RFC 0009
- Electron migration surface: `session.webRequest`, `setProxy`, `resolveProxy`, `net`
- Target mapping: `REWRITE`

## Objective

Expose a complete, typed, Profile-scoped networking slice for the pinned CEF
151 runtime. The implementation must use Chromium's NetworkContext and CEF's
resource-request interception path. It must not emulate Electron's mutable
callback bags, use a process-wide proxy switch, or introduce a second HTTP
client.

The v1 slice includes declarative request rules, bounded request observation,
direct/static/PAC proxy configuration, proxy resolution, Profile user-agent
and Accept-Language configuration, and atomic replacement. Network emulation,
TLS certificate policy, downloads, and `netLog` remain owned by later RFCs.

Contract revision: `2026-09-28.4`

## Implementation contract

### Public Kotlin contract

`kweb-core` publishes the following value types and `KWebProfile` operations:

```kotlin
public enum class KWebNetworkRuleAction { ALLOW, BLOCK, REDIRECT }
public enum class KWebNetworkRequestPhase { BEFORE_REQUEST, COMPLETE }
public enum class KWebNetworkCompletionStatus { UNKNOWN, SUCCESS, PENDING, CANCELED, FAILED }
public enum class KWebNetworkResourceType { MAIN_FRAME, SUB_FRAME, STYLESHEET,
    SCRIPT, IMAGE, FONT, OBJECT, MEDIA, WORKER, XHR, PING, CSP_REPORT, OTHER }

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

public enum class KWebProxyMode { DIRECT, FIXED, PAC }

public data class KWebProxyConfiguration(
    public val mode: KWebProxyMode = KWebProxyMode.DIRECT,
    public val rules: String = "",
    public val pacUrl: String? = null,
    public val pacMandatory: Boolean = false,
    public val bypassList: List<String> = emptyList(),
)

public data class KWebNetworkPolicy(
    public val version: Int = 1,
    public val rules: List<KWebNetworkRule> = emptyList(),
    public val proxy: KWebProxyConfiguration = KWebProxyConfiguration(),
    public val userAgent: String? = null,
    public val acceptLanguage: String? = null,
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

public data class KWebProxyResolution(
    public val url: String,
    public val result: String, // Chromium PAC form, e.g. "PROXY host:8080; DIRECT"
)
```

`KWebProfile.configureNetworkPolicy(policy)` validates the complete policy,
then installs it as one Profile transaction. `KWebProfile.resolveProxy(url)`
uses the same Profile NetworkContext. `KWebProfile.networkEvents` is a
broadcast view of a bounded, ordered event stream shared by the Profile's pages.
It has no replay: each collector receives events emitted while it is subscribed.
Observation is active from the Profile's first page request even before a
non-default policy is installed; the initial policy is version 1 with empty
rules and direct proxy configuration.

`openProfile(name)` creates and awaits that Profile's persistent CEF
`CefRequestContext` and initialized Chromium NetworkContext before it returns;
it does not create a Page, native surface, or renderer. Therefore policy
configuration and `resolveProxy` are usable immediately after `openProfile`,
including before the first Page. The first Page reuses this exact context.
Context initialization failure fails `openProfile` with the typed
`profile.context-initialization-failed` error; a runtime missing the required
Profile network ABI fails with `network.runtime-capability-missing`. No
system-proxy or stock-runtime substitute is allowed. An engine close attempted
while a Profile context is still opening fails with `desktop.profile-opening`;
the caller may retry once `openProfile` settles.
If the Profile buffer overflows, every active collector fails with
`network.observation-backpressure`, later collectors fail with the same
terminal error, and no further network events are delivered for that Profile.
Closing the Profile terminates active and future collectors with
`network.profile-closing`.

The wire representation between Kotlin and native is versioned JSON only at
the private ABI boundary. It is not a public arbitrary-string IPC API. Native
validates the same schema again before touching Chromium.

### Limits and validation

The following limits are part of the v1 contract:

| Field | Limit |
| --- | ---: |
| policy version | exactly `1` |
| rules per policy | 256 |
| rule id/pattern | 128/2048 UTF-8 bytes |
| header mutations per rule | 32 |
| header name/value | 128/8192 UTF-8 bytes |
| URL, redirect target, PAC URL | 8192 UTF-8 bytes |
| proxy rules / bypass entry / serialized bypass list | 8192/2048/8192 UTF-8 bytes |
| user-agent / Accept-Language | 1024/1024 UTF-8 bytes |
| total policy JSON | 256 KiB |
| redirect depth per request | 5 |
| observation event payload | 16 KiB |
| buffered observation events | 256 per Profile |

URL patterns are absolute `http` or `https` URL globs with `*` matching any
sequence of bytes. A pattern must parse as an absolute URL after substituting
each wildcard with a valid URL byte and must not contain user-info. Patterns
are anchored to the complete URL. Empty
`resourceTypes` and `methods` match all values. Rule precedence is descending
`priority`, then declaration order; the first matching rule is the decision.
`REDIRECT` requires an absolute `http` or `https` target. Duplicate rule IDs,
invalid methods, malformed patterns, unsupported resource types, and limits
that are exceeded fail before activation. A fixed-proxy rules string is
accepted only if every non-empty proxy rule and alternative is valid under
Chromium's supported proxy-rules grammar; Chromium's best-effort parser must
not silently discard malformed entries or turn an invalid configuration into
direct access.

Header mutation names are ASCII case-insensitive. The following headers may
not be added, removed, or overwritten: `Host`, `Content-Length`, `Cookie`,
`Set-Cookie`, `Authorization`, `Proxy-Authorization`, `Origin`, and
`Referer`. The policy is rejected with a stable validation error rather than
silently weakening the request.

Proxy modes are:

- `DIRECT`: no proxy;
- `FIXED`: Chromium proxy rules plus an explicit bypass list;
- `PAC`: an absolute `http` or `https` PAC URL, with an explicit mandatory
flag.

Fixed proxy rules use either one global comma-separated proxy list or unique
semicolon-separated `http=`, `https=`, `ftp=`, and `socks=` clauses. Every
alternative must parse as a valid Chromium proxy URI (`http`, `https`, `socks`,
`socks4`, `socks5`, or explicit `direct://`); QUIC and malformed/empty
alternatives are rejected. Bypass entries must each parse as a Chromium proxy
host-matching rule. Header values reject control characters other than HTAB.

An explicit proxy policy never falls back to the system proxy. A PAC failure
is reported as a typed error when `pacMandatory` is true; when it is false,
the result is the Chromium PAC result, including any explicitly declared
`DIRECT` fallback. `resolveProxy` returns Chromium's PAC-form result and never
performs a second resolver in Kotlin. If Chromium returns a proxy chain that
cannot be represented in PAC form, resolution fails with
`network.proxy.unavailable`; it must not rewrite the chain or claim `DIRECT`.

`userAgent` is applied when the Profile NetworkContext is created. Replacing
it after a Profile has created a NetworkContext is rejected with
`network.user-agent-requires-profile-reopen`; the caller must close and reopen
the Profile. `acceptLanguage` can be replaced atomically through Chromium's
NetworkContext API. A null value removes the override and restores the exact
CEF-generated default captured before the first Profile override; it does not
retain a prior override. A policy replacement that changes neither value
remains valid while pages are open. Both values reject ASCII control bytes,
including CR, LF, TAB, and DEL; values exceeding 1024 UTF-8 bytes fail with
`network.policy.limit-exceeded`.

### Lifecycle, threads, and errors

Policy validation and serialization run off the CEF UI thread. Native stages
the immutable snapshot on the CEF UI thread, applies Chromium's proxy and
language updates, and publishes the request-handler snapshot only after those
updates are accepted. A network operation invoked on the CEF UI thread fails
with `network.operation-wrong-thread`. A rejected replacement leaves both the
old Chromium configuration and old request snapshot active. Every request observation pair
uses the decision and policy version captured when the request began, even if
the Profile policy is later replaced. Event URLs remove URL user-info before
crossing the native callback ABI; credentials never appear in event details.
`errorId` carries a stable policy error such as `network.redirect.loop`;
`completionStatus` maps CEF's URL-request completion enum to the platform-neutral
Kotlin enum and is present only on complete events. `statusCode` is the HTTP
response status, not a transport error code.
No callback is executed on a Chromium network thread. `networkEvents` is
delivered through the existing native callback dispatcher and has
`DROP_OLDEST` disabled: overflow is a terminal typed
`network.observation-backpressure` error for that Profile.

Close wins races with a pending policy operation. A pending operation returns
`network.profile-closing`; a late Chromium callback is discarded and cannot
reopen the Profile or emit an event. Replacing a policy while another
replacement or proxy resolution is active returns
`network.operation-pending`.
Profile close releases its cached CEF request context after all native sessions
and network operations settle, so reopening the same Profile path creates a
fresh NetworkContext and cannot inherit the previous proxy, user-agent, or
Accept-Language snapshot.
The Profile-owned request context is released on close even when no Page was
ever opened; a successful reopen creates and initializes a fresh context before
returning.

Stable error identifiers include:

- `network.policy.invalid`
- `network.policy.limit-exceeded`
- `network.header.forbidden`
- `network.redirect.invalid`
- `network.redirect.loop`
- `network.proxy.invalid`
- `network.proxy.unavailable`
- `network.proxy.authentication-required`
- `network.user-agent-requires-profile-reopen`
- `network.profile-closing`
- `network.operation-pending`
- `network.operation-wrong-thread`
- `network.observation-backpressure`
- `network.runtime-capability-missing`
- `profile.context-initialization-failed`

There is no system-WebView, process-wide proxy, renderer fetch bypass, Node
HTTP client, silent proxy fallback, or unredacted response body path.

### Native and custom-runtime boundary

The pinned custom CEF source patch series adds a private, versioned C ABI for
Profile NetworkContext proxy configuration and proxy resolution. It binds the
Profile's `CefBrowserContext` to its NetworkContext and uses Chromium's
`initial_proxy_config`, `proxy_config_client_receiver`, `LookUpProxyForURL`,
`SetAcceptLanguage`, and user-agent NetworkContext parameters. The C ABI is
opaque to common Kotlin and is compiled only into the custom CEF runtime.

The KWeb native layer uses CEF's real `CefResourceRequestHandler` for request
rules and metadata observation. Response bodies and credential values never
cross the ABI. Stock CEF must fail the capability gate when the private ABI is
absent; it is not a fallback implementation.

### Security and ownership

Policies are keyed by the canonical Profile path and never shared between
Profiles. Private/incognito contexts, extension-origin requests, proxy
credentials, localhost/private-network access, and authentication challenges
remain governed by Chromium and the separate RFC 0011/TLS and extension
permission contracts. RFC 0010 does not grant new private-network or
credential capabilities. `netLog` and diagnostic files are owned by RFC 0032.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
| --- | --- | --- | --- | --- | --- | --- |
| A1 / typed contract | Policy, rule, proxy, event, and error schemas are versioned and bounded. | Valid v1; unknown version; each size limit; null/empty boundary. | Kotlin contract tests and native ABI tests on macOS arm64, Windows x64, and Linux x64. | `KWebNetworkContract.kt`; `KWebDesktopNetworkTest`; `NativeStatusContractTest`; `engine_abi_header_c_test`. | Pending hosted evidence; reviewed contract `.3`. | `NOT_RUN` — cross-target evidence required. |
| A2 / matcher precedence | Anchored HTTP URL glob without user-info, method, resource type, priority, and declaration order choose one rule. | Overlapping priorities and ties; method/resource filters; no match; malformed URL/user-info patterns; duplicate IDs. | Validator tests and real CEF request fixture on all three targets. | `NetworkPolicyState::Prepare`, `Match`; `networkPolicyIntegrationTest` priority scenario. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — native runtime evidence required. |
| A3 / observation | Default and configured policies emit ordered, bounded, body-free, credential-redacted before/complete metadata using the request's captured snapshot. | Observe before configuration; no replay; 256-event real burst; overflow fails active/future collectors; HTTPS interception; user-info redaction; completion/decision pairing; Profile close terminates collectors. | Real CEF fixture plus stream contract tests on all three targets. | `KWebDesktopNetworkEventStream`; `KWebDesktopNetworkTest`; `networkPolicyIntegrationTest` default/burst/credential scenarios; native `JsonEvent` and dispatcher. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — native runtime evidence required. |
| A4 / header policy | Allowed add/remove mutations apply; forbidden headers and control-character values reject activation. | Add and remove through a real request; forbidden header rejected by Kotlin and native validation; duplicate mutation; CR/LF and DEL values. | Kotlin negative tests and real CEF/CDP request fixture on all three targets. | `KWebDesktopNetworkTest`; native policy validator; `networkPolicyIntegrationTest` header scenario. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — native runtime evidence required. |
| A5 / block/redirect | Block cancels; redirect changes URL; redirect depth is bounded and reports a typed loop error. | HTTP and HTTPS interception; one redirect; six-step loop reports `network.redirect.loop`; invalid target/scheme; private targets remain subject to Chromium policy. | Real CEF request fixture plus negative validation on all three targets. | `NetworkPolicyRequestHandler`; `networkPolicyIntegrationTest` block/redirect/loop scenarios. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — native runtime evidence required. |
| A6 / proxy modes | No-policy and explicit `DIRECT`, fixed, PAC, and bypass behavior use each Profile NetworkContext; explicit configuration never falls back to system proxy. | Direct default; two live local proxies; bypass; successful PAC request; mandatory PAC failure; malformed fixed rules preserve prior config. | Custom CEF runtime proxy fixture on all three targets. | CEF adapter; `networkPolicyIntegrationTest` direct/fixed/bypass/PAC scenarios; raw native policy rejection. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — custom CEF builds and runtime evidence required. |
| A7 / proxy resolution | Resolution uses the Profile NetworkContext and returns Chromium PAC form without lossy chain conversion. | Fixed; PAC; direct; malformed URL; unavailable mandatory PAC; unrepresentable proxy chain; Profile close race. Multi-hop chains are not constructible by the declared v1 proxy modes; the conversion path still returns typed failure if Chromium supplies one. | Real NetworkContext `LookUpProxyForURL` fixture on all three targets. | Private proxy-resolve ABI; Kotlin result validation; `networkPolicyIntegrationTest`; reviewed non-applicability of multi-hop generation. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — custom CEF builds and runtime evidence required. |
| A8 / UA/language | UA and Accept-Language are applied with declared replacement semantics; null restores the original CEF default. | Initial values; live language replacement and reset; UA replacement rejection; invalid/control-containing and over-limit values. | Real origin server and validation tests on all three targets. | `ConfigureNetworkContextParams`; `NetworkContext::SetAcceptLanguage`; `networkPolicyIntegrationTest`; `KWebDesktopNetworkTest`; raw native validation. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — cross-target runtime evidence required. |
| A9 / isolation/atomicity | Profiles are isolated; replacement is all-or-nothing across Chromium NetworkContext and request snapshot. | Two real proxy endpoints; malformed native replacement preserves prior proxy/language/rules; rejected UA replacement; close clears default/configured state. | Two-profile custom-runtime fixture on all three targets. | `PreparedNetworkPolicy`; `NetworkPolicyState::Install`; CEF profile registry; `networkPolicyIntegrationTest`. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — custom CEF builds and runtime evidence required. |
| A10 / security | Credentials, bodies, forbidden headers, and private-network widening do not escape the declared metadata/policy boundary. | URL user-info redaction; body-free event schema rejects body fields; forbidden header; CR/LF UA/language rejected. Auth, extension-origin policy, and private-network authorization remain owned by Chromium and RFC 0011/extension policy. | Native security code review and applicable real CEF cases on all three targets; record each non-applicable owner explicitly. | Event schema and `JsonEvent`; native/Kotlin validators; `networkPolicyIntegrationTest`; RFC 0011/extension policy references. | Pending hosted review/evidence; reviewed contract `.3`. | `NOT_RUN` — cross-target evidence and reviewed applicability decisions required. |
| A11 / lifecycle | Profile close, replacement, resolution, and late callbacks have deterministic outcomes; reopening a Profile path creates a fresh NetworkContext. | Pending resolution blocks Profile close; competing operation is rejected; close wins with `network.profile-closing`; close/reopen same path clears default/configured adapter state. | Native lifecycle tests and real CEF close/reopen runs on all three targets. | Profile network-operation mutex; CEF context release and config cleanup; `networkPolicyIntegrationTest`; stream close test. | Pending patched-runtime runs; reviewed contract `.3`. | `NOT_RUN` — custom CEF lifecycle evidence required. |
| A12 / Electron migration | Each `webRequest` phase and proxy operation maps to typed v1 or a blocker/rewrite. | Imperative callback bag; body mutation; auth; netLog; supported declarative cases. | Migration matrix tests and reviewed document. | `KWebElectronCapabilityMatrixTest`; `verifyElectronMigrationManifest`; migration README. | Local JVM tests pass; hosted review pending; reviewed contract `.3`. | `NOT_RUN` — hosted review/evidence required. |
| A13 / patch provenance | Custom CEF patch series applies cleanly and exports the pinned private ABI. | Clean preimage; reordered/missing patch; stale header; stock runtime. | Source verifier, artifact verifier, and patched CEF builds on all targets. | `CefSourcePatchVerifierTest`; `CefCustomRuntimeArtifactVerifierTest`; `runtime/cef/patches/0002-*`; manifest/build tool. | Source/artifact verifier pass on macOS arm64; hosted runtime builds pending; reviewed contract `.3`. | `NOT_RUN` — source builds and artifacts required on all targets. |
| A14 / universal completion | Implementation, tests, evidence, docs, matrix, and supported-state promotion agree. | Any skipped target, stale evidence, undocumented API, or dirty worktree blocks merge. | Final row-by-row PR review and three-target custom-runtime CI. | RFC review record, complete PR diff, retained GitHub Actions artifacts. | Pending PR revision and hosted evidence. | `NOT_RUN` — final review required before merge. |
| A15 / eager Profile context | `openProfile` returns only after its persistent CEF context and NetworkContext are initialized; networking works before any Page, and the first Page reuses the same context. | Pre-Page `DIRECT` resolve; pre-Page fixed/PAC policy and resolution; context-init failure; absent private ABI; close without a Page; close/reopen same path; first Page observes the already-installed policy; engine close during open returns `desktop.profile-opening`. | Real custom CEF integration on macOS arm64, Windows x64, and Linux x64; native/Kotlin typed status tests and source/artifact capability-gate tests. | `KWebDesktopEngine.openProfile`; native `EnsureProfileContext` and Profile-context registry; `networkPolicyIntegrationTest`; `NativeStatusContractTest`; source/artifact verifier tests. | Pending hosted patched-runtime evidence; reviewed contract `.4`. | `NOT_RUN` — all target runtime evidence and final row review required. |

## Implementation-readiness review

- Historical reviewed revision: `2026-09-28.1`
- Reviewed revision: `2026-09-28.1` (this contract revision)
- Review pass: Codex contract/feasibility review, 2026-09-28 (same contributor; separate pass)
- Probe references: pinned CEF 151 source checkout under `.cef/chromium-151-arm64`; `ChromeContentBrowserClientCef::ConfigureNetworkContextParams`; `CefBrowserContext::GetNetworkContext`; Chromium `NetworkContextParams`, `ProxyConfigClient`, `SetAcceptLanguage`, `LookUpProxyForURL`, `ProxyInfo::ToPacString`, and `ProxyConfig::ProxyRules::ParseFromString`; CEF `CefRequestHandler`/`CefRequestContextHandler`/`CefResourceRequestHandler`.
- Decisions: profile-scoped proxy requires the custom Chromium/CEF patch series; fixed proxy and bypass input uses an explicitly bounded Chromium grammar with strict pre-validation; policy publication is staged after NetworkContext updates; observations redact URL user-info, use a platform-neutral completion status plus stable error IDs, and are terminal on buffer overflow; unrepresentable proxy chains fail instead of being rewritten; `netLog`, TLS, and network emulation remain outside this v1 objective; UA replacement after NetworkContext creation is an explicit typed rejection.
- Findings and disposition: CEF 151 has no Profile-level `SetProxy`/`ResolveProxy`; A13 adds the private patch series. Chromium's proxy parser is best-effort and silently discards invalid alternatives; A6 now requires strict pre-validation. Chromium exposes a canonical PAC serializer for `ProxyInfo`; A7 requires it or an explicit typed failure. These implementation risks have falsifiable acceptance scenarios and are feasible to probe with the pinned source/runtime. No unresolved required v1 behavior remains.
- Decision: **READY** — implementation and falsifiable acceptance are feasible on macOS arm64, Windows x64, and Linux x64 with the pinned custom runtime. This readiness decision does not count as implementation or runtime evidence. No stock-CEF fallback is permitted.

### Contract amendment review

- Reviewed revision: `2026-09-28.2`
- Review pass: Codex contract/feasibility amendment review, 2026-09-28 (same contributor; separate pass; historical `.1` decision preserved).
- New externally observable decisions: an unconfigured Profile NetworkContext starts with explicit Chromium `DIRECT`, never system proxy; `acceptLanguage = null` restores the original value captured from CEF's NetworkContext parameters; calls on CEF UI fail as `network.operation-wrong-thread`.
- Probe references: pinned `ChromeContentBrowserClientCef::ConfigureNetworkContextParams` calls the Chrome implementation before the KWeb hook; Chromium `NetworkContext::SetAcceptLanguage` updates `user_agent_settings_`; the Profile CEF hook now captures that pre-override value and feeds it back when an override is cleared. Native JSON validation is staged on the caller's non-UI IO path and only the prepared snapshot is applied on CEF UI.
- Falsifiable scenarios: default Profile request resolves `DIRECT` before configuration; explicit proxy rules replace that default; language replacement and reset are recorded by the real origin; a CEF-UI invocation returns the stable thread error; rejected proxy and UA replacements leave the prior config active.
- Decision: **READY** for revision `.2`; hosted patched-CEF compilation and macOS/Windows/Linux runtime evidence remain mandatory delivery gates and are not represented as passed here.

### Header-safety amendment review

- Reviewed revision: `2026-09-28.3`
- Review pass: Codex header-safety contract review, 2026-09-28 (same contributor; separate pass; `.1` and `.2` approvals preserved).
- New externally observable behavior: User-Agent and Accept-Language reject ASCII controls, including CR/LF/TAB/DEL, before policy activation; values over 1024 UTF-8 bytes report `network.policy.limit-exceeded` consistently in Kotlin and native validation.
- Probe references: `KWebDesktopNetworkJson.validate`, native `NetworkPolicyState::Prepare`, and the pinned CEF `cef_kweb_network_set_config` validation boundary. The CEF adapter independently rejects control bytes if called without the KWeb native validator.
- Falsifiable scenarios: Kotlin tests submit CR/LF in both values and an over-limit UA; real patched-runtime integration sends raw private-ABI policies to verify native revalidation rejects CR/LF before NetworkContext mutation.
- Decision: **READY** for revision `.3`; patched CEF compilation and real runtime evidence remain required delivery gates.

### Eager Profile-context amendment review

- Reviewed revision: `2026-09-28.4`
- Review pass: Codex CEF lifecycle feasibility and contract review, 2026-09-28 (same contributor; separate pass; historical `.1`–`.3` approvals preserved).
- New externally observable behavior: `openProfile` waits for the persistent CEF request context and Profile NetworkContext to initialize, without creating a Page; pre-Page policy configuration and proxy resolution are supported. The first Page reuses that context. Initialization failure is typed, a missing private network ABI fails as `network.runtime-capability-missing`, and engine close during initialization fails with `desktop.profile-opening`.
- Probe references: pinned CEF 151 `CefRequestContext::CreateContext` and `CefRequestContextHandler::OnRequestContextInitialized`; `CefRequestContextImpl::Initialize` attaches a persistent cache path to `CefBrowserContext`; `ChromeContentBrowserClientCef::ConfigureNetworkContextParams` installs the Profile proxy configuration; the private `cef_kweb_network_resolve_proxy` calls `CefBrowserContext::GetNetworkContext` and Chromium `NetworkContext::LookUpProxyForURL`. The open path resolves an ineligible sentinel host before returning and requires Chromium's exact `DIRECT` PAC result; this forces NetworkContext creation without issuing an HTTP request. The current `SessionRegistry::ProfileContexts` is keyed by the canonical Profile path and is reused by the first Page, providing the ownership seam for eager creation and release.
- Falsifiable scenarios: immediately after `openProfile`, a real pre-Page resolve returns `DIRECT`; fixed/PAC policies set before Page creation resolve and route through their declared endpoint; the subsequent first Page uses that same endpoint; close-before-Page releases the context; reopening the same path starts with a fresh direct context; initialization and missing-network-ABI failures return their stable typed errors.
- Decision: **READY** for revision `.4`; full eager-context implementation, patched CEF builds, and real macOS/Windows/Linux runs remain mandatory delivery gates.

## Non-goals

No general Kotlin HTTP client, renderer fetch bypass, VPN, packet capture,
TLS/certificate policy, download policy, network emulation, `netLog`,
unlimited observation retention, response-body capture, proxy credential store,
or compatibility promise for Electron's imperative callback API.
