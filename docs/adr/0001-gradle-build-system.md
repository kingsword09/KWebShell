# ADR 0001: Use Gradle for the initial build system

- Status: Accepted
- Date: 2026-08-10

## Context

KWebShell must build Kotlin Multiplatform contracts, JVM Compose integration, generated Kotlin/TypeScript bindings, JNI libraries, CMake projects, and versioned CEF runtime packs. The first browser vertical slice uses Compose Desktop on the JVM and a C++ CEF host behind JNI.

Kotlin Toolchain provides a simpler declarative Kotlin build and supports Kotlin/Native cinterop, but its published documentation currently describes the tool as alpha and subject to change. It also does not replace CEF's GN/Ninja/CMake and platform C++ toolchains.

## Decision

Use the checked-in Gradle wrapper as the repository build entry point for the initial implementation.

- Gradle is pinned to 9.7.0.
- Kotlin is pinned to the latest stable 2.4 line available when this decision was accepted, 2.4.10.
- JVM compilation and tests require JDK 21.
- Dependencies are declared through the version catalog and resolved only from repositories declared in `settings.gradle.kts`.
- Native C++ builds remain explicit CMake/GN/Ninja tasks invoked by Gradle; they are not translated into Kotlin compilation tasks.
- Kotlin Toolchain will be evaluated only after the JNI and C ABI contracts are stable and the same native artifacts can be consumed without changing their lifecycle or packaging behavior.

## Consequences

- Clean checkouts have one reproducible build command on all supported hosts.
- CEF and Kotlin versions can move independently through explicit, reviewable changes.
- The project does not depend on undocumented Kotlin Toolchain internals for native provisioning.
- Adopting Kotlin Toolchain later is a deliberate breaking build change, with all CI, packaging, and contributor documentation updated in the same objective.

## Rejected alternatives

### Kotlin Toolchain as the initial build

Rejected for the initial vertical slice because its alpha lifecycle and evolving plugin surface would add build-system risk before the CEF ABI is stable.

### Platform-specific build entry points

Rejected because separate shell, Xcode, and Visual Studio entry points would duplicate dependency and test orchestration and make cross-platform verification inconsistent.
