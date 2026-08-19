# JNI to FFM Migration Feasibility Analysis

- Status: Objective 8.1 evidence complete; Objective 8.2 production replacement
  passes local macOS arm64 acceptance and awaits hosted merge gates
- Date: 2026-08-18
- Target runtime: JDK 25 LTS

## Executive decision

KWebShell's JVM/native binding now uses the Java Foreign Function and Memory
API over the existing small, versioned C ABI with opaque integer handles.
Objective 8.1 froze that ABI, proved a supported Compose parent handle, and
measured a test-only JNI/FFM boundary. Objective 8.2 applies the resulting
one-time breaking replacement: every JVM module targets JDK 25, production
downcalls and upcalls use FFM, and the JNI/JAWT implementation and payload are
deleted. There is no backend selector or compatibility path.

The critical parent-handle gate is now experimentally resolved through the
documented `ComposeWindow.windowHandle` API in Compose Desktop 1.11.1 (Skiko
0.144.6). It returns the top-level `HWND`, X11 `Window`, or macOS `NSWindow`;
Objective 8.1 validates the value in platform native code on all three hosted
targets. KWebShell's FFM probe never calls JAWT, reflects into the JDK, scans
window trees, or creates an overlay.

## Current implementation baseline

The production boundary remains ABI version 6: exactly 18 C functions, eight
public structures including `kweb_string_view`, and four callback signatures.
The internal Java 25 layer binds every export from canonical absolute paths,
performs strict UTF-8 conversion, owns shared-Arena upcall stubs, and presents a
primitive/SAM-only facade to Kotlin. `NativeBindings` is ordinary Kotlin code;
it contains no JVM `native` methods and does not expose FFM types.

Compose, coroutines, lifecycle state, Profiles, Bridge, DevTools, and MV3 remain
Kotlin-owned. Java is restricted to the low-level ABI binding where
`MemoryLayout`, `MemorySegment`, and exact `MethodHandle` carrier types are most
direct. The deleted JNI implementation is retained only in repository history
and Objective 8.1 measurements.

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
logical lifetime of a browser callback owner. KWebShell retains the bound
method handle, callback owner, upcall segment, and shared Arena until the
native terminal callback has returned. Closing that Arena early is equivalent
to exposing a dangling function pointer.

### Native-thread callbacks require explicit ownership

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

### jextract is not a stable build dependency

The OpenJDK jextract downloads are still labeled early access. KWebShell must
not download an unpinned latest tool or regenerate bindings differently on
each developer machine. The two acceptable implementation choices are:

1. Pin a specific JDK 25 jextract archive by platform, version, size, and hash;
   check generated Java sources into the repository; regenerate them only via
   an explicit verification task.
2. Hand-write the small binding layer and verify every layout, offset, symbol,
   and calling convention against a native C conformance executable.

Objective 8.2 selects the second option. The checked-in, generated-style Java
layer is small enough to review directly, while native conformance tests remain
the authority for every layout, offset, symbol, and callback signature.

## Feasibility by subsystem

| Subsystem | Feasibility | Required treatment |
|---|---|---|
| C ABI downcalls | High | `Linker.nativeLinker`, exact descriptors, static Java wrappers |
| Exact engine loading | High | Arena-scoped `SymbolLookup.libraryLookup(path, arena)`; Windows first uses FFM `LoadLibraryExW` with exact paths and dependency-safe search flags |
| Engine/browser structs | High | Generated or verified platform layouts; never hard-coded offsets without conformance |
| UTF-8 input | High | Allocate exact UTF-8 bytes and pass pointer plus byte count; no NUL reliance |
| UTF-8 callback payload | High | Reinterpret only for declared size, validate, and copy before callback return |
| Native callbacks | Medium-high | Shared Arena, strong owner retention, exception containment, terminal ordering |
| Concurrent close | Medium-high | Close Arena only after terminal callback and dispatcher drain |
| Error propagation | High | Keep integer status ABI and typed Kotlin mapping |
| Compose native parent | Proven on hosted targets | Consume public `ComposeWindow.windowHandle`; validate exact OS type before browser creation |
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
On Windows, JDK 25's path lookup delegates to plain `LoadLibrary`, whose
dependent-library search does not include the target DLL directory. The FFM
layer therefore preloads the exact CEF and engine paths through
`LoadLibraryExW` with `LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR` and
`LOAD_LIBRARY_SEARCH_DEFAULT_DIRS`, opens both Arena-scoped lookups, then
releases only the temporary preload references. It does not mutate `PATH` or
the process-wide DLL directory and does not fall back to a name lookup.
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

## The Compose parent-handle gate

