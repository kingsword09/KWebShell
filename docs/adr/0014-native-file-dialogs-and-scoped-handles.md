# ADR 0014: Native file dialogs and scoped handles

- Status: Accepted
- Date: 2026-09-06

## Context

Migrated Electron renderers commonly need an open or save picker, but returning
an arbitrary host path would make a renderer string an unrestricted filesystem
capability. The picker also belongs to the application's existing window and
must not create a hidden or replacement top-level window. Selected data needs a
bounded lifetime and access mode so owner shutdown and renderer cancellation
have deterministic effects.

## Decision

`kweb-service-dialogs` binds one caller-owned Compose Desktop `ComposeWindow` to
an `NSSavePanel`/`NSOpenPanel` sheet on macOS, COM `IFileDialog` on Windows, or
the XDG Desktop Portal on Linux. A small versioned C ABI owns asynchronous
presentation and cancellation, while the JVM provider binds it through JDK 25
FFM. `OPEN` selections create read-only handles; `SAVE` selections create
write-only handles. The bridge returns only a random opaque token, display
name, byte size, and mode. Read, write, truncate, and close operations validate
the token, mode, offsets, byte range, and bounded request size before touching a
`FileChannel`. Paths remain provider state.

The provider canonicalizes an existing open file and its parent, rejects
directories and symlink targets, and never creates a file until a native save
selection has completed. The native owner handle is read on the AWT event
thread, and each platform implementation presents and cancels the picker on its
required event loop. Service close cancels an active native operation, closes
all channels, unloads its FFM binding, and does not dispose the caller window.

The exact-origin bridge is generated from one schema. Permission, origin,
lifecycle, request validation, and cancellation remain in the existing bridge
and service layers; the dialog service adds no generic IPC or path operation.

## Consequences

Renderer migrations must change path-based Electron calls to the generated
handle operations. This removes path disclosure and gives the host a clear
owner-close boundary, at the cost of explicit I/O calls and bounded buffers.
The separately packaged native provider supplies the picker on the three
advertised JVM targets; a missing portal, incompatible ABI, or unusable native
owner is a typed failure rather than a fallback.

## Verification

Common tests cover request limits, filters, modes, and descriptor grants. JVM
provider and bridge tests cover token isolation, mode enforcement, bounded I/O,
cancellation, owner close, and typed failures. The module's native dialog smoke
and desktop CEF fixture cover real selection, exact-origin/frame isolation, and
caller-window survival on each hosted target.
