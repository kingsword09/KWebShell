# RFC 0045: Page PDF export artifact

- Status: Implemented
- Priority: P1
- Owners: `kweb-core`, `kweb-desktop`, Chromium PDF adapter
- Depends on: RFC 0004, RFC 0008
- Electron migration surface: `webContents.printToPDF`
- Target mapping: `DIRECT`

## Objective and scope

Publish one page-scoped PDF export backed by Chromium's own print-to-PDF
pipeline on macOS arm64, Windows x64, and Linux x64:

1. a typed request covering paper, orientation, scale, margins, header/footer,
   page ranges, background printing, and CSS page size preferences;
2. an engine-owned artifact inside the Profile directory, readable only through
   bounded reads, with a declared size ceiling, a SHA-256 digest, and deletion
   when the document closes;
3. typed refusal of every value the runtime cannot honor, and of every
   destination outside the Profile artifact directory.

This RFC is one objective carved out of [RFC 0026](0026-printing-and-pdf.md).
Interactive printing, `window.print()` behavior, printer enumeration, and silent
print jobs stay with RFC 0026 and its own review. Nothing from those areas is
published here.

## Non-goals and applicability

- No printer discovery, printer capability facts, print dialogs, or spooler
  jobs; no `CefPrintHandler` registration, so page-initiated `window.print()`
  behavior is unchanged.
- No caller-chosen output path and no arbitrary filesystem write. The engine
  derives the artifact location from the Profile directory and refuses anything
  else.
- No PDF post-processing, merging, encryption, or text extraction.
- No fallback to another renderer: a runtime that cannot reach
  `CefBrowserHost::PrintToPDF` fails with a typed native error.

Universal completion areas map as follows: common contract and bounds A1-A2;
artifact access A3; lifecycle and concurrency A4; settings fidelity A5;
security/authority A6; migration A7; platform providers A8; evidence A9;
documentation and packaging A10; completion A11.

## Implementation contract

### Common KMP contract

`kweb-core` publishes `KWebPdfExportContract.kt` and adds one operation to
`KWebPageUtilities`:

```kotlin
public suspend fun exportPdf(
    request: KWebPdfExportRequest = KWebPdfExportRequest(),
): KWebPdfDocument
```

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `KWebPdfPaper` | `A4` (8.27x11.69 in), `LETTER` (8.5x11 in), or `Custom(width, height)` with 1-100 in | defaults to `A4` | paper box of the rendered page | A1, A5 |
| `KWebPdfMargins` | `DEFAULT`, `NONE`, or `Custom(top, right, bottom, left)` with 0-10 in | defaults to `DEFAULT` | margin box of the rendered page | A1, A5 |
| `scale` | finite, 0.1-2.0 | defaults to 1.0 | content scale | A1, A5 |
| `displayHeaderFooter`, `headerTemplate`, `footerTemplate` | templates up to 8 KiB UTF-8 each; a template requires `displayHeaderFooter` | defaults to no header/footer | header/footer rendering | A1, A5 |
| `pageRanges` | Chromium's one-based grammar `1-3, 5`, up to 256 UTF-8 bytes | defaults to the whole document | printed page subset | A1, A5 |
| `preferCssPageSize`, `printBackground`, `landscape` | Boolean | defaults to `false` | rendered PDF | A5 |
| `KWebPdfDocument.name` | safe `*.pdf` file name inside the Profile export directory | assigned by the engine | artifact identity | A2, A3 |
| `KWebPdfDocument.sizeBytes` | 1 .. 64 MiB | observed from the file | artifact size | A2, A3 |
| `KWebPdfDocument.sha256` | 64 lower-case hex characters | computed over the artifact | artifact digest | A2 |
| `read(offset, length)` | `offset >= 0`, `1 <= length <= 1 MiB` | returns `eof` on the last chunk | bounded artifact access | A3 |

The published contract also declares Chromium's exact landscape behavior: a
landscape export swaps the declared paper dimensions, so a 6x4 inch request
renders a 4x6 inch page (a 288x432 point media box).

### Engine ABI

| ABI item | Shape | Behavior | Acceptance IDs |
|---|---|---|---|
| `kweb_browser_export_pdf` | `(browser, request_id, path, settings)` | validates the Profile-scoped path and the bounded settings JSON, then starts one Chromium PDF print job | A1, A4, A6 |
| `KWEB_BROWSER_EVENT_PDF_EXPORT` (30) | `request_id`, `status_code` = succeeded/failed, `text` = failure code, `details` = `{version,bytes}` | terminal observation of that export | A2, A4 |
| `KWEB_STATUS_PDF_EXPORT_INVALID` (95) | settings refused | `pdf.settings-invalid` | A1 |
| `KWEB_STATUS_PDF_EXPORT_ACTIVE` (96) | a second export while one is in flight | `pdf.export-active` | A4 |
| `KWEB_STATUS_PDF_EXPORT_FAILED` (97) | Chromium reported a failed job | `pdf.native-failed` | A5 |
| `KWEB_STATUS_PDF_PATH_INVALID` (98) | destination outside the Profile export directory or not a safe `.pdf` name | `pdf.path-invalid` | A6 |

