# RFC 0001: RFC governance and capability evidence manifest

- Status: Implementing
- Priority: P0
- Owners: repository governance, CI, documentation
- Depends on: existing Phase 11 prerequisites
- Electron migration surface: all inventoried Electron and Node usage
- Target mapping: infrastructure, not an application adapter

## Objective

Make every future capability independently implementable and auditable. Publish
the RFC state machine, a machine-readable capability evidence manifest, and CI
validation that prevents documentation, runtime descriptors, migration rows, and
hosted evidence from disagreeing.

## Decisions

The evidence manifest MUST identify RFC ID/status, service and schema versions,
target triple, provider ID, CEF/Chromium identity, Electron fixture major,
artifact digests, test run IDs, and compatibility status. `Implemented` requires
three current hosted target records unless the RFC declares a platform-specific
key. Evidence expires when any bound contract or runtime digest changes.

The migration matrix remains conservative runtime code. The RFC catalog is the
planning source; CI MUST reject a supported matrix row that lacks an
`Implemented` RFC and matching evidence.

## Non-goals

This RFC does not publish an OS capability, infer support from source files,
waive platform tests, or let a stale successful run validate changed artifacts.

## Required deliverables

- Versioned JSON schema and deterministic validator for capability evidence.
- Gradle documentation check joining RFC metadata, descriptors, matrix rows,
  packaged schemas, and retained evidence.
- A command that reports `ready`, `blocked`, `stale`, or `platform-specific`
  with structured reasons.
- Contributor documentation and the template in this directory.

## Acceptance

1. Fixtures prove unknown fields, duplicate RFC IDs, missing targets, digest
   changes, stale runtime versions, and unsupported-to-supported promotion fail.
2. A delivered Phase 11 capability can be represented without hand-edited test
   output, and regeneration is byte-for-byte deterministic.
3. CI verifies documentation-only RFC proposals with `git diff --check` and the
   schema validator without running the native matrix.
4. Moving an RFC to `Implemented` without all required native evidence fails.
5. Reports contain no credentials, private paths, runner tokens, or native
   pointers.

## Evidence

CI retains the validated manifest, deterministic digest, joined matrix report,
and explicit stale/blocking diagnostics.
