# RFC 0024: Policy-controlled managed processes

- Status: Implemented
- Priority: P1
- Owners: new `kweb-service-processes` KMP service
- Depends on: RFC 0002, RFC 0003, RFC 0004, RFC 0013
- Electron migration surface: `utilityProcess`, Node `child_process`, `parentPort`, native addons
- Target mapping: `REWRITE`

## Objective and scope

Run application-declared executables or JVM worker entry points under explicit
policy, resource limits, typed streams, and owner shutdown for Windows x64,
macOS arm64, and Linux x64. KWebShell does not ship Node.js and never executes
renderer-provided code.

One implementation objective delivers:

1. the typed immutable process specification — a packaged executable identifier,
   validated arguments, a declared environment allowlist, a declared working
   directory, bounded stdin/stdout/stderr policies, time and memory limits, and a
   required isolation set — plus the application-scoped closeable
   `KWebManagedProcess` handle;
2. exactly one provider per target: a verified `execve` child in its own process
group on macOS and Linux, and `CreateProcessW` inside a job object on Windows;
3. identity binding: the host declares each packaged executable with the digest it
   read, and the provider re-verifies that digest immediately before launch, so a
   PATH lookup, a relative or shell string, or a replaced file never starts;
4. real limits: a declared wall-clock timeout, CPU quota, resident-memory cap, and
   per-stream output quotas, each ending the whole tree with its own typed reason
   and no orphan process;
5. ordered, bounded, backpressured stdout/stderr for RFC 0004 consumers and a
   bounded stdin path;
6. the closed Electron `utilityProcess`/`child_process` mapping, real three-target
   evidence, documentation, and the final acceptance review.

## Non-goals

No shell, terminal emulator, Node runtime, npm package execution, arbitrary
dynamic library loading, `PATH` lookup, renderer-supplied executable or argument,
unbounded process, or claim of identical Chromium utility-process networking. No
sandbox profile the packaged provider does not implement is ever substituted by a
weaker one.

## Common implementation contract

```kotlin
public interface KWebProcesses : KWebNativeService {
    public suspend fun capabilities(): KWebProcessCapabilities
    public suspend fun start(spec: KWebProcessSpec): KWebManagedProcess
    public suspend fun processes(): List<KWebProcessSummary>
}

public interface KWebManagedProcess : AutoCloseable {
    public val id: KWebProcessId
    public val executableId: KWebProcessExecutableId
    public val state: StateFlow<KWebProcessState>
    public val output: Flow<KWebProcessOutputChunk>
    public val termination: Flow<KWebProcessTermination>
    public suspend fun writeStdin(bytes: ByteArray): Int
    public suspend fun closeStdin()
    public suspend fun kill(): KWebProcessTermination
    public suspend fun await(): KWebProcessTermination
    override fun close()
}
```

The v1 descriptor is version `1.0.0`, service id `processes`, scope
`APPLICATION`, and publishes `capabilities`, `start`, and `processes` as host
operations. It declares **no renderer operation**: a page can never name an
executable, an argument, a working directory, or a limit, so renderer-provided
code is not a launch surface at all.

### Values and bounds

| Value | v1 rule |
|---|---|
| process id | opaque ASCII `[A-Za-z][A-Za-z0-9._-]{0,127}`, unique among live processes |
| executable id | the same form; resolved only through the host declaration |
| working directory id | the same form; resolved only through the host declaration |
| arguments | at most 64 entries, each at most 4096 UTF-8 bytes, no NUL; Unicode is ordinary declared bytes |
| environment | at most 64 `NAME=value` entries; `NAME` matches `[A-Za-z_][A-Za-z0-9_]{0,63}`, value at most 4096 bytes; this map is the child's whole environment and is never inherited |
| timeout | whole seconds 1..3600 |
| CPU quota | whole seconds 1..3600 |
| memory cap | 16 MiB..16 GiB, enforced on the child's resident set |
| output quota | 256 KiB..64 MiB per declared stream |
| stream window | 4 KiB..1 MiB per declared stream |
| stdin chunk | at most 65536 bytes per call |
| output chunk | at most 65536 bytes per delivered chunk |
| live processes | at most 8 records per service |
| isolation | the required set always contains `PROCESS_TREE`; every requested profile must be declared by the provider |

