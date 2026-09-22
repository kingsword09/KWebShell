# Application lifecycle service

RFC 0006 is implemented by `kweb-service-application-lifecycle`. It is a
host-only service: renderer code can receive a declared activation route, but
cannot acquire the application lease, inspect process arguments, request quit,
or select a relaunch executable.

The lifecycle owner accepts only canonical `kweb:` URIs and absolute file
paths declared by the RFC 0030 application manifest. Activation frames are
strict UTF-8 JSON, bounded to 256 KiB, and delivered through a replay-bounded
ordered stream with a 64-event activation capacity. The common state machine
is:

```text
NEW -> STARTING -> PRIMARY_READY -> QUIESCING -> CLOSED
                  \\-> SECONDARY_FORWARDED -> CLOSED
STARTING/PRIMARY_READY/QUIESCING -> FAILED
```

Create one configuration from the verified package identity and register the
desktop engine before starting the service:

```kotlin
val lifecycle = KWebApplicationLifecycleController(configuration, backend)
lifecycle.registerShutdownParticipant(engine.applicationShutdownParticipant())
lifecycle.start(initialActivation)
lifecycle.installAssociations() // explicit host operation
```

The JVM desktop binding uses the internal JDK 25 FFM binding and the versioned
native ABI in `kweb-cef-native`:

| Target | Native provider | Transport boundary |
| --- | --- | --- |
| Windows | `windows-named-mutex-authenticated-pipe` | current-user named mutex and same-user named pipe |
| macOS | `macos-appkit-launch-services` | AppKit application delegate, Launch Services identity, same-user local activation transport |
| Linux | `linux-dbus-freedesktop-application` | session D-Bus `org.freedesktop.Application` name and credential-checked `Activate` method |

The native provider rejects invalid UTF-8, oversized frames, wrong-user
senders, unavailable platform facilities, and an unavailable lease. It never
selects a WebView or another transport as a fallback. The hosted two-process
probe writes `application-lifecycle-report.json` under
`kweb-service-application-lifecycle/build/reports/application-lifecycle/`.

Association mutation is explicit and host-only. `installAssociations()` and
`removeAssociations()` validate the packaged executable and return the target,
provider, operation, and observed registration digest. Starting the lifecycle
does not install or remove OS registrations.

Use `requestQuit` for orderly shutdown. Participants vote in descending order;
the same order is used for close. A veto restores `PRIMARY_READY`. A timeout,
participant failure, native failure, or OS termination is not reported as a
graceful close. Relaunch has no executable or argument parameter and is only
available for a verified packaged target.