The ABI version moves from 20 to 21.

### Lifecycle, concurrency, and limits

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| Idle | `exportPdf` | page open, valid request | one job in flight; suspend returns the artifact | one file inside the Profile export directory | A2, A4 |
| Job in flight | a second `exportPdf` | page open | second call fails `pdf.export-active` | none | A4 |
| Job in flight | page or engine closes | any | native job state cleared; waiter fails `page.closed` | partial file removed by the desktop layer | A4 |
| Job outstanding | 5 s elapse | IO | `pdf.native-failed`; the artifact is removed if present | waiter dropped | A4 |
| Artifact open | `read` | any | bounded chunk with `eof` on the last chunk | none | A3 |
| Artifact open | `close` | any | artifact removed once | file deleted | A3 |
| Artifact closed | `read` or second `close` | any | `pdf.artifact-invalid`; close is idempotent | none | A3 |

### Errors, renderer, and migration policy

| Condition | Stable error / outcome | Acceptance IDs |
|---|---|---|
| Invalid paper, margin, scale, template, or page range | `pdf.settings-invalid` before native dispatch | A1 |
| Second concurrent export | `pdf.export-active` | A4 |
| Chromium reported a failed job | `pdf.native-failed`; the artifact is removed | A5 |
| Missing, empty, oversized, or non-PDF artifact | `pdf.artifact-invalid` / `pdf.artifact-too-large`; the artifact is removed | A2 |
| Destination outside the Profile export directory | `pdf.path-invalid` | A6 |
| Page closed | `page.closed` | A4 |

PDF export is host-only: it is not exposed on the generated renderer bridge and
grants the renderer nothing. The migration matrix classifies
`webContents.printToPDF` as `print-to-pdf` (`DIRECT`); interactive printing stays
with RFC 0026 and silent print jobs remain blocked.

### Platform implementation and feasibility

| Target | Exact provider / native API | Thread / owner / availability constraints | Probe and result | Acceptance IDs |
|---|---|---|---|---|
| macOS arm64 | `CefBrowserHost::PrintToPDF` with explicit paper dimensions | job started on the browser UI thread, completion on the same thread | local `pdf-export` probe: 20,804-byte A4 PDF, one page, media box 595.92x841.92, 4x6 landscape page 288x432 | A2, A5, A8 |
| Windows x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A5, A8 |
| Linux x64 | same CEF 151 contract | same | hosted `engine-integration` job | A2, A5, A8 |

Feasibility facts established by reading the pinned CEF and Chromium sources and
by the real probe:

- `print_util::PrintToPDF` writes the file and posts `OnPdfPrintFinished` after
  the write completes, so a successful callback always observes a complete file.
- `print_to_pdf::GetPrintPagesParams` computes the printable area from explicit
  paper inches and never consults the platform default paper size, so no
  `CefPrintHandler` registration is required and `window.print()` behavior is
  untouched, including on Linux.
- The failure path for an impossible page range still reports through the
  callback, which is why the contract maps it to `pdf.native-failed` rather than
to a timeout.

### Evidence lifecycle and delivery

Bound contract paths: the `kweb-core` PDF contract and tests, the desktop page
utilities and native bindings, the CEF ABI and session implementation, the
migration matrix, the aggregate script, and this RFC. The recorded artifact is
`engine-integration/pdf-export-evidence.json` from the three-target verification
jobs plus the migration compatibility report.

