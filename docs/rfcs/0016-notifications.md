# RFC 0016: Native notifications and activation

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-notifications` KMP service
- Depends on: RFC 0003, RFC 0004, RFC 0027, RFC 0030
- Electron migration surface: `Notification`, `dialog.showMessageBox` migrations that are notifications
- Target mapping: `ADAPTER`

## Objective

Publish native notifications with stable application identity, permission
status, actions, reply where supported, activation routing, replacement, and
explicit close.

## Common KMP contract

Define notification IDs/tags, title/body limits, icon reference, actions,
urgency, timeout policy, permission state, and ordered shown/action/reply/closed/
failed events. Unsupported action/reply fields fail validation rather than
disappearing.

## Platform provider contract

Use Windows App SDK/toast activation with AUMID, macOS
UNUserNotificationCenter, and the declared Linux freedesktop portal/service.
Activation must reach RFC 0006 after cold start.

## Acceptance

1. Packaged applications request real permission, show, replace, activate,
   action, reply where advertised, and close notifications on all targets.
2. Cold/warm activation is delivered once to the correct application owner with
   validated payload identity.
3. Denied, unavailable daemon, invalid icon, rate limit, owner close, and stale
   action return stable results.
4. Renderer creation requires exact grant; action payloads are schema-defined and
   cannot inject a generic channel.
5. Migration fixtures cover constructor/static support checks and application-
   specific action handlers without emulating EventEmitter identity.
6. CI retains OS-visible evidence and activation transcripts without notification
   body content when marked sensitive.

## Non-goals

No custom hidden toast window, silent fallback to in-page UI, remote push
transport (RFC 0034), or generic message-box API.
