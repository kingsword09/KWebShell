# JNI to FFM Migration Feasibility Analysis

- Status: Planned feasibility gate for Phase 8
- Date: 2026-08-13
- Target runtime: JDK 25 LTS

## Executive decision

Migrating KWebShell's JVM/native binding from JNI to the Java Foreign Function
and Memory API is technically feasible because the browser engine already
exposes a small, versioned C ABI with opaque integer handles. The migration is
not currently an implementation objective. It belongs after distribution and
hardening, when the C ABI, Compose host contract, DevTools, and extension
surfaces have stabilized.

When Phase 8 begins, it will be a one-time breaking replacement:

- upgrade the desktop runtime from JDK 21 to JDK 25 LTS;
- replace JNI downcalls and callbacks with FFM downcalls and upcall stubs;
- remove the JNI library and JNI-specific tests in the same objective;
- run the complete real-CEF contract on all advertised desktop targets;
- do not ship selectable JNI and FFM backends.

The critical blocker is not calling the C ABI. It is acquiring an exact native
parent from a Compose/AWT component. The current implementation uses JAWT,
which requires `JNIEnv*` and a Java object reference. FFM deliberately models C
data and functions, not Java object handles. Pure FFM is therefore blocked
until KWebShell can obtain the raw `HWND`, AppKit parent, or X11 drawable from a
supported Compose/Skiko/JBR API or from a redesigned explicit surface contract.

## Current implementation baseline

The Objective 3.3 worktree snapshot has ten Kotlin `external` methods, not the
eleven methods in the earlier proposal. The old Phase 2 echo session has been
deleted; browser operations now belong to the engine ABI.

| Component | Current file | Lines | Responsibility |
|---|---:|---:|---|
| Engine/browser JNI bridge | `engine_jni_bridge.cc` | 846 | Exact library loading, ten JNI methods, callback contexts, global references, thread attachment, UTF conversion calls |
| JNI entry point | `jni_bridge.cc` | 35 | `JNI_OnLoad`, class lookup, dynamic registration |
| String conversion | `jni_string.cc` | 139 | Strict UTF-16/UTF-8 conversion |
| Bridge/string headers | two headers | 33 | Registration and conversion declarations |
| Total | five files | 1,053 | JNI-specific native boundary |

The ten current JVM methods are:

```text
loadEngineLibrary
engineAbiVersion
engineCreate
engineClose
liveEngineCount
browserCreate
browserNavigate
browserResize
browserClose
liveBrowserCount
```

The C engine exports eleven functions because `kweb_status_name` is also part
of the native ABI. FFM should bind the complete exported set rather than keep a
second status-name table on the JVM side.

This count is a baseline, not a promised deletion total. Phase 4 through Phase
7 will add DevTools, bridge, extension, UI, and packaging operations. Phase 8
must assess the then-current ABI instead of extrapolating from this snapshot.

## Corrections to the initial proposal

### Java release selection

FFM was finalized by JEP 454 in JDK 22. JDK 21 contains the third preview API,
not the final API. Java 23 is not an LTS release. As of this analysis, JDK 25 is
the current LTS and is the appropriate breaking baseline for a pre-1.0 Phase 8.
Using the JDK 21 preview would require preview flags and bind the project to a
superseded API, so it is rejected.

### FFM is not automatically compile-time safe

`FunctionDescriptor` and `MemoryLayout` make the native signature explicit,
but a raw `MethodHandle` can still be invoked with an incorrect Java method
type and fail at runtime. Static Java wrapper methods or pinned jextract output
provide the useful compile-time surface. Native `sizeof` and `offsetof`
conformance tests remain mandatory.

### Arena does not replace a JNI global reference one-for-one

An Arena controls native allocations, memory segments, library lookup scope,
and the lifetime of an upcall stub. It does not automatically define the
logical lifetime of a browser callback owner. KWebShell must retain the bound
method handle, callback owner, upcall segment, and shared Arena until the
native terminal callback has returned. Closing that Arena early is equivalent
to exposing a dangling function pointer.

### Native-thread callbacks still require design work

FFM performs the JVM/native transition for an upcall, so application code no
longer calls `AttachCurrentThread` or `DetachCurrentThread`. That does not make
callbacks thread-agnostic. CEF may call from UI, IO, and other native threads;
the upcall target must use a shared Arena, copy transient event data during the
callback, serialize delivery onto the Kotlin dispatcher, and catch all Java
exceptions. A Java exception must never unwind through C or C++.

