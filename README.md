# KWebShell

KWebShell is a Kotlin Multiplatform browser shell for Compose and Chromium. The project targets native, hardware-accelerated browser surfaces, persistent profiles, DevTools/CDP, typed host bridges, and Manifest V3 extensions on Windows, macOS, and Linux.

## Current Status

The repository currently contains the multiplatform build foundation, platform target contracts, and the verified CEF runtime catalog. Browser APIs are not published until the native CEF vertical slice and its cross-platform tests are complete.

See [DESIGN_PLAN.md](DESIGN_PLAN.md) for architecture and delivery phases, and [AGENTS.md](AGENTS.md) for the non-fallback implementation rules.

## Requirements

- JDK 21
- The checked-in Gradle wrapper

Native CEF development additionally requires CMake, Ninja, and the platform C++ toolchain described by the design plan.

## Verification

```shell
./gradlew check
```

The root `check` task compiles every included module, runs all tests, and validates the pinned CEF runtime manifest.

To verify a downloaded CEF archive against its pinned size and SHA-1, provide both inputs explicitly:

```shell
./gradlew :kweb-runtime-pack:verifyCefRuntimeArtifact \
  -PkwebTarget=macos-arm64 \
  -PcefRuntimeArchive=/absolute/path/to/cef_binary.tar.bz2
```

The task fails on a missing file, target mismatch, size mismatch, checksum mismatch, or unsupported checksum algorithm. It never selects another runtime artifact.