The removed `browserCreate` JNI method accepted a Java `Component`. Native code
used JAWT to resolve:

- the Canvas `HWND` on Windows;
- the X11 drawable on Linux;
- the JAWT surface layer and exact AppKit `NSWindow` on macOS.

JAWT functions require JNI values. FFM has no supported operation that converts
an arbitrary Java object into a stable `jobject` or exposes the current
`JNIEnv*`, so calling JAWT through FFM remains rejected.

Compose Desktop 1.11.1 resolves the gate through the documented public
`ComposeWindow.windowHandle` property. Its contract returns `HWND` on Windows,
an X11 `Window` on Linux, and `NSWindow` on macOS. `kweb-interop-probe` creates
a real visible Compose window on the AWT event-dispatch thread and passes that
value to a platform-native validator. Hosted macOS arm64 confirms a nonzero
`NSWindow` with a content view, Windows x64 confirms a valid top-level `HWND`,
and Linux x64 under Xvfb confirms a valid X11 `Window`; all three dispose the
peer cleanly.

Objective 8.2 passes this raw value as `kweb_browser_config.native_parent`.
It retains no JNI/JAWT shim and does not reflect into `sun.awt`, infer a window
by title/order, or create a hidden or overlay top-level window.

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

Local macOS arm64 evidence uses Temurin 25.0.4 and the real ABI version 6
engine built against the pinned CEF runtime. Native CTest proves all compiler
layouts and callback conventions; the Java probe matches all eight layouts,
resolves and binds all 18 engine exports, exercises 1 MiB and malformed UTF-8,
shared-Arena callbacks from a native-created thread, callback exception
containment, and validates a real `ComposeWindow.windowHandle`.
The unchanged production JNI target still resolves its headers, `libjawt`, and
`libjvm` from the exact JDK 21 Gradle toolchain; CMake clears and rejects stale
FindJNI paths from a different daemon JDK.

The benchmark fixes five warmup and 15 measured samples and uses linear
interpolation for percentiles. The initialization-only zero-argument query has
a 100 ns FFM p95 limit, avoiding an unstable ratio against a roughly 3 ns JNI
denominator. The high-frequency FFM p95 limit remains `5x` JNI; owner creation
additionally has a `1000x` ratio and 5 ms absolute limit. Every FFM operation
is limited to 1 MiB of JVM allocation per invocation and native live bytes must
be zero before and after measurement. The maximum Unicode case contains
exactly 1 MiB of valid UTF-8. The latest local macOS Gradle-run measurements
are:

| Operation | JNI p95 ns | FFM p95 ns | FFM/JNI | FFM p95 JVM bytes/op |
|---|---:|---:|---:|---:|
| Zero-argument ABI-version downcall | 4.446 | 19.606 | 4.410 | 0.000 |
| Integer downcall | 8.626 | 17.285 | 2.004 | 0.000 |
| Small Unicode downcall | 283.114 | 295.982 | 1.045 | 408.000 |
| Medium Unicode downcall | 5,769.488 | 6,057.429 | 1.050 | 408.000 |
| Maximum Unicode downcall | 4,011,693.750 | 3,875,540.638 | 0.966 | 408.000 |
| Fixed upcall | 173.433 | 65.434 | 0.377 | 4.986 |
| Unicode upcall | 1,693.654 | 2,808.146 | 1.658 | 7,459.750 |
| Owner lifecycle | 403.035 | 29,146.377 | 72.317 | 2,392.000 |

Every threshold passes and native live bytes return to zero, so the local
Objective 8.1 decision is **GO**. These are boundary microbenchmarks, not a
claim that the browser is faster. Objective 8.2 still requires real CEF
startup, navigation, callback stress, memory, and shutdown measurements on all
advertised targets before deleting JNI.

GitHub-hosted verification reproduced the same five warmup samples, 15
measured samples, population variance, allocation ceilings, and zero native
live-byte contract on macOS arm64, Windows x64, and Linux x64. All three hosted
reports returned **GO**. The measurements below are immutable evidence from CI
run `32036292736`; ratios are calculated from the recorded p95 values.

Hosted Linux x64 on Temurin 25.0.4:

| Operation | JNI p95 ns | FFM p95 ns | FFM/JNI | FFM p95 JVM bytes/op |
|---|---:|---:|---:|---:|
| Zero-argument ABI-version downcall | 5.302 | 6.947 | 1.310 | 0.000 |
| Integer downcall | 5.330 | 9.113 | 1.710 | 0.000 |
| Small Unicode downcall | 136.579 | 334.241 | 2.447 | 408.000 |
| Medium Unicode downcall | 4,132.209 | 5,443.393 | 1.317 | 408.000 |
| Maximum Unicode downcall | 2,717,776.338 | 3,304,086.850 | 1.216 | 408.000 |
| Fixed upcall | 82.241 | 42.769 | 0.520 | 10.993 |
| Unicode upcall | 2,071.926 | 5,519.828 | 2.664 | 7,459.750 |
| Owner lifecycle | 372.012 | 76,231.344 | 204.916 | 2,392.528 |