A fact a provider does not implement is absent from its capability report and
fails typed on use; it is never mapped onto a weaker mechanism.

### Lifecycle, concurrency, and limits

- `start` is atomic per specification: it verifies the working directory, the
executable digest, the isolation set, and every limit before anything is created;
any failure starts nothing and leaves no record.
- The whole tree is the unit of ownership: the macOS/Linux child leads its own
process group and the Windows child lives inside its own job object, so ending the
process ends every descendant it created.
- Exactly one terminal result is reported per process. `EXITED` and `CRASHED`
describe the child's own exit; `TIMEOUT`, `CPU_LIMIT`, `MEMORY_LIMIT`, and
`OUTPUT_LIMIT` are declared limits; `KILLED` and `OWNER_CLOSED` describe an
explicit termination.
- The CPU quota is enforced by the operating system on POSIX (`RLIMIT_CPU` signals
`SIGXCPU` at the declared quota, mapped to `CPU_LIMIT`) and by the sampled
accounting on Windows. The resident cap is enforced on the child's own resident
counter, sampled every 100 ms, so the reported peak can exceed the declared cap by
what a fast allocator produced inside one sampling window.
- Output quotas are enforced on bytes the child produced, not on bytes a consumer
read; a stream that reaches its quota ends the tree with `OUTPUT_LIMIT` and no
byte beyond the quota is delivered or retained.
- `stdin` accepts one bounded chunk per call. A child that stops reading reports
`processes.stdin-timeout` instead of blocking a host thread, and a closed child
pipe reports `processes.stdin-closed`; the service ignores `SIGPIPE` on POSIX so
the host process is never terminated by a closed child stdin.
- `output` is one ordered stream: a cold, single-consumer flow over the provider
window. Sequences are strictly increasing and never repeat, the declared window is
the real backpressure bound a consumer's pace imposes on the child, and the
terminal result is published separately so an owner can await it without consuming
output. One process cannot be consumed by two collectors at once.
- `close` terminates the whole tree, publishes `OWNER_CLOSED` when the process was
still live, and releases the provider record exactly once. The service `close`
terminates every live tree before releasing its provider; a failure while closing
is reported once as a sticky failure.

### Errors, renderer, and migration policy

- Every rejection has a stable `processes.*` code; there is no shell, `PATH`
lookup, weaker isolation, or unowned child.
- The service descriptor exposes no renderer operation. Process output is application
data the host chose to collect; it is never a renderer capability.
- The Electron mapping is `REWRITE`: `utilityProcess.fork` and Node `child_process`
spawn/exec workflows rewrite to a declared packaged executable or JVM worker with
an anchored digest, a declared environment, and a declared working directory. The
request/response shape of `utilityProcess` becomes a typed stdin/stdout exchange
over the ordered stream. Arbitrary Node modules, `parentPort`-style implicit
messaging, and native addons remain blockers.

| Condition | Stable error |
|---|---|
| malformed identifier | `processes.id-invalid` |
| malformed argument or too many arguments | `processes.argument-invalid` |
| malformed environment entry, name, value, or count | `processes.environment-invalid` |
| timeout, CPU, memory, or quota outside its published range | `processes.limit-invalid` |
| stream window outside its published range | `processes.stream-invalid` |
| required isolation set without the process tree | `processes.isolation-invalid` |
| isolation profile the provider does not declare | `processes.isolation-unsupported` |
| executable identifier the host never declared | `processes.executable-unknown` |
| declared executable file absent at launch | `processes.executable-missing` |
| declared executable digest does not match the file | `processes.executable-unverified` |
| working directory absent, not a directory, or undeclared | `processes.working-directory-invalid` |
| the provider could not create the child | `processes.launch-failed` |
| the live-record limit is reached | `processes.limit-exceeded` |
| unknown process handle | `processes.process-unknown` |
| stdin write after the child closed its stdin | `processes.stdin-closed` |
| stdin write the child did not accept within the budget | `processes.stdin-timeout` |
| stdin chunk or empty payload outside its bound | `processes.stdin-bounds` |
| a second consumer of one output stream, or an unbounded read | `processes.output-bounds` |
| the declared platform facility is missing | `processes.platform-unavailable` |
| native provider failure | `processes.native-failed` |
| use after close | `processes.owner-closed` |

