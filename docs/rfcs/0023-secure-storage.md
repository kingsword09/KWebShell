# RFC 0023: OS credential-backed secure storage

- Status: Accepted
- Priority: P0
- Owners: new `kweb-service-secure-storage` KMP service
- Depends on: RFC 0002, RFC 0003, RFC 0030
- Electron migration surface: `safeStorage`
- Target mapping: `REWRITE`

## Objective and scope

Publish one application-scoped secret store for Windows x64, macOS arm64, and
Linux x64 that keeps application secrets in the operating system credential
facility, with an explicitly identified backend, application and Profile
ownership, a declared accessibility policy, optional user presence, versioned
ciphertext metadata, rotation, and deletion.

One implementation objective delivers:

1. the typed secret identifier, scope, accessibility, value, metadata, capability,
   rotation, and error contract with bounded values and closeable zeroizable
   buffers;
2. exactly one provider per target: macOS Keychain generic-password items,
   Windows Credential Manager generic credentials, and the Linux Secret Service
   through libsecret — each advertising only the protection semantics it provides;
3. a versioned ciphertext envelope so a corrupted or foreign record is detected
   instead of decrypted, and a declared accessibility policy that makes an older
   record report as stale for rotation;
4. the opt-in renderer adapter that exposes only an application-declared metadata
   allowlist with a current grant and gesture, and never a secret byte — the
   default service descriptor has no renderer surface at all;
5. the closed Electron `safeStorage` mapping, real target evidence, documentation,
   and the final acceptance review.

## Non-goals

No password-manager UI, no cross-device sync, no plaintext or file-keystore Linux
backend, no generic cryptography API, no automatic backend switch, and no promise
that encryption protects against the same signed application process.

## Common implementation contract

```kotlin
public interface KWebSecureStorage : KWebNativeService {
    public suspend fun capabilities(): KWebSecureStorageCapabilities
    public suspend fun put(request: KWebSecretPutRequest): KWebSecretMetadata
    public suspend fun get(id: KWebSecretRef): KWebSecretValue
    public suspend fun delete(id: KWebSecretRef): Boolean
    public suspend fun exists(id: KWebSecretRef): Boolean
    public suspend fun rotate(id: KWebSecretRef): KWebSecretMetadata
    public suspend fun rotationStatus(id: KWebSecretRef): KWebSecretRotationStatus
}
```

The v1 descriptor is version `1.0.0`, service id `secure-storage`, scope
`APPLICATION`, and publishes `capabilities`, `put`, `get`, `delete`, `exists`,
`rotate`, and `rotation-status` as host operations. It declares **no renderer
operation**: a secret is never renderer-reachable through the service descriptor.

### Values and bounds

