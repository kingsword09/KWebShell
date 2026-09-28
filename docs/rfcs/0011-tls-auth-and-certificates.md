# RFC 0011: TLS errors, HTTP authentication, and client certificates

- Status: Accepted
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium network adapter
- Depends on: RFC 0003, RFC 0010
- Electron migration surface: `certificate-error`, `login`, `select-client-certificate`
- Target mapping: `REWRITE`
- Contract revision: `2026-09-28.1`

## Objective

Provide bounded, one-shot host decisions for TLS certificate errors, HTTP or
proxy authentication, and client-certificate selection while preserving
Chromium certificate validation and the operating-system credential boundary.

The implementation is a stock-CEF vertical slice. It does not add a custom
Chromium NetworkContext, global certificate bypass, bundled credential store,
or renderer-visible challenge API.

## Implementation contract

### Challenge model

`KWebProfile.securityChallenges` is a cold `Flow<KWebSecurityChallenge>` with
no replay. Every challenge has a Profile-unique `requestId`, a `profileId`, an
optional `pageId`, a bounded `origin`, and a monotonic deadline represented in
the public value as `deadlineEpochMillis`. The native layer owns the CEF
callback; Kotlin receives only the typed value model.

```kotlin
public sealed interface KWebSecurityChallenge {
    public val requestId: Long
    public val profileId: String
    public val pageId: String?
    public val origin: String?
    public val deadlineEpochMillis: Long

    public data class Tls(
        override val requestId: Long,
        override val profileId: String,
        override val pageId: String?,
        override val origin: String?,
        override val deadlineEpochMillis: Long,
        public val requestUrl: String,
        public val error: KWebTlsError,
        public val certificate: KWebCertificateSummary,
    ) : KWebSecurityChallenge

    public data class HttpAuthentication(
        override val requestId: Long,
        override val profileId: String,
        override val pageId: String?,
        override val origin: String?,
        override val deadlineEpochMillis: Long,
        public val host: String,
        public val port: Int,
        public val realm: String?,
        public val scheme: String,
        public val isProxy: Boolean,
        public val attempt: Int,
    ) : KWebSecurityChallenge

    public data class ClientCertificate(
        override val requestId: Long,
        override val profileId: String,
        override val pageId: String?,
        override val origin: String?,
        override val deadlineEpochMillis: Long,
        public val host: String,
        public val port: Int,
        public val isProxy: Boolean,
        public val certificates: List<KWebCertificateSummary>,
    ) : KWebSecurityChallenge
}
```

`origin` is the request origin for server authentication and the page origin
for a browser-owned challenge. Proxy challenges use `null` when Chromium has
no page origin; the proxy host and `isProxy=true` remain explicit. No raw
renderer frame, CEF pointer, certificate handle, DER/PEM bytes, private key,
or credential is included in a challenge.

### Certificate summary and errors

`KWebCertificateSummary` contains only bounded public metadata:

```kotlin
public data class KWebCertificateSummary(
    public val sha256Fingerprint: String, // exactly 64 lowercase hex characters
    public val subject: String,
    public val issuer: String,
    public val serialNumber: String,
    public val validStartEpochMillis: Long?,
    public val validExpiryEpochMillis: Long?,
)
```

`subject`, `issuer`, and `serialNumber` are UTF-8 strings bounded to 512,
512, and 256 bytes respectively. Validity values are `null` only when the
underlying certificate has no encoded date. The fingerprint is SHA-256 of the
DER certificate and is the only stable certificate reference accepted by the
public response API.

`KWebTlsError` is a closed enum containing `UNKNOWN`, `COMMON_NAME_INVALID`,
`DATE_INVALID`, `AUTHORITY_INVALID`, `REVOKED`, `WEAK_SIGNATURE_ALGORITHM`,
`WEAK_KEY`, `PINNED_KEY_MISSING`, `INVALID`, and `OTHER`. Unknown Chromium
errors map to `OTHER` and retain the numeric Chromium error only in native
diagnostics, never as an unbounded public string.

### Decisions and responses

