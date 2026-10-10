# KWebShell Tray Service

`kweb-service-tray` implements the RFC 0018 typed tray contract for Windows
notification-area icons, macOS status items, and Linux status notifier items.
Kotlin owns item identity, icon selection, menu versions, capability reporting,
and event ordering; each advertised target contributes one native provider
behind a versioned C ABI and the JDK 25 FFM binding.

The published v1 surface contains:

- application-scoped items with a bounded id, RFC 0027 icon variants, an
  optional bounded tooltip, a declared activation set, and an optional menu;
- one versioned RFC 0017 menu tree per live item, cleared on close so no command
  outlives an item replacement;
- ordered events for primary, secondary, and double activation, menu commands,
  balloon actions, native removal, and failure;
- declared provider capabilities, including which activations the platform
  delivers and whether it publishes item bounds; and
- typed errors for every rejected item, icon, tooltip, activation, menu, and
  platform condition.

There is no renderer surface: a tray item belongs to the application, and page
content can never create, mutate, or observe one. A platform without its
declared facility fails immediately with a typed error instead of falling back
to a Compose or AWT tray.

```kotlin
import java.nio.file.Path

val trays = JvmKWebTrays.open(
    applicationId = "io.example.app",
    nativeLibrary = Path.of("/absolute/path/to/libkwebshell_tray"),
)
engine.nativeServices.install(KWebTrays.Key, trays)

trays.create(
    KWebTrayItemSpec(
        id = KWebTrayItemId("status-item"),
        icon = KWebTrayIcon(listOf(KWebTrayIconVariant(1, "icons/tray.png", sha256))),
        tooltip = "Example",
        activations = setOf(KWebTrayActivation.PRIMARY, KWebTrayActivation.SECONDARY),
    ),
)
```

## Verification

```shell
./gradlew :kweb-service-tray:check
```

The task runs common contract tests, the native C ABI tests of the current
target, the packaged provider ZIP check, and the real provider integration
fixture.
