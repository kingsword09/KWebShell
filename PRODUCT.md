# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Inferred from the repository and explicit brief: Kotlin Multiplatform and
Compose Desktop host a pinned CEF/Chromium native child through JDK 25 FFM;
test workloads use locally served standards-based HTML, CSS, and JavaScript.

## Users

Confirmed by the project plan: Kotlin and Compose application developers who
need an Electron-class embedded Chromium surface on Windows, macOS, and Linux.
For the benchmark surface, the user is an engine maintainer evaluating whether
complex modern frontends remain correct, responsive, observable, and
reproducible across runtime revisions.

## Product Purpose

KWebShell provides a high-performance Compose-compatible Chromium shell with
persistent Profiles, DevTools/CDP, typed host bridges, and an eventual real
Manifest V3 runtime. Success means advertised capabilities are implemented by
the pinned engine, demonstrated with real artifacts, and verified separately
on every supported desktop platform.

## Positioning

KWebShell keeps Kotlin in ownership of product lifecycle and Compose UI while a
small versioned C ABI and JDK 25 FFM boundary host one native, GPU-accelerated
Chromium child. It does not substitute a system WebView, OSR renderer, reduced
extension runtime, or hidden fallback backend.

## Operating Context

Maintainers work with JDK 25, Gradle, CMake/Ninja, platform C++ toolchains,
pinned CEF artifacts, GitHub Actions, real Chromium Profiles, CDP traces, raw
benchmark samples, and platform-specific GPU evidence. Pull requests are
squash-merged only after the relevant macOS, Windows, and Linux gates pass.

## Capabilities and Constraints

- Confirmed: windowed native child rendering, persistent Profiles, navigation,
  resize, DevTools, explicit loopback CDP, typed lifecycle errors, and public
  Engine/Profile/Page contracts.
- Confirmed: breaking changes are allowed before 1.0; fallback and placeholder
  behavior are prohibited; objectives require complete implementation and
  verification before publication.
- Confirmed: examples and benchmarks must use the public facade rather than
  CEF, FFM, or opaque native handles.
- Inferred for the application benchmark from the approved Phase 10 plan: a
  deterministic in-repository workload may model LobeHub-class complexity but
  must never be represented as LobeHub or as third-party product evidence.

## Brand Commitments

Confirmed name: KWebShell. Voice is technical, evidence-led, explicit about
unsupported capability, and avoids unverified performance or compatibility
claims.

## Evidence on Hand

- Architecture, platform gates, and delivery rules: `DESIGN_PLAN.md` and
  `AGENTS.md`.
- Runtime provenance and source manifests: `runtime/`.
- Real CEF/FFM/Profile/DevTools/MV3 conformance tests: `kweb-cef-native/tests/`
  and `kweb-desktop/src/test/`.
- HTML5 engine evidence client: `kweb-example-html5-lab/`.
- No third-party customer claims, LobeHub benchmark results, pricing, or
  compatibility endorsements are available and none may be fabricated.

## Product Principles

1. Publish evidence, not inferred capability.
2. Keep one explicit production backend and fail fast when its contract cannot
   be met.
3. Preserve Kotlin ownership while isolating CEF and FFM behind narrow layers.
4. Treat reproducibility, raw samples, and platform provenance as product
   features.
5. Optimize measurable user workloads without hiding correctness regressions.

## Accessibility & Inclusion

Confirmed by Phase 10: example workloads expose semantic structure and retain
accessibility-tree evidence. Keyboard focus, contrast, reduced motion, and
platform input behavior must remain testable alongside performance.
