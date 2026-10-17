# kweb-service-processes

The RFC 0024 application-scoped managed-process service. It starts only
application-declared packaged executables or JVM worker entry points, under
declared limits, and owns every child tree it created.

The service descriptor declares **no renderer operation**: a page can never name an
executable, an argument, a working directory, or a limit.

## Declared model

```kotlin
val declaration = KWebProcessDeclaration(
    applicationId = "io.github.example.app",
    executables = listOf(
        KWebProcessExecutable.packaged("helper", Path.of("runtime/helper")),
        KWebProcessExecutable.jvmWorker("worker", "io.github.example.WorkerKt", workerClassPath),
    ),
    workingDirectories = listOf(
        KWebProcessWorkingDirectory(KWebProcessWorkingDirectoryId("scratch"), scratch),
    ),
)

val processes = JvmKWebProcesses.open(declaration, nativeLibrary)
val process = processes.start(
    KWebProcessSpec(
        id = KWebProcessId("report"),
        executableId = KWebProcessExecutableId("helper"),
        workingDirectoryId = KWebProcessWorkingDirectoryId("scratch"),
        arguments = listOf("report"),
        environment = mapOf("KWEB_DECLARED" to "1"),
        limits = KWebProcessLimits(
            timeoutSeconds = 30,
            cpuSeconds = 10,
            memoryBytes = 256L * 1024 * 1024,
            stdoutQuotaBytes = 4L * 1024 * 1024,
            stderrQuotaBytes = 1024L * 1024,
        ),
        streams = KWebProcessStreamPolicy(16 * 1024, 16 * 1024),
    ),
)
process.output.collect { chunk -> consume(chunk.stream, chunk.bytes()) }
val termination = process.await()
process.close()
```

## Declared facts

| Fact | v1 rule |
|---|---|
| executable identity | host-declared path and SHA-256; re-verified immediately before launch |
| environment | the declared map is the child's whole environment, never inherited |
| working directory | required, host-declared, verified before launch |
| timeout / CPU / memory | 1..3600 s, 1..3600 s, 16 MiB..16 GiB |
| output quotas | 256 KiB..64 MiB per stream |
| stream window | 4 KiB..1 MiB; a consumer's pace is the backpressure the child observes |
| stdin chunk | at most 64 KiB per call; a non-reading child reports a typed timeout |
| live processes | at most 8 records |
| isolation | the request must be a subset of the provider's declared set |
| renderer | no operation at all |

## Providers

| Target | Provider | What it implements |
|---|---|---|
| macOS arm64 | `processes.macos.posix-exec` | verified `execve`, own process group, `RLIMIT_CPU`, `proc_pidinfo` counters |
| Windows x64 | `processes.windows.job-object` | `CreateProcessW`, job object kill-tree and committed-memory guard, `GetProcessTimes`/`K32GetProcessMemoryInfo` |
| Linux x64 | `processes.linux.posix-exec` | verified `execve`, own process group, `RLIMIT_CPU`, `PR_SET_PDEATHSIG`, `/proc` counters |

Every packaged provider declares `PROCESS_TREE` and `RESOURCE_LIMITS`. A request
for `SEATBELT`, `SECCOMP`, `NAMESPACES`, `SYSTEMD_SCOPE`, or `RESTRICTED_TOKEN` is
refused with `processes.isolation-unsupported` before anything starts.

## Boundaries

- No shell, no `PATH` lookup, no renderer-provided executable or argument.
- No address-space rlimit: a declared *resident* cap must not clip a legitimate
  worker's virtual reservations, so the cap is enforced by the provider monitor and
  the reported peak can exceed the cap by one 100 ms sampling window.
- One output consumer per process; a second collection fails typed.
- Process output is application data: retained evidence records identifiers and
  counters only, never a path, argument, process id, or handle.

## Verification

`./gradlew :kweb-service-processes:check` runs the native ABI and provider structure
tests with real child processes, the JVM provider integration (including a real JVM
worker entry point), and the exact native package verification.
