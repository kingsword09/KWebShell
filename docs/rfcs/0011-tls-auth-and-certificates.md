# RFC 0011: TLS errors and client-certificate selection

- Status: Implementing
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium network adapter
- Depends on: RFC 0003, RFC 0010
- Electron migration surface: `certificate-error`, `select-client-certificate`
- Target mapping: `REWRITE`

## Objective

Provide bounded, one-shot host decisions for TLS certificate errors and
Chromium-offered client-certificate selection while preserving Chromium
certificate validation and the operating-system certificate/private-key
boundary.

This deliverable uses stock CEF 151. It does not add a custom Chromium
NetworkContext, a global certificate bypass, a bundled certificate store, or a
renderer-visible challenge API.

HTTP and proxy authentication are deliberately outside this implementation
objective. A stock CEF 151 feasibility audit showed that real browser 401
responses do not reach `CefRequestHandler::GetAuthCredentials`; that behavior
is retained as a blocking follow-up instead of being exposed as a partial API.

Contract revision: `2026-09-29.1`.

## Implementation contract

### Challenge model

`KWebProfile.securityChallenges` is a cold `Flow<KWebSecurityChallenge>` with
no replay. Every challenge has a Profile-unique `requestId`, a `profileId`, an
optional `pageId`, a bounded `origin`, and a five-second deadline represented
publicly as `deadlineEpochMillis`. The native layer owns the CEF callback;
Kotlin receives only the typed value model.

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

No raw renderer frame, CEF pointer, certificate handle, DER/PEM bytes, or
private key is included in a challenge. An empty `certificates` list is a
valid provider result and can only be denied; a selected fingerprint must be
one of the certificates offered in that challenge.

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
512, and 256 bytes respectively. The fingerprint is SHA-256 of the DER
certificate and is the only stable certificate reference accepted by the
response API.

`KWebTlsError` is a closed enum containing `UNKNOWN`, `COMMON_NAME_INVALID`,
`DATE_INVALID`, `AUTHORITY_INVALID`, `REVOKED`, `WEAK_SIGNATURE_ALGORITHM`,
`WEAK_KEY`, `PINNED_KEY_MISSING`, `INVALID`, and `OTHER`. Unknown Chromium
errors map to `OTHER`.

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

    public sealed interface ClientCertificate : KWebSecurityDecision {
        public data object DENY : ClientCertificate
        public data class SELECT(
            public val sha256Fingerprint: String,
        ) : ClientCertificate
    }
}

