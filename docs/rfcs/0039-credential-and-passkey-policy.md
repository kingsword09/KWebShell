# RFC 0039: WebAuthn, passkey, and credential mediation policy

- Status: Proposed
- Priority: P2
- Owners: Chromium Profile credential adapter, application policy
- Depends on: RFC 0003, RFC 0009, RFC 0011
- Electron migration surface: authentication handlers and applications replacing Node credential helpers
- Target mapping: `DIRECT` web platform with Kotlin policy

## Objective

Support Chromium WebAuthn/passkeys and credential mediation through real
platform authenticators, explicit relying-party policy, native UI ownership, and
Profile lifecycle. Do not reimplement cryptography or expose credential secrets
as a native service.

## Contract

Kotlin policy can allow/deny relying-party IDs, authenticator classes, resident
credentials, enterprise attestation, and conditional mediation. Events expose
request status and redacted authenticator facts only. Chromium and the OS own
credential creation/assertion and user verification.

## Platform provider contract

Use Chromium integrations with Windows WebAuthn, macOS AuthenticationServices/
Keychain, and Linux FIDO2/desktop facilities in the pinned runtime. Missing
platform authenticator support is an explicit capability failure.

## Acceptance

1. Hardware-in-loop or certified virtual authenticators perform create/get for
   platform and roaming credentials on each advertised target.
2. Wrong RP/origin, insecure context, cancel, timeout, PIN/biometric denial,
   authenticator removal, counter behavior, and Profile close are covered.
3. Cross-origin frames and unapproved enterprise attestation are rejected.
4. No private key, credential secret, PIN, biometric data, or raw OS handle
   enters Kotlin events, bridge payloads, logs, or artifacts.
5. Migration fixture demonstrates replacing Node/native credential helpers with
   standards-based renderer WebAuthn and Kotlin host policy.
6. Package entitlements and authenticator runtime requirements are verified.

## Non-goals

No password manager, credential sync service, WebAuthn implementation in Kotlin,
automated biometric bypass, or generic safe-storage substitution.