| Value | v1 rule |
|---|---|
| secret id | opaque ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}` |
| scope | `APPLICATION` (one secret per application) or `PROFILE` (one secret per application and profile) |
| owner id | required and non-blank for `PROFILE`; forbidden for `APPLICATION` |
| secret bytes | 1 byte through 4096 bytes |
| accessibility | `WHEN_UNLOCKED`, `WHEN_UNLOCKED_THIS_DEVICE_ONLY`, `AFTER_FIRST_UNLOCK`, `AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY` |
| backend | `MACOS_KEYCHAIN`, `WINDOWS_CREDENTIAL_MANAGER`, `LINUX_SECRET_SERVICE` |
| envelope version | `1`; a record written by another version is stale, not decrypted |
| generation | whole count from 1, incremented by each rotation |

A protection semantic the backend does not implement is absent from the
capability report and fails typed on use; it is never mapped onto a weaker one.
Windows Credential Manager and the Linux Secret Service do not publish an
accessibility distinction, so those providers advertise an empty accessibility
set and the declared policy is recorded in the envelope instead.

### Lifecycle, concurrency, and limits

- `put` is atomic per identifier: it either stores the whole secret and returns
  its metadata, or fails typed and stores nothing.
- `put` on an existing identifier replaces it and starts a new generation.
- `get` returns a closeable value; closing it zeroes the buffer exactly once, and
  the value is unusable afterwards.
- `delete` is idempotent per identifier; deleting an unknown identifier returns
  `false` rather than failing.
- `rotate` re-writes the stored secret through the current backend and declared
  accessibility, increments the generation, and leaves the secret unchanged.
- Concurrent operations on distinct identifiers never interfere; two operations
  on one identifier serialize, and the later `put` wins.
- `close` releases the platform session, zeroizes every staging buffer, and
  leaves no credential handle behind; a provider failure while closing is reported
  once as a sticky failure.

### Errors, renderer, and migration policy

- Every rejection has a stable `secure-storage.*` code; there is no hidden
  fallback and no weaker backend is ever selected.
- The service descriptor exposes no renderer operation. An application may opt in
  to the separate metadata adapter, which returns an application-declared
  allowlist of identifiers with their scope and byte size only. Secret bytes are
  never renderer-visible, an undeclared identifier is denied, and a missing
  current grant or gesture is denied. An observation never reads a secret.
- The Electron `safeStorage` mapping is `REWRITE`: `isEncryptionAvailable`,
  `encryptString`, and `decryptString` rewrite to the typed asynchronous
  operations, while `encryptStringSync`, `decryptStringSync`, and
  `getSelectedStorageBackend` remain blocked because the service is asynchronous
  and reports typed backend facts instead of a raw backend name.

| Condition | Stable error |
|---|---|
| malformed identifier | `secure-storage.id-invalid` |
| empty, over-long, or absent value | `secure-storage.value-invalid` |
| missing or forbidden owner | `secure-storage.scope-invalid` |
| accessibility the backend does not implement | `secure-storage.accessibility-unsupported` |
| user presence the backend cannot require | `secure-storage.user-presence-unsupported` |
| user presence was required and refused | `secure-storage.user-presence-denied` |
| the declared credential backend is missing | `secure-storage.backend-unavailable` |
| the credential session or service is missing | `secure-storage.service-unavailable` |
| the store is locked and cannot prompt | `secure-storage.locked` |
| the user cancelled a prompt | `secure-storage.cancelled` |
| the record is absent | `secure-storage.secret-not-found` |
| the record is not a valid envelope | `secure-storage.corrupted` |
| rotation is unsupported by the backend | `secure-storage.rotation-unsupported` |
| native credential failure | `secure-storage.native-failed` |
| use after close | `secure-storage.owner-closed` |

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| macOS arm64 | `secure-storage.macos.keychain` | `SecItemAdd`/`SecItemCopyMatching`/`SecItemUpdate`/`SecItemDelete` with `kSecClassGenericPassword`, the application identity as the service and the scope-qualified identifier as the account, `kSecAttrAccessible*` for the declared accessibility, and `SecAccessControlCreateWithFlags(kSecAccessControlUserPresence)` when user presence is required; `errSecItemNotFound`, `errSecDuplicateItem`, `errSecUserCanceled`, and `errSecAuthFailed` map to their own typed codes |
| Windows x64 | `secure-storage.windows.credential-manager` | `CredWriteW`/`CredReadW`/`CredDeleteW` with `CRED_TYPE_GENERIC` and `CRED_PERSIST_LOCAL_MACHINE`, the scope-qualified identifier as the target name, and the versioned envelope in `CredentialBlob`; `ERROR_NOT_FOUND` and `ERROR_NO_SUCH_LOGON_SESSION` map to their own typed codes; the accessibility distinction is a typed absence |
| Linux x64 | `secure-storage.linux.secret-service` | `secret_password_store_sync`/`secret_password_lookup_sync`/`secret_password_clear_sync` with a declared schema, the default collection, and the scope-qualified identifier as an attribute; a missing session bus or Secret Service fails typed with `secure-storage.service-unavailable` instead of writing a file; the accessibility distinction is a typed absence |

### Evidence lifecycle

The RFC binds `kweb-service-secure-storage/src`,
`kweb-service-secure-storage/native`, `kweb-service-secure-storage/build.gradle.kts`,
the migration kit, the governance service catalog, the CI planner metadata, and
`settings.gradle.kts` in `docs/rfcs/evidence/contracts.json`. Records expire when
the service version, the C ABI, a provider identity, or the pinned CEF/Chromium
identity changes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Identifiers, scopes, values, metadata, and rotation status are bounded and immutable, and an invalid value fails typed before a provider sees it. | Malformed and boundary identifiers, empty and over-long values, profile scope without an owner, application scope with an owner, unknown accessibility. | Common contract tests. | `KWebSecureStorage.kt`; `KWebSecureStorageContractTest`. | Local and hosted records. | NOT_RUN |
| A2 / platform providers | One provider per target publishes exactly the backend and protection semantics it implements. | macOS Keychain, Windows Credential Manager, Linux Secret Service, missing backend. | Provider structure tests on all targets plus the capability report. | Provider snapshots and per-target structure tests. | Provider transcripts. | NOT_RUN |
| A3 / store, read, delete across restart | A stored secret is readable after the service is reopened, and deleting it removes it. | Round trip, reopen, delete then read, delete twice, replace an existing identifier. | Provider tests with a real reopen. | Round-trip code and provider tests. | Round-trip transcripts. | NOT_RUN |
| A4 / identity and scope isolation | A secret written by one application identity or profile is not visible to another. | Different application id, different profile owner, same id in two profiles. | Provider tests plus the native identity fixture. | Entry key derivation and provider tests. | Isolation transcripts. | NOT_RUN |
| A5 / versioned envelope and corruption | A record that is not a valid envelope is rejected as corrupted instead of decrypted, and an older envelope version is stale for rotation. | Valid envelope, foreign bytes, truncated record, stale generation, current generation. | Provider tests with a planted invalid record. | Envelope codec and provider tests. | Envelope transcripts. | NOT_RUN |
| A6 / rotation | Rotation re-writes the record through the current backend and declared accessibility, increments the generation, and leaves the secret unchanged. | Rotate an existing record, rotate an unknown record, rotation status before and after a policy change. | Provider tests plus the reopen fixture. | Rotation code and provider tests. | Rotation transcripts. | NOT_RUN |
| A7 / failure distinctions | A locked store, a denied or cancelled prompt, a missing Linux service, and a missing backend are distinct typed outcomes. | Locked keychain, cancelled prompt, absent session bus, absent Secret Service. | Provider tests with the facility removed. | Typed error mapping. | Failure transcripts. | NOT_RUN |
| A8 / zeroization and redaction | No test secret survives in a buffer, a log, or a retained artifact after close. | Read then close, release a native value buffer, inspect the retained report. | Native zeroization assertion plus the integration report scan. | Zeroizing buffers and the report writer. | Zeroization transcript. | NOT_RUN |
| A9 / renderer authority | The default descriptor has no renderer operation, and the opt-in adapter exposes only an application-declared metadata allowlist with a grant and gesture. | Descriptor shape, granted metadata read, undeclared identifier, missing grant, child frame, secret byte request. | Bridge contract tests. | Metadata adapter and its tests. | Bridge transcript. | NOT_RUN |
| A10 / no fallback | A missing backend or service fails typed and never falls back to a file keystore or a weaker protection. | Missing session bus, missing Secret Service, unsupported accessibility, unsupported user presence. | Provider tests with the facility removed. | Typed error mapping. | Provider transcripts. | NOT_RUN |
| A11 / migration | The Electron `safeStorage` mapping rewrites the asynchronous operations and blocks the synchronous ones and the raw backend name. | Each declared method, both synchronous methods, the backend name, unknown method. | Inventory, golden, and migration tests. | Matrix row and focused tests. | Migration compatibility report. | NOT_RUN |
| A12 / packaging and docs | Native libraries, generated output, and documentation agree. | Native package contents and packaged resource scan. | Gradle package verification plus governance. | Native runtime ZIP and package verification tasks. | Package digest report. | NOT_RUN |
| A13 / evidence redaction | Retained evidence carries identifiers, sizes, and backend facts, never a secret value or a credential handle. | Fixture report contents, the scan for the planted test secret. | Evidence collector and retained artifacts. | `aggregate-rfc-evidence.sh` and the CI planner. | Retained transcripts. | NOT_RUN |
| A14 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | NOT_RUN |
| A15 / deferred items | No password-manager UI, no sync, no plaintext backend, no generic cryptography API, no automatic backend switch. | Capability review and provider declarations. | Public API and provider review. | This RFC's non-goals and descriptor. | Capability transcript. | NOT_RUN |

## Contract review record

- Reviewed contract revision: `c97da3cf` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation (real probes, not assumptions):
  - macOS arm64: `SecItemAdd`/`SecItemCopyMatching`/`SecItemDelete` against
    `kSecClassGenericPassword` returned `errSecSuccess` for the full add, copy,
    and delete round trip with the value read back exactly; the
    `kSecAttrAccessible*` and `kSecAttrAccessControl` constants are reachable with
    the pinned SDK, and `errSecItemNotFound`, `errSecDuplicateItem`,
    `errSecUserCanceled`, and `errSecAuthFailed` are distinct status codes.
  - Linux: `libsecret` builds against the pinned `libsecret-1` headers, and
    without a session bus the store, lookup, and clear calls each fail with a
    GError instead of touching the filesystem, which is the typed
    `secure-storage.service-unavailable` boundary rather than a plaintext
    fallback. With a real Secret Service (`gnome-keyring` on a private session
    bus) the store, lookup, and clear round trip returns the stored value.
  - Windows: the declared facilities are `CredWriteW`/`CredReadW`/`CredDeleteW`
    with `CRED_TYPE_GENERIC`; there is no local Windows host, so the provider is
    verified by the hosted Windows run.
- Open questions settled:
  - The service descriptor declares no renderer operation, and renderer metadata
    exposure is a separate opt-in adapter over an application-declared allowlist,
    so a page can never reach a secret byte.
  - The accessibility distinction is a macOS capability; Windows and Linux record
    the declared policy in the versioned envelope and report the semantic as a
    typed absence instead of claiming it.
  - `safeStorage`'s synchronous methods stay blocked because the service is
    asynchronous, and the raw backend name is replaced by typed backend facts.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: to be recorded when the implementation PR is
  complete; this section is filled by the same pull request before merge.
- Review pass: Delta agent, same contributor as the implementation; recorded
  honestly and not an independent-person approval.
- Decision: `BLOCKED` until the implementation, real three-target evidence, and the
  row-by-row acceptance are complete in this focused pull request.