### Platform implementation and feasibility

| Target | Provider | Declared behavior |
|---|---|---|
| macOS arm64 | `processes.macos.posix-exec` | `execve` of the verified absolute path in a child that leads its own process group, with the declared environment as the whole environment, the declared working directory, and `RLIMIT_CPU`/`RLIMIT_NOFILE` guards; resident bytes and mach-time CPU come from `proc_pidinfo(PROC_PIDTASKINFO)`; `SIGXCPU` maps to `CPU_LIMIT`; the group kill is the tree kill |
| Windows x64 | `processes.windows.job-object` | `CreateProcessW` with an explicit inherited-handle list and `CREATE_UNICODE_ENVIRONMENT`, assigned to a job object with `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE` and a committed-memory guard above the declared cap; `PeekNamedPipe`/`ReadFile` for the declared streams and a provider-owned writer thread for stdin; `GetProcessTimes` and `K32GetProcessMemoryInfo` for the monitor; `TerminateJobObject` is the tree kill. A CRT `abort()` reports exit code 3, which this provider classifies as `CRASHED`, so an explicit exit code 3 is not distinguishable from a crash on this target |
| Linux x64 | `processes.linux.posix-exec` | the same verified `execve`, process group, and rlimit design as macOS, plus `PR_SET_PDEATHSIG`; resident bytes and CPU come from `/proc/<pid>/status` and `/proc/<pid>/stat`; `SIGXCPU` maps to `CPU_LIMIT`; the group kill is the tree kill |

Every packaged provider declares `PROCESS_TREE` and `RESOURCE_LIMITS` and nothing
else; a request for `SEATBELT`, `SECCOMP`, `NAMESPACES`, `SYSTEMD_SCOPE`, or
`RESTRICTED_TOKEN` is refused before launch. No address-space rlimit is installed
on Linux: a declared *resident* cap must never clip a legitimate worker's virtual
reservations (a JVM reserves far more address space than its working set), so the
resident cap is enforced by the same sampled monitor as on macOS.

### Evidence lifecycle

The RFC binds `kweb-service-processes/src`, `kweb-service-processes/native`,
`kweb-service-processes/build.gradle.kts`, the migration kit, the governance service
catalog, the CI planner metadata, and `settings.gradle.kts` in
`docs/rfcs/evidence/contracts.json`. Records expire when the service version, the C
ABI, a provider identity, or the pinned CEF/Chromium identity changes.

Retained evidence carries declared identifiers, target and provider identity,
declared bounds, scenario outcomes, byte counts, and the live-process count after
owner shutdown. It never carries a resolved executable path, an argument value, a
process id, a raw handle, or child output bytes.

## Acceptance matrix