Hosted macOS arm64 on Temurin 25.0.3:

| Operation | JNI p95 ns | FFM p95 ns | FFM/JNI | FFM p95 JVM bytes/op |
|---|---:|---:|---:|---:|
| Zero-argument ABI-version downcall | 4.937 | 15.992 | 3.239 | 0.000 |
| Integer downcall | 7.809 | 15.564 | 1.993 | 0.000 |
| Small Unicode downcall | 190.739 | 296.012 | 1.552 | 384.000 |
| Medium Unicode downcall | 7,688.404 | 8,159.646 | 1.061 | 384.000 |
| Maximum Unicode downcall | 5,761,166.075 | 5,576,118.763 | 0.968 | 384.000 |
| Fixed upcall | 97.298 | 55.779 | 0.573 | 40.665 |
| Unicode upcall | 1,554.500 | 3,160.038 | 2.033 | 7,459.750 |
| Owner lifecycle | 239.001 | 24,196.751 | 101.241 | 2,392.000 |

Hosted Windows x64 on Temurin 25.0.4:

| Operation | JNI p95 ns | FFM p95 ns | FFM/JNI | FFM p95 JVM bytes/op |
|---|---:|---:|---:|---:|
| Zero-argument ABI-version downcall | 7.909 | 9.178 | 1.160 | 0.000 |
| Integer downcall | 10.255 | 13.615 | 1.328 | 0.000 |
| Small Unicode downcall | 1,056.128 | 642.692 | 0.609 | 408.000 |
| Medium Unicode downcall | 31,014.270 | 12,165.610 | 0.392 | 408.000 |
| Maximum Unicode downcall | 21,466,688.750 | 8,865,296.250 | 0.413 | 408.000 |
| Fixed upcall | 130.151 | 68.684 | 0.528 | 10.626 |
| Unicode upcall | 21,616.240 | 9,028.500 | 0.418 | 7,459.750 |
| Owner lifecycle | 485.900 | 94,345.100 | 194.166 | 2,392.528 |

Each hosted root `check` also passed the native ABI and real MV3 Chromium
contracts, isolated JVM/CEF integration, runtime payload verification, all 18
FFM engine bindings, native-thread upcalls, and the platform Compose parent
proof. The hosted runtime ZIPs likewise contain no interop probe or JDK 25
probe artifact.

The final macOS arm64 root `check` passed all 11 sequential native CTests, the
isolated JVM/CEF engine integration, runtime payload build and verification,
the Compose parent proof, and every interop task in 6 minutes 43 seconds. The
verified runtime ZIP contains no interop probe, Compose/Skiko probe dependency,
or JDK 25 artifact.

### Objective 8.2: Perform the breaking JDK 25 and FFM replacement

- Gradle toolchains, bytecode, CI, launchers, and documentation require JDK 25.
- The complete ABI is implemented by an internal Java FFM binding and Kotlin
  facade.
- Engine, browser, bridge, and extension upcalls use explicit shared-Arena
  owners released only after terminal callback quiescence.
- Real CEF integration rejects a stale handle from inside the terminal upcall,
  then runs a 1,000-browser navigation/close race contract with stale-handle
  and zero-owner assertions.
- The JNI/JAWT library, native methods, conversion sources, packaging, and
  comparison-only probe code are deleted.

No deprecation period or runtime backend switch is added because KWebShell is
pre-1.0 and its engineering rules prefer a coherent breaking contract over a
dual implementation.

Local macOS arm64 acceptance uses OpenJDK 25.0.4 and the catalog-pinned CEF
151.3.16 archive. The archive size, SHA-1, and bzip2 stream pass the repository
verifier. All 11 native CTests pass, followed by the eight-layout FFM probe,
all 18 production symbol bindings, native-thread upcalls, and the real
`ComposeWindow.windowHandle` validator. The named-module JVM/CEF suite passes
CDP, DevTools, Profile, Bridge, callback-failure, and process-singleton paths,
then completes 1,000 real browser navigation/close races with contiguous
events, stale-handle rejection, no callbacks after close, and zero native and
FFM owners. The fingerprint-verified macOS custom CEF runtime also passes MV3
install, update, reload, cancellation reconciliation, hard-crash recovery,
Profile isolation, and uninstall through the same production FFM layer.

