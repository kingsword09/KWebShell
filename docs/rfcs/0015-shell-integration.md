# RFC 0015: External URL, reveal, trash, and shell integration

- Status: Proposed
- Priority: P0
- Owners: new `kweb-service-shell` KMP service
- Depends on: RFC 0003, RFC 0013
- Electron migration surface: `shell.openExternal`, `openPath`, `showItemInFolder`, `trashItem`, shortcut links
- Target mapping: `ADAPTER` or `REWRITE`

## Objective

Expose narrowly scoped OS shell actions with explicit scheme/path policy and
user intent. Shell execution is never a generic command runner.

## Common KMP contract

Define typed external URI requests, scoped-handle reveal/open/trash operations,
trash results, and platform shortcut metadata only where a separate capability
is advertised. Kotlin host code may use validated absolute resources; renderers
must use scoped handles.

## Platform provider contract

Use Windows ShellExecute/Explorer/recycle-bin and shell-link contracts, macOS
NSWorkspace/Finder trash APIs, and Linux XDG Portals/desktop trash contracts.
Headless or missing desktop sessions return typed unavailability.

## Acceptance

1. Scheme allow/deny tests cover HTTPS, mail, custom protocols, file, script,
   command, malformed, Unicode-confusable, and nested URLs.
2. Real UI integration proves the OS handler receives an allowed URI and no
   executable argument injection occurs.
3. Reveal/open/trash use RFC 0013 handles and reject stale, symlink-swapped,
   cross-owner, and directory-policy violations.
4. Trash verifies actual OS result and collision behavior; it never degrades to
   permanent deletion.
5. Renderer operations require a current gesture and exact operation grant.
6. Migration fixtures classify each Electron shell method independently;
   unsupported beep/shortcut semantics remain blocked until declared.

## Non-goals

No arbitrary executable launch, terminal opening, command line, permanent
delete fallback, or unrestricted `file://` launch.