| ID / source clause | Observable requirement | Normal, negative and boundary scenarios | Planned verification / required targets | Implementation and test references | Retained evidence / tested revision | Result / review rationale |
|---|---|---|---|---|---|---|
| A1 / common model | Identifiers, arguments, environment, limits, streams, and isolation requirements are bounded and immutable, and an invalid specification fails typed before a provider sees it. | Boundary identifiers, over-long argument, too many arguments, NUL argument, invalid environment name/value/count, out-of-range timeout/CPU/memory/quota/window, missing process tree. | Common contract tests. | `KWebProcesses.kt`; `KWebProcessesContractTest`. | Local and hosted records. | `PASS` — `KWebProcessesContractTest` covers every bound and proves Unicode arguments are accepted while an out-of-range limit fails typed. |
| A2 / executable identity | Only a host-declared executable runs, and the provider verifies its digest and working directory before anything starts. | Declared helper, undeclared identifier, relative or bare path, directory as executable, replaced file after declaration, absent working directory. | Native ABI tests plus the JVM integration fixture. | Provider digest verification; `kweb_processes_start`; `ProcessesIntegrationMain`. | Native and integration transcripts. | `PASS` — the ABI refuses an undeclared, relative, missing, or directory target, and a helper copy altered after its declaration reports `processes.executable-unverified` with no process created. |
| A3 / no renderer launch surface | The default descriptor has no renderer operation and no renderer path can name an executable, an argument, or a directory. | Descriptor shape, operation permission, gesture requirement. | Contract test and governance review. | `KWebProcesses.DESCRIPTOR`; `KWebProcessesContractTest`. | Contract transcript. | `PASS` — every published operation carries a null renderer permission and no gesture requirement, so a page has no launch path at all. |
| A4 / declared environment and working directory | The declared environment map is the child's whole environment, and the child runs in the declared working directory. | Inherited variable absent, both declared names present, exact cwd, Unicode argument bytes. | Real child test on all three targets plus the JVM integration. | Provider `execve`/`CreateProcessW`; `processes_structure_shared.cc`; `ProcessesIntegrationMain`. | Structure transcript. | `PASS` — the helper reports exactly the two declared names (an inherited probe variable is absent), the exact declared directory, and the exact hex of its Unicode argument on macOS and Linux; the hosted Windows run repeats it. |
| A5 / ordered bounded streams | stdout and stderr arrive in one strictly increasing order, the declared window is real backpressure, and no byte is dropped or duplicated. | 8 MiB burst with a minimal window and a slow consumer; 2 MiB burst in the JVM integration; declared byte count. | Real child test on all targets plus the JVM integration. | ABI reader threads and windows; `processes_structure_shared.cc`; `ProcessesIntegrationMain`. | Structure transcript with chunk counts. | `PASS` — the 8 MiB burst arrives byte-exact and in order with strictly increasing sequences under a 4 KiB window, and the integration records its ordered chunk and byte counts for a 2 MiB burst under the same slow consumer. |
| A6 / output quotas | A stream that reaches its quota ends the tree with `OUTPUT_LIMIT`, delivers no byte beyond the quota plus one read, and leaves no orphan. | stdout quota, stderr quota, consumer that drains, consumer that does not drain. | Real child test on all targets plus the JVM integration. | Quota check in the ABI reader; `processes_structure_shared.cc`; `ProcessesIntegrationMain`. | Structure transcript. | `PASS` — both streams report `OUTPUT_LIMIT` within the declared bound, and a process whose output is not consumed instead applies the declared window as backpressure until a declared limit ends it. |
| A7 / timeout, CPU, and memory limits | Each declared limit ends the whole tree with its own typed reason, records the accounting, and leaves no orphan. | 1 s timeout on a 30 s child; 1 s CPU quota on a spin child; 64 MiB cap on a 256 MiB child. | Real child test on all targets. | Monitor thread; `SetCpuGuard`; `processes_structure_shared.cc`. | Structure transcripts with recorded accounting. | `PASS` — macOS reports `TIMEOUT`, `CPU_LIMIT` with `SIGXCPU` at the declared quota, and `MEMORY_LIMIT` with a recorded peak above the cap; Linux reports the same; the hosted Windows run reports `CPU_LIMIT` and `MEMORY_LIMIT` from the sampled accounting. |
| A8 / kill tree and owner shutdown | Ending a process ends every descendant, and an owner shutdown leaves no child and no handle. | `tree` helper, explicit `kill`, `release` of a live process, service close with eight live processes, live-record limit. | Real child test on all targets plus the JVM integration. | Process group / job object; `processes_structure_shared.cc`; `ProcessesIntegrationMain`. | Structure transcript and `liveProcessesAfterClose`. | `PASS` — one kill ends the announced descendant (checked through the operating system), releasing one handle kills exactly its own tree, and closing the service leaves `liveProcessesAfterClose` at zero. |
| A9 / bounded stdin | stdin accepts bounded chunks, refuses an over-long chunk, reports a typed timeout for a child that does not read, and reports a closed pipe. | 96 KiB round trip, 64 KiB chunks against a non-reading child, write after termination, over-long write. | Real child test on all targets plus the JVM integration. | Owner-side queue and provider writer thread; `processes_structure_shared.cc`; `ProcessesIntegrationMain`. | Structure transcript. | `PASS` — the round trip echoes exactly the accepted bytes, a non-reading child reports `processes.stdin-timeout` within the budget, and the closed pipe reports `processes.stdin-closed`. |
| A10 / crash classification | A child that dies abnormally reports `CRASHED` with its platform termination code, distinct from a declared limit. | `abort()` helper on all targets. | Real child test on all targets. | Reap and signal mapping; `processes_structure_shared.cc`. | Structure transcript. | `PASS` — the helper's `abort()` reports `CRASHED` with `SIGABRT` on macOS/Linux and the provider's abnormal-exit marker on Windows. |
| A11 / JVM worker entry points | A JVM worker runs as an ordinary managed process with the declared limits and no lifetime of its own. | Declared worker main class, real class path, normal exit, memory measured. | JVM integration fixture. | `KWebProcessExecutable.jvmWorker`; `ProcessesWorkerMain`; `ProcessesIntegrationMain`. | Integration report. | `PASS` — the fixture starts a real JVM worker through the shipped launcher declaration, reads its ready line, and records a normal `EXITED` result with measured memory. |
| A12 / no fallback | A missing provider, undeclared isolation, unverified identity, or out-of-range limit fails typed and never falls back to a shell, PATH lookup, weaker isolation, or another launcher. | Missing library, undeclared isolation profile, replaced executable, all boundary limits. | Native ABI tests and provider review. | ABI validation order; provider declarations. | ABI transcript. | `PASS` — every refusal is typed before launch, and no provider performs a PATH lookup, shell interpolation, or weaker isolation. |
| A13 / migration | The Electron `utilityProcess`/Node `child_process` mapping rewrites declared workflows and keeps arbitrary Node modules and native addons blocked. | Matrix row, inventory classification, fixture service declaration, golden checklist. | Migration tests and governance. | Matrix row and fixture. | Migration compatibility report. | `PASS` — the capability matrix publishes `process` as `REWRITE` for `KWebProcesses`, the scanner classifies both symbols, and the migration fixture declares the service. |
| A14 / packaging and docs | Native libraries, generated output, and documentation agree, and the package contains only the provider and its ABI header. | Native package contents and packaged header scan. | Gradle package verification, governance, and the module README. | `verifyNativeProcessesPackage`; `README.md`. | Package digest report. | `PASS` — `verifyNativeProcessesPackage` checks the exact package contents and retains the digest, and the module README documents the providers, the limits, and the renderer boundary. |
| A15 / evidence redaction | Retained evidence carries declared identifiers and counters, never a path, an argument, a process id, or a handle. | Fixture report contents and the native transcripts. | Evidence collector and retained artifacts. | `ProcessesIntegrationMain`; `aggregate-rfc-evidence.sh`. | Retained transcripts. | `PASS` — the retained report records the provider, declared isolation, scenario outcomes, chunk and byte counts, and the live count after close; it carries no path, argument, process id, or handle. |
| A16 / universal completion | Complete implementation, tests, real target evidence, migration, docs, matrix, and one focused squash PR. | Skipped target, stale evidence, partial API, unsupported advertised field. | Full PR diff review, hosted matrix, strict governance, final acceptance record. | This RFC's merge acceptance record. | Three READY records and retained artifacts. | `PASS` — the hosted three-target matrix and the aggregation job of this pull request import the three `processes.hosted` records before merge. |

