# KWebShell

KWebShell is a Kotlin Multiplatform browser shell for Compose and Chromium. The project targets native, hardware-accelerated browser surfaces, persistent profiles, DevTools/CDP, typed host bridges, and Manifest V3 extensions on Windows, macOS, and Linux.

## Current Status

The repository contains the multiplatform build foundation, a native CEF host vertical slice, the verified CEF runtime catalog, and the internal Phase 2 C ABI/JNI lifecycle contract. The host uses the Chrome bootstrap with an explicit Alloy, windowed native child and reports fatal capability errors instead of selecting a fallback backend. Phase 2 deliberately exposes no public browser API: its navigation event means that a request crossed the ownership boundary, not that Chromium committed a navigation.

Current verification evidence is intentionally platform-specific:

- macOS arm64: local Apple M2 and hosted Apple Paravirtual ANGLE/Metal WebGL runs pass the real CEF, Alloy child, focus, mouse, native wheel, keyboard, resize, renderer/GPU failure, lifecycle, and bounded shutdown tests.
- Linux arm64 and x64: GCC 13 builds and links the real CEF runtime; GTK/X11 parent validation, root screen bounds, renderer/GPU failure, bounded shutdown, and the strict no-GPU contract pass on both architectures, including the hosted x64 Actions job. The available runners have no `/dev/dri`; positive Linux hardware rendering remains unverified and fails with `native.gpu.hardware-acceleration-unavailable`.
- Windows x64: MSVC 19.44 builds and links the complete real CEF host and all four hosted CTest contracts pass, including Win32 focus, DPI, screen bounds, input routing, renderer/GPU failure, and bounded shutdown. The hosted Microsoft Basic Render Driver is rejected explicitly; the `Hardware GPU Validation` workflow is the positive D3D11 gate for a self-hosted physical-GPU runner.

The Phase 2 contract is verified locally on macOS arm64 through a pure C consumer, native concurrency/lifetime tests, and a JVM that loads the real JNI shared library by absolute path. Windows and Linux support is accepted only when the same sources and real shared libraries pass the hosted matrix; there is no mock or alternate native backend in those jobs.

See [DESIGN_PLAN.md](DESIGN_PLAN.md) for architecture and delivery phases, [ADR 0003](docs/adr/0003-versioned-native-session-contract.md) for the native ownership contract, and [AGENTS.md](AGENTS.md) for the non-fallback implementation rules.

## Requirements

- JDK 21
- The checked-in Gradle wrapper

Native CEF development additionally requires CMake, Ninja, and the platform C++ toolchain described by the design plan.

## Verification

```shell
./gradlew check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary_151.3.16+gbe1e15d+chromium-151.0.7922.109_macosarm64_minimal
```

The root `check` task compiles every included module, runs Kotlin tests and native unit/GUI tests, and validates the pinned CEF runtime manifest. `cefRoot` must point to an extracted, checksum-verified CEF distribution for the current host; it is never inferred from another platform or replaced by a system WebView.

On a runner known to have no hardware GPU, use the explicit negative capability contract:

```shell
./gradlew check \
  -PcefRoot=/absolute/path/to/extracted/cef_binary \
  -PkwebExpectHardwareGpuUnavailable=true
```

This replaces only the positive hardware-rendering self-test with a strict test that requires exit code `71`, `native.gpu.hardware-acceleration-unavailable`, an Alloy native child, browser/renderer/GPU/utility processes, and complete shutdown. It fails if hardware rendering unexpectedly succeeds and is not evidence of GPU support.

The normal GitHub Actions matrix runs macOS arm64 with the positive Metal contract and runs Linux/Windows x64 with the explicit software-GPU rejection contract. Positive Linux and Windows hardware evidence must be run through [.github/workflows/gpu-validation.yml](.github/workflows/gpu-validation.yml) on self-hosted runners labeled `kwebshell-gpu`, with a Linux `/dev/dri/renderD128` node or a physical Windows GPU. A hosted Windows WARP device and a hosted Linux Xvfb display are intentionally never counted as hardware-rendering evidence.

To verify a downloaded CEF archive against its pinned size and SHA-1, provide both inputs explicitly:

```shell
./gradlew :kweb-runtime-pack:verifyCefRuntimeArtifact \
  -PkwebTarget=macos-arm64 \
  -PcefRuntimeArchive=/absolute/path/to/cef_binary.tar.bz2
```

The task fails on a missing file, target mismatch, size mismatch, checksum mismatch, or unsupported checksum algorithm. It never selects another runtime artifact.