```kotlin
public sealed interface KWebSecurityDecision {
    public sealed interface Tls : KWebSecurityDecision {
        public data object DENY : Tls
        public data object ALLOW_ONCE : Tls
        public data class ALLOW_FOR_PROFILE_ORIGIN(
            public val expiresAtEpochMillis: Long,
        ) : Tls
    }

    public sealed interface HttpAuthentication : KWebSecurityDecision {
        public data object DENY : HttpAuthentication
        public class PROVIDE(
            public val username: String,
            public val password: String,
        ) : HttpAuthentication {
            override fun toString(): String = "PROVIDE(username=<redacted>, password=<redacted>)"
        }
    }

    public sealed interface ClientCertificate : KWebSecurityDecision {
        public data object DENY : ClientCertificate
        public data class SELECT(
            public val sha256Fingerprint: String,
        ) : ClientCertificate
    }
}

public enum class KWebSecurityChallengeOutcome {
    ACCEPTED,
    DENIED,
    TIMED_OUT,
    OWNER_CLOSED,
    STALE,
    REJECTED,
}

public data class KWebSecurityChallengeResult(
    public val requestId: Long,
    public val outcome: KWebSecurityChallengeOutcome,
)
```

`KWebProfile.respondToSecurityChallenge(requestId, decision)` is the only
response operation. The decision type must match the challenge type. TLS
`ALLOW_FOR_PROFILE_ORIGIN` stores an in-memory exception keyed by
`(Profile, normalized origin, certificate fingerprint)` and expires at the
requested time. The expiry must be in the future and no more than 24 hours
from decision time. Exceptions are removed on Profile close and are never
written to disk or shared with another Profile.

The authentication response is never retained after the one CEF callback.
The username and password each have a 512/4096 UTF-8 byte limit, reject NUL,
CR, and LF, and are absent from events, logs, errors, evidence, and
`toString()`. Authentication attempts are counted per `(Profile, host, port,
realm, scheme, isProxy, origin)` and the native adapter denies after three
failed challenge attempts without requiring a host-side loop.

Client-certificate selection succeeds only when the requested fingerprint is
one of the certificates offered by Chromium for that challenge. A selection
passes the opaque CEF certificate reference back to Chromium; the public API
never receives or stores a private key or OS certificate handle.

### Lifecycle, concurrency, and limits

- The decision deadline is five seconds for all three challenge kinds.
- A Profile may have at most 64 live challenges. A 65th challenge is denied
  immediately with `security.challenge.capacity-exceeded`.
- Challenge IDs are never reused during an Engine lifetime.
- A response is one-shot. A second response returns `STALE` with
  `security.challenge.already-resolved`; it cannot call CEF again.
- Profile, Page, or Engine close resolves every live challenge as
  `OWNER_CLOSED`, cancels the CEF callback, clears credentials and releases
  all native certificate/callback references before close returns.
- Native callbacks may arrive on CEF UI or IO threads. Kotlin collectors run
  on the desktop callback executor, and response calls marshal back to the
  required CEF thread. No Kotlin or renderer callback blocks a CEF thread.
- The challenge flow has a bounded 64-item buffer. Overflow is handled by
  immediate native denial, not by dropping a challenge or silently accepting
  it.

### Stable errors

The implementation exposes these stable error identifiers:

| Error | Meaning |
|---|---|
| `security.challenge.not-found` | The request ID is not live in this Profile. |
| `security.challenge.already-resolved` | A one-shot challenge was answered twice. |
| `security.challenge.deadline-expired` | The decision arrived after the five-second deadline. |
| `security.challenge.capacity-exceeded` | The Profile reached 64 live challenges. |
| `security.challenge.profile-closing` | The owner is closing; the challenge was denied. |
| `security.challenge.decision-invalid` | The decision type or bounded field is invalid. |
| `security.tls.expiry-invalid` | A scoped TLS exception has an invalid expiry. |
| `security.auth.retry-limit` | Chromium requested more than three attempts for one challenge key. |
| `security.client-certificate.not-offered` | The requested fingerprint was not offered by Chromium. |
| `security.credential.invalid` | A credential contains a forbidden character or exceeds its limit. |
| `security.challenge.callback-failed` | Chromium rejected or lost the native callback. |
| `security.store.unavailable` | Chromium returned no usable client certificate because its configured store/provider is unavailable. |

No error path falls back to global certificate acceptance, a bundled
credential file, a renderer prompt, or another Profile.

### Native CEF boundary and platform feasibility

Pinned stock CEF 151 provides the exact required hooks in
`include/cef_request_handler.h`:

- `CefRequestHandler::OnCertificateError` on the UI thread with
  `cef_errorcode_t`, URL, `CefSSLInfo`, and `CefCallback`;
