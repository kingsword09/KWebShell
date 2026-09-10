# RFC 0040: Owner-bound native prompts

- Status: Proposed
- Priority: P1
- Owners: new `kweb-service-prompts` KMP service, Compose host
- Depends on: RFC 0003, RFC 0007, RFC 0027
- Electron migration surface: `dialog.showMessageBox`, `dialog.showErrorBox`
- Target mapping: `ADAPTER` for declared prompts, otherwise `REWRITE`

## Objective

Publish asynchronous native information, warning, error, question, and
confirmation prompts owned by an existing application window. Prompt definitions
are closed Kotlin data, not renderer-supplied dialog templates.

## Common KMP contract

The service accepts a prompt ID, severity, title, bounded message/detail text,
one to four typed actions, default/cancel action IDs, optional bounded checkbox,
and optional `KWebImage`. Results identify the selected action, checkbox state,
dismissal reason, and owner. Each request requires a live window owner; only
predeclared prompt IDs may be exposed to a renderer.

## Platform provider contract

Use `TaskDialogIndirect` on Windows, `NSAlert` as an owner-bound sheet on macOS,
and an explicitly packaged GTK4 alert-dialog provider on Linux. Providers run on
their required UI thread and return only after native dismissal. Linux packaging
must name and verify its GTK ABI; an HTML or Compose replacement is not selected
when the native provider is unavailable.

## Security and lifecycle invariants

Messages are bounded and treated as text, never markup. The provider rejects
duplicate action IDs, invalid default/cancel IDs, control characters, icon
overflow, a closed owner, a second active modal prompt for the same owner, and
renderer calls without operation grant and recent user gesture. Closing the owner
cancels its prompt without activating any action.

## Acceptance

1. Common tests cover closed validation, Unicode, action/default/cancel rules,
   result mapping, gesture expiry, cancellation, and owner-close races.
2. Real UI tests on all three targets exercise every severity, keyboard default
   and cancel, checkbox, long bounded text, ownership, DPI, and accessibility
   metadata; screenshots and native accessibility dumps are retained.
3. Exact-origin tests prove a renderer can select only a declared prompt ID and
   cannot alter text, actions, icon, owner, or severity.
4. An Electron fixture maps only inventory-declared `showMessageBox` calls;
   dynamic option objects and synchronous assumptions block migration.
5. Native resources and callbacks return to zero after accept, dismiss, cancel,
   owner shutdown, repeated use, and service close.

## Non-goals

No certificate decision UI, file picker, text/password input, arbitrary renderer
prompt construction, synchronous prompt API, hidden owner window, or HTML
fallback.
