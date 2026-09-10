# RFC 0037: Native drag, drop, and promised files

- Status: Proposed
- Priority: P1
- Owners: new drag/drop service, Compose/native surface integration
- Depends on: RFC 0003, RFC 0013, RFC 0027
- Electron migration surface: `webContents.startDrag`, file drops
- Target mapping: `REWRITE`

## Objective

Support dragging typed application data and scoped files between Chromium,
Compose, and other native applications, including promised/lazy files where the
platform contract is complete.

## Common KMP contract

Define drag actions, MIME/type allowlists, text/URI/image/scoped-file items,
preview icon, drag result, drop location, and closeable promised-file producer
with byte/time limits. Incoming host files become RFC 0013 handles, never raw
renderer paths.

## Platform provider contract

Use OLE drag/drop and virtual files on Windows, NSPasteboard/NSDragging and file
promises on macOS, and X11/Wayland Portal or declared desktop drag protocols on
Linux. Native child/Compose coordinate conversion is explicit.

## Acceptance

1. Real UI automation drags text, image, existing file, and promised file both
   into and out of a controlled external native target on all advertised
   providers.
2. Move/copy/link negotiation, user cancel, target rejection, source/target
   close, slow promised data, and multi-monitor scale are deterministic.
3. Incoming paths are canonicalized into least-privilege handles and reject
   symlink/reparse races.
4. Renderer start requires a current gesture and predeclared item schema; child/
   cross-origin frames cannot initiate privileged file drags.
5. Migration fixture maps `startDrag` and renderer drop workflows without
   exposing absolute file paths.
6. CI proves no temporary promised file or native drag object remains.

## Non-goals

No clipboard fallback, synthetic DOM-only proof, arbitrary native format, or
claim of Wayland support through XWayland unless that exact provider is declared.