### Performance gains are unproven for KWebShell

JEP 454 does not promise a universal JNI-to-FFM speed multiplier. Microbenchmark
results depend on signature, method-handle warmup, critical-call eligibility,
memory access, and callback direction. KWebShell does not transfer rendered
frames across the JVM boundary; Chromium's native child renders directly. Its
normal boundary traffic consists mostly of lifecycle, navigation, title, load,
resize, DevTools, and bridge events.

FFM may reduce binding overhead, but it cannot be credited with a `2x`, `5x`,
or `10x` product performance improvement without project-specific evidence.
Maintainability and removal of JNI object/thread plumbing are the primary
expected benefits.

### jextract is not a stable build dependency yet

The OpenJDK jextract downloads are still labeled early access. KWebShell must
not download an unpinned latest tool or regenerate bindings differently on
each developer machine. The two acceptable implementation choices are:

1. Pin a specific JDK 25 jextract archive by platform, version, size, and hash;
   check generated Java sources into the repository; regenerate them only via
   an explicit verification task.
2. Hand-write the small binding layer and verify every layout, offset, symbol,
   and calling convention against a native C conformance executable.

The choice must be made from measured generated-code quality when Phase 8
starts. The current ABI is small enough that a hand-written, generated-style
Java layer may be simpler than making an early-access generator part of every
build.

## Feasibility by subsystem

| Subsystem | Feasibility | Required treatment |
|---|---|---|
| C ABI downcalls | High | `Linker.nativeLinker`, exact descriptors, static Java wrappers |
| Exact engine loading | High | `SymbolLookup.libraryLookup(path, arena)` plus existing explicit CEF platform startup |
| Engine/browser structs | High | Generated or verified platform layouts; never hard-coded offsets without conformance |
| UTF-8 input | High | Allocate exact UTF-8 bytes and pass pointer plus byte count; no NUL reliance |
| UTF-8 callback payload | High | Reinterpret only for declared size, validate, and copy before callback return |
| Native callbacks | Medium-high | Shared Arena, strong owner retention, exception containment, terminal ordering |
| Concurrent close | Medium-high | Close Arena only after terminal callback and dispatcher drain |
| Error propagation | High | Keep integer status ABI and typed Kotlin mapping |
| AWT native parent | Blocked | FFM cannot directly turn a `Component` object into JAWT `JNIEnv*`/`jobject` inputs |
| Windows/macOS/Linux ABI | High | Per-target CI for pointer size, alignment, calling convention, loader behavior |
| Kotlin source ergonomics | High | Put low-level FFM in Java; retain an idiomatic internal Kotlin facade |

## Target architecture

```text
Compose Desktop / Kotlin lifecycle
               |
               v
      internal Kotlin facade
               |
               v
  small Java 25 FFM binding layer
     | downcalls       ^ upcalls
     v                 |
 versioned kweb C ABI and callbacks
               |
               v
     C++ CEF/Chromium engine
```

Low-level FFM should be Java rather than Kotlin for three reasons:

- jextract emits Java;
- Java expresses `MethodHandle`, `MemoryLayout`, and exact carrier types
  directly;
- Kotlin should continue to own lifecycle state, coroutines, dispatcher
  serialization, and typed errors rather than native layouts.

The Java layer must remain internal. No `MemorySegment`, `Arena`,
`MethodHandle`, raw address, or generated binding type crosses into `kweb-core`
or the public Compose API.

## Library loading and native access

FFM downcall and upcall operations are restricted native-access operations.
Packaged launchers must explicitly grant native access to KWebShell's named
module, for example through the equivalent of:

```text
--enable-native-access=io.github.kingsword09.kwebshell.desktop
```

Classpath development may require `ALL-UNNAMED`, but production packaging
should use a named module so the permission is narrow. The launcher, Gradle
integration tests, and packaged applications must all exercise the exact
production grant. KWebShell must detect a missing grant at startup and report
an actionable typed error; it must not switch to JNI.

`System.loadLibrary` and ambient loader search remain prohibited. The FFM layer
must use a canonical absolute engine path and an Arena-scoped library lookup.
CEF runtime provenance, framework loading on macOS, `XInitThreads` on Linux,
and runtime/header version checks remain responsibilities of the existing
engine/platform C ABI.