Any change to the request fields/bounds, artifact rules, native ABI, error
mapping, or evidence producer invalidates the affected target records. The
sequence is: common/provider unit tests, native ABI tests, the real-CEF probe on
every target, migration tests, evidence aggregation, strict governance, and the
final row-by-row review.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / request contract | The typed request publishes exactly the declared fields and bounds. | A4/LETTER/custom paper, scale bounds, template without header/footer, malformed page ranges, oversized template. | Common contract tests on every target. | `KWebPdfExportContract.kt`, `KWebPdfExportContractTest.kt` | Contract digest in each record | PENDING_HOSTED |
| A2 / artifact | A real PDF artifact with the declared name, size, digest, and page count is published. | Default export, bounded full read, `%PDF-` header, one page, A4 media box. | Real-CEF probe on all three targets. | `runPdfExportIntegration`, `KWebDesktopPdfDocument` | `pdf-export-evidence.json` per target | PENDING_HOSTED |
| A3 / bounded access | Reads are bounded, report `eof`, and stop working after close; the file is deleted on close. | Full read, closed read, leftover directory listing. | Real-CEF probe plus desktop unit tests. | `KWebDesktopPdfDocument.read/close`, probe `leftoverFiles` | `readBytes`, `eofReached`, `closedReadRefused`, `leftoverFiles` | PENDING_HOSTED |
| A4 / lifecycle and concurrency | One export per page, typed `export-active`, close fails the waiter, deadline bounded. | Concurrent export, page close during export, 5 s deadline. | Probe plus native ABI status tests. | `NativeBrowser.exportPdf`, `ExportPdf` native guard | Evidence `failure` empty and zero live browsers | PENDING_HOSTED |
| A5 / settings fidelity | Declared settings reach Chromium's PDF writer. | Custom 6x4 inch landscape paper renders 288x432; impossible page range fails typed. | Real-CEF probe on all three targets. | `ExportPdf` margin/paper mapping, probe media boxes | `landscapeMediaBox`, `pageRangeRefused` | PENDING_HOSTED |
| A6 / destination authority | Only Profile-scoped `.pdf` destinations are accepted. | Outside path, unsafe name, symlinked directory. | Native ABI negative test through the live handle. | `IsSupportedPdfExportPath`, `kweb_browser_export_pdf` | `pathRefused` | PENDING_HOSTED |
| A7 / migration | `webContents.printToPDF` is classified `DIRECT` and the migration check passes. | Matrix row review, migration tests, real fixture. | Migration tests and `electronMigrationIntegrationTest`. | `KWebElectronCapabilityMatrix` row `print-to-pdf` | `compatibility.json` per target | PENDING_HOSTED |
| A8 / providers | The declared CEF API is used without fallback on every target. | Missing browser host, UI-thread failure, Windows/Linux execution. | Native ABI tests and three-target probe. | `browser_session.cc` `ExportPdf`, `engine_abi.cc` | Provider evidence per target | PENDING_HOSTED |
| A9 / evidence | The recorder binds contract, runtime, artifact, and matrix facts for all three targets. | Missing artifact, stale digest, unsupported row claim. | Aggregation tests, hosted recording, strict governance. | `aggregate-rfc-evidence.sh`, `contracts.json`, `suites.json` | Manifest records for 0045 | PENDING_HOSTED |
| A10 / docs and matrix | RFC, roadmap, split record, matrix row, and capability report agree. | Governance check and documentation workflow. | `:kweb-rfc-governance:check`, docs workflow. | This RFC, `0026-printing-and-pdf.md`, `README.md` | Governance report | PENDING_HOSTED |
| A11 / completion | One focused squash PR with a clean worktree and the reviewed revision. | Diff review, commit list, worktree state. | Final PR review. | This PR | Squash commit | PENDING_HOSTED |

## Contract review record

- Reviewed revision: the RFC 0045 contract commit on this branch.
- Review pass: same-contributor contract review against the pinned CEF 151
  sources and the local real-CEF probe; not an independent-person approval.
- Date: 2026-10-18.
- Findings and dispositions:
  - RFC 0026 bundles PDF output, interactive printing, printer facts, and silent
    jobs. Interactive printing requires a real print queue per platform and
    cannot be verified in hosted CI, so the objective is split and only PDF
    output is published here.
  - Chromium swaps the declared paper dimensions for a landscape export. The
    contract states that fact and the probe asserts the resulting media box
    instead of pretending the declared pair is the printed box.
  - Registering a `CefPrintHandler` for the Linux default paper size would change
    page-initiated `window.print()` behavior. The probe showed explicit paper
    dimensions avoid the platform delegate entirely, so no print handler is
    registered.
  - An impossible page range is reported through the PDF callback rather than
    rejected up front, so the contract maps it to `pdf.native-failed`.
- Decision: `READY`.

## Merge acceptance record

- Reviewed revision: the final commit of this PR.
- Review pass: same-contributor acceptance review of the complete PR diff
  against the matrix above.
- Date: 2026-10-18.
- Local verification: `:kweb-core:check`, `:kweb-desktop:check`, the native ABI
  contract binaries, and the real-CEF `pdf-export` probe passed on macos-arm64
  (20,804-byte A4 PDF with one page and a 595.92x841.92 media box, a 288x432
  landscape page, typed page-range and path refusals, a closed-read refusal, and
  no leftover artifacts).
- Hosted verification: the three-target verification jobs, the recorded evidence
  manifest, and the strict governance check for the merged revision.
- Result: see the matrix rows once the hosted evidence is imported into this
  RFC.
