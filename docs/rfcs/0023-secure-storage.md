# RFC 0023: OS credential-backed secure storage

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-secure-storage` KMP service
- Depends on: RFC 0002, RFC 0003, RFC 0030
- Electron migration surface: `safeStorage`
- Target mapping: `REWRITE`

## Objective

Store application secrets using an explicitly identified OS security backend,
asynchronous access, versioned ciphertext metadata, key rotation, and deletion.
An unavailable secure backend must fail; plaintext/basic-text encryption is
forbidden.

## Common KMP contract

Expose application/Profile-scoped secret IDs, bounded byte/string put/get/delete,
backend security facts, accessibility policy, biometric/user-presence option
where supported, and rotation status. Secret bytes use closeable zeroizable
buffers internally and never enter events, logs, reports, or renderer errors.

## Platform provider contract

Use Windows Credential Manager/DPAPI with declared scope, macOS Keychain with
stable signed application identity, and Linux Secret Service/libsecret through a
declared session. No hard-coded password, file keystore fallback, or automatic
backend switch is permitted.

## Acceptance

1. Packaged signed applications store/read/delete across restart and reject a
   different application identity/user.
2. Locked keychain, denied prompt, missing Linux secret service, corrupted
   ciphertext, key rotation, cancellation, and concurrent access are distinct.
3. Memory/log/artifact inspection finds no test secret after close; native
   handles and temporary buffers reach zero.
4. Renderer exposure is absent by default. If an application adapter declares
   it, IDs and payload sizes are allowlisted and a current grant/gesture applies.
5. Migration fixture rewrites async safe-storage use; synchronous methods remain
   blocked.
6. Backend facts identify protection semantics without exposing account names or
   secret metadata.

## Non-goals

No password manager UI, cross-device sync, plaintext Linux backend, generic
cryptography API, or promise that encryption protects against the same signed
application process.
