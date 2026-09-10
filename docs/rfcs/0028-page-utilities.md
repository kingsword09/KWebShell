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