## Contract review record

- Reviewed contract revision: `a603a592` plus this reviewed contract; the review
  pass is recorded honestly as a same-contributor review, not an independent
  approval.
- Reconnaissance recorded before implementation (real probes, not assumptions):
  - macOS arm64: `proc_pidinfo(PROC_PIDTASKINFO)` reports the child's resident set
    and CPU time for a child the host owns, without an entitlement. The CPU fields
    are in mach absolute time units, not nanoseconds: a child that burned exactly
    1.496 s of CPU measured 35,908,000 ticks with a timebase of 125/3 ns, which is
    the declared quota accounting this provider converts. `setrlimit(RLIMIT_AS)`,
    `RLIMIT_DATA`, and `RLIMIT_RSS` fail with `EINVAL` on this target, so a resident
    cap cannot be a kernel rlimit and is enforced by the sampled monitor;
    `RLIMIT_CPU` delivers `SIGXCPU` at the declared quota; `getpgid(child)` equals
    the child's own pid and one `kill(-pgid, SIGKILL)` ends the tree; `execve` with
    a declared `envp` leaves the child with exactly the declared variables.
  - Linux: the same probe shape confirms `/proc/<pid>/status` `VmRSS` and
    `/proc/<pid>/stat` `utime`/`stime` are readable for the host's own child,
    `PR_SET_PDEATHSIG` is available, the process group kill ends the tree, the
    declared `envp` filters the environment, and `RLIMIT_CPU` signals `SIGXCPU` at
    the declared quota. `RLIMIT_AS` *is* enforced, but it also clips the virtual
    reservations an emulator or a JVM needs (an emulated x86_64 child aborted with
    `SIGTRAP` inside its allocator), which is why the packaged Linux provider
    installs no address-space rlimit and enforces the declared resident cap with the
    same monitor as macOS.
  - Windows: the declared facilities are `CreateProcessW` with
    `PROC_THREAD_ATTRIBUTE_HANDLE_LIST`, a job object with
    `JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE`, `PeekNamedPipe`/`ReadFile`,
    `GetProcessTimes`, `K32GetProcessMemoryInfo`, and `TerminateJobObject`; there is
    no local Windows host, so the provider is verified by the hosted Windows run.
