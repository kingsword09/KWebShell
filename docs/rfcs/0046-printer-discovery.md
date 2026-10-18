# RFC 0046: Printer discovery and supported facts

- Status: Implemented
- Priority: P1
- Owners: new `kweb-service-printers` KMP service
- Depends on: RFC 0002, RFC 0003
- Electron migration surface: printer enumeration used by `webContents.print` `deviceName`; print submission itself stays with RFC 0026
- Target mapping: `REWRITE`

## Objective and scope

Publish one application-scoped printer-discovery service for Windows x64,
macOS arm64, and Linux x64. The service reports the host's printers exactly as
the platform print subsystem publishes them:

1. a bounded, immutable snapshot of printer names, descriptions, locations,
   default selection, and the state the backend reports;
2. per-printer facts only where the backend publishes them: color support,
   duplex support, and the media names it lists;
3. a typed empty observation and a typed failure when the host has no print
   subsystem, so a caller can never mistake an absent query for an empty host.

This RFC is one objective carved out of [RFC 0026](0026-printing-and-pdf.md).
It is a discovery contract: it never submits, spools, cancels, or observes a
print job. Print submission, print dialogs, and job lifecycle stay with RFC 0026
and its own review, and the split record there explains why the pinned runtime
cannot deliver them as verified hosted evidence.

## Non-goals and applicability

- No print submission, no print dialog, no spooler job, no job state, and no
  silent-print policy.
- No renderer access. The service publishes one host-only operation and no
  renderer grant, so a page cannot enumerate the user's devices.
- No device mutation: adding, removing, or configuring a queue is not exposed.
- No fallback: a host whose print subsystem is missing fails with
  `printers.unavailable`, and a backend attribute that is not published stays
  `null` instead of being defaulted.

Universal completion areas map as follows: common contract and bounds A1-A2;
provider fidelity A3; empty and absent hosts A4; lifecycle A5; authority A6;
evidence A7; documentation and capability reporting A8; completion A9.

## Implementation contract

### Common KMP contract

The service lives in `kweb-service-printers` and publishes
`KWebPrinters : KWebNativeService`:

```kotlin
public interface KWebPrinters : KWebNativeService {
    override val descriptor: KWebServiceDescriptor
    override val lifecycle: StateFlow<KWebLifecycleState>
    public suspend fun list(): KWebPrinterSnapshot
}
```

The v1 descriptor is version `1.0.0`, service id `printers`, scope
`APPLICATION`, with exactly one operation: host-only `list` (schema version 1,
no renderer permission, no user gesture, no OS consent).

| Field or operation | Type / input constraints | Required / default / null behavior | Observable result | Acceptance IDs |
|---|---|---|---|---|
| `KWebPrinterSnapshot.provider` | 1-64 UTF-8 bytes without NUL | required | names the provider that produced the snapshot | A1 |
| `KWebPrinterSnapshot.printers` | 0-256 entries, unique names, at most one default | empty list is a valid observation | the host's printers | A1, A4 |
| `KWebPrinter.name` | 1-256 UTF-8 bytes without NUL; the exact queue or device name | required | identity a later print request can name | A1, A3 |
| `KWebPrinter.info` / `.location` | 0-512 UTF-8 bytes without NUL | `null` when the backend publishes nothing | free-form device text | A1, A3 |
| `KWebPrinter.isDefault` | Boolean, at most one per snapshot | `false` when the host has no default | default selection | A3 |
| `KWebPrinter.state` | `IDLE`, `PROCESSING`, `STOPPED`, `UNKNOWN` | `UNKNOWN` when no usable state attribute exists | reported device state | A3 |
| `KWebPrinterFacts.colorSupported` | `Boolean?` | `null` when the backend does not publish color support | color fact | A3 |
| `KWebPrinterFacts.duplexSupported` | `Boolean?` | `null` when the backend does not publish sides support | duplex fact | A3 |
| `KWebPrinterFacts.mediaSizes` | 0-32 unique names, each 1-64 UTF-8 bytes | empty when the backend publishes no media list | media names | A3 |

### Lifecycle, concurrency, and limits

