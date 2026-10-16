# KWebShell Power Service

`kweb-service-power` implements the RFC 0022 typed power contract for Windows
power requests, macOS `IOPMAssertion` and `NSWorkspace`, and the Linux
logind/UPower system-bus contract. Kotlin owns lease identity, the duration
ceiling, capability reporting, expiry, and ordered event delivery; each advertised
target contributes one native provider behind a versioned C ABI and the JDK 25 FFM
binding.

The published v1 surface contains:

- ordered events for suspend, resume, session lock, unlock, power-source changes,
  thermal changes where the session publishes them, and lease endings;
- the facts the session publishes — on battery, battery percentage, thermal level,
  and lock state — with an unpublished fact absent instead of defaulted;
- explicit sleep-blocker leases with a required maximum duration from 1 second to
  four hours, renewal, automatic expiry, deterministic composition, and release on
  owner close; and
- typed `power.*` errors for every rejected lease, unsupported type, missing
  facility, and platform condition, with no polling fallback.

The renderer surface is policy-gated and exact-origin only: `read` returns a
bounded summary (whether blocking is supported, whether the session is on battery,
and the live lease count) without any lease identifier, reason, or assertion
detail, and `acquire-profile` acquires one application-declared lease profile with
a user gesture and consent. An observation never creates a lease, and a page can
never name a blocker type or an arbitrary duration.

```kotlin
import java.nio.file.Path

val power = JvmKWebPower.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_power"),
    profiles = listOf(
        KWebPowerLeaseProfile(
            KWebPowerLeaseProfileId("render"),
            KWebPowerBlockerType.SYSTEM_SLEEP,
            "Exporting a report",
            600,
        ),
    ),
)
engine.nativeServices.install(KWebPower.Key, power)

power.acquire(
    KWebPowerLeaseRequest(
        id = KWebPowerLeaseId("render"),
        type = KWebPowerBlockerType.SYSTEM_SLEEP,
        reason = "Exporting a report",
        maxDurationSeconds = 600,
    ),
)
```

## Verification

```shell
./gradlew :kweb-service-power:check
```

The task runs the common contract tests, the native C ABI and provider tests of
the current target, the packaged provider ZIP check, and the real provider
integration fixture. The macOS provider test inspects the operating system's live
assertion list, including reclaiming an assertion whose owner process exited
without releasing it. The Linux provider test drives the real D-Bus contract
against a logind/UPower test double on a private bus and proves that releasing a
lease closes exactly its own inhibitor descriptor.
