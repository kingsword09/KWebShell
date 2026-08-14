# ADR 0011: Profile-scoped extension package store

- Status: Accepted
- Date: 2026-08-14

## Context

The Chromium extension runtime must load packages from a stable directory that
cannot change underneath an asynchronous install or reload. User-selected
unpacked directories are mutable, and CRX3 is a signed container rather than a
directory Chromium can load through the planned Profile adapter. Copying files
and immediately returning success would also leave ambiguous state when the
process exits between filesystem preparation and Chromium activation.

Chromium's DevTools `Extensions` domain is not this boundary. In Chromium 151 it
targets `ProfileManager::GetLastUsedProfile()` rather than the CEF
`CefRequestContext`, supports only unpacked load/uninstall, and marks loaded
extensions `INSTALLED_VIA_CDP` for cleanup on restart. KWebShell therefore does
not use CDP, preference editing, or `--load-extension` as a product lifecycle
fallback.

## Decision

Each KWebShell Profile owns one dedicated managed store. Verified packages are
copied or extracted into same-filesystem staging, verified again, hashed as a
portable deterministic tree, and atomically moved to
`objects/<extension-id>/<version>/<digest>`. Objects are immutable by contract:
updates always create a new object and never modify a directory Chromium may be
using.

CRX3 extraction consumes the exact archive bytes whose developer proof was
verified. The store adds that verified public key to the managed
`manifest.json` with a canonical encoder that omits absent and default-valued
fields, then runs the unpacked verifier over the result. This preserves the
signed extension ID and a Chromium-valid manifest when Chromium later loads the
managed directory; no private key or path-derived identity is used.

A per-extension transaction journal separates filesystem preparation from
runtime truth:

```text
source -> same-filesystem staging -> verified object -> PREPARED journal
                                                      |
                                      Chromium success|failure
                                                      v
                                   commit active / abort journal
```

Install has no previous active object. Update requires a strictly newer version.
Reload requires the exact active digest. Uninstall records the active object but
does not remove its pointer before Chromium confirms uninstall. Active metadata
uses force-to-disk plus atomic rename; an implementation that cannot provide the
atomic move fails instead of degrading to copy/delete.

On open, the store removes abandoned staging paths without following links. A
journal is finalized automatically only when the active record already equals
its committed target (or is absent for uninstall). Every other journal remains
pending for Objective 5.4 to reconcile against Chromium. Garbage collection is
explicit, refuses pending transactions, and removes only inactive objects under
the validated store root. Opening also checks the shallow object layout and all
referenced object hashes; unreferenced object content is verified before garbage
collection. Metadata is bounded, strict UTF-8 JSON with no unknown or duplicate
keys, and its private serialized enums are converted explicitly to the Kotlin
model rather than defining the public package schema.

## Consequences

Filesystem state alone never claims that an extension is running. Objective 5.3
can be tested completely on all desktop filesystems while the package lifecycle
remains unpublished. Objective 5.4 must use a version-pinned CEF/Chromium adapter
for the matching Profile and commit or abort these transactions from real
runtime results.

The design intentionally retains ambiguous prepared objects after a crash. This
uses bounded disk space but preserves evidence and avoids an unsafe inferred
rollback. Once runtime reconciliation is complete, explicit garbage collection
removes every unreferenced verified object.
