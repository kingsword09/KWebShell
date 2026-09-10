# RFC 0034: Push registration and notification activation

- Status: Proposed
- Priority: P2
- Owners: push service, RFC 0016 notification integration
- Depends on: RFC 0004, RFC 0016, RFC 0030
- Electron migration surface: `pushNotifications`
- Target mapping: platform-specific `REWRITE`

## Objective

Register packaged applications with explicitly selected platform push services,
receive bounded payloads, and route them through native notification/application
activation contracts.

## Common KMP contract

Define provider ID, registration state/token reference, topic/subscription
requests where supported, payload schema ID, delivery/activation event, and
revocation. Raw device tokens are trusted Kotlin data and are never exposed to a
renderer or retained in general diagnostics.

## Platform provider contract

Use Windows Push Notification Services for the selected Windows package,
Apple Push Notification service with signed entitlements on macOS, and no Linux
provider until one exact distribution/desktop push contract is selected. This
RFC may first publish separate Windows/macOS keys; it must not claim Linux.

## Acceptance

1. Signed packaged apps register with real sandbox/test push infrastructure and
   receive foreground, background, cold-start, duplicate, and expired messages.
2. Token rotation/revocation, offline queueing, malformed/oversized payload,
   permission denial, and uninstall are explicit.
3. Delivery routes once to the correct application identity/Profile policy and
   RFC 0016 activation.
4. Renderer receives only schema-decoded application events, not provider token
   or arbitrary JSON.
5. Migration fixture marks Linux use blocked unless the application selects a
   separate implemented provider.
6. CI stores delivery IDs and schema hashes, never credentials or full payloads.

## Non-goals

No KWebShell push cloud, cross-platform token abstraction that hides provider
semantics, polling fallback, or undocumented Electron service compatibility.
