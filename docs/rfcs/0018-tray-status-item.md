# RFC 0018: Tray and status-item lifecycle

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-tray` KMP service
- Depends on: RFC 0004, RFC 0017, RFC 0027, RFC 0030
- Electron migration surface: `Tray`
- Target mapping: `REWRITE`

## Objective

Publish explicitly owned Windows notification-area icons, macOS status items,
and Linux status notifier items with menu, tooltip, click, bounds, and teardown
contracts.

## Common KMP contract

`KWebTrayItem` is application-scoped and closeable. Creation declares stable ID,
icon variants, tooltip, menu ID, activation behavior, and platform capabilities.
Ordered events distinguish primary/secondary activation, menu, balloon/action,
and native removal.

## Platform provider contract

Use Shell_NotifyIcon with Explorer restart recovery, NSStatusItem on AppKit, and
StatusNotifierItem/D-Bus on declared Linux desktops. A missing Linux host is a
typed unsupported environment, not a hidden AWT tray fallback.

## Acceptance

1. Real desktop tests create/update/activate/menu/close one item and prove no
   icon remains after normal or crash-recovery cleanup.
2. Windows Explorer restart republishes the same item once; macOS status item
   runs only on AppKit; Linux watcher loss/reconnect is explicit.
3. Icons pass scale/theme variants through RFC 0027 and bounds report actual
   availability.
4. Menu commands use RFC 0017 ownership and cannot outlive item replacement.
5. Migration fixture replaces declared Tray operations while unknown event
   methods block.
6. Packaging verifies stable app identity and no duplicate item across
   single-instance activation.

## Non-goals

No fake Compose overlay, generic system-tray fallback, renderer-owned tray item,
or claim of Linux support without a tested status-notifier host.
