# KWebNotifications Service

`kweb-service-notifications` is the RFC 0016 application-scoped native
notification service. It uses the declared WinRT toast, macOS
`UNUserNotificationCenter`, and Linux `org.freedesktop.Notifications` providers
through one versioned C ABI and JDK 25 FFM binding.

The v1 icon is the verified packaged application icon only. Custom image bytes,
paths, URLs, in-page toast windows, generic IPC, and silent provider fallbacks
are not part of the API. Linux reports notification permission as
`NOT_APPLICABLE` because the freedesktop daemon has no portable permission
store, and does not advertise text replies.

Notification action responses are routed through the host-only RFC 0006
protocol activation sink. Renderer calls use the generated
`NotificationsBridge` and the normal exact-origin, grant, consent, and
lifecycle policy checks.

Hosts must observe `permission()` and request undetermined OS authorization
before showing notifications. The migration integration test checks the real
provider status and the notification action contract in the required hosted CI.
The physical macOS/Windows notification-center action is a separate manual
conformance workflow because hosted desktop layout and accessibility state are
not stable CI inputs. Use
`.github/workflows/macos-notification-conformance.yml` and
`.github/workflows/windows-notification-conformance.yml`. The Electron
capability matrix remains `UNSUPPORTED` for the notification migration row
until both workflows retain fresh OS-visible action evidence. Missing
permission or an unavailable provider still fails the required test with
redacted diagnostics.

GitHub's macOS image disables the Notification Center LaunchAgent. CI runs
`bash .github/scripts/configure-macos-notification-fixture.sh` to enable and
start that exact agent and verify its process before the packaged fixture.
The service label is read from Apple's plist; application permission remains
subject to the real system prompt.

For the Windows action conformance fixture, run
`.github/scripts/configure-windows-notification-fixture.ps1` with Windows
PowerShell 5.1 before the integration test, then run it with `-Cleanup` afterward.
It registers only the test desktop AUMID. Required CI runs the native test's
`--fixture-permission` mode and the deterministic action/payload contract;
the action through `.github/workflows/windows-notification-conformance.yml`
when an interactive desktop is available. Pass
`["self-hosted","Windows","X64","windows-notifications"]` only from a
diagnostic branch when GitHub-hosted Windows cannot provide that desktop.
These steps do not enable denied OS notifications or establish MSIX/cold-
activation conformance.