public enum class KWebSecurityChallengeOutcome(public val id: String) {
    ACCEPTED("accepted"),
    DENIED("denied"),
    TIMED_OUT("timed-out"),
    OWNER_CLOSED("owner-closed"),
    STALE("stale"),
    REJECTED("rejected"),
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

Client-certificate selection passes the opaque CEF certificate reference back
to Chromium. Chromium performs the private-key operation; KWebShell never
receives or stores a private key or OS certificate handle.

The selection callback path is implemented, but the public capability is not
considered complete until a selected provider certificate completes a real
mutual-TLS handshake on every advertised target. An isolated macOS probe now
reaches the real Chromium candidate list and completes the handshake through a
temporary Keychain after granting the JVM process the explicit key partition
ACL (`apple-tool:,apple:,codesigning:`). The retained local probe proves the
macOS provider path only; it is not hosted three-target support evidence.

### Lifecycle, concurrency, and limits

- A Profile may have at most 64 live challenges; excess callbacks are denied.
- Challenge IDs are never reused during an Engine lifetime.
- A response is one-shot; a second response is stale and cannot call CEF again.
- Profile, Page, or Engine close cancels every live CEF callback and releases
  all native certificate/callback references before close returns.
- Native callbacks arrive on the CEF UI thread for both advertised hooks.
  Kotlin collectors run on the desktop callback executor, and response calls
  marshal back to the required CEF thread.
- The challenge flow has a bounded 64-item buffer. Overflow is handled by
  immediate native denial, never by silent acceptance or dropping.

### Stable errors

The implemented slice exposes these stable error identifiers:

| Error | Meaning |
|---|---|
| `security.challenge.not-found` | The request ID is not live in this Profile. |
| `security.challenge.already-resolved` | A one-shot challenge was answered twice. |
| `security.challenge.deadline-expired` | The decision arrived after the five-second deadline. |
| `security.challenge.capacity-exceeded` | The Profile reached its live-challenge limit. |
| `security.challenge.profile-closing` | The owner is closing; the challenge was denied. |
| `security.challenge.decision-invalid` | The decision type or bounded field is invalid. |
| `security.tls.expiry-invalid` | A scoped TLS exception has an invalid expiry. |
| `security.client-certificate.not-offered` | The requested fingerprint was not offered by Chromium. |
| `security.challenge.callback-failed` | Chromium rejected or lost the native callback. |

No error path falls back to global certificate acceptance, a bundled
certificate, a renderer prompt, or another Profile.

### Native CEF boundary and platform feasibility

Pinned stock CEF 151 provides the required hooks in
`include/cef_request_handler.h`:

- `CefRequestHandler::OnCertificateError` on the UI thread with
  `cef_errorcode_t`, URL, `CefSSLInfo`, and `CefCallback`;
- `CefRequestHandler::OnSelectClientCertificate` on the UI thread with host,
  port, proxy flag, Chromium-pruned `X509CertificateList`, and
  `CefSelectClientCertificateCallback`.

`CefSSLInfo` and `CefX509Certificate` provide the public summary and
fingerprint inputs. Chromium owns Windows certificate stores, macOS
Keychain/SecIdentity, and the Linux provider selected by the pinned Chromium
build. KWebShell does not implement a parallel store or signing ABI, and
`cef_settings_t.ignore_certificate_errors` remains false.

The native C ABI carries only opaque Profile challenge IDs, bounded UTF-8
challenge JSON, and typed response JSON. It never carries a `CefRefPtr`,
private key, OS certificate handle, or certificate bytes across the ABI.

### Electron migration

While this RFC is `Implementing`, both `certificate-error` and
`select-client-certificate` remain `UNSUPPORTED` in the published migration
matrix. Their typed Profile mapping is an implementation target, not a support
claim, until the RFC is promoted with hosted evidence. Electron `login` is also
explicitly `UNSUPPORTED`: HTTP/proxy authentication requires a follow-up
contract after the stock CEF 151 callback feasibility blocker is resolved.
Electron response-body access, arbitrary credential persistence, and renderer
challenge handlers are unsupported.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / typed challenge contract | TLS and client-certificate challenges and decisions are closed, bounded, versioned, and Profile-scoped. | Valid values; unknown kind; malformed fingerprint; oversized origin; wrong decision type; empty certificate list. | Kotlin contract tests, native JSON validation, ABI/FFM contract tests on Linux, macOS, Windows. | `KWebSecurityContract.kt`; `KWebDesktopSecurity.kt`; FFM binding contract tests. | Local JVM/native gates passed; hosted three-target evidence is not retained. | BLOCKED — required hosted target evidence is missing. |
| A2 / TLS summary and validation | Invalid TLS connections expose only typed error and public certificate summary while Chromium validation remains enabled. | Host mismatch/self-signed certificate; no DER/private key; allow-once and deny. | Real CEF `OnCertificateError` fixture on all three targets. | `SecurityChallengeRegistry::OnCertificateError`; TLS integration fixture. | Local macOS stock-CEF TLS integration passed; hosted Linux/Windows artifacts are pending. | BLOCKED — not all advertised targets are verified. |
| A3 / scoped TLS exceptions | Allow-once and Profile/origin/fingerprint/expiry-scoped allow behave exactly as declared. | Reuse; wrong origin/fingerprint; expired scope; Profile close; no global bypass. | Repeated real TLS handshakes on all three targets. | TLS exception registry and integration assertions. | Local macOS stock-CEF scoped reuse passed; hosted Linux/Windows artifacts are pending. | BLOCKED — not all advertised targets are verified. |
| A4 / HTTP and proxy authentication | HTTP/proxy authentication is not advertised by this deliverable. | Real 401 and proxy probes are retained as a blocker; no public credential API exists. | Feasibility audit and source review; follow-up objective required. | No `HttpAuthentication`, `GetAuthCredentials`, or credential ABI. | Stock CEF 151 audit retained in review notes; no support evidence. | NOT_APPLICABLE — explicitly deferred after the stock CEF callback blocker. |
| A5 / credential and retry boundary | Credential handling and retry semantics are not advertised by this deliverable. | No credentials in Kotlin, ABI, logs, or evidence; Electron `login` is unsupported. | Source review and migration matrix test. | Auth surface removed; `login` matrix row is `UNSUPPORTED`. | No credential-bearing artifact is retained. | NOT_APPLICABLE — deferred with A4. |
| A6 / client-certificate selection | A Chromium-offered public certificate is summarized and a selected fingerprint is passed to Chromium for a real mutual-TLS handshake. | Empty list; multiple certificates; invalid selection; deny; wrong Profile/origin. | Real mTLS fixture with Chromium certificate provider on Linux, macOS, and Windows. | `SecurityChallengeRegistry::OnSelectClientCertificate`; isolated provider probe. | macOS candidate discovery, selection, private-key acquisition, and server-side client fingerprint verification passed in the isolated probe; hosted Linux/Windows mTLS artifacts do not exist. | BLOCKED — macOS is proven locally, but selection is not supported until all advertised targets are hosted-verified. |
| A7 / private-key boundary | Private keys and OS certificate handles never cross Kotlin, C ABI, renderer, logs, or evidence. | Inspect ABI/JSON; selected signing remains Chromium-owned; provider denial is explicit. | Native boundary inspection and real provider test on all advertised targets. | Certificate summary serializer; C ABI inventory; redaction assertions; isolated provider probe. | macOS provider signing passed without private-key data in Kotlin/ABI/evidence; Linux/Windows provider-boundary evidence is incomplete. | BLOCKED — the required all-target provider verification is unavailable. |
| A8 / timeout and owner lifecycle | Timeout, Profile/Page/Engine close, and late callbacks deny exactly once and release native owners. | Deadline race; close with live challenge; late callback; duplicate response. | Native lifecycle stress and real close evidence on all targets. | Challenge registry and desktop owner maps. | Local unit/FFM gates passed; hosted lifecycle evidence is pending. | BLOCKED — required hosted target evidence is missing. |
| A9 / concurrency and capacity | Live challenges are bounded and independent challenges do not cross-talk. | 64/65 burst; concurrent TLS/client-cert callbacks; slow collector; arbitrary response thread. | Kotlin/native stress plus real CEF fixture on all targets. | Bounded stream and native registry. | Local contract/native gates passed; real three-target challenge stress evidence is pending. | BLOCKED — required hosted target evidence is missing. |
| A10 / renderer and security ownership | Renderer cannot observe or resolve challenges; no global trust or certificate-store fallback exists. | Forged bridge request; cross-origin page; process-wide bypass probe; bundled-store probe. | Source/bridge review and real negative TLS fixture on all targets. | Exact-origin policy and native-only callback ownership. | Local source review and macOS TLS negative path passed; complete target evidence is pending. | BLOCKED — required hosted target evidence is missing. |
| A11 / stock CEF feasibility | The advertised TLS/client-certificate slice works with stock CEF 151 without custom NetworkContext ABI. | CEF header/API probe; native build; real TLS/mTLS path; provider denial. | Ordinary three-platform CEF CI and retained API probe. | Stock `cef_request_handler.h` hooks and native adapter. | Native header/build probes and the macOS provider-backed mTLS probe passed; Linux/Windows provider evidence is absent. | BLOCKED — stock CEF feasibility is proven on macOS only until the hosted matrix completes. |
| A12 / Electron migration | Incomplete RFC 0011 security surfaces remain explicitly unsupported; no migration support claim is published before hosted promotion. | Matrix rows for `certificate-error`, `select-client-certificate`, and `login` are `UNSUPPORTED`; no credential persistence or renderer handler. | Migration matrix tests and docs review. | `KWebElectronCapabilityMatrix`; RFC scope notes. | `:kweb-electron-migration:jvmTest` passed; all three security rows are explicitly unsupported while this RFC is blocked. | PASS — migration does not overclaim an incomplete capability. |
| A13 / evidence and documentation | Transcripts retain only public fingerprints/statuses and bind the exact contract revision; docs and capability state agree. | Secret/private-key scan; stale digest; missing target; changed contract; artifact mismatch. | Governance recorder, strict check, complete PR review, `git diff --check`. | RFC matrix, manifest, retained evidence, migration docs. | Formal RFC 0011 evidence was intentionally not recorded; client-certificate evidence is incomplete. | BLOCKED — no support manifest may be published. |
| A14 / universal completion | Implementation, tests, docs, evidence, packaging, reviewed revision, and clean worktree agree. | Any missing target, skipped required test, stale evidence, or undocumented break blocks merge. | Final row-by-row review and all required CI jobs. | Final review record and complete PR diff. | The mTLS/provider blocker and missing hosted evidence remain. | BLOCKED — the RFC cannot merge or move to RFC 0012. |

## Implementation-readiness review

- Reviewed revision: `2026-09-29.1` (reduced TLS/client-certificate contract).
- Review pass: Codex stock-CEF feasibility review, 2026-09-29 (same
  contributor as implementation, identified separately from implementation
  work).
- Decision: **NOT_READY** for the full TLS and client-certificate objective.
  The contract uses only stock CEF 151 hooks and keeps certificate stores,
  private keys, and signing in Chromium, but the provider-backed mTLS path has
  not yet been verified.
- Blocking finding carried forward: HTTP/proxy authentication is **NOT_READY**
  for a separate follow-up. The real 401 audit did not produce
  `GetAuthCredentials` callbacks in stock CEF 151, so no authentication API is
  published in this RFC.
- Required before implementation can be accepted for merge: a reproducible
  provider-backed mTLS handshake and provider-denial behavior on Linux, macOS,
  and Windows; lifecycle/negative tests; migration and boundary review. The
  current implementation remains on the topic branch while this feasibility
  finding is unresolved.

## Non-goals

No global certificate-verification bypass, HTTP/proxy credential handling,
renderer-visible credentials or private keys, persistent trust without
Profile/origin/fingerprint scope and expiry, bundled certificate-store
fallback, automatic acceptance, arbitrary certificate import/export, raw OS
certificate handles, response-body capture, or promise of Electron callback
identity/semantics.