| Current state | Trigger / race | Preconditions and thread | Next state / terminal result | Resource effects | Acceptance IDs |
|---|---|---|---|---|---|
| `OPEN` | `list` | - | snapshot returned; no provider state retained | none | A1, A3 |
| `OPEN` | more than 256 printers | - | `printers.limit-exceeded` instead of a truncated list | none | A2 |
| `OPEN` | backend attribute query fails | - | `printers.query-failed` naming the printer and category | none | A3 |
| `OPEN` | print subsystem missing | - | `printers.unavailable` | none | A4 |
| `CLOSING`/`CLOSED` | `list` | - | `service.owner-closed` | none | A5 |
| `CLOSED` | `close` | - | idempotent | provider scope cancelled once | A5 |

Calls are serialized per service and run on an IO dispatcher; the snapshot is
immutable, so concurrent readers never observe a partially built list.

### Errors, renderer, and migration policy

| Condition | Stable error / outcome | Acceptance IDs |
|---|---|---|
| No print subsystem on the host | `printers.unavailable` | A4 |
| Attribute query failed | `printers.query-failed` | A3 |
| More printers than the declared bound | `printers.limit-exceeded` | A2 |
| Malformed provider value | `printers.contract-invalid` | A1 |
| Query after close | `service.owner-closed` | A5 |

The operation is host-only and has no renderer grant, so the generated bridge
exposes nothing. The migration matrix keeps `webContents.print` unclaimed: this
RFC publishes discovery, not printing, and a migration that needs print
submission remains blocked on RFC 0026.

### Platform implementation and feasibility

| Target | Exact provider | Thread / owner / availability constraints | Probe and result | Acceptance IDs |
|---|---|---|---|---|
| macOS arm64 | JDK print service (`javax.print`), backed by CUPS | query on an IO thread; no UI session required | local probe: host and service both report zero printers | A3, A4 |
| Windows x64 | JDK print service, backed by the WinSpool print subsystem | same | hosted `printers-integration` job comparing with `Get-Printer` | A3, A4 |
| Linux x64 | JDK print service, backed by CUPS | same | hosted `printers-integration` job comparing with `lpstat` | A3, A4 |

Feasibility facts established by reading the pinned runtime and by the real
probe:

- `PrintServiceLookup` is the JDK's platform print API: it dispatches to CUPS on
  macOS and Linux and to the WinSpool print subsystem on Windows, so the service
  needs no native library of its own and cannot silently substitute a backend.
- A host with no queues yields an empty array and a null default, which the
  service publishes as an empty snapshot rather than an error.
- `lpstat` localizes its prose but keeps its `lpstat:` error prefix and the queue
  name as the second token of a printer line, so the cross-check does not depend
  on the runner locale.

### Evidence lifecycle and delivery

Bound contract paths: the service module, this RFC, the aggregate script, the CI
family wiring, and the governance dependency. The recorded artifact is
`printers-integration/printers-integration.json` from each verification job.

