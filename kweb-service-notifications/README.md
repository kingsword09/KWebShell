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
provider status before driving the system notification UI; its action fixture
requires macOS Accessibility access or the Windows interactive desktop. Missing
permission or an unavailable action fails the test with redacted diagnostics.