Hosted macOS arm64, Windows x64, and Linux x64 jobs remain merge gates. Local
macOS evidence is not used to infer another platform's loader, calling
convention, or native-parent behavior.

The macOS job also requires the upstream
`MACH_PORT_RENDEZVOUS_PEER_VALDATION=0` unsigned-embedder policy marker exactly
once before `OnContextInitialized`, plus a marker proving that the browser
process disabled both Mach-port peer-validation features. A fourth marker
proves that `GatherProcessRequirementMetrics` is disabled: Chromium schedules
that diagnostic Security.framework validation as `CONTINUE_ON_SHUTDOWN`, while
the browser process in this integration is the JVM executable rather than the
CEF helper app. Inability to install any policy is a terminal
platform-initialization error. Before any runtime test, the macOS build also
recreates the CEF framework hierarchy from a clean directory and rejects
recursive, missing, or non-canonical framework links. Distribution code
signing remains outside the native compiler task because it does not change the
JVM browser-process identity.

The Windows 1,000-lifecycle contract requires every native child to initiate
close through `CefBrowserHost`. Chromium is a direct child of the Compose
`HWND`. `DoClose` returns `true` to accept KWebShell's custom destruction path,
clears pointer capture and hover tracking, drains queued pointer messages from
the child subtree, disables and hides the child,
synchronously confirms focus has left the inner `Chrome_WidgetWin`, drains
eight CEF UI queue turns, and then destroys the Chromium child. Returning
`false` would make CEF 151 send the notification to
the top-level Compose ancestor; posting `WM_CLOSE` again after returning `true` re-enters
`TryCloseBrowser` while CEF has reset its destruction state to `NONE`, so the
application-owned path must destroy the child directly. After every lifecycle,
the test
checks the same Compose `HWND` is visible and receives an OS-generated click;
an Aura destroyed-window diagnostic is fatal even if the child exits zero.

`OnBeforeClose` is CEF's last client callback, but CEF still completes browser
observer, `browser_info`, platform delegate, and browser-host destruction after
that callback returns. KWebShell therefore waits for CEF to release its final
`SessionClient` owner, then retains the empty surface across three CEF UI
quiescence tasks. It releases the surface, removes the registry entry, and
publishes `CLOSED` only after that barrier. Kotlin cannot begin the next lifecycle until
the post-callback quiescence barrier has completed.

Profile context initialization is also part of the upcall lifetime boundary.
The shared context cache holds weak, cancellable waiters; a browser closed while
waiting is removed before `CLOSED`, and later context success or failure visits
only live sessions. The Windows real-CEF suite closes a concurrent waiter burst,
releases every FFM owner, and then completes the shared context with a survivor.

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

## Benchmark interpretation

Objective 8.1 recorded the like-for-like JNI/FFM warmup, median, p95,
allocation, native-memory, and variance evidence above before deletion. Its
comparison harness is intentionally not shipped as a second backend in
Objective 8.2. Product conclusions remain grounded in the real browser suite
and 1,000-lifecycle stability run; boundary microbenchmarks are not presented
as an end-to-end Chromium speed multiplier.

## Original cost estimate and expected benefit

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

Use the JDK 25 FFM implementation as the only JVM/native backend. The stable C
ABI keeps the low-level Java layer small while Kotlin continues to own product
behavior. Do not restore JNI for platform defects; a failing target remains
unsupported until the same FFM, native-child, Profile, DevTools, and MV3
contracts pass there.

Objective 8.1 supplies immutable layout, parent, callback, and performance
evidence for macOS arm64, Windows x64, and Linux x64. Objective 8.2 removes the
old boundary and makes the corresponding real-CEF suite, 1,000-lifecycle stress
contract, named-module native-access test, deletion audit, and engine-only
runtime payload mandatory merge gates.

## References

- [JEP 454: Foreign Function & Memory API](https://openjdk.org/jeps/454)
- [JEP 442: Foreign Function & Memory API, third preview](https://openjdk.org/jeps/442)
- [JEP 472: Prepare to Restrict the Use of JNI](https://openjdk.org/jeps/472)
- [JDK 25 Linker API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.html)
- [OpenJDK jextract early-access builds](https://jdk.java.net/jextract/)
- [Chromium 151 Mach-port rendezvous peer-validation policy](https://chromium.googlesource.com/chromium/src/+/refs/branch-heads/7922/base/apple/mach_port_rendezvous_mac.cc)
- [CEF windowed browser close lifecycle](https://github.com/chromiumembedded/cef/blob/master/include/cef_life_span_handler.h)
