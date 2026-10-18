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

## Delivery split (2026-10-18)

This RFC is a multi-objective backlog entry and must be split before
implementation.

[RFC 0045](0045-pdf-export.md) delivers its first publishable objective: writing
the current page as a bounded, engine-owned PDF artifact through Chromium's
PDF pipeline, with typed paper, scale, margin, header/footer, page-range, and
background settings.

The remaining objectives stay here:

1. printer discovery and capability facts, delivered as
   [RFC 0046](0046-printer-discovery.md);
2. interactive printing through the platform print path, including
   `window.print()` behavior on every target;
3. silent print jobs under a separately audited application policy;
4. job state, cancellation, and shutdown semantics for real spooler jobs.

### Blocked print-submission remainder (2026-10-18)

Objectives 2-4 are recorded as blocked by the pinned runtime, not as pending
work:

- `CefBrowserHost::Print()` disables print preview whenever the runtime style is
  Alloy (`CefBrowserPlatformDelegate::IsPrintPreviewSupported` returns false for
  `IsAlloyStyle()` unless the command line enables preview explicitly). The
  Windows and macOS paths therefore fall back to the operating system's own
  modal print dialog, which a hosted verification job cannot drive or cancel and
  whose outcome cannot be retained as evidence.
- On Linux the same call needs an application-provided `CefPrintHandler`:
  `CefPrintDialogLinux::ShowDialog` invokes `OnPrintDialog` and `OnPrintJob`, so
  the application must render the dialog and submit the job to a real queue
  itself. That is a complete platform objective of its own, not a slice of this
  RFC, and it still cannot produce cross-platform evidence.
- A silent job has no cross-platform hook: `CefPrintHandler` is documented and
  implemented for Linux only, so no Windows or macOS path accepts an
  application-chosen queue without a Chromium patch series of its own.

Publishing `print()` or a silent job API under these constraints would either
promise behavior the runtime cannot deliver or require hosting native dialogs as
verified evidence. The RFC therefore stays `Proposed` for these objectives, and
the capability matrix keeps `webContents.print` unclaimed. A future objective
that patches Chromium's print path, or that declares a Linux-only print contract
with an application-owned dialog, must start with its own readiness review.

RFC 0045's and RFC 0046's contracts must not be extended in place: a change to
their settings, artifact, or snapshot rules is a new reviewed objective.

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