Any change to the snapshot fields/bounds, provider identity, or error mapping
invalidates the affected target records. The sequence is: contract tests, the
real provider test on each target, the cross-check probe against the host tool,
evidence aggregation, strict governance, and the final row-by-row review.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / contract | The snapshot model publishes the declared fields with the declared bounds. | Empty values, 257-byte name, duplicate names, two defaults, 33 media names. | Common contract tests. | `KWebPrinters.kt`, `KWebPrintersContractTest.kt` | Contract digest per record | `PASS` — the contract tests cover empty values, a 257-byte name, duplicate names, two defaults, and a 33rd media name; all three targets bind the same contract digest. |
| A2 / bounds | A host with more printers than the bound fails instead of truncating. | 257 printers. | Contract tests plus code review of the provider guard. | `KWebPrintersErrorCode.LIMIT_EXCEEDED`, provider guard | Contract digest | `PASS` — the provider refuses a host above the bound with `printers.limit-exceeded` instead of truncating, and the contract tests pin the snapshot ceiling. |
| A3 / provider fidelity | Names, default selection, descriptions, state, and published facts come from the backend unchanged. | Real host enumeration, attribute presence/absence, hostile attribute query. | Real provider test plus the cross-check probe on all three targets. | `JvmKWebPrinters.kt`, `JvmKWebPrintersTest.kt`, `PrintersIntegrationMain.kt` | `printers-integration.json` per target | `PASS` — every target matched the host tool exactly (`matchesOs=true`, `defaultMatches=true`); the Windows host published 2 printers with media and color facts for both and no duplex fact, which the snapshot reports as `null`. |
| A4 / empty and absent host | An empty host is an empty snapshot; a missing print subsystem is typed. | Zero printers on macOS/Linux, non-empty Windows host, unavailable backend. | Cross-check probe on all three targets. | `observeCups`/`observeWindows`, `printers.unavailable` | `osCount`/`serviceCount`/`matchesOs` | `PASS` — macOS and Linux recorded `serviceCount=0`/`osCount=0` and Windows recorded `serviceCount=2`/`osCount=2`, so both the empty and the populated observation agree with the host. |
| A5 / lifecycle | A closed service refuses queries and close is idempotent. | Query after close, double close. | Provider unit test. | `JvmKWebPrintersTest.aClosedServiceRefusesFurtherQueries` | Test symbol | `PASS` — the provider test queries after close, asserts `service.owner-closed`, and closes twice. |
| A6 / authority | The service is host-only and adds no renderer surface. | Bridge surface review. | Code review of the descriptor and generated outputs. | `KWebPrinters.DESCRIPTOR` (no renderer permission) | Review note | `PASS` — the descriptor publishes one operation with no renderer permission, and the generated bridge outputs contain no printer surface. |
| A7 / evidence | The recorder binds the contract digest and the probe artifact for all three targets. | Missing artifact, stale digest. | Aggregation tests, hosted recording, strict governance. | `aggregate-rfc-evidence.sh`, `contracts.json`, `suites.json` | Manifest records for 0046 | `PASS` — run 39116259913 recorded three `READY` records with the probe artifact and contract digest for every target. |
| A8 / docs and reporting | RFC, split record, README, and capability reporting agree and the print row stays unclaimed. | Governance check, documentation workflow. | `:kweb-rfc-governance:check`, docs workflow. | This RFC, `0026-printing-and-pdf.md`, `README.md` | Governance report | `PASS` — the documentation workflow passed for this revision, the governance report resolves no print row, and `webContents.print` stays unclaimed. |
| A9 / completion | One focused squash PR with a clean worktree and the reviewed revision. | Diff review, commit list, worktree state. | Final PR review. | This PR | Squash commit | `PASS` — the PR contains only the RFC 0046 objective; the worktree is clean and the reviewed revision is the PR head. |

## Contract review record

- Reviewed revision: the RFC 0046 contract commit on this branch.
- Review pass: same-contributor contract review against the pinned runtime and
  the local probe; not an independent-person approval.
- Date: 2026-10-18.
- Findings and dispositions:
  - RFC 0026 needs print submission, print dialogs, and job lifecycle, which the
    pinned Alloy runtime cannot expose on all three targets: CEF disables print
    preview in Alloy, so Windows and macOS fall back to native dialogs that
    hosted jobs cannot drive, and Linux requires an application-provided
    `CefPrintHandler` dialog plus its own spool submission. That remainder is
    recorded as blocked in RFC 0026 instead of being published as a partial API.
  - Discovery is the part of RFC 0026 that is verifiable against the host's own
    tooling on every target, so it is delivered as this objective.
  - A discovery contract without print submission is still a complete
    capability on its own terms: it answers "what does this host publish?" with
    exact names, and a migration that needs those names is not blocked by it.
  - The JDK print service is the runtime's platform print API and dispatches to
    CUPS or WinSpool itself; no second backend, shell parsing, or fallback is
    introduced.
- Decision: `READY`.

## Merge acceptance record

- Reviewed revision: the final commit of this PR.
- Review pass: same-contributor acceptance review of the complete PR diff
  against the matrix above.
- Date: 2026-10-18.
- Local verification: `:kweb-service-printers:check` passed on macos-arm64 with
  the host and the service both reporting zero printers, matching
  `printers-integration.json`.
- Hosted verification: run [39116259913](https://github.com/kingsword09/KWebShell/actions/runs/39116259913)
  recorded three `READY` records: macOS and Linux matched `lpstat` with zero
  printers, and Windows matched `Get-Printer` with two printers whose media and
  color facts the backend publishes.
- Result: every row is `PASS`; the evidence is imported into this PR and the
  checked-in manifest and catalog are governed together.