- Open questions settled:
  - The service publishes host operations only. Renderer-provided code is not a
    launch surface, so no renderer grant, gesture, or consent path exists for
    processes.
  - Isolation is a required set that must be a subset of what the provider
    declares; the sandbox profiles no packaged provider implements are refused
    before launch instead of being approximated.
  - The declared memory cap is a resident measure enforced by a 100 ms monitor, not
    a virtual-address rlimit, so a legitimate JVM worker's reservations are never
    clipped; the reported peak can exceed the cap by one sampling window.
  - Output is one ordered stream with a cold single-consumer flow over the
    provider window, so a consumer's pace is the real backpressure and no unbounded
    buffer exists; a process nobody consumes is bound by its declared limits.
  - The stdin path is a bounded owner queue drained by a provider writer thread, so
    a non-reading child reports a typed timeout instead of blocking a host thread;
    the service ignores `SIGPIPE` on POSIX as a declared process-wide effect.
- Decision: `READY`.

## Merge acceptance record

- Reviewed implementation revision: the ABI, providers, and tests of this branch,
  followed by the Kotlin service, migration rows, governance fixture, and CI
  metadata.
- Review pass: Delta agent, same contributor as the implementation; the review is
  recorded honestly and is not an independent-person approval.
- Date: 2026-10-17.
- Full PR diff reviewed: the typed contract and descriptor, the versioned C ABI with
  its ordered bounded stream queue, stdin queue, monitor, and terminal-reason
  precedence, the shared macOS/Linux provider, the Win32 job-object provider, the
  native helper and its target structure tests, the FFM binding with its layout
  guard, the JVM session and declaration API with the JVM worker entry point, the
  integration fixture, the migration capability-matrix row, and the change-aware CI
  planner, workflow, and evidence collector wiring.
- Row-by-row coverage: A1-A16 are `PASS` against the tests listed in the matrix;
  A16 depends on the hosted import in this same pull request. No requirement is
  silently excluded.
- Findings and dispositions: the ABI validates limits before the provider sees a
  request, so a bad duration fails identically everywhere; the macOS CPU counters
  were initially misread as nanoseconds and are now converted through
  `mach_timebase_info`; the Linux address-space rlimit was removed because it
  clipped a legitimate child's virtual reservations; the output quota now stops the
  reader instead of reading past it; the stdin path was moved to an owner queue plus
  a provider writer thread so a non-reading child cannot block a host thread; and the
  output stream is a cold single-consumer flow so a late consumer never loses a byte.
- Hosted references: the three-target matrix run, its aggregation job, and the
  strict checked-in evidence verification of this pull request.
- Decision: `PASS` — the capability is published only when the aggregation imports
  the three `processes.hosted` records and strict governance passes at this revision.
