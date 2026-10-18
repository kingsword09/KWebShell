# RFC 0028: Page find, zoom, capture, save, and source utilities

- Status: Proposed
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium Page adapter
- Depends on: RFC 0004, RFC 0008, RFC 0013, RFC 0027
- Electron migration surface: `webContents.findInPage`, zoom, `capturePage`, `savePage`, selected `webFrame`
- Target mapping: `DIRECT` or `REWRITE`

## Objective

Publish bounded Page utilities backed by Chromium semantics: find sessions,
zoom state, controlled page capture, save/export, source/DOM diagnostics, and
visibility/background-throttling facts that real migrations require.

## Delivery split (2026-10-18)

This RFC describes a multi-objective capability and must be split before
implementation. [RFC 0044](0044-page-find-and-zoom.md) delivers its first
publishable objective — page find sessions and page zoom — completely on its own
terms, with real three-target evidence and its own acceptance matrix.

The remaining objectives stay here and are not implemented by RFC 0044:

1. viewport/page capture into RFC 0027 image values, including clipping, scale,
   and pixel evidence across the GPU contracts;
2. save/export of the current page into RFC 0013 handles, with format and
   metadata normalization;
3. page source and DOM diagnostics under an explicit byte limit and trusted
   policy;
4. visibility, minimized, and occluded behavior facts;
5. durable zoom persistence per profile and host, which RFC 0044 explicitly
   leaves out.

Each of those needs its own readiness review before coding. RFC 0044's contract
must not be extended in place: a change to its find or zoom semantics is a new
reviewed objective.

## Contract

Each operation is typed and Page-scoped. Find has an owned session and ordered
match updates. Zoom distinguishes factor/level and origin persistence. Capture
declares viewport rectangle/scale and returns RFC 0027 data. Save/export returns
RFC 0013 handles. Source access has explicit byte limit and trusted policy.

## Acceptance

1. Real pages cover find next/previous/case, dynamic DOM changes, stop actions,
   zoom persistence/isolation, device scale, capture clipping, and save formats.
2. Hidden/minimized/occluded Page behavior is measured and explicitly documented.
3. Navigation, renderer crash, Page close, invalid rectangle, huge source, and
   concurrent operations terminate owned sessions.
4. Capture pixel evidence compares controlled fixtures on all GPU contracts.
5. Renderer adapters expose only application-declared utilities; arbitrary
   `executeJavaScript`, frame globals, and prototype identity remain unsupported.
6. Migration inventory classifies each `webContents` utility method separately.

## Non-goals

No generic renderer code execution, remote debugging shortcut, DOM object
transport, or OS desktop capture.
