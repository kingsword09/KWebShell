# KWebShell Shell Service

`kweb-service-shell` is the RFC 0015 typed desktop-shell adapter. It publishes
only four PAGE-scoped operations:

- `openExternal` for an explicitly allowlisted external URI;
- `openResource` for an RFC 0013 file handle;
- `revealResource` for an RFC 0013 file handle; and
- `trashResource` for a verified OS trash/recycle operation.

Renderer requests use the generated `ShellBridge` and are checked by the
exact-origin bridge, service permission policy, native user-gesture registry,
page lifecycle, and the files-owner resolver. Paths, commands, executable
launches, `file:` URI conversion, and permanent-delete fallbacks are not part
of the API.

The JVM provider targets JDK 25 FFM over the versioned C ABI in `native/`:

- Windows uses ShellExecute, PIDL reveal, and the recycle-bin shell API;
- macOS uses `NSWorkspace`; and
- Linux uses GLib/GIO and the session `FileManager1` D-Bus contract.

Missing desktop handlers or session facilities produce typed failures. The
provider never selects another backend silently. Native ABI tests, JVM contract
tests, and the platform FFM integration task are run by `:check`.

The page host must call `JvmKWebShellHandle.onNavigationStarted()` before a
main-frame navigation and `onNavigationCommitted()` after the committed origin
is known. The handle rejects pre-boundary work from the previous document with
`service.cancelled`; `close()` rejects pre-boundary work with
`service.owner-closed` and waits only for an action that already crossed the
native boundary.