- `CefRequestHandler::GetAuthCredentials` on the IO thread with origin,
  proxy flag, host, port, realm, scheme, and `CefAuthCallback`;
- `CefRequestHandler::OnSelectClientCertificate` on the UI thread with host,
  port, proxy flag, Chromium-pruned `X509CertificateList`, and
  `CefSelectClientCertificateCallback`.

`CefSSLInfo`/`CefX509Certificate` expose public certificate metadata and DER
bytes for fingerprinting. The implementation uses only the summary and keeps
DER bytes inside the native callback scope. CEF documents that the client
certificate list is already pruned by Chromium to trusted issuers and that
selecting a certificate lets Chromium perform the private-key operation.
Therefore Windows certificate stores, macOS Keychain/SecIdentity, and the
NSS/system provider selected by the pinned Chromium build remain Chromium
responsibilities. KWebShell does not implement a parallel store or signing
ABI. `cef_settings_t.ignore_certificate_errors` remains false.

The native C ABI carries opaque Profile challenge IDs, bounded UTF-8 challenge
JSON, and typed response JSON. It never carries a `CefRefPtr`, private key,
OS certificate handle, or credential after the callback has been settled.

### Electron migration

The migration matrix maps Electron `certificate-error`, `login`, and
`select-client-certificate` to `REWRITE`: each imperative callback is
translated into the typed Profile challenge flow. Electron response-body
access, arbitrary credential persistence, and renderer challenge handlers are
unsupported. Unknown or unclassified event names remain blocking findings.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / typed challenge contract | TLS, auth, client-certificate challenges and decisions are closed, bounded, versioned, and Profile-scoped. | Valid values; unknown error; malformed fingerprint; oversized origin/realm/credential; wrong decision type. | Kotlin contract tests, native JSON validation, ABI/FFM contract tests on Linux, macOS, Windows. | `KWebSecurityContract.kt`; native challenge validator; FFM binding contract tests. | Pending implementation evidence. | NOT_RUN |
| A2 / TLS summary and validation | Invalid TLS connections expose only the typed error and public certificate summary; Chromium validation remains enabled. | Valid, expired, hostname mismatch, revoked, self-signed, weak certificate; no raw DER/private data. | Controlled TLS server and real CEF `OnCertificateError` fixture on all three targets. | Native request handler; certificate summary/fingerprint mapper. | Pending hosted TLS evidence. | NOT_RUN |
| A3 / scoped TLS exceptions | Allow-once and Profile/origin/fingerprint/expiry-scoped allow behave exactly as declared. | Wrong Profile; wrong origin; wrong fingerprint; expired exception; close/reopen; global bypass probe. | Real repeated TLS handshakes and Profile isolation on all three targets. | In-memory exception registry; `respondToSecurityChallenge` tests. | Pending hosted TLS evidence. | NOT_RUN |
| A4 / HTTP and proxy authentication | Basic and digest auth challenges expose bounded metadata and one-shot typed decisions. | Server auth; proxy auth; success; deny; concurrent challenges; no renderer event. | Controlled auth servers and real CEF `GetAuthCredentials` fixture on all three targets. | Native auth callback adapter; challenge flow integration. | Pending hosted auth evidence. | NOT_RUN |
| A5 / credential and retry boundary | Credentials never cross observation/evidence boundaries and retry attempts terminate deterministically. | Wrong credentials; three retries; fourth retry; NUL/CR/LF; oversized username/password; close/timeout. | Auth fixture plus redaction and retry assertions on all three targets. | Redacted `PROVIDE`; retry registry; retained transcript without secrets. | Pending hosted auth evidence. | NOT_RUN |
| A6 / client-certificate selection | Chromium-offered public certificates are summarized and one selected fingerprint completes a real mutual-TLS handshake. | Empty list; multiple certificates; invalid selection; deny; wrong Profile/origin. | Real mTLS server with platform certificate provider on macOS, Windows, Linux. | Native `OnSelectClientCertificate`; opaque selection callback; mTLS fixture. | Pending hosted mTLS evidence. | NOT_RUN |
| A7 / private-key boundary | Private keys and OS certificate handles never cross Kotlin, C ABI, renderer, logs, or evidence. | Inspect ABI/JSON; token/keychain denial; store unavailable; selected cert signing remains Chromium-owned. | Native boundary inspection and real provider denial on all advertised targets. | C ABI inventory; certificate summary serializer; evidence redaction test. | Pending boundary evidence. | NOT_RUN |
| A8 / timeout and owner lifecycle | Timeout, Profile/Page/Engine close, and late callbacks deny exactly once and release all native owners. | Timeout at deadline; response race; close with live challenge; late CEF callback; duplicate response. | Native lifecycle stress and retained zero-owner evidence on all targets. | Challenge registry and callback owner; close/reopen integration fixture. | Pending lifecycle evidence. | NOT_RUN |
| A9 / concurrency and capacity | 64 live challenges are bounded; the 65th is denied; independent challenges do not cross-talk. | 64/65 burst; concurrent TLS/auth/client-cert challenges; slow collector; response from arbitrary threads. | Kotlin/native stress plus real CEF concurrent challenge fixture on all targets. | Bounded flow and native registry tests. | Pending stress evidence. | NOT_RUN |
| A10 / renderer and security ownership | Renderer cannot observe or resolve challenges; no global trust or credential fallback exists. | Forged bridge request; cross-origin page; process-wide bypass probe; bundled-store probe. | Source/bridge inspection and real negative CEF fixtures on all targets. | Exact-origin policy review; capability/migration tests. | Pending security evidence. | NOT_RUN |
| A11 / stock CEF feasibility | The advertised slice works with stock CEF 151 and its Chromium certificate providers, without custom NetworkContext ABI. | CEF header/API probe; native build; real TLS/auth/mTLS paths; store-unavailable failure. | Ordinary three-platform CEF CI and retained API probe. | `cef_request_handler.h` hooks and native adapter. | Pending hosted feasibility evidence. | NOT_RUN |
| A12 / Electron migration | `certificate-error`, `login`, and `select-client-certificate` map to typed Kotlin policy; unsupported surfaces block. | Supported event mapping; callback timeout; unknown event; credential persistence; renderer handler. | Migration matrix/golden tests and docs review. | `KWebElectronCapabilityMatrix`; migration README. | Pending migration evidence. | NOT_RUN |
| A13 / evidence and documentation | Transcripts retain only public fingerprints/statuses and bind the exact contract revision; docs and capability state agree. | Secret/private-key scan; stale digest; missing target; changed contract; retained artifact mismatch. | Governance recorder, strict check, complete PR review, `git diff --check`. | RFC matrix, `manifest.json`, evidence artifacts, docs. | Pending final evidence. | NOT_RUN |
| A14 / universal completion | Implementation, tests, docs, migration matrix, evidence, packaging, reviewed revision, and clean worktree agree. | Any missing target, skipped required test, stale evidence, undocumented breaking API, or dirty tree blocks merge. | Final row-by-row review and all required CI jobs. | Final review record and complete PR diff. | Pending final review. | NOT_RUN |