## Memory and callback ownership

Each live engine/browser callback domain requires an explicit owner containing:

```text
shared Arena
bound Java MethodHandle
upcall MemorySegment
callback dispatcher reference
native opaque handle
terminal-event latch/state
```

Required ordering is:

```text
create shared Arena
  -> create callback owner and upcall stub
  -> allocate/fill configuration
  -> native create
  -> receive and copy events
  -> request native close
  -> receive terminal event
  -> return from terminal upcall
  -> drain JVM callback dispatcher
  -> close Arena and library scope
```

An `Arena.ofConfined()` is unsuitable for callback state that native CEF
threads may access. An automatic Arena is also unsuitable because garbage
collection is not a deterministic browser shutdown protocol. A shared Arena
with explicit close after the terminal event is required.

The upcall target must catch `Throwable`, record a typed callback failure, and
return normally to native code. It must not close its own Arena while executing
inside that Arena's upcall stub. Terminal cleanup therefore happens after the
callback has returned, on the JVM owner/dispatcher path.

## Structure layout and calling conventions

The current C ABI uses:

- fixed-width `uint32_t`, `int32_t`, and `uint64_t` fields;
- `size_t`, `uintptr_t`, pointers, and function pointers;
- explicitly sized structures with an ABI version;
- `__cdecl` on Windows and the platform C convention elsewhere.

All currently advertised desktop targets are 64-bit, but the binding may not
assume that `size_t`, pointer alignment, padding, or aggregate layout is the
same merely because values are eight bytes. CI must compare Java layouts with a
C program that reports or asserts:

```text
sizeof and alignof every ABI structure
offsetof every structure field
pointer, size_t, and uintptr_t size
callback and function calling convention
exact exported symbol set
```

Hand-written numeric offsets without these tests are rejected. Unsupported
targets fail at binding initialization with platform and architecture details.

## The AWT/JAWT blocker

The current `browserCreate` JNI method accepts a Java `Component`. Native code
uses JAWT to resolve:

- the Canvas `HWND` on Windows;
- the X11 drawable on Linux;
- the JAWT surface layer and exact AppKit `NSWindow` on macOS.

JAWT functions require JNI values. FFM has no supported operation that converts
an arbitrary Java object into a stable `jobject` or exposes the current
`JNIEnv*`. Calling JAWT through FFM therefore does not remove the JNI
dependency.

Phase 8 may proceed only after one of these supported designs is proven on all
three platforms:

1. Compose/Skiko/JBR exposes the exact native parent handle through a supported,
   versioned API that KWebShell can pass as `uintptr_t` to the C ABI.
2. KWebShell changes its Compose host ownership so the native parent is created
   through the C ABI and integrated without overlays, hidden top-level windows,
   reflection, or private JDK APIs.

A residual JNI/JAWT shim, reflective `sun.awt` access, scanning window trees by
title/order, or creating an overlay window does not satisfy a pure FFM
migration. If neither supported design exists, Phase 8 is blocked and JNI
remains the declared implementation until the constraint changes.

## Migration sequence

Phase 8 is split into complete internal objectives. None introduces a public
stub or a selectable backend.

### Objective 8.1: Freeze and prove the C ABI for FFM

- Inventory the final post-Phase-7 exported ABI.
- Add cross-platform layout and calling-convention conformance artifacts.
- Resolve the raw native-parent design through a supported API.
- Build a non-production benchmark harness that compares the current JNI
  boundary with FFM using the same native test library.
- Record measurements and a go/no-go decision. This objective changes no
  production backend.

### Objective 8.2: Perform the breaking JDK 25 and FFM replacement

- Upgrade Gradle toolchains, bytecode target, CI, packaging, and documentation
  to JDK 25 LTS in one change.
- Add the Java FFM binding and Kotlin facade for the complete ABI.
- Replace callback/global-reference ownership with explicit shared-Arena
  ownership.
- Run all real CEF and cross-platform tests through FFM.
- Delete the JNI/JAWT library, native methods, conversion sources, packaging,
  and tests before merging.

No deprecation period or runtime backend switch is added because KWebShell is
pre-1.0 and its engineering rules prefer a coherent breaking contract over a
dual implementation.

## Verification matrix

The minimum mandatory matrix is:

| Target | Runtime | Native evidence |
|---|---|---|
| macOS arm64 | JDK 25 | AppKit parent, Metal/ANGLE browser, upcalls, clean close |
| macOS x64 | JDK 25 | Layout/build/package plus available real-browser runner |
| Windows x64 | JDK 25 | `__cdecl`, `HWND` parent, callback threads, package launch |
| Linux x64 | JDK 25 under Xvfb | X11 parent, callback threads, package launch |

Tests include:

- exact library and symbol lookup;
- ABI version and structure layout mismatch failures;
- valid and malformed UTF-8 in both directions;
- engine/browser creation, navigation, resize, and close;
- callbacks from each CEF thread category used by the product;
- listener exceptions and callback dispatch rejection;
- concurrent commands and close;
- terminal callback followed by Arena closure;
- 1,000 complete lifecycle repetitions with native memory observation;
- missing `--enable-native-access` diagnostics;
- packaged launcher verification, not only Gradle test execution.

The existing real Chromium tests remain the behavioral oracle. FFM is an
interop replacement, not permission to weaken browser, Profile, GPU, DevTools,
extension, or shutdown coverage.

## Benchmark plan

The benchmark compares like-for-like signatures after warmup:

- zero-argument integer downcall (`abiVersion`);
- handle plus integer downcall (`resize` validation fixture);
- UTF-8 downcall at small, medium, and maximum accepted payload sizes;
- fixed-size lifecycle upcall;
- variable UTF-8 browser-event upcall;
- create/close ownership cycle;
- complete engine/browser startup, navigation, and shutdown.

Report median, p95, allocation rate, native memory, and variance. Separate
microbenchmark results from end-to-end browser results. FFM adoption does not
require a fabricated speedup: equal end-to-end performance is acceptable if it
removes JNI complexity without regressing startup, callback latency, memory, or
stability beyond thresholds established by Objective 8.1.

## Cost and expected benefit

The initial 9-14 week estimate overstates work that the stable C ABI has
already completed and understates the native-parent risk. A realistic estimate
must be conditional:

| Work | Estimate | Main risk |
|---|---:|---|
| ABI/layout proof and benchmark harness | 3-5 days | Per-platform layout/tooling |
| Supported raw-parent proof | 3-10 days or blocked | Compose/Skiko/JBR API availability |
| JDK 25/toolchain/package upgrade | 2-4 days | Kotlin/Gradle/plugin compatibility |
| Complete FFM binding and ownership | 5-8 days | Upcall lifetime and exception handling |
| macOS integration and stress verification | 3-5 days | AppKit/CEF callback ordering |
| Windows/Linux CI fixes | 3-7 days | Calling convention and window handles |
| Documentation and deletion audit | 1-2 days | Residual JNI artifacts |

If the raw-parent gate is solved, the focused migration is approximately three
to six engineering weeks for one experienced implementer, including CI
stabilization. If it is not solved, there is no honest completion estimate:
the migration remains blocked rather than compromising the native-child
contract.

Expected benefits are:

- deletion of JNI registration, global references, manual thread attachment,
  and duplicated JVM/native string plumbing;
- one C ABI consumed consistently by JVM FFM and possible future Kotlin/Native
  clients;
- explicit native memory scopes and generated or verified structure layouts;
- alignment with the supported Java native-interoperability direction.

Claims such as “90% fewer bugs,” “80% less code,” or a fixed multi-year ROI are
not accepted without repository history and measured maintenance data.

## Final recommendation

Keep JNI through Phase 7 while the product ABI and native surface contracts are
still changing. Preserve the small versioned C ABI and avoid adding new JNI
object-oriented APIs; this keeps the future FFM boundary mechanical.

After Phase 7, execute Objective 8.1. Proceed to Objective 8.2 only when all of
the following are true:

- JDK 25 is accepted as the desktop minimum;
- Kotlin, Gradle, Compose, packaging, and CI pass on JDK 25;
- an exact supported raw-parent mechanism works on Windows, macOS, and Linux;
- FFM upcall stress and Arena lifetime tests pass;
- the full real-CEF test suite can run without JNI-specific hooks.

This makes FFM the intended long-term direction without hiding the one
constraint that can prevent a complete migration.

## References

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 442: Foreign Function & Memory API, third preview](https://openjdk.org/jeps/442)
- [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472)
- [JDK 25 Linker API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html)
- [OpenJDK jextract early-access builds](https://jdk.java.net/jextract/)
