# RFC 0026: Native printing and PDF output

- Status: Proposed
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium print adapter
- Depends on: RFC 0003, RFC 0004, RFC 0013
- Electron migration surface: `webContents.print`, `printToPDF`, printer enumeration
- Target mapping: `REWRITE`

## Objective

Print a `KWebPage` through Chromium's print pipeline with native dialog/user
selection, typed settings, progress/cancellation, and scoped PDF output.

## Contract

Define page range, media, margins, orientation, color, duplex, copies, scale,
headers/footers, background, silent policy, printer capability facts, job state,
and PDF result handle. Silent printing requires trusted Kotlin policy and an
exact printer identity; renderers cannot choose arbitrary system queues.

## Platform provider contract

Use Chromium printing integrated with Windows print spooler, macOS NSPrint/
PrintCore, and Linux CUPS/portal print contracts. Printer discovery and status
must reflect the selected provider.

## Acceptance

1. Controlled HTML prints to a virtual/test printer and PDF on all targets with
   pixel/text/page-count comparison.
2. Native dialog cancel, invalid ranges, unsupported media/duplex, offline
   printer, spool failure, Page close, and shutdown produce typed terminal jobs.
3. PDF bytes are deterministic within declared metadata normalization and are
   returned via RFC 0013 scoped handles.
4. Renderer printing requires gesture; silent mode is unavailable through the
   generated bridge unless a separately audited application policy permits it.
5. Migration fixtures cover supported Electron options and block unknown/
   platform-only settings.
6. CI proves no queued test job, temporary PDF, Chromium print host, or handle
   remains.

## Non-goals

No custom PDF renderer, raw spooler command, hidden print-dialog automation, or
fallback from native print to PDF.
