# KWebShell Secure Storage Service

`kweb-service-secure-storage` implements the RFC 0023 typed secret contract for
the macOS Keychain, the Windows Credential Manager, and the Linux Secret Service.
Kotlin owns identifier and scope policy, the accessibility contract, the envelope
version, and rotation status; each advertised target contributes one native
provider behind a versioned C ABI and the JDK 25 FFM binding.

The published v1 surface contains:

- application- and Profile-scoped secrets with a bounded identifier and a bounded
  value of 1 through 4096 bytes;
- a declared accessibility policy, optional user presence where the backend
  implements it, and a capability report that publishes only the semantics the
  target actually provides;
- versioned ciphertext metadata with a generation, so a foreign or truncated
  record is reported corrupted instead of decrypted and an older envelope is stale
  for rotation;
- closeable zeroizable secret values, and a rotation status that reports a record
  whose policy the backend no longer implements; and
- typed `secure-storage.*` errors for every rejection, with no file-keystore
  fallback and no automatic backend switch.

The service descriptor declares **no renderer operation**: a secret is never
renderer-reachable. An application may opt in to the separate metadata adapter,
which returns only an application-declared allowlist of identifiers with their
scope and stored size, behind a current grant and gesture, and never a secret byte.

```kotlin
import java.nio.file.Path

val secrets = JvmKWebSecureStorage.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_secure_storage"),
)
engine.nativeServices.install(KWebSecureStorage.Key, secrets)

val reference = KWebSecretRef(KWebSecretId("api-token"))
secrets.put(
    KWebSecretPutRequest(reference, tokenBytes, KWebSecretAccessibility.WHEN_UNLOCKED),
)
val value = secrets.get(reference)
try {
    use(value.copyBytes())
} finally {
    value.close()
}
```

## Verification

```shell
./gradlew :kweb-service-secure-storage:check
```

The task runs the common contract tests, the native C ABI and provider tests of
the current target, the packaged provider ZIP check, and the real provider
integration fixture. The macOS provider test inspects the Keychain's own item
count, exercises the declared accessibility policy against the real item, and
plants a non-envelope record to prove the corruption path. The Linux provider test
drives the real Secret Service when the session declares one and reports the typed
`secure-storage.service-unavailable` boundary when it does not, without ever
blocking on an autolaunched bus.
