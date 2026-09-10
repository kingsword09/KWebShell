# RFC 0011: TLS errors, HTTP authentication, and client certificates

- Status: Proposed
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium network adapter
- Depends on: RFC 0003, RFC 0010
- Electron migration surface: certificate-error, login, select-client-certificate events
- Target mapping: `REWRITE`

## Objective

Provide bounded host decisions for TLS certificate errors, HTTP authentication,
and client-certificate selection while preserving Chromium validation and OS
credential boundaries.

## Contract

Define typed challenge IDs, origin, certificate summaries/fingerprints, error
codes, credential requests, client-certificate references, decision deadlines,
and one-shot responses. Private keys and raw OS certificate handles never cross
common Kotlin or the renderer bridge.

## Platform provider contract

Chromium supplies connection challenges. Certificate enumeration/signing uses
the declared Windows certificate store, macOS Keychain/SecIdentity, and Linux
NSS/system integration selected by the pinned Chromium build. Store
unavailability is not replaced by a bundled credential file.

## Acceptance

1. Controlled roots exercise valid, expired, hostname-mismatched, revoked,
   self-signed, and client-auth TLS connections.
2. Allow exceptions are Profile/origin/fingerprint scoped, expire explicitly,
   and never apply globally.
3. Basic/digest/proxy auth covers success, cancellation, retries, credential
   redaction, and concurrent challenges.
4. Client-certificate selection signs a real handshake without exposing the
   private key and handles token/keychain denial.
5. Renderer access to challenges is absent; migration moves Electron handlers to
   Kotlin policy code.
6. Timeout and owner close deny the challenge deterministically and release all
   native certificate references.

## Evidence

Retain handshake results, public certificate fingerprints, decision transcripts,
and zero live challenge/store handles; never retain credentials or private keys.

## Non-goals

No global certificate-verification bypass, renderer-visible credentials or
private keys, persistent trust without scope/expiry, bundled credential-store
fallback, or automatic acceptance.