## Implementation-readiness review

- Reviewed revision: `2026-09-28.1` (this contract revision before implementation).
- Review pass: Codex stock-CEF feasibility review, 2026-09-28 (same contributor
  as implementation, identified separately from implementation work).
- Decisions: use one Profile-scoped bounded challenge flow with typed TLS,
  authentication, and client-certificate decisions; keep credentials and all
  private-key/OS-store state out of common Kotlin, the C ABI, and the renderer;
  deny on timeout, close, capacity, invalid response, or retry limit.
- Feasibility evidence: pinned CEF 151 headers expose
  `OnCertificateError`, `GetAuthCredentials`, and
  `OnSelectClientCertificate`; `CefSSLInfo`/`CefX509Certificate` provide the
  public summary/fingerprint inputs; CEF explicitly states that Chromium
  prunes the client-certificate list and performs the private-key operation
  after selection. No custom CEF patch or private NetworkContext ABI is
  required for this slice.
- Platform decision: Windows certificate store, macOS Keychain/SecIdentity,
  and Linux NSS/system provider remain Chromium-owned. Store/provider
  unavailability is an explicit failure; no bundled credential fallback is
  added.
- Decision: **READY** for implementation against stock CEF 151. Real TLS,
  auth, mutual-TLS, timeout, retry, and provider-denial evidence is mandatory
  before merge.

## Non-goals

No global certificate-verification bypass, renderer-visible credentials or
private keys, persistent trust without Profile/origin/fingerprint scope and
expiry, bundled credential-store fallback, automatic acceptance, arbitrary
certificate import/export, raw OS certificate handles, response-body capture,
or promise of Electron callback identity/semantics.
