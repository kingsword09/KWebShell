# KWebShell Shortcut Service

`kweb-service-shortcuts` implements the RFC 0021 typed global-shortcut contract
for Windows `RegisterHotKey`, macOS Carbon `RegisterEventHotKey`, and Linux X11
`XGrabKey`. Kotlin owns registration identity, the bounded accelerator model,
capability reporting, atomic bulk replacement, and ordered invocation, loss,
removal, and failure events; each advertised target contributes one native
provider behind a versioned C ABI and the JDK 25 FFM binding.

The published v1 surface contains:

- an application-scoped accelerator model of modifiers and portable named or
  media keys, where a named key requires at least one modifier;
- application-owned registration identifiers bound to application-declared
  command identifiers;
- one live registration per identifier, an atomic `replaceAll`, and an
  idempotent `unregister`;
- declared provider capabilities, including which modifiers and media keys the
  target and session actually register; and
- typed `shortcuts.*` errors for every rejected accelerator, conflict, permission,
  and platform condition, with no silent fallback to an in-window shortcut.

The renderer surface is policy-gated and exact-origin only: `read` returns whether
global registration is supported and the live registration count without any
accelerator or identifier, and `dispatch` invokes one application-declared command
with a user gesture and consent. A page can never register an accelerator or
observe one. A session without a declared facility — the v1 Wayland session — fails
immediately with `shortcuts.platform-unavailable` instead of falling back to X11.

```kotlin
import java.nio.file.Path

val shortcuts = JvmKWebShortcuts.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_shortcuts"),
)
engine.nativeServices.install(KWebShortcuts.Key, shortcuts)

shortcuts.register(
    KWebShortcutRegistration(
        id = KWebShortcutId("copy"),
        accelerator = KWebShortcutAccelerator(
            setOf(KWebShortcutModifier.CONTROL, KWebShortcutModifier.SHIFT),
            KWebShortcutKey.Named(KWebShortcutNamedKey.C),
        ),
        commandId = KWebShortcutCommandId("text.copy"),
    ),
)
```

## Verification

```shell
./gradlew :kweb-service-shortcuts:check
```

The task runs the common contract tests, the native C ABI tests of the current
target, the packaged provider ZIP check, and the real provider integration
fixture.
