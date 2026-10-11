# KWebShell System Preferences Service

`kweb-service-system-preferences` implements the RFC 0019 typed appearance and
accessibility contract for Windows, macOS, and Linux. Kotlin owns the snapshot,
capability reporting, change ordering, and the appearance request; each
advertised target contributes one native provider behind a versioned C ABI and
the JDK 25 FFM binding.

The published v1 surface contains:

- one atomic snapshot of the facts the platform publishes: color scheme,
  contrast, reduced motion, reduced transparency, differentiate-without-color,
  invert colors, accent color, text scale, screen reader, and the application
  appearance source;
- declared capability facts that separate what a provider publishes from what it
  keeps current at runtime, so an unpublished fact is absent instead of guessed;
- an ordered, exactly-once change stream with strictly increasing sequences; and
- the bounded `SYSTEM`, `LIGHT`, or `DARK` appearance request with a stable
  effective source and typed errors.

Facts that describe assistive-technology use are delivered only when the host
declares the matching platform key: `macos.voiceover`, `windows.screen-reader`,
or `linux.a11y`. Without the declared key the fact is absent from the snapshot,
the capability report, and the renderer snapshot.

## Renderer surface

A page can only read the non-sensitive facts through the policy-gated bridge:
`read` and `subscribe` need the declared renderer grant, and `set-appearance`
additionally needs an exact-origin main frame with a user gesture. A child or
cross-origin frame is denied before anything happens, and no assistive-technology
fact is ever renderer-visible.

## Engine alignment

The engine applies the declared startup preferences Chromium reads once
(`prefers-reduced-motion`, `prefers-contrast`), and page color scheme follows the
platform appearance — the same fact this service publishes. A host that needs a
different page appearance changes it in page content instead of expecting an
engine switch the pinned runtime does not implement.

```kotlin
import java.nio.file.Path

val preferences = JvmKWebSystemPreferences.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_system_preferences"),
    sensitiveKeys = setOf(KWebSystemPreferenceSensitiveKeys.MACOS_VOICEOVER),
)
engine.nativeServices.install(KWebSystemPreferences.Key, preferences)

val snapshot = preferences.snapshot()
val dark = snapshot.colorScheme == KWebColorScheme.DARK
```

## Verification

```shell
./gradlew :kweb-service-system-preferences:check
```

The task runs the common contract tests, the native C ABI and provider structure
tests of the current target, the bridge contract tests, the packaged provider ZIP
check, and the real provider integration fixture.
