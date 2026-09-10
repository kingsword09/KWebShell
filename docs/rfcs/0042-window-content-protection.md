# RFC 0042: Window content protection and privacy state

- Status: Proposed
- Priority: P1
- Owners: `kweb-service-window-controls`, desktop capture policy
- Depends on: RFC 0003, RFC 0007, RFC 0025
- Electron migration surface: `BrowserWindow.setContentProtection`, capture exclusion
- Target mapping: `ADAPTER` only on verified target capabilities

## Objective

Let trusted Kotlin code request OS-enforced capture protection for a specific
application window and expose the actual protection state. This is a privacy
control with explicit limits, not a digital-rights-management guarantee.

## Common KMP contract

The window service publishes a typed protection request, actual state, reason,
target capability record, and ordered change events. States distinguish active,
inactive, externally revoked, and unsupported. Renderer code cannot enable,
disable, or infer protected content unless an application-specific read-only
operation is declared.

## Platform provider contract

Use verified Windows display-affinity APIs and verified macOS sharing-type or
replacement public APIs permitted by the minimum OS. Linux support requires a
documented compositor/portal protocol that actually excludes the window from
capture; if no such protocol is available, Linux is an explicitly unsupported
target for this platform-specific key rather than a successful no-op.

## Security and lifecycle invariants

Protection belongs to one live window, is applied on its UI thread, is restored
or removed deterministically, and cannot outlive the owner. Logs and diagnostics
must not capture protected pixels. Desktop capture RFC 0025 excludes protected
KWebShell windows and reports a redacted source rather than bypassing policy.

## Acceptance

1. Common tests cover target capability, transitions, idempotence, external
   revocation, owner close, and forbidden renderer mutation.
2. Real target tests enable protection and attempt OS screenshot, window capture,
   desktop capture, and KWebShell capture. Evidence distinguishes exclusion,
   blanking, and unsupported behavior without retaining sensitive pixels.
3. Protected and unprotected sibling windows remain isolated, including during
   recreation, fullscreen, display changes, and capture already in progress.
4. Migration reports Electron `setContentProtection` as `ADAPTER` only for a
   target whose real capture evidence passes; other targets block packaging or
   require an application-approved rewrite.
5. Crash recovery and normal shutdown leave no persistent global capture policy.

## Non-goals

No DRM claim, camera prevention, external-device prevention, guaranteed defense
against privileged OS software, fabricated Linux success, or renderer-controlled
privacy policy.
